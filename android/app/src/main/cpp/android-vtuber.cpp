#include "android-session-gate.h"
#include "android-rgba-frame.h"
// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-vtuber.h"
#include "android-sources.h"
#include <obs-module.h>
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>

namespace {
std::mutex g_vt_mutex;
JavaVM *g_vt_jvm = nullptr;
jclass g_vt_host_class = nullptr;
jmethodID g_mid_vt_update = nullptr;
jmethodID g_mid_vt_destroy = nullptr;
jmethodID g_mid_vt_visible = nullptr;
jmethodID g_mid_vt_destroy_all = nullptr;
jmethodID g_mid_vt_pick_cubism = nullptr;
std::atomic<int64_t> g_vt_seq{0};
std::atomic<int> g_vt_active_count{0};
bool g_vt_registered = false;

struct VtuberInstance {
    AndroidRgbaFrame frame;
    int64_t id{};
    obs_source_t *source{};
    std::string mode{"png"};
    int width{720};
    int height{720};
    int fps{30};
    std::string config_json;
};

std::unordered_map<int64_t, VtuberInstance *> g_vt_instances;

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

const char *vtuber_get_name(void *) { return "Avatar VTuber (PNG / Live2D / VRM)"; }

void vtuber_get_defaults(obs_data_t *s)
{
    obs_data_set_default_string(s, "vt_mode", "png");
    obs_data_set_default_string(s, "veadomini_file", "");
    obs_data_set_default_int(s, "veadomini_state", 0);
    obs_data_set_default_string(s, "png_idle", "");
    obs_data_set_default_string(s, "png_talk", "");
    obs_data_set_default_string(s, "png_blink", "");
    obs_data_set_default_string(s, "png_blink_talk", "");
    obs_data_set_default_bool(s, "png_bounce", true);
    obs_data_set_default_double(s, "voice_threshold", 0.04);
    obs_data_set_default_string(s, "vrm_file", "");
    obs_data_set_default_double(s, "vrm_cam_dist", 1.15);
    obs_data_set_default_double(s, "vrm_cam_y", 1.38);
    obs_data_set_default_string(s, "live2d_file", "");
    obs_data_set_default_bool(s, "face_tracking", true);
    obs_data_set_default_double(s, "head_sensitivity", 1.0);
    obs_data_set_default_bool(s, "hand_tracking", true);
    obs_data_set_default_bool(s, "lip_sync", true);
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

    for (const char *k : {"veadomini_file", "veadomini_state", "png_idle", "png_talk", "png_blink", "png_blink_talk", "png_bounce"}) {
        if (auto *p = obs_properties_get(props, k)) obs_property_set_visible(p, is_png);
    }
    for (const char *k : {"live2d_info", "live2d_file"}) {
        if (auto *p = obs_properties_get(props, k)) obs_property_set_visible(p, is_l2d);
    }
    for (const char *k : {"vrm_info", "vrm_file", "vrm_cam_dist", "vrm_cam_y"}) {
        if (auto *p = obs_properties_get(props, k)) obs_property_set_visible(p, is_vrm);
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
    obs_property_list_add_string(mode, "Live2D (.zip / .model3.json + seguimiento facial)", "live2d");
    obs_property_list_add_string(mode, "VRM 3D (.vrm + seguimiento facial WebGL)", "vrm");
    obs_property_set_modified_callback(mode, on_vt_mode_modified);

    obs_properties_add_path(props, "veadomini_file", "Archivo .veadomini (Veadotube Mini)", OBS_PATH_FILE, "Todos los archivos (*);;Veadotube Mini (*.veadomini *.zip)", nullptr);
    obs_properties_add_int(props, "veadomini_state", "Estado / Expresión (.veadomini, 0=principal)", 0, 15, 1);
    obs_properties_add_path(props, "png_idle", "PNG Reposo (si no usas .veadomini)", OBS_PATH_FILE, "Todos los archivos (*);;Imágenes (*.png *.webp *.gif)", nullptr);
    obs_properties_add_path(props, "png_talk", "PNG Hablando (boca abierta)", OBS_PATH_FILE, "Todos los archivos (*);;Imágenes (*.png *.webp *.gif)", nullptr);
    obs_properties_add_path(props, "png_blink", "PNG Parpadeo (opcional)", OBS_PATH_FILE, "Todos los archivos (*);;Imágenes (*.png *.webp *.gif)", nullptr);
    obs_properties_add_path(props, "png_blink_talk", "PNG Hablando + parpadeo (opcional)", OBS_PATH_FILE, "Todos los archivos (*);;Imágenes (*.png *.webp *.gif)", nullptr);
    obs_properties_add_bool(props, "png_bounce", "Rebote suave al hablar");

    obs_properties_add_text(props, "live2d_info",
        "Live2D Cubism Core ya viene integrado. Selecciona el archivo .zip completo del modelo (recomendado, incluye .moc3 y texturas .png) o un .model3.json.",
        OBS_TEXT_INFO);
    obs_properties_add_path(props, "live2d_file", "Modelo Live2D (.zip / .model3.json / .moc3)", OBS_PATH_FILE, "Todos los archivos (*);;Live2D (*.zip *.json *.moc3)", nullptr);

    obs_properties_add_text(props, "vrm_info",
        "Usa modelos exportados en formato .vrm (en VRoid Studio usa 'Export as VRM', no el archivo de proyecto .vroid).",
        OBS_TEXT_INFO);
    obs_properties_add_path(props, "vrm_file", "Modelo 3D VRM (.vrm)", OBS_PATH_FILE, "Todos los archivos (*);;Modelos VRM (*.vrm *.glb)", nullptr);
    obs_properties_add_float_slider(props, "vrm_cam_dist", "Distancia de cámara VRM", 0.4, 3.0, 0.05);
    obs_properties_add_float_slider(props, "vrm_cam_y", "Altura de cámara VRM", 0.5, 2.0, 0.02);

    obs_properties_add_bool(props, "face_tracking", "Seguimiento facial en vivo (Cámara + VMC UDP :39539)");
    obs_properties_add_float_slider(props, "head_sensitivity", "Sensibilidad movimiento cabeza", 0.5, 2.5, 0.1);
    obs_properties_add_bool(props, "hand_tracking", "Seguimiento de manos (saludar con la mano)");
    obs_properties_add_bool(props, "lip_sync", "Sincronización labial con Micrófono");
    obs_properties_add_float_slider(props, "voice_threshold", "Umbral de voz (sensibilidad)", 0.01, 0.30, 0.01);
    obs_property_t *qual = obs_properties_add_list(props, "vt_quality", "Calidad de renderizado (VRM / Live2D)", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    obs_property_list_add_string(qual, "Alta (768p · Recomendada)", "high");
    obs_property_list_add_string(qual, "Ultra HD (1024p · Máxima nitidez)", "ultra");
    obs_property_list_add_string(qual, "Estándar (512p · Menor CPU)", "standard");
    obs_properties_add_int(props, "width", "Ancho", 256, 1280, 16);
    obs_properties_add_int(props, "height", "Alto", 256, 1280, 16);
    obs_properties_add_int(props, "fps", "FPS", 10, 60, 1);

    auto *inst = static_cast<VtuberInstance *>(data);
    if (inst) {
        obs_data_t *tmp = obs_data_create();
        obs_data_set_string(tmp, "vt_mode", inst->mode.c_str());
        on_vt_mode_modified(props, mode, tmp);
        obs_data_release(tmp);
    }
    return props;
}

void build_config_json(VtuberInstance *inst, obs_data_t *s)
{
    const char *m = obs_data_get_string(s, "vt_mode");
    inst->mode = (m && *m) ? m : "png";
    inst->width = int(obs_data_get_int(s, "width"));
    inst->height = int(obs_data_get_int(s, "height"));
    inst->fps = int(obs_data_get_int(s, "fps"));
    if (inst->width < 128) inst->width = 720;
    if (inst->height < 128) inst->height = 720;
    if (inst->fps < 5) inst->fps = 30;

    std::ostringstream js;
    js << "{"
       << "\"mode\":\"" << json_escape(inst->mode.c_str()) << "\","
       << "\"veadominiFile\":\"" << json_escape(obs_data_get_string(s, "veadomini_file")) << "\","
       << "\"veadoState\":" << obs_data_get_int(s, "veadomini_state") << ","
       << "\"pngIdle\":\"" << json_escape(obs_data_get_string(s, "png_idle")) << "\","
       << "\"pngTalk\":\"" << json_escape(obs_data_get_string(s, "png_talk")) << "\","
       << "\"pngBlink\":\"" << json_escape(obs_data_get_string(s, "png_blink")) << "\","
       << "\"pngBlinkTalk\":\"" << json_escape(obs_data_get_string(s, "png_blink_talk")) << "\","
       << "\"bounce\":" << (obs_data_get_bool(s, "png_bounce") ? "true" : "false") << ","
       << "\"voiceThreshold\":" << obs_data_get_double(s, "voice_threshold") << ","
       << "\"vrmFile\":\"" << json_escape(obs_data_get_string(s, "vrm_file")) << "\","
       << "\"camDist\":" << obs_data_get_double(s, "vrm_cam_dist") << ","
       << "\"camY\":" << obs_data_get_double(s, "vrm_cam_y") << ","
       << "\"live2dFile\":\"" << json_escape(obs_data_get_string(s, "live2d_file")) << "\","
       << "\"faceTracking\":" << (obs_data_get_bool(s, "face_tracking") ? "true" : "false") << ","
       << "\"headSensitivity\":" << obs_data_get_double(s, "head_sensitivity") << ","
       << "\"handTracking\":" << (obs_data_get_bool(s, "hand_tracking") ? "true" : "false") << ","
       << "\"quality\":\"" << json_escape(obs_data_get_string(s, "vt_quality")) << "\","
       << "\"lipSync\":" << (obs_data_get_bool(s, "lip_sync") ? "true" : "false")
       << "}";
    inst->config_json = js.str();
}

void vtuber_update(void *data, obs_data_t *s)
{
    auto *inst = static_cast<VtuberInstance *>(data);
    if (!inst) return;
    build_config_json(inst, s);
    push_vtuber_config(inst);
}

void *vtuber_create(obs_data_t *s, obs_source_t *source)
{
    auto *inst = new VtuberInstance{};
    inst->id = ++g_vt_seq;
    inst->source = source;
    build_config_json(inst, s);
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        g_vt_instances[inst->id] = inst;
        g_vt_active_count.store(int(g_vt_instances.size()), std::memory_order_release);
    }
    push_vtuber_config(inst);
    return inst;
}

void vtuber_destroy(void *data)
{
    auto *inst = static_cast<VtuberInstance *>(data);
    if (!inst) return;
    int64_t id = inst->id;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        g_vt_instances.erase(id);
        g_vt_active_count.store(int(g_vt_instances.size()), std::memory_order_release);
    }
    call_vtuber_id(g_mid_vt_destroy, id);
    delete inst;
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
    info.show = [](void *data) {
        auto *inst = static_cast<VtuberInstance *>(data);
        JNIEnv *env = nullptr; bool detach = false;
        if (!g_vt_jvm || !g_mid_vt_visible) return;
        if (g_vt_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (g_vt_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
            detach = true;
        }
        if (env) { env->CallStaticVoidMethod(g_vt_host_class, g_mid_vt_visible, jlong(inst->id), JNI_TRUE); if (env->ExceptionCheck()) env->ExceptionClear(); }
        if (detach) g_vt_jvm->DetachCurrentThread();
    };
    info.hide = [](void *data) {
        auto *inst = static_cast<VtuberInstance *>(data);
        JNIEnv *env = nullptr; bool detach = false;
        if (!g_vt_jvm || !g_mid_vt_visible) return;
        if (g_vt_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (g_vt_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
            detach = true;
        }
        if (env) { env->CallStaticVoidMethod(g_vt_host_class, g_mid_vt_visible, jlong(inst->id), JNI_FALSE); if (env->ExceptionCheck()) env->ExceptionClear(); }
        if (detach) g_vt_jvm->DetachCurrentThread();
    };
    info.update = vtuber_update;
    info.get_defaults = vtuber_get_defaults;
    info.get_properties = vtuber_get_properties;
    info.get_width = [](void *data) -> uint32_t { auto *inst = static_cast<VtuberInstance *>(data); return inst->frame.width() ? inst->frame.width() : uint32_t(inst->width); };
    info.get_height = [](void *data) -> uint32_t { auto *inst = static_cast<VtuberInstance *>(data); return inst->frame.height() ? inst->frame.height() : uint32_t(inst->height); };
    info.video_render = [](void *data, gs_effect_t *) { static_cast<VtuberInstance *>(data)->frame.render(); };
    info.icon_type = OBS_ICON_TYPE_CUSTOM;
    obs_register_source(&info);
    g_vt_registered = true;
}

void android_vtuber_shutdown()
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_vt_mutex);
        jvm = g_vt_jvm; cls = g_vt_host_class; mid = g_mid_vt_destroy_all;
        g_vt_instances.clear();
        g_vt_active_count.store(0, std::memory_order_release);
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
    if (env->ExceptionCheck()) env->ExceptionClear();
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

    std::lock_guard<std::mutex> lock(g_vt_mutex);
    auto it = g_vt_instances.find(int64_t(id));
    if (it != g_vt_instances.end()) it->second->frame.submit(ptr, uint32_t(width), uint32_t(height), uint32_t(width) * 4, true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_obsandroid_port_NativeBridge_pushVtuberBitmap(JNIEnv *env, jclass, jlong id, jobject bitmap)
{
    AndroidCallbackScope callback;
    if (!callback) return false;
    std::lock_guard<std::mutex> lock(g_vt_mutex);
    auto it = g_vt_instances.find(int64_t(id));
    return it != g_vt_instances.end() && it->second->frame.submitBitmap(env, bitmap);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_obsandroid_port_NativeBridge_pushVtuberArray(JNIEnv *env, jclass, jlong id, jbyteArray bytes,
    jint offset, jint width, jint height, jboolean flip)
{
    AndroidCallbackScope callback;
    if (!callback || !bytes || offset < 0 || width < 1 || height < 1 || width > 1280 || height > 1280) return false;
    if (int64_t(width) * height * 4 + offset != env->GetArrayLength(bytes)) return false;
    std::lock_guard<std::mutex> lock(g_vt_mutex);
    auto it = g_vt_instances.find(int64_t(id));
    if (it == g_vt_instances.end()) return false;
    jbyte *raw = env->GetByteArrayElements(bytes, nullptr);
    if (!raw) return false;
    const bool accepted = it->second->frame.submit(reinterpret_cast<uint8_t *>(raw) + offset,
        uint32_t(width), uint32_t(height), uint32_t(width) * 4, true, flip);
    env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
    return accepted;
}
