// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-multistream.h"
#include <obs.h>
#include <util/platform.h>
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>
#include <fcntl.h>
#include <unistd.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <deque>
#include <util/bmem.h>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>

extern "C" void obs_android_on_recording_finished(const char *file_path);

namespace {
constexpr const char *kTag = "ObsMultiVertical";

struct MultiTarget {
    obs_service_t *service{};
    obs_encoder_t *v_enc{};
    obs_encoder_t *a_enc{};
    obs_output_t *output{};
    bool uses_vertical{false};
};

struct VerticalEngine {
    obs_view_t *view{};
    video_t *video{};
    obs_scene_t *wrapper_scene{};
    obs_sceneitem_t *wrapped_item{};
    obs_source_t *program_canvas{};
    int width{720};
    int height{1280};
    int fit_mode{1}; // 1=fill 9:16 (outer), 0=fit inner
    std::string scene_name;

    // Independent vertical RTMP stream
    obs_service_t *stream_service{};
    obs_encoder_t *stream_venc{};
    obs_encoder_t *stream_aenc{};
    obs_output_t *stream_out{};

    // Independent vertical MP4 recording
    obs_encoder_t *rec_venc{};
    obs_encoder_t *rec_aenc{};
    obs_output_t *rec_out{};
    std::string rec_path;

    // Independent vertical 9:16 Backtrack (Aitum-style instant clip ring buffer)
    obs_encoder_t *bt_venc{};
    obs_encoder_t *bt_aenc{};
    obs_output_t *bt_out{};
    int bt_seconds{30};
};

struct BacktrackPacket {
    std::vector<uint8_t> data;
    int64_t pts_us{};
    bool keyframe{};
    bool is_audio{};
};

struct BacktrackState {
    std::mutex mutex;
    obs_output_t *output{};
    int max_seconds{30};
    int width{720};
    int height{1280};
    int sample_rate{48000};
    std::vector<uint8_t> video_csd;
    std::vector<uint8_t> audio_csd;
    std::deque<BacktrackPacket> packets;
};

BacktrackState *g_active_bt = nullptr;

// Split concatenated Annex-B SPS+PPS (if present) so AMediaMuxer gets clean csd-0 / csd-1
void split_annexb_csd(const std::vector<uint8_t> &csd, std::vector<uint8_t> &sps, std::vector<uint8_t> &pps)
{
    sps.clear();
    pps.clear();
    if (csd.size() < 5) {
        sps = csd;
        return;
    }
    auto find_start_code = [&](size_t from) -> size_t {
        for (size_t i = from; i + 3 < csd.size(); ++i) {
            if (csd[i] == 0 && csd[i + 1] == 0 &&
                (csd[i + 2] == 1 || (csd[i + 2] == 0 && csd[i + 3] == 1))) {
                return i;
            }
        }
        return std::string::npos;
    };
    size_t first = find_start_code(0);
    if (first == std::string::npos) {
        sps = csd;
        return;
    }
    size_t second = find_start_code(first + 4);
    if (second == std::string::npos) {
        sps.assign(csd.begin() + first, csd.end());
        return;
    }
    sps.assign(csd.begin() + first, csd.begin() + second);
    pps.assign(csd.begin() + second, csd.end());
}

void backtrack_prune_locked(BacktrackState &st)
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
    const int64_t window_us = int64_t(std::clamp(st.max_seconds, 10, 120)) * 1000000LL;
    const int64_t cutoff_us = latest_v_us - window_us;

    // Keep packets starting from the latest video keyframe at or before cutoff_us
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
    // Hard safety cap (~90 MB max) so runaway streams can never OOM
    while (st.packets.size() > 16000) {
        st.packets.pop_front();
    }
}

void backtrack_encoded_packet(void *data, struct encoder_packet *pkt)
{
    auto *st = static_cast<BacktrackState *>(data);
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

    BacktrackPacket bp;
    bp.data.assign(pkt->data, pkt->data + pkt->size);
    bp.pts_us = pts_us;
    bp.keyframe = !is_audio && pkt->keyframe;
    bp.is_audio = is_audio;
    st->packets.push_back(std::move(bp));
    backtrack_prune_locked(*st);
}

bool write_backtrack_snapshot_to_mp4(const char *path,
                                     int width, int height, int sample_rate,
                                     const std::vector<uint8_t> &video_csd,
                                     const std::vector<uint8_t> &audio_csd,
                                     const std::vector<BacktrackPacket> &pkts)
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
    if (fd < 0) {
        blog(LOG_ERROR, "[android-vertical] Backtrack open('%s') failed", path);
        return false;
    }

    AMediaMuxer *muxer = AMediaMuxer_new(fd, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
    if (!muxer) {
        close(fd);
        return false;
    }

    AMediaFormat *vfmt = AMediaFormat_new();
    AMediaFormat_setString(vfmt, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(vfmt, AMEDIAFORMAT_KEY_WIDTH, width > 0 ? width : 720);
    AMediaFormat_setInt32(vfmt, AMEDIAFORMAT_KEY_HEIGHT, height > 0 ? height : 1280);
    std::vector<uint8_t> sps, pps;
    split_annexb_csd(video_csd, sps, pps);
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

    int64_t last_v_pts = -1;
    int64_t last_a_pts = -1;
    size_t written_v = 0;
    size_t written_a = 0;
    for (size_t i = first_kf; i < pkts.size(); ++i) {
        const auto &p = pkts[i];
        AMediaCodecBufferInfo bi{};
        bi.offset = 0;
        bi.size = int32_t(p.data.size());
        if (!p.is_audio) {
            int64_t rel_us = p.pts_us - base_v_pts_us;
            if (rel_us < 0) continue;
            if (rel_us <= last_v_pts) rel_us = last_v_pts + 1000;
            last_v_pts = rel_us;
            bi.presentationTimeUs = rel_us;
            bi.flags = p.keyframe ? AMEDIACODEC_BUFFER_FLAG_KEY_FRAME : 0;
            if (AMediaMuxer_writeSampleData(muxer, size_t(vtrack), p.data.data(), &bi) == AMEDIA_OK)
                ++written_v;
        } else if (atrack >= 0 && base_a_pts_us >= 0) {
            int64_t rel_us = p.pts_us - base_a_pts_us;
            if (rel_us < 0) continue;
            if (rel_us <= last_a_pts) rel_us = last_a_pts + 1000;
            last_a_pts = rel_us;
            bi.presentationTimeUs = rel_us;
            bi.flags = 0;
            if (AMediaMuxer_writeSampleData(muxer, size_t(atrack), p.data.data(), &bi) == AMEDIA_OK)
                ++written_a;
        }
    }

    const media_status_t stop_st = AMediaMuxer_stop(muxer);
    AMediaMuxer_delete(muxer);
    close(fd);
    const bool ok = (stop_st == AMEDIA_OK && written_v > 0);
    if (!ok) unlink(path);
    blog(LOG_INFO, "[android-vertical] Backtrack clip saved '%s' ok=%d v_frames=%zu a_frames=%zu",
         path, ok, written_v, written_a);
    return ok;
}

std::mutex g_ms_mutex;
std::map<std::string, MultiTarget> g_targets;
VerticalEngine g_vert;

void release_multi_target(MultiTarget &t)
{
    if (t.output) {
        if (obs_output_active(t.output)) obs_output_stop(t.output);
        obs_output_release(t.output);
        t.output = nullptr;
    }
    if (t.v_enc) { obs_encoder_release(t.v_enc); t.v_enc = nullptr; }
    if (t.a_enc) { obs_encoder_release(t.a_enc); t.a_enc = nullptr; }
    if (t.service) { obs_service_release(t.service); t.service = nullptr; }
}

obs_encoder_t *create_video_encoder(const char *name, video_t *video, int bitrate_kbps)
{
    obs_data_t *vs = obs_data_create();
    obs_data_set_int(vs, "bitrate", bitrate_kbps > 300 ? bitrate_kbps : 3500);
    obs_data_set_int(vs, "keyint_sec", 2);
    obs_encoder_t *enc = obs_video_encoder_create("android_mediacodec_h264", name, vs, nullptr);
    obs_data_release(vs);
    if (enc) obs_encoder_set_video(enc, video);
    return enc;
}

obs_encoder_t *create_audio_encoder(const char *name)
{
    obs_data_t *as = obs_data_create();
    obs_data_set_int(as, "bitrate", 128);
    obs_encoder_t *enc = obs_audio_encoder_create("android_mediacodec_aac", name, as, 0, nullptr);
    obs_data_release(as);
    if (enc) obs_encoder_set_audio(enc, obs_get_audio());
    return enc;
}

struct ProgramCanvas {
    std::mutex mutex;
    obs_source_t *background{};
    std::string path;
    uint32_t width{}, height{};
    int mode{1};
    int crop_x{0};
    int pos_x{0};
    int pos_y{0};
    int scale_pct{100};
    int blur_amount{65};
    gs_effect_t *blur{};
    bool blur_initialized{};
    gs_texrender_t *blur_a{};
    gs_texrender_t *blur_b{};
};

constexpr const char *kBlurEffect = R"(
uniform float4x4 ViewProj;
uniform texture2d image;
uniform float2 cover;
uniform float2 direction;
uniform float dim;
sampler_state linear_clamp {
    Filter = Linear;
    AddressU = Clamp;
    AddressV = Clamp;
};
struct Vertex { float4 pos : POSITION; float2 uv : TEXCOORD0; };
Vertex VS(Vertex v_in)
{
    Vertex vert_out;
    vert_out.pos = mul(float4(v_in.pos.xyz, 1.0), ViewProj);
    vert_out.uv = v_in.uv;
    return vert_out;
}
float4 PS(Vertex v_in) : TARGET
{
    float2 uv = (v_in.uv - float2(0.5, 0.5)) * cover + float2(0.5, 0.5);
    float4 col = image.Sample(linear_clamp, uv) * 0.1630;
    col += (image.Sample(linear_clamp, uv + direction * 1.0) + image.Sample(linear_clamp, uv - direction * 1.0)) * 0.1524;
    col += (image.Sample(linear_clamp, uv + direction * 2.0) + image.Sample(linear_clamp, uv - direction * 2.0)) * 0.1251;
    col += (image.Sample(linear_clamp, uv + direction * 3.0) + image.Sample(linear_clamp, uv - direction * 3.0)) * 0.0899;
    col += (image.Sample(linear_clamp, uv + direction * 4.0) + image.Sample(linear_clamp, uv - direction * 4.0)) * 0.0511;
    return float4(col.rgb * dim, 1.0);
}
technique Draw
{
    pass {
        vertex_shader = VS(v_in);
        pixel_shader = PS(v_in);
    }
}
)";

std::string g_vertical_background;
int g_vertical_crop_x = 0;
int g_vertical_pos_x = 0;
int g_vertical_pos_y = 0;
int g_vertical_scale = 100;
int g_vertical_blur = 65;

void update_program_canvas(void *data, obs_data_t *settings)
{
    auto &canvas = *static_cast<ProgramCanvas *>(data);
    const int mode = int(obs_data_get_int(settings, "mode"));
    const std::string path = obs_data_get_string(settings, "background");
    const int crop_x = std::clamp(int(obs_data_get_int(settings, "crop_x")), -100, 100);
    const int pos_x = std::clamp(int(obs_data_get_int(settings, "pos_x")), -100, 100);
    const int pos_y = std::clamp(int(obs_data_get_int(settings, "pos_y")), -100, 100);
    const int scale_raw = obs_data_has_user_value(settings, "scale") ? int(obs_data_get_int(settings, "scale")) : 100;
    const int scale_pct = std::clamp(scale_raw, 25, 180);
    const int blur_raw = obs_data_has_user_value(settings, "blur") ? int(obs_data_get_int(settings, "blur")) : 65;
    const int blur_amount = std::clamp(blur_raw, 15, 100);

    bool need_image_reload = false;
    {
        std::lock_guard<std::mutex> lock(canvas.mutex);
        need_image_reload = (canvas.mode != mode || canvas.path != path) && (mode == 2 || canvas.background != nullptr);
        canvas.crop_x = crop_x;
        canvas.pos_x = pos_x;
        canvas.pos_y = pos_y;
        canvas.scale_pct = scale_pct;
        canvas.blur_amount = blur_amount;
        if (!need_image_reload) {
            canvas.mode = mode;
            canvas.path = path;
            return;
        }
    }
    obs_source_t *image = nullptr;
    if (mode == 2 && !path.empty()) {
        obs_data_t *image_settings = obs_data_create();
        obs_data_set_string(image_settings, "file", path.c_str());
        obs_data_set_bool(image_settings, "unload", false);
        image = obs_source_create_private("image_source", "Vertical background", image_settings);
        obs_data_release(image_settings);
    }
    obs_source_t *previous = nullptr;
    {
        std::lock_guard<std::mutex> lock(canvas.mutex);
        previous = canvas.background;
        canvas.background = image;
        canvas.path = path;
        canvas.mode = mode;
        canvas.crop_x = crop_x;
        canvas.pos_x = pos_x;
        canvas.pos_y = pos_y;
        canvas.scale_pct = scale_pct;
        canvas.blur_amount = blur_amount;
    }
    obs_source_release(previous);
}

void draw_fitted(const ProgramCanvas &canvas, float sw, float sh, bool fill,
                 obs_source_t *source, int pan_x_pct = 0, int pos_y_pct = 0, int scale_pct = 100)
{
    if (sw <= 0 || sh <= 0)
        return;
    const float cw = float(canvas.width);
    const float ch = float(canvas.height);
    const float sx = cw / sw;
    const float sy = ch / sh;
    float scale = 1.0f;
    float tx = 0.0f;
    float ty = 0.0f;
    if (fill) {
        scale = std::max(sx, sy);
        const float rw = sw * scale;
        const float rh = sh * scale;
        const float tx_norm = std::clamp(pan_x_pct, -100, 100) / 100.0f;
        const float ty_norm = std::clamp(pos_y_pct, -100, 100) / 100.0f;
        tx = (cw - rw) * (0.5f + 0.5f * tx_norm);
        ty = (ch - rh) * (0.5f + 0.5f * ty_norm);
    } else {
        const float s = std::clamp(scale_pct, 25, 180) / 100.0f;
        scale = std::min(sx, sy) * s;
        const float rw = sw * scale;
        const float rh = sh * scale;
        const float max_dx = std::fabs(cw - rw) * 0.5f;
        const float max_dy = std::fabs(ch - rh) * 0.5f;
        const float tx_norm = std::clamp(pan_x_pct, -100, 100) / 100.0f;
        const float ty_norm = std::clamp(pos_y_pct, -100, 100) / 100.0f;
        tx = (cw - rw) * 0.5f + tx_norm * max_dx;
        ty = (ch - rh) * 0.5f + ty_norm * max_dy;
    }
    gs_matrix_push();
    gs_matrix_translate3f(tx, ty, 0);
    gs_matrix_scale3f(scale, scale, 1);
    if (source)
        obs_source_video_render(source);
    else
        obs_render_main_texture();
    gs_matrix_pop();
}

bool run_blur_pass(gs_effect_t *effect, gs_texrender_t *target, gs_texture_t *src,
                   uint32_t w, uint32_t h, const vec2 &cover, const vec2 &direction, float dim)
{
    if (!effect || !target || !src)
        return false;
    gs_texrender_reset(target);
    if (!gs_texrender_begin(target, w, h))
        return false;
    gs_ortho(0, float(w), 0, float(h), -100, 100);
    gs_matrix_push();
    gs_matrix_identity();
    gs_blend_state_push();
    gs_enable_blending(false);
    gs_effect_set_texture(gs_effect_get_param_by_name(effect, "image"), src);
    gs_effect_set_vec2(gs_effect_get_param_by_name(effect, "cover"), &cover);
    gs_effect_set_vec2(gs_effect_get_param_by_name(effect, "direction"), &direction);
    gs_effect_set_float(gs_effect_get_param_by_name(effect, "dim"), dim);
    while (gs_effect_loop(effect, "Draw"))
        gs_draw_sprite(src, 0, w, h);
    gs_blend_state_pop();
    gs_matrix_pop();
    gs_texrender_end(target);
    return true;
}

void draw_blurred(ProgramCanvas &canvas, gs_texture_t *program, int blur_amount)
{
    constexpr uint32_t low_w = 144, low_h = 256;
    if (!canvas.blur_initialized) {
        canvas.blur_initialized = true;
        char *error = nullptr;
        canvas.blur = gs_effect_create(kBlurEffect, "android_vertical_blur", &error);
        if (error) {
            blog(LOG_ERROR, "[android-vertical] %s", error);
            bfree(error);
        }
    }
    if (!canvas.blur)
        return;
    if (!canvas.blur_a)
        canvas.blur_a = gs_texrender_create(GS_RGBA, GS_ZS_NONE);
    if (!canvas.blur_b)
        canvas.blur_b = gs_texrender_create(GS_RGBA, GS_ZS_NONE);
    if (!canvas.blur_a || !canvas.blur_b)
        return;

    const float sw = float(gs_texture_get_width(program));
    const float sh = float(gs_texture_get_height(program));
    if (sw <= 0 || sh <= 0)
        return;
    const float cover_scale = std::max(float(low_w) / sw, float(low_h) / sh);
    const vec2 cover{float(low_w) / (sw * cover_scale), float(low_h) / (sh * cover_scale)};
    const vec2 unit{1.0f, 1.0f};
    const float t = std::clamp(blur_amount, 15, 100) / 100.0f;
    const float step1 = 0.85f + 1.35f * t;
    const float step2 = 1.25f + 2.25f * t;

    const vec2 dir_h1{(step1 / float(low_w)) * cover.x, 0.0f};
    const vec2 dir_v1{0.0f, step1 / float(low_h)};
    const vec2 dir_h2{step2 / float(low_w), 0.0f};
    const vec2 dir_v2{0.0f, step2 / float(low_h)};

    if (!run_blur_pass(canvas.blur, canvas.blur_a, program, low_w, low_h, cover, dir_h1, 1.0f))
        return;
    auto *tex_a = gs_texrender_get_texture(canvas.blur_a);
    if (!run_blur_pass(canvas.blur, canvas.blur_b, tex_a, low_w, low_h, unit, dir_v1, 1.0f))
        return;
    auto *tex_b = gs_texrender_get_texture(canvas.blur_b);
    if (!run_blur_pass(canvas.blur, canvas.blur_a, tex_b, low_w, low_h, unit, dir_h2, 1.0f))
        return;
    tex_a = gs_texrender_get_texture(canvas.blur_a);
    if (!run_blur_pass(canvas.blur, canvas.blur_b, tex_a, low_w, low_h, unit, dir_v2, 0.82f))
        return;

    auto *texture = gs_texrender_get_texture(canvas.blur_b);
    auto *effect = obs_get_base_effect(OBS_EFFECT_DEFAULT);
    gs_effect_set_texture(gs_effect_get_param_by_name(effect, "image"), texture);
    while (gs_effect_loop(effect, "Draw"))
        gs_draw_sprite(texture, 0, canvas.width, canvas.height);
}

void render_program_canvas(void *data, gs_effect_t *)
{
    auto &canvas = *static_cast<ProgramCanvas *>(data);
    obs_source_t *background = nullptr;
    int mode = 1, crop_x = 0, pos_x = 0, pos_y = 0, scale_pct = 100, blur_amount = 65;
    {
        std::lock_guard<std::mutex> lock(canvas.mutex);
        mode = canvas.mode;
        crop_x = canvas.crop_x;
        pos_x = canvas.pos_x;
        pos_y = canvas.pos_y;
        scale_pct = canvas.scale_pct;
        blur_amount = canvas.blur_amount;
        background = obs_source_get_ref(canvas.background);
    }
    auto *program = obs_get_main_texture();
    if (mode == 1) {
        obs_source_release(background);
        if (program)
            draw_fitted(canvas, float(gs_texture_get_width(program)), float(gs_texture_get_height(program)),
                        true, nullptr, crop_x, 0, 100);
        return;
    }
    if (mode == 3 && program) {
        draw_blurred(canvas, program, blur_amount);
    } else if (background) {
        draw_fitted(canvas, float(obs_source_get_width(background)), float(obs_source_get_height(background)),
                    true, background, 0, 0, 100);
    } else {
        auto *solid = obs_get_base_effect(OBS_EFFECT_SOLID);
        if (solid) {
            vec4 bg{0.07f, 0.09f, 0.13f, 1.0f};
            gs_effect_set_vec4(gs_effect_get_param_by_name(solid, "color"), &bg);
            while (gs_effect_loop(solid, "Solid"))
                gs_draw_sprite(nullptr, 0, canvas.width, canvas.height);
        }
    }
    obs_source_release(background);
    if (program)
        draw_fitted(canvas, float(gs_texture_get_width(program)), float(gs_texture_get_height(program)),
                    false, nullptr, pos_x, pos_y, scale_pct);
}

obs_source_t *create_program_canvas(int width, int height, int mode, const char *background,
                                    int crop_x, int pos_x, int pos_y, int scale_pct, int blur_amount)
{
    obs_data_t *settings = obs_data_create();
    obs_data_set_int(settings, "width", width);
    obs_data_set_int(settings, "height", height);
    obs_data_set_int(settings, "mode", mode);
    obs_data_set_string(settings, "background", background ? background : "");
    obs_data_set_int(settings, "crop_x", crop_x);
    obs_data_set_int(settings, "pos_x", pos_x);
    obs_data_set_int(settings, "pos_y", pos_y);
    obs_data_set_int(settings, "scale", scale_pct);
    obs_data_set_int(settings, "blur", blur_amount);
    auto *source = obs_source_create_private("android_vertical_program_canvas", "Vertical Program", settings);
    obs_data_release(settings);
    return source;
}

void configure_program_canvas(obs_source_t *source, int mode, const char *background,
                              int crop_x, int pos_x, int pos_y, int scale_pct, int blur_amount)
{
    if (!source)
        return;
    obs_data_t *settings = obs_data_create();
    obs_data_set_int(settings, "mode", mode);
    obs_data_set_string(settings, "background", background ? background : "");
    obs_data_set_int(settings, "crop_x", crop_x);
    obs_data_set_int(settings, "pos_x", pos_x);
    obs_data_set_int(settings, "pos_y", pos_y);
    obs_data_set_int(settings, "scale", scale_pct);
    obs_data_set_int(settings, "blur", blur_amount);
    obs_source_update(source, settings);
    obs_data_release(settings);
}

void update_vertical_wrapped_scene_locked(const char *scene_name, int fit_mode)
{
    if (!g_vert.wrapper_scene) return;
    g_vert.fit_mode = fit_mode;
    g_vert.scene_name = scene_name ? scene_name : "";

    obs_source_t *target_src = nullptr;
    const bool use_program_canvas = (fit_mode >= 2) || (fit_mode == 1 && (!scene_name || !*scene_name));
    if (use_program_canvas) {
        if (!g_vert.program_canvas) {
            g_vert.program_canvas = create_program_canvas(g_vert.width, g_vert.height, fit_mode,
                                                          g_vertical_background.c_str(),
                                                          g_vertical_crop_x, g_vertical_pos_x,
                                                          g_vertical_pos_y, g_vertical_scale,
                                                          g_vertical_blur);
        } else {
            configure_program_canvas(g_vert.program_canvas, fit_mode, g_vertical_background.c_str(),
                                     g_vertical_crop_x, g_vertical_pos_x,
                                     g_vertical_pos_y, g_vertical_scale,
                                     g_vertical_blur);
        }
        target_src = obs_source_get_ref(g_vert.program_canvas);
    } else if (scene_name && *scene_name) {
        target_src = obs_get_source_by_name(scene_name);
    }
    if (!target_src) {
        target_src = obs_get_output_source(0);
    }
    if (!target_src) return;

    if (g_vert.wrapped_item) {
        if (obs_sceneitem_get_source(g_vert.wrapped_item) != target_src) {
            obs_sceneitem_remove(g_vert.wrapped_item);
            obs_sceneitem_release(g_vert.wrapped_item);
            g_vert.wrapped_item = nullptr;
        }
    }
    if (!g_vert.wrapped_item) {
        obs_sceneitem_t *item = obs_scene_add(g_vert.wrapper_scene, target_src);
        if (item) {
            obs_sceneitem_addref(item);
            g_vert.wrapped_item = item;
        }
    }
    if (g_vert.wrapped_item) {
        vec2 pos{0.0f, 0.0f};
        vec2 bounds{float(g_vert.width), float(g_vert.height)};
        obs_sceneitem_set_alignment(g_vert.wrapped_item, OBS_ALIGN_TOP | OBS_ALIGN_LEFT);
        obs_sceneitem_set_pos(g_vert.wrapped_item, &pos);
        obs_sceneitem_set_rot(g_vert.wrapped_item, 0.0f);
        obs_sceneitem_set_bounds_type(
            g_vert.wrapped_item, (use_program_canvas || fit_mode == 1) ? OBS_BOUNDS_SCALE_OUTER : OBS_BOUNDS_SCALE_INNER);
        obs_sceneitem_set_bounds_alignment(g_vert.wrapped_item, OBS_ALIGN_CENTER);
        obs_sceneitem_set_bounds(g_vert.wrapped_item, &bounds);
    }
    obs_source_release(target_src);
}

bool ensure_vertical_canvas_locked(int width, int height, const char *scene_name, int fit_mode)
{
    int w = (width >= 360 && width <= 1080) ? (width & ~1) : 720;
    int h = (height >= 640 && height <= 1920) ? (height & ~1) : 1280;

    if (g_vert.view && g_vert.video && g_vert.width == w && g_vert.height == h) {
        update_vertical_wrapped_scene_locked(scene_name, fit_mode);
        return true;
    }

    if (g_vert.stream_out && obs_output_active(g_vert.stream_out)) return false;
    if (g_vert.rec_out && obs_output_active(g_vert.rec_out)) return false;
    if (g_vert.bt_out && obs_output_active(g_vert.bt_out)) return false;

    // Tear down any previous view
    if (g_vert.wrapped_item) { obs_sceneitem_release(g_vert.wrapped_item); g_vert.wrapped_item = nullptr; }
    if (g_vert.view) {
        obs_view_set_source(g_vert.view, 0, nullptr);
        obs_view_remove(g_vert.view);
        obs_view_destroy(g_vert.view);
        g_vert.view = nullptr;
        g_vert.video = nullptr;
    }
    if (g_vert.wrapper_scene) { obs_scene_release(g_vert.wrapper_scene); g_vert.wrapper_scene = nullptr; }
    obs_source_release(g_vert.program_canvas);
    g_vert.program_canvas = nullptr;

    obs_video_info ovi{};
    if (!obs_get_video_info(&ovi)) return false;
    ovi.base_width = uint32_t(w);
    ovi.base_height = uint32_t(h);
    ovi.output_width = uint32_t(w);
    ovi.output_height = uint32_t(h);

    g_vert.view = obs_view_create();
    if (!g_vert.view) return false;
    g_vert.video = obs_view_add2(g_vert.view, &ovi);
    if (!g_vert.video) {
        obs_view_destroy(g_vert.view);
        g_vert.view = nullptr;
        return false;
    }
    g_vert.width = w;
    g_vert.height = h;
    g_vert.wrapper_scene = obs_scene_create_private("OBS_Android_Vertical_9x16");
    obs_view_set_source(g_vert.view, 0, obs_scene_get_source(g_vert.wrapper_scene));
    update_vertical_wrapped_scene_locked(scene_name, fit_mode);
    blog(LOG_INFO, "[android-vertical] Created independent 9:16 canvas %dx%d (fit_mode=%d)", w, h, fit_mode);
    return true;
}

void maybe_teardown_vertical_canvas_locked()
{
    bool streaming = g_vert.stream_out && obs_output_active(g_vert.stream_out);
    bool recording = g_vert.rec_out && obs_output_active(g_vert.rec_out);
    bool backtrack = g_vert.bt_out && obs_output_active(g_vert.bt_out);
    bool multi_vert = false;
    for (const auto &kv : g_targets) {
        if (kv.second.uses_vertical && kv.second.output && obs_output_active(kv.second.output)) {
            multi_vert = true;
            break;
        }
    }
    if (streaming || recording || backtrack || multi_vert) return;
    if (g_vert.wrapped_item) { obs_sceneitem_release(g_vert.wrapped_item); g_vert.wrapped_item = nullptr; }
    if (g_vert.view) {
        obs_view_set_source(g_vert.view, 0, nullptr);
        obs_view_remove(g_vert.view);
        obs_view_destroy(g_vert.view);
        g_vert.view = nullptr;
        g_vert.video = nullptr;
    }
    if (g_vert.wrapper_scene) { obs_scene_release(g_vert.wrapper_scene); g_vert.wrapper_scene = nullptr; }
    obs_source_release(g_vert.program_canvas);
    g_vert.program_canvas = nullptr;
}
} // namespace

void android_vertical_register()
{
    obs_source_info info{};
    info.id = "android_vertical_program_canvas";
    info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_VIDEO | OBS_SOURCE_CUSTOM_DRAW | OBS_SOURCE_SRGB | OBS_SOURCE_CAP_DISABLED;
    info.get_name = [](void *) { return "Vertical Program"; };
    info.create = [](obs_data_t *settings, obs_source_t *) -> void * {
        auto *canvas = new ProgramCanvas;
        canvas->width = uint32_t(std::max<int64_t>(1, obs_data_get_int(settings, "width")));
        canvas->height = uint32_t(std::max<int64_t>(1, obs_data_get_int(settings, "height")));
        update_program_canvas(canvas, settings);
        return canvas;
    };
    info.update = update_program_canvas;
    info.destroy = [](void *data) {
        auto *canvas = static_cast<ProgramCanvas *>(data);
        obs_source_release(canvas->background);
        obs_enter_graphics();
        if (canvas->blur_a) gs_texrender_destroy(canvas->blur_a);
        if (canvas->blur_b) gs_texrender_destroy(canvas->blur_b);
        if (canvas->blur) gs_effect_destroy(canvas->blur);
        obs_leave_graphics();
        delete canvas;
    };
    info.get_width = [](void *data) { return static_cast<ProgramCanvas *>(data)->width; };
    info.get_height = [](void *data) { return static_cast<ProgramCanvas *>(data)->height; };
    info.video_render = render_program_canvas;
    obs_register_source(&info);

    obs_output_info bt{};
    bt.id = "android_vertical_backtrack";
    bt.flags = OBS_OUTPUT_AV | OBS_OUTPUT_ENCODED;
    bt.encoded_video_codecs = "h264";
    bt.encoded_audio_codecs = "aac";
    bt.get_name = [](void *) { return "Vertical Backtrack Ring Buffer"; };
    bt.create = [](obs_data_t *settings, obs_output_t *output) -> void * {
        auto *st = new BacktrackState();
        st->output = output;
        st->max_seconds = std::clamp(int(obs_data_get_int(settings, "seconds")), 10, 120);
        g_active_bt = st;
        return st;
    };
    bt.destroy = [](void *data) {
        auto *st = static_cast<BacktrackState *>(data);
        if (g_active_bt == st) g_active_bt = nullptr;
        delete st;
    };
    bt.start = [](void *data) -> bool {
        auto *st = static_cast<BacktrackState *>(data);
        if (!obs_output_can_begin_data_capture(st->output, 0)) return false;
        if (!obs_output_initialize_encoders(st->output, 0)) return false;
        return obs_output_begin_data_capture(st->output, 0);
    };
    bt.stop = [](void *data, uint64_t) {
        auto *st = static_cast<BacktrackState *>(data);
        obs_output_end_data_capture(st->output);
    };
    bt.encoded_packet = backtrack_encoded_packet;
    obs_register_output(&bt);
}

extern "C" __attribute__((visibility("default"))) obs_source_t *
obs_android_vertical_preview_create(int mode, const char *background)
{
    int crop_x = 0, pos_x = 0, pos_y = 0, scale_pct = 100, blur = 65;
    {
        std::lock_guard<std::mutex> lock(g_ms_mutex);
        crop_x = g_vertical_crop_x;
        pos_x = g_vertical_pos_x;
        pos_y = g_vertical_pos_y;
        scale_pct = g_vertical_scale;
        blur = g_vertical_blur;
    }
    return create_program_canvas(360, 640, mode, background, crop_x, pos_x, pos_y, scale_pct, blur);
}

extern "C" __attribute__((visibility("default"))) void
obs_android_vertical_preview_update_ex(obs_source_t *source, int mode, const char *background,
                                       int crop_x, int pos_x, int pos_y, int scale_pct, int blur_amount)
{
    configure_program_canvas(source, mode, background, crop_x, pos_x, pos_y, scale_pct, blur_amount);
}

extern "C" __attribute__((visibility("default"))) void
obs_android_vertical_preview_update(obs_source_t *source, int mode, const char *background,
                                    int crop_x, int pos_y, int blur_amount)
{
    int pos_x = 0, scale_pct = 100;
    {
        std::lock_guard<std::mutex> lock(g_ms_mutex);
        pos_x = g_vertical_pos_x;
        scale_pct = g_vertical_scale;
    }
    configure_program_canvas(source, mode, background, crop_x, pos_x, pos_y, scale_pct, blur_amount);
}

extern "C" __attribute__((visibility("default"))) void
obs_android_vertical_set_background(const char *background)
{
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    g_vertical_background = background ? background : "";
    if (g_vert.program_canvas) {
        configure_program_canvas(g_vert.program_canvas, g_vert.fit_mode, g_vertical_background.c_str(),
                                 g_vertical_crop_x, g_vertical_pos_x, g_vertical_pos_y,
                                 g_vertical_scale, g_vertical_blur);
    }
}

extern "C" __attribute__((visibility("default"))) void
obs_android_vertical_set_transform(int crop_x, int pos_x, int pos_y, int scale_pct, int blur_amount)
{
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    g_vertical_crop_x = std::clamp(crop_x, -100, 100);
    g_vertical_pos_x = std::clamp(pos_x, -100, 100);
    g_vertical_pos_y = std::clamp(pos_y, -100, 100);
    g_vertical_scale = std::clamp(scale_pct, 25, 180);
    g_vertical_blur = std::clamp(blur_amount, 15, 100);
    if (g_vert.program_canvas) {
        configure_program_canvas(g_vert.program_canvas, g_vert.fit_mode, g_vertical_background.c_str(),
                                 g_vertical_crop_x, g_vertical_pos_x, g_vertical_pos_y,
                                 g_vertical_scale, g_vertical_blur);
    }
}

extern "C" __attribute__((visibility("default"))) void
obs_android_vertical_set_params(int crop_x, int pos_y, int blur_amount)
{
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    g_vertical_crop_x = std::clamp(crop_x, -100, 100);
    g_vertical_pos_y = std::clamp(pos_y, -100, 100);
    g_vertical_blur = std::clamp(blur_amount, 15, 100);
    if (g_vert.program_canvas) {
        configure_program_canvas(g_vert.program_canvas, g_vert.fit_mode, g_vertical_background.c_str(),
                                 g_vertical_crop_x, g_vertical_pos_x, g_vertical_pos_y,
                                 g_vertical_scale, g_vertical_blur);
    }
}

extern "C" __attribute__((visibility("default"))) bool obs_android_multistream_start_target_ex(
    const char *id, const char *server, const char *key, int bitrate_kbps,
    bool use_vertical, int vert_w, int vert_h, const char *vert_scene, int fit_mode)
{
    if (!id || !*id || !server || !*server || !obs_initialized()) return false;
    std::lock_guard<std::mutex> lock(g_ms_mutex);

    auto &t = g_targets[id];
    release_multi_target(t);
    t.uses_vertical = use_vertical;

    video_t *video_feed = obs_get_video();
    if (use_vertical) {
        if (!ensure_vertical_canvas_locked(vert_w, vert_h, vert_scene, fit_mode)) return false;
        video_feed = g_vert.video;
    }

    obs_data_t *ss = obs_data_create();
    obs_data_set_string(ss, "server", server);
    obs_data_set_string(ss, "key", key ? key : "");
    std::string svc_name = std::string("multi_svc_") + id;
    t.service = obs_service_create("rtmp_custom", svc_name.c_str(), ss, nullptr);
    obs_data_release(ss);
    if (!t.service) return false;

    std::string vname = std::string("multi_venc_") + id;
    std::string aname = std::string("multi_aenc_") + id;
    std::string oname = std::string("multi_out_") + id;
    t.v_enc = create_video_encoder(vname.c_str(), video_feed, bitrate_kbps);
    t.a_enc = create_audio_encoder(aname.c_str());
    t.output = obs_output_create("rtmp_output", oname.c_str(), nullptr, nullptr);
    if (!t.v_enc || !t.a_enc || !t.output) {
        release_multi_target(t);
        maybe_teardown_vertical_canvas_locked();
        return false;
    }

    obs_output_set_video_encoder(t.output, t.v_enc);
    obs_output_set_audio_encoder(t.output, t.a_enc, 0);
    obs_output_set_service(t.output, t.service);
    obs_output_set_reconnect_settings(t.output, 20, 5);

    bool ok = obs_output_start(t.output);
    blog(LOG_INFO, "[android-multistream] Target '%s' (%s, vertical=%d) start=%d", id, server, use_vertical, ok);
    if (!ok) {
        release_multi_target(t);
        maybe_teardown_vertical_canvas_locked();
    }
    return ok;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_multistream_start_target(
    const char *id, const char *server, const char *key, int bitrate_kbps)
{
    return obs_android_multistream_start_target_ex(id, server, key, bitrate_kbps, false, 720, 1280, "", 1);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_multistream_stop_target(const char *id)
{
    if (!id || !*id) return false;
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    auto it = g_targets.find(id);
    if (it == g_targets.end()) return false;
    release_multi_target(it->second);
    g_targets.erase(it);
    maybe_teardown_vertical_canvas_locked();
    blog(LOG_INFO, "[android-multistream] Stopped target '%s'", id);
    return true;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_multistream_is_active(const char *id)
{
    if (!id || !*id) return false;
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    auto it = g_targets.find(id);
    return it != g_targets.end() && it->second.output &&
           (obs_output_active(it->second.output) || obs_output_reconnecting(it->second.output));
}

extern "C" __attribute__((visibility("default"))) bool obs_android_multistream_is_reconnecting(const char *id)
{
    if (!id || !*id) return false;
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    auto it = g_targets.find(id);
    return it != g_targets.end() && it->second.output && obs_output_reconnecting(it->second.output);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_vertical_start_stream(
    const char *server, const char *key, int width, int height, int bitrate_kbps,
    const char *scene_name, int fit_mode)
{
    if (!server || !*server || !obs_initialized()) return false;
    std::lock_guard<std::mutex> lock(g_ms_mutex);

    if (g_vert.stream_out && obs_output_active(g_vert.stream_out)) return true;
    if (!ensure_vertical_canvas_locked(width, height, scene_name, fit_mode)) return false;

    if (g_vert.stream_out) { obs_output_release(g_vert.stream_out); g_vert.stream_out = nullptr; }
    if (g_vert.stream_venc) { obs_encoder_release(g_vert.stream_venc); g_vert.stream_venc = nullptr; }
    if (g_vert.stream_aenc) { obs_encoder_release(g_vert.stream_aenc); g_vert.stream_aenc = nullptr; }
    if (g_vert.stream_service) { obs_service_release(g_vert.stream_service); g_vert.stream_service = nullptr; }

    obs_data_t *ss = obs_data_create();
    obs_data_set_string(ss, "server", server);
    obs_data_set_string(ss, "key", key ? key : "");
    g_vert.stream_service = obs_service_create("rtmp_custom", "vertical_rtmp_svc", ss, nullptr);
    obs_data_release(ss);

    g_vert.stream_venc = create_video_encoder("vertical_h264", g_vert.video, bitrate_kbps);
    g_vert.stream_aenc = create_audio_encoder("vertical_aac");
    g_vert.stream_out = obs_output_create("rtmp_output", "vertical_rtmp_out", nullptr, nullptr);
    if (!g_vert.stream_service || !g_vert.stream_venc || !g_vert.stream_aenc || !g_vert.stream_out) {
        if (g_vert.stream_out) { obs_output_release(g_vert.stream_out); g_vert.stream_out = nullptr; }
        if (g_vert.stream_venc) { obs_encoder_release(g_vert.stream_venc); g_vert.stream_venc = nullptr; }
        if (g_vert.stream_aenc) { obs_encoder_release(g_vert.stream_aenc); g_vert.stream_aenc = nullptr; }
        if (g_vert.stream_service) { obs_service_release(g_vert.stream_service); g_vert.stream_service = nullptr; }
        maybe_teardown_vertical_canvas_locked();
        return false;
    }
    obs_output_set_video_encoder(g_vert.stream_out, g_vert.stream_venc);
    obs_output_set_audio_encoder(g_vert.stream_out, g_vert.stream_aenc, 0);
    obs_output_set_service(g_vert.stream_out, g_vert.stream_service);
    obs_output_set_reconnect_settings(g_vert.stream_out, 20, 5);

    bool ok = obs_output_start(g_vert.stream_out);
    blog(LOG_INFO, "[android-vertical] Vertical 9:16 stream (%dx%d -> %s) start=%d",
         g_vert.width, g_vert.height, server, ok);
    if (!ok) {
        obs_output_release(g_vert.stream_out); g_vert.stream_out = nullptr;
        obs_encoder_release(g_vert.stream_venc); g_vert.stream_venc = nullptr;
        obs_encoder_release(g_vert.stream_aenc); g_vert.stream_aenc = nullptr;
        obs_service_release(g_vert.stream_service); g_vert.stream_service = nullptr;
        maybe_teardown_vertical_canvas_locked();
    }
    return ok;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_vertical_stop_stream()
{
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    if (g_vert.stream_out) {
        if (obs_output_active(g_vert.stream_out)) obs_output_stop(g_vert.stream_out);
        obs_output_release(g_vert.stream_out);
        g_vert.stream_out = nullptr;
    }
    if (g_vert.stream_venc) { obs_encoder_release(g_vert.stream_venc); g_vert.stream_venc = nullptr; }
    if (g_vert.stream_aenc) { obs_encoder_release(g_vert.stream_aenc); g_vert.stream_aenc = nullptr; }
    if (g_vert.stream_service) { obs_service_release(g_vert.stream_service); g_vert.stream_service = nullptr; }
    maybe_teardown_vertical_canvas_locked();
    return true;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_vertical_is_streaming()
{
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    return g_vert.stream_out && (obs_output_active(g_vert.stream_out) || obs_output_reconnecting(g_vert.stream_out));
}

extern "C" __attribute__((visibility("default"))) bool obs_android_vertical_is_reconnecting()
{
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    return g_vert.stream_out && obs_output_reconnecting(g_vert.stream_out);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_vertical_start_record(
    const char *output_file_path, int width, int height, int bitrate_kbps,
    const char *scene_name, int fit_mode)
{
    if (!output_file_path || !*output_file_path || !obs_initialized()) return false;
    std::lock_guard<std::mutex> lock(g_ms_mutex);

    if (g_vert.rec_out && obs_output_active(g_vert.rec_out)) return true;
    if (!ensure_vertical_canvas_locked(width, height, scene_name, fit_mode)) return false;

    if (g_vert.rec_out) { obs_output_release(g_vert.rec_out); g_vert.rec_out = nullptr; }
    if (g_vert.rec_venc) { obs_encoder_release(g_vert.rec_venc); g_vert.rec_venc = nullptr; }
    if (g_vert.rec_aenc) { obs_encoder_release(g_vert.rec_aenc); g_vert.rec_aenc = nullptr; }

    g_vert.rec_venc = create_video_encoder("vertical_rec_h264", g_vert.video, bitrate_kbps);
    g_vert.rec_aenc = create_audio_encoder("vertical_rec_aac");
    obs_data_t *os = obs_data_create();
    obs_data_set_string(os, "path", output_file_path);
    g_vert.rec_out = obs_output_create("mp4_output", "vertical_mp4_out", os, nullptr);
    if (!g_vert.rec_out) {
        g_vert.rec_out = obs_output_create("ffmpeg_muxer", "vertical_mp4_out", os, nullptr);
    }
    obs_data_release(os);
    if (!g_vert.rec_venc || !g_vert.rec_aenc || !g_vert.rec_out) {
        blog(LOG_ERROR, "[android-vertical] Record init failed (venc=%p aenc=%p out=%p)",
             (void *)g_vert.rec_venc, (void *)g_vert.rec_aenc, (void *)g_vert.rec_out);
        if (g_vert.rec_out) { obs_output_release(g_vert.rec_out); g_vert.rec_out = nullptr; }
        if (g_vert.rec_venc) { obs_encoder_release(g_vert.rec_venc); g_vert.rec_venc = nullptr; }
        if (g_vert.rec_aenc) { obs_encoder_release(g_vert.rec_aenc); g_vert.rec_aenc = nullptr; }
        maybe_teardown_vertical_canvas_locked();
        return false;
    }

    obs_output_set_video_encoder(g_vert.rec_out, g_vert.rec_venc);
    obs_output_set_audio_encoder(g_vert.rec_out, g_vert.rec_aenc, 0);
    g_vert.rec_path = output_file_path;
    bool ok = obs_output_start(g_vert.rec_out);
    blog(LOG_INFO, "[android-vertical] Vertical 9:16 recording (%dx%d -> %s) start=%d",
         g_vert.width, g_vert.height, output_file_path, ok);
    if (!ok) {
        obs_output_release(g_vert.rec_out); g_vert.rec_out = nullptr;
        obs_encoder_release(g_vert.rec_venc); g_vert.rec_venc = nullptr;
        obs_encoder_release(g_vert.rec_aenc); g_vert.rec_aenc = nullptr;
        g_vert.rec_path.clear();
        maybe_teardown_vertical_canvas_locked();
    }
    return ok;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_vertical_stop_record()
{
    std::string finished_path;
    {
        std::lock_guard<std::mutex> lock(g_ms_mutex);
        if (g_vert.rec_out) {
            if (obs_output_active(g_vert.rec_out)) obs_output_stop(g_vert.rec_out);
            obs_output_release(g_vert.rec_out);
            g_vert.rec_out = nullptr;
            finished_path = g_vert.rec_path;
            g_vert.rec_path.clear();
        }
        if (g_vert.rec_venc) { obs_encoder_release(g_vert.rec_venc); g_vert.rec_venc = nullptr; }
        if (g_vert.rec_aenc) { obs_encoder_release(g_vert.rec_aenc); g_vert.rec_aenc = nullptr; }
        maybe_teardown_vertical_canvas_locked();
    }
    if (!finished_path.empty()) {
        obs_android_on_recording_finished(finished_path.c_str());
    }
    return true;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_vertical_is_recording()
{
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    return g_vert.rec_out && obs_output_active(g_vert.rec_out);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_vertical_start_backtrack(
    int width, int height, int bitrate_kbps, const char *scene_name, int fit_mode, int seconds)
{
    if (!obs_initialized()) return false;
    std::lock_guard<std::mutex> lock(g_ms_mutex);

    if (g_vert.bt_out && obs_output_active(g_vert.bt_out)) return true;
    if (!ensure_vertical_canvas_locked(width, height, scene_name, fit_mode)) return false;

    if (g_vert.bt_out) { obs_output_release(g_vert.bt_out); g_vert.bt_out = nullptr; }
    if (g_vert.bt_venc) { obs_encoder_release(g_vert.bt_venc); g_vert.bt_venc = nullptr; }
    if (g_vert.bt_aenc) { obs_encoder_release(g_vert.bt_aenc); g_vert.bt_aenc = nullptr; }

    g_vert.bt_seconds = std::clamp(seconds > 0 ? seconds : 30, 10, 120);
    g_vert.bt_venc = create_video_encoder("vertical_bt_h264", g_vert.video, bitrate_kbps);
    g_vert.bt_aenc = create_audio_encoder("vertical_bt_aac");
    obs_data_t *bs = obs_data_create();
    obs_data_set_int(bs, "seconds", g_vert.bt_seconds);
    g_vert.bt_out = obs_output_create("android_vertical_backtrack", "vertical_bt_out", bs, nullptr);
    obs_data_release(bs);
    if (!g_vert.bt_venc || !g_vert.bt_aenc || !g_vert.bt_out) {
        if (g_vert.bt_out) { obs_output_release(g_vert.bt_out); g_vert.bt_out = nullptr; }
        if (g_vert.bt_venc) { obs_encoder_release(g_vert.bt_venc); g_vert.bt_venc = nullptr; }
        if (g_vert.bt_aenc) { obs_encoder_release(g_vert.bt_aenc); g_vert.bt_aenc = nullptr; }
        maybe_teardown_vertical_canvas_locked();
        return false;
    }

    obs_output_set_video_encoder(g_vert.bt_out, g_vert.bt_venc);
    obs_output_set_audio_encoder(g_vert.bt_out, g_vert.bt_aenc, 0);
    bool ok = obs_output_start(g_vert.bt_out);
    blog(LOG_INFO, "[android-vertical] Vertical 9:16 Backtrack (%dx%d, %ds) start=%d",
         g_vert.width, g_vert.height, g_vert.bt_seconds, ok);
    if (!ok) {
        obs_output_release(g_vert.bt_out); g_vert.bt_out = nullptr;
        obs_encoder_release(g_vert.bt_venc); g_vert.bt_venc = nullptr;
        obs_encoder_release(g_vert.bt_aenc); g_vert.bt_aenc = nullptr;
        maybe_teardown_vertical_canvas_locked();
    }
    return ok;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_vertical_stop_backtrack()
{
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    if (g_vert.bt_out) {
        if (obs_output_active(g_vert.bt_out)) obs_output_stop(g_vert.bt_out);
        obs_output_release(g_vert.bt_out);
        g_vert.bt_out = nullptr;
    }
    if (g_vert.bt_venc) { obs_encoder_release(g_vert.bt_venc); g_vert.bt_venc = nullptr; }
    if (g_vert.bt_aenc) { obs_encoder_release(g_vert.bt_aenc); g_vert.bt_aenc = nullptr; }
    maybe_teardown_vertical_canvas_locked();
    return true;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_vertical_is_backtrack_active()
{
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    return g_vert.bt_out && obs_output_active(g_vert.bt_out);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_vertical_save_backtrack(const char *output_file_path)
{
    if (!output_file_path || !*output_file_path) return false;
    int width = 720, height = 1280, sample_rate = 48000;
    std::vector<uint8_t> video_csd, audio_csd;
    std::vector<BacktrackPacket> snapshot;
    {
        std::lock_guard<std::mutex> lock(g_ms_mutex);
        if (!g_vert.bt_out || !obs_output_active(g_vert.bt_out) || !g_active_bt)
            return false;
        std::lock_guard<std::mutex> bt_lock(g_active_bt->mutex);
        if (g_active_bt->video_csd.empty() || g_active_bt->packets.empty())
            return false;
        width = g_active_bt->width;
        height = g_active_bt->height;
        sample_rate = g_active_bt->sample_rate;
        video_csd = g_active_bt->video_csd;
        audio_csd = g_active_bt->audio_csd;
        snapshot.assign(g_active_bt->packets.begin(), g_active_bt->packets.end());
    }
    const bool ok = write_backtrack_snapshot_to_mp4(
        output_file_path, width, height, sample_rate, video_csd, audio_csd, snapshot);
    if (ok) {
        obs_android_on_recording_finished(output_file_path);
    }
    return ok;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_vertical_get_stats(char *out_buf, size_t out_cap)
{
    if (!out_buf || out_cap == 0) return false;
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    const bool st_rec = g_vert.stream_out && obs_output_reconnecting(g_vert.stream_out);
    const bool st_act = g_vert.stream_out && (obs_output_active(g_vert.stream_out) || st_rec);
    const int st_frames = st_act ? obs_output_get_total_frames(g_vert.stream_out) : 0;
    const int st_dropped = st_act ? obs_output_get_frames_dropped(g_vert.stream_out) : 0;
    const uint64_t st_bytes = st_act ? obs_output_get_total_bytes(g_vert.stream_out) : 0;

    const bool rc_act = g_vert.rec_out && obs_output_active(g_vert.rec_out);
    const int rc_frames = rc_act ? obs_output_get_total_frames(g_vert.rec_out) : 0;
    const uint64_t rc_bytes = rc_act ? obs_output_get_total_bytes(g_vert.rec_out) : 0;

    const bool bt_act = g_vert.bt_out && obs_output_active(g_vert.bt_out);
    double bt_buffered = 0.0;
    if (bt_act && g_active_bt) {
        std::lock_guard<std::mutex> bt_lock(g_active_bt->mutex);
        int64_t first_v = -1, last_v = -1;
        for (const auto &p : g_active_bt->packets) {
            if (!p.is_audio) {
                if (first_v < 0) first_v = p.pts_us;
                last_v = p.pts_us;
            }
        }
        if (first_v >= 0 && last_v > first_v) {
            bt_buffered = double(last_v - first_v) / 1000000.0;
        }
    }

    int multi_active = 0;
    for (const auto &kv : g_targets) {
        if (kv.second.output && obs_output_active(kv.second.output)) ++multi_active;
    }

    std::snprintf(
        out_buf, out_cap,
        "{\"streaming\":%s,\"stream_reconnecting\":%s,\"stream_frames\":%d,\"stream_dropped\":%d,\"stream_bytes\":%llu,"
        "\"recording\":%s,\"rec_frames\":%d,\"rec_bytes\":%llu,"
        "\"backtrack\":%s,\"backtrack_secs\":%d,\"backtrack_buffered\":%.1f,"
        "\"multi_active\":%d,\"any_active\":%s}",
        st_act ? "true" : "false", st_rec ? "true" : "false", st_frames, st_dropped, (unsigned long long)st_bytes,
        rc_act ? "true" : "false", rc_frames, (unsigned long long)rc_bytes,
        bt_act ? "true" : "false", g_vert.bt_seconds, bt_buffered,
        multi_active, (st_act || rc_act || bt_act || multi_active > 0) ? "true" : "false");
    return true;
}

extern "C" __attribute__((visibility("default"))) void obs_android_vertical_set_scene(const char *scene_name, int fit_mode)
{
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    if (g_vert.wrapper_scene) {
        update_vertical_wrapped_scene_locked(scene_name, fit_mode);
    }
}

void android_multistream_shutdown()
{
    std::lock_guard<std::mutex> lock(g_ms_mutex);
    for (auto &kv : g_targets) release_multi_target(kv.second);
    g_targets.clear();
    if (g_vert.stream_out) {
        if (obs_output_active(g_vert.stream_out)) obs_output_stop(g_vert.stream_out);
        obs_output_release(g_vert.stream_out); g_vert.stream_out = nullptr;
    }
    if (g_vert.rec_out) {
        if (obs_output_active(g_vert.rec_out)) obs_output_stop(g_vert.rec_out);
        obs_output_release(g_vert.rec_out); g_vert.rec_out = nullptr;
    }
    if (g_vert.bt_out) {
        if (obs_output_active(g_vert.bt_out)) obs_output_stop(g_vert.bt_out);
        obs_output_release(g_vert.bt_out); g_vert.bt_out = nullptr;
    }
    if (g_vert.stream_venc) { obs_encoder_release(g_vert.stream_venc); g_vert.stream_venc = nullptr; }
    if (g_vert.stream_aenc) { obs_encoder_release(g_vert.stream_aenc); g_vert.stream_aenc = nullptr; }
    if (g_vert.stream_service) { obs_service_release(g_vert.stream_service); g_vert.stream_service = nullptr; }
    if (g_vert.rec_venc) { obs_encoder_release(g_vert.rec_venc); g_vert.rec_venc = nullptr; }
    if (g_vert.rec_aenc) { obs_encoder_release(g_vert.rec_aenc); g_vert.rec_aenc = nullptr; }
    if (g_vert.bt_venc) { obs_encoder_release(g_vert.bt_venc); g_vert.bt_venc = nullptr; }
    if (g_vert.bt_aenc) { obs_encoder_release(g_vert.bt_aenc); g_vert.bt_aenc = nullptr; }
    maybe_teardown_vertical_canvas_locked();
}
