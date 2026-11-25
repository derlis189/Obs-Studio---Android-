// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.graphics.Bitmap;
import android.util.Log;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BgSegmenterManager {
    private static final String TAG = "ObsBgSegmenter";

    private static final class WorkerInstance {
        final long id;
        final Segmenter segmenter;
        final ExecutorService executor;
        final AtomicBoolean busy = new AtomicBoolean(false);
        final AtomicBoolean closed = new AtomicBoolean(false);
        Bitmap reusableBitmap;
        byte[] maskBytes;
        byte[] stagingRgba;

        WorkerInstance(long id) {
            this.id = id;
            SelfieSegmenterOptions options = new SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .enableRawSizeMask()
                .build();
            this.segmenter = Segmentation.getClient(options);
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
        if (rgba == null || width <= 0 || height <= 0 || rgba.length < width * height * 4) {
            return;
        }
        WorkerInstance inst = sInstances.get(instanceId);
        if (inst == null) {
            try {
                WorkerInstance created = new WorkerInstance(instanceId);
                WorkerInstance prev = sInstances.putIfAbsent(instanceId, created);
                if (prev != null) {
                    created.close();
                    inst = prev;
                } else {
                    inst = created;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to initialize ML Kit SelfieSegmenter", t);
                return;
            }
        }
        if (inst.closed.get() || !inst.busy.compareAndSet(false, true)) {
            return; // Drop frame cleanly if inference is still running (zero render latency!)
        }

        final WorkerInstance worker = inst;
        final int expectedLen = width * height * 4;
        if (worker.stagingRgba == null || worker.stagingRgba.length != expectedLen) {
            worker.stagingRgba = new byte[expectedLen];
        }
        System.arraycopy(rgba, 0, worker.stagingRgba, 0, expectedLen);
        final byte[] localRgba = worker.stagingRgba;

        try {
            worker.executor.execute(() -> {
                if (worker.closed.get()) {
                    worker.busy.set(false);
                    return;
                }
                try {
                    if (worker.reusableBitmap == null
                            || worker.reusableBitmap.getWidth() != width
                            || worker.reusableBitmap.getHeight() != height
                            || worker.reusableBitmap.isRecycled()) {
                        if (worker.reusableBitmap != null && !worker.reusableBitmap.isRecycled()) {
                            worker.reusableBitmap.recycle();
                        }
                        worker.reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    }
                    worker.reusableBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(localRgba));
                    InputImage image = InputImage.fromBitmap(worker.reusableBitmap, 0);
                    worker.segmenter.process(image)
                        .addOnSuccessListener(worker.executor, mask -> {
                            try {
                                if (!worker.closed.get() && mask != null) {
                                    int mw = mask.getWidth();
                                    int mh = mask.getHeight();
                                    ByteBuffer buf = mask.getBuffer();
                                    if (buf != null && mw > 0 && mh > 0) {
                                        buf.rewind();
                                        FloatBuffer fb = buf.asFloatBuffer();
                                        int total = mw * mh;
                                        if (worker.maskBytes == null || worker.maskBytes.length != total) {
                                            worker.maskBytes = new byte[total];
                                        }
                                        byte[] out = worker.maskBytes;
                                        int count = Math.min(total, fb.remaining());
                                        for (int i = 0; i < count; ++i) {
                                            float v = fb.get(i);
                                            int q = (int) (v * 255.0f + 0.5f);
                                            if (q < 0) q = 0;
                                            else if (q > 255) q = 255;
                                            out[i] = (byte) q;
                                        }
                                        NativeBridge.pushBgMask(worker.id, out, mw, mh);
                                    }
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "Mask readback error", t);
                            } finally {
                                worker.busy.set(false);
                            }
                        })
                        .addOnFailureListener(worker.executor, e -> {
                            Log.w(TAG, "Segmentation inference failed: " + e.getMessage());
                            worker.busy.set(false);
                        });
                } catch (Throwable t) {
                    Log.w(TAG, "Segmentation dispatch failed", t);
                    worker.busy.set(false);
                }
            });
        } catch (Throwable t) {
            worker.busy.set(false);
        }
    }

    public static void destroyInstance(long instanceId) {
        WorkerInstance inst = sInstances.remove(instanceId);
        if (inst != null) {
            inst.close();
        }
    }

    public static void destroyAll() {
        for (Long key : sInstances.keySet()) {
            WorkerInstance inst = sInstances.remove(key);
            if (inst != null) {
                inst.close();
            }
        }
    }

    private BgSegmenterManager() {}
}
