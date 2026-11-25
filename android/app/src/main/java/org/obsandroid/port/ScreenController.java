package org.obsandroid.port;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ScreenController {
    private final Context context;
    private final Runnable stoppedCallback;
    private final HandlerThread thread = new HandlerThread("OBS-MediaProjection");
    private Handler handler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private AudioCaptureController audio;
    private volatile boolean stopped;
    private volatile int maxDimension = 1920;
    private int lastRawWidth = 0;
    private int lastRawHeight = 0;
    private long lastFrame;
    private long lastFpsCheck;
    private long minFrameIntervalNs = 31_000_000L; // Matches OBS canvas FPS (default 30 fps)
    ScreenController(Context context, Runnable stoppedCallback) { this.context = context; this.stoppedCallback = stoppedCallback; }
    void start(int resultCode, Intent permission, boolean captureAudio) {
        start(resultCode, permission, captureAudio, 1920);
    }
    void start(int resultCode, Intent permission, boolean captureAudio, int maxDim) {
        this.maxDimension = (maxDim >= 720 && maxDim <= 2560) ? maxDim : 1920;
        thread.start(); handler = new Handler(thread.getLooper());
        EngineRuntime.EXECUTOR.execute(() -> {
            if (stopped) return;
            if (!NativeBridge.enableCapture(2, true)) { stoppedCallback.run(); return; }
            handler.post(() -> {
                if (stopped) return;
                try {
                    projection = context.getSystemService(MediaProjectionManager.class).getMediaProjection(resultCode, permission);
                    if (projection == null) throw new IllegalStateException("MediaProjection was not granted");
                    projection.registerCallback(new MediaProjection.Callback() {
                        @Override public void onStop() { if (!stopped) stoppedCallback.run(); }
                        @Override public void onCapturedContentResize(int width, int height) { if (!stopped) resize(width, height); }
                    }, handler);
                    WindowManager manager = context.getSystemService(WindowManager.class);
                    int width, height;
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        Rect bounds = manager.getMaximumWindowMetrics().getBounds(); width = bounds.width(); height = bounds.height();
                    } else {
                        DisplayMetrics metrics = new DisplayMetrics(); manager.getDefaultDisplay().getRealMetrics(metrics); width = metrics.widthPixels; height = metrics.heightPixels;
                    }
                    resize(width, height);
                    if (captureAudio) { audio = new AudioCaptureController(projection, stoppedCallback); audio.start(); }
                    Log.i("ObsScreen", "MediaProjection started with new user consent; playbackAudio=" + captureAudio);
                } catch (Exception error) { Log.e("ObsScreen", "Projection start failed", error); stoppedCallback.run(); }
            });
        });
    }
    private void resize(int width, int height) {
        if (stopped || projection == null || width <= 0 || height <= 0) return;
        lastRawWidth = width;
        lastRawHeight = height;
        try {
            float scale = Math.min(1f, floatMaxDim() / Math.max(width, height));
            int outputWidth = Math.max(2, Math.round(width*scale) & ~1), outputHeight = Math.max(2, Math.round(height*scale) & ~1);
            if (reader != null && reader.getWidth() == outputWidth && reader.getHeight() == outputHeight) return;
            ImageReader next = null;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                try {
                    next = ImageReader.newInstance(outputWidth, outputHeight, PixelFormat.RGBA_8888, 4,
                        android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
                } catch (Exception ignored) {
                    next = null;
                }
            }
            if (next == null) {
                next = ImageReader.newInstance(outputWidth, outputHeight, PixelFormat.RGBA_8888, 3);
            }
            next.setOnImageAvailableListener(this::frame, handler);
            int density = context.getResources().getDisplayMetrics().densityDpi;
            if (display == null) display = projection.createVirtualDisplay("OBS Android screen", outputWidth, outputHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, next.getSurface(), null, handler);
            else { display.resize(outputWidth, outputHeight, density); display.setSurface(next.getSurface()); }
            ImageReader previous = reader; reader = next;
            if (previous != null) previous.close();
            Log.i("ObsScreen", "Projection size=" + outputWidth + "x" + outputHeight + " zeroCopyGpu=" + (android.os.Build.VERSION.SDK_INT >= 29));
        } catch (Exception error) { Log.e("ObsScreen", "Projection resize failed", error); stoppedCallback.run(); }
    }
    private void frame(ImageReader source) {
        if (source != reader || stopped) return;
        try (Image image = source.acquireLatestImage()) {
            if (image == null || stopped) return;
            long now = System.nanoTime();
            if (now - lastFpsCheck > 2_000_000_000L) {
                lastFpsCheck = now;
                int fps = 30;
                try { fps = NativeBridge.targetVideoFps(); } catch (Throwable ignored) {}
                if (fps < 15 || fps > 120) fps = 30;
                minFrameIntervalNs = Math.max(14_000_000L, (1_000_000_000L / fps) - 2_000_000L);
            }
            if (now - lastFrame < minFrameIntervalNs) return;
            lastFrame = now;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.hardware.HardwareBuffer hb = image.getHardwareBuffer();
                if (hb != null) {
                    try {
                        NativeBridge.screenHardwareBuffer(hb, image.getWidth(), image.getHeight(), now);
                        return;
                    } finally {
                        hb.close();
                    }
                }
            }
            Image.Plane plane = image.getPlanes()[0];
            if (plane.getPixelStride() != 4) throw new IllegalStateException("Unsupported projection pixel stride");
            NativeBridge.screenFrame(plane.getBuffer().slice(), plane.getRowStride(), image.getWidth(), image.getHeight(), now);
        } catch (Exception error) { if (!stopped && source == reader) { Log.e("ObsScreen", "Projection frame", error); stoppedCallback.run(); } }
    }
    private float floatMaxDim() {
        return (maxDimension >= 720 && maxDimension <= 2560) ? (float) maxDimension : 1920f;
    }
    void setMaxDimension(int maxDim) {
        int clamped = (maxDim >= 720 && maxDim <= 2560) ? maxDim : 1920;
        if (clamped == maxDimension) return;
        maxDimension = clamped;
        if (stopped || handler == null) return;
        handler.post(() -> {
            if (!stopped && projection != null && lastRawWidth > 0 && lastRawHeight > 0) {
                resize(lastRawWidth, lastRawHeight);
            }
        });
    }
    void setAudioEnabled(boolean enableAudio) {
        if (stopped || handler == null) return;
        handler.post(() -> {
            if (stopped || projection == null) return;
            if (enableAudio && audio == null) {
                try {
                    audio = new AudioCaptureController(projection, stoppedCallback);
                    audio.start();
                    Log.i("ObsScreen", "Enabled independent screen playback audio");
                } catch (Exception e) {
                    Log.w("ObsScreen", "Failed enabling screen audio", e);
                }
            } else if (!enableAudio && audio != null) {
                audio.stop();
                audio = null;
                Log.i("ObsScreen", "Disabled independent screen playback audio");
            }
        });
    }

    void stop() {
        if (stopped) return;
        stopped = true;
        if (audio != null) { audio.stop(); audio = null; }
        if (handler != null) {
            CountDownLatch closed = new CountDownLatch(1);
            handler.post(() -> {
                if (display != null) display.release(); display = null;
                if (reader != null) reader.close(); reader = null;
                if (projection != null) projection.stop(); projection = null;
                closed.countDown();
            });
            try { closed.await(2, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            thread.quitSafely();
        }
        EngineRuntime.EXECUTOR.execute(() -> { NativeBridge.enableCapture(2, false); NativeBridge.enableCapture(3, false); });
    }
}
