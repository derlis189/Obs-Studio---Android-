// SPDX-License-Identifier: GPL-2.0-or-later
#include <jni.h>
#include <cstdint>
#include <mutex>

namespace {
std::mutex g_bd_mutex;
JavaVM *g_bd_jvm = nullptr;
jclass g_bd_cls = nullptr;
jmethodID g_mid_upsert = nullptr;
jmethodID g_mid_bounds = nullptr;
jmethodID g_mid_nav = nullptr;
jmethodID g_mid_destroy = nullptr;
jmethodID g_mid_suspend = nullptr;

bool attach_bd_env(JNIEnv **env_out, bool *detach_out)
{
    JavaVM *jvm = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_bd_mutex);
        jvm = g_bd_jvm;
    }
    if (!jvm) return false;
    JNIEnv *env = nullptr;
    bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false;
        detach = true;
    } else if (st != JNI_OK) {
        return false;
    }
    *env_out = env;
    *detach_out = detach;
    return true;
}

void detach_bd_env(bool detach)
{
    if (!detach) return;
    JavaVM *jvm = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_bd_mutex);
        jvm = g_bd_jvm;
    }
    if (jvm) jvm->DetachCurrentThread();
}
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_registerBrowserDockHost(JNIEnv *env, jclass, jclass hostClass)
{
    std::lock_guard<std::mutex> lock(g_bd_mutex);
    if (g_bd_cls) return;
    env->GetJavaVM(&g_bd_jvm);
    g_bd_cls = static_cast<jclass>(env->NewGlobalRef(hostClass));
    if (!g_bd_cls) return;
    g_mid_upsert = env->GetStaticMethodID(g_bd_cls, "createOrUpdate", "(JLjava/lang/String;Z)V");
    g_mid_bounds = env->GetStaticMethodID(g_bd_cls, "setBounds", "(JIIIIZ)V");
    g_mid_nav = env->GetStaticMethodID(g_bd_cls, "navigate", "(JI)V");
    g_mid_destroy = env->GetStaticMethodID(g_bd_cls, "destroy", "(J)V");
    g_mid_suspend = env->GetStaticMethodID(g_bd_cls, "setQtSuspended", "(Z)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
}

extern "C" __attribute__((visibility("default"))) void obs_android_browser_dock_upsert(
    int64_t id, const char *url, bool desktop_mode)
{
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_bd_mutex);
        cls = g_bd_cls;
        mid = g_mid_upsert;
    }
    if (!cls || !mid) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (!attach_bd_env(&env, &detach)) return;
    jstring jUrl = env->NewStringUTF(url ? url : "about:blank");
    env->CallStaticVoidMethod(cls, mid, jlong(id), jUrl, jboolean(desktop_mode ? JNI_TRUE : JNI_FALSE));
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jUrl);
    detach_bd_env(detach);
}

extern "C" __attribute__((visibility("default"))) void obs_android_browser_dock_set_bounds(
    int64_t id, int x, int y, int w, int h, bool visible)
{
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_bd_mutex);
        cls = g_bd_cls;
        mid = g_mid_bounds;
    }
    if (!cls || !mid) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (!attach_bd_env(&env, &detach)) return;
    env->CallStaticVoidMethod(cls, mid, jlong(id), jint(x), jint(y), jint(w), jint(h),
                              jboolean(visible ? JNI_TRUE : JNI_FALSE));
    if (env->ExceptionCheck()) env->ExceptionClear();
    detach_bd_env(detach);
}

extern "C" __attribute__((visibility("default"))) void obs_android_browser_dock_navigate(
    int64_t id, int action)
{
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_bd_mutex);
        cls = g_bd_cls;
        mid = g_mid_nav;
    }
    if (!cls || !mid) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (!attach_bd_env(&env, &detach)) return;
    env->CallStaticVoidMethod(cls, mid, jlong(id), jint(action));
    if (env->ExceptionCheck()) env->ExceptionClear();
    detach_bd_env(detach);
}

extern "C" __attribute__((visibility("default"))) void obs_android_browser_dock_destroy(int64_t id)
{
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_bd_mutex);
        cls = g_bd_cls;
        mid = g_mid_destroy;
    }
    if (!cls || !mid) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (!attach_bd_env(&env, &detach)) return;
    env->CallStaticVoidMethod(cls, mid, jlong(id));
    if (env->ExceptionCheck()) env->ExceptionClear();
    detach_bd_env(detach);
}

extern "C" __attribute__((visibility("default"))) void obs_android_browser_dock_set_suspended(bool suspended)
{
    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_bd_mutex);
        cls = g_bd_cls;
        mid = g_mid_suspend;
    }
    if (!cls || !mid) return;
    JNIEnv *env = nullptr;
    bool detach = false;
    if (!attach_bd_env(&env, &detach)) return;
    env->CallStaticVoidMethod(cls, mid, jboolean(suspended ? JNI_TRUE : JNI_FALSE));
    if (env->ExceptionCheck()) env->ExceptionClear();
    detach_bd_env(detach);
}
