/* SPDX-License-Identifier: GPL-2.0-or-later */
#include <jni.h>
#include <android/native_window.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <media/NdkMediaCodec.h>
#include <camera/NdkCameraManager.h>
#include <aaudio/AAudio.h>
#include <pthread.h>
#include <stdatomic.h>

#if !defined(__ANDROID__) || !defined(__aarch64__)
#error Expected an Android AArch64 target
#endif
_Static_assert(sizeof(void *) == 8, "Expected 64-bit pointers");

/* Compile/link coverage only: never invoked by an Android application. */
int android_toolchain_probe(void)
{
    atomic_int value = 0;
    atomic_store(&value, __ANDROID_API__);
    __android_log_print(ANDROID_LOG_DEBUG, "ToolchainProbe", "API %d", atomic_load(&value));
    (void)eglGetError();
    (void)glGetError();
    AMediaCodec *codec = AMediaCodec_createEncoderByType("video/avc");
    if (codec)
        AMediaCodec_delete(codec);
    return JNI_VERSION_1_6;
}
