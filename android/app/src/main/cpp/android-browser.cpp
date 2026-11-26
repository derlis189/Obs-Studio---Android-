#include "android-session-gate.h"
#include "android-rgba-frame.h"
// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-browser.h"
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
#include <string>
#include <unordered_map>

namespace {
using PFN_eglGetNativeClientBufferANDROID = EGLClientBuffer(EGLAPIENTRYP)(const struct AHardwareBuffer *buffer);
using PFN_eglCreateImageKHR = EGLImageKHR(EGLAPIENTRYP)(EGLDisplay dpy, EGLContext ctx, EGLenum target,
                                                        EGLClientBuffer buffer, const EGLint *attrib_list);
using PFN_eglDestroyImageKHR = EGLBoolean(EGLAPIENTRYP)(EGLDisplay dpy, EGLImageKHR image);
using PFN_glEGLImageTargetTexture2DOES = void (*)(GLenum target, void *image);

struct BrowserOesBlitContext {
    bool initialized{false};
    bool supported{false};
    PFN_eglGetNativeClientBufferANDROID get_native_client_buffer{};
    PFN_eglCreateImageKHR create_image{};
    PFN_eglDestroyImageKHR destroy_image{};
    PFN_glEGLImageTargetTexture2DOES image_target_tex2d{};
    GLuint prog{0};
    GLuint vao{0};
    GLint loc_tex{-1};
};

BrowserOesBlitContext g_browser_oes;

constexpr const char *kBrowserOesVS = R"(#version 300 es
out vec2 v_uv;
void main() {
    vec2 pos = vec2((gl_VertexID == 1) ? 3.0 : -1.0,
                    (gl_VertexID == 2) ? 3.0 : -1.0);
    gl_Position = vec4(pos, 0.0, 1.0);
    v_uv = pos * 0.5 + 0.5;
}
)";

constexpr const char *kBrowserOesFS = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 v_uv;
uniform samplerExternalOES u_tex;
out vec4 fragColor;
void main() {
    fragColor = vec4(texture(u_tex, v_uv).rgb, 1.0);
}
)";

GLuint compile_browser_oes_shader(GLenum type, const char *src)
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
        blog(LOG_ERROR, "[android-browser] GLES OES shader error: %s", log);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

bool ensure_browser_oes_ready()
{
    if (g_browser_oes.initialized) return g_browser_oes.supported;
    g_browser_oes.initialized = true;
    g_browser_oes.get_native_client_buffer =
        reinterpret_cast<PFN_eglGetNativeClientBufferANDROID>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    g_browser_oes.create_image = reinterpret_cast<PFN_eglCreateImageKHR>(eglGetProcAddress("eglCreateImageKHR"));
    g_browser_oes.destroy_image = reinterpret_cast<PFN_eglDestroyImageKHR>(eglGetProcAddress("eglDestroyImageKHR"));
    g_browser_oes.image_target_tex2d =
        reinterpret_cast<PFN_glEGLImageTargetTexture2DOES>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!g_browser_oes.get_native_client_buffer || !g_browser_oes.create_image ||
        !g_browser_oes.destroy_image || !g_browser_oes.image_target_tex2d) {
        blog(LOG_WARNING, "[android-browser] EGLImage/OES extensions unavailable; using PixelCopy fallback");
        return false;
    }
    GLuint vs = compile_browser_oes_shader(GL_VERTEX_SHADER, kBrowserOesVS);
    GLuint fs = compile_browser_oes_shader(GL_FRAGMENT_SHADER, kBrowserOesFS);
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
        blog(LOG_ERROR, "[android-browser] GLES OES link error: %s", log);
        glDeleteProgram(prog);
        return false;
    }
    g_browser_oes.prog = prog;
    g_browser_oes.loc_tex = glGetUniformLocation(prog, "u_tex");
    glGenVertexArrays(1, &g_browser_oes.vao);
    g_browser_oes.supported = true;
    blog(LOG_INFO, "[android-browser] Zero-copy AHardwareBuffer -> EGLImage -> OES enabled for opaque Browser/WebRTC");
    return true;
}

struct BrowserHwState {
    std::mutex mutex;
    AHardwareBuffer *pending_ahb{};
    int pending_w{0}, pending_h{0};
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
            if (kv.second != EGL_NO_IMAGE_KHR && cached_dpy != EGL_NO_DISPLAY && g_browser_oes.destroy_image) {
                g_browser_oes.destroy_image(cached_dpy, kv.second);
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

void blit_browser_ahb(BrowserHwState &hw, AHardwareBuffer *ahb, int w, int h, int64_t inst_id)
{
    if (!ahb || w <= 0 || h <= 0) return;
    if (!ensure_browser_oes_ready()) return;
    EGLDisplay dpy = eglGetCurrentDisplay();
    if (dpy == EGL_NO_DISPLAY) return;

    const bool size_changed = (!hw.obs_tex || hw.tex_w != uint32_t(w) || hw.tex_h != uint32_t(h));
    if (size_changed || hw.cached_dpy != dpy || hw.img_cache.size() > 8) {
        hw.clear_img_cache();
        hw.cached_dpy = dpy;
    }

    EGLImageKHR img = EGL_NO_IMAGE_KHR;
    auto it = hw.img_cache.find(ahb);
    if (it != hw.img_cache.end()) {
        img = it->second;
    } else {
        EGLClientBuffer cbuf = g_browser_oes.get_native_client_buffer(ahb);
        if (!cbuf) return;
        const EGLint img_attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        img = g_browser_oes.create_image(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cbuf, img_attrs);
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
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    g_browser_oes.image_target_tex2d(GL_TEXTURE_EXTERNAL_OES, img);

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
        glUseProgram(g_browser_oes.prog);
        glUniform1i(g_browser_oes.loc_tex, 0);
        glBindVertexArray(g_browser_oes.vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        if (!hw.logged_active) {
            hw.logged_active = true;
            blog(LOG_INFO, "[android-browser] Zero-copy HardwareBuffer frame active id=%lld (%dx%d)",
                 static_cast<long long>(inst_id), w, h);
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

std::mutex g_browser_mutex;
JavaVM *g_browser_jvm = nullptr;
jclass g_browser_host_class = nullptr;
jmethodID g_mid_create_update = nullptr;
jmethodID g_mid_set_visible = nullptr;
jmethodID g_mid_reload = nullptr;
jmethodID g_mid_interact = nullptr;
jmethodID g_mid_destroy = nullptr;
jmethodID g_mid_destroy_all = nullptr;

std::atomic<int64_t> g_browser_id_seq{0};
bool g_browser_registered = false;

struct BrowserSourceInstance {
    AndroidRgbaFrame frame;
    BrowserHwState hw;
    int64_t id{};
    obs_source_t *source{};
    bool is_local_file{false};
    std::string url;
    std::string local_file;
    std::string css;
    std::atomic<int> width{1280};
    std::atomic<int> height{720};
    int fps{30};
    bool transparent{true};
    std::atomic<bool> capture_audio{false};
    bool shutdown_when_hidden{false};
    std::atomic<bool> showing{true};
};

std::unordered_map<int64_t, std::shared_ptr<BrowserSourceInstance>> g_instances;

struct JniAttachGuard {
    JavaVM *jvm{};
    JNIEnv *env{};
    bool detach{false};

    explicit JniAttachGuard(JavaVM *vm) : jvm(vm)
    {
        if (!jvm) return;
        int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (st == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&env, nullptr) == 0) {
                detach = true;
            } else {
                env = nullptr;
            }
        } else if (st != JNI_OK) {
            env = nullptr;
        }
    }

    ~JniAttachGuard()
    {
        if (detach && jvm) jvm->DetachCurrentThread();
    }
};

std::string build_effective_url(const BrowserSourceInstance *inst)
{
    if (inst->is_local_file) {
        if (inst->local_file.empty()) return "about:blank";
        if (inst->local_file.rfind("file://", 0) == 0 ||
            inst->local_file.rfind("http://", 0) == 0 ||
            inst->local_file.rfind("https://", 0) == 0) {
            return inst->local_file;
        }
        return std::string("file://") + inst->local_file;
    }
    if (inst->url.empty()) return "about:blank";
    if (inst->url.find("://") == std::string::npos && inst->url.rfind("about:", 0) != 0) {
        return std::string("https://") + inst->url;
    }
    return inst->url;
}

void push_config_to_java(const BrowserSourceInstance *inst)
{
    JavaVM *jvm = nullptr;
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_browser_mutex);
        jvm = g_browser_jvm;
        cls = g_browser_host_class;
        mid = g_mid_create_update;
    }
    if (!jvm || !cls || !mid) return;
    JniAttachGuard guard(jvm);
    if (!guard.env) return;

    std::string effective = build_effective_url(inst);
    bool visible = inst->showing;

    jstring jUrl = guard.env->NewStringUTF(effective.c_str());
    jstring jCss = guard.env->NewStringUTF(inst->css.c_str());
    guard.env->CallStaticVoidMethod(
        cls, mid,
        jlong(inst->id), jUrl,
        jint(inst->width), jint(inst->height), jint(inst->fps),
        jboolean(inst->transparent), jCss, jboolean(visible), jboolean(inst->shutdown_when_hidden), jboolean(inst->capture_audio));
    if (guard.env->ExceptionCheck()) guard.env->ExceptionClear();
    guard.env->DeleteLocalRef(jUrl);
    guard.env->DeleteLocalRef(jCss);
}

void call_java_id_bool(jmethodID mid, int64_t id, bool flag)
{
    JavaVM *jvm = nullptr;
    jclass cls = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_browser_mutex);
        jvm = g_browser_jvm;
        cls = g_browser_host_class;
    }
    if (!jvm || !cls || !mid) return;
    JniAttachGuard guard(jvm);
    if (!guard.env) return;
    guard.env->CallStaticVoidMethod(cls, mid, jlong(id), jboolean(flag));
    if (guard.env->ExceptionCheck()) guard.env->ExceptionClear();
}

void call_java_id_only(jmethodID mid, int64_t id)
{
    JavaVM *jvm = nullptr;
    jclass cls = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_browser_mutex);
        jvm = g_browser_jvm;
        cls = g_browser_host_class;
    }
    if (!jvm || !cls || !mid) return;
    JniAttachGuard guard(jvm);
    if (!guard.env) return;
    guard.env->CallStaticVoidMethod(cls, mid, jlong(id));
    if (guard.env->ExceptionCheck()) guard.env->ExceptionClear();
}

const char *browser_get_name(void *)
{
    return "Navegador";
}

void browser_get_defaults(obs_data_t *settings)
{
    obs_data_set_default_bool(settings, "is_local_file", false);
    obs_data_set_default_string(settings, "local_file", "");
    obs_data_set_default_string(settings, "url", "https://obsproject.com/browser-source");
    obs_data_set_default_int(settings, "width", 1280);
    obs_data_set_default_int(settings, "height", 720);
    obs_data_set_default_int(settings, "fps", 30);
    obs_data_set_default_bool(settings, "transparent", true);
    obs_data_set_default_bool(settings, "capture_audio", false);
    obs_data_set_default_string(
        settings, "css",
        "body { background-color: rgba(0, 0, 0, 0); margin: 0px auto; overflow: hidden; }");
    obs_data_set_default_bool(settings, "shutdown", false);
}

bool on_is_local_file_modified(obs_properties_t *props, obs_property_t *, obs_data_t *settings)
{
    bool is_local = obs_data_get_bool(settings, "is_local_file");
    obs_property_t *local_prop = obs_properties_get(props, "local_file");
    obs_property_t *url_prop = obs_properties_get(props, "url");
    if (local_prop) obs_property_set_visible(local_prop, is_local);
    if (url_prop) obs_property_set_visible(url_prop, !is_local);
    return true;
}

bool on_interact_clicked(obs_properties_t *, obs_property_t *, void *data)
{
    auto *holder = static_cast<std::shared_ptr<BrowserSourceInstance> *>(data);
    if (!holder || !*holder) return false;
    call_java_id_only(g_mid_interact, (*holder)->id);
    return false;
}

bool on_refresh_clicked(obs_properties_t *, obs_property_t *, void *data)
{
    auto *holder = static_cast<std::shared_ptr<BrowserSourceInstance> *>(data);
    if (!holder || !*holder) return false;
    call_java_id_bool(g_mid_reload, (*holder)->id, true);
    return false;
}

obs_properties_t *browser_get_properties(void *data)
{
    obs_properties_t *props = obs_properties_create();
    obs_properties_add_text(props, "browser_help",
        "Video HTML5/WebRTC y WebGL se capturan por GPU. Para VDO.Ninja usa el enlace de visualización (?view=...). "
        "El emisor debe mantener el envío activo.", OBS_TEXT_INFO);
    obs_property_t *local_chk = obs_properties_add_bool(props, "is_local_file", "Archivo local");
    obs_property_set_modified_callback(local_chk, on_is_local_file_modified);

    obs_properties_add_path(
        props, "local_file", "Archivo local", OBS_PATH_FILE,
        "Archivos HTML (*.html *.htm);;Todos los archivos (*.*)", nullptr);
    obs_properties_add_text(props, "url", "URL", OBS_TEXT_DEFAULT);
    obs_properties_add_int(props, "width", "Ancho", 64, 3840, 1);
    obs_properties_add_int(props, "height", "Alto", 64, 2160, 1);
    obs_properties_add_int(props, "fps", "FPS", 1, 60, 1);
    obs_properties_add_bool(props, "transparent", "Fondo transparente (RGBA)");
    obs_properties_add_bool(props, "capture_audio", "Capturar audio de esta página (Web Audio)");
    obs_properties_add_text(props, "audio_help", "Audio HTML5 y WebRTC accesible a la página. Los medios de otro origen necesitan permiso CORS. Evita duplicarlo con Audio interno.", OBS_TEXT_INFO);
    obs_properties_add_text(props, "css", "CSS personalizado", OBS_TEXT_MULTILINE);
    obs_properties_add_bool(props, "shutdown", "Pausar también la página cuando no esté visible");
    obs_properties_add_text(props, "capture_savings",
        "Las capturas se pausan automáticamente al ocultar la fuente. Si no pausas también la página, "
        "se conserva la sesión para volver más rápido. El límite térmico puede reducir los FPS de esta fuente.", OBS_TEXT_INFO);
    obs_properties_add_button(props, "interact", "Interactuar con la página", on_interact_clicked);
    obs_properties_add_button(
        props, "refreshnocache", "Actualizar caché de la página actual", on_refresh_clicked);

    auto *inst = static_cast<BrowserSourceInstance *>(data);
    if (inst) {
        obs_property_set_visible(obs_properties_get(props, "local_file"), inst->is_local_file);
        obs_property_set_visible(obs_properties_get(props, "url"), !inst->is_local_file);
    }
    return props;
}

void apply_settings(BrowserSourceInstance *inst, obs_data_t *settings)
{
    inst->is_local_file = obs_data_get_bool(settings, "is_local_file");
    const char *lf = obs_data_get_string(settings, "local_file");
    const char *u = obs_data_get_string(settings, "url");
    const char *c = obs_data_get_string(settings, "css");
    inst->local_file = lf ? lf : "";
    inst->url = u ? u : "";
    inst->css = c ? c : "";
    inst->width = static_cast<int>(obs_data_get_int(settings, "width"));
    inst->height = static_cast<int>(obs_data_get_int(settings, "height"));
    inst->fps = static_cast<int>(obs_data_get_int(settings, "fps"));
    if (inst->width < 64) inst->width = 1280;
    if (inst->height < 64) inst->height = 720;
    if (inst->fps < 1) inst->fps = 30;
    inst->transparent = obs_data_get_bool(settings, "transparent");
    inst->capture_audio = obs_data_get_bool(settings, "capture_audio");
    inst->shutdown_when_hidden = obs_data_get_bool(settings, "shutdown");
}

void browser_update(void *data, obs_data_t *settings)
{
    auto *inst = static_cast<BrowserSourceInstance *>(data);
    if (!inst) return;
    apply_settings(inst, settings);
    push_config_to_java(inst);
}

void *browser_create(obs_data_t *settings, obs_source_t *source)
{
    auto inst = std::make_shared<BrowserSourceInstance>();
    inst->id = ++g_browser_id_seq;
    inst->source = source;
    inst->showing = obs_source_showing(source);
    apply_settings(inst.get(), settings);
    {
        std::lock_guard<std::mutex> lock(g_browser_mutex);
        g_instances[inst->id] = inst;
    }
    push_config_to_java(inst.get());
    return new std::shared_ptr<BrowserSourceInstance>(std::move(inst));
}

void browser_destroy(void *data)
{
    auto *holder = static_cast<std::shared_ptr<BrowserSourceInstance> *>(data);
    if (!holder || !*holder) {
        delete holder;
        return;
    }
    int64_t id = (*holder)->id;
    {
        std::lock_guard<std::mutex> lock(g_browser_mutex);
        g_instances.erase(id);
    }
    call_java_id_only(g_mid_destroy, id);
    (*holder)->hw.clear_pending();
    if (obs_initialized()) {
        obs_enter_graphics();
        (*holder)->hw.destroy_gl_resources();
        obs_leave_graphics();
    }
    delete holder;
}

void browser_show(void *data)
{
    auto *holder = static_cast<std::shared_ptr<BrowserSourceInstance> *>(data);
    if (!holder || !*holder) return;
    (*holder)->showing = true;
    call_java_id_bool(g_mid_set_visible, (*holder)->id, true);
}

void browser_hide(void *data)
{
    auto *holder = static_cast<std::shared_ptr<BrowserSourceInstance> *>(data);
    if (!holder || !*holder) return;
    (*holder)->showing = false;
    call_java_id_bool(g_mid_set_visible, (*holder)->id, false);
}
} // namespace

void android_browser_register()
{
    std::lock_guard<std::mutex> lock(g_browser_mutex);
    if (g_browser_registered) return;

    obs_source_info info{};
    info.id = "browser_source";
    info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_AUDIO | OBS_SOURCE_VIDEO | OBS_SOURCE_CUSTOM_DRAW | OBS_SOURCE_SRGB | OBS_SOURCE_DO_NOT_DUPLICATE;
    info.get_name = browser_get_name;
    info.create = browser_create;
    info.destroy = browser_destroy;
    info.update = [](void *data, obs_data_t *settings) {
        auto *holder = static_cast<std::shared_ptr<BrowserSourceInstance> *>(data);
        if (holder && *holder) browser_update(holder->get(), settings);
    };
    info.get_defaults = browser_get_defaults;
    info.get_properties = [](void *data) {
        auto *holder = static_cast<std::shared_ptr<BrowserSourceInstance> *>(data);
        return browser_get_properties((holder && *holder) ? holder->get() : nullptr);
    };
    info.show = browser_show;
    info.hide = browser_hide;
    info.get_width = [](void *data) -> uint32_t {
        auto *holder = static_cast<std::shared_ptr<BrowserSourceInstance> *>(data);
        if (!holder || !*holder) return 1280;
        auto *inst = holder->get();
        if (inst->hw.active.load(std::memory_order_relaxed)) {
            uint32_t hwW = inst->hw.reported_w.load(std::memory_order_relaxed);
            if (hwW > 0) return hwW;
        }
        return inst->frame.width() ? inst->frame.width() : uint32_t(inst->width);
    };
    info.get_height = [](void *data) -> uint32_t {
        auto *holder = static_cast<std::shared_ptr<BrowserSourceInstance> *>(data);
        if (!holder || !*holder) return 720;
        auto *inst = holder->get();
        if (inst->hw.active.load(std::memory_order_relaxed)) {
            uint32_t hwH = inst->hw.reported_h.load(std::memory_order_relaxed);
            if (hwH > 0) return hwH;
        }
        return inst->frame.height() ? inst->frame.height() : uint32_t(inst->height);
    };
    info.video_render = [](void *data, gs_effect_t *effect) {
        auto *holder = static_cast<std::shared_ptr<BrowserSourceInstance> *>(data);
        if (!holder || !*holder) return;
        auto *inst = holder->get();
        if (inst->hw.active.load(std::memory_order_acquire)) {
            AHardwareBuffer *ahb = nullptr;
            int w = 0, h = 0;
            {
                std::lock_guard<std::mutex> lock(inst->hw.mutex);
                if (inst->hw.pending_ahb) {
                    ahb = inst->hw.pending_ahb;
                    w = inst->hw.pending_w;
                    h = inst->hw.pending_h;
                    inst->hw.pending_ahb = nullptr;
                }
            }
            if (ahb) {
                blit_browser_ahb(inst->hw, ahb, w, h, inst->id);
                AHardwareBuffer_release(ahb);
            }
            if (inst->hw.obs_tex && inst->hw.tex_w > 0 && inst->hw.tex_h > 0) {
                gs_effect_t *eff = effect ? effect : obs_get_base_effect(OBS_EFFECT_DEFAULT);
                const bool linear_srgb = gs_get_linear_srgb();
                const bool previous_srgb = gs_framebuffer_srgb_enabled();
                gs_enable_framebuffer_srgb(linear_srgb);
                gs_eparam_t *param = gs_effect_get_param_by_name(eff, "image");
                if (linear_srgb)
                    gs_effect_set_texture_srgb(param, inst->hw.obs_tex);
                else
                    gs_effect_set_texture(param, inst->hw.obs_tex);
                while (gs_effect_loop(eff, "Draw")) {
                    gs_draw_sprite(inst->hw.obs_tex, 0, inst->hw.tex_w, inst->hw.tex_h);
                }
                gs_enable_framebuffer_srgb(previous_srgb);
                return;
            }
        }
        inst->frame.render();
    };
    info.icon_type = OBS_ICON_TYPE_BROWSER;
    obs_register_source(&info);
    g_browser_registered = true;
}

void android_browser_shutdown()
{
    JavaVM *jvm = nullptr;
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_browser_mutex);
        jvm = g_browser_jvm;
        cls = g_browser_host_class;
        mid = g_mid_destroy_all;
        g_instances.clear();
    }
    if (obs_initialized()) {
        obs_enter_graphics();
        if (g_browser_oes.prog) { glDeleteProgram(g_browser_oes.prog); g_browser_oes.prog = 0; }
        if (g_browser_oes.vao) { glDeleteVertexArrays(1, &g_browser_oes.vao); g_browser_oes.vao = 0; }
        g_browser_oes.initialized = false;
        g_browser_oes.supported = false;
        obs_leave_graphics();
    }
    if (!jvm || !cls || !mid) return;
    JniAttachGuard guard(jvm);
    if (!guard.env) return;
    guard.env->CallStaticVoidMethod(cls, mid);
    if (guard.env->ExceptionCheck()) guard.env->ExceptionClear();
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_registerBrowserHost(JNIEnv *env, jclass, jclass hostClass)
{
    std::lock_guard<std::mutex> lock(g_browser_mutex);
    if (g_browser_host_class) return;
    env->GetJavaVM(&g_browser_jvm);
    g_browser_host_class = static_cast<jclass>(env->NewGlobalRef(hostClass));
    if (!g_browser_host_class) return;
    auto get_mid = [&](const char *name, const char *sig) -> jmethodID {
        jmethodID m = env->GetStaticMethodID(g_browser_host_class, name, sig);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            __android_log_print(ANDROID_LOG_ERROR, "ObsBrowserSource", "Missing JNI method %s%s", name, sig);
            return nullptr;
        }
        return m;
    };
    g_mid_create_update = get_mid("createOrUpdate", "(JLjava/lang/String;IIIZLjava/lang/String;ZZZ)V");
    g_mid_set_visible = get_mid("setVisible", "(JZ)V");
    g_mid_reload = get_mid("reload", "(JZ)V");
    g_mid_interact = get_mid("interact", "(J)V");
    g_mid_destroy = get_mid("destroy", "(J)V");
    g_mid_destroy_all = get_mid("destroyAll", "()V");
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_pushBrowserFrame(
    JNIEnv *env, jclass, jlong instanceId, jobject rgbaBuf, jint width, jint height, jlong timestampNs)
{
    AndroidCallbackScope callback;
    if (!callback) return ;
    if (!rgbaBuf || width <= 0 || height <= 0) return;
    auto *ptr = static_cast<uint8_t *>(env->GetDirectBufferAddress(rgbaBuf));
    jlong cap = env->GetDirectBufferCapacity(rgbaBuf);
    if (!ptr || cap < jlong(width) * jlong(height) * 4) return;

    std::shared_ptr<BrowserSourceInstance> inst;
    {
        std::lock_guard<std::mutex> lock(g_browser_mutex);
        auto it = g_instances.find(int64_t(instanceId));
        if (it != g_instances.end()) inst = it->second;
    }
    if (inst) {
        inst->hw.active.store(false, std::memory_order_release);
        inst->frame.submit(ptr, uint32_t(width), uint32_t(height), uint32_t(width) * 4, true);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_obsandroid_port_NativeBridge_pushBrowserBitmap(JNIEnv *env, jclass, jlong id, jobject bitmap)
{
    AndroidCallbackScope callback;
    if (!callback) return false;
    std::shared_ptr<BrowserSourceInstance> inst;
    {
        std::lock_guard<std::mutex> lock(g_browser_mutex);
        auto it = g_instances.find(int64_t(id));
        if (it != g_instances.end()) inst = it->second;
    }
    if (!inst) return false;
    inst->hw.active.store(false, std::memory_order_release);
    return inst->frame.submitBitmap(env, bitmap);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_obsandroid_port_NativeBridge_pushBrowserHardwareBuffer(
    JNIEnv *env, jclass, jlong id, jobject hwBuf, jint width, jint height)
{
    AndroidCallbackScope callback;
    if (!callback || !hwBuf || width <= 0 || height <= 0 || width > 4096 || height > 4096) return JNI_FALSE;
    std::shared_ptr<BrowserSourceInstance> inst;
    {
        std::lock_guard<std::mutex> lock(g_browser_mutex);
        auto it = g_instances.find(int64_t(id));
        if (it != g_instances.end()) inst = it->second;
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
    }
    inst->hw.reported_w.store(uint32_t(width), std::memory_order_relaxed);
    inst->hw.reported_h.store(uint32_t(height), std::memory_order_relaxed);
    inst->hw.active.store(true, std::memory_order_release);
    if (old) AHardwareBuffer_release(old);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_pushBrowserAudio(JNIEnv *env, jclass, jlong id, jbyteArray pcm,
    jint channels, jint rate, jlong timestamp)
{
    AndroidCallbackScope callback;
    if (!callback || !pcm || channels < 1 || channels > 2 || rate < 8000 || rate > 96000) return;
    jsize bytes = env->GetArrayLength(pcm);
    if (bytes <= 0 || bytes > 8192 || bytes % (channels * 2)) return;
    obs_source_t *source = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_browser_mutex);
        auto it = g_instances.find(int64_t(id));
        if (it != g_instances.end() && it->second->capture_audio && it->second->showing)
            source = obs_source_get_ref(it->second->source);
    }
    if (!source) return;
    jbyte *data = env->GetByteArrayElements(pcm, nullptr);
    if (data) {
        obs_source_audio audio{};
        audio.data[0] = reinterpret_cast<const uint8_t *>(data);
        audio.frames = uint32_t(bytes / (channels * 2));
        audio.samples_per_sec = uint32_t(rate);
        audio.format = AUDIO_FORMAT_16BIT;
        audio.speakers = channels == 1 ? SPEAKERS_MONO : SPEAKERS_STEREO;
        audio.timestamp = uint64_t(timestamp);
        obs_source_output_audio(source, &audio);
        env->ReleaseByteArrayElements(pcm, data, JNI_ABORT);
    }
    obs_source_release(source);
}
