package org.obsandroid.port;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class CameraController {
    private static volatile CameraController sActiveInstance;
    private final Context context;
    private final Runnable failure;
    private final HandlerThread thread = new HandlerThread("OBS-Camera2");
    private Handler handler;
    private volatile boolean stopped;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CameraCharacteristics characteristics;
    private CaptureRequest.Builder request;
    private String requestedDeviceId;
    private int requestedHeight = 720;
    private int requestedFps = 30;
    private boolean front;
    private volatile boolean cpuReadEnabled;
    private volatile boolean faceDetectActive;
    private volatile float targetZoom = 1.0f;
    private volatile int targetExposure = 0;
    private volatile boolean targetStabilization = true;
    private volatile boolean targetAeLock = false;
    private volatile boolean targetTorch = false;
    private boolean realtime;
    private int rotation;
    private boolean loggedDataSpace;

    CameraController(Context context, boolean front, Runnable failure) {
        this(context, front ? "front" : "default", 1.0f, 0, failure);
    }

    private void decodeControls(int raw) {
        if ((raw & 0x1000) != 0) {
            this.targetExposure = (raw & 0xFF) - 64;
            this.targetStabilization = (raw & 0x100) != 0;
            this.targetAeLock = (raw & 0x200) != 0;
            this.targetTorch = (raw & 0x400) != 0;
        } else {
            this.targetExposure = raw;
        }
    }

    CameraController(Context context, String deviceId, float initialZoom, int initialExposure, Runnable failure) {
        this.context = context;
        String raw = (deviceId == null || deviceId.isEmpty()) ? "default" : deviceId;
        int at = raw.indexOf('@');
        if (at >= 0) {
            this.requestedDeviceId = raw.substring(0, at);
            if (this.requestedDeviceId.isEmpty()) this.requestedDeviceId = "default";
            String suffix = raw.substring(at + 1);
            int colon = suffix.indexOf(':');
            if (colon >= 0) {
                try { this.requestedHeight = Integer.parseInt(suffix.substring(0, colon)); } catch (NumberFormatException ignored) {}
                try { this.requestedFps = Integer.parseInt(suffix.substring(colon + 1)); } catch (NumberFormatException ignored) {}
            } else {
                try { this.requestedHeight = Integer.parseInt(suffix); } catch (NumberFormatException ignored) {}
            }
        } else {
            this.requestedDeviceId = raw;
        }
        if (this.requestedFps != 60) this.requestedFps = 30;
        this.front = "front".equals(this.requestedDeviceId);
        this.targetZoom = Math.max(0.5f, initialZoom);
        decodeControls(initialExposure);
        this.failure = failure;
    }

    static String listCameras(Context context) {
        StringBuilder sb = new StringBuilder();
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            if (manager == null) return "";
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer lens = c.get(CameraCharacteristics.LENS_FACING);
                String facing = "Cámara";
                if (lens != null) {
                    if (lens == CameraCharacteristics.LENS_FACING_BACK) facing = "Cámara trasera";
                    else if (lens == CameraCharacteristics.LENS_FACING_FRONT) facing = "Cámara frontal";
                    else if (lens == CameraCharacteristics.LENS_FACING_EXTERNAL) facing = "Cámara USB externa";
                }
                float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                String focalInfo = (focals != null && focals.length > 0)
                    ? String.format(Locale.US, " · %.1fmm", focals[0])
                    : "";
                sb.append(id).append('\t').append(facing).append(" (ID ").append(id).append(focalInfo).append(")\n");
            }
        } catch (Exception e) {
            Log.w("ObsCamera", "listCameras failed", e);
        }
        return sb.toString();
    }

    void start() {
        thread.start(); handler = new Handler(thread.getLooper());
        EngineRuntime.EXECUTOR.execute(() -> {
            if (stopped) return;
            if (!NativeBridge.enableCapture(0, true)) { failed(new IllegalStateException("OBS camera source unavailable")); return; }
            handler.post(this::open);
        });
    }

    private void applyZoomAndExposureLocked() {
        if (request == null || characteristics == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zoomRange != null) {
                float clamped = Math.max(zoomRange.getLower(), Math.min(targetZoom, zoomRange.getUpper()));
                request.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped);
            }
        } else {
            Rect active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float maximum = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (active != null && maximum != null) {
                float zoom = Math.max(1f, Math.min(targetZoom, maximum));
                int halfWidth = Math.round(active.width() / (2f * zoom));
                int halfHeight = Math.round(active.height() / (2f * zoom));
                request.set(CaptureRequest.SCALER_CROP_REGION,
                    new Rect(active.centerX() - halfWidth, active.centerY() - halfHeight,
                             active.centerX() + halfWidth, active.centerY() + halfHeight));
            }
        }
        Range<Integer> aeRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (aeRange != null) {
            request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                Math.max(aeRange.getLower(), Math.min(targetExposure, aeRange.getUpper())));
        }
        // Video stabilization (EIS preferred, OIS fallback)
        boolean eisSupported = false;
        int[] videoStab = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (videoStab != null) {
            for (int m : videoStab) {
                if (m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) { eisSupported = true; break; }
            }
        }
        if (eisSupported) {
            request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                targetStabilization ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                                    : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        }
        int[] oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (oisModes != null) {
            for (int m : oisModes) {
                if (m == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                    request.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        targetStabilization ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                                            : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                    break;
                }
            }
        }
        // AE Lock
        Boolean aeLockAvail = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
        if (Boolean.TRUE.equals(aeLockAvail)) {
            request.set(CaptureRequest.CONTROL_AE_LOCK, targetAeLock);
        }
        // Torch / Flash (rear camera only)
        Boolean flashAvail = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        if (Boolean.TRUE.equals(flashAvail) && !front) {
            request.set(CaptureRequest.FLASH_MODE,
                targetTorch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        }
    }

    private int computeDisplayRelativeRotation() {
        int displayDeg = 90;
        try {
            WindowManager wm = context.getSystemService(WindowManager.class);
            if (wm != null && wm.getDefaultDisplay() != null) {
                int r = wm.getDefaultDisplay().getRotation();
                if (r == Surface.ROTATION_0) displayDeg = 0;
                else if (r == Surface.ROTATION_90) displayDeg = 90;
                else if (r == Surface.ROTATION_180) displayDeg = 180;
                else if (r == Surface.ROTATION_270) displayDeg = 270;
            }
        } catch (Throwable ignored) {}
        if (front) {
            return (rotation + displayDeg) % 360;
        }
        return (rotation - displayDeg + 360) % 360;
    }

    private final CameraCaptureSession.CaptureCallback faceCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult result) {
            if (stopped || characteristics == null) return;
            Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
            if (faces != null && faces.length > 0) {
                Rect active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                FaceTrackerManager.onCamera2Faces(faces, active, computeDisplayRelativeRotation(), front);
            }
        }
    };

    private void applyFaceDetectLocked(boolean enable) {
        if (request == null || characteristics == null) return;
        faceDetectActive = false;
        if (!enable) {
            request.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF);
            return;
        }
        int[] fdModes = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
        if (fdModes == null) return;
        int best = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;
        for (int m : fdModes) {
            if (m == CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) { best = m; break; }
            if (m == CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) { best = m; }
        }
        if (best != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
            request.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, best);
            faceDetectActive = true;
        }
    }

    static void refreshFaceTrackingMode() {
        CameraController inst = sActiveInstance;
        if (inst == null || inst.stopped || inst.handler == null) return;
        inst.handler.post(() -> {
            if (inst.stopped || inst.request == null || inst.session == null) return;
            boolean wantFace = VtuberSourceManager.isFaceTrackingNeeded();
            if (wantFace == inst.faceDetectActive) return;
            inst.applyFaceDetectLocked(wantFace);
            try {
                inst.session.setRepeatingRequest(inst.request.build(), inst.faceDetectActive ? inst.faceCallback : null, inst.handler);
                Log.i("ObsCamera", "Updated face detection mode: active=" + inst.faceDetectActive);
            } catch (Exception ignored) {}
        });
    }

    @SuppressWarnings("MissingPermission") private void open() {
        if (stopped) return;
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            String[] allIds = manager.getCameraIdList();
            String selected = null;
            for (String id : allIds) {
                if (id.equals(requestedDeviceId)) {
                    selected = id;
                    break;
                }
            }
            if (selected == null) {
                int wantFacing = "front".equals(requestedDeviceId)
                    ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
                for (String id : allIds) {
                    CameraCharacteristics candidate = manager.getCameraCharacteristics(id);
                    Integer lens = candidate.get(CameraCharacteristics.LENS_FACING);
                    if (lens != null && lens == wantFacing) { selected = id; break; }
                }
            }
            boolean explicitFacing = "front".equals(requestedDeviceId) || "back".equals(requestedDeviceId);
            if (selected == null && !explicitFacing && allIds.length > 0) selected = allIds[0];
            if (selected == null) throw new IllegalStateException("Requested camera is not available");
            characteristics = manager.getCameraCharacteristics(selected);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            front = lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT;
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException("Camera output formats unavailable");
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            final int tgtH = Math.max(480, Math.min(2160, requestedHeight));
            final int tgtW = (tgtH == 480) ? 854 : (tgtH == 1080 ? 1920 : (tgtH >= 1440 ? 2560 : 1280));
            final int maxW = tgtH >= 1440 ? 3840 : (tgtH >= 1080 ? 1920 : (tgtH >= 720 ? 1280 : 960));
            final int maxH = tgtH >= 1440 ? 2160 : (tgtH >= 1080 ? 1080 : (tgtH >= 720 ? 720 : 540));
            Size size = Arrays.stream(sizes).filter(s -> s.getWidth() <= maxW && s.getHeight() <= maxH)
                .min(Comparator.comparingLong(s -> Math.abs((long)s.getWidth()*s.getHeight()-(long)tgtW*tgtH) + 1000L*Math.abs(s.getWidth()*9-s.getHeight()*16)))
                .orElseGet(() -> Arrays.stream(sizes).filter(s -> s.getWidth() <= 1920 && s.getHeight() <= 1080)
                    .min(Comparator.comparingLong(s -> Math.abs((long)s.getWidth()*s.getHeight()-1280L*720)))
                    .orElse(sizes[0]));
            Integer timestampSource = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            realtime = timestampSource != null && timestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            rotation = orientation == null ? 0 : orientation;
            reader = null;
            final boolean wantFace = VtuberSourceManager.isFaceTrackingNeeded();
            cpuReadEnabled = front && wantFace;
            if (Build.VERSION.SDK_INT >= 29) {
                long usage = android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                    | (cpuReadEnabled ? android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN : 0L);
                try {
                    reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 4, usage);
                } catch (Exception ignored) {
                    reader = null;
                }
            }
            if (reader == null) {
                reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
                cpuReadEnabled = front;
            }
            reader.setOnImageAvailableListener(this::frame, handler);
            sActiveInstance = this;
            Log.i("ObsCamera", "Opening camera=" + selected + " size=" + size + " front=" + front + " zoom=" + targetZoom
                + " sensorRotation=" + rotation + " zeroCopyGpu=" + (Build.VERSION.SDK_INT >= 29)
                + " faceDetect=" + wantFace + " cpuRead=" + cpuReadEnabled);
            manager.openCamera(selected, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (stopped) { device.close(); return; }
                    camera = device;
                    try {
                        request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        request.addTarget(reader.getSurface());
                        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                        if (modes != null && Arrays.stream(modes).anyMatch(mode -> mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO))
                            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                        if (ranges != null) {
                            final int wantFps = requestedFps;
                            Arrays.stream(ranges).filter(range -> range.contains(wantFps))
                                .min(Comparator.comparingInt(range -> Math.abs(range.getUpper() - wantFps) * 100 + Math.abs(wantFps - range.getLower())))
                                .or(() -> Arrays.stream(ranges).filter(range -> range.contains(30))
                                    .min(Comparator.comparingInt(range -> Math.abs(range.getUpper() - 30) * 100 + 30 - range.getLower())))
                                .ifPresent(range -> {
                                    request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
                                    Log.i("ObsCamera", "Selected AE FPS range: " + range + " (requested=" + wantFps + ")");
                                });
                        }
                        applyZoomAndExposureLocked();
                        applyFaceDetectLocked(VtuberSourceManager.isFaceTrackingNeeded());
                        camera.createCaptureSession(Arrays.asList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession configured) {
                                if (stopped) { configured.close(); return; }
                                session = configured;
                                try { session.setRepeatingRequest(request.build(), faceDetectActive ? faceCallback : null, handler); }
                                catch (Exception error) { failed(error); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession configured) { configured.close(); failed(new IllegalStateException("Camera session failed")); }
                        }, handler);
                    } catch (Exception error) { failed(error); }
                }
                @Override public void onDisconnected(CameraDevice device) { device.close(); failed(new IllegalStateException("Camera disconnected")); }
                @Override public void onError(CameraDevice device, int code) { device.close(); failed(new IllegalStateException("Camera error " + code)); }
            }, handler);
        } catch (Exception error) { failed(error); }
    }

    private void frame(ImageReader source) {
        try (Image image = source.acquireLatestImage()) {
            if (image == null || stopped) return;
            long timestamp = realtime ? image.getTimestamp() + (System.nanoTime() - SystemClock.elapsedRealtimeNanos()) : System.nanoTime();
            int displayRot = computeDisplayRelativeRotation();
            boolean deliveredZeroCopy = false;
            if (Build.VERSION.SDK_INT >= 29) {
                android.hardware.HardwareBuffer hb = image.getHardwareBuffer();
                if (hb != null) {
                    try {
                        NativeBridge.cameraHardwareBuffer(hb, image.getWidth(), image.getHeight(), timestamp, displayRot, front);
                        deliveredZeroCopy = true;
                    } finally {
                        hb.close();
                    }
                }
            }
            if (!deliveredZeroCopy) {
                Image.Plane[] plane = image.getPlanes();
                boolean full = true, rec709 = false;
                if (Build.VERSION.SDK_INT >= 33) {
                    int space = image.getDataSpace();
                    int range = android.hardware.DataSpace.getRange(space);
                    int standard = android.hardware.DataSpace.getStandard(space);
                    if (range == android.hardware.DataSpace.RANGE_LIMITED) full = false;
                    else if (range == android.hardware.DataSpace.RANGE_FULL) full = true;
                    if (standard == android.hardware.DataSpace.STANDARD_BT709) rec709 = true;
                    else if (standard == android.hardware.DataSpace.STANDARD_BT601_625
                             || standard == android.hardware.DataSpace.STANDARD_BT601_525) rec709 = false;
                    if (!loggedDataSpace) {
                        loggedDataSpace = true;
                        Log.i("ObsCamera", "Camera2 DataSpace=" + space + " range=" + range + " std=" + standard
                            + " -> full=" + full + " rec709=" + rec709);
                    }
                }
                NativeBridge.cameraFrame(plane[0].getBuffer().slice(), plane[1].getBuffer().slice(), plane[2].getBuffer().slice(),
                    plane[0].getRowStride(), plane[1].getRowStride(), plane[2].getRowStride(), plane[1].getPixelStride(), plane[2].getPixelStride(),
                    image.getWidth(), image.getHeight(), timestamp, displayRot, front, full, rec709);
            }
            if (front && cpuReadEnabled && VtuberSourceManager.isFaceTrackingNeeded()) {
                try { FaceTrackerManager.onFrontCameraYuv(image, true); } catch (Exception ignored) {}
            }
        } catch (IllegalStateException error) { if (!stopped) failed(error); }
    }

    void setZoom(float value) {
        targetZoom = Math.max(0.5f, value);
        if (handler == null) return;
        handler.post(() -> {
            if (stopped || request == null || session == null) return;
            applyZoomAndExposureLocked();
            try { session.setRepeatingRequest(request.build(), faceDetectActive ? faceCallback : null, handler); } catch (Exception error) { failed(error); }
        });
    }

    void setExposure(int compensation) {
        decodeControls(compensation);
        if (handler == null) return;
        handler.post(() -> {
            if (stopped || request == null || session == null) return;
            applyZoomAndExposureLocked();
            try { session.setRepeatingRequest(request.build(), faceDetectActive ? faceCallback : null, handler); } catch (Exception error) { failed(error); }
        });
    }

    private void failed(Exception error) { Log.e("ObsCamera", "Camera2", error); if (!stopped) failure.run(); }

    void stop() {
        stopped = true;
        if (sActiveInstance == this) sActiveInstance = null;
        if (handler != null) {
            CountDownLatch closed = new CountDownLatch(1);
            handler.post(() -> {
                if (session != null) session.close(); session = null;
                if (camera != null) camera.close(); camera = null;
                if (reader != null) reader.close(); reader = null;
                closed.countDown();
            });
            try { closed.await(2, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            thread.quitSafely();
        }
        EngineRuntime.EXECUTOR.execute(() -> NativeBridge.enableCapture(0, false));
    }
}
