#include "android-session-gate.h"
// SPDX-License-Identifier: GPL-2.0-or-later
#include <jni.h>
#include <android/native_window_jni.h>
#include <obs.h>
#include <obs-module.h>
#include "android-sources.h"
#include "android-encoders.h"
#include "android-hw-video.h"
#include "android-browser.h"
#include "android-ndi.h"
#include "android-multistream.h"
#include "android-iso-replay.h"
#include "android-vtuber.h"
#include "android-graphics.h"
#include "android-srt.h"
#include "android-move-transition.h"
#include "android-frame-style-filter.h"
#include "android-bg-removal-filter.h"
#include "android-vmix-color-filter.h"
#include <GLES3/gl3.h>
#include <util/platform-android.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <dlfcn.h>
#include <vector>

static std::atomic<bool (*)(void)> g_android_back_handler{nullptr};
static std::mutex g_text_host_mutex;
static JavaVM *g_activity_jvm = nullptr;
static jclass g_activity_class = nullptr;
static jmethodID g_open_text_method = nullptr;
static jmethodID g_resolve_uri_method = nullptr;
static jmethodID g_default_rec_path_method = nullptr;
static jmethodID g_pick_dir_method = nullptr;
static jmethodID g_rec_finished_method = nullptr;
static jmethodID g_install_plugin_method = nullptr;
static jmethodID g_open_web_remote_method = nullptr;
static jmethodID g_open_graphics_panel_method = nullptr;
static jmethodID g_toggle_floating_method = nullptr;
static jmethodID g_list_srt_urls_method = nullptr;
static jmethodID g_broadcast_keepalive_method = nullptr;
static jmethodID g_device_health_method = nullptr;
static jmethodID g_hw_profile_method = nullptr;
static jmethodID g_zip_dir_method = nullptr;
static jmethodID g_unzip_dir_method = nullptr;
static jmethodID g_list_ext_displays_method = nullptr;
static jmethodID g_start_ext_display_method = nullptr;
static jmethodID g_stop_ext_display_method = nullptr;
static jmethodID g_open_wifi_display_method = nullptr;
static jmethodID g_ptz_get_state_method = nullptr;
static jmethodID g_ptz_execute_method = nullptr;
static jmethodID g_show_qr_method = nullptr;
static jmethodID g_share_text_method = nullptr;

static std::mutex g_ext_display_mutex;
static obs_display_t *g_ext_display = nullptr;
static ANativeWindow *g_ext_window = nullptr;
static std::atomic<bool> g_shutdown_prepared{false};
static std::atomic<int> g_thermal_tier{0};

static void detach_external_display_locked();
namespace { void detach_program_snapshot_now(); }

extern "C" __attribute__((visibility("default"))) int obs_android_get_thermal_tier()
{
    return g_thermal_tier.load(std::memory_order_acquire);
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_setThermalTier(JNIEnv *, jclass, jint tier)
{
    g_thermal_tier.store(tier < 0 ? 0 : (tier > 3 ? 3 : int(tier)), std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_reopenSession(JNIEnv *, jclass)
{
    android_session_reopen();
    g_shutdown_prepared.store(false, std::memory_order_release);
}

extern "C" __attribute__((visibility("default"))) void obs_android_prepare_shutdown()
{
    if (g_shutdown_prepared.exchange(true, std::memory_order_acq_rel)) return;
    android_session_close();
    detach_program_snapshot_now();
    {
        std::lock_guard<std::mutex> lock(g_ext_display_mutex);
        detach_external_display_locked();
    }
    blog(LOG_INFO, "[android-session] Producer callbacks drained before OBS shutdown");
    using ProbeStopFn = void (*)();
    if (auto stop = reinterpret_cast<ProbeStopFn>(dlsym(RTLD_DEFAULT, "obs_android_performance_probe_shutdown"))) stop();
    android_multistream_shutdown();
    android_iso_replay_shutdown();
    android_srt_shutdown();
    JavaVM *jvm = nullptr;
    jclass cls = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm;
        cls = g_activity_class;
    }
    if (!jvm || !cls) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        detach = true;
    }
    if (env) {
        jmethodID mid = env->GetStaticMethodID(cls, "onNativeShutdown", "()V");
        if (mid) env->CallStaticVoidMethod(cls, mid);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (detach) jvm->DetachCurrentThread();
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_quiesceCallbacks(JNIEnv *, jclass)
{
    android_session_close();
}
extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_setPreviewFpsLimit(JNIEnv *, jclass, jint fps)
{
    obs_android_set_preview_fps(fps);
}
static void (*g_text_result_cb)(int64_t, bool, const char *) = nullptr;
static void (*g_dir_result_cb)(int64_t, const char *) = nullptr;
static bool (*g_remote_state_cb)(char *, size_t) = nullptr;
static bool (*g_remote_action_cb)(const char *, char *, size_t) = nullptr;
static void (*g_save_state_cb)(void) = nullptr;

// Lazy Program snapshot capture (480x270 RGBA via libobs raw video callback)
namespace {
std::mutex g_prog_mutex;
std::mutex g_prog_detach_mutex;
std::vector<uint8_t> g_prog_rgba;
int g_prog_w = 0;
int g_prog_h = 0;
bool g_prog_attached = false;
int64_t g_prog_last_req_ms = 0;
int64_t g_prog_last_copy_ms = 0;
constexpr int kProgSnapW = 480;
constexpr int kProgSnapH = 270;

int64_t now_ms_steady()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::atomic<bool> g_prog_detach_pending{false};
void on_program_raw_video(void *, struct video_data *frame);

void detach_program_snapshot_now()
{
    std::lock_guard<std::mutex> detach_lock(g_prog_detach_mutex);
    bool wasAttached = false;
    {
        std::lock_guard<std::mutex> lock(g_prog_mutex);
        wasAttached = g_prog_attached;
        g_prog_attached = false;
        g_prog_rgba.clear();
        g_prog_rgba.shrink_to_fit();
        g_prog_w = 0;
        g_prog_h = 0;
    }
    if (wasAttached && obs_initialized()) {
        obs_remove_raw_video_callback(on_program_raw_video, nullptr);
    }
    g_prog_detach_pending.store(false, std::memory_order_release);
}

void on_program_raw_video(void *, struct video_data *frame)
{
    AndroidCallbackScope callback;
    if (!callback) return;
    if (!frame || !frame->data[0]) return;
    bool idle = false;
    {
        std::lock_guard<std::mutex> lock(g_prog_mutex);
        const int64_t now_ms = now_ms_steady();
        if (now_ms - g_prog_last_req_ms > 2500) {
            idle = true;
        } else if (now_ms - g_prog_last_copy_ms >= 160) {
            g_prog_last_copy_ms = now_ms;
            const int row_bytes = kProgSnapW * 4;
            const uint32_t stride = frame->linesize[0];
            if (stride < uint32_t(row_bytes)) return;
            if (int(g_prog_rgba.size()) != row_bytes * kProgSnapH) {
                g_prog_rgba.resize(size_t(row_bytes) * kProgSnapH);
            }
            for (int y = 0; y < kProgSnapH; ++y) {
                std::memcpy(g_prog_rgba.data() + size_t(y) * row_bytes,
                            frame->data[0] + size_t(y) * stride,
                            size_t(row_bytes));
            }
            g_prog_w = kProgSnapW;
            g_prog_h = kProgSnapH;
        }
    }
    if (idle) {
        bool expected = false;
        if (g_prog_detach_pending.compare_exchange_strong(expected, true)) {
            std::thread([]() {
                AndroidCallbackScope callback;
                if (callback) detach_program_snapshot_now();
                else g_prog_detach_pending.store(false, std::memory_order_release);
            }).detach();
        }
    }
}

void ensure_program_snapshot_attached()
{
    if (!obs_initialized()) return;
    std::lock_guard<std::mutex> detach_lock(g_prog_detach_mutex);
    std::lock_guard<std::mutex> lock(g_prog_mutex);
    g_prog_last_req_ms = now_ms_steady();
    if (!g_prog_attached) {
        video_scale_info vsi{};
        vsi.format = VIDEO_FORMAT_RGBA;
        vsi.width = kProgSnapW;
        vsi.height = kProgSnapH;
        vsi.range = VIDEO_RANGE_FULL;
        vsi.colorspace = VIDEO_CS_709;
        obs_add_raw_video_callback(&vsi, on_program_raw_video, nullptr);
        g_prog_attached = true;
    }
}
} // namespace

static bool call_activity_string_method(jmethodID mid, const char *arg, char *out_buf, size_t out_cap)
{
    if (!out_buf || out_cap == 0) return false;
    out_buf[0] = '\0';
    JavaVM *jvm = nullptr;
    jclass cls = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm;
        cls = g_activity_class;
    }
    if (!jvm || !cls || !mid) return false;
    JNIEnv *env = nullptr;
    bool detach = false;
    int status = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false;
        detach = true;
    } else if (status != JNI_OK) {
        return false;
    }
    jstring jArg = arg ? env->NewStringUTF(arg) : nullptr;
    jstring jRes = nullptr;
    if (arg) {
        jRes = static_cast<jstring>(env->CallStaticObjectMethod(cls, mid, jArg));
    } else {
        jRes = static_cast<jstring>(env->CallStaticObjectMethod(cls, mid));
    }
    bool ok = false;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (jRes) {
        const char *utf = env->GetStringUTFChars(jRes, nullptr);
        if (utf && *utf) {
            std::snprintf(out_buf, out_cap, "%s", utf);
            ok = true;
        }
        if (utf) env->ReleaseStringUTFChars(jRes, utf);
        env->DeleteLocalRef(jRes);
    }
    if (jArg) env->DeleteLocalRef(jArg);
    if (detach) jvm->DetachCurrentThread();
    return ok;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_resolve_content_uri(
    const char *uri, char *out_buf, size_t out_cap)
{
    if (!uri) return false;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        mid = g_resolve_uri_method;
    }
    return call_activity_string_method(mid, uri, out_buf, out_cap);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_get_default_recording_path(
    char *out_buf, size_t out_cap)
{
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        mid = g_default_rec_path_method;
    }
    return call_activity_string_method(mid, nullptr, out_buf, out_cap);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_install_plugin(
    const char *file_path, char *out_buf, size_t out_cap)
{
    if (!file_path) return false;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        mid = g_install_plugin_method;
    }
    return call_activity_string_method(mid, file_path, out_buf, out_cap);
}

extern "C" __attribute__((visibility("default"))) void obs_android_on_recording_finished(const char *file_path)
{
    if (!file_path || !*file_path) return;
    JavaVM *jvm = nullptr;
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm;
        cls = g_activity_class;
        mid = g_rec_finished_method;
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
    jstring jPath = env->NewStringUTF(file_path);
    env->CallStaticVoidMethod(cls, mid, jPath);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jPath);
    if (detach) jvm->DetachCurrentThread();
}

extern "C" __attribute__((visibility("default"))) bool obs_android_pick_directory(
    int64_t req_id, const char *title, const char *initial, void (*cb)(int64_t, const char *))
{
    JavaVM *jvm = nullptr;
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        g_dir_result_cb = cb;
        jvm = g_activity_jvm;
        cls = g_activity_class;
        mid = g_pick_dir_method;
    }
    if (!jvm || !cls || !mid) return false;
    JNIEnv *env = nullptr;
    bool detach = false;
    int status = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false;
        detach = true;
    } else if (status != JNI_OK) {
        return false;
    }
    jstring jTitle = env->NewStringUTF(title ? title : "Seleccionar carpeta");
    jstring jInit = env->NewStringUTF(initial ? initial : "");
    env->CallStaticVoidMethod(cls, mid, jlong(req_id), jTitle, jInit);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jTitle);
    env->DeleteLocalRef(jInit);
    if (detach) jvm->DetachCurrentThread();
    return true;
}

extern "C" __attribute__((visibility("default"))) void obs_android_register_back_handler(bool (*handler)(void))
{
    g_android_back_handler.store(handler);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_request_text_input(
    int64_t req_id, const char *title, const char *initial, bool is_password,
    void (*cb)(int64_t, bool, const char *))
{
    JavaVM *jvm = nullptr;
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        g_text_result_cb = cb;
        jvm = g_activity_jvm;
        cls = g_activity_class;
        mid = g_open_text_method;
    }
    if (!jvm || !cls || !mid) return false;
    JNIEnv *env = nullptr;
    bool detach = false;
    int status = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false;
        detach = true;
    } else if (status != JNI_OK) {
        return false;
    }
    jstring jTitle = env->NewStringUTF(title ? title : "Editar texto");
    jstring jInit = env->NewStringUTF(initial ? initial : "");
    env->CallStaticVoidMethod(cls, mid, jlong(req_id), jTitle, jInit, jboolean(is_password));
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jTitle);
    env->DeleteLocalRef(jInit);
    if (detach) jvm->DetachCurrentThread();
    return true;
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_registerActivityHost(JNIEnv *env, jclass, jclass hostClass)
{
    std::lock_guard<std::mutex> lock(g_text_host_mutex);
    env->GetJavaVM(&g_activity_jvm);
    if (!g_activity_class) {
        g_activity_class = static_cast<jclass>(env->NewGlobalRef(hostClass));
    }
    g_open_text_method = env->GetStaticMethodID(
        g_activity_class, "openNativeTextInput", "(JLjava/lang/String;Ljava/lang/String;Z)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_open_text_method = nullptr;
    }
    g_resolve_uri_method = env->GetStaticMethodID(
        g_activity_class, "resolveContentUri", "(Ljava/lang/String;)Ljava/lang/String;");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_resolve_uri_method = nullptr;
    }
    g_default_rec_path_method = env->GetStaticMethodID(
        g_activity_class, "getDefaultRecordingPath", "()Ljava/lang/String;");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_default_rec_path_method = nullptr;
    }
    g_pick_dir_method = env->GetStaticMethodID(
        g_activity_class, "pickDirectory", "(JLjava/lang/String;Ljava/lang/String;)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_pick_dir_method = nullptr;
    }
    g_rec_finished_method = env->GetStaticMethodID(
        g_activity_class, "onRecordingFinished", "(Ljava/lang/String;)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_rec_finished_method = nullptr;
    }
    g_install_plugin_method = env->GetStaticMethodID(
        g_activity_class, "installPlugin", "(Ljava/lang/String;)Ljava/lang/String;");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_install_plugin_method = nullptr;
    }
    g_open_web_remote_method = env->GetStaticMethodID(g_activity_class, "openWebRemoteQr", "()V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_open_web_remote_method = nullptr;
    }
    g_open_graphics_panel_method = env->GetStaticMethodID(g_activity_class, "openGraphicsPanel", "()V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_open_graphics_panel_method = nullptr;
    }
    g_toggle_floating_method = env->GetStaticMethodID(g_activity_class, "toggleFloatingOverlay", "()V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_toggle_floating_method = nullptr;
    }
    g_list_srt_urls_method = env->GetStaticMethodID(g_activity_class, "listLocalNetworkAddresses", "(II)Ljava/lang/String;");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_list_srt_urls_method = nullptr;
    }
    g_broadcast_keepalive_method = env->GetStaticMethodID(g_activity_class, "setBroadcastKeepalive", "(Z)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_broadcast_keepalive_method = nullptr;
    }
    g_device_health_method = env->GetStaticMethodID(g_activity_class, "getDeviceHealthJson", "()Ljava/lang/String;");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_device_health_method = nullptr;
    }
    g_hw_profile_method = env->GetStaticMethodID(g_activity_class, "getDeviceHardwareProfileJson", "()Ljava/lang/String;");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_hw_profile_method = nullptr;
    }
    g_zip_dir_method = env->GetStaticMethodID(g_activity_class, "zipDirectory", "(Ljava/lang/String;Ljava/lang/String;)Z");
    if (env->ExceptionCheck()) { env->ExceptionClear(); g_zip_dir_method = nullptr; }
    g_unzip_dir_method = env->GetStaticMethodID(g_activity_class, "unzipToDirectory", "(Ljava/lang/String;Ljava/lang/String;)Z");
    if (env->ExceptionCheck()) { env->ExceptionClear(); g_unzip_dir_method = nullptr; }
    g_list_ext_displays_method = env->GetStaticMethodID(g_activity_class, "listExternalDisplaysJson", "()Ljava/lang/String;");
    if (env->ExceptionCheck()) { env->ExceptionClear(); g_list_ext_displays_method = nullptr; }
    g_start_ext_display_method = env->GetStaticMethodID(g_activity_class, "startExternalDisplay", "(I)Z");
    if (env->ExceptionCheck()) { env->ExceptionClear(); g_start_ext_display_method = nullptr; }
    g_stop_ext_display_method = env->GetStaticMethodID(g_activity_class, "stopExternalDisplay", "()V");
    if (env->ExceptionCheck()) { env->ExceptionClear(); g_stop_ext_display_method = nullptr; }
    g_open_wifi_display_method = env->GetStaticMethodID(g_activity_class, "openWirelessDisplaySettings", "()V");
    if (env->ExceptionCheck()) { env->ExceptionClear(); g_open_wifi_display_method = nullptr; }
    g_ptz_get_state_method = env->GetStaticMethodID(g_activity_class, "ptzGetStateJson", "()Ljava/lang/String;");
    if (env->ExceptionCheck()) { env->ExceptionClear(); g_ptz_get_state_method = nullptr; }
    g_ptz_execute_method = env->GetStaticMethodID(g_activity_class, "ptzExecuteJson", "(Ljava/lang/String;)Ljava/lang/String;");
    if (env->ExceptionCheck()) { env->ExceptionClear(); g_ptz_execute_method = nullptr; }
    g_show_qr_method = env->GetStaticMethodID(g_activity_class, "showQrDialog", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (env->ExceptionCheck()) { env->ExceptionClear(); g_show_qr_method = nullptr; }
    g_share_text_method = env->GetStaticMethodID(g_activity_class, "shareText", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (env->ExceptionCheck()) { env->ExceptionClear(); g_share_text_method = nullptr; }
}

extern "C" __attribute__((visibility("default"))) void obs_android_set_broadcast_keepalive(bool active)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm; cls = g_activity_class; mid = g_broadcast_keepalive_method;
    }
    if (!jvm || !cls || !mid) return;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return; detach = true; }
    else if (st != JNI_OK) return;
    env->CallStaticVoidMethod(cls, mid, jboolean(active));
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) jvm->DetachCurrentThread();
}

extern "C" __attribute__((visibility("default"))) bool obs_android_get_device_health(char *out_buf, size_t out_cap)
{
    if (!out_buf || out_cap == 0) return false;
    out_buf[0] = '\0';
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm; cls = g_activity_class; mid = g_device_health_method;
    }
    if (!jvm || !cls || !mid) return false;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false; detach = true; }
    else if (st != JNI_OK) return false;
    jstring jres = static_cast<jstring>(env->CallStaticObjectMethod(cls, mid));
    bool ok = false;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (jres) {
        const char *utf = env->GetStringUTFChars(jres, nullptr);
        if (utf) {
            std::snprintf(out_buf, out_cap, "%s", utf);
            env->ReleaseStringUTFChars(jres, utf);
            ok = true;
        }
        env->DeleteLocalRef(jres);
    }
    if (detach) jvm->DetachCurrentThread();
    return ok;
}

static bool call_activity_two_string_bool(jmethodID mid, const char *a, const char *b)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm; cls = g_activity_class;
    }
    if (!jvm || !cls || !mid || !a || !b) return false;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false; detach = true; }
    else if (st != JNI_OK) return false;
    jstring ja = env->NewStringUTF(a);
    jstring jb = env->NewStringUTF(b);
    jboolean res = env->CallStaticBooleanMethod(cls, mid, ja, jb);
    if (env->ExceptionCheck()) { env->ExceptionClear(); res = JNI_FALSE; }
    env->DeleteLocalRef(ja);
    env->DeleteLocalRef(jb);
    if (detach) jvm->DetachCurrentThread();
    return res == JNI_TRUE;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_zip_directory(const char *src_dir, const char *zip_path)
{
    jmethodID mid = nullptr;
    { std::lock_guard<std::mutex> lock(g_text_host_mutex); mid = g_zip_dir_method; }
    return call_activity_two_string_bool(mid, src_dir, zip_path);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_unzip_to_directory(const char *zip_path, const char *dst_dir)
{
    jmethodID mid = nullptr;
    { std::lock_guard<std::mutex> lock(g_text_host_mutex); mid = g_unzip_dir_method; }
    return call_activity_two_string_bool(mid, zip_path, dst_dir);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_list_external_displays(char *out_buf, size_t out_cap)
{
    if (!out_buf || out_cap == 0) return false;
    out_buf[0] = '\0';
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm; cls = g_activity_class; mid = g_list_ext_displays_method;
    }
    if (!jvm || !cls || !mid) return false;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false; detach = true; }
    else if (st != JNI_OK) return false;
    jstring jres = static_cast<jstring>(env->CallStaticObjectMethod(cls, mid));
    bool ok = false;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (jres) {
        const char *utf = env->GetStringUTFChars(jres, nullptr);
        if (utf) {
            std::snprintf(out_buf, out_cap, "%s", utf);
            env->ReleaseStringUTFChars(jres, utf);
            ok = true;
        }
        env->DeleteLocalRef(jres);
    }
    if (detach) jvm->DetachCurrentThread();
    return ok;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_start_external_display(int display_id)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm; cls = g_activity_class; mid = g_start_ext_display_method;
    }
    if (!jvm || !cls || !mid) return false;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false; detach = true; }
    else if (st != JNI_OK) return false;
    jboolean res = env->CallStaticBooleanMethod(cls, mid, jint(display_id));
    if (env->ExceptionCheck()) { env->ExceptionClear(); res = JNI_FALSE; }
    if (detach) jvm->DetachCurrentThread();
    return res == JNI_TRUE;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_ptz_get_state(char *out_buf, size_t out_cap)
{
    if (!out_buf || out_cap == 0) return false;
    out_buf[0] = '\0';
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm; cls = g_activity_class; mid = g_ptz_get_state_method;
    }
    if (!jvm || !cls || !mid) return false;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false; detach = true; }
    else if (st != JNI_OK) return false;
    jstring jres = static_cast<jstring>(env->CallStaticObjectMethod(cls, mid));
    bool ok = false;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (jres) {
        const char *utf = env->GetStringUTFChars(jres, nullptr);
        if (utf) {
            std::snprintf(out_buf, out_cap, "%s", utf);
            env->ReleaseStringUTFChars(jres, utf);
            ok = true;
        }
        env->DeleteLocalRef(jres);
    }
    if (detach) jvm->DetachCurrentThread();
    return ok;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_ptz_execute(const char *action_json, char *out_buf, size_t out_cap)
{
    if (out_buf && out_cap > 0) out_buf[0] = '\0';
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm; cls = g_activity_class; mid = g_ptz_execute_method;
    }
    if (!jvm || !cls || !mid || !action_json) return false;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false; detach = true; }
    else if (st != JNI_OK) return false;
    jstring jreq = env->NewStringUTF(action_json);
    jstring jres = static_cast<jstring>(env->CallStaticObjectMethod(cls, mid, jreq));
    env->DeleteLocalRef(jreq);
    bool ok = false;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (jres) {
        const char *utf = env->GetStringUTFChars(jres, nullptr);
        if (utf && out_buf && out_cap > 0) {
            std::snprintf(out_buf, out_cap, "%s", utf);
        }
        if (utf) env->ReleaseStringUTFChars(jres, utf);
        env->DeleteLocalRef(jres);
        ok = true;
    }
    if (detach) jvm->DetachCurrentThread();
    return ok;
}

extern "C" __attribute__((visibility("default"))) void obs_android_show_qr_dialog(const char *title, const char *subtitle, const char *url)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm; cls = g_activity_class; mid = g_show_qr_method;
    }
    if (!jvm || !cls || !mid || !url) return;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return; detach = true; }
    else if (st != JNI_OK) return;
    jstring jt = env->NewStringUTF(title ? title : "");
    jstring js = env->NewStringUTF(subtitle ? subtitle : "");
    jstring ju = env->NewStringUTF(url);
    env->CallStaticVoidMethod(cls, mid, jt, js, ju);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jt);
    env->DeleteLocalRef(js);
    env->DeleteLocalRef(ju);
    if (detach) jvm->DetachCurrentThread();
}

extern "C" __attribute__((visibility("default"))) void obs_android_share_text(const char *title, const char *text)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm; cls = g_activity_class; mid = g_share_text_method;
    }
    if (!jvm || !cls || !mid || !text) return;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return; detach = true; }
    else if (st != JNI_OK) return;
    jstring jt = env->NewStringUTF(title ? title : "");
    jstring jx = env->NewStringUTF(text);
    env->CallStaticVoidMethod(cls, mid, jt, jx);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jt);
    env->DeleteLocalRef(jx);
    if (detach) jvm->DetachCurrentThread();
}

extern "C" __attribute__((visibility("default"))) void obs_android_stop_external_display()
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm; cls = g_activity_class; mid = g_stop_ext_display_method;
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

extern "C" __attribute__((visibility("default"))) void obs_android_open_wireless_display_settings()
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm; cls = g_activity_class; mid = g_open_wifi_display_method;
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

static void draw_external_program(void *, uint32_t cx, uint32_t cy)
{
    obs_video_info ovi{};
    if (!obs_get_video_info(&ovi) || !ovi.base_width || !ovi.base_height || !cx || !cy)
        return;
    const float srcAspect = float(ovi.base_width) / float(ovi.base_height);
    const float dstAspect = float(cx) / float(cy);
    int vx = 0, vy = 0, vw = int(cx), vh = int(cy);
    if (srcAspect > dstAspect) {
        vh = int(float(cx) / srcAspect);
        vy = (int(cy) - vh) / 2;
    } else {
        vw = int(float(cy) * srcAspect);
        vx = (int(cx) - vw) / 2;
    }
    gs_viewport_push();
    gs_projection_push();
    gs_set_viewport(vx, vy, std::max(1, vw), std::max(1, vh));
    gs_ortho(0.0f, float(ovi.base_width), 0.0f, float(ovi.base_height), -100.0f, 100.0f);
    obs_render_main_texture();
    gs_projection_pop();
    gs_viewport_pop();
}

static void detach_external_display_locked()
{
    if (g_ext_display) {
        obs_display_remove_draw_callback(g_ext_display, draw_external_program, nullptr);
        obs_display_destroy(g_ext_display);
        g_ext_display = nullptr;
    }
    if (g_ext_window) {
        ANativeWindow_release(g_ext_window);
        g_ext_window = nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_obsandroid_port_NativeBridge_attachExternalDisplay(JNIEnv *env, jclass, jobject surface, jint width, jint height)
{
    std::lock_guard<std::mutex> lock(g_ext_display_mutex);
    if (!obs_initialized() || !surface || width <= 0 || height <= 0)
        return JNI_FALSE;
    detach_external_display_locked();
    ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
    if (!win)
        return JNI_FALSE;
    gs_init_data info{};
    info.window.android_window = win;
    info.cx = uint32_t(width);
    info.cy = uint32_t(height);
    info.format = GS_BGRA;
    info.zsformat = GS_ZS_NONE;
    obs_display_t *disp = obs_display_create(&info, 0x000000);
    if (!disp) {
        ANativeWindow_release(win);
        return JNI_FALSE;
    }
    obs_display_add_draw_callback(disp, draw_external_program, nullptr);
    g_ext_window = win;
    g_ext_display = disp;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_detachExternalDisplay(JNIEnv *, jclass)
{
    std::lock_guard<std::mutex> lock(g_ext_display_mutex);
    detach_external_display_locked();
}

extern "C" bool obs_android_select_video_codec(const char *request, char *result, size_t capacity)
{
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        if (g_activity_class && g_activity_jvm) {
            JNIEnv *env = nullptr;
            bool detach = false;
            if (g_activity_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
                if (g_activity_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
                detach = true;
            }
            if (env) {
                mid = env->GetStaticMethodID(g_activity_class, "getVideoCodecConfigJson", "(Ljava/lang/String;)Ljava/lang/String;");
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
            if (detach) g_activity_jvm->DetachCurrentThread();
        }
    }
    return call_activity_string_method(mid, request, result, capacity);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_get_hardware_profile(char *out_buf, size_t out_cap)
{
    if (!out_buf || out_cap == 0) return false;
    out_buf[0] = '\0';
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        mid = g_hw_profile_method;
    }
    std::string raw(8192, '\0');
    bool ok = call_activity_string_method(mid, nullptr, raw.data(), raw.size());
    obs_data_t *obj = (ok && raw[0]) ? obs_data_create_from_json(raw.c_str()) : obs_data_create();
    if (!obj) obj = obs_data_create();

    if (obs_initialized()) {
        std::string gpu_renderer, gpu_vendor, gles_ver;
        obs_enter_graphics();
        const char *ren = reinterpret_cast<const char *>(glGetString(GL_RENDERER));
        const char *ven = reinterpret_cast<const char *>(glGetString(GL_VENDOR));
        const char *ver = reinterpret_cast<const char *>(glGetString(GL_VERSION));
        if (ren && *ren) gpu_renderer = ren;
        if (ven && *ven) gpu_vendor = ven;
        if (ver && *ver) gles_ver = ver;
        obs_leave_graphics();
        if (!gpu_renderer.empty()) obs_data_set_string(obj, "gpu_renderer", gpu_renderer.c_str());
        if (!gpu_vendor.empty()) obs_data_set_string(obj, "gpu_vendor", gpu_vendor.c_str());
        if (!gles_ver.empty()) obs_data_set_string(obj, "gles_version", gles_ver.c_str());
    }
    const char *json = obs_data_get_json(obj);
    if (json && *json) {
        std::snprintf(out_buf, out_cap, "%s", json);
        ok = true;
    }
    obs_data_release(obj);
    return ok;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_list_srt_local_urls(
    int port, int latency_ms, char *out_buf, size_t out_cap)
{
    if (!out_buf || out_cap == 0) return false;
    out_buf[0] = '\0';
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm; cls = g_activity_class; mid = g_list_srt_urls_method;
    }
    if (!jvm || !cls || !mid) return false;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false; detach = true; }
    else if (st != JNI_OK) return false;
    auto jRes = static_cast<jstring>(env->CallStaticObjectMethod(cls, mid, jint(port), jint(latency_ms)));
    bool ok = false;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (jRes) {
        const char *utf = env->GetStringUTFChars(jRes, nullptr);
        if (utf) {
            std::snprintf(out_buf, out_cap, "%s", utf);
            env->ReleaseStringUTFChars(jRes, utf);
            ok = true;
        }
        env->DeleteLocalRef(jRes);
    }
    if (detach) jvm->DetachCurrentThread();
    return ok;
}

extern "C" __attribute__((visibility("default"))) void obs_android_register_save_handler(void (*save_cb)(void))
{
    std::lock_guard<std::mutex> lock(g_text_host_mutex);
    g_save_state_cb = save_cb;
}

extern "C" __attribute__((visibility("default"))) void obs_android_register_remote_handlers(
    bool (*state_cb)(char *, size_t), bool (*action_cb)(const char *, char *, size_t))
{
    std::lock_guard<std::mutex> lock(g_text_host_mutex);
    g_remote_state_cb = state_cb;
    g_remote_action_cb = action_cb;
}

static void call_activity_void_method(jmethodID mid)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        jvm = g_activity_jvm; cls = g_activity_class;
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

extern "C" __attribute__((visibility("default"))) void obs_android_open_web_remote_qr()
{
    jmethodID mid = nullptr;
    { std::lock_guard<std::mutex> lock(g_text_host_mutex); mid = g_open_web_remote_method; }
    call_activity_void_method(mid);
}

extern "C" __attribute__((visibility("default"))) void obs_android_open_graphics_panel()
{
    jmethodID mid = nullptr;
    { std::lock_guard<std::mutex> lock(g_text_host_mutex); mid = g_open_graphics_panel_method; }
    call_activity_void_method(mid);
}

extern "C" __attribute__((visibility("default"))) void obs_android_toggle_floating_overlay()
{
    jmethodID mid = nullptr;
    { std::lock_guard<std::mutex> lock(g_text_host_mutex); mid = g_toggle_floating_method; }
    call_activity_void_method(mid);
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_saveAllState(JNIEnv *, jclass)
{
    void (*cb)(void) = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        cb = g_save_state_cb;
    }
    if (cb) cb();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_obsandroid_port_NativeBridge_captureProgramRgba(JNIEnv *env, jclass, jintArray outDims)
{
    AndroidCallbackScope callback;
    if (!callback) return nullptr;
    ensure_program_snapshot_attached();
    std::lock_guard<std::mutex> lock(g_prog_mutex);
    if (g_prog_w <= 0 || g_prog_h <= 0 || g_prog_rgba.empty()) return nullptr;
    if (outDims && env->GetArrayLength(outDims) >= 2) {
        jint dims[2] = {g_prog_w, g_prog_h};
        env->SetIntArrayRegion(outDims, 0, 2, dims);
    }
    jbyteArray arr = env->NewByteArray(jsize(g_prog_rgba.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, jsize(g_prog_rgba.size()),
                            reinterpret_cast<const jbyte *>(g_prog_rgba.data()));
    return arr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_obsandroid_port_NativeBridge_remoteGetState(JNIEnv *env, jclass)
{
    bool (*cb)(char *, size_t) = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        cb = g_remote_state_cb;
    }
    if (!cb) return env->NewStringUTF("{}");
    std::string buf(65536, '\0');
    if (cb(buf.data(), buf.size())) {
        return env->NewStringUTF(buf.c_str());
    }
    return env->NewStringUTF("{}");
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_obsandroid_port_NativeBridge_remoteExecuteAction(JNIEnv *env, jclass, jstring jBody)
{
    bool (*cb)(const char *, char *, size_t) = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        cb = g_remote_action_cb;
    }
    if (!cb) return env->NewStringUTF("{\"ok\":false}");
    const char *utf = jBody ? env->GetStringUTFChars(jBody, nullptr) : nullptr;
    std::string out(4096, '\0');
    bool ok = cb(utf ? utf : "{}", out.data(), out.size());
    if (utf) env->ReleaseStringUTFChars(jBody, utf);
    return env->NewStringUTF(ok && out[0] ? out.c_str() : "{\"ok\":false}");
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_onDirectoryPicked(JNIEnv *env, jclass, jlong reqId, jstring path)
{
    void (*cb)(int64_t, const char *) = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        cb = g_dir_result_cb;
    }
    if (!cb) return;
    const char *utf = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    cb(int64_t(reqId), utf ? utf : "");
    if (utf) env->ReleaseStringUTFChars(path, utf);
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_onNativeTextInputResult(JNIEnv *env, jclass, jlong reqId, jboolean accepted, jstring text)
{
    void (*cb)(int64_t, bool, const char *) = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_text_host_mutex);
        cb = g_text_result_cb;
    }
    if (!cb) return;
    const char *utf = text ? env->GetStringUTFChars(text, nullptr) : nullptr;
    cb(int64_t(reqId), accepted != JNI_FALSE, utf ? utf : "");
    if (utf) env->ReleaseStringUTFChars(text, utf);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_obsandroid_port_NativeBridge_handleAndroidBack(JNIEnv *, jclass)
{
    auto fn = g_android_back_handler.load();
    return fn ? jboolean(fn()) : JNI_FALSE;
}

OBS_DECLARE_MODULE()
MODULE_EXPORT const char *obs_module_description(void)
{
    return "Android native camera, audio, screen capture sources and MediaCodec encoders";
}
bool obs_module_load(void)
{
    android_captures_register();
    android_encoders_register();
    android_hw_video_register();
    android_browser_register();
    android_ndi_register();
    android_vtuber_register();
    android_graphics_register();
    android_iso_replay_register();
    android_vertical_register();
    android_srt_register();
    android_move_transition_register();
    android_frame_style_filter_register();
    android_bg_removal_filter_register();
    android_vmix_color_filter_register();
    return true;
}
void obs_module_unload(void)
{
    android_bg_removal_filter_shutdown();
    android_multistream_shutdown();
    android_iso_replay_shutdown();
    android_srt_shutdown();
    android_ndi_shutdown();
    android_vtuber_shutdown();
    android_browser_shutdown();
    android_captures_shutdown();
    android_hw_video_shutdown();
}
extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_configurePaths(JNIEnv *env, jclass, jstring files, jstring data, jstring libraries)
{
    const char *f = env->GetStringUTFChars(files, nullptr);
    const char *d = env->GetStringUTFChars(data, nullptr);
    const char *l = env->GetStringUTFChars(libraries, nullptr);
    if (f && d && l) {
        obs_android_set_paths(f, d, l);
    }
    if (f) env->ReleaseStringUTFChars(files, f);
    if (d) env->ReleaseStringUTFChars(data, d);
    if (l) env->ReleaseStringUTFChars(libraries, l);
}
#include <android/log.h>
#include <android/native_window_jni.h>
#include <algorithm>
#include <atomic>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>
#include <cmath>

namespace {
constexpr uint32_t canvas_width = 1280, canvas_height = 720;
std::mutex engine_mutex, view_mutex;
std::vector<obs_scene_t *> scenes;
obs_source_t *backgrounds[2]{}, *image{}, *transition_source{};
obs_display_t *displays[2]{};
obs_output_t *stream_output{};
obs_encoder_t *video_encoder{}, *audio_encoder{};
obs_service_t *stream_service{};
std::string stream_error;
std::atomic<bool> stream_finished{};
std::atomic<int> stream_stop_code{};
void output_stopped(void *, calldata_t *data) { stream_stop_code = int(calldata_int(data, "code")); stream_finished = true; }
void stop_stream()
{
    if (stream_output) { obs_output_force_stop(stream_output); obs_output_release(stream_output); }
    stream_output = nullptr;
    if (stream_service) obs_service_release(stream_service);
    stream_service = nullptr;
    if (video_encoder) obs_encoder_release(video_encoder);
    if (audio_encoder) obs_encoder_release(audio_encoder);
    video_encoder = audio_encoder = nullptr;
}
int program_index = 0, preview_index = 1;
int64_t selected_item_id = -1;
std::string edit_error;
bool item_state(obs_scene_t *, obs_sceneitem_t *item, void *data)
{
    auto *array = static_cast<obs_data_array_t *>(data);
    obs_data_t *entry = obs_data_create();
    obs_source_t *source = obs_sceneitem_get_source(item);
    vec2 position{}, scale{}; obs_sceneitem_get_pos(item, &position); obs_sceneitem_get_scale(item, &scale);
    obs_sceneitem_crop crop{}; obs_sceneitem_get_crop(item, &crop);
    obs_data_set_int(entry, "id", obs_sceneitem_get_id(item));
    obs_data_set_string(entry, "name", obs_source_get_name(source));
    obs_data_set_string(entry, "type", obs_source_get_id(source));
    obs_data_set_bool(entry, "visible", obs_sceneitem_visible(item));
    obs_data_set_bool(entry, "locked", obs_sceneitem_locked(item));
    obs_data_set_double(entry, "x", position.x); obs_data_set_double(entry, "y", position.y);
    obs_data_set_double(entry, "scale_x", scale.x); obs_data_set_double(entry, "scale_y", scale.y);
    obs_data_set_double(entry, "rotation", obs_sceneitem_get_rot(item));
    obs_data_set_int(entry, "width", obs_source_get_width(source)); obs_data_set_int(entry, "height", obs_source_get_height(source));
    obs_data_set_int(entry, "crop_left", crop.left); obs_data_set_int(entry, "crop_top", crop.top);
    obs_data_set_int(entry, "crop_right", crop.right); obs_data_set_int(entry, "crop_bottom", crop.bottom);
    obs_data_set_double(entry, "volume", obs_source_get_volume(source)); obs_data_set_bool(entry, "muted", obs_source_muted(source));
    obs_data_set_int(entry, "flags", obs_source_get_output_flags(source));
    obs_data_array_push_back(array, entry); obs_data_release(entry); return true;
}
obs_sceneitem_t *selected_item()
{
    return scenes.empty() ? nullptr : obs_scene_find_sceneitem_by_id(scenes[preview_index], selected_item_id);
}
std::atomic<uint64_t> probe_frames{}, probe_matches{};
std::atomic<uint32_t> probe_pixel{};
bool probe_active{};
void probe_video(void *, video_data *frame)
{
    const uint8_t *pixel = frame->data[0] + 540 * frame->linesize[0] + 640 * 4;
    probe_pixel = uint32_t(pixel[2]) << 16 | uint32_t(pixel[1]) << 8 | pixel[0];
    probe_frames++;
    if (pixel[2] >= 185 && pixel[2] <= 215 && pixel[1] < 35 && pixel[0] >= 165 && pixel[0] <= 195) probe_matches++;
}
std::string from_java(JNIEnv *env, jstring text)
{
    const char *utf = env->GetStringUTFChars(text, nullptr);
    if (!utf) return {};
    std::string result(utf);
    env->ReleaseStringUTFChars(text, utf);
    return result;
}
void log_to_android(int level, const char *format, va_list args, void *)
{
    char buffer[8192];
    vsnprintf(buffer, sizeof(buffer), format, args);
    int priority = level <= LOG_ERROR ? ANDROID_LOG_ERROR : level <= LOG_WARNING ? ANDROID_LOG_WARN :
                   level <= LOG_INFO ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
    __android_log_write(priority, "LibObsAndroid", buffer);
}
std::string state()
{
    obs_data_t *snapshot = obs_data_create();
    obs_data_set_bool(snapshot, "ready", obs_initialized());
    obs_data_t *output = obs_data_create();
    obs_data_set_bool(output, "active", stream_output && obs_output_active(stream_output));
    obs_data_set_bool(output, "reconnecting", stream_output && obs_output_reconnecting(stream_output));
    obs_data_set_bool(output, "finished", stream_output && stream_finished.load());
    obs_data_set_int(output, "stop_code", stream_stop_code.load());
    obs_data_set_int(output, "bytes", stream_output ? obs_output_get_total_bytes(stream_output) : 0);
    obs_data_set_int(output, "frames", stream_output ? obs_output_get_total_frames(stream_output) : 0);
    obs_data_set_int(output, "dropped", stream_output ? obs_output_get_frames_dropped(stream_output) : 0);
    const char *last_error = stream_output ? obs_output_get_last_error(stream_output) : nullptr;
    obs_data_set_string(output, "error", last_error && *last_error ? last_error : stream_error.c_str());
    obs_data_set_obj(snapshot, "stream", output); obs_data_release(output);
    obs_data_t *captures = android_capture_stats();
    obs_data_set_obj(snapshot, "captures", captures); obs_data_release(captures);
    obs_data_set_string(snapshot, "program", scenes.empty() ? "" : obs_source_get_name(obs_scene_get_source(scenes[program_index])));
    obs_data_set_string(snapshot, "preview", scenes.empty() ? "" : obs_source_get_name(obs_scene_get_source(scenes[preview_index])));
    obs_data_set_int(snapshot, "program_index", program_index); obs_data_set_int(snapshot, "preview_index", preview_index);
    obs_data_set_int(snapshot, "selected_item", selected_item_id); obs_data_set_string(snapshot, "edit_error", edit_error.c_str());
    obs_data_array_t *scene_array = obs_data_array_create(), *items = obs_data_array_create();
    for (size_t i = 0; i < scenes.size(); ++i) {
        obs_data_t *scene = obs_data_create(); obs_data_set_int(scene, "index", i);
        obs_data_set_string(scene, "name", obs_source_get_name(obs_scene_get_source(scenes[i])));
        obs_data_array_push_back(scene_array, scene); obs_data_release(scene);
    }
    if (!scenes.empty()) obs_scene_enum_items(scenes[preview_index], item_state, items);
    obs_data_set_array(snapshot, "scenes", scene_array); obs_data_set_array(snapshot, "items", items);
    obs_data_array_release(scene_array); obs_data_array_release(items);
    obs_data_set_int(snapshot, "frames", obs_initialized() ? obs_get_total_frames() : 0);
    obs_data_set_int(snapshot, "probe_frames", probe_frames.load());
    obs_data_set_int(snapshot, "probe_matches", probe_matches.load());
    obs_data_set_int(snapshot, "probe_pixel", probe_pixel.load());
    obs_data_set_string(snapshot, "status", "Motor OBS activo · 1280 × 720 · 30 FPS");
    std::string json = obs_data_get_json(snapshot);
    obs_data_release(snapshot);
    return json;
}
void detach_internal(int index)
{
    if (displays[index]) obs_display_destroy(displays[index]);
    displays[index] = nullptr;
}
void stop_internal()
{
    if (!obs_initialized()) return;
    stop_stream();
    if (probe_active) obs_remove_raw_video_callback(probe_video, nullptr);
    probe_active = false;
    detach_program_snapshot_now();
    android_srt_shutdown();
    android_captures_shutdown();
    detach_internal(0); detach_internal(1);
    obs_set_output_source(0, nullptr);
    if (transition_source) obs_source_release(transition_source);
    transition_source = nullptr;
    for (auto scene : scenes) {
        if (scene) {
            obs_source_dec_showing(obs_scene_get_source(scene));
            obs_scene_release(scene);
        }
    }
    scenes.clear();
    for (int i = 0; i < 2; ++i) {
        if (backgrounds[i]) obs_source_release(backgrounds[i]);
        backgrounds[i] = nullptr;
    }
    if (image) obs_source_release(image);
    image = nullptr;
    obs_shutdown();
}
void draw(void *data, uint32_t width, uint32_t height)
{
    if (!width || !height) return;
    double scale = std::min(double(width) / canvas_width, double(height) / canvas_height);
    uint32_t w = uint32_t(canvas_width * scale), h = uint32_t(canvas_height * scale);
    gs_viewport_push(); gs_projection_push(); gs_matrix_push(); gs_matrix_identity();
    gs_set_viewport(int((width - w) / 2), int((height - h) / 2), w, h);
    gs_ortho(0.0f, float(canvas_width), 0.0f, float(canvas_height), -100.0f, 100.0f);
    if (reinterpret_cast<uintptr_t>(data) == 0) obs_render_main_texture();
    else {
        obs_source_t *source = nullptr;
        {
            std::lock_guard<std::mutex> lock(view_mutex);
            if (scenes[preview_index]) source = obs_source_get_ref(obs_scene_get_source(scenes[preview_index]));
        }
        if (source) { obs_source_video_render(source); obs_source_release(source); }
    }
    gs_matrix_pop(); gs_projection_pop(); gs_viewport_pop();
}
obs_source_t *create_color(const char *name, uint32_t abgr)
{
    obs_data_t *settings = obs_data_create();
    obs_data_set_int(settings, "color", abgr);
    obs_data_set_int(settings, "width", canvas_width);
    obs_data_set_int(settings, "height", canvas_height);
    obs_source_t *source = obs_source_create("color_source_v3", name, settings, nullptr);
    obs_data_release(settings);
    return source;
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_obsandroid_port_NativeBridge_start(JNIEnv *env, jclass, jstring files, jstring data, jstring libraries)
{
    std::lock_guard<std::mutex> guard(engine_mutex);
    if (obs_initialized()) return env->NewStringUTF(state().c_str());
    std::string file_path = from_java(env, files), data_path = from_java(env, data), library_path = from_java(env, libraries);
    if (env->ExceptionCheck()) return nullptr;
    base_set_log_handler(log_to_android, nullptr);
    std::string error;
    if (!obs_android_set_paths(file_path.c_str(), data_path.c_str(), library_path.c_str()) ||
        !obs_startup("en-US", file_path.c_str(), nullptr)) error = "ERROR: no se pudo iniciar libobs";
    else {
        obs_audio_info audio{48000, SPEAKERS_STEREO};
        obs_reset_audio(&audio);
        std::string graphics_module = library_path + "/libobs-opengl.so";
        obs_video_info video{};
        video.graphics_module = graphics_module.c_str();
        video.fps_num = 30; video.fps_den = 1;
        video.base_width = video.output_width = canvas_width;
        video.base_height = video.output_height = canvas_height;
        video.output_format = VIDEO_FORMAT_BGRA;
        video.colorspace = VIDEO_CS_709; video.range = VIDEO_RANGE_FULL;
        video.scale_type = OBS_SCALE_BILINEAR;
        if (obs_reset_video(&video) != OBS_VIDEO_SUCCESS) error = "ERROR: no se pudo iniciar EGL/GLES";
        else if (!obs_load_core_modules()) error = "ERROR: no se pudieron cargar los módulos OBS";
        else {
            obs_post_load_modules();
            obs_data_t *private_data = obs_get_private_data();
            obs_data_set_bool(private_data, "AbsoluteCoordinates", true);
            obs_data_release(private_data);
            scenes = {obs_scene_create("Escena 1"), obs_scene_create("Escena 2")};
            backgrounds[0] = create_color("Fondo azul", 0xff734323);
            backgrounds[1] = create_color("Fondo rojo", 0xff244ebd);
            obs_data_t *settings = obs_data_create();
            std::string image_path = data_path + "/android-test.png";
            obs_data_set_string(settings, "file", image_path.c_str());
            obs_data_set_bool(settings, "unload", false);
            image = obs_source_create("image_source", "Patrón de imagen", settings, nullptr);
            obs_data_release(settings);
            for (int i = 0; i < 2; ++i) {
                obs_scene_add(scenes[i], backgrounds[i]);
                obs_sceneitem_t *item = obs_scene_add(scenes[i], image);
                vec2 position{}, scale{};
                vec2_set(&position, i == 0 ? 220.0f : 650.0f, i == 0 ? 200.0f : 340.0f);
                vec2_set(&scale, 1.7f, 1.7f);
                obs_sceneitem_set_pos(item, &position); obs_sceneitem_set_scale(item, &scale);
                if (i == 1) selected_item_id = obs_sceneitem_get_id(item);
                obs_source_inc_showing(obs_scene_get_source(scenes[i]));
            }
            transition_source = obs_source_create("fade_transition", "Fundido", nullptr, nullptr);
            if (!transition_source || !obs_source_get_width(image)) error = "ERROR: no se pudo cargar la imagen o transición";
            else {
                program_index = 0; preview_index = 1;
                obs_transition_set(transition_source, obs_scene_get_source(scenes[program_index]));
                obs_set_output_source(0, transition_source);
                android_captures_initialize(scenes[0], scenes[1]);
                android_encoders_register();
            }
        }
    }
    if (!error.empty()) { stop_internal(); return env->NewStringUTF(error.c_str()); }
    return env->NewStringUTF(state().c_str());
}
extern "C" JNIEXPORT jboolean JNICALL
Java_org_obsandroid_port_NativeBridge_attach(JNIEnv *env, jclass, jobject surface, jint view, jint width, jint height)
{
    std::lock_guard<std::mutex> guard(engine_mutex);
    if (!obs_initialized() || view < 0 || view > 1 || width <= 0 || height <= 0) return false;
    detach_internal(view);
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) return false;
    gs_init_data info{};
    info.window.android_window = window; info.cx = uint32_t(width); info.cy = uint32_t(height);
    info.format = GS_RGBA; info.zsformat = GS_ZS_NONE;
    displays[view] = obs_display_create(&info, 0xff000000);
    ANativeWindow_release(window);
    if (!displays[view]) return false;
    obs_display_add_draw_callback(displays[view], draw, reinterpret_cast<void *>(uintptr_t(view)));
    return true;
}
extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_detach(JNIEnv *, jclass, jint view)
{
    std::lock_guard<std::mutex> guard(engine_mutex);
    if (view >= 0 && view <= 1) detach_internal(view);
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_obsandroid_port_NativeBridge_transition(JNIEnv *env, jclass, jboolean fade)
{
    std::lock_guard<std::mutex> guard(engine_mutex);
    if (!transition_source) return env->NewStringUTF("ERROR: motor inactivo");
    if (obs_transition_is_active(transition_source)) return env->NewStringUTF(state().c_str());
    obs_source_t *destination = obs_scene_get_source(scenes[preview_index]);
    if (fade) {
        if (!obs_transition_start(transition_source, OBS_TRANSITION_MODE_AUTO, 500, destination))
            return env->NewStringUTF("ERROR: no se pudo iniciar el fundido");
    } else obs_transition_set(transition_source, destination);
    { std::lock_guard<std::mutex> lock(view_mutex); std::swap(program_index, preview_index); selected_item_id = -1; }
    return env->NewStringUTF(state().c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_movePreviewImage(JNIEnv *, jclass, jfloat dx, jfloat dy)
{
    std::lock_guard<std::mutex> guard(engine_mutex);
    if (scenes.empty()) return;
    obs_sceneitem_t *item = selected_item();
    if (!item || obs_sceneitem_locked(item)) return;
    vec2 position{}; obs_sceneitem_get_pos(item, &position);
    position.x = std::clamp(position.x + dx, -1000.0f, 1280.0f);
    position.y = std::clamp(position.y + dy, -1000.0f, 720.0f);
    obs_sceneitem_set_pos(item, &position);
}
extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_scalePreviewImage(JNIEnv *, jclass, jfloat factor)
{
    std::lock_guard<std::mutex> guard(engine_mutex);
    if (scenes.empty() || factor <= 0 || !std::isfinite(factor)) return;
    obs_sceneitem_t *item = selected_item();
    if (!item || obs_sceneitem_locked(item)) return;
    if (obs_sceneitem_get_bounds_type(item) != OBS_BOUNDS_NONE) {
        vec2 bounds{}; obs_sceneitem_get_bounds(item, &bounds);
        bounds.x = std::clamp(bounds.x * factor, 16.0f, 7680.0f); bounds.y = std::clamp(bounds.y * factor, 16.0f, 4320.0f);
        obs_sceneitem_set_bounds(item, &bounds); return;
    }
    vec2 scale{}; obs_sceneitem_get_scale(item, &scale);
    scale.x = scale.y = std::clamp(scale.x * factor, 0.1f, 6.0f);
    obs_sceneitem_set_scale(item, &scale);
}
extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_stop(JNIEnv *, jclass)
{
    std::lock_guard<std::mutex> guard(engine_mutex);
    stop_internal();
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_obsandroid_port_NativeBridge_state(JNIEnv *env, jclass)
{
    std::lock_guard<std::mutex> guard(engine_mutex);
    return env->NewStringUTF(state().c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_probe(JNIEnv *, jclass, jboolean enabled)
{
    std::lock_guard<std::mutex> guard(engine_mutex);
    if (!obs_initialized() || probe_active == bool(enabled)) return;
    if (enabled) {
        probe_frames = 0; probe_matches = 0;
        video_scale_info info{};
        info.format = VIDEO_FORMAT_BGRA; info.width = canvas_width; info.height = canvas_height;
        info.colorspace = VIDEO_CS_709; info.range = VIDEO_RANGE_FULL;
        obs_add_raw_video_callback(&info, probe_video, nullptr);
    } else obs_remove_raw_video_callback(probe_video, nullptr);
    probe_active = enabled;
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_obsandroid_port_NativeBridge_startStream(JNIEnv *env, jclass, jstring json)
{
    std::lock_guard<std::mutex> guard(engine_mutex);
    if (!obs_initialized()) return env->NewStringUTF("Motor no iniciado");
    if (stream_output && obs_output_active(stream_output)) return env->NewStringUTF("Ya hay una transmisión activa");
    stop_stream(); stream_error.clear(); stream_finished = false; stream_stop_code = 0;
    std::string text = from_java(env, json);
    obs_data_t *settings = obs_data_create_from_json(text.c_str());
    if (!settings) return env->NewStringUTF("Configuración inválida");
    const char *server = obs_data_get_string(settings, "server");
    // TLS certificate integration is not yet verified. Expose RTMP only in this first gate.
    if (strncmp(server, "rtmp://", 7) != 0) stream_error = "Esta fase admite RTMP; RTMPS requiere validar certificados Android";
    int64_t bitrate = obs_data_get_int(settings, "bitrate");
    if (bitrate < 250 || bitrate > 20000) stream_error = "Bitrate fuera de 250–20000 kbps";
    if (stream_error.empty()) {
        video_encoder = obs_video_encoder_create("android_mediacodec_h264", "MediaCodec H264", settings, nullptr);
        obs_encoder_set_video(video_encoder, obs_get_video());
        obs_data_t *audio = obs_data_create();
        obs_data_set_string(audio, "codec_name", obs_data_get_string(settings, "audio_codec"));
        int64_t audio_bitrate = obs_data_get_int(settings, "audio_bitrate");
        if (audio_bitrate < 128 || audio_bitrate > 320 || audio_bitrate % 32 != 0)
            audio_bitrate = 160;
        obs_data_set_int(audio, "bitrate", audio_bitrate);
        obs_data_set_bool(audio, "hardware", obs_data_get_bool(settings, "audio_hardware"));
        audio_encoder = obs_audio_encoder_create("android_mediacodec_aac", "MediaCodec AAC", audio, 0, nullptr);
        obs_data_release(audio);
        obs_encoder_set_audio(audio_encoder, obs_get_audio());
        stream_service = obs_service_create("rtmp_custom", "Servidor personalizado", settings, nullptr);
        stream_output = obs_output_create("rtmp_output", "RTMP Android", nullptr, nullptr);
        if (!video_encoder || !audio_encoder || !stream_service || !stream_output) stream_error = "No se pudieron crear encoder/servicio/salida originales";
        else {
            signal_handler_connect(obs_output_get_signal_handler(stream_output), "stop", output_stopped, nullptr);
            obs_output_set_video_encoder(stream_output, video_encoder); obs_output_set_audio_encoder(stream_output, audio_encoder, 0);
            obs_output_set_service(stream_output, stream_service); obs_output_set_reconnect_settings(stream_output, 20, 2);
            if (!obs_output_start(stream_output)) {
                const char *error = obs_output_get_last_error(stream_output);
                stream_error = error && *error ? error : "La salida RTMP no pudo iniciar; consulta logcat";
            }
        }
    }
    obs_data_release(settings);
    if (!stream_error.empty()) stop_stream();
    return env->NewStringUTF(stream_error.c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_stopStream(JNIEnv *, jclass)
{
    std::lock_guard<std::mutex> guard(engine_mutex); stop_stream();
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_obsandroid_port_NativeBridge_edit(JNIEnv *env, jclass, jstring json)
{
    std::lock_guard<std::mutex> guard(engine_mutex);
    edit_error.clear();
    if (!obs_initialized() || scenes.empty()) return env->NewStringUTF("Motor inactivo");
    std::string text = from_java(env, json);
    obs_data_t *command = obs_data_create_from_json(text.c_str());
    if (!command) return env->NewStringUTF("Comando inválido");
    std::string op = obs_data_get_string(command, "op"), name = obs_data_get_string(command, "name");
    auto error = [&](const char *message) { edit_error = message; };
    obs_sceneitem_t *item = selected_item();
    if (op == "scene_add" || op == "scene_duplicate" || op == "scene_rename" || op == "source_add") {
        obs_source_t *existing = obs_get_source_by_name(name.c_str());
        if (name.empty() || name.size() > 256 || existing) error("Usa un nombre único de 1–256 bytes");
        if (existing) obs_source_release(existing);
    }
    if (edit_error.empty()) {
        if (op == "scene_select") {
            int64_t index = obs_data_get_int(command, "index");
            if (index < 0 || size_t(index) >= scenes.size()) error("Escena inexistente");
            else { std::lock_guard<std::mutex> lock(view_mutex); preview_index = int(index); selected_item_id = -1; }
        } else if (op == "scene_add" || op == "scene_duplicate") {
            if (scenes.size() >= 64) error("Límite inicial de 64 escenas");
            else {
                obs_scene_t *scene = op == "scene_add" ? obs_scene_create(name.c_str()) : obs_scene_duplicate(scenes[preview_index], name.c_str(), OBS_SCENE_DUP_REFS);
                if (!scene) error("No se pudo crear la escena OBS");
                else {
                    obs_source_inc_showing(obs_scene_get_source(scene));
                    android_captures_add_scene(scene);
                    std::lock_guard<std::mutex> lock(view_mutex); scenes.push_back(scene); preview_index = int(scenes.size()-1); selected_item_id = -1;
                }
            }
        } else if (op == "scene_rename") obs_source_set_name(obs_scene_get_source(scenes[preview_index]), name.c_str());
        else if (op == "scene_remove") {
            if (scenes.size() <= 2) error("Conserva al menos dos escenas para Program/Preview");
            else if (obs_transition_is_active(transition_source)) error("Espera a que termine la transición");
            else {
                int removed = preview_index;
                obs_scene_t *scene = scenes[removed]; android_captures_remove_scene(scene);
                {
                    std::lock_guard<std::mutex> lock(view_mutex);
                    scenes.erase(scenes.begin()+removed);
                    program_index = program_index == removed ? 0 : program_index - (program_index > removed ? 1 : 0);
                    preview_index = program_index == 0 ? 1 : 0; selected_item_id = -1;
                }
                obs_transition_set(transition_source, obs_scene_get_source(scenes[program_index]));
                obs_source_dec_showing(obs_scene_get_source(scene)); obs_source_remove(obs_scene_get_source(scene)); obs_scene_release(scene);
            }
        } else if (op == "source_select") selected_item_id = obs_data_get_int(command, "id");
        else if (op == "source_add") {
            std::string type = obs_data_get_string(command, "type");
            if (type != "image_source" && type != "ffmpeg_source" && type != "color_source_v3") error("Tipo de fuente no habilitado");
            else {
                obs_data_t *settings = obs_data_get_obj(command, "settings");
                if (!settings) settings = obs_data_create();
                if (type == "ffmpeg_source") {
                    obs_data_set_bool(settings, "is_local_file", true); obs_data_set_bool(settings, "looping", true);
                    obs_data_set_bool(settings, "hw_decode", false); obs_data_set_bool(settings, "restart_on_activate", true);
                }
                obs_source_t *source = obs_source_create(type.c_str(), name.c_str(), settings, nullptr); obs_data_release(settings);
                if (!source) error("No se pudo crear la fuente original");
                else {
                    obs_sceneitem_t *added = obs_scene_add(scenes[preview_index], source);
                    if (added) {
                        vec2 position{}, bounds{}; vec2_set(&position, 640, 360); vec2_set(&bounds, 1280, 720);
                        obs_sceneitem_set_alignment(added, OBS_ALIGN_CENTER); obs_sceneitem_set_pos(added, &position);
                        obs_sceneitem_set_bounds_type(added, OBS_BOUNDS_SCALE_INNER); obs_sceneitem_set_bounds(added, &bounds);
                        selected_item_id = obs_sceneitem_get_id(added);
                    } else error("No se pudo añadir la fuente a la escena");
                    obs_source_release(source);
                }
            }
        } else if (!item) error("Selecciona una fuente en Preview");
        else if (op == "source_remove") { obs_sceneitem_remove(item); selected_item_id = -1; }
        else if (op == "source_visible") obs_sceneitem_set_visible(item, obs_data_get_bool(command, "visible"));
        else if (op == "source_lock") obs_sceneitem_set_locked(item, obs_data_get_bool(command, "locked"));
        else if (op == "source_order") obs_sceneitem_set_order(item, obs_data_get_bool(command, "up") ? OBS_ORDER_MOVE_UP : OBS_ORDER_MOVE_DOWN);
        else if (op == "source_gain") {
            obs_source_t *source = obs_sceneitem_get_source(item);
            obs_source_set_volume(source, std::clamp(float(obs_data_get_double(command, "volume")), 0.0f, 2.0f));
            obs_source_set_muted(source, obs_data_get_bool(command, "muted"));
        } else if (op == "source_transform") {
            if (obs_sceneitem_locked(item)) error("La fuente está bloqueada");
            else {
                vec2 position{}, scale{};
                float x = float(obs_data_get_double(command, "x")), y = float(obs_data_get_double(command, "y")), rotation = float(obs_data_get_double(command, "rotation"));
                float sx = float(obs_data_get_double(command, "scale_x")), sy = float(obs_data_get_double(command, "scale_y"));
                if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(rotation) || !std::isfinite(sx) || !std::isfinite(sy)) error("Transformación no finita");
                else {
                    vec2_set(&position, std::clamp(x,-7680.0f,7680.0f), std::clamp(y,-4320.0f,4320.0f));
                    vec2_set(&scale,std::clamp(sx,-20.0f,20.0f),std::clamp(sy,-20.0f,20.0f));
                    obs_source_t *source = obs_sceneitem_get_source(item);
                    int width = int(obs_source_get_width(source)), height = int(obs_source_get_height(source));
                    obs_sceneitem_crop crop{};
                    crop.left = int(std::clamp<int64_t>(obs_data_get_int(command,"crop_left"),0,std::max(0,width-2)));
                    crop.right = int(std::clamp<int64_t>(obs_data_get_int(command,"crop_right"),0,std::max(0,width-crop.left-2)));
                    crop.top = int(std::clamp<int64_t>(obs_data_get_int(command,"crop_top"),0,std::max(0,height-2)));
                    crop.bottom = int(std::clamp<int64_t>(obs_data_get_int(command,"crop_bottom"),0,std::max(0,height-crop.top-2)));
                    obs_sceneitem_defer_update_begin(item);
                    obs_sceneitem_set_pos(item,&position); obs_sceneitem_set_scale(item,&scale); obs_sceneitem_set_rot(item,rotation); obs_sceneitem_set_crop(item,&crop);
                    obs_sceneitem_defer_update_end(item);
                }
            }
        } else if (op == "filter") {
            std::string type = obs_data_get_string(command,"type");
            if (type != "color_filter_v2" && type != "crop_filter" && type != "gain_filter" && type != "noise_suppress_filter_v2" && type != "compressor_filter" && type != "limiter_filter") error("Filtro no habilitado");
            else {
                obs_source_t *source = obs_sceneitem_get_source(item), *filter = obs_source_get_filter_by_name(source,type.c_str());
                if (filter) { obs_source_filter_remove(source,filter); obs_source_release(filter); }
                if (obs_data_get_bool(command,"enabled")) {
                    obs_data_t *settings = obs_data_get_obj(command,"settings");
                    filter = obs_source_create(type.c_str(),type.c_str(),settings,nullptr); obs_data_release(settings);
                    if (filter) { obs_source_filter_add(source,filter); obs_source_release(filter); } else error("No se pudo crear el filtro OBS");
                }
            }
        } else error("Operación desconocida");
    }
    obs_data_release(command);
    return env->NewStringUTF(state().c_str());
}
