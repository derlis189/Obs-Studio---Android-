// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-iso-replay.h"
#include <obs.h>
#include <util/platform.h>
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>
#include <nlohmann/json.hpp>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

extern "C" void obs_android_on_recording_finished(const char *file_path);

namespace {

std::string sanitize_filename(const std::string &in)
{
    std::string out;
    out.reserve(in.size());
    for (unsigned char ch : in) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
            (ch >= '0' && ch <= '9') || ch == '-' || ch == '_') {
            out.push_back(char(ch));
        } else if (ch == ' ') {
            out.push_back('_');
        }
    }
    if (out.empty()) out = "source";
    if (out.size() > 40) out.resize(40);
    return out;
}

void ensure_dir_recursive(const std::string &dir)
{
    if (dir.empty()) return;
    std::string cur;
    for (size_t i = 0; i < dir.size(); ++i) {
        cur.push_back(dir[i]);
        if (dir[i] == '/' && cur.size() > 1) {
            mkdir(cur.c_str(), 0755);
        }
    }
    mkdir(dir.c_str(), 0755);
}

std::string timestamp_str()
{
    std::time_t now = std::time(nullptr);
    std::tm tm_val{};
    localtime_r(&now, &tm_val);
    char buf[32] = {};
    std::strftime(buf, sizeof(buf), "%Y-%m-%d_%H-%M-%S", &tm_val);
    return buf;
}

// Split concatenated Annex-B SPS+PPS so AMediaMuxer receives clean csd-0 / csd-1
void split_annexb_csd_iso(const std::vector<uint8_t> &csd, std::vector<uint8_t> &sps, std::vector<uint8_t> &pps)
{
    sps.clear();
    pps.clear();
    if (csd.size() < 5) {
        sps = csd;
        return;
    }
    auto find_sc = [&](size_t from) -> size_t {
        for (size_t i = from; i + 3 < csd.size(); ++i) {
            if (csd[i] == 0 && csd[i + 1] == 0 &&
                (csd[i + 2] == 1 || (csd[i + 2] == 0 && csd[i + 3] == 1))) {
                return i;
            }
        }
        return std::string::npos;
    };
    size_t first = find_sc(0);
    if (first == std::string::npos) { sps = csd; return; }
    size_t second = find_sc(first + 4);
    if (second == std::string::npos) { sps.assign(csd.begin() + first, csd.end()); return; }
    sps.assign(csd.begin() + first, csd.begin() + second);
    pps.assign(csd.begin() + second, csd.end());
}

// =========================================================================
// 1. ISO Multi-Source Recording Engine
// =========================================================================
struct IsoTrack {
    std::string source_name;
    std::string file_path;
    obs_view_t *view{};
    video_t *video{};
    obs_scene_t *scene{};
    obs_sceneitem_t *item{};
    obs_encoder_t *v_enc{};
    obs_encoder_t *a_enc{};
    obs_output_t *output{};
    bool active{false};
};

std::mutex g_iso_mutex;
std::vector<IsoTrack> g_iso_tracks;
int64_t g_iso_start_ms = 0;

void release_iso_track(IsoTrack &tr)
{
    std::string finished_path;
    if (tr.output) {
        if (obs_output_active(tr.output)) {
            obs_output_stop(tr.output);
            // Brief wait for muxer trailer flush (max 1.5s)
            for (int i = 0; i < 30 && obs_output_active(tr.output); ++i) {
                os_sleep_ms(50);
            }
            if (obs_output_active(tr.output)) {
                obs_output_force_stop(tr.output);
            }
        }
        obs_output_release(tr.output);
        tr.output = nullptr;
        finished_path = tr.file_path;
    }
    if (tr.v_enc) { obs_encoder_release(tr.v_enc); tr.v_enc = nullptr; }
    if (tr.a_enc) { obs_encoder_release(tr.a_enc); tr.a_enc = nullptr; }
    if (tr.item) { obs_sceneitem_release(tr.item); tr.item = nullptr; }
    if (tr.view) {
        obs_view_set_source(tr.view, 0, nullptr);
        obs_view_remove(tr.view);
        obs_view_destroy(tr.view);
        tr.view = nullptr;
        tr.video = nullptr;
    }
    if (tr.scene) { obs_scene_release(tr.scene); tr.scene = nullptr; }
    tr.active = false;
    if (!finished_path.empty()) {
        obs_android_on_recording_finished(finished_path.c_str());
    }
}

bool create_source_view(const char *source_name, int width, int height,
                        obs_view_t *&out_view, video_t *&out_video,
                        obs_scene_t *&out_scene, obs_sceneitem_t *&out_item,
                        const char *tag)
{
    obs_source_t *src = obs_get_source_by_name(source_name);
    if (!src) {
        blog(LOG_WARNING, "[%s] Source not found: '%s'", tag, source_name);
        return false;
    }

    obs_video_info main_ovi{};
    if (!obs_get_video_info(&main_ovi)) {
        obs_source_release(src);
        return false;
    }

    int w = std::clamp(width, 320, 1920) & ~1;
    int h = std::clamp(height, 180, 1080) & ~1;

    obs_video_info ovi = main_ovi;
    ovi.base_width = uint32_t(w);
    ovi.base_height = uint32_t(h);
    ovi.output_width = uint32_t(w);
    ovi.output_height = uint32_t(h);
    ovi.output_format = VIDEO_FORMAT_NV12;
    ovi.colorspace = VIDEO_CS_709;
    ovi.range = VIDEO_RANGE_PARTIAL;

    obs_view_t *view = obs_view_create();
    if (!view) {
        obs_source_release(src);
        return false;
    }
    video_t *vid = obs_view_add2(view, &ovi);
    if (!vid) {
        obs_view_destroy(view);
        obs_source_release(src);
        return false;
    }

    std::string scene_id = std::string("OBS_Android_View_") + tag + "_" + sanitize_filename(source_name);
    obs_scene_t *scene = obs_scene_create_private(scene_id.c_str());
    obs_sceneitem_t *item = obs_scene_add(scene, src);
    obs_source_release(src);
    if (!item) {
        obs_view_remove(view);
        obs_view_destroy(view);
        obs_scene_release(scene);
        return false;
    }
    obs_sceneitem_addref(item);

    vec2 pos{0.0f, 0.0f};
    vec2 bounds{float(w), float(h)};
    obs_sceneitem_set_alignment(item, OBS_ALIGN_TOP | OBS_ALIGN_LEFT);
    obs_sceneitem_set_pos(item, &pos);
    obs_sceneitem_set_rot(item, 0.0f);
    obs_sceneitem_set_bounds_type(item, OBS_BOUNDS_SCALE_INNER);
    obs_sceneitem_set_bounds_alignment(item, OBS_ALIGN_CENTER);
    obs_sceneitem_set_bounds(item, &bounds);
    obs_view_set_source(view, 0, obs_scene_get_source(scene));

    out_view = view;
    out_video = vid;
    out_scene = scene;
    out_item = item;
    return true;
}

// =========================================================================
// 2. Instant Replay Ring Buffer Engine (Program or Single Camera/Source)
// =========================================================================
struct ReplayPacket {
    std::vector<uint8_t> data;
    int64_t pts_us{};
    bool keyframe{};
    bool is_audio{};
};

struct ReplayBufferState {
    std::mutex mutex;
    obs_output_t *output{};
    int max_seconds{30};
    int width{1280};
    int height{720};
    int sample_rate{48000};
    std::vector<uint8_t> video_csd;
    std::vector<uint8_t> audio_csd;
    std::deque<ReplayPacket> packets;
};

ReplayBufferState *g_active_replay_state = nullptr;

void replay_prune_locked(ReplayBufferState &st)
{
    if (st.packets.empty()) return;
    int64_t latest_v_us = -1;
    for (auto it = st.packets.rbegin(); it != st.packets.rend(); ++it) {
        if (!it->is_audio) {
            latest_v_us = it->pts_us;
            break;
        }
    }
    if (latest_v_us < 0) return;
    const int64_t window_us = int64_t(std::clamp(st.max_seconds, 5, 65)) * 1000000LL;
    const int64_t cutoff_us = latest_v_us - window_us;

    size_t keep_from = 0;
    for (size_t i = 0; i < st.packets.size(); ++i) {
        const auto &p = st.packets[i];
        if (!p.is_audio && p.keyframe && p.pts_us <= cutoff_us) {
            keep_from = i;
        }
    }
    if (keep_from > 0) {
        st.packets.erase(st.packets.begin(), st.packets.begin() + keep_from);
    }
    // Hard safety cap so RAM stays bounded (~100 MB worst case)
    while (st.packets.size() > 18000) {
        st.packets.pop_front();
    }
}

void replay_encoded_packet(void *data, struct encoder_packet *pkt)
{
    auto *st = static_cast<ReplayBufferState *>(data);
    if (!st || !pkt || !pkt->data || !pkt->size) return;

    const bool is_audio = (pkt->type == OBS_ENCODER_AUDIO);
    int64_t pts_us = 0;
    if (pkt->timebase_den > 0) {
        pts_us = int64_t((double(pkt->pts) * double(pkt->timebase_num) / double(pkt->timebase_den)) * 1000000.0);
    } else {
        pts_us = pkt->dts_usec;
    }

    std::lock_guard<std::mutex> lock(st->mutex);
    if (!is_audio && st->video_csd.empty()) {
        obs_encoder_t *ve = obs_output_get_video_encoder(st->output);
        uint8_t *ex = nullptr;
        size_t ex_sz = 0;
        if (ve && obs_encoder_get_extra_data(ve, &ex, &ex_sz) && ex && ex_sz > 0) {
            st->video_csd.assign(ex, ex + ex_sz);
            st->width = int(obs_encoder_get_width(ve));
            st->height = int(obs_encoder_get_height(ve));
        }
    }
    if (is_audio && st->audio_csd.empty()) {
        obs_encoder_t *ae = obs_output_get_audio_encoder(st->output, 0);
        uint8_t *ex = nullptr;
        size_t ex_sz = 0;
        if (ae && obs_encoder_get_extra_data(ae, &ex, &ex_sz) && ex && ex_sz > 0) {
            st->audio_csd.assign(ex, ex + ex_sz);
            uint32_t sr = obs_encoder_get_sample_rate(ae);
            if (sr > 0) st->sample_rate = int(sr);
        }
    }

    ReplayPacket rp;
    rp.data.assign(pkt->data, pkt->data + pkt->size);
    rp.pts_us = pts_us;
    rp.keyframe = !is_audio && pkt->keyframe;
    rp.is_audio = is_audio;
    st->packets.push_back(std::move(rp));
    replay_prune_locked(*st);
}

bool write_replay_mp4(const char *path,
                      int width, int height, int sample_rate,
                      const std::vector<uint8_t> &video_csd,
                      const std::vector<uint8_t> &audio_csd,
                      const std::vector<ReplayPacket> &pkts)
{
    if (!path || !*path || video_csd.empty() || pkts.empty()) return false;

    size_t first_kf = pkts.size();
    for (size_t i = 0; i < pkts.size(); ++i) {
        if (!pkts[i].is_audio && pkts[i].keyframe) {
            first_kf = i;
            break;
        }
    }
    if (first_kf >= pkts.size()) return false;

    const int64_t base_v_pts_us = pkts[first_kf].pts_us;
    int64_t base_a_pts_us = -1;
    for (size_t i = first_kf; i < pkts.size(); ++i) {
        if (pkts[i].is_audio) {
            base_a_pts_us = pkts[i].pts_us;
            break;
        }
    }

    int fd = open(path, O_CREAT | O_RDWR | O_TRUNC, 0644);
    if (fd < 0) return false;

    AMediaMuxer *muxer = AMediaMuxer_new(fd, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
    if (!muxer) {
        close(fd);
        return false;
    }

    AMediaFormat *vfmt = AMediaFormat_new();
    AMediaFormat_setString(vfmt, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(vfmt, AMEDIAFORMAT_KEY_WIDTH, width > 0 ? width : 1280);
    AMediaFormat_setInt32(vfmt, AMEDIAFORMAT_KEY_HEIGHT, height > 0 ? height : 720);
    std::vector<uint8_t> sps, pps;
    split_annexb_csd_iso(video_csd, sps, pps);
    if (!sps.empty()) AMediaFormat_setBuffer(vfmt, "csd-0", sps.data(), sps.size());
    if (!pps.empty()) AMediaFormat_setBuffer(vfmt, "csd-1", pps.data(), pps.size());
    ssize_t vtrack = AMediaMuxer_addTrack(muxer, vfmt);
    AMediaFormat_delete(vfmt);

    ssize_t atrack = -1;
    if (!audio_csd.empty()) {
        AMediaFormat *afmt = AMediaFormat_new();
        AMediaFormat_setString(afmt, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
        AMediaFormat_setInt32(afmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, sample_rate > 0 ? sample_rate : 48000);
        AMediaFormat_setInt32(afmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, 2);
        AMediaFormat_setBuffer(afmt, "csd-0", audio_csd.data(), audio_csd.size());
        atrack = AMediaMuxer_addTrack(muxer, afmt);
        AMediaFormat_delete(afmt);
    }

    if (vtrack < 0 || AMediaMuxer_start(muxer) != AMEDIA_OK) {
        AMediaMuxer_delete(muxer);
        close(fd);
        unlink(path);
        return false;
    }

    size_t written_v = 0;
    size_t written_a = 0;
    int64_t last_v_us = -1;
    int64_t last_a_us = -1;

    for (size_t i = first_kf; i < pkts.size(); ++i) {
        const auto &p = pkts[i];
        if (p.data.empty()) continue;
        AMediaCodecBufferInfo info{};
        info.offset = 0;
        info.size = int32_t(p.data.size());
        if (!p.is_audio) {
            int64_t pts = std::max<int64_t>(0, p.pts_us - base_v_pts_us);
            if (pts <= last_v_us) pts = last_v_us + 1000;
            last_v_us = pts;
            info.presentationTimeUs = pts;
            info.flags = p.keyframe ? AMEDIACODEC_BUFFER_FLAG_KEY_FRAME : 0;
            if (AMediaMuxer_writeSampleData(muxer, vtrack, p.data.data(), &info) == AMEDIA_OK) {
                ++written_v;
            }
        } else if (atrack >= 0 && base_a_pts_us >= 0) {
            int64_t pts = std::max<int64_t>(0, p.pts_us - base_a_pts_us);
            if (pts <= last_a_us) pts = last_a_us + 1000;
            last_a_us = pts;
            info.presentationTimeUs = pts;
            info.flags = 0;
            if (AMediaMuxer_writeSampleData(muxer, atrack, p.data.data(), &info) == AMEDIA_OK) {
                ++written_a;
            }
        }
    }

    const media_status_t stop_st = AMediaMuxer_stop(muxer);
    AMediaMuxer_delete(muxer);
    close(fd);
    const bool ok = (stop_st == AMEDIA_OK && written_v > 0);
    if (!ok) unlink(path);
    blog(LOG_INFO, "[android-replay] Saved clip '%s' ok=%d v=%zu a=%zu", path, ok, written_v, written_a);
    return ok;
}

struct ReplayEngine {
    std::string source_name; // empty or "__PROGRAM__" = Program output
    int max_seconds{30};
    obs_view_t *view{};
    video_t *video{};
    obs_scene_t *scene{};
    obs_sceneitem_t *item{};
    obs_encoder_t *v_enc{};
    obs_encoder_t *a_enc{};
    obs_output_t *output{};
};

std::mutex g_replay_mutex;
ReplayEngine g_replay;

void stop_replay_locked()
{
    if (g_replay.output) {
        if (obs_output_active(g_replay.output)) obs_output_force_stop(g_replay.output);
        obs_output_release(g_replay.output);
        g_replay.output = nullptr;
    }
    if (g_replay.v_enc) { obs_encoder_release(g_replay.v_enc); g_replay.v_enc = nullptr; }
    if (g_replay.a_enc) { obs_encoder_release(g_replay.a_enc); g_replay.a_enc = nullptr; }
    if (g_replay.item) { obs_sceneitem_release(g_replay.item); g_replay.item = nullptr; }
    if (g_replay.view) {
        obs_view_set_source(g_replay.view, 0, nullptr);
        obs_view_remove(g_replay.view);
        obs_view_destroy(g_replay.view);
        g_replay.view = nullptr;
        g_replay.video = nullptr;
    }
    if (g_replay.scene) { obs_scene_release(g_replay.scene); g_replay.scene = nullptr; }
    g_replay.source_name.clear();
}

} // namespace

void android_iso_replay_register()
{
    obs_output_info rp{};
    rp.id = "android_instant_replay_buffer";
    rp.flags = OBS_OUTPUT_AV | OBS_OUTPUT_ENCODED;
    rp.encoded_video_codecs = "h264";
    rp.encoded_audio_codecs = "aac";
    rp.get_name = [](void *) { return "Instant Replay Ring Buffer"; };
    rp.create = [](obs_data_t *settings, obs_output_t *output) -> void * {
        auto *st = new ReplayBufferState();
        st->output = output;
        st->max_seconds = std::clamp(int(obs_data_get_int(settings, "seconds")), 5, 65);
        g_active_replay_state = st;
        return st;
    };
    rp.destroy = [](void *data) {
        auto *st = static_cast<ReplayBufferState *>(data);
        if (g_active_replay_state == st) g_active_replay_state = nullptr;
        delete st;
    };
    rp.start = [](void *data) -> bool {
        auto *st = static_cast<ReplayBufferState *>(data);
        if (!obs_output_can_begin_data_capture(st->output, 0)) return false;
        if (!obs_output_initialize_encoders(st->output, 0)) return false;
        return obs_output_begin_data_capture(st->output, 0);
    };
    rp.stop = [](void *data, uint64_t) {
        auto *st = static_cast<ReplayBufferState *>(data);
        obs_output_end_data_capture(st->output);
    };
    rp.encoded_packet = replay_encoded_packet;
    obs_register_output(&rp);
}

void android_iso_replay_shutdown()
{
    {
        std::lock_guard<std::mutex> lock(g_iso_mutex);
        for (auto &tr : g_iso_tracks) release_iso_track(tr);
        g_iso_tracks.clear();
        g_iso_start_ms = 0;
    }
    {
        std::lock_guard<std::mutex> lock(g_replay_mutex);
        stop_replay_locked();
    }
}

extern "C" __attribute__((visibility("default"))) bool obs_android_iso_start(const char *config_json)
{
    if (!config_json || !*config_json || !obs_initialized()) return false;
    nlohmann::json cfg;
    try {
        cfg = nlohmann::json::parse(config_json);
    } catch (...) {
        return false;
    }
    if (!cfg.contains("sources") || !cfg["sources"].is_array() || cfg["sources"].empty()) return false;

    const std::string out_dir = cfg.value("dir", std::string("/storage/emulated/0/Movies/OBS Studio/ISO"));
    const int width = std::clamp(cfg.value("width", 1280), 320, 1920) & ~1;
    const int height = std::clamp(cfg.value("height", 720), 180, 1080) & ~1;
    const int bitrate = std::clamp(cfg.value("bitrate", 4000), 800, 12000);
    const bool include_audio = cfg.value("includeAudio", true);
    ensure_dir_recursive(out_dir);

    std::lock_guard<std::mutex> lock(g_iso_mutex);
    for (auto &tr : g_iso_tracks) release_iso_track(tr);
    g_iso_tracks.clear();

    const std::string ts = timestamp_str();
    int started = 0;
    int idx = 0;
    for (const auto &s : cfg["sources"]) {
        if (!s.is_string() || idx >= 4) break;
        const std::string src_name = s.get<std::string>();
        if (src_name.empty()) continue;
        ++idx;

        IsoTrack tr;
        tr.source_name = src_name;
        tr.file_path = out_dir + "/ISO_" + ts + "_" + sanitize_filename(src_name) + ".mp4";

        if (!create_source_view(src_name.c_str(), width, height, tr.view, tr.video, tr.scene, tr.item, "ISO")) {
            continue;
        }

        obs_data_t *vs = obs_data_create();
        obs_data_set_int(vs, "bitrate", bitrate);
        obs_data_set_int(vs, "keyint_sec", 2);
        std::string vname = "iso_venc_" + std::to_string(idx);
        tr.v_enc = obs_video_encoder_create("android_mediacodec_h264", vname.c_str(), vs, nullptr);
        obs_data_release(vs);
        if (!tr.v_enc) {
            release_iso_track(tr);
            continue;
        }
        obs_encoder_set_video(tr.v_enc, tr.video);

        if (include_audio) {
            obs_data_t *as = obs_data_create();
            obs_data_set_int(as, "bitrate", 128);
            std::string aname = "iso_aenc_" + std::to_string(idx);
            tr.a_enc = obs_audio_encoder_create("android_mediacodec_aac", aname.c_str(), as, 0, nullptr);
            obs_data_release(as);
            if (tr.a_enc) obs_encoder_set_audio(tr.a_enc, obs_get_audio());
        }

        obs_data_t *os = obs_data_create();
        obs_data_set_string(os, "path", tr.file_path.c_str());
        obs_data_set_string(os, "muxer_settings", "movflags=frag_keyframe+empty_moov+faststart");
        std::string oname = "iso_mux_" + std::to_string(idx);
        tr.output = obs_output_create("ffmpeg_muxer", oname.c_str(), os, nullptr);
        obs_data_release(os);
        if (!tr.output) {
            release_iso_track(tr);
            continue;
        }

        obs_output_set_video_encoder(tr.output, tr.v_enc);
        if (tr.a_enc) obs_output_set_audio_encoder(tr.output, tr.a_enc, 0);

        if (obs_output_start(tr.output)) {
            tr.active = true;
            g_iso_tracks.push_back(std::move(tr));
            ++started;
            blog(LOG_INFO, "[android-iso] Started ISO track '%s' -> '%s' (%dx%d @ %d kbps)",
                 src_name.c_str(), g_iso_tracks.back().file_path.c_str(), width, height, bitrate);
        } else {
            blog(LOG_WARNING, "[android-iso] Failed to start ISO output for '%s'", src_name.c_str());
            release_iso_track(tr);
        }
    }

    if (started > 0) {
        g_iso_start_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        return true;
    }
    return false;
}

extern "C" __attribute__((visibility("default"))) void obs_android_iso_stop()
{
    std::lock_guard<std::mutex> lock(g_iso_mutex);
    for (auto &tr : g_iso_tracks) release_iso_track(tr);
    g_iso_tracks.clear();
    g_iso_start_ms = 0;
    blog(LOG_INFO, "[android-iso] Stopped all ISO recording tracks");
}

extern "C" __attribute__((visibility("default"))) bool obs_android_iso_get_stats(char *out_buf, size_t out_cap)
{
    if (!out_buf || out_cap == 0) return false;
    out_buf[0] = '\0';
    std::lock_guard<std::mutex> lock(g_iso_mutex);
    nlohmann::json j;
    bool any_active = false;
    nlohmann::json arr = nlohmann::json::array();
    for (const auto &tr : g_iso_tracks) {
        bool act = tr.output && obs_output_active(tr.output);
        if (act) any_active = true;
        arr.push_back({
            {"source", tr.source_name},
            {"path", tr.file_path},
            {"active", act},
            {"frames", tr.output ? int64_t(obs_output_get_total_frames(tr.output)) : 0}
        });
    }
    int elapsed = 0;
    if (any_active && g_iso_start_ms > 0) {
        int64_t now = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        elapsed = int(std::max<int64_t>(0, (now - g_iso_start_ms) / 1000));
    }
    j["active"] = any_active;
    j["elapsedSec"] = elapsed;
    j["tracks"] = arr;
    const std::string dump = j.dump();
    std::snprintf(out_buf, out_cap, "%s", dump.c_str());
    return true;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_replay_start(
    const char *source_name, int max_seconds, int width, int height, int bitrate_kbps)
{
    if (!obs_initialized()) return false;
    std::lock_guard<std::mutex> lock(g_replay_mutex);
    stop_replay_locked();

    const std::string src_str = (source_name && *source_name) ? source_name : "__PROGRAM__";
    const int secs = std::clamp(max_seconds, 5, 60);
    const int br = std::clamp(bitrate_kbps, 1500, 10000);

    video_t *video_feed = nullptr;
    if (src_str == "__PROGRAM__") {
        video_feed = obs_get_video();
    } else {
        if (!create_source_view(src_str.c_str(), width, height,
                                g_replay.view, g_replay.video, g_replay.scene, g_replay.item, "Replay")) {
            return false;
        }
        video_feed = g_replay.video;
    }
    if (!video_feed) {
        stop_replay_locked();
        return false;
    }

    obs_data_t *vs = obs_data_create();
    obs_data_set_int(vs, "bitrate", br);
    obs_data_set_int(vs, "keyint_sec", 1); // 1s GOP for snappy Instant Replay cuts!
    g_replay.v_enc = obs_video_encoder_create("android_mediacodec_h264", "replay_venc", vs, nullptr);
    obs_data_release(vs);
    if (!g_replay.v_enc) {
        stop_replay_locked();
        return false;
    }
    obs_encoder_set_video(g_replay.v_enc, video_feed);

    obs_data_t *as = obs_data_create();
    obs_data_set_int(as, "bitrate", 128);
    g_replay.a_enc = obs_audio_encoder_create("android_mediacodec_aac", "replay_aenc", as, 0, nullptr);
    obs_data_release(as);
    if (g_replay.a_enc) obs_encoder_set_audio(g_replay.a_enc, obs_get_audio());

    obs_data_t *os = obs_data_create();
    obs_data_set_int(os, "seconds", secs + 2);
    g_replay.output = obs_output_create("android_instant_replay_buffer", "replay_out", os, nullptr);
    obs_data_release(os);
    if (!g_replay.output) {
        stop_replay_locked();
        return false;
    }

    obs_output_set_video_encoder(g_replay.output, g_replay.v_enc);
    if (g_replay.a_enc) obs_output_set_audio_encoder(g_replay.output, g_replay.a_enc, 0);

    if (!obs_output_start(g_replay.output)) {
        stop_replay_locked();
        return false;
    }
    g_replay.source_name = src_str;
    g_replay.max_seconds = secs;
    blog(LOG_INFO, "[android-replay] Started buffer on '%s' (%ds, %d kbps)", src_str.c_str(), secs, br);
    return true;
}

extern "C" __attribute__((visibility("default"))) void obs_android_replay_stop()
{
    std::lock_guard<std::mutex> lock(g_replay_mutex);
    stop_replay_locked();
}

extern "C" __attribute__((visibility("default"))) bool obs_android_replay_save_clip(int clip_seconds, const char *out_path)
{
    if (!out_path || !*out_path) return false;

    int w = 1280, h = 720, sr = 48000;
    std::vector<uint8_t> vcsd, acsd;
    std::vector<ReplayPacket> snap;

    {
        std::lock_guard<std::mutex> rlock(g_replay_mutex);
        if (!g_replay.output || !obs_output_active(g_replay.output) || !g_active_replay_state) {
            return false;
        }
        std::lock_guard<std::mutex> plock(g_active_replay_state->mutex);
        auto &st = *g_active_replay_state;
        if (st.packets.empty() || st.video_csd.empty()) return false;

        w = st.width;
        h = st.height;
        sr = st.sample_rate;
        vcsd = st.video_csd;
        acsd = st.audio_csd;

        int64_t latest_v_us = -1;
        for (auto it = st.packets.rbegin(); it != st.packets.rend(); ++it) {
            if (!it->is_audio) { latest_v_us = it->pts_us; break; }
        }
        if (latest_v_us < 0) return false;

        const int want_sec = std::clamp(clip_seconds, 3, 60);
        const int64_t cutoff_us = latest_v_us - int64_t(want_sec) * 1000000LL;

        size_t start_idx = 0;
        for (size_t i = 0; i < st.packets.size(); ++i) {
            if (!st.packets[i].is_audio && st.packets[i].keyframe && st.packets[i].pts_us <= cutoff_us) {
                start_idx = i;
            }
        }
        snap.reserve(st.packets.size() - start_idx);
        for (size_t i = start_idx; i < st.packets.size(); ++i) {
            snap.push_back(st.packets[i]);
        }
    }

    // Ensure parent directory exists
    std::string pstr(out_path);
    auto slash = pstr.find_last_of('/');
    if (slash != std::string::npos) ensure_dir_recursive(pstr.substr(0, slash));

    bool ok = write_replay_mp4(out_path, w, h, sr, vcsd, acsd, snap);
    if (ok) obs_android_on_recording_finished(out_path);
    return ok;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_replay_get_stats(char *out_buf, size_t out_cap)
{
    if (!out_buf || out_cap == 0) return false;
    out_buf[0] = '\0';
    std::lock_guard<std::mutex> rlock(g_replay_mutex);
    bool active = g_replay.output && obs_output_active(g_replay.output) && g_active_replay_state;
    double buffered_sec = 0.0;
    size_t pkt_count = 0;
    if (active) {
        std::lock_guard<std::mutex> plock(g_active_replay_state->mutex);
        const auto &pkts = g_active_replay_state->packets;
        pkt_count = pkts.size();
        int64_t first_v = -1, last_v = -1;
        for (const auto &p : pkts) {
            if (!p.is_audio) {
                if (first_v < 0) first_v = p.pts_us;
                last_v = p.pts_us;
            }
        }
        if (first_v >= 0 && last_v > first_v) {
            buffered_sec = double(last_v - first_v) / 1000000.0;
        }
    }
    nlohmann::json j = {
        {"active", active},
        {"source", g_replay.source_name.empty() ? "__PROGRAM__" : g_replay.source_name},
        {"maxSec", g_replay.max_seconds},
        {"bufferedSec", buffered_sec},
        {"packets", int64_t(pkt_count)}
    };
    const std::string dump = j.dump();
    std::snprintf(out_buf, out_cap, "%s", dump.c_str());
    return true;
}
