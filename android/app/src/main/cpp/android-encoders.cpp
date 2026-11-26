// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-encoders.h"
#include <obs.h>
#include <obs-avc.h>
#include <obs-hevc.h>
#include <graphics/graphics.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <nlohmann/json.hpp>
#include <util/platform.h>
#include <string>
#include <unordered_map>
#include <vector>

extern "C" bool obs_android_select_video_codec(const char *, char *, size_t);
extern "C" int obs_android_get_thermal_tier();
namespace {
std::mutex codec_budget_mutex;
std::unordered_map<std::string, int> active_codec_counts;
std::atomic<int> active_surface_codecs{0};
std::atomic<int> active_buffer_codecs{0};
std::atomic<uint64_t> surface_frames{0}, cpu_frames{0}, encode_ns{0}, encode_calls{0}, encode_max_ns{0};
struct EncodeTiming {
    uint64_t start{os_gettime_ns()};
    ~EncodeTiming() {
        uint64_t elapsed = os_gettime_ns() - start;
        encode_ns.fetch_add(elapsed, std::memory_order_relaxed);
        encode_calls.fetch_add(1, std::memory_order_relaxed);
        auto old = encode_max_ns.load();
        while (elapsed > old && !encode_max_ns.compare_exchange_weak(old, elapsed)) {}
    }
};

#ifndef EGL_RECORDABLE_ANDROID
#define EGL_RECORDABLE_ANDROID 0x3142
#endif
constexpr int32_t kColorFormatSurface = 0x7F000789; // COLOR_FormatSurface

using PFN_eglPresentationTimeANDROID = EGLBoolean(EGLAPIENTRYP)(EGLDisplay dpy, EGLSurface surface, EGLnsecsANDROID time);

constexpr const char *kNv12ToRgbVS = R"(#version 300 es
out vec2 v_uv;
void main() {
    vec2 pos = vec2((gl_VertexID == 1) ? 3.0 : -1.0,
                    (gl_VertexID == 2) ? 3.0 : -1.0);
    gl_Position = vec4(pos, 0.0, 1.0);
    v_uv = vec2(pos.x * 0.5 + 0.5, 0.5 - pos.y * 0.5);
}
)";

constexpr const char *kNv12ToRgbFS = R"(#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_tex_y;
uniform sampler2D u_tex_uv;
uniform vec4 u_bias_scale;
uniform mat3 u_yuv_to_rgb;
out vec4 fragColor;
void main() {
    float y = (texture(u_tex_y, v_uv).r - u_bias_scale.x) * u_bias_scale.y;
    vec2 uv = (texture(u_tex_uv, v_uv).rg - u_bias_scale.z) * u_bias_scale.w;
    vec3 rgb = clamp(u_yuv_to_rgb * vec3(y, uv.x, uv.y), 0.0, 1.0);
    fragColor = vec4(rgb, 1.0);
}
)";

GLuint compile_shader(GLenum type, const char *src)
{
    GLuint sh = glCreateShader(type);
    if (!sh) return 0;
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {};
        glGetShaderInfoLog(sh, sizeof(log), nullptr, log);
        blog(LOG_ERROR, "Android MediaCodec GLES shader error: %s", log);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

GLuint create_nv12_program()
{
    GLuint vs = compile_shader(GL_VERTEX_SHADER, kNv12ToRgbVS);
    GLuint fs = compile_shader(GL_FRAGMENT_SHADER, kNv12ToRgbFS);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return 0;
    }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512] = {};
        glGetProgramInfoLog(prog, sizeof(log), nullptr, log);
        blog(LOG_ERROR, "Android MediaCodec GLES link error: %s", log);
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

struct QueuedPacket {
    std::vector<uint8_t> data;
    int64_t pts_us{};
    bool keyframe{};
};

struct Encoder {
    obs_encoder_t *obs{};
    AMediaCodec *codec{};
    bool audio{}, hevc{}, started{}, planar{}, surface_mode{};
    uint32_t width{}, height{}, stride{}, slice_height{}, rate{}, fps_num{30}, fps_den{1};
    int32_t bitrate_kbps{2500}, user_bitrate_kbps{2500}, keyint_sec{2}, bitrate_mode{2}, color_format{21};
    int32_t profile{0}, max_bframes{0}, aac_profile{2};
    bool low_latency{true};
    std::atomic<int32_t> pending_bitrate_kbps{0};
    int32_t last_thermal_tier{0};
    uint32_t thermal_check_frames{0};
    std::string codec_name;
    bool hardware{true};
    bool budget_reserved{false};
    bool counted_surface{false};
    bool counted_buffer{false};
    std::vector<uint8_t> extra, packet;
    std::deque<QueuedPacket> output_queue;
    std::atomic<bool> extra_ready{};

    // Zero-copy GPU Surface encoding state (EGL shared context -> AMediaCodec ANativeWindow)
    ANativeWindow *input_window{};
    EGLDisplay egl_display{EGL_NO_DISPLAY};
    EGLContext egl_ctx{EGL_NO_CONTEXT};
    EGLSurface egl_surface{EGL_NO_SURFACE};
    PFN_eglPresentationTimeANDROID set_pts_fn{};
    GLuint gl_prog{0};
    GLuint gl_vao{0};
    GLint loc_tex_y{-1}, loc_tex_uv{-1}, loc_bias_scale{-1}, loc_yuv_mat{-1};
    video_colorspace colorspace{VIDEO_CS_709};
    video_range_type range{VIDEO_RANGE_PARTIAL};
    std::unordered_map<const gs_texture_t *, GLuint> tex_cache;

    GLuint resolve_gl_tex(gs_texture_t *tex) {
        if (!tex) return 0;
        auto it = tex_cache.find(tex);
        if (it != tex_cache.end()) return it->second;
        if (tex_cache.size() >= 16) tex_cache.clear();
        GLuint id = 0;
        obs_enter_graphics();
        const auto *ptr = static_cast<const GLuint *>(gs_texture_get_obj(tex));
        if (ptr) id = *ptr;
        obs_leave_graphics();
        if (id) tex_cache[tex] = id;
        return id;
    }

    ~Encoder() { reset_codec(); }

    void cleanup_egl() {
        if (egl_display != EGL_NO_DISPLAY) {
            if (egl_ctx != EGL_NO_CONTEXT && egl_surface != EGL_NO_SURFACE) {
                if (eglMakeCurrent(egl_display, egl_surface, egl_surface, egl_ctx)) {
                    if (gl_prog) { glDeleteProgram(gl_prog); gl_prog = 0; }
                    if (gl_vao) { glDeleteVertexArrays(1, &gl_vao); gl_vao = 0; }
                }
            }
            eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (egl_ctx != EGL_NO_CONTEXT) {
                eglDestroyContext(egl_display, egl_ctx);
                egl_ctx = EGL_NO_CONTEXT;
            }
            if (egl_surface != EGL_NO_SURFACE) {
                eglDestroySurface(egl_display, egl_surface);
                egl_surface = EGL_NO_SURFACE;
            }
            egl_display = EGL_NO_DISPLAY;
        }
        if (input_window) {
            ANativeWindow_release(input_window);
            input_window = nullptr;
        }
        gl_prog = 0;
        gl_vao = 0;
        tex_cache.clear();
        surface_mode = false;
    }

    void reset_codec() {
        cleanup_egl();
        if (codec) {
            if (started) AMediaCodec_stop(codec);
            AMediaCodec_delete(codec);
            codec = nullptr;
        }
        if (budget_reserved) {
            std::lock_guard<std::mutex> lock(codec_budget_mutex);
            auto it = active_codec_counts.find(codec_name);
            if (it != active_codec_counts.end() && --it->second <= 0) active_codec_counts.erase(it);
            budget_reserved = false;
        }
        if (counted_surface) { --active_surface_codecs; counted_surface = false; }
        if (counted_buffer) { --active_buffer_codecs; counted_buffer = false; }
        started = false;
        output_queue.clear();
        extra.clear();
        extra_ready.store(false, std::memory_order_release);
    }

    bool fail(const char *text) {
        obs_encoder_set_last_error(obs, text);
        blog(LOG_ERROR, "Android MediaCodec: %s", text);
        return false;
    }

    void read_format() {
        if (extra_ready.load(std::memory_order_acquire)) return;
        AMediaFormat *format = AMediaCodec_getOutputFormat(codec);
        if (!format) return;
        void *data = nullptr; size_t size = 0;
        if (AMediaFormat_getBuffer(format, "csd-0", &data, &size) && data && size) {
            extra.assign(static_cast<uint8_t *>(data), static_cast<uint8_t *>(data) + size);
            if (!audio && AMediaFormat_getBuffer(format, "csd-1", &data, &size) && data && size)
                extra.insert(extra.end(), static_cast<uint8_t *>(data), static_cast<uint8_t *>(data) + size);
            extra_ready.store(true, std::memory_order_release);
        }
        AMediaFormat_delete(format);
    }

    void read_input_layout() {
        stride = width;
        slice_height = height;
        AMediaFormat *in_fmt = AMediaCodec_getInputFormat(codec);
        if (!in_fmt) return;
        int32_t s = 0, sh = 0;
        if (AMediaFormat_getInt32(in_fmt, "stride", &s) && s >= int32_t(width)) stride = uint32_t(s);
        if (AMediaFormat_getInt32(in_fmt, "slice-height", &sh) && sh >= int32_t(height)) slice_height = uint32_t(sh);
        blog(LOG_INFO, "MediaCodec input layout: %ux%u stride=%u slice_height=%u", width, height, stride, slice_height);
        AMediaFormat_delete(in_fmt);
    }

    bool resolve_video_params() {
        obs_video_info video{};
        obs_get_video_info(&video);
        uint32_t ew = obs_encoder_get_width(obs), eh = obs_encoder_get_height(obs);
        width = ew > 0 ? ew : (video.output_width > 0 ? video.output_width : 1280);
        height = eh > 0 ? eh : (video.output_height > 0 ? video.output_height : 720);
        fps_num = video.fps_num > 0 ? video.fps_num : 30;
        fps_den = video.fps_den > 0 ? video.fps_den : 1;
        colorspace = video.colorspace;
        range = video.range;
        return width > 0 && height > 0;
    }

    const char *mime_type() const {
        return audio ? "audio/mp4a-latm" : (hevc ? "video/hevc" : "video/avc");
    }

    void apply_pending_bitrate() {
        if (!started || !codec || audio) return;
        int32_t next_br = pending_bitrate_kbps.exchange(0, std::memory_order_acq_rel);
        bool force_check = false;
        if (next_br > 0) {
            user_bitrate_kbps = next_br;
            force_check = true;
        }
        // Check thermal bitrate governor every ~60 frames (~2s) or on explicit user bitrate update
        if (!force_check && (++thermal_check_frames % 60u) != 0u) return;
        const int tier = obs_android_get_thermal_tier();
        const float factor = (tier >= 3) ? 0.65f : (tier == 2 ? 0.80f : 1.0f);
        const int32_t base_br = user_bitrate_kbps > 0 ? user_bitrate_kbps : bitrate_kbps;
        const int32_t target_br = std::min(base_br, std::max<int32_t>(600, int32_t(std::lround(base_br * factor))));
        if (target_br == bitrate_kbps && tier == last_thermal_tier) return;
        last_thermal_tier = tier;
        if (target_br == bitrate_kbps) return;
        bitrate_kbps = target_br;
        AMediaFormat *params = AMediaFormat_new();
        AMediaFormat_setInt32(params, "video-bitrate", bitrate_kbps * 1000);
        media_status_t st = AMediaCodec_setParameters(codec, params);
        AMediaFormat_delete(params);
        blog(LOG_INFO, "MediaCodec live bitrate (%s) -> %d kbps (base=%d, thermalTier=%d, status=%d)",
             mime_type(), bitrate_kbps, base_br, tier, st);
    }

    bool start_codec_surface() {
        if (started && surface_mode) return true;
        if (started) reset_codec();
        if (!hardware) return fail("Video codec must be hardware accelerated");
        if (!resolve_video_params()) return fail("Invalid encoder video dimensions");

        const char *mime = mime_type();
        nlohmann::json req{{"mime", mime}, {"width", width}, {"height", height},
            {"fps", double(fps_num) / std::max(1u, fps_den)}, {"bitrate", bitrate_kbps * 1000},
            {"mode", bitrate_mode}, {"codec", codec_name}};
        char selected[1024] = {};
        if (!obs_android_select_video_codec(req.dump().c_str(), selected, sizeof(selected)))
            return fail("No se pudieron verificar las capacidades MediaCodec de Android");
        auto choice = nlohmann::json::parse(selected, nullptr, false);
        if (choice.is_discarded() || !choice.contains("codec"))
            return fail("No hay códec hardware Surface compatible con los ajustes seleccionados");
        codec_name = choice.value("codec", "");
        const int limit = choice.value("max_instances", 1);
        {
            std::lock_guard<std::mutex> lock(codec_budget_mutex);
            int &count = active_codec_counts[codec_name];
            if (count >= limit) return fail("Límite de codificadores simultáneos alcanzado; comparte la salida o reduce destinos independientes");
            ++count;
            budget_reserved = true;
        }
        blog(LOG_INFO, "[android-encoder] Verified hardware codec %s; Surface %ux%u; concurrency hint=%d", codec_name.c_str(), width, height, limit);
        if (!codec_name.empty()) codec = AMediaCodec_createCodecByName(codec_name.c_str());
        if (!codec) {
            reset_codec(); // Release the concurrency reservation before a later retry.
            return fail("No se pudo crear el codificador hardware verificado (recursos no disponibles)");
        }

        AMediaFormat *format = AMediaFormat_new();
        AMediaFormat_setString(format, "mime", mime);
        AMediaFormat_setInt32(format, "width", int32_t(width));
        AMediaFormat_setInt32(format, "height", int32_t(height));
        AMediaFormat_setInt32(format, "bitrate", bitrate_kbps * 1000);
        AMediaFormat_setInt32(format, "frame-rate", int32_t(std::max(1u, fps_num / std::max(1u, fps_den))));
        AMediaFormat_setInt32(format, "color-format", kColorFormatSurface);
        AMediaFormat_setInt32(format, "color-standard", colorspace == VIDEO_CS_601 ? 4 : 1);
        AMediaFormat_setInt32(format, "color-range", range == VIDEO_RANGE_FULL ? 1 : 2);
        AMediaFormat_setInt32(format, "color-transfer", 3);
        AMediaFormat_setInt32(format, "i-frame-interval", keyint_sec);
        AMediaFormat_setInt32(format, "bitrate-mode", bitrate_mode);
        if (!hevc) {
            const int32_t avc_prof = (profile == 2 || profile == 8 || profile == 1) ? profile : 1;
            AMediaFormat_setInt32(format, "profile", avc_prof);
        } else if (profile == 1) {
            AMediaFormat_setInt32(format, "profile", 1);
        }
        AMediaFormat_setInt32(format, "max-bframes", std::clamp(max_bframes, 0, 2));
        if (low_latency) {
            AMediaFormat_setInt32(format, "latency", 1);
            AMediaFormat_setInt32(format, "priority", 0);
        }

        media_status_t status = AMediaCodec_configure(codec, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        AMediaFormat_delete(format);
        if (status != AMEDIA_OK) {
            // Retry once without optional tuning keys (some vendor HEVC codecs reject latency/bitrate-mode)
            AMediaFormat *fallback = AMediaFormat_new();
            AMediaFormat_setString(fallback, "mime", mime);
            AMediaFormat_setInt32(fallback, "width", int32_t(width));
            AMediaFormat_setInt32(fallback, "height", int32_t(height));
            AMediaFormat_setInt32(fallback, "bitrate", bitrate_kbps * 1000);
            AMediaFormat_setInt32(fallback, "frame-rate", int32_t(std::max(1u, fps_num / std::max(1u, fps_den))));
            AMediaFormat_setInt32(fallback, "color-format", kColorFormatSurface);
            AMediaFormat_setInt32(fallback, "i-frame-interval", keyint_sec);
            status = AMediaCodec_configure(codec, fallback, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
            AMediaFormat_delete(fallback);
        }
        if (status != AMEDIA_OK) {
            reset_codec();
            return fail("MediaCodec Surface configure failed");
        }
        if (AMediaCodec_createInputSurface(codec, &input_window) != AMEDIA_OK || !input_window) {
            reset_codec();
            return fail("AMediaCodec_createInputSurface failed");
        }
        if (AMediaCodec_start(codec) != AMEDIA_OK) {
            reset_codec();
            return fail("MediaCodec Surface start failed");
        }
        started = true;

        EGLDisplay dpy = EGL_NO_DISPLAY;
        EGLContext share_ctx = EGL_NO_CONTEXT;
        obs_enter_graphics();
        dpy = eglGetCurrentDisplay();
        share_ctx = static_cast<EGLContext>(gs_get_device_obj());
        obs_leave_graphics();
        if (dpy == EGL_NO_DISPLAY || share_ctx == EGL_NO_CONTEXT) {
            reset_codec();
            return fail("Failed to obtain OBS EGL display/context for zero-copy encode");
        }
        egl_display = dpy;

        EGLConfig cfg = nullptr;
        EGLint count = 0;
        const EGLint rec_attrs[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_RECORDABLE_ANDROID, 1,
            EGL_NONE
        };
        if (!eglChooseConfig(dpy, rec_attrs, &cfg, 1, &count) || count < 1) {
            const EGLint fallback_attrs[] = {
                EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                EGL_NONE
            };
            if (!eglChooseConfig(dpy, fallback_attrs, &cfg, 1, &count) || count < 1) {
                reset_codec();
                return fail("eglChooseConfig failed for MediaCodec Surface");
            }
        }

        egl_surface = eglCreateWindowSurface(dpy, cfg, input_window, nullptr);
        if (egl_surface == EGL_NO_SURFACE) {
            reset_codec();
            return fail("eglCreateWindowSurface failed for MediaCodec Surface");
        }
        const EGLint ctx_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        egl_ctx = eglCreateContext(dpy, cfg, share_ctx, ctx_attrs);
        if (egl_ctx == EGL_NO_CONTEXT) {
            reset_codec();
            return fail("Shared eglCreateContext failed for MediaCodec Surface");
        }
        if (!eglMakeCurrent(dpy, egl_surface, egl_surface, egl_ctx)) {
            reset_codec();
            return fail("eglMakeCurrent failed for MediaCodec Surface");
        }

        gl_prog = create_nv12_program();
        if (!gl_prog) {
            reset_codec();
            return fail("Failed to compile NV12->RGB GLES shader for MediaCodec Surface");
        }
        glGenVertexArrays(1, &gl_vao);
        loc_tex_y = glGetUniformLocation(gl_prog, "u_tex_y");
        loc_tex_uv = glGetUniformLocation(gl_prog, "u_tex_uv");
        loc_bias_scale = glGetUniformLocation(gl_prog, "u_bias_scale");
        loc_yuv_mat = glGetUniformLocation(gl_prog, "u_yuv_to_rgb");
        set_pts_fn = reinterpret_cast<PFN_eglPresentationTimeANDROID>(eglGetProcAddress("eglPresentationTimeANDROID"));

        eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        surface_mode = true;
        counted_surface = true;
        ++active_surface_codecs;
        blog(LOG_INFO, "MediaCodec ZERO-COPY GPU Surface active: %s %ux%u@%ufps bitrate=%dkbps mode=%d (%s)",
             mime_type(), width, height, fps_num / std::max(1u, fps_den), bitrate_kbps, bitrate_mode,
             codec_name.empty() ? (hevc ? "default-hevc" : "default-avc") : codec_name.c_str());
        return true;
    }

    bool start_codec() {
        if (started && !surface_mode) return true;
        if (started) reset_codec();
        if (!audio && !hardware) return fail("Video codec must be hardware accelerated");
        const char *mime = mime_type();
        if (!audio && !codec_name.empty()) {
            std::lock_guard<std::mutex> lock(codec_budget_mutex);
            ++active_codec_counts[codec_name];
            budget_reserved = true;
        }
        if (!codec_name.empty()) {
            codec = AMediaCodec_createCodecByName(codec_name.c_str());
        }
        if (!codec) {
            codec = AMediaCodec_createEncoderByType(mime);
        }
        if (!codec) {
            reset_codec();
            return fail("AMediaCodec creation failed");
        }
        AMediaFormat *format = AMediaFormat_new();
        AMediaFormat_setString(format, "mime", mime);
        AMediaFormat_setInt32(format, "bitrate", bitrate_kbps * 1000);
        if (audio) {
            uint32_t sr = obs_encoder_get_sample_rate(obs);
            rate = sr > 0 ? sr : 48000;
            AMediaFormat_setInt32(format, "sample-rate", rate);
            AMediaFormat_setInt32(format, "channel-count", 2);
            AMediaFormat_setInt32(format, "aac-profile", (aac_profile == 5) ? 5 : 2);
            AMediaFormat_setInt32(format, "max-input-size", 4096);
        } else {
            resolve_video_params();
            if (color_format != 19 && color_format != 21) {
                AMediaFormat_delete(format);
                return fail("Initial encoder requires planar I420 or semiplanar NV12 byte buffers");
            }
            planar = color_format == 19;
            AMediaFormat_setInt32(format, "width", width);
            AMediaFormat_setInt32(format, "height", height);
            AMediaFormat_setInt32(format, "frame-rate", fps_num / std::max(1u, fps_den));
            AMediaFormat_setInt32(format, "color-format", color_format);
            AMediaFormat_setInt32(format, "color-standard", 1);
            AMediaFormat_setInt32(format, "color-range", 2);
            AMediaFormat_setInt32(format, "color-transfer", 3);
            AMediaFormat_setInt32(format, "i-frame-interval", keyint_sec);
            AMediaFormat_setInt32(format, "bitrate-mode", bitrate_mode);
            if (!hevc) {
                const int32_t avc_prof = (profile == 2 || profile == 8 || profile == 1) ? profile : 1;
                AMediaFormat_setInt32(format, "profile", avc_prof);
            }
            AMediaFormat_setInt32(format, "max-bframes", std::clamp(max_bframes, 0, 2));
            if (low_latency) {
                AMediaFormat_setInt32(format, "latency", 1);
                AMediaFormat_setInt32(format, "priority", 0);
            }
        }
        media_status_t status = AMediaCodec_configure(codec, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        blog(LOG_INFO, "MediaCodec selected: %s hardware=%d format=%s status=%d",
             codec_name.empty() ? (audio ? "default-aac" : (hevc ? "default-hevc" : "default-avc")) : codec_name.c_str(),
             hardware, AMediaFormat_toString(format), status);
        AMediaFormat_delete(format);
        if (status != AMEDIA_OK) return fail("MediaCodec configure failed; check supported profile/size/bitrate");
        if (AMediaCodec_start(codec) != AMEDIA_OK) return fail("MediaCodec start failed");
        if (!audio) read_input_layout();
        started = true;
        surface_mode = false;
        if (!audio) { counted_buffer = true; ++active_buffer_codecs; }
        return true;
    }

    bool drain_outputs(int64_t initial_timeout_us) {
        int64_t timeout = initial_timeout_us;
        for (int attempt = 0; attempt < 16; ++attempt) {
            AMediaCodecBufferInfo info{};
            ssize_t output = AMediaCodec_dequeueOutputBuffer(codec, &info, timeout);
            timeout = 0;
            if (output == AMEDIACODEC_INFO_TRY_AGAIN_LATER) return true;
            if (output == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) { read_format(); continue; }
            if (output == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
            if (output < 0) return fail("MediaCodec dequeueOutputBuffer failed");
            size_t length = 0; uint8_t *encoded = AMediaCodec_getOutputBuffer(codec, output, &length);
            if (!encoded || info.offset < 0 || info.size < 0 || size_t(info.offset) + size_t(info.size) > length) {
                AMediaCodec_releaseOutputBuffer(codec, output, false);
                return fail("Invalid MediaCodec output bounds");
            }
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) {
                if (!extra_ready.load(std::memory_order_acquire) && info.size) {
                    extra.assign(encoded + info.offset, encoded + info.offset + info.size);
                    extra_ready.store(true, std::memory_order_release);
                }
                AMediaCodec_releaseOutputBuffer(codec, output, false);
                continue;
            }
            if (info.size > 0) {
                QueuedPacket qp{};
                qp.data.assign(encoded + info.offset, encoded + info.offset + info.size);
                qp.pts_us = info.presentationTimeUs;
                qp.keyframe = !audio && (info.flags & AMEDIACODEC_BUFFER_FLAG_KEY_FRAME);
                output_queue.push_back(std::move(qp));
            }
            AMediaCodec_releaseOutputBuffer(codec, output, false);
        }
        return true;
    }

    bool pop_output_packet(encoder_packet *pkt, bool *received) {
        if (output_queue.empty()) return true;
        QueuedPacket qp = std::move(output_queue.front());
        output_queue.pop_front();
        packet = std::move(qp.data);
        read_format();
        if (!extra_ready.load(std::memory_order_acquire))
            return fail("Missing actual codec initialization data");
        if (!audio && (packet.size() < 4 || packet[0] || packet[1] ||
            !(packet[2] == 1 || (packet[2] == 0 && packet[3] == 1))))
            return fail(hevc ? "Expected Annex B HEVC from MediaCodec" : "Expected Annex B H.264 from MediaCodec");
        pkt->data = packet.data();
        pkt->size = packet.size();
        pkt->type = audio ? OBS_ENCODER_AUDIO : OBS_ENCODER_VIDEO;
        pkt->pts = audio
            ? std::llround(double(qp.pts_us) * rate / 1000000.0)
            : std::llround(double(qp.pts_us - (surface_mode ? 1000000LL : 0LL)) * double(fps_num) / (1000000.0 * std::max<uint32_t>(1, fps_den)));
        pkt->dts = pkt->pts;
        pkt->keyframe = qp.keyframe || (!audio && (hevc ? obs_hevc_keyframe(packet.data(), packet.size())
                                                        : obs_avc_keyframe(packet.data(), packet.size())));
        if (!audio) {
            pkt->priority = pkt->drop_priority =
                hevc ? obs_parse_hevc_packet_priority(pkt) : obs_parse_avc_packet_priority(pkt);
        }
        *received = true;
        return true;
    }

    void apply_settings(obs_data_t *settings) {
        const char *cn = obs_data_get_string(settings, "codec_name");
        if (cn && *cn) codec_name = cn;
        int64_t br = obs_data_get_int(settings, "bitrate");
        if (br > 0) {
            user_bitrate_kbps = int32_t(br);
            if (started && !audio && int32_t(br) != bitrate_kbps) {
                pending_bitrate_kbps.store(int32_t(br), std::memory_order_release);
            }
            bitrate_kbps = int32_t(br);
        }
        int64_t ki = obs_data_get_int(settings, "keyint_sec");
        if (ki > 0) keyint_sec = int32_t(ki);
        int64_t bm = obs_data_get_int(settings, "bitrate_mode");
        if (bm >= 0) bitrate_mode = int32_t(bm);
        int64_t cf = obs_data_get_int(settings, "color_format");
        if (cf > 0) color_format = int32_t(cf);
        int64_t prof = obs_data_get_int(settings, "profile");
        if (prof >= 0) profile = int32_t(prof);
        int64_t bf = obs_data_get_int(settings, "max_bframes");
        if (bf >= 0) max_bframes = std::clamp(int32_t(bf), 0, 2);
        low_latency = obs_data_get_bool(settings, "low_latency");
        int64_t aprof = obs_data_get_int(settings, "aac_profile");
        if (aprof == 2 || aprof == 5) aac_profile = int32_t(aprof);
        hardware = obs_data_get_bool(settings, "hardware");
        planar = color_format == 19;
    }
};

const char *name(void *td) {
    const auto kind = reinterpret_cast<uintptr_t>(td);
    if (kind == 1) return "Android MediaCodec AAC";
    if (kind == 2) return "Android MediaCodec HEVC / H.265 (GPU)";
    return "Android MediaCodec H.264 (GPU)";
}

void *create(obs_data_t *settings, obs_encoder_t *obs) {
    auto encoder = std::make_unique<Encoder>();
    encoder->obs = obs;
    encoder->audio = obs_encoder_get_type(obs) == OBS_ENCODER_AUDIO;
    const char *id = obs_encoder_get_id(obs);
    encoder->hevc = (!encoder->audio && id && std::strcmp(id, "android_mediacodec_hevc") == 0);
    encoder->bitrate_kbps = encoder->audio ? 160 : 2500;
    encoder->apply_settings(settings);
    const char *explicit_codec = obs_data_get_string(settings, "codec_name");
    bool has_explicit_codec = explicit_codec && *explicit_codec && strcmp(explicit_codec, "auto") != 0;
    if (has_explicit_codec && encoder->audio) {
        if (!encoder->start_codec()) return nullptr;
    }
    return encoder.release();
}

bool update(void *data, obs_data_t *settings) {
    auto &encoder = *static_cast<Encoder *>(data);
    // OBS serializes encoder initialization, but live bitrate updates can arrive
    // from an output's networking thread while MediaCodec is encoding.
    if (obs_encoder_active(encoder.obs)) {
        const int64_t bitrate = obs_data_get_int(settings, "bitrate");
        if (!encoder.audio && bitrate > 0 && bitrate <= 1000000)
            encoder.pending_bitrate_kbps.store(int32_t(bitrate), std::memory_order_release);
    } else encoder.apply_settings(settings);
    return true;
}

void destroy(void *data) { delete static_cast<Encoder *>(data); }

void video_info(void *data, video_scale_info *info) {
    auto &encoder = *static_cast<Encoder *>(data);
    info->format = encoder.planar ? VIDEO_FORMAT_I420 : VIDEO_FORMAT_NV12;
    info->colorspace = VIDEO_CS_709; info->range = VIDEO_RANGE_PARTIAL;
}

void audio_info(void *, audio_convert_info *info) {
    info->format = AUDIO_FORMAT_16BIT; info->speakers = SPEAKERS_STEREO; info->samples_per_sec = 48000;
}
size_t audio_size(void *) { return 1024; }

bool extra_data(void *data, uint8_t **extra, size_t *size) {
    auto &encoder = *static_cast<Encoder *>(data);
    if (!encoder.extra_ready.load(std::memory_order_acquire)) return false;
    *extra = encoder.extra.data(); *size = encoder.extra.size(); return true;
}

bool encode_texture2(void *data, struct encoder_texture *texture, int64_t pts, uint64_t, uint64_t *,
                     struct encoder_packet *packet, bool *received)
{
    EncodeTiming timing;
    auto &encoder = *static_cast<Encoder *>(data);
    *received = false;
    if (!texture || !texture->tex[0] || !texture->tex[1]) return true;

    if (encoder.started) {
        uint32_t ew = obs_encoder_get_width(encoder.obs), eh = obs_encoder_get_height(encoder.obs);
        if (!encoder.surface_mode || (ew > 0 && ew != encoder.width) || (eh > 0 && eh != encoder.height)) {
            encoder.reset_codec();
        }
    }
    if (!encoder.started && !encoder.start_codec_surface()) return false;
    encoder.apply_pending_bitrate();

    if (!encoder.drain_outputs(0)) return false;

    const GLuint y_tex = encoder.resolve_gl_tex(texture->tex[0]);
    const GLuint uv_tex = encoder.resolve_gl_tex(texture->tex[1]);
    if (!y_tex || !uv_tex) return encoder.fail("Invalid NV12 GL texture handles");

    if (!eglMakeCurrent(encoder.egl_display, encoder.egl_surface, encoder.egl_surface, encoder.egl_ctx)) {
        return encoder.fail("eglMakeCurrent failed in encode_texture2");
    }

    glViewport(0, 0, GLsizei(encoder.width), GLsizei(encoder.height));
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_CULL_FACE);

    glUseProgram(encoder.gl_prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, y_tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glUniform1i(encoder.loc_tex_y, 0);

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, uv_tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glUniform1i(encoder.loc_tex_uv, 1);

    const bool full = (encoder.range == VIDEO_RANGE_FULL);
    const float bias_scale[4] = {
        full ? 0.0f : (16.0f / 255.0f),
        full ? 1.0f : (255.0f / 219.0f),
        128.0f / 255.0f,
        full ? 1.0f : (255.0f / 224.0f)
    };
    glUniform4fv(encoder.loc_bias_scale, 1, bias_scale);

    // Column-major 3x3 YUV -> RGB matrix
    static constexpr float kMat709[9] = {
        1.0f, 1.0f, 1.0f,
        0.0f, -0.187324f, 1.8556f,
        1.5748f, -0.468124f, 0.0f
    };
    static constexpr float kMat601[9] = {
        1.0f, 1.0f, 1.0f,
        0.0f, -0.344136f, 1.772f,
        1.402f, -0.714136f, 0.0f
    };
    glUniformMatrix3fv(encoder.loc_yuv_mat, 1, GL_FALSE,
                       encoder.colorspace == VIDEO_CS_601 ? kMat601 : kMat709);

    glBindVertexArray(encoder.gl_vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);

    const int64_t pts_us = 1000000LL + pts * 1000000LL * int64_t(std::max<uint32_t>(1, encoder.fps_den)) / std::max<int64_t>(1, encoder.fps_num);
    if (encoder.set_pts_fn) {
        encoder.set_pts_fn(encoder.egl_display, encoder.egl_surface, EGLnsecsANDROID(pts_us * 1000LL));
    }
    const EGLBoolean swapped = eglSwapBuffers(encoder.egl_display, encoder.egl_surface);
    eglMakeCurrent(encoder.egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (!swapped) {
        return encoder.fail("eglSwapBuffers failed on MediaCodec Surface");
    }
    ++surface_frames;

    if (!encoder.drain_outputs(encoder.output_queue.empty() ? 2000 : 0)) return false;
    return encoder.pop_output_packet(packet, received);
}

bool encode(void *data, encoder_frame *frame, encoder_packet *packet, bool *received) {
    EncodeTiming timing;
    auto &encoder = *static_cast<Encoder *>(data); *received = false;
    if (!frame) return true;
    if (encoder.started && !encoder.audio) {
        uint32_t ew = obs_encoder_get_width(encoder.obs), eh = obs_encoder_get_height(encoder.obs);
        if (encoder.surface_mode || (ew > 0 && ew != encoder.width) || (eh > 0 && eh != encoder.height)) {
            encoder.reset_codec();
        }
    }
    if (!encoder.started && !encoder.start_codec()) return false;
    encoder.apply_pending_bitrate();

    if (!encoder.drain_outputs(0)) return false;

    ssize_t input = -1;
    for (int retry = 0; retry < 12; ++retry) {
        input = AMediaCodec_dequeueInputBuffer(encoder.codec, 25000);
        if (input >= 0) break;
        if (!encoder.drain_outputs(5000)) return false;
    }
    if (input < 0) return encoder.fail("MediaCodec input unavailable after 300ms; stopping rather than dropping timestamps");

    size_t capacity = 0; uint8_t *buffer = AMediaCodec_getInputBuffer(encoder.codec, input, &capacity);
    size_t bytes = 0;
    if (encoder.audio) {
        bytes = size_t(frame->frames) * 4;
        if (!buffer || capacity < bytes) return encoder.fail("MediaCodec input buffer smaller than OBS audio frame");
        std::memcpy(buffer, frame->data[0], bytes);
    } else {
        uint32_t dst_stride = encoder.stride >= encoder.width ? encoder.stride : encoder.width;
        uint32_t dst_slice = encoder.slice_height >= encoder.height ? encoder.slice_height : encoder.height;
        bytes = size_t(dst_stride) * dst_slice + size_t(dst_stride) * (encoder.height / 2);
        if (!buffer || capacity < bytes) {
            dst_stride = encoder.width;
            dst_slice = encoder.height;
            bytes = size_t(encoder.width) * encoder.height * 3 / 2;
        }
        if (!buffer || capacity < bytes) return encoder.fail("MediaCodec input buffer smaller than OBS frame");
        if (!frame->data[0] || frame->linesize[0] < encoder.width) return encoder.fail("Invalid OBS Y plane");
        for (uint32_t row = 0; row < encoder.height; ++row) {
            std::memcpy(buffer + size_t(row) * dst_stride, frame->data[0] + size_t(row) * frame->linesize[0], encoder.width);
        }
        size_t uv_offset = size_t(dst_stride) * dst_slice;
        if (encoder.planar) {
            uint32_t cw = encoder.width / 2, ch = encoder.height / 2, cs = dst_stride / 2;
            uint32_t csh = dst_slice / 2;
            for (int plane = 1; plane <= 2; ++plane) {
                if (!frame->data[plane] || frame->linesize[plane] < cw) return encoder.fail("Invalid OBS chroma plane");
                size_t base = uv_offset + (plane == 2 ? size_t(cs) * csh : 0);
                for (uint32_t row = 0; row < ch; ++row) {
                    std::memcpy(buffer + base + size_t(row) * cs, frame->data[plane] + size_t(row) * frame->linesize[plane], cw);
                }
            }
        } else {
            if (!frame->data[1] || frame->linesize[1] < encoder.width) return encoder.fail("Invalid OBS UV plane");
            for (uint32_t row = 0; row < encoder.height / 2; ++row) {
                std::memcpy(buffer + uv_offset + size_t(row) * dst_stride, frame->data[1] + size_t(row) * frame->linesize[1], encoder.width);
            }
        }
    }
    int64_t pts_us = encoder.audio
        ? frame->pts * 1000000 / encoder.rate
        : frame->pts * 1000000 * int64_t(std::max<uint32_t>(1, encoder.fps_den)) / std::max<int64_t>(1, encoder.fps_num);
    if (AMediaCodec_queueInputBuffer(encoder.codec, input, 0, bytes, pts_us, 0) != AMEDIA_OK) return encoder.fail("MediaCodec queueInputBuffer failed");
    if (!encoder.audio) ++cpu_frames;

    if (!encoder.drain_outputs(encoder.output_queue.empty() ? 2000 : 0)) return false;
    return encoder.pop_output_packet(packet, received);
}

void video_defaults(obs_data_t *settings) {
    obs_data_set_default_int(settings, "bitrate", 2500);
    obs_data_set_default_int(settings, "keyint_sec", 2);
    obs_data_set_default_int(settings, "bitrate_mode", 2);
    obs_data_set_default_int(settings, "profile", 8);
    obs_data_set_default_int(settings, "max_bframes", 0);
    obs_data_set_default_bool(settings, "low_latency", true);
    obs_data_set_default_int(settings, "color_format", 21);
    obs_data_set_default_bool(settings, "hardware", true);
    obs_data_set_default_string(settings, "codec_name", "");
}

void audio_defaults(obs_data_t *settings) {
    obs_data_set_default_int(settings, "bitrate", 160);
    obs_data_set_default_int(settings, "aac_profile", 2);
    obs_data_set_default_bool(settings, "hardware", false);
    obs_data_set_default_string(settings, "codec_name", "");
}

obs_properties_t *video_properties(void *) {
    obs_properties_t *props = obs_properties_create();
    obs_property_t *rc = obs_properties_add_list(props, "bitrate_mode", "Control de tasa",
                                                 OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(rc, "CBR (Constante · Streaming estable)", 2);
    obs_property_list_add_int(rc, "VBR (Variable · Mayor calidad)", 1);
    obs_property_list_add_int(rc, "CQ (Calidad constante)", 0);
    obs_property_t *p = obs_properties_add_int(props, "bitrate", "Tasa de bits (Bitrate)", 500, 50000, 50);
    obs_property_int_set_suffix(p, " Kbps");
    p = obs_properties_add_int(props, "keyint_sec", "Intervalo de fotogramas clave (0=auto)", 1, 10, 1);
    obs_property_int_set_suffix(p, " s");
    obs_property_t *prof = obs_properties_add_list(props, "profile", "Perfil de codificación",
                                                   OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(prof, "High (Recomendado · mejor calidad)", 8);
    obs_property_list_add_int(prof, "Main (Estándar streaming)", 2);
    obs_property_list_add_int(prof, "Baseline (Máxima compatibilidad / baja latencia)", 1);
    obs_property_t *bf = obs_properties_add_int(props, "max_bframes", "Máximo de B-frames (0 = baja latencia)", 0, 2, 1);
    (void)bf;
    obs_properties_add_bool(props, "low_latency", "Modo baja latencia en tiempo real (MediaCodec)");
    return props;
}

obs_properties_t *audio_properties(void *) {
    obs_properties_t *props = obs_properties_create();
    obs_property_t *p = obs_properties_add_int(props, "bitrate", "Bitrate de audio", 64, 320, 32);
    obs_property_int_set_suffix(p, " Kbps");
    obs_property_t *ap = obs_properties_add_list(props, "aac_profile", "Perfil AAC",
                                                 OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(ap, "AAC-LC (Estándar compatible)", 2);
    obs_property_list_add_int(ap, "HE-AAC (Bajo bitrate)", 5);
    return props;
}
} // namespace

void android_encoders_register() {
    obs_encoder_info video{};
    video.id = "android_mediacodec_h264";
    video.type = OBS_ENCODER_VIDEO;
    video.codec = "h264";
    video.caps = OBS_ENCODER_CAP_PASS_TEXTURE | OBS_ENCODER_CAP_DYN_BITRATE;
    video.get_name = name;
    video.create = create;
    video.destroy = destroy;
    video.encode = encode;
    video.encode_texture2 = encode_texture2;
    video.update = update;
    video.get_extra_data = extra_data;
    video.get_video_info = video_info;
    video.get_defaults = video_defaults;
    video.get_properties = video_properties;
    obs_register_encoder(&video);

    obs_encoder_info hevc_video = video;
    hevc_video.id = "android_mediacodec_hevc";
    hevc_video.codec = "hevc";
    hevc_video.type_data = reinterpret_cast<void *>(2);
    obs_register_encoder(&hevc_video);

    obs_encoder_info audio{};
    audio.id = "android_mediacodec_aac";
    audio.type = OBS_ENCODER_AUDIO;
    audio.codec = "aac";
    audio.type_data = reinterpret_cast<void *>(1);
    audio.get_name = name;
    audio.create = create;
    audio.destroy = destroy;
    audio.encode = encode;
    audio.update = update;
    audio.get_extra_data = extra_data;
    audio.get_audio_info = audio_info;
    audio.get_frame_size = audio_size;
    audio.get_defaults = audio_defaults;
    audio.get_properties = audio_properties;
    obs_register_encoder(&audio);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_encoder_stats(char *out, size_t capacity)
{
    if (!out || !capacity) return false;
    const uint64_t calls = encode_calls.load();
    auto json = nlohmann::json{{"active_surface_codecs", active_surface_codecs.load()},
        {"active_cpu_video_codecs", active_buffer_codecs.load()}, {"surface_frames", surface_frames.load()},
        {"cpu_video_frames", cpu_frames.load()}, {"encode_avg_ms", calls ? double(encode_ns.load()) / calls / 1e6 : 0.0},
        {"encode_max_ms", double(encode_max_ns.load()) / 1e6}}.dump();
    if (json.size() >= capacity) return false;
    std::memcpy(out, json.c_str(), json.size() + 1);
    return true;
}
