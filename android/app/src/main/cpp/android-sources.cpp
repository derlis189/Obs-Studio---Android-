#include "android-session-gate.h"
// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-sources.h"
#include "android-uvc.h"
#include "android-vtuber.h"
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
#include <graphics/graphics.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
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

struct OesBlitContext {
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

OesBlitContext g_oes;

constexpr const char *kOesBlitVS = R"(#version 300 es
out vec2 v_uv;
void main() {
    vec2 pos = vec2((gl_VertexID == 1) ? 3.0 : -1.0,
                    (gl_VertexID == 2) ? 3.0 : -1.0);
    gl_Position = vec4(pos, 0.0, 1.0);
    v_uv = pos * 0.5 + 0.5;
}
)";

constexpr const char *kOesBlitFS = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 v_uv;
uniform samplerExternalOES u_tex;
out vec4 fragColor;
void main() {
    fragColor = vec4(texture(u_tex, v_uv).rgb, 1.0);
}
)";

GLuint compile_oes_shader(GLenum type, const char *src)
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
        blog(LOG_ERROR, "[android-gpu-capture] GLES OES shader error: %s", log);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

bool ensure_oes_blit_ready()
{
    if (g_oes.initialized) return g_oes.supported;
    g_oes.initialized = true;
    g_oes.get_native_client_buffer =
        reinterpret_cast<PFN_eglGetNativeClientBufferANDROID>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    g_oes.create_image = reinterpret_cast<PFN_eglCreateImageKHR>(eglGetProcAddress("eglCreateImageKHR"));
    g_oes.destroy_image = reinterpret_cast<PFN_eglDestroyImageKHR>(eglGetProcAddress("eglDestroyImageKHR"));
    g_oes.image_target_tex2d =
        reinterpret_cast<PFN_glEGLImageTargetTexture2DOES>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!g_oes.get_native_client_buffer || !g_oes.create_image || !g_oes.destroy_image || !g_oes.image_target_tex2d) {
        blog(LOG_WARNING, "[android-gpu-capture] EGLImage/OES extensions unavailable");
        return false;
    }
    GLuint vs = compile_oes_shader(GL_VERTEX_SHADER, kOesBlitVS);
    GLuint fs = compile_oes_shader(GL_FRAGMENT_SHADER, kOesBlitFS);
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
        blog(LOG_ERROR, "[android-gpu-capture] GLES OES link error: %s", log);
        glDeleteProgram(prog);
        return false;
    }
    g_oes.prog = prog;
    g_oes.loc_tex = glGetUniformLocation(prog, "u_tex");
    glGenVertexArrays(1, &g_oes.vao);
    g_oes.supported = true;
    blog(LOG_INFO, "[android-gpu-capture] Zero-copy AHardwareBuffer -> EGLImage -> GL_TEXTURE_EXTERNAL_OES enabled");
    return true;
}

struct GpuCaptureState {
    std::mutex mutex;
    AHardwareBuffer *pending_ahb{};
    int pending_w{0}, pending_h{0};
    std::vector<uint8_t> pending_rgba;
    int cpu_w{0}, cpu_h{0};
    bool has_cpu_pending{false};
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
        if (oes_tex) {
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
        }
        for (auto &kv : img_cache) {
            if (kv.second != EGL_NO_IMAGE_KHR && cached_dpy != EGL_NO_DISPLAY && g_oes.destroy_image) {
                g_oes.destroy_image(cached_dpy, kv.second);
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
            pending_rgba.clear();
            cpu_w = cpu_h = 0;
            has_cpu_pending = false;
        }
        if (old) AHardwareBuffer_release(old);
        reported_w.store(0, std::memory_order_relaxed);
        reported_h.store(0, std::memory_order_relaxed);
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

std::mutex source_mutex;
JavaVM *g_jvm = nullptr;
jclass g_host_class = nullptr;
jmethodID g_host_on_capture_cfg = nullptr;
jmethodID g_host_enum_devices = nullptr;

constexpr int kNumCaptureKinds = 5;
const char *ids[kNumCaptureKinds] = {
    "android_camera",
    "android_microphone",
    "android_screen",
    "android_playback_audio",
    "android_usb_camera"
};
const char *names[kNumCaptureKinds] = {
    "Cámara (Android)",
    "Micrófono (Android)",
    "Pantalla (Android)",
    "Audio interno (Android)",
    "Cámara / Capturadora USB (UVC)"
};
std::vector<obs_scene_t *> scenes;

struct SourceInstance {
    obs_source_t *source{};
    int kind{};
    bool enabled{true};
    bool visible{false};
    bool capture_audio{true};
    bool auto_fit{true};
    int fit_mode{0}; // 0=inner (no crop), 1=outer (fill)
    int last_fit_w{0};
    int last_fit_h{0};
    int last_fit_rot{-1};
    std::string device_id{"default"};
    float zoom{1.0f};
    int exposure{0};
};

struct Capture {
    obs_source_t *source{};
    std::vector<obs_source_t *> active_sources;
    std::vector<SourceInstance *> active_instances;
    std::vector<obs_sceneitem_t *> items;
    std::vector<uint8_t> pixels;
    uint64_t frames{}, timestamp{};
    float peak{};
    int width{}, height{}, rotation{};
    bool mirror{};
    GpuCaptureState gpu;
};
std::array<Capture, kNumCaptureKinds> captures;
std::atomic<uint64_t> mixed_frames{};
std::atomic<float> mixed_peak{};
std::atomic<float> g_mic_rms_level{0.0f};
bool audio_callback_registered{};
bool sources_registered{};

struct AutoFitCtx {
    obs_source_t *target;
    uint32_t base_w;
    uint32_t base_h;
    int rotation;
    int fit_mode;
};

bool auto_fit_enum_items(obs_scene_t *, obs_sceneitem_t *item, void *param)
{
    auto *ctx = static_cast<AutoFitCtx *>(param);
    if (!ctx || !item || obs_sceneitem_get_source(item) != ctx->target) return true;

    // Preserve any custom position, scale, or bounds the user manually set (e.g. PiP camera)
    vec2 curPos{}, curScale{}, curBounds{};
    obs_sceneitem_get_pos(item, &curPos);
    obs_sceneitem_get_scale(item, &curScale);
    obs_sceneitem_get_bounds(item, &curBounds);
    obs_bounds_type curBt = obs_sceneitem_get_bounds_type(item);
    bool userMoved = (std::fabs(curPos.x) > 2.0f || std::fabs(curPos.y) > 2.0f);
    bool userScaled = (std::fabs(curScale.x - 1.0f) > 0.02f || std::fabs(curScale.y - 1.0f) > 0.02f);
    bool userBounded = (curBt != OBS_BOUNDS_NONE &&
        (std::fabs(curBounds.x - float(ctx->base_w)) > 4.0f || std::fabs(curBounds.y - float(ctx->base_h)) > 4.0f));
    if (userMoved || userScaled || userBounded) {
        // Respect user's custom position, scale, bounds, and rotation
        return true;
    }

    vec2 pos{0.0f, 0.0f};
    vec2 bounds{float(ctx->base_w), float(ctx->base_h)};
    obs_sceneitem_set_alignment(item, OBS_ALIGN_TOP | OBS_ALIGN_LEFT);
    obs_sceneitem_set_pos(item, &pos);
    obs_sceneitem_set_rot(item, float(ctx->rotation));
    obs_sceneitem_set_bounds_type(item, ctx->fit_mode == 1 ? OBS_BOUNDS_SCALE_OUTER : OBS_BOUNDS_SCALE_INNER);
    obs_sceneitem_set_bounds_alignment(item, OBS_ALIGN_CENTER);
    obs_sceneitem_set_bounds(item, &bounds);
    return true;
}

bool auto_fit_enum_scenes(void *param, obs_source_t *scene_src)
{
    obs_scene_t *scene = obs_scene_from_source(scene_src);
    if (scene) obs_scene_enum_items(scene, auto_fit_enum_items, param);
    return true;
}

void apply_auto_fit_to_source(obs_source_t *target, int rotation, int fit_mode)
{
    if (!target || !obs_initialized()) return;
    obs_video_info ovi{};
    if (!obs_get_video_info(&ovi) || ovi.base_width == 0 || ovi.base_height == 0) return;
    AutoFitCtx ctx{target, ovi.base_width, ovi.base_height, rotation, fit_mode};
    obs_enum_scenes(auto_fit_enum_scenes, &ctx);
}

void notify_java_capture(int kind, bool enabled, const std::string &device_id, float zoom, int exposure)
{
    JavaVM *jvm = nullptr;
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        jvm = g_jvm;
        cls = g_host_class;
        mid = g_host_on_capture_cfg;
    }
    if (!jvm || !cls || !mid) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    int status = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) != 0) return;
        detach = true;
    } else if (status != JNI_OK) {
        return;
    }
    jstring jDev = env->NewStringUTF(device_id.empty() ? "default" : device_id.c_str());
    env->CallStaticVoidMethod(cls, mid, jint(kind), jboolean(enabled), jDev, jfloat(zoom), jint(exposure));
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jDev);
    if (detach) jvm->DetachCurrentThread();
}

std::string query_java_devices(int kind)
{
    JavaVM *jvm = nullptr;
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        jvm = g_jvm;
        cls = g_host_class;
        mid = g_host_enum_devices;
    }
    if (!jvm || !cls || !mid) return {};
    JNIEnv *env = nullptr;
    bool detach = false;
    int status = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) != 0) return {};
        detach = true;
    } else if (status != JNI_OK) {
        return {};
    }
    std::string result;
    auto jStr = static_cast<jstring>(env->CallStaticObjectMethod(cls, mid, jint(kind)));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (jStr) {
        const char *utf = env->GetStringUTFChars(jStr, nullptr);
        if (utf) {
            result = utf;
            env->ReleaseStringUTFChars(jStr, utf);
        }
        env->DeleteLocalRef(jStr);
    }
    if (detach) jvm->DetachCurrentThread();
    return result;
}

const char *source_name(void *kind) { return names[reinterpret_cast<uintptr_t>(kind)]; }

void source_defaults(void *type_data, obs_data_t *settings)
{
    int kind = static_cast<int>(reinterpret_cast<uintptr_t>(type_data));
    obs_data_set_default_string(settings, "device_id", "default");
    obs_data_set_default_bool(settings, "enabled", kind == 0 || kind == 1 || kind == 4);
    obs_data_set_default_bool(settings, "front_camera", false);
    obs_data_set_default_double(settings, "zoom", 1.0);
    obs_data_set_default_int(settings, "exposure", 0);
    obs_data_set_default_int(settings, "cam_quality", 720);
    obs_data_set_default_int(settings, "cam_fps", 30);
    obs_data_set_default_bool(settings, "cam_stab", true);
    obs_data_set_default_bool(settings, "cam_ae_lock", false);
    obs_data_set_default_bool(settings, "cam_torch", false);
    obs_data_set_default_int(settings, "screen_max_res", 1920);
    obs_data_set_default_bool(settings, "capture_audio", true);
    obs_data_set_default_bool(settings, "auto_fit", kind == 2);
    obs_data_set_default_int(settings, "fit_mode", 0);
}

obs_properties_t *source_properties2(void *data, void *type_data)
{
    obs_properties_t *props = obs_properties_create();
    int kind = data ? static_cast<SourceInstance *>(data)->kind
                    : static_cast<int>(reinterpret_cast<uintptr_t>(type_data));
    obs_property_t *dev = obs_properties_add_list(props, "device_id", "Dispositivo", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    if (kind == 1) {
        obs_property_list_add_string(dev, "Predeterminado (Interfaz USB o Micrófono del celular)", "default");
        obs_property_list_add_string(dev, "Micrófono del celular (integrado)", "builtin");
    } else {
        obs_property_list_add_string(dev, "Predeterminado (automático)", "default");
    }
    if (kind == 0) {
        obs_property_list_add_string(dev, "Cámara frontal", "front");
        obs_property_list_add_string(dev, "Cámara trasera", "back");
    }
    if (kind == 0 || kind == 1 || kind == 4) {
        std::string listed = query_java_devices(kind);
        std::istringstream ss(listed);
        std::string line;
        while (std::getline(ss, line)) {
            if (line.empty()) continue;
            auto tab = line.find('\t');
            if (tab == std::string::npos) continue;
            std::string id = line.substr(0, tab);
            std::string label = line.substr(tab + 1);
            if (!id.empty() && !label.empty()) {
                obs_property_list_add_string(dev, label.c_str(), id.c_str());
            }
        }
    }
    obs_properties_add_bool(props, "enabled", "Captura activa");
    if (kind == 2) {
        obs_properties_add_bool(props, "capture_audio", "Capturar audio de la pantalla (control independiente en Mezclador)");
        obs_property_t *sr = obs_properties_add_list(props, "screen_max_res", "Resolucion maxima de pantalla",
                                                     OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
        obs_property_list_add_int(sr, "1080p (Full HD nitido · GPU zero-copy)", 1920);
        obs_property_list_add_int(sr, "720p (HD · Menor consumo)", 1280);
    }
    if (kind == 0 || kind == 2 || kind == 4) {
        obs_properties_add_bool(props, "auto_fit", "Ajustar al lienzo horizontal (solo si no la moviste)");
        obs_property_t *fm = obs_properties_add_list(props, "fit_mode", "Modo de ajuste horizontal", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
        obs_property_list_add_int(fm, "Ajustar completo (sin recortar)", 0);
        obs_property_list_add_int(fm, "Rellenar pantalla horizontal", 1);
    }
    if (kind == 0 || kind == 4) {
        obs_property_t *q = obs_properties_add_list(props, "cam_quality", "Calidad / Resolucion de camara", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
        obs_property_list_add_int(q, "480p (SD - Menor CPU / USB)", 480);
        obs_property_list_add_int(q, "720p (HD - Recomendada)", 720);
        obs_property_list_add_int(q, "1080p (Full HD)", 1080);
        if (kind == 0) {
            obs_property_list_add_int(q, "1440p / 4K (Maxima disponible)", 2160);
        }
    }
    if (kind == 0) {
        obs_property_t *fps = obs_properties_add_list(props, "cam_fps", "FPS de camara",
                                                      OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
        obs_property_list_add_int(fps, "30 fps (Estandar · mejor en poca luz)", 30);
        obs_property_list_add_int(fps, "60 fps (Fluido · si el sensor soporta)", 60);
        obs_properties_add_bool(props, "cam_stab", "Estabilizacion de video (EIS / OIS)");
        obs_properties_add_bool(props, "cam_ae_lock", "Bloquear exposicion automatica (AE Lock)");
        obs_properties_add_bool(props, "cam_torch", "Linterna / Flash continuo (camara trasera)");
        obs_properties_add_float_slider(props, "zoom", "Zoom", 0.6, 10.0, 0.1);
        obs_properties_add_int_slider(props, "exposure", "Exposicion (EV)", -6, 6, 1);
    }
    return props;
}

std::string resolve_camera_device_id(obs_data_t *settings)
{
    const char *dev = obs_data_get_string(settings, "device_id");
    std::string base = "default";
    if (dev && *dev && std::strcmp(dev, "default") != 0) base = dev;
    else if (obs_data_get_bool(settings, "front_camera")) base = "front";
    int q = int(obs_data_get_int(settings, "cam_quality"));
    if (q <= 0) q = 720;
    int fps = int(obs_data_get_int(settings, "cam_fps"));
    if (fps != 60) fps = 30;
    return base + "@" + std::to_string(q) + ":" + std::to_string(fps);
}

int pack_camera_controls(obs_data_t *settings)
{
    const int ev = std::clamp(int(obs_data_get_int(settings, "exposure")), -12, 12);
    const bool stab = obs_data_has_user_value(settings, "cam_stab") ? obs_data_get_bool(settings, "cam_stab") : true;
    const bool ae_lock = obs_data_get_bool(settings, "cam_ae_lock");
    const bool torch = obs_data_get_bool(settings, "cam_torch");
    return 0x1000 | ((ev + 64) & 0xFF) | (stab ? 0x100 : 0) | (ae_lock ? 0x200 : 0) | (torch ? 0x400 : 0);
}

std::string resolve_uvc_device_id(obs_data_t *settings)
{
    const char *dev = obs_data_get_string(settings, "device_id");
    std::string base = (dev && *dev) ? dev : "default";
    int q = int(obs_data_get_int(settings, "cam_quality"));
    if (q <= 0) q = 720;
    return base + "@" + std::to_string(q);
}

std::string collect_active_mic_specs_locked()
{
    std::vector<std::string> unique_specs;
    for (SourceInstance *si : captures[1].active_instances) {
        if (!si || !si->enabled) continue;
        std::string spec = si->device_id.empty() ? "default" : si->device_id;
        if (std::find(unique_specs.begin(), unique_specs.end(), spec) == unique_specs.end()) {
            unique_specs.push_back(std::move(spec));
        }
    }
    std::string joined;
    for (size_t i = 0; i < unique_specs.size(); ++i) {
        if (i > 0) joined.push_back('\n');
        joined += unique_specs[i];
    }
    return joined;
}

bool collect_visible_video_capture_locked(int kind, std::string &device_id, float &zoom, int &exposure)
{
    if (kind != 0 && kind != 4) return false;
    for (SourceInstance *inst : captures[kind].active_instances) {
        if (!inst || !inst->visible || !inst->enabled) continue;
        device_id = inst->device_id.empty() ? "default" : inst->device_id;
        zoom = inst->zoom;
        exposure = inst->exposure;
        return true;
    }
    return false;
}

void refresh_visible_video_capture(int kind)
{
    std::string device_id = "default";
    float zoom = 1.0f;
    int exposure = 0;
    bool enabled = false;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        enabled = collect_visible_video_capture_locked(kind, device_id, zoom, exposure);
    }
    notify_java_capture(kind, enabled, device_id, zoom, exposure);
}

void source_show(void *data)
{
    auto *inst = static_cast<SourceInstance *>(data);
    if (!inst || (inst->kind != 0 && inst->kind != 4)) return;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        if (inst->visible) return;
        inst->visible = true;
    }
    refresh_visible_video_capture(inst->kind);
}

void source_hide(void *data)
{
    auto *inst = static_cast<SourceInstance *>(data);
    if (!inst || (inst->kind != 0 && inst->kind != 4)) return;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        if (!inst->visible) return;
        inst->visible = false;
    }
    refresh_visible_video_capture(inst->kind);
}

void source_update(void *data, obs_data_t *settings)
{
    auto *inst = static_cast<SourceInstance *>(data);
    if (!inst) return;
    int kind = 0;
    bool enabled = true;
    std::string device_id;
    float zoom = 1.0f;
    int exposure = 0;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        inst->enabled = obs_data_get_bool(settings, "enabled");
        inst->capture_audio = obs_data_get_bool(settings, "capture_audio");
        inst->auto_fit = obs_data_get_bool(settings, "auto_fit");
        inst->fit_mode = int(obs_data_get_int(settings, "fit_mode"));
        inst->last_fit_w = 0;
        inst->last_fit_h = 0;
        inst->last_fit_rot = -1;
        if (inst->kind == 0) {
            inst->device_id = resolve_camera_device_id(settings);
            double z = obs_data_get_double(settings, "zoom");
            inst->zoom = float(z > 0.1 ? z : 1.0);
            inst->exposure = pack_camera_controls(settings);
        } else if (inst->kind == 2) {
            int max_res = int(obs_data_get_int(settings, "screen_max_res"));
            if (max_res != 1280 && max_res != 1920) max_res = 1920;
            inst->device_id = std::to_string(max_res);
            inst->exposure = inst->capture_audio ? 1 : 0;
        } else if (inst->kind == 4) {
            inst->device_id = resolve_uvc_device_id(settings);
        } else {
            const char *dev = obs_data_get_string(settings, "device_id");
            inst->device_id = (dev && *dev) ? dev : "default";
        }
        kind = inst->kind;
        if (kind == 1) {
            device_id = collect_active_mic_specs_locked();
            enabled = !device_id.empty();
        } else if (kind == 0 || kind == 4) {
            enabled = collect_visible_video_capture_locked(kind, device_id, zoom, exposure);
        } else {
            enabled = inst->enabled;
            device_id = inst->device_id;
        }
        zoom = inst->zoom;
        exposure = inst->exposure;
    }
    notify_java_capture(kind, enabled, device_id, zoom, exposure);
}

void *source_create(obs_data_t *settings, obs_source_t *source)
{
    int kind = 0;
    const char *id = obs_source_get_id(source);
    for (int i = 0; i < kNumCaptureKinds; ++i) {
        if (id && std::strcmp(id, ids[i]) == 0) { kind = i; break; }
    }
    auto *inst = new SourceInstance{};
    inst->source = source;
    inst->kind = kind;
    inst->enabled = obs_data_get_bool(settings, "enabled");
    inst->capture_audio = obs_data_get_bool(settings, "capture_audio");
    inst->auto_fit = obs_data_get_bool(settings, "auto_fit");
    inst->fit_mode = int(obs_data_get_int(settings, "fit_mode"));
    if (kind == 0) {
        inst->device_id = resolve_camera_device_id(settings);
        double z = obs_data_get_double(settings, "zoom");
        inst->zoom = float(z > 0.1 ? z : 1.0);
        inst->exposure = pack_camera_controls(settings);
    } else if (kind == 2) {
        int max_res = int(obs_data_get_int(settings, "screen_max_res"));
        if (max_res != 1280 && max_res != 1920) max_res = 1920;
        inst->device_id = std::to_string(max_res);
        inst->exposure = inst->capture_audio ? 1 : 0;
    } else if (kind == 4) {
        inst->device_id = resolve_uvc_device_id(settings);
    } else {
        const char *dev = obs_data_get_string(settings, "device_id");
        inst->device_id = (dev && *dev) ? dev : "default";
    }
    if (kind == 4) obs_source_set_async_unbuffered(source, true);
    std::string notify_dev = inst->device_id;
    bool notify_en = inst->enabled;
    bool notify_on_create = kind != 0 && kind != 4;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        captures[kind].active_sources.push_back(source);
        captures[kind].active_instances.push_back(inst);
        if (kind == 1) {
            notify_dev = collect_active_mic_specs_locked();
            notify_en = !notify_dev.empty();
        }
    }
    if (notify_on_create && notify_en) {
        notify_java_capture(kind, notify_en, notify_dev, inst->zoom, inst->exposure);
    }
    return inst;
}

void source_destroy(void *data)
{
    auto *inst = static_cast<SourceInstance *>(data);
    if (!inst) return;
    int kind = inst->kind;
    bool any_left = false;
    std::string remaining_mic_specs;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        auto &vec = captures[kind].active_sources;
        vec.erase(std::remove(vec.begin(), vec.end(), inst->source), vec.end());
        auto &ivec = captures[kind].active_instances;
        ivec.erase(std::remove(ivec.begin(), ivec.end(), inst), ivec.end());
        any_left = !vec.empty();
        if (kind == 1) {
            remaining_mic_specs = collect_active_mic_specs_locked();
        }
    }
    if (kind == 1) {
        notify_java_capture(1, !remaining_mic_specs.empty(), remaining_mic_specs, 1.0f, 0);
    } else if (kind == 0 || kind == 4) {
        refresh_visible_video_capture(kind);
        if (!any_left && kind == 0) {
            captures[kind].gpu.clear_pending();
            if (obs_initialized()) {
                obs_enter_graphics();
                captures[kind].gpu.destroy_gl_resources();
                obs_leave_graphics();
            }
        }
    } else if (!any_left) {
        notify_java_capture(kind, false, "default", 1.0f, 0);
        if (kind == 0 || kind == 2) {
            captures[kind].gpu.clear_pending();
            if (obs_initialized()) {
                obs_enter_graphics();
                captures[kind].gpu.destroy_gl_resources();
                obs_leave_graphics();
            }
        }
    }
    delete inst;
}

uint32_t gpu_source_get_width(void *data)
{
    auto *inst = static_cast<SourceInstance *>(data);
    if (!inst || (inst->kind != 0 && inst->kind != 2)) return 0;
    return captures[inst->kind].gpu.reported_w.load(std::memory_order_relaxed);
}

uint32_t gpu_source_get_height(void *data)
{
    auto *inst = static_cast<SourceInstance *>(data);
    if (!inst || (inst->kind != 0 && inst->kind != 2)) return 0;
    return captures[inst->kind].gpu.reported_h.load(std::memory_order_relaxed);
}

void blit_ahb_to_gpu_texture(GpuCaptureState &gpu, AHardwareBuffer *ahb, int w, int h, int kind)
{
    if (!ahb || w <= 0 || h <= 0) return;
    if (!ensure_oes_blit_ready()) return;
    EGLDisplay dpy = eglGetCurrentDisplay();
    if (dpy == EGL_NO_DISPLAY) return;

    const bool size_changed = (!gpu.obs_tex || gpu.tex_w != uint32_t(w) || gpu.tex_h != uint32_t(h));
    if (size_changed || gpu.cached_dpy != dpy || gpu.img_cache.size() > 8) {
        gpu.clear_img_cache();
        gpu.cached_dpy = dpy;
    }

    EGLImageKHR img = EGL_NO_IMAGE_KHR;
    auto it = gpu.img_cache.find(ahb);
    if (it != gpu.img_cache.end()) {
        img = it->second;
    } else {
        EGLClientBuffer cbuf = g_oes.get_native_client_buffer(ahb);
        if (!cbuf) return;
        const EGLint img_attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        img = g_oes.create_image(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cbuf, img_attrs);
        if (img == EGL_NO_IMAGE_KHR) return;
        AHardwareBuffer_acquire(ahb);
        gpu.img_cache.emplace(ahb, img);
    }

    if (size_changed) {
        if (gpu.obs_tex) gs_texture_destroy(gpu.obs_tex);
        gpu.obs_tex = gs_texture_create(uint32_t(w), uint32_t(h), GS_RGBA, 1, nullptr, GS_RENDER_TARGET);
        gpu.tex_w = gpu.obs_tex ? uint32_t(w) : 0;
        gpu.tex_h = gpu.obs_tex ? uint32_t(h) : 0;
        gpu.fbo_ready = false;
    }
    if (!gpu.obs_tex) return;
    const auto *dst_tex_ptr = static_cast<const GLuint *>(gs_texture_get_obj(gpu.obs_tex));
    if (!dst_tex_ptr || !*dst_tex_ptr) return;
    if (!gpu.fbo) {
        glGenFramebuffers(1, &gpu.fbo);
        gpu.fbo_ready = false;
    }
    if (!gpu.oes_tex) {
        glGenTextures(1, &gpu.oes_tex);
    }

    // Save raw GLES state so libobs-opengl cached state remains 100% intact
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
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, gpu.oes_tex);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    g_oes.image_target_tex2d(GL_TEXTURE_EXTERNAL_OES, img);

    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, gpu.fbo);
    if (!gpu.fbo_ready) {
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, *dst_tex_ptr, 0);
        gpu.fbo_ready = (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE);
    }
    if (gpu.fbo_ready) {
        if (prev_blend) glDisable(GL_BLEND);
        if (prev_scissor) glDisable(GL_SCISSOR_TEST);
        if (prev_depth) glDisable(GL_DEPTH_TEST);
        if (prev_cull) glDisable(GL_CULL_FACE);
        if (prev_srgb) glDisable(GL_FRAMEBUFFER_SRGB);
        glViewport(0, 0, w, h);
        glUseProgram(g_oes.prog);
        glUniform1i(g_oes.loc_tex, 0);
        glBindVertexArray(g_oes.vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        if (!gpu.logged_active) {
            gpu.logged_active = true;
            blog(LOG_INFO, "[android-gpu-capture] Zero-copy %s GPU frame rendered (%dx%d)",
                 kind == 0 ? "camera" : "screen", w, h);
        }
    }

    // Restore GLES state
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

void gpu_source_video_render(void *data, gs_effect_t *effect)
{
    auto *inst = static_cast<SourceInstance *>(data);
    if (!inst || (inst->kind != 0 && inst->kind != 2)) return;
    auto &gpu = captures[inst->kind].gpu;

    AHardwareBuffer *ahb = nullptr;
    int ahb_w = 0, ahb_h = 0;
    std::vector<uint8_t> cpu_rgba;
    int cpu_w = 0, cpu_h = 0;
    bool has_cpu = false;
    {
        std::lock_guard<std::mutex> lock(gpu.mutex);
        if (gpu.pending_ahb) {
            ahb = gpu.pending_ahb;
            ahb_w = gpu.pending_w;
            ahb_h = gpu.pending_h;
            gpu.pending_ahb = nullptr;
        } else if (gpu.has_cpu_pending && gpu.cpu_w > 0 && gpu.cpu_h > 0 && !gpu.pending_rgba.empty()) {
            cpu_rgba.swap(gpu.pending_rgba);
            cpu_w = gpu.cpu_w;
            cpu_h = gpu.cpu_h;
            gpu.has_cpu_pending = false;
            has_cpu = true;
        }
    }

    if (ahb) {
        blit_ahb_to_gpu_texture(gpu, ahb, ahb_w, ahb_h, inst->kind);
        AHardwareBuffer_release(ahb);
    } else if (has_cpu) {
        if (!gpu.obs_tex || gpu.tex_w != uint32_t(cpu_w) || gpu.tex_h != uint32_t(cpu_h)) {
            if (gpu.obs_tex) gs_texture_destroy(gpu.obs_tex);
            gpu.obs_tex = gs_texture_create(uint32_t(cpu_w), uint32_t(cpu_h), GS_RGBA, 1, nullptr, GS_DYNAMIC | GS_RENDER_TARGET);
            gpu.tex_w = gpu.obs_tex ? uint32_t(cpu_w) : 0;
            gpu.tex_h = gpu.obs_tex ? uint32_t(cpu_h) : 0;
        }
        if (gpu.obs_tex) {
            gs_texture_set_image(gpu.obs_tex, cpu_rgba.data(), uint32_t(cpu_w) * 4, false);
        }
    }

    if (!gpu.obs_tex || gpu.tex_w == 0 || gpu.tex_h == 0) return;
    gs_effect_t *eff = effect ? effect : obs_get_base_effect(OBS_EFFECT_DEFAULT);
    const bool linear_srgb = gs_get_linear_srgb();
    const bool previous_srgb = gs_framebuffer_srgb_enabled();
    gs_enable_framebuffer_srgb(linear_srgb);
    gs_eparam_t *param = gs_effect_get_param_by_name(eff, "image");
    if (linear_srgb)
        gs_effect_set_texture_srgb(param, gpu.obs_tex);
    else
        gs_effect_set_texture(param, gpu.obs_tex);
    while (gs_effect_loop(eff, "Draw")) {
        gs_draw_sprite(gpu.obs_tex, 0, gpu.tex_w, gpu.tex_h);
    }
    gs_enable_framebuffer_srgb(previous_srgb);
}

void fit(Capture &capture, int kind, int width, int height, int rotation, bool mirror);

void push_ahb_capture_frame(int kind, AHardwareBuffer *ahb, int width, int height, uint64_t timestamp, int rotation, bool mirror)
{
    if (!ahb || width <= 0 || height <= 0 || width > 4096 || height > 4096 || (kind != 0 && kind != 2)) return;
    std::vector<std::pair<obs_source_t *, int>> to_autofit;
    bool has_consumers = false;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        auto &capture = captures[kind];
        if (capture.active_sources.empty() && !capture.source) return;
        has_consumers = true;
        fit(capture, kind, width, height, rotation, mirror);
        for (SourceInstance *inst : capture.active_instances) {
            if (inst && inst->auto_fit && inst->source &&
                (inst->last_fit_w != width || inst->last_fit_h != height || (kind == 0 && inst->last_fit_rot != rotation))) {
                inst->last_fit_w = width;
                inst->last_fit_h = height;
                inst->last_fit_rot = rotation;
                obs_source_t *ref = obs_source_get_ref(inst->source);
                if (ref) to_autofit.emplace_back(ref, inst->fit_mode);
            }
        }
        capture.frames++;
        capture.timestamp = timestamp;
    }
    if (has_consumers) {
        AHardwareBuffer_acquire(ahb);
        AHardwareBuffer *old = nullptr;
        auto &gpu = captures[kind].gpu;
        {
            std::lock_guard<std::mutex> lock(gpu.mutex);
            old = gpu.pending_ahb;
            gpu.pending_ahb = ahb;
            gpu.pending_w = width;
            gpu.pending_h = height;
            gpu.has_cpu_pending = false;
        }
        gpu.reported_w.store(uint32_t(width), std::memory_order_relaxed);
        gpu.reported_h.store(uint32_t(height), std::memory_order_relaxed);
        if (old) AHardwareBuffer_release(old);
    }
    for (auto &item : to_autofit) {
        apply_auto_fit_to_source(item.first, kind == 0 ? rotation : 0, item.second);
        obs_source_release(item.first);
    }
}

void on_mixed_audio(void *, size_t, audio_data *audio)
{
    const float *samples = reinterpret_cast<const float *>(audio->data[0]);
    float peak = 0;
    for (size_t i = 0; samples && i < size_t(audio->frames) * 2; ++i) peak = std::max(peak, std::fabs(samples[i]));
    mixed_peak.store(peak, std::memory_order_relaxed);
    mixed_frames.fetch_add(audio->frames, std::memory_order_relaxed);
}
void remove_capture(Capture &capture)
{
    for (auto &item : capture.items) {
        if (item) { obs_sceneitem_remove(item); obs_sceneitem_release(item); }
        item = nullptr;
    }
    capture.items.clear();
    if (capture.source) obs_source_release(capture.source);
    capture.source = nullptr;
    capture.pixels.clear();
    capture.width = capture.height = 0;
}
void fit(Capture &capture, int kind, int width, int height, int rotation, bool mirror)
{
    if (capture.width == width && capture.height == height && capture.rotation == rotation && capture.mirror == mirror) return;
    capture.width = width; capture.height = height; capture.rotation = rotation; capture.mirror = mirror;
    bool rotated = rotation == 90 || rotation == 270;
    float actual_width = float(rotated ? height : width), actual_height = float(rotated ? width : height);
    float factor = kind == 0 ? std::min(430.0f / actual_width, 300.0f / actual_height) : std::min(1280.0f / actual_width, 720.0f / actual_height);
    vec2 scale{}, position{};
    vec2_set(&scale, factor * (mirror && !rotated ? -1.0f : 1.0f), factor * (mirror && rotated ? -1.0f : 1.0f));
    vec2_set(&position, kind == 0 ? 1040.0f : 640.0f, kind == 0 ? 550.0f : 360.0f);
    for (auto item : capture.items) {
        if (!item) continue;
        obs_sceneitem_set_alignment(item, OBS_ALIGN_CENTER);
        obs_sceneitem_set_rot(item, float(rotation));
        obs_sceneitem_set_scale(item, &scale);
        obs_sceneitem_set_pos(item, &position);
    }
}
}

void android_captures_register()
{
    std::lock_guard<std::mutex> guard(source_mutex);
    if (sources_registered) return;
    for (int i = 0; i < kNumCaptureKinds; ++i) {
        obs_source_info info{};
        info.id = ids[i]; info.type = OBS_SOURCE_TYPE_INPUT;
        if (i == 0) {
            info.output_flags = OBS_SOURCE_VIDEO | OBS_SOURCE_CUSTOM_DRAW | OBS_SOURCE_SRGB;
            info.get_width = gpu_source_get_width;
            info.get_height = gpu_source_get_height;
            info.video_render = gpu_source_video_render;
        } else if (i == 2) {
            info.output_flags = OBS_SOURCE_VIDEO | OBS_SOURCE_CUSTOM_DRAW | OBS_SOURCE_SRGB | OBS_SOURCE_AUDIO;
            info.get_width = gpu_source_get_width;
            info.get_height = gpu_source_get_height;
            info.video_render = gpu_source_video_render;
        } else if (i == 4) {
            info.output_flags = OBS_SOURCE_ASYNC_VIDEO;
        } else {
            info.output_flags = OBS_SOURCE_AUDIO;
        }
        info.get_name = source_name;
        info.create = source_create;
        info.destroy = source_destroy;
        info.update = source_update;
        info.show = source_show;
        info.hide = source_hide;
        info.get_defaults2 = source_defaults;
        info.get_properties2 = source_properties2;
        info.type_data = reinterpret_cast<void *>(uintptr_t(i));
        info.icon_type = (i == 0 || i == 4) ? OBS_ICON_TYPE_CAMERA : i == 2 ? OBS_ICON_TYPE_DESKTOP_CAPTURE :
                         i == 3 ? OBS_ICON_TYPE_AUDIO_OUTPUT : OBS_ICON_TYPE_AUDIO_INPUT;
        obs_register_source(&info);
        captures[i].frames = 0; captures[i].timestamp = 0; captures[i].peak = 0;
    }
    sources_registered = true;
}

void android_captures_initialize(obs_scene_t *first, obs_scene_t *second)
{
    android_captures_register();
    std::lock_guard<std::mutex> guard(source_mutex);
    scenes = {first, second};
    mixed_frames = 0; mixed_peak = 0;
    if (!audio_callback_registered) {
        audio_convert_info audio{};
        audio.samples_per_sec = 48000; audio.format = AUDIO_FORMAT_FLOAT; audio.speakers = SPEAKERS_STEREO;
        obs_add_raw_audio_callback(0, &audio, on_mixed_audio, nullptr);
        audio_callback_registered = true;
    }
}
void android_captures_shutdown()
{
    android_uvc_stop_all();
    if (audio_callback_registered) obs_remove_raw_audio_callback(0, on_mixed_audio, nullptr);
    audio_callback_registered = false;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        for (auto &capture : captures) {
            capture.gpu.clear_pending();
            remove_capture(capture);
        }
        scenes.clear();
        sources_registered = false;
    }
    if (obs_initialized()) {
        obs_enter_graphics();
        captures[0].gpu.destroy_gl_resources();
        captures[2].gpu.destroy_gl_resources();
        if (g_oes.prog) { glDeleteProgram(g_oes.prog); g_oes.prog = 0; }
        if (g_oes.vao) { glDeleteVertexArrays(1, &g_oes.vao); g_oes.vao = 0; }
        g_oes.initialized = false;
        g_oes.supported = false;
        obs_leave_graphics();
    }
}
void android_captures_add_scene(obs_scene_t *scene)
{
    std::lock_guard<std::mutex> guard(source_mutex);
    scenes.push_back(scene);
    for (auto &capture : captures) {
        if (!capture.source) continue;
        obs_sceneitem_t *item = obs_scene_find_source(scene, obs_source_get_name(capture.source));
        bool added = !item;
        if (!item) item = obs_scene_add(scene, capture.source);
        if (item) {
            obs_sceneitem_addref(item);
            if (added && !capture.items.empty() && capture.items[0]) {
                obs_transform_info info{}; obs_sceneitem_get_info2(capture.items[0], &info); obs_sceneitem_set_info2(item, &info);
            }
        }
        capture.items.push_back(item);
    }
}
void android_captures_remove_scene(obs_scene_t *scene)
{
    std::lock_guard<std::mutex> guard(source_mutex);
    auto found = std::find(scenes.begin(), scenes.end(), scene);
    if (found == scenes.end()) return;
    size_t index = size_t(found - scenes.begin());
    for (auto &capture : captures) {
        if (index >= capture.items.size()) continue;
        if (auto item = capture.items[index]) { obs_sceneitem_remove(item); obs_sceneitem_release(item); }
        capture.items.erase(capture.items.begin() + index);
    }
    scenes.erase(found);
}
obs_data_t *android_capture_stats()
{
    obs_data_t *stats = obs_data_create();
    obs_source_t *references[kNumCaptureKinds]{};
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        for (int i = 0; i < kNumCaptureKinds; ++i) {
            obs_data_t *entry = obs_data_create();
            obs_source_t *raw = captures[i].source ? captures[i].source :
                                (!captures[i].active_sources.empty() ? captures[i].active_sources.front() : nullptr);
            obs_source_t *active = raw ? obs_source_get_ref(raw) : nullptr;
            obs_data_set_bool(entry, "enabled", active != nullptr);
            obs_data_set_int(entry, "frames", int64_t(captures[i].frames));
            obs_data_set_int(entry, "timestamp_ns", int64_t(captures[i].timestamp));
            obs_data_set_int(entry, "width", captures[i].width); obs_data_set_int(entry, "height", captures[i].height);
            obs_data_set_double(entry, "peak", captures[i].peak);
            obs_data_set_double(entry, "volume", active ? obs_source_get_volume(active) : 1.0);
            obs_data_set_bool(entry, "muted", active && obs_source_muted(active));
            references[i] = active;
            obs_data_set_obj(stats, ids[i], entry); obs_data_release(entry);
        }
    }
    for (auto *source : references) {
        if (source) obs_source_release(source);
    }
    obs_data_set_int(stats, "mixed_frames", int64_t(mixed_frames.load()));
    obs_data_set_double(stats, "mixed_peak", mixed_peak.load());
    return stats;
}

void android_uvc_deliver_yuy2(const uint8_t *data, uint32_t width, uint32_t height, uint64_t timestamp_ns)
{
    if (!data || !width || !height) return;
    std::vector<obs_source_t *> targets;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        auto &capture = captures[4];
        if (capture.active_sources.empty()) return;
        targets.reserve(capture.active_sources.size());
        for (obs_source_t *src : capture.active_sources) {
            if (src) {
                obs_source_t *ref = obs_source_get_ref(src);
                if (ref) targets.push_back(ref);
            }
        }
        capture.width = int(width); capture.height = int(height);
        capture.frames++; capture.timestamp = timestamp_ns;
    }
    if (targets.empty()) return;
    obs_source_frame frame{};
    frame.data[0] = const_cast<uint8_t *>(data);
    frame.linesize[0] = width * 2;
    frame.width = width;
    frame.height = height;
    frame.timestamp = timestamp_ns;
    frame.format = VIDEO_FORMAT_YUY2;
    frame.full_range = false;
    video_format_get_parameters(height >= 720 ? VIDEO_CS_709 : VIDEO_CS_601, VIDEO_RANGE_PARTIAL,
                                frame.color_matrix, frame.color_range_min, frame.color_range_max);
    for (obs_source_t *src : targets) {
        obs_source_output_video(src, &frame);
        obs_source_release(src);
    }
}

void android_uvc_deliver_i420(const uint8_t *y, const uint8_t *u, const uint8_t *v,
                              uint32_t y_stride, uint32_t u_stride, uint32_t v_stride,
                              uint32_t width, uint32_t height, uint64_t timestamp_ns)
{
    if (!y || !u || !v || !width || !height) return;
    std::vector<obs_source_t *> targets;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        auto &capture = captures[4];
        if (capture.active_sources.empty()) return;
        targets.reserve(capture.active_sources.size());
        for (obs_source_t *src : capture.active_sources) {
            if (src) {
                obs_source_t *ref = obs_source_get_ref(src);
                if (ref) targets.push_back(ref);
            }
        }
        capture.width = int(width); capture.height = int(height);
        capture.frames++; capture.timestamp = timestamp_ns;
    }
    if (targets.empty()) return;
    obs_source_frame frame{};
    frame.data[0] = const_cast<uint8_t *>(y);
    frame.data[1] = const_cast<uint8_t *>(u);
    frame.data[2] = const_cast<uint8_t *>(v);
    frame.linesize[0] = y_stride;
    frame.linesize[1] = u_stride;
    frame.linesize[2] = v_stride;
    frame.width = width;
    frame.height = height;
    frame.timestamp = timestamp_ns;
    frame.format = VIDEO_FORMAT_I420;
    frame.full_range = true;
    video_format_get_parameters(height >= 720 ? VIDEO_CS_709 : VIDEO_CS_601, VIDEO_RANGE_FULL,
                                frame.color_matrix, frame.color_range_min, frame.color_range_max);
    for (obs_source_t *src : targets) {
        obs_source_output_video(src, &frame);
        obs_source_release(src);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_registerCaptureHost(JNIEnv *env, jclass, jclass hostClass)
{
    std::lock_guard<std::mutex> guard(source_mutex);
    env->GetJavaVM(&g_jvm);
    if (!g_host_class) {
        g_host_class = static_cast<jclass>(env->NewGlobalRef(hostClass));
    }
    g_host_on_capture_cfg = env->GetStaticMethodID(g_host_class, "onNativeCaptureConfigured", "(IZLjava/lang/String;FI)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_host_on_capture_cfg = nullptr;
    }
    g_host_enum_devices = env->GetStaticMethodID(g_host_class, "enumerateDevices", "(I)Ljava/lang/String;");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_host_enum_devices = nullptr;
    }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_org_obsandroid_port_NativeBridge_enableCapture(JNIEnv *, jclass, jint kind, jboolean enabled)
{
    AndroidCallbackScope callback;
    if (!callback) return false;
    if (kind < 0 || kind >= kNumCaptureKinds) return false;
    std::unique_lock<std::mutex> guard(source_mutex);
    auto &capture = captures[kind];
    if (!enabled) { remove_capture(capture); return true; }
    if (capture.source) return true;
    if (!obs_initialized()) return false;
    if (scenes.empty()) {
        if (kind == 3) return !capture.active_sources.empty() || !captures[2].active_sources.empty();
        return !capture.active_sources.empty();
    }
    guard.unlock();
    obs_source_t *created = obs_source_create(ids[kind], names[kind], nullptr, nullptr);
    guard.lock();
    capture.source = created;
    if (!capture.source) return false;
    capture.frames = 0; capture.timestamp = 0; capture.peak = 0;
    if (kind == 4) obs_source_set_async_unbuffered(capture.source, true);
    if (kind == 1 || kind == 3) obs_source_set_audio_mixers(capture.source, 1);
    capture.items.resize(scenes.size());
    for (size_t i = 0; i < scenes.size(); ++i) {
        capture.items[i] = obs_scene_find_source(scenes[i], names[kind]);
        if (!capture.items[i]) capture.items[i] = obs_scene_add(scenes[i], capture.source);
        if (capture.items[i]) {
            obs_sceneitem_addref(capture.items[i]);
            if (kind == 2) obs_sceneitem_set_order_position(capture.items[i], 1);
        }
    }
    return true;
}
float android_sources_get_mic_level()
{
    return g_mic_rms_level.load(std::memory_order_relaxed);
}

extern "C" __attribute__((visibility("default"))) void
obs_android_test_push_ahb(int kind, AHardwareBuffer *ahb, int width, int height)
{
    push_ahb_capture_frame(kind, ahb, width, height, 0, 0, false);
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_cameraHardwareBuffer(JNIEnv *env, jclass, jobject buffer,
    jint width, jint height, jlong timestamp, jint rotation, jboolean mirror)
{
    AndroidCallbackScope callback;
    if (!callback) return ;
    if (!buffer) return;
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    if (!ahb) return;
    push_ahb_capture_frame(0, ahb, width, height, uint64_t(timestamp), rotation, mirror);
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_screenHardwareBuffer(JNIEnv *env, jclass, jobject buffer,
    jint width, jint height, jlong timestamp)
{
    AndroidCallbackScope callback;
    if (!callback) return ;
    if (!buffer) return;
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    if (!ahb) return;
    push_ahb_capture_frame(2, ahb, width, height, uint64_t(timestamp), 0, false);
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_cameraFrame(JNIEnv *env, jclass, jobject y, jobject u, jobject v,
    jint y_stride, jint u_stride, jint v_stride, jint u_pixel_stride, jint v_pixel_stride,
    jint width, jint height, jlong timestamp, jint rotation, jboolean mirror, jboolean full_range, jboolean rec709)
{
    AndroidCallbackScope callback;
    if (!callback) return ;
    if (width <= 0 || height <= 0 || width > 4096 || height > 4096 || width % 2 || height % 2) return;
    uint8_t *planes[] = {static_cast<uint8_t *>(env->GetDirectBufferAddress(y)), static_cast<uint8_t *>(env->GetDirectBufferAddress(u)), static_cast<uint8_t *>(env->GetDirectBufferAddress(v))};
    jlong capacities[] = {env->GetDirectBufferCapacity(y), env->GetDirectBufferCapacity(u), env->GetDirectBufferCapacity(v)};
    int strides[] = {y_stride, u_stride, v_stride}, pixel_strides[] = {1, u_pixel_stride, v_pixel_stride};
    if (!planes[0] || !planes[1] || !planes[2] || strides[0] < width || strides[1] <= 0 || strides[2] <= 0 ||
        pixel_strides[1] <= 0 || pixel_strides[2] <= 0) return;

    std::vector<std::pair<obs_source_t *, int>> to_autofit;
    bool has_consumers = false;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        auto &capture = captures[0];
        if (capture.active_sources.empty() && !capture.source) return;
        has_consumers = true;
        fit(capture, 0, width, height, rotation, mirror);
        for (SourceInstance *inst : capture.active_instances) {
            if (inst && inst->auto_fit && inst->source &&
                (inst->last_fit_w != width || inst->last_fit_h != height || inst->last_fit_rot != rotation)) {
                inst->last_fit_w = width;
                inst->last_fit_h = height;
                inst->last_fit_rot = rotation;
                obs_source_t *ref = obs_source_get_ref(inst->source);
                if (ref) to_autofit.emplace_back(ref, inst->fit_mode);
            }
        }
        capture.frames++; capture.timestamp = uint64_t(timestamp);
    }

    if (has_consumers &&
        int64_t(height - 1) * strides[0] + width <= capacities[0] &&
        int64_t(height / 2 - 1) * strides[1] + int64_t(width / 2 - 1) * pixel_strides[1] < capacities[1] &&
        int64_t(height / 2 - 1) * strides[2] + int64_t(width / 2 - 1) * pixel_strides[2] < capacities[2]) {
        std::vector<uint8_t> rgba(size_t(width) * size_t(height) * 4);
        const float kr = rec709 ? 1.5748f : 1.402f;
        const float kgu = rec709 ? 0.187324f : 0.344136f;
        const float kgv = rec709 ? 0.468124f : 0.714136f;
        const float kb = rec709 ? 1.8556f : 1.772f;
        const float y_off = full_range ? 0.0f : 16.0f;
        const float y_sc = full_range ? 1.0f : (255.0f / 219.0f);
        const float uv_sc = full_range ? 1.0f : (255.0f / 224.0f);
        for (int row = 0; row < height; ++row) {
            const uint8_t *yp = planes[0] + size_t(row) * strides[0];
            const uint8_t *up = planes[1] + size_t(row / 2) * strides[1];
            const uint8_t *vp = planes[2] + size_t(row / 2) * strides[2];
            uint8_t *dst = rgba.data() + size_t(row) * width * 4;
            for (int col = 0; col < width; ++col) {
                const float yv = (float(yp[col]) - y_off) * y_sc;
                const float uv = (float(up[(col / 2) * pixel_strides[1]]) - 128.0f) * uv_sc;
                const float vv = (float(vp[(col / 2) * pixel_strides[2]]) - 128.0f) * uv_sc;
                dst[col * 4 + 0] = uint8_t(std::clamp(int(yv + kr * vv), 0, 255));
                dst[col * 4 + 1] = uint8_t(std::clamp(int(yv - kgu * uv - kgv * vv), 0, 255));
                dst[col * 4 + 2] = uint8_t(std::clamp(int(yv + kb * uv), 0, 255));
                dst[col * 4 + 3] = 255;
            }
        }
        auto &gpu = captures[0].gpu;
        {
            std::lock_guard<std::mutex> lock(gpu.mutex);
            gpu.pending_rgba.swap(rgba);
            gpu.cpu_w = width;
            gpu.cpu_h = height;
            gpu.has_cpu_pending = true;
        }
        gpu.reported_w.store(uint32_t(width), std::memory_order_relaxed);
        gpu.reported_h.store(uint32_t(height), std::memory_order_relaxed);
    }
    for (auto &item : to_autofit) {
        apply_auto_fit_to_source(item.first, rotation, item.second);
        obs_source_release(item.first);
    }
}
extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_screenFrame(JNIEnv *env, jclass, jobject rgba, jint stride, jint width, jint height, jlong timestamp)
{
    AndroidCallbackScope callback;
    if (!callback) return ;
    if (width <= 0 || height <= 0 || stride < width * 4) return;
    auto *pixels = static_cast<uint8_t *>(env->GetDirectBufferAddress(rgba));
    if (!pixels || int64_t(height - 1) * stride + int64_t(width) * 4 > env->GetDirectBufferCapacity(rgba)) return;

    std::vector<std::pair<obs_source_t *, int>> to_autofit;
    bool has_consumers = false;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        auto &capture = captures[2];
        if (capture.active_sources.empty() && !capture.source) return;
        has_consumers = true;
        fit(capture, 2, width, height, 0, false);
        for (SourceInstance *inst : capture.active_instances) {
            if (inst && inst->auto_fit && inst->source &&
                (inst->last_fit_w != width || inst->last_fit_h != height)) {
                inst->last_fit_w = width;
                inst->last_fit_h = height;
                obs_source_t *ref = obs_source_get_ref(inst->source);
                if (ref) to_autofit.emplace_back(ref, inst->fit_mode);
            }
        }
        capture.frames++; capture.timestamp = uint64_t(timestamp);
    }
    if (has_consumers) {
        std::vector<uint8_t> packed(size_t(width) * size_t(height) * 4);
        for (int row = 0; row < height; ++row) {
            std::memcpy(packed.data() + size_t(row) * width * 4, pixels + size_t(row) * stride, size_t(width) * 4);
        }
        auto &gpu = captures[2].gpu;
        {
            std::lock_guard<std::mutex> lock(gpu.mutex);
            gpu.pending_rgba.swap(packed);
            gpu.cpu_w = width;
            gpu.cpu_h = height;
            gpu.has_cpu_pending = true;
        }
        gpu.reported_w.store(uint32_t(width), std::memory_order_relaxed);
        gpu.reported_h.store(uint32_t(height), std::memory_order_relaxed);
    }
    for (auto &item : to_autofit) {
        apply_auto_fit_to_source(item.first, 0, item.second);
        obs_source_release(item.first);
    }
}
extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_audioFrame(JNIEnv *env, jclass, jint kind, jobject pcm, jint bytes, jint channels, jint sample_rate, jlong timestamp)
{
    AndroidCallbackScope callback;
    if (!callback) return ;
    if (kind != 1 && kind != 3) return;
    if (bytes <= 0 || channels < 1 || channels > 2 || bytes % (2 * channels)) return;
    const auto *samples = static_cast<const int16_t *>(env->GetDirectBufferAddress(pcm));
    if (!samples || bytes > env->GetDirectBufferCapacity(pcm)) return;
    obs_source_audio audio{};
    audio.data[0] = reinterpret_cast<const uint8_t *>(samples);
    audio.frames = bytes / (2 * channels); audio.samples_per_sec = sample_rate > 0 ? uint32_t(sample_rate) : 48000;
    audio.format = AUDIO_FORMAT_16BIT; audio.speakers = channels == 1 ? SPEAKERS_MONO : SPEAKERS_STEREO;
    audio.timestamp = uint64_t(timestamp);

    const int total_samples = bytes / 2;
    int max_abs = 0;
    const bool need_rms = (kind == 1) && android_vtuber_has_active();
    if (need_rms) {
        double sum_sq = 0.0;
        for (int i = 0; i < total_samples; ++i) {
            const int s = samples[i];
            const int as = s < 0 ? -s : s;
            if (as > max_abs) max_abs = as;
            const double v = double(s) * (1.0 / 32768.0);
            sum_sq += v * v;
        }
        const float rms = total_samples > 0 ? float(std::sqrt(sum_sq / double(total_samples))) : 0.0f;
        g_mic_rms_level.store(rms, std::memory_order_relaxed);
    } else {
        // Fast integer peak scan when no VTuber/PNGTuber needs RMS lip-sync
        for (int i = 0; i < total_samples; ++i) {
            const int s = samples[i];
            const int as = s < 0 ? -s : s;
            if (as > max_abs) max_abs = as;
        }
    }
    const float peak = float(max_abs) * (1.0f / 32768.0f);

    std::vector<obs_source_t *> targets;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        auto &capture = captures[kind];
        if (kind == 3) {
            bool delivered_to_screen = false;
            for (SourceInstance *inst : captures[2].active_instances) {
                if (inst && inst->source && inst->capture_audio) {
                    obs_source_t *ref = obs_source_get_ref(inst->source);
                    if (ref) {
                        targets.push_back(ref);
                        delivered_to_screen = true;
                    }
                }
            }
            if (!delivered_to_screen) {
                for (obs_source_t *src : capture.active_sources) {
                    if (src) {
                        obs_source_t *ref = obs_source_get_ref(src);
                        if (ref) targets.push_back(ref);
                    }
                }
            }
        } else {
            for (obs_source_t *src : capture.active_sources) {
                if (src) {
                    obs_source_t *ref = obs_source_get_ref(src);
                    if (ref) targets.push_back(ref);
                }
            }
        }
        capture.peak = peak; capture.frames += audio.frames; capture.timestamp = audio.timestamp;
    }
    for (obs_source_t *src : targets) {
        obs_source_output_audio(src, &audio);
        obs_source_release(src);
    }
}
extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_audioFrameForDevice(
    JNIEnv *env, jclass, jstring jDeviceSpec, jobject pcm, jint bytes, jint channels, jint sample_rate, jlong timestamp)
{
    AndroidCallbackScope callback;
    if (!callback) return ;
    if (bytes <= 0 || channels < 1 || channels > 2 || bytes % (2 * channels)) return;
    const auto *samples = static_cast<const int16_t *>(env->GetDirectBufferAddress(pcm));
    if (!samples || bytes > env->GetDirectBufferCapacity(pcm)) return;

    std::string spec = "default";
    if (jDeviceSpec) {
        const char *utf = env->GetStringUTFChars(jDeviceSpec, nullptr);
        if (utf) {
            if (*utf) spec = utf;
            env->ReleaseStringUTFChars(jDeviceSpec, utf);
        }
    }

    obs_source_audio audio{};
    audio.data[0] = reinterpret_cast<const uint8_t *>(samples);
    audio.frames = bytes / (2 * channels);
    audio.samples_per_sec = sample_rate > 0 ? uint32_t(sample_rate) : 48000;
    audio.format = AUDIO_FORMAT_16BIT;
    audio.speakers = channels == 1 ? SPEAKERS_MONO : SPEAKERS_STEREO;
    audio.timestamp = uint64_t(timestamp);

    const int total_samples = bytes / 2;
    int max_abs = 0;
    const bool need_rms = android_vtuber_has_active();
    if (need_rms) {
        double sum_sq = 0.0;
        for (int i = 0; i < total_samples; ++i) {
            const int s = samples[i];
            const int as = s < 0 ? -s : s;
            if (as > max_abs) max_abs = as;
            const double v = double(s) * (1.0 / 32768.0);
            sum_sq += v * v;
        }
        const float rms = total_samples > 0 ? float(std::sqrt(sum_sq / double(total_samples))) : 0.0f;
        g_mic_rms_level.store(rms, std::memory_order_relaxed);
    } else {
        for (int i = 0; i < total_samples; ++i) {
            const int s = samples[i];
            const int as = s < 0 ? -s : s;
            if (as > max_abs) max_abs = as;
        }
    }
    const float peak = float(max_abs) * (1.0f / 32768.0f);

    std::vector<obs_source_t *> targets;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        auto &capture = captures[1];
        for (SourceInstance *inst : capture.active_instances) {
            if (!inst || !inst->source || !inst->enabled) continue;
            const std::string instSpec = inst->device_id.empty() ? "default" : inst->device_id;
            if (instSpec == spec) {
                obs_source_t *ref = obs_source_get_ref(inst->source);
                if (ref) targets.push_back(ref);
            }
        }
        if (targets.empty() && capture.active_instances.empty()) {
            // Fallback for headless/test capture without SourceInstance
            for (obs_source_t *src : capture.active_sources) {
                if (src) {
                    obs_source_t *ref = obs_source_get_ref(src);
                    if (ref) targets.push_back(ref);
                }
            }
        }
        capture.peak = peak;
        capture.frames += audio.frames;
        capture.timestamp = audio.timestamp;
    }
    for (obs_source_t *src : targets) {
        obs_source_output_audio(src, &audio);
        obs_source_release(src);
    }
}
extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_captureGain(JNIEnv *, jclass, jint kind, jfloat gain, jboolean mute)
{
    AndroidCallbackScope callback;
    if (!callback) return ;
    if (kind < 0 || kind >= kNumCaptureKinds) return;
    std::vector<obs_source_t *> targets;
    {
        std::lock_guard<std::mutex> guard(source_mutex);
        for (obs_source_t *src : captures[kind].active_sources) {
            if (src) {
                obs_source_t *ref = obs_source_get_ref(src);
                if (ref) targets.push_back(ref);
            }
        }
    }
    for (obs_source_t *src : targets) {
        obs_source_set_volume(src, std::clamp(gain, 0.0f, 2.0f));
        obs_source_set_muted(src, mute);
        obs_source_release(src);
    }
}
