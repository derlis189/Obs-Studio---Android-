#include "android-session-gate.h"
#include "android-rgba-frame.h"
// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-vtuber.h"
#include "android-sources.h"
#include <obs-module.h>
#include <graphics/graphics.h>
#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#ifndef GL_FRAMEBUFFER_SRGB
#define GL_FRAMEBUFFER_SRGB 0x8DB9
#endif
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
using PFN_eglGetNativeClientBufferANDROID = EGLClientBuffer(EGLAPIENTRYP)(const struct AHardwareBuffer *buffer);
using PFN_eglCreateImageKHR = EGLImageKHR(EGLAPIENTRYP)(EGLDisplay dpy, EGLContext ctx, EGLenum target,
                                                        EGLClientBuffer buffer, const EGLint *attrib_list);
using PFN_eglDestroyImageKHR = EGLBoolean(EGLAPIENTRYP)(EGLDisplay dpy, EGLImageKHR image);
using PFN_glEGLImageTargetTexture2DOES = void (*)(GLenum target, void *image);

struct VtuberOesBlitContext {
    bool initialized{false};
    bool supported{false};
    PFN_eglGetNativeClientBufferANDROID get_native_client_buffer{};
    PFN_eglCreateImageKHR create_image{};
    PFN_eglDestroyImageKHR destroy_image{};
    PFN_glEGLImageTargetTexture2DOES image_target_tex2d{};
    GLuint prog{0};
    GLuint vao{0};
    GLint loc_tex{-1};
    GLint loc_sbs_alpha{-1};
};

VtuberOesBlitContext g_vt_oes;

constexpr const char *kVtuberOesVS = R"(#version 300 es
out vec2 v_uv;
void main() {
    vec2 pos = vec2((gl_VertexID == 1) ? 3.0 : -1.0,
                    (gl_VertexID == 2) ? 3.0 : -1.0);
    gl_Position = vec4(pos, 0.0, 1.0);
    v_uv = pos * 0.5 + 0.5;
}
)";

constexpr const char *kVtuberOesFS = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 v_uv;
uniform samplerExternalOES u_tex;
uniform int u_sbs_alpha;
out vec4 fragColor;
void main() {
    if (u_sbs_alpha != 0) {
        vec3 rgb = texture(u_tex, vec2(v_uv.x * 0.5, v_uv.y)).rgb;
        float a = texture(u_tex, vec2(0.5 + v_uv.x * 0.5, v_uv.y)).r;
        fragColor = vec4(rgb, a);
    } else {
        fragColor = texture(u_tex, v_uv);
    }
}
)";

GLuint compile_vt_oes_shader(GLenum type, const char *src)
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
        blog(LOG_ERROR, "[android-vtuber] GLES OES shader error: %s", log);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

bool ensure_vt_oes_ready()
{
    if (g_vt_oes.initialized) return g_vt_oes.supported;
    g_vt_oes.initialized = true;
    g_vt_oes.get_native_client_buffer =
        reinterpret_cast<PFN_eglGetNativeClientBufferANDROID>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    g_vt_oes.create_image = reinterpret_cast<PFN_eglCreateImageKHR>(eglGetProcAddress("eglCreateImageKHR"));
    g_vt_oes.destroy_image = reinterpret_cast<PFN_eglDestroyImageKHR>(eglGetProcAddress("eglDestroyImageKHR"));
    g_vt_oes.image_target_tex2d =
        reinterpret_cast<PFN_glEGLImageTargetTexture2DOES>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!g_vt_oes.get_native_client_buffer || !g_vt_oes.create_image ||
        !g_vt_oes.destroy_image || !g_vt_oes.image_target_tex2d) {
        blog(LOG_WARNING, "[android-vtuber] EGLImage/OES unavailable; using WebMessage/Bitmap fallback");
        return false;
    }
    GLuint vs = compile_vt_oes_shader(GL_VERTEX_SHADER, kVtuberOesVS);
    GLuint fs = compile_vt_oes_shader(GL_FRAGMENT_SHADER, kVtuberOesFS);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return false;
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
        blog(LOG_ERROR, "[android-vtuber] GLES OES link error: %s", log);
        glDeleteProgram(prog);
        return false;
    }
    g_vt_oes.prog = prog;
    g_vt_oes.loc_tex = glGetUniformLocation(prog, "u_tex");
    g_vt_oes.loc_sbs_alpha = glGetUniformLocation(prog, "u_sbs_alpha");
    glGenVertexArrays(1, &g_vt_oes.vao);
    g_vt_oes.supported = true;
    blog(LOG_INFO, "[android-vtuber] Zero-copy AHardwareBuffer side-by-side alpha OES pipeline enabled");
    return true;
}

struct VtuberHwState {
    std::mutex mutex;
    AHardwareBuffer *pending_ahb{};
    int pending_w{0}, pending_h{0};
    bool pending_sbs{true};
    std::atomic<bool> active{false};
    std::atomic<uint32_t> reported_w{0};
    std::atomic<uint32_t> reported_h{0};

    // Graphics-thread-only resources
    gs_texture_t *obs_tex{};
    uint32_t tex_w{0}, tex_h{0};
    GLuint fbo{0};
    GLuint oes_tex{0};
    bool fbo_ready{false};
    bool logged_active{false};
    EGLDisplay cached_dpy{EGL_NO_DISPLAY};
    std::unordered_map<AHardwareBuffer *, EGLImageKHR> img_cache;

    void clear_img_cache() {
        if (oes_tex) glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
        for (auto &kv : img_cache) {
            if (kv.second != EGL_NO_IMAGE_KHR && cached_dpy != EGL_NO_DISPLAY && g_vt_oes.destroy_image) {
                g_vt_oes.destroy_image(cached_dpy, kv.second);
            }
            if (kv.first) AHardwareBuffer_release(kv.first);
        }
        img_cache.clear();
        cached_dpy = EGL_NO_DISPLAY;
    }

    void clear_pending() {
        AHardwareBuffer *old = nullptr;
        {
            std::lock_guard<std::mutex> lock(mutex);
            old = pending_ahb;
            pending_ahb = nullptr;
            pending_w = pending_h = 0;
        }
        if (old) AHardwareBuffer_release(old);
        active.store(false, std::memory_order_release);
    }

    void destroy_gl_resources() {
        clear_img_cache();
        if (obs_tex) { gs_texture_destroy(obs_tex); obs_tex = nullptr; }
        if (fbo) { glDeleteFramebuffers(1, &fbo); fbo = 0; }
        if (oes_tex) { glDeleteTextures(1, &oes_tex); oes_tex = 0; }
        tex_w = tex_h = 0;
        fbo_ready = false;
        logged_active = false;
    }
};

void blit_vtuber_ahb(VtuberHwState &hw, AHardwareBuffer *ahb, int w, int h, bool sbs_alpha, int64_t inst_id)
{
    if (!ahb || w <= 0 || h <= 0) return;
    if (!ensure_vt_oes_ready()) return;
    EGLDisplay dpy = eglGetCurrentDisplay();
    if (dpy == EGL_NO_DISPLAY) return;

    const bool size_changed = (!hw.obs_tex || hw.tex_w != uint32_t(w) || hw.tex_h != uint32_t(h));
    if (size_changed || hw.cached_dpy != dpy || hw.img_cache.size() > 4) {
        hw.clear_img_cache();
        hw.cached_dpy = dpy;
    }

    EGLImageKHR img = EGL_NO_IMAGE_KHR;
    auto it = hw.img_cache.find(ahb);
    if (it != hw.img_cache.end()) {
        img = it->second;
    } else {
        EGLClientBuffer cbuf = g_vt_oes.get_native_client_buffer(ahb);
        if (!cbuf) return;
        const EGLint img_attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        img = g_vt_oes.create_image(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cbuf, img_attrs);
        if (img == EGL_NO_IMAGE_KHR) return;
        AHardwareBuffer_acquire(ahb);
        hw.img_cache.emplace(ahb, img);
    }

    if (size_changed) {
        if (hw.obs_tex) gs_texture_destroy(hw.obs_tex);
        hw.obs_tex = gs_texture_create(uint32_t(w), uint32_t(h), GS_RGBA, 1, nullptr, GS_RENDER_TARGET);
        hw.tex_w = hw.obs_tex ? uint32_t(w) : 0;
        hw.tex_h = hw.obs_tex ? uint32_t(h) : 0;
        hw.fbo_ready = false;
    }
    if (!hw.obs_tex) return;
    const auto *dst_tex_ptr = static_cast<const GLuint *>(gs_texture_get_obj(hw.obs_tex));
    if (!dst_tex_ptr || !*dst_tex_ptr) return;
    if (!hw.fbo) { glGenFramebuffers(1, &hw.fbo); hw.fbo_ready = false; }
    if (!hw.oes_tex) glGenTextures(1, &hw.oes_tex);

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
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, hw.oes_tex);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    g_vt_oes.image_target_tex2d(GL_TEXTURE_EXTERNAL_OES, img);

    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, hw.fbo);
    if (!hw.fbo_ready) {
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, *dst_tex_ptr, 0);
        hw.fbo_ready = (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE);
    }
    if (hw.fbo_ready) {
        if (prev_blend) glDisable(GL_BLEND);
        if (prev_scissor) glDisable(GL_SCISSOR_TEST);
        if (prev_depth) glDisable(GL_DEPTH_TEST);
        if (prev_cull) glDisable(GL_CULL_FACE);
        if (prev_srgb) glDisable(GL_FRAMEBUFFER_SRGB);
        glViewport(0, 0, w, h);
        glUseProgram(g_vt_oes.prog);
        glUniform1i(g_vt_oes.loc_tex, 0);
        glUniform1i(g_vt_oes.loc_sbs_alpha, sbs_alpha ? 1 : 0);
        glBindVertexArray(g_vt_oes.vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        if (!hw.logged_active) {
            hw.logged_active = true;
            blog(LOG_INFO, "[android-vtuber] Zero-copy HardwareBuffer alpha-pack active id=%lld (%dx%d sbs=%d)",
                 static_cast<long long>(inst_id), w, h, sbs_alpha ? 1 : 0);
        }
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

std::mutex g_vt_mutex;
JavaVM *g_vt_jvm = nullptr;
jclass g_vt_host_class = nullptr;
jmethodID g_mid_vt_update = nullptr;
jmethodID g_mid_vt_destroy = nullptr;
jmethodID g_mid_vt_visible = nullptr;
jmethodID g_mid_vt_destroy_all = nullptr;
jmethodID g_mid_vt_pick_cubism = nullptr;
jmethodID g_mid_vt_gesture = nullptr;
jmethodID g_mid_vt_expr = nullptr;
std::atomic<int64_t> g_vt_seq{0};
std::atomic<int> g_vt_active_count{0};
bool g_vt_registered = false;

struct VtuberInstance {
    AndroidRgbaFrame frame;
    VtuberHwState hw;
    int64_t id{};
    obs_source_t *source{};
    std::string mode{"png"};
    int width{720};
    int height{720};
    int fps{30};
    bool veado_auto_emotion{};
    std::string config_json;
};

std::unordered_map<int64_t, std::shared_ptr<VtuberInstance>> g_vt_instances;

std::string json_escape(const char *s)
{
    if (!s) return "";
    std::string out;
    for (const unsigned char *p = reinterpret_cast<const unsigned char *>(s); *p; ++p) {
        if (*p == '"' || *p == '\\') { out.push_back('\\'); out.push_back(char(*p)); }
        else if (*p == '\n') out += "\\n";
        else if (*p == '\r') out += "\\r";
        else if (*p >= 32) out.push_back(char(*p));
    }
    return out;
}

void push_vtuber_config(const VtuberInstance *inst)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        jvm = g_vt_jvm; cls = g_vt_host_class; mid = g_mid_vt_update;
    }
    if (!jvm || !cls || !mid) return;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return; detach = true; }
    else if (st != JNI_OK) return;

    jstring jCfg = env->NewStringUTF(inst->config_json.c_str());
    env->CallStaticVoidMethod(cls, mid, jlong(inst->id), jCfg, jint(inst->width), jint(inst->height), jint(inst->fps));
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jCfg);
    if (detach) jvm->DetachCurrentThread();
}

void call_vtuber_id(jmethodID mid, int64_t id)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        jvm = g_vt_jvm; cls = g_vt_host_class;
    }
    if (!jvm || !cls || !mid) return;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return; detach = true; }
    else if (st != JNI_OK) return;

    env->CallStaticVoidMethod(cls, mid, jlong(id));
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) jvm->DetachCurrentThread();
}

const char *vtuber_get_name(void *) { return "Avatar VTuber (PNG / Live2D / VRM / Inochi2D)"; }

void vtuber_get_defaults(obs_data_t *s)
{
    obs_data_set_default_string(s, "vt_mode", "png");
    obs_data_set_default_string(s, "veadomini_file", "");
    obs_data_set_default_int(s, "veadomini_state", 0);
    obs_data_set_default_bool(s, "veadomini_auto_emotion", false);
    obs_data_set_default_int(s, "veadomini_happy_state", 1);
    obs_data_set_default_int(s, "veadomini_sad_state", 2);
    obs_data_set_default_int(s, "veadomini_angry_state", 3);
    obs_data_set_default_int(s, "veadomini_surprised_state", 4);
    obs_data_set_default_string(s, "png_idle", "");
    obs_data_set_default_string(s, "png_talk", "");
    obs_data_set_default_string(s, "png_blink", "");
    obs_data_set_default_string(s, "png_blink_talk", "");
    obs_data_set_default_bool(s, "png_bounce", true);
    obs_data_set_default_double(s, "voice_threshold", 0.04);
    obs_data_set_default_string(s, "vrm_file", "");
    obs_data_set_default_string(s, "vrma_file", "");
    obs_data_set_default_bool(s, "vrma_loop", true);
    obs_data_set_default_double(s, "vrm_cam_dist", 1.15);
    obs_data_set_default_double(s, "vrm_cam_y", 1.38);
    obs_data_set_default_string(s, "live2d_file", "");
    obs_data_set_default_string(s, "live2d_framing", "bust");
    obs_data_set_default_double(s, "live2d_zoom", 1.0);
    obs_data_set_default_double(s, "live2d_offset_y", 0.0);
    obs_data_set_default_string(s, "inochi_file", "");
    obs_data_set_default_string(s, "vt_gesture", "");
    obs_data_set_default_bool(s, "face_tracking", true);
    obs_data_set_default_double(s, "head_sensitivity", 1.0);
    obs_data_set_default_bool(s, "vt_invert_x", false);
    obs_data_set_default_bool(s, "vt_invert_y", false);
    obs_data_set_default_bool(s, "hand_tracking", true);
    obs_data_set_default_bool(s, "lip_sync", true);
    obs_data_set_default_bool(s, "vts_api", true);
    obs_data_set_default_bool(s, "vt_zero_copy", true);
    obs_data_set_default_string(s, "vt_quality", "high");
    obs_data_set_default_int(s, "width", 768);
    obs_data_set_default_int(s, "height", 768);
    obs_data_set_default_int(s, "fps", 30);
}

bool on_vt_mode_modified(obs_properties_t *props, obs_property_t *, obs_data_t *s)
{
    const char *m = obs_data_get_string(s, "vt_mode");
    bool is_png = !m || std::strcmp(m, "png") == 0;
    bool is_l2d = m && std::strcmp(m, "live2d") == 0;
    bool is_vrm = m && std::strcmp(m, "vrm") == 0;
    bool is_inochi = m && std::strcmp(m, "inochi2d") == 0;

    for (const char *k : {"veadomini_file", "veadomini_state", "veadomini_auto_emotion", "veadomini_emotion_info", "veadomini_happy_state",
                          "veadomini_sad_state", "veadomini_angry_state", "veadomini_surprised_state",
                          "png_idle", "png_talk", "png_blink", "png_blink_talk", "png_bounce"}) {
        if (auto *p = obs_properties_get(props, k)) obs_property_set_visible(p, is_png);
    }
    for (const char *k : {"live2d_info", "install_cubism", "live2d_file"}) {
        if (auto *p = obs_properties_get(props, k)) obs_property_set_visible(p, is_l2d);
    }
    for (const char *k : {"live2d_framing", "live2d_zoom", "live2d_offset_y"}) {
        if (auto *p = obs_properties_get(props, k)) obs_property_set_visible(p, is_l2d || is_inochi);
    }
    for (const char *k : {"vrm_info", "vrm_file", "vrma_file", "vrma_loop", "vrm_cam_dist", "vrm_cam_y"}) {
        if (auto *p = obs_properties_get(props, k)) obs_property_set_visible(p, is_vrm);
    }
    for (const char *k : {"inochi_info", "inochi_file"}) {
        if (auto *p = obs_properties_get(props, k)) obs_property_set_visible(p, is_inochi);
    }
    for (const char *k : {"vt_gesture", "vt_zero_copy", "vt_invert_x", "vt_invert_y"}) {
        if (auto *p = obs_properties_get(props, k)) obs_property_set_visible(p, !is_png);
    }
    return true;
}

bool on_veado_auto_modified(obs_properties_t *props, obs_property_t *, obs_data_t *s)
{
    bool enabled = obs_data_get_bool(s, "veadomini_auto_emotion");
    for (const char *k : {"veadomini_emotion_info", "veadomini_happy_state", "veadomini_sad_state",
                          "veadomini_angry_state", "veadomini_surprised_state"}) {
        if (auto *p = obs_properties_get(props, k)) obs_property_set_visible(p, enabled);
    }
    return true;
}

bool on_install_cubism_clicked(obs_properties_t *, obs_property_t *, void *)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        jvm = g_vt_jvm; cls = g_vt_host_class; mid = g_mid_vt_pick_cubism;
    }
    if (!jvm || !cls || !mid) return false;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false; detach = true; }
    else if (st != JNI_OK) return false;
    env->CallStaticVoidMethod(cls, mid);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) jvm->DetachCurrentThread();
    return false;
}

obs_properties_t *vtuber_get_properties(void *data)
{
    obs_properties_t *props = obs_properties_create();
    obs_property_t *mode = obs_properties_add_list(props, "vt_mode", "Modo VTuber", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    obs_property_list_add_string(mode, "PNGTuber (.veadomini o imágenes PNG)", "png");
    obs_property_list_add_string(mode, "Live2D (.zip / .model3.json + seguimiento 52 ARKit)", "live2d");
    obs_property_list_add_string(mode, "VRM 3D (.vrm + .vrma + manos/52 ARKit WebGL)", "vrm");
    obs_property_list_add_string(mode, "Inochi2D (.inp / .inx · 2D libre sin Cubism)", "inochi2d");
    obs_property_set_modified_callback(mode, on_vt_mode_modified);

    obs_properties_add_path(props, "veadomini_file", "Archivo .veadomini (Veadotube Mini)", OBS_PATH_FILE, "Todos los archivos (*);;Veadotube Mini (*.veadomini *.zip)", nullptr);
    obs_properties_add_int(props, "veadomini_state", "Estado / Expresión (.veadomini, 0=principal)", 0, 15, 1);
    obs_properties_add_bool(props, "veadomini_auto_emotion", "Cambiar estado automáticamente con expresiones (MediaPipe / VMC)");
    obs_properties_add_text(props, "veadomini_emotion_info",
        "Detecta alegría, tristeza, enojo o sorpresa localmente con MediaPipe (52 ARKit) o por VMC/OSC UDP (puerto 39539).",
        OBS_TEXT_INFO);
    obs_properties_add_int(props, "veadomini_happy_state", "Estado para alegría (índice .veadomini)", 0, 15, 1);
    obs_properties_add_int(props, "veadomini_sad_state", "Estado para tristeza (índice .veadomini)", 0, 15, 1);
    obs_properties_add_int(props, "veadomini_angry_state", "Estado para enojo (índice .veadomini)", 0, 15, 1);
    obs_properties_add_int(props, "veadomini_surprised_state", "Estado para sorpresa (índice .veadomini)", 0, 15, 1);
    if (auto *autoEmotion = obs_properties_get(props, "veadomini_auto_emotion"))
        obs_property_set_modified_callback(autoEmotion, on_veado_auto_modified);
    obs_properties_add_path(props, "png_idle", "PNG Reposo (si no usas .veadomini)", OBS_PATH_FILE, "Todos los archivos (*);;Imágenes (*.png *.webp *.gif)", nullptr);
    obs_properties_add_path(props, "png_talk", "PNG Hablando (boca abierta)", OBS_PATH_FILE, "Todos los archivos (*);;Imágenes (*.png *.webp *.gif)", nullptr);
    obs_properties_add_path(props, "png_blink", "PNG Parpadeo (opcional)", OBS_PATH_FILE, "Todos los archivos (*);;Imágenes (*.png *.webp *.gif)", nullptr);
    obs_properties_add_path(props, "png_blink_talk", "PNG Hablando + parpadeo (opcional)", OBS_PATH_FILE, "Todos los archivos (*);;Imágenes (*.png *.webp *.gif)", nullptr);
    obs_properties_add_bool(props, "png_bounce", "Rebote suave al hablar");

    obs_properties_add_text(props, "live2d_info",
        "Por licencia, importa una vez live2dcubismcore.min.js con el botón de abajo y luego selecciona el .zip completo del modelo (o .model3.json).",
        OBS_TEXT_INFO);
    obs_properties_add_button(props, "install_cubism", "Importar Live2D Cubism Core (live2dcubismcore.min.js)...", on_install_cubism_clicked);
    obs_properties_add_path(props, "live2d_file", "Modelo Live2D (.zip / .model3.json / .moc3)", OBS_PATH_FILE, "Todos los archivos (*);;Live2D (*.zip *.json *.moc3)", nullptr);
    obs_property_t *l2dFrame = obs_properties_add_list(props, "live2d_framing", "Encuadre (parte superior / cuerpo)", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    obs_property_list_add_string(l2dFrame, "Parte superior / Busto (sin pies · Recomendado)", "bust");
    obs_property_list_add_string(l2dFrame, "Primer plano (rostro y hombros)", "closeup");
    obs_property_list_add_string(l2dFrame, "Cuerpo completo (con pies)", "full");
    obs_properties_add_float_slider(props, "live2d_zoom", "Zoom / Agrandar avatar 2D", 0.5, 3.5, 0.05);
    obs_properties_add_float_slider(props, "live2d_offset_y", "Posición vertical 2D (arriba / abajo)", -1.0, 1.0, 0.02);

    obs_properties_add_text(props, "vrm_info",
        "Usa modelos .vrm (VRoid Studio → 'Export as VRM'). Opcionalmente carga una animación .vrma (gesto o pose continua).",
        OBS_TEXT_INFO);
    obs_properties_add_path(props, "vrm_file", "Modelo 3D VRM (.vrm)", OBS_PATH_FILE, "Todos los archivos (*);;Modelos VRM (*.vrm *.glb)", nullptr);
    obs_properties_add_path(props, "vrma_file", "Animación VRMA opcional (.vrma)", OBS_PATH_FILE, "Todos los archivos (*);;Animaciones VRMA (*.vrma *.glb)", nullptr);
    obs_properties_add_bool(props, "vrma_loop", "Repetir animación VRMA en bucle");
    obs_properties_add_float_slider(props, "vrm_cam_dist", "Distancia de cámara VRM", 0.4, 3.0, 0.05);
    obs_properties_add_float_slider(props, "vrm_cam_y", "Altura de cámara VRM", 0.5, 2.0, 0.02);

    obs_properties_add_text(props, "inochi_info",
        "Inochi2D es un formato 2D abierto (sin SDK propietario Cubism). Carga cualquier marioneta exportada como .inp o .inx.",
        OBS_TEXT_INFO);
    obs_properties_add_path(props, "inochi_file", "Marioneta Inochi2D (.inp / .inx)", OBS_PATH_FILE, "Todos los archivos (*);;Inochi2D (*.inp *.inx)", nullptr);

    obs_property_t *gest = obs_properties_add_list(props, "vt_gesture", "Gesto / Animación rápida", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    obs_property_list_add_string(gest, "Ninguno (seguimiento normal)", "");
    obs_property_list_add_string(gest, "Saludar con la mano", "wave");
    obs_property_list_add_string(gest, "Feliz / Sonrisa", "happy");
    obs_property_list_add_string(gest, "Victoria (✌️)", "peace");
    obs_property_list_add_string(gest, "Aplaudir", "clap");
    obs_property_list_add_string(gest, "Pensando", "think");
    obs_property_list_add_string(gest, "Baile suave", "dance");

    obs_properties_add_bool(props, "face_tracking", "Seguimiento facial 52 ARKit (MediaPipe GPU + VMC UDP :39539)");
    obs_properties_add_float_slider(props, "head_sensitivity", "Sensibilidad movimiento cabeza", 0.5, 2.5, 0.1);
    obs_properties_add_bool(props, "vt_invert_x", "Invertir movimiento izquierda / derecha");
    obs_properties_add_bool(props, "vt_invert_y", "Invertir movimiento arriba / abajo");
    obs_properties_add_bool(props, "hand_tracking", "Seguimiento de manos y dedos (MediaPipe Hands)");
    obs_properties_add_bool(props, "lip_sync", "Sincronización labial 5 vocales + Micrófono");
    obs_properties_add_float_slider(props, "voice_threshold", "Umbral de voz (sensibilidad)", 0.01, 0.30, 0.01);
    obs_properties_add_bool(props, "vts_api", "API VTube Studio (WebSocket :8001) y VMC completo");
    obs_properties_add_bool(props, "vt_zero_copy", "Captura GPU Zero-Copy (HardwareBuffer 60 FPS)");
    obs_property_t *qual = obs_properties_add_list(props, "vt_quality", "Calidad de renderizado (VRM / Live2D / Inochi2D)", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    obs_property_list_add_string(qual, "Alta (768p · Recomendada)", "high");
    obs_property_list_add_string(qual, "Ultra HD (1024p · Máxima nitidez)", "ultra");
    obs_property_list_add_string(qual, "Estándar (512p · Menor CPU)", "standard");
    obs_properties_add_int(props, "width", "Ancho", 256, 1280, 16);
    obs_properties_add_int(props, "height", "Alto", 256, 1280, 16);
    obs_properties_add_int(props, "fps", "FPS", 10, 60, 1);

    auto *holder = static_cast<std::shared_ptr<VtuberInstance> *>(data);
    auto *inst = (holder && *holder) ? holder->get() : nullptr;
    if (inst) {
        obs_data_t *tmp = obs_data_create();
        obs_data_set_string(tmp, "vt_mode", inst->mode.c_str());
        obs_data_set_bool(tmp, "veadomini_auto_emotion", inst->veado_auto_emotion);
        on_vt_mode_modified(props, mode, tmp);
        on_veado_auto_modified(props, nullptr, tmp);
        obs_data_release(tmp);
    } else {
        obs_data_t *tmp = obs_data_create();
        obs_data_set_string(tmp, "vt_mode", "png");
        obs_data_set_bool(tmp, "veadomini_auto_emotion", false);
        on_vt_mode_modified(props, mode, tmp);
        on_veado_auto_modified(props, nullptr, tmp);
        obs_data_release(tmp);
    }
    return props;
}

void build_config_json(VtuberInstance *inst, obs_data_t *s)
{
    const char *m = obs_data_get_string(s, "vt_mode");
    inst->veado_auto_emotion = obs_data_get_bool(s, "veadomini_auto_emotion");
    inst->mode = (m && *m) ? m : "png";
    inst->width = int(obs_data_get_int(s, "width"));
    inst->height = int(obs_data_get_int(s, "height"));
    inst->fps = int(obs_data_get_int(s, "fps"));
    if (inst->width < 128) inst->width = 768;
    if (inst->height < 128) inst->height = 768;
    if (inst->fps < 5) inst->fps = 30;

    std::ostringstream js;
    js << "{"
       << "\"mode\":\"" << json_escape(inst->mode.c_str()) << "\","
       << "\"veadominiFile\":\"" << json_escape(obs_data_get_string(s, "veadomini_file")) << "\","
       << "\"veadoState\":" << obs_data_get_int(s, "veadomini_state") << ","
       << "\"veadoAutoEmotion\":" << (obs_data_get_bool(s, "veadomini_auto_emotion") ? "true" : "false") << ","
       << "\"veadoHappyState\":" << obs_data_get_int(s, "veadomini_happy_state") << ","
       << "\"veadoSadState\":" << obs_data_get_int(s, "veadomini_sad_state") << ","
       << "\"veadoAngryState\":" << obs_data_get_int(s, "veadomini_angry_state") << ","
       << "\"veadoSurprisedState\":" << obs_data_get_int(s, "veadomini_surprised_state") << ","
       << "\"pngIdle\":\"" << json_escape(obs_data_get_string(s, "png_idle")) << "\","
       << "\"pngTalk\":\"" << json_escape(obs_data_get_string(s, "png_talk")) << "\","
       << "\"pngBlink\":\"" << json_escape(obs_data_get_string(s, "png_blink")) << "\","
       << "\"pngBlinkTalk\":\"" << json_escape(obs_data_get_string(s, "png_blink_talk")) << "\","
       << "\"bounce\":" << (obs_data_get_bool(s, "png_bounce") ? "true" : "false") << ","
       << "\"voiceThreshold\":" << obs_data_get_double(s, "voice_threshold") << ","
       << "\"vrmFile\":\"" << json_escape(obs_data_get_string(s, "vrm_file")) << "\","
       << "\"vrmaFile\":\"" << json_escape(obs_data_get_string(s, "vrma_file")) << "\","
       << "\"vrmaLoop\":" << (obs_data_get_bool(s, "vrma_loop") ? "true" : "false") << ","
       << "\"camDist\":" << obs_data_get_double(s, "vrm_cam_dist") << ","
       << "\"camY\":" << obs_data_get_double(s, "vrm_cam_y") << ","
       << "\"live2dFile\":\"" << json_escape(obs_data_get_string(s, "live2d_file")) << "\","
       << "\"live2dFraming\":\"" << json_escape(obs_data_get_string(s, "live2d_framing")) << "\","
       << "\"live2dZoom\":" << obs_data_get_double(s, "live2d_zoom") << ","
       << "\"live2dOffsetY\":" << obs_data_get_double(s, "live2d_offset_y") << ","
       << "\"inochiFile\":\"" << json_escape(obs_data_get_string(s, "inochi_file")) << "\","
       << "\"gesture\":\"" << json_escape(obs_data_get_string(s, "vt_gesture")) << "\","
       << "\"faceTracking\":" << (obs_data_get_bool(s, "face_tracking") ? "true" : "false") << ","
       << "\"headSensitivity\":" << obs_data_get_double(s, "head_sensitivity") << ","
       << "\"invertX\":" << (obs_data_get_bool(s, "vt_invert_x") ? "true" : "false") << ","
       << "\"invertY\":" << (obs_data_get_bool(s, "vt_invert_y") ? "true" : "false") << ","
       << "\"handTracking\":" << (obs_data_get_bool(s, "hand_tracking") ? "true" : "false") << ","
       << "\"vtsApi\":" << (obs_data_get_bool(s, "vts_api") ? "true" : "false") << ","
       << "\"zeroCopy\":" << (obs_data_get_bool(s, "vt_zero_copy") ? "true" : "false") << ","
       << "\"quality\":\"" << json_escape(obs_data_get_string(s, "vt_quality")) << "\","
       << "\"lipSync\":" << (obs_data_get_bool(s, "lip_sync") ? "true" : "false")
       << "}";
    inst->config_json = js.str();
}

void vtuber_update(void *data, obs_data_t *s)
{
    auto *holder = static_cast<std::shared_ptr<VtuberInstance> *>(data);
    if (!holder || !*holder) return;
    VtuberInstance *inst = holder->get();
    build_config_json(inst, s);
    if (inst->mode == "png") inst->hw.clear_pending();
    push_vtuber_config(inst);
}

void *vtuber_create(obs_data_t *s, obs_source_t *source)
{
    auto inst = std::make_shared<VtuberInstance>();
    inst->id = ++g_vt_seq;
    inst->source = source;
    build_config_json(inst.get(), s);
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        g_vt_instances[inst->id] = inst;
        g_vt_active_count.store(int(g_vt_instances.size()), std::memory_order_release);
    }
    push_vtuber_config(inst.get());
    return new std::shared_ptr<VtuberInstance>(std::move(inst));
}

void vtuber_destroy(void *data)
{
    auto *holder = static_cast<std::shared_ptr<VtuberInstance> *>(data);
    if (!holder) return;
    std::shared_ptr<VtuberInstance> inst = std::move(*holder);
    delete holder;
    if (!inst) return;
    int64_t id = inst->id;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        g_vt_instances.erase(id);
        g_vt_active_count.store(int(g_vt_instances.size()), std::memory_order_release);
    }
    call_vtuber_id(g_mid_vt_destroy, id);
    inst->hw.clear_pending();
    if (obs_initialized()) {
        obs_enter_graphics();
        inst->hw.destroy_gl_resources();
        obs_leave_graphics();
    }
}

void vtuber_set_visible_jni(void *data, jboolean visible)
{
    auto *holder = static_cast<std::shared_ptr<VtuberInstance> *>(data);
    if (!holder || !*holder) return;
    int64_t id = (*holder)->id;
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        jvm = g_vt_jvm; cls = g_vt_host_class; mid = g_mid_vt_visible;
    }
    if (!jvm || !cls || !mid) return;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        detach = true;
    } else if (st != JNI_OK) {
        return;
    }
    if (env) {
        env->CallStaticVoidMethod(cls, mid, jlong(id), visible);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (detach) jvm->DetachCurrentThread();
}

void vtuber_video_render(void *data, gs_effect_t *effect)
{
    auto *holder = static_cast<std::shared_ptr<VtuberInstance> *>(data);
    if (!holder || !*holder) return;
    auto &inst = *holder;
    if (inst->hw.active.load(std::memory_order_acquire)) {
        AHardwareBuffer *ahb = nullptr;
        int w = 0, h = 0;
        bool sbs = true;
        {
            std::lock_guard<std::mutex> lock(inst->hw.mutex);
            ahb = inst->hw.pending_ahb;
            inst->hw.pending_ahb = nullptr;
            w = inst->hw.pending_w;
            h = inst->hw.pending_h;
            sbs = inst->hw.pending_sbs;
        }
        if (ahb) {
            blit_vtuber_ahb(inst->hw, ahb, w, h, sbs, inst->id);
            AHardwareBuffer_release(ahb);
        }
        if (inst->hw.obs_tex && inst->hw.tex_w > 0 && inst->hw.tex_h > 0) {
            gs_effect_t *eff = effect ? effect : obs_get_base_effect(OBS_EFFECT_DEFAULT);
            const bool linear_srgb = gs_get_linear_srgb();
            const bool prev_srgb = gs_framebuffer_srgb_enabled();
            gs_enable_framebuffer_srgb(linear_srgb);
            gs_eparam_t *param = gs_effect_get_param_by_name(eff, "image");
            if (linear_srgb)
                gs_effect_set_texture_srgb(param, inst->hw.obs_tex);
            else
                gs_effect_set_texture(param, inst->hw.obs_tex);
            while (gs_effect_loop(eff, "Draw")) {
                gs_draw_sprite(inst->hw.obs_tex, 0, inst->hw.tex_w, inst->hw.tex_h);
            }
            gs_enable_framebuffer_srgb(prev_srgb);
            return;
        }
    }
    inst->frame.render();
}
} // namespace

bool android_vtuber_has_active()
{
    return g_vt_active_count.load(std::memory_order_acquire) > 0;
}

void android_vtuber_register()
{
    std::lock_guard<std::mutex> lock(g_vt_mutex);
    if (g_vt_registered) return;
    obs_source_info info{};
    info.id = "vtuber_source";
    info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_VIDEO | OBS_SOURCE_CUSTOM_DRAW | OBS_SOURCE_SRGB | OBS_SOURCE_DO_NOT_DUPLICATE;
    info.get_name = vtuber_get_name;
    info.create = vtuber_create;
    info.destroy = vtuber_destroy;
    info.show = [](void *data) { vtuber_set_visible_jni(data, JNI_TRUE); };
    info.hide = [](void *data) { vtuber_set_visible_jni(data, JNI_FALSE); };
    info.update = vtuber_update;
    info.get_defaults = vtuber_get_defaults;
    info.get_properties = vtuber_get_properties;
    info.get_width = [](void *data) -> uint32_t {
        auto *holder = static_cast<std::shared_ptr<VtuberInstance> *>(data);
        if (!holder || !*holder) return 768;
        auto &inst = *holder;
        if (inst->hw.active.load(std::memory_order_acquire)) {
            uint32_t rw = inst->hw.reported_w.load(std::memory_order_relaxed);
            if (rw) return rw;
        }
        return inst->frame.width() ? inst->frame.width() : uint32_t(inst->width);
    };
    info.get_height = [](void *data) -> uint32_t {
        auto *holder = static_cast<std::shared_ptr<VtuberInstance> *>(data);
        if (!holder || !*holder) return 768;
        auto &inst = *holder;
        if (inst->hw.active.load(std::memory_order_acquire)) {
            uint32_t rh = inst->hw.reported_h.load(std::memory_order_relaxed);
            if (rh) return rh;
        }
        return inst->frame.height() ? inst->frame.height() : uint32_t(inst->height);
    };
    info.video_render = vtuber_video_render;
    info.icon_type = OBS_ICON_TYPE_CUSTOM;
    obs_register_source(&info);
    g_vt_registered = true;
}

void android_vtuber_shutdown()
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    std::vector<std::shared_ptr<VtuberInstance>> remaining;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        jvm = g_vt_jvm; cls = g_vt_host_class; mid = g_mid_vt_destroy_all;
        for (auto &kv : g_vt_instances) remaining.push_back(kv.second);
        g_vt_instances.clear();
        g_vt_active_count.store(0, std::memory_order_release);
    }
    for (auto &inst : remaining) {
        if (inst) inst->hw.clear_pending();
    }
    if (obs_initialized()) {
        obs_enter_graphics();
        for (auto &inst : remaining) {
            if (inst) inst->hw.destroy_gl_resources();
        }
        if (g_vt_oes.prog) { glDeleteProgram(g_vt_oes.prog); g_vt_oes.prog = 0; }
        if (g_vt_oes.vao) { glDeleteVertexArrays(1, &g_vt_oes.vao); g_vt_oes.vao = 0; }
        g_vt_oes.initialized = false;
        g_vt_oes.supported = false;
        obs_leave_graphics();
    }
    if (!jvm || !cls || !mid) return;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return; detach = true; }
    else if (st != JNI_OK) return;
    env->CallStaticVoidMethod(cls, mid);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) jvm->DetachCurrentThread();
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_registerVtuberHost(JNIEnv *env, jclass, jclass hostClass)
{
    std::lock_guard<std::mutex> lock(g_vt_mutex);
    if (g_vt_host_class) return;
    env->GetJavaVM(&g_vt_jvm);
    g_vt_host_class = static_cast<jclass>(env->NewGlobalRef(hostClass));
    if (!g_vt_host_class) return;
    g_mid_vt_update = env->GetStaticMethodID(g_vt_host_class, "vtuberCreateOrUpdate", "(JLjava/lang/String;III)V");
    g_mid_vt_visible = env->GetStaticMethodID(g_vt_host_class, "vtuberSetVisible", "(JZ)V");
    g_mid_vt_destroy = env->GetStaticMethodID(g_vt_host_class, "vtuberDestroy", "(J)V");
    g_mid_vt_destroy_all = env->GetStaticMethodID(g_vt_host_class, "vtuberDestroyAll", "()V");
    g_mid_vt_pick_cubism = env->GetStaticMethodID(g_vt_host_class, "pickLive2dCubismCore", "()V");
    g_mid_vt_gesture = env->GetStaticMethodID(g_vt_host_class, "vtuberTriggerGesture", "(Ljava/lang/String;I)V");
    g_mid_vt_expr = env->GetStaticMethodID(g_vt_host_class, "vtuberTriggerLive2dExpr", "(Ljava/lang/String;Z)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
}

extern "C" __attribute__((visibility("default"))) void obs_android_vtuber_trigger_gesture(const char *gesture_id, int duration_ms)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        jvm = g_vt_jvm; cls = g_vt_host_class; mid = g_mid_vt_gesture;
    }
    if (!jvm || !cls || !mid) return;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return; detach = true; }
    else if (st != JNI_OK) return;
    jstring jGest = env->NewStringUTF(gesture_id ? gesture_id : "reset");
    env->CallStaticVoidMethod(cls, mid, jGest, jint(duration_ms));
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jGest);
    if (detach) jvm->DetachCurrentThread();
}

extern "C" __attribute__((visibility("default"))) void obs_android_vtuber_trigger_live2d_expr(const char *expr_key, bool active)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        jvm = g_vt_jvm; cls = g_vt_host_class; mid = g_mid_vt_expr;
    }
    if (!jvm || !cls || !mid) return;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return; detach = true; }
    else if (st != JNI_OK) return;
    jstring jKey = env->NewStringUTF(expr_key ? expr_key : "reset");
    env->CallStaticVoidMethod(cls, mid, jKey, active ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jKey);
    if (detach) jvm->DetachCurrentThread();
}

extern "C" __attribute__((visibility("default"))) void obs_android_vtuber_set_veado_state(int state_index)
{
    std::vector<obs_source_t *> sources;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        for (auto &kv : g_vt_instances) {
            if (kv.second && kv.second->source) {
                obs_source_t *ref = obs_source_get_ref(kv.second->source);
                if (ref) sources.push_back(ref);
            }
        }
    }
    for (obs_source_t *src : sources) {
        obs_data_t *s = obs_source_get_settings(src);
        if (s) {
            obs_data_set_int(s, "veadomini_state", state_index < 0 ? 0 : (state_index > 15 ? 15 : state_index));
            obs_source_update(src, s);
            obs_data_release(s);
        }
        obs_source_release(src);
    }
}

extern "C" __attribute__((visibility("default"))) void obs_android_vtuber_set_framing(const char *framing)
{
    if (!framing || !*framing) return;
    std::vector<obs_source_t *> sources;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        for (auto &kv : g_vt_instances) {
            if (kv.second && kv.second->source) {
                obs_source_t *ref = obs_source_get_ref(kv.second->source);
                if (ref) sources.push_back(ref);
            }
        }
    }
    for (obs_source_t *src : sources) {
        obs_data_t *s = obs_source_get_settings(src);
        if (s) {
            obs_data_set_string(s, "live2d_framing", framing);
            if (std::strcmp(framing, "closeup") == 0) {
                obs_data_set_double(s, "vrm_cam_dist", 0.72);
                obs_data_set_double(s, "vrm_cam_y", 1.46);
            } else if (std::strcmp(framing, "full") == 0) {
                obs_data_set_double(s, "vrm_cam_dist", 2.35);
                obs_data_set_double(s, "vrm_cam_y", 0.96);
            } else {
                obs_data_set_double(s, "vrm_cam_dist", 1.15);
                obs_data_set_double(s, "vrm_cam_y", 1.38);
            }
            obs_source_update(src, s);
            obs_data_release(s);
        }
        obs_source_release(src);
    }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_org_obsandroid_port_NativeBridge_getMicLevel(JNIEnv *, jclass)
{
    AndroidCallbackScope callback;
    if (!callback) return 0;
    return jfloat(android_sources_get_mic_level());
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_pushVtuberFrame(
    JNIEnv *env, jclass, jlong id, jobject rgbaBuf, jint width, jint height, jlong timestampNs)
{
    AndroidCallbackScope callback;
    if (!callback) return ;
    if (!rgbaBuf || width <= 0 || height <= 0) return;
    auto *ptr = static_cast<uint8_t *>(env->GetDirectBufferAddress(rgbaBuf));
    jlong cap = env->GetDirectBufferCapacity(rgbaBuf);
    if (!ptr || cap < jlong(width) * jlong(height) * 4) return;

    std::shared_ptr<VtuberInstance> inst;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        auto it = g_vt_instances.find(int64_t(id));
        if (it != g_vt_instances.end()) inst = it->second;
    }
    if (inst) {
        inst->hw.active.store(false, std::memory_order_release);
        inst->frame.submit(ptr, uint32_t(width), uint32_t(height), uint32_t(width) * 4, true);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_obsandroid_port_NativeBridge_pushVtuberBitmap(JNIEnv *env, jclass, jlong id, jobject bitmap)
{
    AndroidCallbackScope callback;
    if (!callback) return false;
    std::shared_ptr<VtuberInstance> inst;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        auto it = g_vt_instances.find(int64_t(id));
        if (it != g_vt_instances.end()) inst = it->second;
    }
    if (!inst) return false;
    inst->hw.active.store(false, std::memory_order_release);
    return inst->frame.submitBitmap(env, bitmap);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_obsandroid_port_NativeBridge_pushVtuberArray(JNIEnv *env, jclass, jlong id, jbyteArray bytes,
    jint offset, jint width, jint height, jboolean flip)
{
    AndroidCallbackScope callback;
    if (!callback || !bytes || offset < 0 || width < 1 || height < 1 || width > 1280 || height > 1280) return false;
    if (int64_t(width) * height * 4 + offset != env->GetArrayLength(bytes)) return false;
    std::shared_ptr<VtuberInstance> inst;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        auto it = g_vt_instances.find(int64_t(id));
        if (it != g_vt_instances.end()) inst = it->second;
    }
    if (!inst) return false;
    jbyte *raw = env->GetByteArrayElements(bytes, nullptr);
    if (!raw) return false;
    inst->hw.active.store(false, std::memory_order_release);
    const bool accepted = inst->frame.submit(reinterpret_cast<uint8_t *>(raw) + offset,
        uint32_t(width), uint32_t(height), uint32_t(width) * 4, true, flip);
    env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
    return accepted;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_obsandroid_port_NativeBridge_pushVtuberHardwareBuffer(
    JNIEnv *env, jclass, jlong id, jobject hwBuf, jint width, jint height, jboolean sideBySideAlpha)
{
    AndroidCallbackScope callback;
    if (!callback || !hwBuf || width <= 0 || height <= 0 || width > 2048 || height > 2048) return JNI_FALSE;
    std::shared_ptr<VtuberInstance> inst;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        auto it = g_vt_instances.find(int64_t(id));
        if (it != g_vt_instances.end()) inst = it->second;
    }
    if (!inst) return JNI_FALSE;
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, hwBuf);
    if (!ahb) return JNI_FALSE;
    AHardwareBuffer_acquire(ahb);
    AHardwareBuffer *old = nullptr;
    {
        std::lock_guard<std::mutex> lock(inst->hw.mutex);
        old = inst->hw.pending_ahb;
        inst->hw.pending_ahb = ahb;
        inst->hw.pending_w = int(width);
        inst->hw.pending_h = int(height);
        inst->hw.pending_sbs = (sideBySideAlpha == JNI_TRUE);
    }
    inst->hw.reported_w.store(uint32_t(width), std::memory_order_relaxed);
    inst->hw.reported_h.store(uint32_t(height), std::memory_order_relaxed);
    inst->hw.active.store(true, std::memory_order_release);
    if (old) AHardwareBuffer_release(old);
    return JNI_TRUE;
}
