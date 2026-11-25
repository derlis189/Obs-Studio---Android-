// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-browser.h"
#include <obs-module.h>
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>

namespace {
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
    int64_t id{};
    obs_source_t *source{};
    bool is_local_file{false};
    std::string url;
    std::string local_file;
    std::string css;
    int width{1280};
    int height{720};
    int fps{30};
    bool transparent{true};
    bool shutdown_when_hidden{false};
    bool showing{true};
};

std::unordered_map<int64_t, BrowserSourceInstance *> g_instances;

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
        jboolean(inst->transparent), jCss, jboolean(visible), jboolean(inst->shutdown_when_hidden));
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
    auto *inst = static_cast<BrowserSourceInstance *>(data);
    if (!inst) return false;
    call_java_id_only(g_mid_interact, inst->id);
    return false;
}

bool on_refresh_clicked(obs_properties_t *, obs_property_t *, void *data)
{
    auto *inst = static_cast<BrowserSourceInstance *>(data);
    if (!inst) return false;
    call_java_id_bool(g_mid_reload, inst->id, true);
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
    auto *inst = new BrowserSourceInstance{};
    inst->id = ++g_browser_id_seq;
    inst->source = source;
    inst->showing = obs_source_showing(source);
    apply_settings(inst, settings);
    obs_source_set_async_unbuffered(source, true);
    {
        std::lock_guard<std::mutex> lock(g_browser_mutex);
        g_instances[inst->id] = inst;
    }
    push_config_to_java(inst);
    return inst;
}

void browser_destroy(void *data)
{
    auto *inst = static_cast<BrowserSourceInstance *>(data);
    if (!inst) return;
    int64_t id = inst->id;
    {
        std::lock_guard<std::mutex> lock(g_browser_mutex);
        g_instances.erase(id);
    }
    call_java_id_only(g_mid_destroy, id);
    delete inst;
}

void browser_show(void *data)
{
    auto *inst = static_cast<BrowserSourceInstance *>(data);
    if (!inst) return;
    inst->showing = true;
    call_java_id_bool(g_mid_set_visible, inst->id, true);
}

void browser_hide(void *data)
{
    auto *inst = static_cast<BrowserSourceInstance *>(data);
    if (!inst) return;
    inst->showing = false;
    call_java_id_bool(g_mid_set_visible, inst->id, false);
}
} // namespace

void android_browser_register()
{
    std::lock_guard<std::mutex> lock(g_browser_mutex);
    if (g_browser_registered) return;

    obs_source_info info{};
    info.id = "browser_source";
    info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_DO_NOT_DUPLICATE;
    info.get_name = browser_get_name;
    info.create = browser_create;
    info.destroy = browser_destroy;
    info.update = browser_update;
    info.get_defaults = browser_get_defaults;
    info.get_properties = browser_get_properties;
    info.show = browser_show;
    info.hide = browser_hide;
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
    g_mid_create_update = env->GetStaticMethodID(
        g_browser_host_class, "createOrUpdate", "(JLjava/lang/String;IIIZLjava/lang/String;ZZ)V");
    g_mid_set_visible = env->GetStaticMethodID(g_browser_host_class, "setVisible", "(JZ)V");
    g_mid_reload = env->GetStaticMethodID(g_browser_host_class, "reload", "(JZ)V");
    g_mid_interact = env->GetStaticMethodID(g_browser_host_class, "interact", "(J)V");
    g_mid_destroy = env->GetStaticMethodID(g_browser_host_class, "destroy", "(J)V");
    g_mid_destroy_all = env->GetStaticMethodID(g_browser_host_class, "destroyAll", "()V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, "ObsBrowserSource", "Failed to resolve BrowserSourceManager JNI methods");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_pushBrowserFrame(
    JNIEnv *env, jclass, jlong instanceId, jobject rgbaBuf, jint width, jint height, jlong timestampNs)
{
    if (!rgbaBuf || width <= 0 || height <= 0) return;
    auto *ptr = static_cast<uint8_t *>(env->GetDirectBufferAddress(rgbaBuf));
    jlong cap = env->GetDirectBufferCapacity(rgbaBuf);
    if (!ptr || cap < jlong(width) * jlong(height) * 4) return;

    obs_source_t *source = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_browser_mutex);
        auto it = g_instances.find(int64_t(instanceId));
        if (it != g_instances.end() && it->second && it->second->source) {
            source = obs_source_get_ref(it->second->source);
        }
    }
    if (!source) return;

    const size_t pixel_count = size_t(width) * size_t(height);
    for (size_t i = 0; i < pixel_count; ++i) {
        uint8_t *p = ptr + i * 4;
        uint8_t a = p[3];
        if (a > 0 && a < 255) {
            uint32_t r = (uint32_t(p[0]) * 255u + (a >> 1)) / a;
            uint32_t g = (uint32_t(p[1]) * 255u + (a >> 1)) / a;
            uint32_t b = (uint32_t(p[2]) * 255u + (a >> 1)) / a;
            p[0] = uint8_t(r > 255u ? 255u : r);
            p[1] = uint8_t(g > 255u ? 255u : g);
            p[2] = uint8_t(b > 255u ? 255u : b);
        }
    }

    obs_source_frame frame{};
    frame.width = uint32_t(width);
    frame.height = uint32_t(height);
    frame.format = VIDEO_FORMAT_RGBA;
    frame.timestamp = uint64_t(timestampNs);
    frame.data[0] = ptr;
    frame.linesize[0] = uint32_t(width) * 4u;
    frame.full_range = true;
    obs_source_output_video(source, &frame);
    obs_source_release(source);
}
