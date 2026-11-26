package org.obsandroid.port;

final class NativeBridge {
    static { System.loadLibrary("obs"); System.loadLibrary("obs-android-jni"); }
    static native void configurePaths(String files, String data, String libraries);
    static native void registerCaptureHost(Class<?> hostClass);
    static native void registerActivityHost(Class<?> hostClass);
    static native void registerBrowserHost(Class<?> hostClass);
    static native void registerBrowserDockHost(Class<?> hostClass);
    static native void setPreviewFpsLimit(int fps);
    static native void registerNdiHost(Class<?> hostClass);
    static native boolean reloadNdiRuntime(String libPath);
    static native void registerVtuberHost(Class<?> hostClass);
    static native void registerGraphicsHost(Class<?> hostClass);
    static native void registerBgSegmenterHost(Class<?> hostClass);
    static native void pushBgMask(long instanceId, byte[] maskBytes, int width, int height);
    static native float getMicLevel();
    static native void pushVtuberFrame(long instanceId, java.nio.ByteBuffer rgba, int width, int height, long timestampNs);
    static native void pushGraphicsFrame(long instanceId, java.nio.ByteBuffer rgba, int width, int height, long timestampNs);
    static native void saveAllState();
    static native void quiesceCallbacks();
    static native void reopenSession();
    static native void setThermalTier(int tier);
    static native byte[] captureProgramRgba(int[] outDims);
    static native String remoteGetState();
    static native String remoteExecuteAction(String actionJson);
    static native boolean handleAndroidBack();
    static native void onNativeTextInputResult(long requestId, boolean accepted, String text);
    static native void onDirectoryPicked(long requestId, String path);
    static native void pushBrowserAudio(long id, byte[] pcm, int channels, int sampleRate, long timestamp);
    static native boolean pushBrowserBitmap(long instanceId, android.graphics.Bitmap bitmap);
    static native boolean pushBrowserHardwareBuffer(long instanceId, android.hardware.HardwareBuffer buffer, int width, int height);
    static native boolean pushVtuberArray(long id, byte[] bytes, int offset, int width, int height, boolean flip);
    static native boolean pushVtuberBitmap(long instanceId, android.graphics.Bitmap bitmap);
    static native void pushBrowserFrame(long instanceId, java.nio.ByteBuffer rgba, int width, int height, long timestampNs);
    static native boolean startUvcCapture(int fd, byte[] rawDescriptors, int targetHeight);
    static native void stopUvcCapture();
    static native String inspectCore(String files, String data, String libraries);
    static native String start(String files, String data, String libraries);
    static native boolean attach(android.view.Surface surface, int view, int width, int height);
    static native void detach(int view);
    static native boolean attachExternalDisplay(android.view.Surface surface, int width, int height);
    static native void detachExternalDisplay();
    static native String transition(boolean fade);
    static native void movePreviewImage(float x, float y);
    static native void scalePreviewImage(float factor);
    static native void stop();
    static native String state();
    static native void probe(boolean enabled);
    static native String startStream(String settings);
    static native void stopStream();
    static native String edit(String command);
    static native boolean enableCapture(int kind, boolean enabled);
    static native void cameraFrame(java.nio.ByteBuffer y, java.nio.ByteBuffer u, java.nio.ByteBuffer v,
        int yStride, int uStride, int vStride, int uPixelStride, int vPixelStride,
        int width, int height, long timestamp, int rotation, boolean mirror, boolean fullRange, boolean rec709);
    static native void cameraHardwareBuffer(android.hardware.HardwareBuffer buffer,
        int width, int height, long timestamp, int rotation, boolean mirror);
    static native void screenFrame(java.nio.ByteBuffer rgba, int stride, int width, int height, long timestamp);
    static native void screenHardwareBuffer(android.hardware.HardwareBuffer buffer,
        int width, int height, long timestamp);
    static native void audioFrame(int kind, java.nio.ByteBuffer pcm, int bytes, int channels, int sampleRate, long timestamp);
    static native void audioFrameForDevice(String deviceSpec, java.nio.ByteBuffer pcm, int bytes, int channels, int sampleRate, long timestamp);
    static native void captureGain(int kind, float gain, boolean mute);
    static native int targetVideoFps();
    private NativeBridge() {}
}
