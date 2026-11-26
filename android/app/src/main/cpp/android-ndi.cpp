// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-ndi.h"
#include <obs-module.h>
#include <util/platform.h>
#include <util/platform-android.h>
#include <media-io/video-io.h>
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <algorithm>
#include <atomic>
#include <cstring>
#include <map>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr const char *kTag = "ObsAndroidNdi";

/* Official Vizrt / NewTek NDI v5/v6 flat C ABI structures */
struct NDIlib_source_t {
    const char *p_ndi_name;
    union {
        const char *p_url_address;
        const char *p_ip_address;
    };
};

struct NDIlib_find_create_t {
    bool show_local_sources;
    const char *p_groups;
    const char *p_extra_ips;
};

enum NDIlib_recv_color_format_e : int32_t {
    NDIlib_recv_color_format_BGRX_BGRA = 0,
    NDIlib_recv_color_format_UYVY_BGRA = 1,
    NDIlib_recv_color_format_RGBX_RGBA = 2,
    NDIlib_recv_color_format_UYVY_RGBA = 3,
    NDIlib_recv_color_format_fastest = 100,
    NDIlib_recv_color_format_best = 101
};

enum NDIlib_recv_bandwidth_e : int32_t {
    NDIlib_recv_bandwidth_metadata_only = -10,
    NDIlib_recv_bandwidth_audio_only = 10,
    NDIlib_recv_bandwidth_lowest = 0,
    NDIlib_recv_bandwidth_highest = 100
};

struct NDIlib_recv_create_v3_t {
    NDIlib_source_t source_to_connect_to;
    NDIlib_recv_color_format_e color_format;
    NDIlib_recv_bandwidth_e bandwidth;
    bool allow_video_fields;
    const char *p_ndi_recv_name;
};

enum NDIlib_frame_type_e : int32_t {
    NDIlib_frame_type_none = 0,
    NDIlib_frame_type_video = 1,
    NDIlib_frame_type_audio = 2,
    NDIlib_frame_type_metadata = 3,
    NDIlib_frame_type_error = 4,
    NDIlib_frame_type_status_change = 100
};

constexpr uint32_t MakeFourCC(char a, char b, char c, char d)
{
    return uint32_t(uint8_t(a)) | (uint32_t(uint8_t(b)) << 8) |
           (uint32_t(uint8_t(c)) << 16) | (uint32_t(uint8_t(d)) << 24);
}

constexpr uint32_t kFourCC_UYVY = MakeFourCC('U', 'Y', 'V', 'Y');
constexpr uint32_t kFourCC_UYVA = MakeFourCC('U', 'Y', 'V', 'A');
constexpr uint32_t kFourCC_NV12 = MakeFourCC('N', 'V', '1', '2');
constexpr uint32_t kFourCC_I420 = MakeFourCC('I', '4', '2', '0');
constexpr uint32_t kFourCC_YV12 = MakeFourCC('Y', 'V', '1', '2');
constexpr uint32_t kFourCC_BGRA = MakeFourCC('B', 'G', 'R', 'A');
constexpr uint32_t kFourCC_BGRX = MakeFourCC('B', 'G', 'R', 'X');
constexpr uint32_t kFourCC_RGBA = MakeFourCC('R', 'G', 'B', 'A');
constexpr uint32_t kFourCC_RGBX = MakeFourCC('R', 'G', 'B', 'X');

struct NDIlib_video_frame_v2_t {
    int32_t xres;
    int32_t yres;
    uint32_t FourCC;
    int32_t frame_rate_N;
    int32_t frame_rate_D;
    float picture_aspect_ratio;
    int32_t frame_format_type;
    int64_t timecode;
    uint8_t *p_data;
    union {
        int32_t line_stride_in_bytes;
        int32_t data_size_in_bytes;
    };
    const char *p_metadata;
    int64_t timestamp;
};

struct NDIlib_audio_frame_v2_t {
    int32_t sample_rate;
    int32_t no_channels;
    int32_t no_samples;
    int64_t timecode;
    float *p_data;
    int32_t channel_stride_in_bytes;
    const char *p_metadata;
    int64_t timestamp;
};

struct NDIlib_metadata_frame_t {
    int32_t length;
    int64_t timecode;
    char *p_data;
};

struct NDIlib_send_create_t { const char *p_ndi_name; const char *p_groups; bool clock_video; bool clock_audio; };
struct NDIlib_send_video_v2_t {
    int32_t xres, yres; uint32_t FourCC; int32_t frame_rate_N, frame_rate_D;
    float picture_aspect_ratio; int32_t frame_format_type; int64_t timecode;
    uint8_t *p_data; int32_t line_stride_in_bytes; const char *p_metadata; int64_t timestamp;
};
struct NDIlib_send_audio_v2_t {
    int32_t sample_rate, no_channels, no_samples; int64_t timecode;
    float *p_data; int32_t channel_stride_in_bytes; const char *p_metadata; int64_t timestamp;
};

struct NdiRuntime {
    void *handle{};
    bool initialized{false};
    std::string version;
    std::string loaded_path;

    bool (*initialize)(void){};
    void (*destroy)(void){};
    const char *(*get_version)(void){};
    void *(*find_create_v2)(const NDIlib_find_create_t *){};
    void (*find_destroy)(void *){};
    bool (*find_wait_for_sources)(void *, uint32_t){};
    const NDIlib_source_t *(*find_get_current_sources)(void *, uint32_t *){};
    void *(*recv_create_v3)(const NDIlib_recv_create_v3_t *){};
    void (*recv_destroy)(void *){};
    void (*recv_connect)(void *, const NDIlib_source_t *){};
    NDIlib_frame_type_e (*recv_capture_v2)(
        void *, NDIlib_video_frame_v2_t *, NDIlib_audio_frame_v2_t *, NDIlib_metadata_frame_t *, uint32_t){};
    void (*recv_free_video_v2)(void *, const NDIlib_video_frame_v2_t *){};
    void (*recv_free_audio_v2)(void *, const NDIlib_audio_frame_v2_t *){};
    void (*recv_free_metadata)(void *, const NDIlib_metadata_frame_t *){};
    void *(*send_create)(const NDIlib_send_create_t *){};
    void (*send_destroy)(void *){};
    void (*send_video_v2)(void *, const NDIlib_send_video_v2_t *){};
    void (*send_audio_v2)(void *, const NDIlib_send_audio_v2_t *){};
};

std::mutex g_ndi_mutex;
NdiRuntime g_rt;
void *g_finder = nullptr;
bool g_registered = false;
bool g_output_registered = false;
struct NdiOutputInstance {
    std::mutex sender_mutex;
    void *sender{};
    void (*send_destroy)(void *){};
    void (*send_video_v2)(void *, const NDIlib_send_video_v2_t *){};
    void (*send_audio_v2)(void *, const NDIlib_send_audio_v2_t *){};
    std::string name;
    int width{}, height{}, fps_n{30}, fps_d{1}, sample_rate{48000};
    std::mutex audio_mutex;
    std::vector<float> planar_audio;
};
std::mutex g_ndi_output_mutex;
obs_output_t *g_ndi_tool_output{};
std::vector<struct NdiSourceInstance *> g_active_ndi_instances;

JavaVM *g_ndi_jvm = nullptr;
jclass g_ndi_host_class = nullptr;
jmethodID g_mid_list_mdns = nullptr;
jmethodID g_mid_pick_ndi_lib = nullptr;

bool load_ndi_runtime_locked(const char *explicit_path = nullptr)
{
    if (g_rt.initialized && g_rt.handle) return true;

    std::vector<std::string> candidates;
    if (explicit_path && *explicit_path) {
        candidates.emplace_back(explicit_path);
    }
    const char *files_dir = obs_android_files_path();
    if (!files_dir || !*files_dir) files_dir = getenv("OBS_ANDROID_FILES_PATH");
    if (files_dir && *files_dir) {
        candidates.emplace_back(std::string(files_dir) + "/obs-studio/ndi/libndi.so");
        candidates.emplace_back(std::string(files_dir) + "/obs-studio/plugins/obs-ndi/libndi.so");
    }
    const char *libs_dir = obs_android_libraries_path();
    if (!libs_dir || !*libs_dir) libs_dir = getenv("OBS_ANDROID_LIBRARIES_PATH");
    if (libs_dir && *libs_dir) {
        candidates.emplace_back(std::string(libs_dir) + "/libndi.so");
    }
    candidates.emplace_back("libndi.so");

    for (const auto &path : candidates) {
        if (path != "libndi.so" && !os_file_exists(path.c_str())) continue;
        void *h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!h) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "dlopen(%s) failed: %s", path.c_str(), dlerror());
            continue;
        }
        NdiRuntime tmp{};
        tmp.handle = h;
        tmp.initialize = reinterpret_cast<bool (*)()>(dlsym(h, "NDIlib_initialize"));
        tmp.destroy = reinterpret_cast<void (*)()>(dlsym(h, "NDIlib_destroy"));
        tmp.get_version = reinterpret_cast<const char *(*)()>(dlsym(h, "NDIlib_version"));
        tmp.find_create_v2 = reinterpret_cast<void *(*)(const NDIlib_find_create_t *)>(dlsym(h, "NDIlib_find_create_v2"));
        tmp.find_destroy = reinterpret_cast<void (*)(void *)>(dlsym(h, "NDIlib_find_destroy"));
        tmp.find_wait_for_sources = reinterpret_cast<bool (*)(void *, uint32_t)>(dlsym(h, "NDIlib_find_wait_for_sources"));
        tmp.find_get_current_sources =
            reinterpret_cast<const NDIlib_source_t *(*)(void *, uint32_t *)>(dlsym(h, "NDIlib_find_get_current_sources"));
        tmp.recv_create_v3 = reinterpret_cast<void *(*)(const NDIlib_recv_create_v3_t *)>(dlsym(h, "NDIlib_recv_create_v3"));
        tmp.recv_destroy = reinterpret_cast<void (*)(void *)>(dlsym(h, "NDIlib_recv_destroy"));
        tmp.recv_connect = reinterpret_cast<void (*)(void *, const NDIlib_source_t *)>(dlsym(h, "NDIlib_recv_connect"));
        tmp.recv_capture_v2 = reinterpret_cast<NDIlib_frame_type_e (*)(
            void *, NDIlib_video_frame_v2_t *, NDIlib_audio_frame_v2_t *, NDIlib_metadata_frame_t *, uint32_t)>(
            dlsym(h, "NDIlib_recv_capture_v2"));
        tmp.recv_free_video_v2 = reinterpret_cast<void (*)(void *, const NDIlib_video_frame_v2_t *)>(dlsym(h, "NDIlib_recv_free_video_v2"));
        tmp.recv_free_audio_v2 = reinterpret_cast<void (*)(void *, const NDIlib_audio_frame_v2_t *)>(dlsym(h, "NDIlib_recv_free_audio_v2"));
        tmp.recv_free_metadata = reinterpret_cast<void (*)(void *, const NDIlib_metadata_frame_t *)>(dlsym(h, "NDIlib_recv_free_metadata"));
        tmp.send_create = reinterpret_cast<void *(*)(const NDIlib_send_create_t *)>(dlsym(h, "NDIlib_send_create"));
        tmp.send_destroy = reinterpret_cast<void (*)(void *)>(dlsym(h, "NDIlib_send_destroy"));
        tmp.send_video_v2 = reinterpret_cast<void (*)(void *, const NDIlib_send_video_v2_t *)>(dlsym(h, "NDIlib_send_send_video_v2"));
        tmp.send_audio_v2 = reinterpret_cast<void (*)(void *, const NDIlib_send_audio_v2_t *)>(dlsym(h, "NDIlib_send_send_audio_v2"));

        if (!tmp.initialize || !tmp.recv_create_v3 || !tmp.recv_capture_v2 || !tmp.recv_destroy ||
            !tmp.recv_free_video_v2 || !tmp.recv_free_audio_v2) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "Missing required NDIlib_* symbols in %s", path.c_str());
            dlclose(h);
            continue;
        }
        if (!tmp.initialize()) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "NDIlib_initialize() failed for %s", path.c_str());
            dlclose(h);
            continue;
        }
        tmp.initialized = true;
        tmp.loaded_path = path;
        tmp.version = (tmp.get_version && tmp.get_version()) ? tmp.get_version() : "NDI v5/v6";
        g_rt = std::move(tmp);
        if (g_rt.find_create_v2 && !g_finder) {
            NDIlib_find_create_t fc{true, nullptr, nullptr};
            g_finder = g_rt.find_create_v2(&fc);
        }
        blog(LOG_INFO, "[android-ndi] Loaded NDI runtime '%s' from %s", g_rt.version.c_str(), g_rt.loaded_path.c_str());
        return true;
    }
    return false;
}

std::map<std::string, std::string> query_java_mdns_sources()
{
    std::map<std::string, std::string> out;
    JavaVM *jvm = nullptr;
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_ndi_mutex);
        jvm = g_ndi_jvm;
        cls = g_ndi_host_class;
        mid = g_mid_list_mdns;
    }
    if (!jvm || !cls || !mid) return out;
    JNIEnv *env = nullptr;
    bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) != 0) return out;
        detach = true;
    } else if (st != JNI_OK) {
        return out;
    }
    auto jStr = static_cast<jstring>(env->CallStaticObjectMethod(cls, mid));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (jStr) {
        const char *utf = env->GetStringUTFChars(jStr, nullptr);
        if (utf) {
            std::istringstream ss(utf);
            std::string line;
            while (std::getline(ss, line)) {
                if (line.empty()) continue;
                auto tab = line.find('\t');
                if (tab == std::string::npos) {
                    out[line] = "";
                } else {
                    out[line.substr(0, tab)] = line.substr(tab + 1);
                }
            }
            env->ReleaseStringUTFChars(jStr, utf);
        }
        env->DeleteLocalRef(jStr);
    }
    if (detach) jvm->DetachCurrentThread();
    return out;
}

std::map<std::string, std::string> collect_all_ndi_sources(bool wait_briefly)
{
    std::map<std::string, std::string> sources = query_java_mdns_sources();
    std::lock_guard<std::mutex> lock(g_ndi_mutex);
    load_ndi_runtime_locked();
    if (g_rt.initialized && g_finder && g_rt.find_get_current_sources) {
        if (wait_briefly && g_rt.find_wait_for_sources) {
            g_rt.find_wait_for_sources(g_finder, 300);
        }
        uint32_t count = 0;
        const NDIlib_source_t *list = g_rt.find_get_current_sources(g_finder, &count);
        for (uint32_t i = 0; list && i < count; ++i) {
            if (list[i].p_ndi_name && *list[i].p_ndi_name) {
                sources[list[i].p_ndi_name] = list[i].p_url_address ? list[i].p_url_address : "";
            }
        }
    }
    return sources;
}

void trigger_java_pick_ndi_lib()
{
    JavaVM *jvm = nullptr;
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_ndi_mutex);
        jvm = g_ndi_jvm;
        cls = g_ndi_host_class;
        mid = g_mid_pick_ndi_lib;
    }
    if (!jvm || !cls || !mid) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) != 0) return;
        detach = true;
    } else if (st != JNI_OK) {
        return;
    }
    env->CallStaticVoidMethod(cls, mid);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) jvm->DetachCurrentThread();
}

speaker_layout channels_to_speakers(int ch)
{
    switch (ch) {
    case 1: return SPEAKERS_MONO;
    case 2: return SPEAKERS_STEREO;
    case 3: return SPEAKERS_2POINT1;
    case 4: return SPEAKERS_4POINT0;
    case 5: return SPEAKERS_4POINT1;
    case 6: return SPEAKERS_5POINT1;
    case 8: return SPEAKERS_7POINT1;
    default: return ch > 2 ? SPEAKERS_STEREO : SPEAKERS_MONO;
    }
}

struct NdiSourceInstance {
    obs_source_t *source{};
    std::string source_name;
    std::string url_address;
    int bw_mode{0};    // 0=highest, 1=lowest, 2=audio_only
    int sync_mode{0};  // 0=internal, 1=network
    bool audio_enabled{true};

    std::atomic<bool> running{false};
    std::thread worker;
};

void stop_worker(NdiSourceInstance *inst)
{
    inst->running.store(false);
    if (inst->worker.joinable()) {
        inst->worker.join();
    }
}

void worker_loop(NdiSourceInstance *inst)
{
    NdiRuntime rt_copy{};
    {
        std::lock_guard<std::mutex> lock(g_ndi_mutex);
        if (!load_ndi_runtime_locked()) {
            blog(LOG_INFO, "[android-ndi] Receiver waiting: libndi.so (ARM64) not installed yet.");
            return;
        }
        rt_copy = g_rt;
    }

    if (inst->source_name.empty() && inst->url_address.empty()) {
        return;
    }

    NDIlib_recv_create_v3_t desc{};
    desc.source_to_connect_to.p_ndi_name = inst->source_name.empty() ? nullptr : inst->source_name.c_str();
    desc.source_to_connect_to.p_url_address = inst->url_address.empty() ? nullptr : inst->url_address.c_str();
    desc.color_format = NDIlib_recv_color_format_UYVY_BGRA;
    desc.bandwidth = (inst->bw_mode == 1) ? NDIlib_recv_bandwidth_lowest
                   : (inst->bw_mode == 2) ? NDIlib_recv_bandwidth_audio_only
                                          : NDIlib_recv_bandwidth_highest;
    desc.allow_video_fields = false;
    desc.p_ndi_recv_name = "OBS Studio Android";

    void *recv = rt_copy.recv_create_v3(&desc);
    if (!recv) {
        blog(LOG_WARNING, "[android-ndi] NDIlib_recv_create_v3 failed for '%s'", inst->source_name.c_str());
        return;
    }

    blog(LOG_INFO, "[android-ndi] Connected receiver to '%s' (url='%s', bw=%d)",
         inst->source_name.c_str(), inst->url_address.c_str(), inst->bw_mode);

    while (inst->running.load()) {
        NDIlib_video_frame_v2_t vframe{};
        NDIlib_audio_frame_v2_t aframe{};
        NDIlib_metadata_frame_t mframe{};
        NDIlib_frame_type_e ftype = rt_copy.recv_capture_v2(recv, &vframe, &aframe, &mframe, 100);

        if (ftype == NDIlib_frame_type_video && vframe.p_data && vframe.xres > 0 && vframe.yres > 0) {
            obs_source_frame frame{};
            frame.width = uint32_t(vframe.xres);
            frame.height = uint32_t(vframe.yres);
            frame.timestamp = (inst->sync_mode == 1 && vframe.timestamp > 0)
                                  ? uint64_t(vframe.timestamp) * 100ULL
                                  : os_gettime_ns();
            frame.full_range = false;
            bool ok = true;

            if (vframe.FourCC == kFourCC_UYVY || vframe.FourCC == kFourCC_UYVA) {
                frame.format = VIDEO_FORMAT_UYVY;
                frame.data[0] = vframe.p_data;
                frame.linesize[0] = uint32_t(vframe.line_stride_in_bytes > 0 ? vframe.line_stride_in_bytes : vframe.xres * 2);
                video_format_get_parameters(
                    vframe.yres >= 720 ? VIDEO_CS_709 : VIDEO_CS_601, VIDEO_RANGE_PARTIAL,
                    frame.color_matrix, frame.color_range_min, frame.color_range_max);
            } else if (vframe.FourCC == kFourCC_BGRA || vframe.FourCC == kFourCC_BGRX) {
                frame.format = (vframe.FourCC == kFourCC_BGRA) ? VIDEO_FORMAT_BGRA : VIDEO_FORMAT_BGRX;
                frame.data[0] = vframe.p_data;
                frame.linesize[0] = uint32_t(vframe.line_stride_in_bytes > 0 ? vframe.line_stride_in_bytes : vframe.xres * 4);
                frame.full_range = true;
            } else if (vframe.FourCC == kFourCC_RGBA || vframe.FourCC == kFourCC_RGBX) {
                frame.format = VIDEO_FORMAT_RGBA;
                frame.data[0] = vframe.p_data;
                frame.linesize[0] = uint32_t(vframe.line_stride_in_bytes > 0 ? vframe.line_stride_in_bytes : vframe.xres * 4);
                frame.full_range = true;
            } else if (vframe.FourCC == kFourCC_NV12) {
                int stride = vframe.line_stride_in_bytes > 0 ? vframe.line_stride_in_bytes : vframe.xres;
                frame.format = VIDEO_FORMAT_NV12;
                frame.data[0] = vframe.p_data;
                frame.data[1] = vframe.p_data + size_t(stride) * size_t(vframe.yres);
                frame.linesize[0] = uint32_t(stride);
                frame.linesize[1] = uint32_t(stride);
                video_format_get_parameters(
                    vframe.yres >= 720 ? VIDEO_CS_709 : VIDEO_CS_601, VIDEO_RANGE_PARTIAL,
                    frame.color_matrix, frame.color_range_min, frame.color_range_max);
            } else if (vframe.FourCC == kFourCC_I420 || vframe.FourCC == kFourCC_YV12) {
                int stride = vframe.line_stride_in_bytes > 0 ? vframe.line_stride_in_bytes : vframe.xres;
                int cstride = stride / 2;
                size_t y_bytes = size_t(stride) * size_t(vframe.yres);
                size_t c_bytes = size_t(cstride) * size_t(vframe.yres / 2);
                frame.format = VIDEO_FORMAT_I420;
                frame.data[0] = vframe.p_data;
                if (vframe.FourCC == kFourCC_I420) {
                    frame.data[1] = vframe.p_data + y_bytes;
                    frame.data[2] = vframe.p_data + y_bytes + c_bytes;
                } else {
                    frame.data[2] = vframe.p_data + y_bytes;
                    frame.data[1] = vframe.p_data + y_bytes + c_bytes;
                }
                frame.linesize[0] = uint32_t(stride);
                frame.linesize[1] = uint32_t(cstride);
                frame.linesize[2] = uint32_t(cstride);
                video_format_get_parameters(
                    vframe.yres >= 720 ? VIDEO_CS_709 : VIDEO_CS_601, VIDEO_RANGE_PARTIAL,
                    frame.color_matrix, frame.color_range_min, frame.color_range_max);
            } else {
                ok = false;
            }

            if (ok && inst->source) {
                obs_source_t *ref = obs_source_get_ref(inst->source);
                if (ref) {
                    obs_source_output_video(ref, &frame);
                    obs_source_release(ref);
                }
            }
            rt_copy.recv_free_video_v2(recv, &vframe);
        } else if (ftype == NDIlib_frame_type_audio && aframe.p_data && aframe.no_samples > 0 && aframe.no_channels > 0) {
            if (inst->audio_enabled && inst->source) {
                obs_source_audio audio{};
                audio.frames = uint32_t(aframe.no_samples);
                audio.samples_per_sec = uint32_t(aframe.sample_rate > 0 ? aframe.sample_rate : 48000);
                audio.format = AUDIO_FORMAT_FLOAT_PLANAR;
                audio.speakers = channels_to_speakers(aframe.no_channels);
                audio.timestamp = (inst->sync_mode == 1 && aframe.timestamp > 0)
                                      ? uint64_t(aframe.timestamp) * 100ULL
                                      : os_gettime_ns();
                int planes = std::min(aframe.no_channels, MAX_AV_PLANES);
                int stride = aframe.channel_stride_in_bytes > 0
                                 ? aframe.channel_stride_in_bytes
                                 : int(aframe.no_samples * sizeof(float));
                const uint8_t *base = reinterpret_cast<const uint8_t *>(aframe.p_data);
                for (int c = 0; c < planes; ++c) {
                    audio.data[c] = base + size_t(c) * size_t(stride);
                }
                obs_source_t *ref = obs_source_get_ref(inst->source);
                if (ref) {
                    obs_source_output_audio(ref, &audio);
                    obs_source_release(ref);
                }
            }
            rt_copy.recv_free_audio_v2(recv, &aframe);
        } else if (ftype == NDIlib_frame_type_metadata && rt_copy.recv_free_metadata) {
            rt_copy.recv_free_metadata(recv, &mframe);
        }
    }

    rt_copy.recv_destroy(recv);
}

void start_worker(NdiSourceInstance *inst)
{
    stop_worker(inst);
    if (inst->source_name.empty() && inst->url_address.empty()) return;
    inst->running.store(true);
    inst->worker = std::thread(worker_loop, inst);
}

const char *ndi_get_name(void *) { return "Fuente NDI™"; }

void ndi_get_defaults(obs_data_t *settings)
{
    obs_data_set_default_string(settings, "ndi_source_name", "");
    obs_data_set_default_int(settings, "ndi_bw_mode", 0);
    obs_data_set_default_int(settings, "ndi_sync", 0);
    obs_data_set_default_bool(settings, "ndi_audio", true);
}

void populate_ndi_list(obs_property_t *list, bool wait_briefly)
{
    obs_property_list_clear(list);
    auto found = collect_all_ndi_sources(wait_briefly);
    for (const auto &kv : found) {
        obs_property_list_add_string(list, kv.first.c_str(), kv.first.c_str());
    }
}

bool on_ndi_refresh_clicked(obs_properties_t *props, obs_property_t *, void *)
{
    obs_property_t *list = obs_properties_get(props, "ndi_source_name");
    if (list) populate_ndi_list(list, true);
    return true;
}

bool on_ndi_install_lib_clicked(obs_properties_t *, obs_property_t *, void *)
{
    trigger_java_pick_ndi_lib();
    return false;
}

obs_properties_t *ndi_get_properties(void *)
{
    obs_properties_t *props = obs_properties_create();

    std::string status_msg;
    {
        std::lock_guard<std::mutex> lock(g_ndi_mutex);
        load_ndi_runtime_locked();
        if (g_rt.initialized) {
            status_msg = "Runtime NDI activo (" + g_rt.version + ") — listo para recibir video y audio NDI en red.";
        } else {
            status_msg = "Runtime libndi.so (ARM64) no instalado. El descubrimiento mDNS (_ndi._tcp) ya funciona; "
                         "para recibir video/audio NDI descarga 'NDI SDK for Android' desde ndi.video y pulsa 'Instalar libndi.so (ARM64)...' "
                         "(o copia libndi.so en Android/media/org.obsandroid.port/ndi/).";
        }
    }
    obs_properties_add_text(props, "ndi_status", status_msg.c_str(), OBS_TEXT_INFO);

    obs_property_t *src_list = obs_properties_add_list(
        props, "ndi_source_name", "Nombre de fuente NDI", OBS_COMBO_TYPE_EDITABLE, OBS_COMBO_FORMAT_STRING);
    populate_ndi_list(src_list, false);

    obs_properties_add_button(props, "ndi_refresh", "Buscar fuentes NDI en red", on_ndi_refresh_clicked);

    obs_property_t *bw = obs_properties_add_list(
        props, "ndi_bw_mode", "Ancho de banda", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(bw, "Alta calidad (Máximo)", 0);
    obs_property_list_add_int(bw, "Baja calidad (Proxy / Wi-Fi)", 1);
    obs_property_list_add_int(bw, "Solo audio", 2);

    obs_property_t *sync = obs_properties_add_list(
        props, "ndi_sync", "Sincronización", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(sync, "Interna (reloj local)", 0);
    obs_property_list_add_int(sync, "Red (marca de tiempo NDI)", 1);

    obs_properties_add_bool(props, "ndi_audio", "Recibir audio NDI");
    obs_properties_add_button(
        props, "ndi_install_lib", "Instalar librería libndi.so (ARM64)...", on_ndi_install_lib_clicked);

    return props;
}

void ndi_update(void *data, obs_data_t *settings)
{
    auto *inst = static_cast<NdiSourceInstance *>(data);
    if (!inst) return;
    // Always stop running worker BEFORE mutating strings/config read by worker_loop
    stop_worker(inst);

    const char *name = obs_data_get_string(settings, "ndi_source_name");
    inst->source_name = name ? name : "";
    inst->bw_mode = int(obs_data_get_int(settings, "ndi_bw_mode"));
    inst->sync_mode = int(obs_data_get_int(settings, "ndi_sync"));
    inst->audio_enabled = obs_data_get_bool(settings, "ndi_audio");

    auto mdns = query_java_mdns_sources();
    auto it = mdns.find(inst->source_name);
    inst->url_address = (it != mdns.end()) ? it->second : "";

    start_worker(inst);
}

void *ndi_create(obs_data_t *settings, obs_source_t *source)
{
    auto *inst = new NdiSourceInstance{};
    inst->source = source;
    {
        std::lock_guard<std::mutex> lock(g_ndi_mutex);
        g_active_ndi_instances.push_back(inst);
    }
    obs_source_set_async_unbuffered(source, true);
    ndi_update(inst, settings);
    return inst;
}

void ndi_destroy(void *data)
{
    auto *inst = static_cast<NdiSourceInstance *>(data);
    if (!inst) return;
    {
        std::lock_guard<std::mutex> lock(g_ndi_mutex);
        auto it = std::find(g_active_ndi_instances.begin(), g_active_ndi_instances.end(), inst);
        if (it != g_active_ndi_instances.end()) g_active_ndi_instances.erase(it);
    }
    stop_worker(inst);
    delete inst;
}
} // namespace

namespace {
const char *ndi_output_name(void *) { return "Salida NDI™"; }
void *ndi_output_create(obs_data_t *settings, obs_output_t *output)
{
    auto *inst = new NdiOutputInstance{};
    const char *name = obs_data_get_string(settings, "ndi_name");
    inst->name = name && *name ? name : "OBS Android";
    obs_video_info vi{};
    if (obs_get_video_info(&vi)) {
        video_scale_info video{}; video.format = VIDEO_FORMAT_RGBA; video.width = vi.output_width;
        video.height = vi.output_height; video.colorspace = VIDEO_CS_709; video.range = VIDEO_RANGE_FULL;
        obs_output_set_video_conversion(output, &video);
    }
    obs_audio_info ai{};
    if (obs_get_audio_info(&ai)) {
        inst->sample_rate = int(ai.samples_per_sec);
        audio_convert_info audio{}; audio.samples_per_sec = ai.samples_per_sec;
        audio.format = AUDIO_FORMAT_FLOAT_PLANAR; audio.speakers = SPEAKERS_STEREO;
        obs_output_set_audio_conversion(output, &audio);
    }
    return inst;
}
void ndi_output_destroy(void *data) { delete static_cast<NdiOutputInstance *>(data); }
bool ndi_output_start(void *data)
{
    auto *inst = static_cast<NdiOutputInstance *>(data);
    void *(*create_fn)(const NDIlib_send_create_t *) = nullptr;
    void (*destroy_fn)(void *) = nullptr;
    void (*video_fn)(void *, const NDIlib_send_video_v2_t *) = nullptr;
    void (*audio_fn)(void *, const NDIlib_send_audio_v2_t *) = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_ndi_mutex);
        if (!load_ndi_runtime_locked() || !g_rt.send_create || !g_rt.send_video_v2 || !g_rt.send_audio_v2 || !g_rt.send_destroy) {
            blog(LOG_ERROR, "[android-ndi] Runtime NDI does not include sender functions");
            return false;
        }
        create_fn = g_rt.send_create;
        destroy_fn = g_rt.send_destroy;
        video_fn = g_rt.send_video_v2;
        audio_fn = g_rt.send_audio_v2;
    }
    obs_video_info vi{};
    if (!obs_get_video_info(&vi)) return false;
    std::lock_guard<std::mutex> slock(inst->sender_mutex);
    inst->width = int(vi.output_width); inst->height = int(vi.output_height);
    inst->fps_n = int(vi.fps_num); inst->fps_d = int(vi.fps_den);
    inst->send_destroy = destroy_fn;
    inst->send_video_v2 = video_fn;
    inst->send_audio_v2 = audio_fn;
    NDIlib_send_create_t cfg{inst->name.c_str(), nullptr, false, false};
    inst->sender = create_fn(&cfg);
    return inst->sender != nullptr;
}
void ndi_output_stop(void *data, uint64_t)
{
    auto *inst = static_cast<NdiOutputInstance *>(data);
    if (!inst) return;
    std::lock_guard<std::mutex> slock(inst->sender_mutex);
    if (inst->sender && inst->send_destroy) inst->send_destroy(inst->sender);
    inst->sender = nullptr;
}
void ndi_output_video(void *data, video_data *frame)
{
    auto *inst = static_cast<NdiOutputInstance *>(data);
    if (!inst || !frame || !frame->data[0]) return;
    std::lock_guard<std::mutex> slock(inst->sender_mutex);
    if (!inst->sender || !inst->send_video_v2) return;
    NDIlib_send_video_v2_t v{}; v.xres = inst->width; v.yres = inst->height;
    v.FourCC = MakeFourCC('R','G','B','A'); v.frame_rate_N = inst->fps_n; v.frame_rate_D = inst->fps_d;
    v.picture_aspect_ratio = float(inst->width) / float(inst->height); v.frame_format_type = 1;
    v.timecode = int64_t(frame->timestamp / 100); v.p_data = frame->data[0];
    v.line_stride_in_bytes = int32_t(inst->width * 4);
    inst->send_video_v2(inst->sender, &v);
}
void ndi_output_audio(void *data, audio_data *frames)
{
    auto *inst = static_cast<NdiOutputInstance *>(data);
    if (!inst || !frames || !frames->frames || !frames->data[0] || !frames->data[1]) return;
    auto *left = reinterpret_cast<float *>(frames->data[0]);
    auto *right = reinterpret_cast<float *>(frames->data[1]);
    std::lock_guard<std::mutex> audio_lock(inst->audio_mutex);
    inst->planar_audio.resize(size_t(frames->frames) * 2);
    std::copy(left, left + frames->frames, inst->planar_audio.begin());
    std::copy(right, right + frames->frames, inst->planar_audio.begin() + frames->frames);
    std::lock_guard<std::mutex> slock(inst->sender_mutex);
    if (!inst->sender || !inst->send_audio_v2) return;
    NDIlib_send_audio_v2_t a{}; a.sample_rate = inst->sample_rate; a.no_channels = 2;
    a.no_samples = int32_t(frames->frames); a.timecode = int64_t(frames->timestamp / 100);
    a.p_data = inst->planar_audio.data(); a.channel_stride_in_bytes = int32_t(frames->frames * sizeof(float));
    inst->send_audio_v2(inst->sender, &a);
}
void register_ndi_output()
{
    if (g_output_registered) return;
    obs_output_info info{}; info.id = "android_ndi_output"; info.flags = OBS_OUTPUT_AV;
    info.get_name = ndi_output_name; info.create = ndi_output_create; info.destroy = ndi_output_destroy;
    info.start = ndi_output_start; info.stop = ndi_output_stop; info.raw_video = ndi_output_video; info.raw_audio = ndi_output_audio;
    obs_register_output(&info); g_output_registered = true;
}
} // namespace

extern "C" __attribute__((visibility("default"))) bool obs_android_ndi_output_start(const char *name)
{
    obs_android_ndi_output_stop();
    std::lock_guard<std::mutex> lock(g_ndi_output_mutex);
    if (!obs_initialized()) return false;
    obs_data_t *settings = obs_data_create(); obs_data_set_string(settings, "ndi_name", name && *name ? name : "OBS Android");
    g_ndi_tool_output = obs_output_create("android_ndi_output", "android_ndi_tool_output", settings, nullptr);
    obs_data_release(settings);
    if (!g_ndi_tool_output) return false;
    const bool started = obs_output_start(g_ndi_tool_output);
    if (!started) { obs_output_release(g_ndi_tool_output); g_ndi_tool_output = nullptr; }
    return started;
}
extern "C" __attribute__((visibility("default"))) void obs_android_ndi_output_stop()
{
    std::lock_guard<std::mutex> lock(g_ndi_output_mutex);
    if (g_ndi_tool_output) {
        if (obs_output_active(g_ndi_tool_output)) obs_output_stop(g_ndi_tool_output);
        obs_output_release(g_ndi_tool_output); g_ndi_tool_output = nullptr;
    }
}
extern "C" __attribute__((visibility("default"))) bool obs_android_ndi_output_status(char *out, size_t cap)
{
    if (!out || cap < 16) return false;
    std::lock_guard<std::mutex> lock(g_ndi_output_mutex);
    std::snprintf(out, cap, "{\"active\":%s}", g_ndi_tool_output && obs_output_active(g_ndi_tool_output) ? "true" : "false");
    return true;
}

void android_ndi_register()
{
    std::lock_guard<std::mutex> lock(g_ndi_mutex);
    if (g_registered) return;

    load_ndi_runtime_locked();

    obs_source_info info{};
    info.id = "ndi_source";
    info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_AUDIO | OBS_SOURCE_DO_NOT_DUPLICATE;
    info.get_name = ndi_get_name;
    info.create = ndi_create;
    info.destroy = ndi_destroy;
    info.update = ndi_update;
    info.get_defaults = ndi_get_defaults;
    info.get_properties = ndi_get_properties;
    info.icon_type = OBS_ICON_TYPE_MEDIA;
    obs_register_source(&info);
    register_ndi_output();
    g_registered = true;
}

void android_ndi_shutdown()
{
    obs_android_ndi_output_stop();
    std::vector<NdiSourceInstance *> active;
    {
        std::lock_guard<std::mutex> lock(g_ndi_mutex);
        active = g_active_ndi_instances;
    }
    // Stop every active worker thread BEFORE destroying NDI runtime or calling dlclose()
    for (NdiSourceInstance *inst : active) {
        if (inst) stop_worker(inst);
    }
    std::lock_guard<std::mutex> lock(g_ndi_mutex);
    if (g_finder && g_rt.find_destroy) {
        g_rt.find_destroy(g_finder);
        g_finder = nullptr;
    }
    if (g_rt.initialized && g_rt.destroy) {
        g_rt.destroy();
    }
    if (g_rt.handle) {
        dlclose(g_rt.handle);
        g_rt.handle = nullptr;
    }
    g_rt.initialized = false;
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_registerNdiHost(JNIEnv *env, jclass, jclass hostClass)
{
    std::lock_guard<std::mutex> lock(g_ndi_mutex);
    env->GetJavaVM(&g_ndi_jvm);
    if (!g_ndi_host_class) {
        g_ndi_host_class = static_cast<jclass>(env->NewGlobalRef(hostClass));
    }
    g_mid_list_mdns = env->GetStaticMethodID(g_ndi_host_class, "listNdiMdnsSources", "()Ljava/lang/String;");
    if (env->ExceptionCheck()) { env->ExceptionClear(); g_mid_list_mdns = nullptr; }
    g_mid_pick_ndi_lib = env->GetStaticMethodID(g_ndi_host_class, "pickNdiLibrary", "()V");
    if (env->ExceptionCheck()) { env->ExceptionClear(); g_mid_pick_ndi_lib = nullptr; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_obsandroid_port_NativeBridge_reloadNdiRuntime(JNIEnv *env, jclass, jstring path)
{
    const char *utf = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    bool ok = false;
    {
        std::lock_guard<std::mutex> lock(g_ndi_mutex);
        ok = load_ndi_runtime_locked(utf);
    }
    if (utf) env->ReleaseStringUTFChars(path, utf);
    return ok ? JNI_TRUE : JNI_FALSE;
}
