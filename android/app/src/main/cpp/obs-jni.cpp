// SPDX-License-Identifier: GPL-2.0-or-later
#include <jni.h>
#include <obs.h>
#include <util/platform-android.h>
#include <android/log.h>
#include <mutex>
#include <string>

static std::mutex engine_mutex;
static std::string from_java(JNIEnv *env, jstring text)
{
    const char *utf = env->GetStringUTFChars(text, nullptr);
    if (!utf) return {};
    std::string result(utf);
    env->ReleaseStringUTFChars(text, utf);
    return result;
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_obsandroid_port_NativeBridge_inspectCore(JNIEnv *env, jclass, jstring files, jstring data, jstring libraries)
{
    std::lock_guard<std::mutex> guard(engine_mutex);
    std::string file_path = from_java(env, files);
    std::string data_path = from_java(env, data);
    std::string library_path = from_java(env, libraries);
    if (env->ExceptionCheck()) return nullptr;
    std::string result;
    if (!obs_android_set_paths(file_path.c_str(), data_path.c_str(), library_path.c_str())) {
        result = "ERROR: rutas Android inválidas";
    } else if (!obs_startup("en-US", file_path.c_str(), nullptr)) {
        result = "ERROR: obs_startup falló";
    } else {
        obs_audio_info audio{48000, SPEAKERS_STEREO};
        const bool audio_ok = obs_reset_audio(&audio);
        obs_scene_t *scene = obs_scene_create("Escena de diagnóstico JNI");
        const bool scene_ok = scene != nullptr;
        result = std::string("libobs original: ") + obs_get_version_string() +
            "\nABI: arm64-v8a\nArranque: correcto\nEscena OBS: " + (scene_ok ? "creada" : "ERROR") +
            "\nMotor de audio 48 kHz estéreo: " + (audio_ok ? "iniciado" : "ERROR") +
            "\nDiagnóstico de fase 2. Renderizado y transmisión pendientes.";
        if (scene) obs_scene_release(scene);
        obs_shutdown();
        result += obs_initialized() ? "\nERROR: cierre incompleto" : "\nCierre del motor: correcto";
    }
    __android_log_write(ANDROID_LOG_INFO, "ObsAndroidJNI", result.c_str());
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_org_obsandroid_port_NativeBridge_targetVideoFps(JNIEnv *, jclass)
{
    obs_video_info ovi{};
    if (obs_initialized() && obs_get_video_info(&ovi) && ovi.fps_num > 0 && ovi.fps_den > 0) {
        int fps = int(ovi.fps_num / ovi.fps_den);
        if (fps >= 15 && fps <= 120) return fps;
    }
    return 30;
}
