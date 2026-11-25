// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-hw-video.h"
#include <obs.h>
#include <graphics/graphics.h>
#include <util/platform.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#ifndef GL_FRAMEBUFFER_SRGB
#define GL_FRAMEBUFFER_SRGB 0x8DB9
#endif
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>

namespace {
using PFN_eglGetNativeClientBufferANDROID = EGLClientBuffer(EGLAPIENTRYP)(const struct AHardwareBuffer *);
using PFN_eglCreateImageKHR = EGLImageKHR(EGLAPIENTRYP)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint *);
using PFN_eglDestroyImageKHR = EGLBoolean(EGLAPIENTRYP)(EGLDisplay, EGLImageKHR);
using PFN_glEGLImageTargetTexture2DOES = void (*)(GLenum, void *);

struct HwOesBlit {
    bool initialized{false};
    bool supported{false};
    PFN_eglGetNativeClientBufferANDROID get_cbuf{};
    PFN_eglCreateImageKHR create_img{};
    PFN_eglDestroyImageKHR destroy_img{};
    PFN_glEGLImageTargetTexture2DOES target_tex{};
    GLuint prog{0};
    GLuint vao{0};
    GLint loc_tex{-1};
    GLint loc_rot{-1};
};

HwOesBlit g_hw_oes;

constexpr const char *kHwOesVS = R"(#version 300 es
out vec2 v_uv;
void main() {
    vec2 pos = vec2((gl_VertexID == 1) ? 3.0 : -1.0,
                    (gl_VertexID == 2) ? 3.0 : -1.0);
    gl_Position = vec4(pos, 0.0, 1.0);
    v_uv = vec2(pos.x * 0.5 + 0.5, pos.y * 0.5 + 0.5);
}
)";

constexpr const char *kHwOesFS = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 v_uv;
uniform samplerExternalOES u_tex;
uniform int u_rot;
out vec4 fragColor;
void main() {
    vec2 uv = v_uv;
    if (u_rot == 1) {
        uv = vec2(v_uv.y, 1.0 - v_uv.x);
    } else if (u_rot == 2) {
        uv = vec2(1.0 - v_uv.x, 1.0 - v_uv.y);
    } else if (u_rot == 3) {
        uv = vec2(1.0 - v_uv.y, v_uv.x);
    }
    fragColor = texture(u_tex, uv);
}
)";

GLuint compile_hw_shader(GLenum type, const char *src)
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
        blog(LOG_ERROR, "[android-hw-video] GLES shader error: %s", log);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

bool ensure_hw_oes_ready()
{
    if (g_hw_oes.initialized) return g_hw_oes.supported;
    g_hw_oes.initialized = true;
    g_hw_oes.get_cbuf = reinterpret_cast<PFN_eglGetNativeClientBufferANDROID>(
        eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    g_hw_oes.create_img = reinterpret_cast<PFN_eglCreateImageKHR>(eglGetProcAddress("eglCreateImageKHR"));
    g_hw_oes.destroy_img = reinterpret_cast<PFN_eglDestroyImageKHR>(eglGetProcAddress("eglDestroyImageKHR"));
    g_hw_oes.target_tex = reinterpret_cast<PFN_glEGLImageTargetTexture2DOES>(
        eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!g_hw_oes.get_cbuf || !g_hw_oes.create_img || !g_hw_oes.destroy_img || !g_hw_oes.target_tex)
        return false;
    GLuint vs = compile_hw_shader(GL_VERTEX_SHADER, kHwOesVS);
    GLuint fs = compile_hw_shader(GL_FRAGMENT_SHADER, kHwOesFS);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return false;
    }
    g_hw_oes.prog = glCreateProgram();
    glAttachShader(g_hw_oes.prog, vs);
    glAttachShader(g_hw_oes.prog, fs);
    glLinkProgram(g_hw_oes.prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(g_hw_oes.prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        glDeleteProgram(g_hw_oes.prog);
        g_hw_oes.prog = 0;
        return false;
    }
    glGenVertexArrays(1, &g_hw_oes.vao);
    g_hw_oes.loc_tex = glGetUniformLocation(g_hw_oes.prog, "u_tex");
    g_hw_oes.loc_rot = glGetUniformLocation(g_hw_oes.prog, "u_rot");
    g_hw_oes.supported = true;
    return true;
}

struct HwVideoSource {
    obs_source_t *source{};
    std::mutex cfg_mutex;
    std::condition_variable cv;
    std::string file_path;
    bool looping{true};
    bool close_when_inactive{true};
    bool restart_on_activate{true};
    int speed_percent{100};
    bool visible{true};
    bool reload_requested{false};
    bool restart_requested{false};
    std::atomic<bool> stop_flag{false};
    std::thread worker;

    // Frame handoff to OBS graphics thread
    std::mutex frame_mutex;
    AHardwareBuffer *pending_ahb{};
    int pending_w{0};
    int pending_h{0};
    int pending_rot{0}; // 0, 1 (90), 2 (180), 3 (270)
    uint64_t pending_gen{0};
    std::atomic<uint64_t> reader_gen{1};
    std::atomic<bool> cache_purge_needed{false};
    std::atomic<uint32_t> reported_w{0};
    std::atomic<uint32_t> reported_h{0};

    // Graphics-thread-only GL state
    gs_texture_t *obs_tex{};
    uint32_t tex_w{0}, tex_h{0};
    GLuint fbo{0};
    GLuint oes_tex{0};
    bool fbo_ready{false};
    EGLDisplay cached_dpy{EGL_NO_DISPLAY};
    uint64_t cached_gen{0};
    std::unordered_map<AHardwareBuffer *, EGLImageKHR> img_cache;

    void set_pending_ahb(AHardwareBuffer *hb, int w, int h, int rot_q, uint64_t gen) {
        AHardwareBuffer *old = nullptr;
        {
            std::lock_guard<std::mutex> lock(frame_mutex);
            old = pending_ahb;
            pending_ahb = hb;
            pending_w = w;
            pending_h = h;
            pending_rot = rot_q;
            pending_gen = gen;
        }
        if (old) AHardwareBuffer_release(old);
        reported_w.store(uint32_t(w), std::memory_order_relaxed);
        reported_h.store(uint32_t(h), std::memory_order_relaxed);
    }

    void clear_pending_ahb() {
        AHardwareBuffer *old = nullptr;
        {
            std::lock_guard<std::mutex> lock(frame_mutex);
            old = pending_ahb;
            pending_ahb = nullptr;
        }
        if (old) AHardwareBuffer_release(old);
    }

    void clear_img_cache() {
        if (img_cache.empty()) {
            cached_dpy = EGL_NO_DISPLAY;
            return;
        }
        if (oes_tex) {
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
        }
        for (auto &kv : img_cache) {
            if (kv.second != EGL_NO_IMAGE_KHR && cached_dpy != EGL_NO_DISPLAY && g_hw_oes.destroy_img) {
                g_hw_oes.destroy_img(cached_dpy, kv.second);
            }
            if (kv.first) AHardwareBuffer_release(kv.first);
        }
        img_cache.clear();
        cached_dpy = EGL_NO_DISPLAY;
    }

    void destroy_gl() {
        clear_img_cache();
        if (obs_tex) { gs_texture_destroy(obs_tex); obs_tex = nullptr; }
        if (fbo) { glDeleteFramebuffers(1, &fbo); fbo = 0; }
        if (oes_tex) { glDeleteTextures(1, &oes_tex); oes_tex = 0; }
        tex_w = tex_h = 0;
        fbo_ready = false;
    }
};

void decode_loop(HwVideoSource *self)
{
    while (!self->stop_flag.load(std::memory_order_acquire)) {
        std::string path;
        bool looping = true;
        bool close_inactive = true;
        int speed = 100;
        {
            std::unique_lock<std::mutex> lock(self->cfg_mutex);
            self->cv.wait(lock, [&]() {
                if (self->stop_flag.load(std::memory_order_acquire)) return true;
                if (self->file_path.empty()) return false;
                if (self->close_when_inactive && !self->visible) return false;
                return true;
            });
            if (self->stop_flag.load(std::memory_order_acquire)) break;
            path = self->file_path;
            looping = self->looping;
            close_inactive = self->close_when_inactive;
            speed = std::clamp(self->speed_percent, 25, 200);
            self->reload_requested = false;
            self->restart_requested = false;
        }

        int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) {
            blog(LOG_WARNING, "[android-hw-video] Cannot open '%s'", path.c_str());
            std::unique_lock<std::mutex> lock(self->cfg_mutex);
            self->cv.wait_for(lock, std::chrono::milliseconds(500), [&]() {
                return self->stop_flag.load() || self->reload_requested;
            });
            continue;
        }
        struct stat st{};
        if (fstat(fd, &st) != 0 || st.st_size <= 0) {
            close(fd);
            std::unique_lock<std::mutex> lock(self->cfg_mutex);
            self->cv.wait_for(lock, std::chrono::milliseconds(500), [&]() {
                return self->stop_flag.load() || self->reload_requested;
            });
            continue;
        }

        AMediaExtractor *ex = AMediaExtractor_new();
        if (!ex || AMediaExtractor_setDataSourceFd(ex, fd, 0, st.st_size) != AMEDIA_OK) {
            if (ex) AMediaExtractor_delete(ex);
            close(fd);
            std::unique_lock<std::mutex> lock(self->cfg_mutex);
            self->cv.wait_for(lock, std::chrono::milliseconds(500), [&]() {
                return self->stop_flag.load() || self->reload_requested;
            });
            continue;
        }

        int v_track = -1, a_track = -1;
        AMediaFormat *v_fmt = nullptr;
        AMediaFormat *a_fmt = nullptr;
        std::string v_mime, a_mime;
        int raw_w = 0, raw_h = 0, rot_deg = 0;
        int a_rate = 48000, a_ch = 2;

        const size_t track_count = AMediaExtractor_getTrackCount(ex);
        for (size_t i = 0; i < track_count; ++i) {
            AMediaFormat *fmt = AMediaExtractor_getTrackFormat(ex, i);
            const char *mime = nullptr;
            if (fmt && AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime) && mime) {
                if (v_track < 0 && std::strncmp(mime, "video/", 6) == 0) {
                    v_track = int(i);
                    v_fmt = fmt;
                    v_mime = mime;
                    AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, &raw_w);
                    AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &raw_h);
                    AMediaFormat_getInt32(fmt, "rotation-degrees", &rot_deg);
                    continue;
                }
                if (a_track < 0 && std::strncmp(mime, "audio/", 6) == 0) {
                    a_track = int(i);
                    a_fmt = fmt;
                    a_mime = mime;
                    AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &a_rate);
                    AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &a_ch);
                    continue;
                }
            }
            if (fmt) AMediaFormat_delete(fmt);
        }

        if (v_track < 0 || !v_fmt || raw_w <= 0 || raw_h <= 0) {
            if (v_fmt) AMediaFormat_delete(v_fmt);
            if (a_fmt) AMediaFormat_delete(a_fmt);
            AMediaExtractor_delete(ex);
            close(fd);
            std::unique_lock<std::mutex> lock(self->cfg_mutex);
            self->cv.wait_for(lock, std::chrono::milliseconds(500), [&]() {
                return self->stop_flag.load() || self->reload_requested;
            });
            continue;
        }

        const int rot_q = ((rot_deg % 360) + 360) % 360 / 90; // 0, 1, 2, 3
        const int out_w = (rot_q == 1 || rot_q == 3) ? raw_h : raw_w;
        const int out_h = (rot_q == 1 || rot_q == 3) ? raw_w : raw_h;

        AImageReader *reader = nullptr;
        ANativeWindow *win = nullptr;
        AMediaCodec *vdec = nullptr;
        AMediaCodec *adec = nullptr;

        if (AImageReader_newWithUsage(raw_w, raw_h, AIMAGE_FORMAT_PRIVATE,
                                      AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, 4, &reader) == AMEDIA_OK &&
            reader && AImageReader_getWindow(reader, &win) == AMEDIA_OK && win) {
            vdec = AMediaCodec_createDecoderByType(v_mime.c_str());
            if (vdec) {
                if (AMediaCodec_configure(vdec, v_fmt, win, nullptr, 0) != AMEDIA_OK ||
                    AMediaCodec_start(vdec) != AMEDIA_OK) {
                    AMediaCodec_delete(vdec);
                    vdec = nullptr;
                }
            }
        }
        if (vdec && a_track >= 0 && a_fmt) {
            adec = AMediaCodec_createDecoderByType(a_mime.c_str());
            if (adec) {
                if (AMediaCodec_configure(adec, a_fmt, nullptr, nullptr, 0) != AMEDIA_OK ||
                    AMediaCodec_start(adec) != AMEDIA_OK) {
                    AMediaCodec_delete(adec);
                    adec = nullptr;
                }
            }
        }

        if (v_fmt) AMediaFormat_delete(v_fmt);
        if (a_fmt) AMediaFormat_delete(a_fmt);

        if (!vdec) {
            blog(LOG_ERROR, "[android-hw-video] Failed to start MediaCodec decoder (%s %dx%d)",
                 v_mime.c_str(), raw_w, raw_h);
            if (adec) { AMediaCodec_stop(adec); AMediaCodec_delete(adec); }
            if (reader) AImageReader_delete(reader);
            AMediaExtractor_delete(ex);
            close(fd);
            std::unique_lock<std::mutex> lock(self->cfg_mutex);
            self->cv.wait_for(lock, std::chrono::milliseconds(500), [&]() {
                return self->stop_flag.load() || self->reload_requested;
            });
            continue;
        }

        const uint64_t session_gen = self->reader_gen.fetch_add(1, std::memory_order_acq_rel) + 1;
        AMediaExtractor_selectTrack(ex, size_t(v_track));
        if (adec && a_track >= 0) AMediaExtractor_selectTrack(ex, size_t(a_track));
        blog(LOG_INFO, "[android-hw-video] Hardware GPU decode active: '%s' (%s %dx%d rot=%d audio=%d)",
             path.c_str(), v_mime.c_str(), out_w, out_h, rot_q * 90, adec != nullptr);

        bool input_eos = false;
        bool video_eos = false;
        bool clock_init = false;
        int64_t base_pts_us = 0;
        auto wall_base = std::chrono::steady_clock::now();

        while (!self->stop_flag.load(std::memory_order_acquire)) {
            bool do_reload = false;
            bool do_restart = false;
            bool should_pause = false;
            {
                std::lock_guard<std::mutex> lock(self->cfg_mutex);
                do_reload = self->reload_requested;
                do_restart = self->restart_requested;
                self->restart_requested = false;
                looping = self->looping;
                close_inactive = self->close_when_inactive;
                speed = std::clamp(self->speed_percent, 25, 200);
                should_pause = close_inactive && !self->visible;
            }
            if (do_reload || should_pause) break;
            if (do_restart) {
                AMediaExtractor_seekTo(ex, 0, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
                AMediaCodec_flush(vdec);
                if (adec) AMediaCodec_flush(adec);
                input_eos = false;
                video_eos = false;
                clock_init = false;
            }

            // 1) Feed extractor packets into video / audio hardware decoders
            if (!input_eos) {
                int track = AMediaExtractor_getSampleTrackIndex(ex);
                if (track < 0) {
                    ssize_t vi = AMediaCodec_dequeueInputBuffer(vdec, 2000);
                    if (vi >= 0) {
                        AMediaCodec_queueInputBuffer(vdec, size_t(vi), 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                        if (adec) {
                            ssize_t ai = AMediaCodec_dequeueInputBuffer(adec, 2000);
                            if (ai >= 0) {
                                AMediaCodec_queueInputBuffer(adec, size_t(ai), 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                            }
                        }
                        input_eos = true;
                    }
                } else if (track == v_track) {
                    ssize_t vi = AMediaCodec_dequeueInputBuffer(vdec, 2000);
                    if (vi >= 0) {
                        size_t cap = 0;
                        uint8_t *buf = AMediaCodec_getInputBuffer(vdec, size_t(vi), &cap);
                        ssize_t sz = buf ? AMediaExtractor_readSampleData(ex, buf, cap) : -1;
                        int64_t pts = AMediaExtractor_getSampleTime(ex);
                        if (sz > 0) {
                            AMediaCodec_queueInputBuffer(vdec, size_t(vi), 0, size_t(sz), uint64_t(std::max<int64_t>(0, pts)), 0);
                            AMediaExtractor_advance(ex);
                        } else {
                            AMediaCodec_queueInputBuffer(vdec, size_t(vi), 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                            input_eos = true;
                        }
                    }
                } else if (adec && track == a_track) {
                    ssize_t ai = AMediaCodec_dequeueInputBuffer(adec, 1000);
                    if (ai >= 0) {
                        size_t cap = 0;
                        uint8_t *buf = AMediaCodec_getInputBuffer(adec, size_t(ai), &cap);
                        ssize_t sz = buf ? AMediaExtractor_readSampleData(ex, buf, cap) : -1;
                        int64_t pts = AMediaExtractor_getSampleTime(ex);
                        if (sz > 0) {
                            AMediaCodec_queueInputBuffer(adec, size_t(ai), 0, size_t(sz), uint64_t(std::max<int64_t>(0, pts)), 0);
                            AMediaExtractor_advance(ex);
                        } else {
                            AMediaCodec_queueInputBuffer(adec, size_t(ai), 0, 0, 0, 0);
                            AMediaExtractor_advance(ex);
                        }
                    }
                } else {
                    AMediaExtractor_advance(ex);
                }
            }

            // 2) Drain decoded audio PCM into OBS audio pipeline
            if (adec) {
                for (int k = 0; k < 4; ++k) {
                    AMediaCodecBufferInfo abi{};
                    ssize_t ao = AMediaCodec_dequeueOutputBuffer(adec, &abi, 0);
                    if (ao == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                        AMediaFormat *of = AMediaCodec_getOutputFormat(adec);
                        if (of) {
                            AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_SAMPLE_RATE, &a_rate);
                            AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &a_ch);
                            AMediaFormat_delete(of);
                        }
                    } else if (ao >= 0) {
                        size_t alen = 0;
                        uint8_t *pcm = AMediaCodec_getOutputBuffer(adec, size_t(ao), &alen);
                        const int ch = std::clamp(a_ch, 1, 2);
                        if (pcm && abi.size > 0 && self->source) {
                            obs_source_audio pkt{};
                            pkt.data[0] = pcm + abi.offset;
                            pkt.frames = uint32_t(abi.size / (ch * 2));
                            pkt.speakers = (ch == 1) ? SPEAKERS_MONO : SPEAKERS_STEREO;
                            pkt.format = AUDIO_FORMAT_16BIT;
                            pkt.samples_per_sec = uint32_t(a_rate > 0 ? a_rate : 48000);
                            pkt.timestamp = os_gettime_ns();
                            if (pkt.frames > 0) obs_source_output_audio(self->source, &pkt);
                        }
                        AMediaCodec_releaseOutputBuffer(adec, size_t(ao), false);
                    } else {
                        break;
                    }
                }
            }

            // 3) Drain decoded video frame -> pace to real time -> zero-copy AHardwareBuffer handoff
            AMediaCodecBufferInfo vbi{};
            ssize_t vo = AMediaCodec_dequeueOutputBuffer(vdec, &vbi, 4000);
            if (vo >= 0) {
                if (vbi.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                    video_eos = true;
                }
                const bool render = (vbi.size > 0);
                if (render) {
                    if (!clock_init) {
                        clock_init = true;
                        base_pts_us = vbi.presentationTimeUs;
                        wall_base = std::chrono::steady_clock::now();
                    } else {
                        const int64_t rel_us = (vbi.presentationTimeUs - base_pts_us) * 100LL / std::max(25, speed);
                        const auto target_time = wall_base + std::chrono::microseconds(std::max<int64_t>(0, rel_us));
                        const auto now = std::chrono::steady_clock::now();
                        if (target_time > now) {
                            auto wait_ms = std::chrono::duration_cast<std::chrono::milliseconds>(target_time - now);
                            if (wait_ms.count() > 200) wait_ms = std::chrono::milliseconds(200);
                            std::unique_lock<std::mutex> lock(self->cfg_mutex);
                            self->cv.wait_for(lock, wait_ms, [&]() {
                                return self->stop_flag.load() || self->reload_requested || self->restart_requested ||
                                       (self->close_when_inactive && !self->visible);
                            });
                        }
                    }
                }
                AMediaCodec_releaseOutputBuffer(vdec, size_t(vo), render);
                if (render) {
                    AImage *img = nullptr;
                    if (AImageReader_acquireLatestImage(reader, &img) == AMEDIA_OK && img) {
                        AHardwareBuffer *hb = nullptr;
                        if (AImage_getHardwareBuffer(img, &hb) == AMEDIA_OK && hb) {
                            AHardwareBuffer_acquire(hb);
                            self->set_pending_ahb(hb, out_w, out_h, rot_q, session_gen);
                        }
                        AImage_delete(img);
                    }
                }
            }

            if (video_eos) {
                if (looping) {
                    AMediaExtractor_seekTo(ex, 0, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
                    AMediaCodec_flush(vdec);
                    if (adec) AMediaCodec_flush(adec);
                    input_eos = false;
                    video_eos = false;
                    clock_init = false;
                } else {
                    std::unique_lock<std::mutex> lock(self->cfg_mutex);
                    self->cv.wait(lock, [&]() {
                        return self->stop_flag.load() || self->reload_requested || self->restart_requested ||
                               (self->close_when_inactive && !self->visible);
                    });
                }
            }
        }

        self->clear_pending_ahb();
        if (vdec) { AMediaCodec_stop(vdec); AMediaCodec_delete(vdec); }
        if (adec) { AMediaCodec_stop(adec); AMediaCodec_delete(adec); }
        if (reader) AImageReader_delete(reader);
        AMediaExtractor_delete(ex);
        close(fd);
        self->reader_gen.fetch_add(1, std::memory_order_release);
        self->cache_purge_needed.store(true, std::memory_order_release);
    }
}

void hw_video_render(void *data, gs_effect_t *effect)
{
    auto *self = static_cast<HwVideoSource *>(data);
    if (!self) return;

    AHardwareBuffer *ahb = nullptr;
    int w = 0, h = 0, rot_q = 0;
    uint64_t gen = 0;
    {
        std::lock_guard<std::mutex> lock(self->frame_mutex);
        if (self->pending_ahb) {
            ahb = self->pending_ahb;
            w = self->pending_w;
            h = self->pending_h;
            rot_q = self->pending_rot;
            gen = self->pending_gen;
            self->pending_ahb = nullptr;
        }
    }

    if (ahb && w > 0 && h > 0 && ensure_hw_oes_ready()) {
        EGLDisplay dpy = eglGetCurrentDisplay();
        if (dpy != EGL_NO_DISPLAY) {
            if (self->cached_dpy != dpy || self->cached_gen != gen || self->img_cache.size() >= 12) {
                self->clear_img_cache();
                self->cached_dpy = dpy;
                self->cached_gen = gen;
            }
            EGLImageKHR img = EGL_NO_IMAGE_KHR;
            auto it = self->img_cache.find(ahb);
            if (it != self->img_cache.end()) {
                img = it->second;
            } else {
                EGLClientBuffer cbuf = g_hw_oes.get_cbuf(ahb);
                const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
                img = cbuf ? g_hw_oes.create_img(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cbuf, attrs)
                           : EGL_NO_IMAGE_KHR;
                if (img != EGL_NO_IMAGE_KHR) {
                    AHardwareBuffer_acquire(ahb);
                    self->img_cache.emplace(ahb, img);
                }
            }
            if (img != EGL_NO_IMAGE_KHR) {
                if (!self->obs_tex || self->tex_w != uint32_t(w) || self->tex_h != uint32_t(h)) {
                    if (self->obs_tex) gs_texture_destroy(self->obs_tex);
                    self->obs_tex = gs_texture_create(uint32_t(w), uint32_t(h), GS_RGBA, 1, nullptr, GS_RENDER_TARGET);
                    self->tex_w = self->obs_tex ? uint32_t(w) : 0;
                    self->tex_h = self->obs_tex ? uint32_t(h) : 0;
                    self->fbo_ready = false;
                }
                const auto *dst_tex_ptr = self->obs_tex ? static_cast<const GLuint *>(gs_texture_get_obj(self->obs_tex)) : nullptr;
                if (dst_tex_ptr && *dst_tex_ptr) {
                    if (!self->fbo) { glGenFramebuffers(1, &self->fbo); self->fbo_ready = false; }
                    if (!self->oes_tex) glGenTextures(1, &self->oes_tex);

                    GLint prev_draw_fbo = 0, prev_read_fbo = 0, prev_prog = 0, prev_vao = 0, prev_active = GL_TEXTURE0, prev_oes = 0;
                    GLint prev_vp[4] = {};
                    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &prev_draw_fbo);
                    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &prev_read_fbo);
                    glGetIntegerv(GL_CURRENT_PROGRAM, &prev_prog);
                    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &prev_vao);
                    glGetIntegerv(GL_ACTIVE_TEXTURE, &prev_active);
                    glGetIntegerv(GL_VIEWPORT, prev_vp);
                    const GLboolean prev_blend = glIsEnabled(GL_BLEND);
                    const GLboolean prev_scissor = glIsEnabled(GL_SCISSOR_TEST);
                    const GLboolean prev_depth = glIsEnabled(GL_DEPTH_TEST);
                    const GLboolean prev_cull = glIsEnabled(GL_CULL_FACE);
                    const GLboolean prev_srgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);

                    glActiveTexture(GL_TEXTURE0);
                    glGetIntegerv(GL_TEXTURE_BINDING_EXTERNAL_OES, &prev_oes);
                    glBindTexture(GL_TEXTURE_EXTERNAL_OES, self->oes_tex);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                    g_hw_oes.target_tex(GL_TEXTURE_EXTERNAL_OES, img);

                    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, self->fbo);
                    if (!self->fbo_ready) {
                        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, *dst_tex_ptr, 0);
                        self->fbo_ready = (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE);
                    }
                    if (self->fbo_ready) {
                        if (prev_blend) glDisable(GL_BLEND);
                        if (prev_scissor) glDisable(GL_SCISSOR_TEST);
                        if (prev_depth) glDisable(GL_DEPTH_TEST);
                        if (prev_cull) glDisable(GL_CULL_FACE);
                        if (prev_srgb) glDisable(GL_FRAMEBUFFER_SRGB);
                        glViewport(0, 0, w, h);
                        glUseProgram(g_hw_oes.prog);
                        glUniform1i(g_hw_oes.loc_tex, 0);
                        glUniform1i(g_hw_oes.loc_rot, rot_q);
                        glBindVertexArray(g_hw_oes.vao);
                        glDrawArrays(GL_TRIANGLES, 0, 3);
                    }

                    glBindVertexArray(GLuint(prev_vao));
                    glUseProgram(GLuint(prev_prog));
                    glBindTexture(GL_TEXTURE_EXTERNAL_OES, GLuint(prev_oes));
                    glActiveTexture(GLenum(prev_active));
                    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, GLuint(prev_draw_fbo));
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, GLuint(prev_read_fbo));
                    glViewport(prev_vp[0], prev_vp[1], prev_vp[2], prev_vp[3]);
                    if (prev_blend) glEnable(GL_BLEND);
                    if (prev_scissor) glEnable(GL_SCISSOR_TEST);
                    if (prev_depth) glEnable(GL_DEPTH_TEST);
                    if (prev_cull) glEnable(GL_CULL_FACE);
                    if (prev_srgb) glEnable(GL_FRAMEBUFFER_SRGB);
                }
            }
        }
        AHardwareBuffer_release(ahb);
    }

    if (!self->obs_tex || self->tex_w == 0 || self->tex_h == 0) return;
    gs_effect_t *eff = effect ? effect : obs_get_base_effect(OBS_EFFECT_DEFAULT);
    const bool linear_srgb = gs_get_linear_srgb();
    const bool previous_srgb = gs_framebuffer_srgb_enabled();
    gs_enable_framebuffer_srgb(linear_srgb);
    gs_eparam_t *param = gs_effect_get_param_by_name(eff, "image");
    if (linear_srgb)
        gs_effect_set_texture_srgb(param, self->obs_tex);
    else
        gs_effect_set_texture(param, self->obs_tex);
    while (gs_effect_loop(eff, "Draw"))
        gs_draw_sprite(self->obs_tex, 0, self->tex_w, self->tex_h);
    gs_enable_framebuffer_srgb(previous_srgb);
}
} // namespace

void android_hw_video_register()
{
    obs_source_info info{};
    info.id = "android_hw_video";
    info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_VIDEO | OBS_SOURCE_AUDIO | OBS_SOURCE_CUSTOM_DRAW | OBS_SOURCE_SRGB;
    info.icon_type = OBS_ICON_TYPE_MEDIA;
    info.get_name = [](void *) { return "Video por Hardware (GPU Android)"; };
    info.get_defaults = [](obs_data_t *s) {
        obs_data_set_default_string(s, "local_file", "");
        obs_data_set_default_bool(s, "looping", true);
        obs_data_set_default_bool(s, "close_when_inactive", true);
        obs_data_set_default_bool(s, "restart_on_activate", true);
        obs_data_set_default_int(s, "speed_percent", 100);
    };
    info.get_properties = [](void *) {
        obs_properties_t *props = obs_properties_create();
        obs_properties_add_path(props, "local_file", "Archivo de video (MP4 / MOV / MKV / WebM)",
                                OBS_PATH_FILE, "Videos (*.mp4 *.mov *.mkv *.webm *.m4v *.ts);;Todos (*)", nullptr);
        obs_properties_add_bool(props, "looping", "Bucle (repetir continuamente)");
        obs_properties_add_bool(props, "close_when_inactive", "Pausar al ocultar (0% CPU / GPU)");
        obs_properties_add_bool(props, "restart_on_activate", "Reiniciar al mostrar la escena");
        obs_properties_add_int_slider(props, "speed_percent", "Velocidad (%)", 25, 200, 5);
        return props;
    };
    info.update = [](void *data, obs_data_t *s) {
        auto *self = static_cast<HwVideoSource *>(data);
        if (!self) return;
        const char *fp = obs_data_get_string(s, "local_file");
        std::string next_path = fp ? fp : "";
        {
            std::lock_guard<std::mutex> lock(self->cfg_mutex);
            if (next_path != self->file_path) {
                self->file_path = next_path;
                self->reload_requested = true;
            }
            self->looping = obs_data_get_bool(s, "looping");
            self->close_when_inactive = obs_data_get_bool(s, "close_when_inactive");
            self->restart_on_activate = obs_data_get_bool(s, "restart_on_activate");
            self->speed_percent = std::clamp(int(obs_data_get_int(s, "speed_percent")), 25, 200);
        }
        self->cv.notify_all();
    };
    info.create = [](obs_data_t *s, obs_source_t *src) -> void * {
        auto *self = new HwVideoSource();
        self->source = src;
        const char *fp = obs_data_get_string(s, "local_file");
        self->file_path = fp ? fp : "";
        self->looping = obs_data_get_bool(s, "looping");
        self->close_when_inactive = obs_data_get_bool(s, "close_when_inactive");
        self->restart_on_activate = obs_data_get_bool(s, "restart_on_activate");
        self->speed_percent = std::clamp(int(obs_data_get_int(s, "speed_percent")), 25, 200);
        self->visible = obs_source_showing(src);
        self->worker = std::thread(decode_loop, self);
        return self;
    };
    info.destroy = [](void *data) {
        auto *self = static_cast<HwVideoSource *>(data);
        if (!self) return;
        self->stop_flag.store(true, std::memory_order_release);
        self->cv.notify_all();
        if (self->worker.joinable()) self->worker.join();
        self->clear_pending_ahb();
        obs_enter_graphics();
        self->destroy_gl();
        obs_leave_graphics();
        delete self;
    };
    info.show = [](void *data) {
        auto *self = static_cast<HwVideoSource *>(data);
        if (!self) return;
        {
            std::lock_guard<std::mutex> lock(self->cfg_mutex);
            self->visible = true;
            if (self->restart_on_activate) self->restart_requested = true;
        }
        self->cv.notify_all();
    };
    info.hide = [](void *data) {
        auto *self = static_cast<HwVideoSource *>(data);
        if (!self) return;
        {
            std::lock_guard<std::mutex> lock(self->cfg_mutex);
            self->visible = false;
        }
        self->cv.notify_all();
    };
    info.get_width = [](void *data) {
        return static_cast<HwVideoSource *>(data)->reported_w.load(std::memory_order_relaxed);
    };
    info.get_height = [](void *data) {
        return static_cast<HwVideoSource *>(data)->reported_h.load(std::memory_order_relaxed);
    };
    info.video_tick = [](void *data, float) {
        auto *self = static_cast<HwVideoSource *>(data);
        if (self && self->cache_purge_needed.exchange(false, std::memory_order_acq_rel)) {
            obs_enter_graphics();
            self->clear_img_cache();
            obs_leave_graphics();
        }
    };
    info.video_render = hw_video_render;
    obs_register_source(&info);
}

void android_hw_video_shutdown()
{
    if (obs_initialized()) {
        obs_enter_graphics();
        if (g_hw_oes.prog) { glDeleteProgram(g_hw_oes.prog); g_hw_oes.prog = 0; }
        if (g_hw_oes.vao) { glDeleteVertexArrays(1, &g_hw_oes.vao); g_hw_oes.vao = 0; }
        g_hw_oes.initialized = false;
        g_hw_oes.supported = false;
        obs_leave_graphics();
    }
}
