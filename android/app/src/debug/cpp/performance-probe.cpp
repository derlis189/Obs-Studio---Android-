// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-session-gate.h"
#include "android-multistream.h"
#include <obs.h>
#include <obs-audio-controls.h>
#include <util/platform.h>
#include <jni.h>
#include <atomic>
#include <mutex>
#include <nlohmann/json.hpp>

extern "C" bool obs_android_encoder_stats(char *, size_t);
extern "C" bool obs_android_rgba_stats(char *, size_t);
extern "C" bool obs_android_multistream_target_stats(const char *, char *, size_t);
namespace {
std::mutex mutex;
obs_source_t *saved_program{}, *test_browser{}, *test_background{};
obs_scene_t *test_scene{};
obs_output_t *recording{};
obs_volmeter_t *meter{};
bool running{}, raw_attached{};
std::atomic<uint32_t> opaque_pixel{}, alpha_pixel{}, transparent_pixel{};
std::atomic<uint64_t> raw_frames{0};
std::atomic<float> audio_peak{-120.0f};

void raw_video(void *, video_data *video) {
    auto pixel = [video](int x) {
        const auto *p = video->data[0] + 90 * video->linesize[0] + x * 4;
        return uint32_t(p[0]) << 16 | uint32_t(p[1]) << 8 | uint32_t(p[2]);
    };
    opaque_pixel.store(pixel(32)); alpha_pixel.store(pixel(120)); transparent_pixel.store(pixel(250));
    ++raw_frames;
}
void audio_level(void *, const float *, const float *peak, const float *) {
    audio_peak.store(std::max(peak[0], peak[1]));
}
void cleanup() {
    blog(LOG_INFO, "[performance-probe] Cleanup recording");
    if (recording) {
        obs_output_force_stop(recording);
        obs_output_release(recording);
        recording = nullptr;
    }
    blog(LOG_INFO, "[performance-probe] Cleanup RTMP outputs");
    obs_android_multistream_stop_target("__perf_a");
    obs_android_multistream_stop_target("__perf_b");
    obs_android_multistream_stop_target("__perf_c");
    obs_android_multistream_stop_target("__perf_d");
    obs_android_multistream_stop_target("__perf_e");
    obs_android_multistream_stop_target("__perf_f");
    blog(LOG_INFO, "[performance-probe] Cleanup sources");
    if (raw_attached) { obs_remove_raw_video_callback(raw_video, nullptr); raw_attached = false; }
    if (meter) { obs_volmeter_destroy(meter); meter = nullptr; }
    if (test_scene) {
        obs_source_t *current = obs_get_output_source(0);
        if (current == obs_scene_get_source(test_scene)) obs_set_output_source(0, saved_program);
        obs_source_release(current);
        obs_scene_release(test_scene); test_scene = nullptr;
    }
    if (saved_program) { obs_source_release(saved_program); saved_program = nullptr; }
    if (test_browser) { obs_source_release(test_browser); test_browser = nullptr; }
    if (test_background) { obs_source_release(test_background); test_background = nullptr; }
    running = false;
    blog(LOG_INFO, "[performance-probe] Cleanup complete");
}
nlohmann::json read_stats(bool (*fn)(char *, size_t)) {
    char buffer[4096] = {};
    if (!fn(buffer, sizeof(buffer))) return nullptr;
    return nlohmann::json::parse(buffer, nullptr, false);
}
}

extern "C" __attribute__((visibility("default"))) void obs_android_performance_probe_shutdown() {
    std::lock_guard<std::mutex> lock(mutex);
    if (running || test_scene || recording) cleanup();
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_obsandroid_port_PerformanceProbeActivity_command(JNIEnv *env, jclass, jstring json)
{
    AndroidCallbackScope scope;
    if (!scope) return env->NewStringUTF("{\"ok\":false,\"error\":\"Session shutting down\"}");
    std::lock_guard<std::mutex> lock(mutex);
    const char *raw = env->GetStringUTFChars(json, nullptr);
    auto request = nlohmann::json::parse(raw ? raw : "{}", nullptr, false);
    if (raw) env->ReleaseStringUTFChars(json, raw);
    nlohmann::json result{{"ok", false}};
    try {
        const auto action = request.value("action", "");
        if (action == "end") { cleanup(); result["ok"] = true; }
        else if (action == "stop_first") {
            result["ok"] = obs_android_multistream_stop_target("__perf_a");
        } else if (action == "begin") {
            if (!obs_initialized()) throw std::runtime_error("OBS is still starting");
            if (running || obs_video_active()) throw std::runtime_error("Stop existing outputs before running the local probe");
            raw_frames = 0; audio_peak = -120.0f;
            if (request.value("mode", "smoke") != "real") {
                obs_video_info vi{}; obs_get_video_info(&vi);
                saved_program = obs_get_output_source(0);
                test_scene = obs_scene_create_private("[Performance test]");
                obs_data_t *settings = obs_data_create();
                obs_data_set_int(settings, "width", vi.base_width); obs_data_set_int(settings, "height", vi.base_height);
                obs_data_set_int(settings, "color", 0xffff0000);
                test_background = obs_source_create_private("color_source_v3", "[Performance background]", settings);
                obs_data_release(settings);
                if (!test_background) throw std::runtime_error("Test color source unavailable");
                obs_scene_add(test_scene, test_background);
                settings = obs_data_create();
                obs_data_set_bool(settings, "is_local_file", true);
                obs_data_set_string(settings, "local_file", request.at("file").get<std::string>().c_str());
                obs_data_set_int(settings, "width", 640); obs_data_set_int(settings, "height", 360);
                obs_data_set_bool(settings, "capture_audio", true);
                const bool avatar = request.value("mode", "smoke") == "vtuber";
                if (avatar) {
                    obs_data_set_string(settings, "vt_mode", "vrm");
                    obs_data_set_bool(settings, "face_tracking", false);
                    obs_data_set_bool(settings, "hand_tracking", false);
                }
                test_browser = obs_source_create_private(avatar ? "vtuber_source" : "browser_source", "[Performance browser]", settings);
                obs_data_release(settings);
                if (!test_browser) throw std::runtime_error("Browser unavailable");
                obs_sceneitem_t *item = obs_scene_add(test_scene, test_browser);
                vec2 bounds{float(vi.base_width), float(vi.base_height)};
                obs_sceneitem_set_bounds_type(item, OBS_BOUNDS_STRETCH); obs_sceneitem_set_bounds(item, &bounds);
                meter = obs_volmeter_create(OBS_FADER_LOG);
                obs_volmeter_attach_source(meter, test_browser); obs_volmeter_add_callback(meter, audio_level, nullptr);
                obs_set_output_source(0, obs_scene_get_source(test_scene));
                video_scale_info conversion{};
                conversion.format = VIDEO_FORMAT_RGBA; conversion.width = 320; conversion.height = 180;
                conversion.colorspace = VIDEO_CS_709; conversion.range = VIDEO_RANGE_FULL;
                obs_add_raw_video_callback(&conversion, raw_video, nullptr); raw_attached = true;
            }
            if (!obs_android_multistream_start_target("__perf_a", "rtmp://127.0.0.1:1935/live", "perf_a", 3500) ||
                !obs_android_multistream_start_target("__perf_b", "rtmp://127.0.0.1:1935/live", "perf_b", 3500))
                throw std::runtime_error("Local multistream did not start");
            if (request.value("mode", "smoke") == "vertical") {
                bool first = obs_android_multistream_start_target_ex("__perf_d", "rtmp://127.0.0.1:1935/live", "perf_d", 3500, true, 720, 1280, "", 1);
                bool second = obs_android_multistream_start_target_ex("__perf_e", "rtmp://127.0.0.1:1935/live", "perf_e", 3500, true, 720, 1280, "", 1);
                bool conflict = obs_android_multistream_start_target_ex("__perf_f", "rtmp://127.0.0.1:1935/live", "perf_f", 3500, true, 1080, 1920, "", 1);
                result["vertical_shared"] = first && second;
                result["incompatible_canvas_rejected"] = !conflict;
                if (!first || !second || conflict) throw std::runtime_error("Vertical sharing/isolation failed");
            }
            char stats[2048] = {};
            obs_android_multistream_target_stats("__perf_b", stats, sizeof(stats));
            auto stream = nlohmann::json::parse(stats);
            obs_encoder_t *ve = obs_get_encoder_by_name(stream.at("video_encoder").get<std::string>().c_str());
            obs_encoder_t *ae = obs_get_encoder_by_name(stream.at("audio_encoder").get<std::string>().c_str());
            obs_data_t *settings = obs_data_create();
            obs_data_set_string(settings, "path", request.at("recording").get<std::string>().c_str());
            recording = obs_output_create("mp4_output", "performance_recording", settings, nullptr);
            obs_data_release(settings);
            if (recording && ve && ae) {
                obs_output_set_video_encoder(recording, ve); obs_output_set_audio_encoder(recording, ae, 0);
            }
            if (ve) obs_encoder_release(ve); if (ae) obs_encoder_release(ae);
            if (!recording || !obs_output_start(recording)) throw std::runtime_error("Local recording did not start");
            running = true; result["ok"] = true;
        } else if (action == "independent_start") {
            result["ok"] = obs_android_multistream_start_target("__perf_c", "rtmp://127.0.0.1:1935/live", "perf_c", 2000);
        } else if (action == "independent_stop") {
            result["ok"] = obs_android_multistream_stop_target("__perf_c");
        } else if (action == "resize" && test_browser) {
            obs_data_t *settings = obs_source_get_settings(test_browser);
            obs_data_set_int(settings, "width", request.value("width", 640));
            obs_data_set_int(settings, "height", request.value("height", 360));
            obs_source_update(test_browser, settings);
            obs_data_release(settings);
            result["ok"] = true;
        } else if (action == "visibility" && test_browser && test_scene) {
            if (auto *item = obs_scene_find_source(test_scene, "[Performance browser]")) {
                obs_sceneitem_set_visible(item, request.value("visible", true));
                result["ok"] = true;
            }
        } else if (action == "sample") {
            result["ok"] = running;
            result["fps"] = obs_get_active_fps();
            result["render_ms"] = double(obs_get_average_frame_time_ns()) / 1e6;
            result["encoders"] = read_stats(obs_android_encoder_stats);
            result["rgba"] = read_stats(obs_android_rgba_stats);
            for (auto id : {"__perf_a", "__perf_b", "__perf_c", "__perf_d", "__perf_e"}) {
                char stats[2048] = {};
                if (obs_android_multistream_target_stats(id, stats, sizeof(stats))) result[id] = nlohmann::json::parse(stats);
            }
            result["recording_frames"] = recording ? obs_output_get_total_frames(recording) : 0;
            result["recording_active"] = recording && obs_output_active(recording);
            result["raw_frames"] = raw_frames.load();
            result["pixels"] = {opaque_pixel.load(), alpha_pixel.load(), transparent_pixel.load()};
            const float peak = audio_peak.load(); result["browser_audio_peak_db"] = std::isfinite(peak) ? peak : -120.0;
        }
    } catch (const std::exception &error) {
        result["error"] = error.what();
        if (request.value("action", "") == "begin") cleanup();
    }
    return env->NewStringUTF(result.dump().c_str());
}
