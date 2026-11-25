// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.ByteBufferExtractor;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-filter MediaPipe/LiteRT selfie segmentation workers. */
public final class BgSegmenterManager {
    private static final String TAG = "ObsBgSegmenter";
    private static final String MODEL = "models/selfie_segmenter_landscape.tflite";

    private static final class WorkerInstance {
        final long id;
        final ImageSegmenter segmenter;
        final ExecutorService executor;
        final AtomicBoolean busy = new AtomicBoolean(false);
        final AtomicBoolean closed = new AtomicBoolean(false);
        Bitmap reusableBitmap;
        byte[] maskBytes;
        byte[] stagingRgba;
        long lastTimestampMs;

        WorkerInstance(long id, Context context) {
            this.id = id;
            BaseOptions base = BaseOptions.builder().setModelAssetPath(MODEL).build();
            ImageSegmenter.ImageSegmenterOptions options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.VIDEO)
                .setOutputCategoryMask(false)
                .setOutputConfidenceMasks(true)
                .build();
            this.segmenter = ImageSegmenter.createFromOptions(context, options);
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "OBS-BgSeg-" + id);
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            });
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            executor.execute(() -> {
                try {
                    segmenter.close();
                } catch (Throwable ignored) {}
                if (reusableBitmap != null && !reusableBitmap.isRecycled()) {
                    reusableBitmap.recycle();
                    reusableBitmap = null;
                }
            });
            executor.shutdown();
        }
    }

    private static final ConcurrentHashMap<Long, WorkerInstance> sInstances = new ConcurrentHashMap<>();

    public static void submitFrame(long instanceId, byte[] rgba, int width, int height) {
        if (rgba == null || width <= 0 || height <= 0 || rgba.length < width * height * 4) return;
        WorkerInstance worker = sInstances.get(instanceId);
        if (worker == null) {
            Context context = QtObsActivity.getAppContext();
            if (context == null) return;
            try {
                WorkerInstance created = new WorkerInstance(instanceId, context);
                WorkerInstance previous = sInstances.putIfAbsent(instanceId, created);
                if (previous != null) {
                    created.close();
                    worker = previous;
                } else {
                    worker = created;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to initialize MediaPipe ImageSegmenter", t);
                return;
            }
        }
        if (worker.closed.get() || !worker.busy.compareAndSet(false, true)) return;

        final WorkerInstance current = worker;
        final int length = width * height * 4;
        if (current.stagingRgba == null || current.stagingRgba.length != length)
            current.stagingRgba = new byte[length];
        System.arraycopy(rgba, 0, current.stagingRgba, 0, length);
        final byte[] frame = current.stagingRgba;

        try {
            current.executor.execute(() -> {
                if (current.closed.get()) {
                    current.busy.set(false);
                    return;
                }
                try {
                    if (current.reusableBitmap == null || current.reusableBitmap.getWidth() != width
                            || current.reusableBitmap.getHeight() != height || current.reusableBitmap.isRecycled()) {
                        if (current.reusableBitmap != null && !current.reusableBitmap.isRecycled())
                            current.reusableBitmap.recycle();
                        current.reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    }
                    current.reusableBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame));
                    MPImage input = new BitmapImageBuilder(current.reusableBitmap).build();
                    long timestamp = Math.max(android.os.SystemClock.uptimeMillis(), current.lastTimestampMs + 1);
                    current.lastTimestampMs = timestamp;
                    ImageSegmenterResult result = current.segmenter.segmentForVideo(input, timestamp);
                    Optional<List<MPImage>> masks = result.confidenceMasks();
                    if (!current.closed.get() && masks.isPresent() && !masks.get().isEmpty()) {
                        MPImage personMask = masks.get().get(0);
                        ByteBuffer bytes = ByteBufferExtractor.extract(personMask).order(java.nio.ByteOrder.nativeOrder());
                        FloatBuffer values = bytes.asFloatBuffer();
                        int count = Math.min(personMask.getWidth() * personMask.getHeight(), values.remaining());
                        if (current.maskBytes == null || current.maskBytes.length != count)
                            current.maskBytes = new byte[count];
                        for (int i = 0; i < count; ++i) {
                            int value = Math.round(values.get(i) * 255.0f);
                            current.maskBytes[i] = (byte) Math.max(0, Math.min(255, value));
                        }
                        NativeBridge.pushBgMask(current.id, current.maskBytes, personMask.getWidth(), personMask.getHeight());
                        for (MPImage mask : masks.get()) mask.close();
                    }
                    input.close();
                } catch (Throwable t) {
                    Log.w(TAG, "MediaPipe segmentation failed", t);
                } finally {
                    current.busy.set(false);
                }
            });
        } catch (Throwable t) {
            current.busy.set(false);
        }
    }

    public static void destroyInstance(long instanceId) {
        WorkerInstance worker = sInstances.remove(instanceId);
        if (worker != null) worker.close();
    }

    public static void destroyAll() {
        for (Long id : sInstances.keySet()) {
            WorkerInstance worker = sInstances.remove(id);
            if (worker != null) worker.close();
        }
    }

    private BgSegmenterManager() {}
}
