// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class FaceTrackerManager {
    private static final String TAG = "ObsFaceTracker";

    public static final class TrackingState {
        public volatile float yaw;      // -45..+45 deg
        public volatile float pitch;    // -35..+35 deg
        public volatile float roll;     // -40..+40 deg
        public volatile float eyeL = 1.0f; // 0=closed, 1=open
        public volatile float eyeR = 1.0f;
        public volatile float lookX;       // -1..+1 eye gaze horizontal
        public volatile float lookY;       // -1..+1 eye gaze vertical
        public volatile float browL;       // -1..+1 left eyebrow
        public volatile float browR;       // -1..+1 right eyebrow
        public volatile float cheekPuff;   // 0..1
        public volatile float mouthOpen;   // 0..1
        public volatile float mouthA, mouthI, mouthU, mouthE, mouthO; // 5-vowel visemes
        public volatile float happy;       // 0..1
        public volatile float sad;         // 0..1
        public volatile float angry;       // 0..1
        public volatile float surprised;   // 0..1
        public volatile float faceX;       // -1..1
        public volatile float faceY;       // -1..1
        public volatile float handL;       // 0..1 left hand raised / waving
        public volatile float handR;       // 0..1 right hand raised / waving
        public volatile float handLX = 0.25f, handLY = 0.75f;
        public volatile float handRX = 0.75f, handRY = 0.75f;
        public volatile float fingerCurlL, fingerCurlR; // 0=open, 1=fist
        public volatile int handGestureL, handGestureR; // 0=none,1=open,2=fist,3=peace,4=point
        public volatile float waveOsc;     // -1..+1 live hand wave oscillation
        public volatile String activeGesture = "";
        public volatile long activeGestureUntilMs = 0L;
        public volatile boolean faceDetected;
        public volatile long lastUpdateMs;
        public volatile boolean mpFaceActive;
        public volatile long lastMpFaceMs;
        public volatile boolean mpHandsActive;
        public volatile long lastMpHandsMs;
        public volatile boolean vmcActive;
        public volatile long lastVmcMs;
        // Full 52 ARKit / VMC blendshapes & full humanoid VMC bone quaternions [qx, qy, qz, qw]
        public final ConcurrentHashMap<String, Float> blendshapes = new ConcurrentHashMap<>();
        public final ConcurrentHashMap<String, float[]> vmcBones = new ConcurrentHashMap<>();
    }

    private static final TrackingState sState = new TrackingState();
    private static Thread sVmcThread;
    private static volatile boolean sVmcRunning = false;
    private static volatile DatagramSocket sVmcSocket;
    private static float sSmoothYaw, sSmoothPitch, sSmoothRoll, sSmoothX, sSmoothY;

    // Fast YUV skin+motion hand-wave detector state (32x24 subsampled grid, <0.25 ms/frame)
    private static final int GRID_W = 32;
    private static final int GRID_H = 24;
    private static final byte[] sPrevLuma = new byte[GRID_W * GRID_H];
    private static boolean sHasPrevLuma = false;
    private static long sLastHandAnalyzeMs = 0L;
    private static volatile float sFaceL = 0.35f, sFaceT = 0.25f, sFaceR = 0.65f, sFaceB = 0.65f;
    private static float sSkinU = 114f, sSkinV = 148f;
    private static float sHandLHold = 0f, sHandRHold = 0f;
    private static float sPrevHandCX = 0.5f, sWaveVel = 0f;
    private static int sWaveStreakL = 0, sWaveStreakR = 0;
    private static long sLastHandLeftMs = 0L, sLastHandRightMs = 0L;

    // Lightweight headless front-camera face tracker (used only when main CameraController is inactive)
    private static HandlerThread sBgThread;
    private static Handler sBgHandler;
    private static CameraDevice sBgCamera;
    private static CameraCaptureSession sBgSession;
    private static ImageReader sBgReader;
    private static volatile boolean sBgActive = false;

    @SuppressWarnings("MissingPermission")
    public static synchronized void startBackgroundFrontCamera(Context ctx) {
        if (ctx == null || sBgActive || CaptureService.cameraActive) return;
        Context app = ctx.getApplicationContext();
        if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        sBgActive = true;
        sBgThread = new HandlerThread("OBS-FaceTrackBg");
        sBgThread.start();
        sBgHandler = new Handler(sBgThread.getLooper());
        sBgHandler.post(() -> {
            if (!sBgActive || CaptureService.cameraActive) { stopBackgroundFrontCamera(); return; }
            try {
                CameraManager mgr = app.getSystemService(CameraManager.class);
                if (mgr == null) { sBgActive = false; return; }
                String frontId = null;
                CameraCharacteristics chosenChars = null;
                for (String id : mgr.getCameraIdList()) {
                    CameraCharacteristics c = mgr.getCameraCharacteristics(id);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        frontId = id;
                        chosenChars = c;
                        break;
                    }
                }
                if (frontId == null || chosenChars == null) { sBgActive = false; return; }
                final CameraCharacteristics chars = chosenChars;
                final Rect activeArr = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                int[] fdModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
                int bestFd = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;
                if (fdModes != null) {
                    for (int m : fdModes) {
                        if (m == CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) { bestFd = m; break; }
                        if (m == CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) bestFd = m;
                    }
                }
                final int finalFd = bestFd;
                sBgReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2);
                sBgReader.setOnImageAvailableListener(reader -> {
                    try (Image img = reader.acquireLatestImage()) {
                        if (img != null) {
                            onFrontCameraYuv(img, true);
                        }
                    } catch (Throwable ignored) {}
                }, sBgHandler);

                final String camId = frontId;
                mgr.openCamera(camId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice device) {
                        if (!sBgActive || CaptureService.cameraActive) { device.close(); return; }
                        sBgCamera = device;
                        try {
                            CaptureRequest.Builder req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            req.addTarget(sBgReader.getSurface());
                            req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                            if (finalFd != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                                req.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, finalFd);
                            }
                            device.createCaptureSession(Collections.singletonList(sBgReader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        if (!sBgActive || CaptureService.cameraActive) { session.close(); return; }
                                        sBgSession = session;
                                        try {
                                            session.setRepeatingRequest(req.build(), new CameraCaptureSession.CaptureCallback() {
                                                @Override
                                                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                                                    if (!sBgActive) return;
                                                    Face[] faces = res.get(CaptureResult.STATISTICS_FACES);
                                                    if (faces != null && faces.length > 0) {
                                                        onCamera2Faces(faces, activeArr, 0, true);
                                                    }
                                                }
                                            }, sBgHandler);
                                            Log.i(TAG, "Headless front-camera face tracking started (cam=" + camId + ", fdMode=" + finalFd + ")");
                                        } catch (Throwable e) {
                                            Log.w(TAG, "Headless face tracking request failed", e);
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession session) {
                                        stopBackgroundFrontCamera();
                                    }
                                }, sBgHandler);
                        } catch (Throwable e) {
                            Log.w(TAG, "Headless face tracking open failed", e);
                            stopBackgroundFrontCamera();
                        }
                    }

                    @Override public void onDisconnected(CameraDevice device) { device.close(); sBgActive = false; }
                    @Override public void onError(CameraDevice device, int error) { device.close(); sBgActive = false; }
                }, sBgHandler);
            } catch (Throwable t) {
                Log.w(TAG, "startBackgroundFrontCamera error", t);
                sBgActive = false;
            }
        });
    }

    public static synchronized void stopBackgroundFrontCamera() {
        if (!sBgActive && sBgCamera == null && sBgThread == null) return;
        sBgActive = false;
        Handler h = sBgHandler;
        HandlerThread t = sBgThread;
        sBgHandler = null;
        sBgThread = null;
        if (h != null) {
            CountDownLatch latch = new CountDownLatch(1);
            h.post(() -> {
                try { if (sBgSession != null) sBgSession.close(); } catch (Throwable ignored) {}
                try { if (sBgCamera != null) sBgCamera.close(); } catch (Throwable ignored) {}
                try { if (sBgReader != null) sBgReader.close(); } catch (Throwable ignored) {}
                sBgSession = null;
                sBgCamera = null;
                sBgReader = null;
                latch.countDown();
            });
            try { latch.await(1500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        }
        if (t != null) t.quitSafely();
        Log.i(TAG, "Headless front-camera face tracking stopped");
    }

    public static TrackingState getState() {
        return sState;
    }

    public static void onCamera2Faces(Face[] faces, Rect activeArray, int displayRelRot, boolean front) {
        if (faces == null || faces.length == 0 || activeArray == null) return;
        long now = System.currentTimeMillis();
        // Prefer external VMC/OSC or on-device MediaPipe 52-blendshape precision when active
        if (sState.vmcActive && (now - sState.lastVmcMs) < 1500L) return;
        if (sState.mpFaceActive && (now - sState.lastMpFaceMs) < 900L) return;

        Face best = faces[0];
        int bestArea = 0;
        for (Face f : faces) {
            if (f == null || f.getBounds() == null) continue;
            int area = f.getBounds().width() * f.getBounds().height();
            if (area > bestArea) {
                bestArea = area;
                best = f;
            }
        }
        Rect b = best.getBounds();
        if (b == null || activeArray.width() <= 0 || activeArray.height() <= 0) return;

        float nx = (b.exactCenterX() - activeArray.left) / (float) activeArray.width();
        float ny = (b.exactCenterY() - activeArray.top) / (float) activeArray.height();
        sFaceL = Math.max(0f, Math.min(1f, (b.left - activeArray.left) / (float) activeArray.width()));
        sFaceT = Math.max(0f, Math.min(1f, (b.top - activeArray.top) / (float) activeArray.height()));
        sFaceR = Math.max(0f, Math.min(1f, (b.right - activeArray.left) / (float) activeArray.width()));
        sFaceB = Math.max(0f, Math.min(1f, (b.bottom - activeArray.top) / (float) activeArray.height()));
        // Map sensor normalized coords to horizontal display coords
        float fx = (nx - 0.5f) * 2.0f;
        float fy = (ny - 0.5f) * 2.0f;
        if (front) fx = -fx;

        float rawYaw = Math.max(-35f, Math.min(35f, fx * 28f));
        float rawPitch = Math.max(-25f, Math.min(25f, -fy * 22f));
        float rawRoll = 0f;

        Point leftEye = best.getLeftEyePosition();
        Point rightEye = best.getRightEyePosition();
        Point mouth = best.getMouthPosition();
        if (leftEye != null && rightEye != null) {
            float dx = rightEye.x - leftEye.x;
            float dy = rightEye.y - leftEye.y;
            float dist = (float) Math.hypot(dx, dy);
            if (dist > 4f) {
                rawRoll = (float) Math.toDegrees(Math.atan2(dy, dx));
                if (rawRoll > 90f) rawRoll -= 180f;
                else if (rawRoll < -90f) rawRoll += 180f;
                if (front) rawRoll = -rawRoll;
                rawRoll = Math.max(-35f, Math.min(35f, rawRoll));

                if (mouth != null) {
                    float midEyeX = (leftEye.x + rightEye.x) * 0.5f;
                    float midEyeY = (leftEye.y + rightEye.y) * 0.5f;
                    float mouthOffsetX = (mouth.x - midEyeX) / dist;
                    float mouthRatioY = (mouth.y - midEyeY) / dist;
                    rawYaw = Math.max(-35f, Math.min(35f, (front ? -mouthOffsetX : mouthOffsetX) * 45f + fx * 15f));
                    rawPitch = Math.max(-25f, Math.min(25f, (0.95f - mouthRatioY) * 32f - fy * 10f));
                }
            }
        }

        // Smooth exponential moving average to avoid jitter
        float alpha = 0.28f;
        sSmoothYaw += (rawYaw - sSmoothYaw) * alpha;
        sSmoothPitch += (rawPitch - sSmoothPitch) * alpha;
        sSmoothRoll += (rawRoll - sSmoothRoll) * alpha;
        sSmoothX += (fx - sSmoothX) * alpha;
        sSmoothY += (fy - sSmoothY) * alpha;

        sState.yaw = sSmoothYaw;
        sState.pitch = sSmoothPitch;
        sState.roll = sSmoothRoll;
        sState.faceX = sSmoothX;
        sState.faceY = sSmoothY;
        sState.faceDetected = true;
        sState.lastUpdateMs = now;
    }

    public static void onFrontCameraYuv(Image img, boolean front) {
        if (img == null) return;
        // Run MediaPipe Face Landmarker (52 ARKit blendshapes) + Hand Landmarker asynchronously first
        MediaPipeVtuberTracker.submitYuvFrame(img, front, sState);
        // Never run the legacy skin-motion heuristic when MediaPipe HandLandmarker is available
        // or when hand tracking is disabled — otherwise shoulders/lighting flicker trigger false waves!
        if (MediaPipeVtuberTracker.isHandTrackerReady() || !MediaPipeVtuberTracker.isHandTrackingWanted()) return;
        long now = System.currentTimeMillis();
        if (now - sLastHandAnalyzeMs < 60L) return; // ~16 Hz fallback hand-wave tracker (<0.25 ms CPU)
        sLastHandAnalyzeMs = now;
        try {
            Image.Plane[] planes = img.getPlanes();
            if (planes == null || planes.length < 3) return;
            int w = img.getWidth();
            int h = img.getHeight();
            if (w < 32 || h < 24) return;

            ByteBuffer yBuf = planes[0].getBuffer();
            ByteBuffer uBuf = planes[1].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();
            int yRow = planes[0].getRowStride();
            int yPix = planes[0].getPixelStride();
            int uRow = planes[1].getRowStride();
            int uPix = planes[1].getPixelStride();
            int vRow = planes[2].getRowStride();
            int vPix = planes[2].getPixelStride();
            int yCap = yBuf.limit();
            int uCap = uBuf.limit();
            int vCap = vBuf.limit();

            float fL = sFaceL, fT = sFaceT, fR = sFaceR, fB = sFaceB;
            float fW = Math.max(0.08f, fR - fL);
            float fH = Math.max(0.08f, fB - fT);

            // 1) Adapt skin Cb/Cr chroma from face center whenever a face was detected recently
            if (sState.faceDetected && (now - sState.lastUpdateMs) < 2000L) {
                float sumU = 0f, sumV = 0f;
                int cnt = 0;
                for (int sy = 0; sy < 4; ++sy) {
                    float ny = fT + fH * (0.28f + sy * 0.14f);
                    int cy = Math.max(0, Math.min(h / 2 - 1, (int) (ny * (h / 2))));
                    for (int sx = 0; sx < 4; ++sx) {
                        float nx = fL + fW * (0.28f + sx * 0.14f);
                        int cx = Math.max(0, Math.min(w / 2 - 1, (int) (nx * (w / 2))));
                        int uIdx = cy * uRow + cx * uPix;
                        int vIdx = cy * vRow + cx * vPix;
                        if (uIdx >= 0 && uIdx < uCap && vIdx >= 0 && vIdx < vCap) {
                            sumU += (uBuf.get(uIdx) & 0xFF);
                            sumV += (vBuf.get(vIdx) & 0xFF);
                            cnt++;
                        }
                    }
                }
                if (cnt > 0) {
                    sSkinU += ((sumU / cnt) - sSkinU) * 0.18f;
                    sSkinV += ((sumV / cnt) - sSkinV) * 0.18f;
                }
            }

            // 2) Scan 32x24 grid outside the face box for actively waving skin clusters on left & right sides
            float padX = fW * 0.25f;
            float padY = fH * 0.15f;
            float exL = fL - padX, exR = fR + padX, exT = fT - padY, exB = fB + fH * 0.35f;
            float maxHandY = Math.min(0.82f, fB + fH * 0.18f);
            float faceMidX = (fL + fR) * 0.5f;

            int movingSkinA = 0, movingSkinB = 0;
            int totalMovingCells = 0;
            float sumXA = 0f, sumXB = 0f;

            for (int gy = 0; gy < GRID_H; ++gy) {
                float ny = (gy + 0.5f) / GRID_H;
                int py = Math.min(h - 1, (int) (ny * h));
                int cy = Math.min(h / 2 - 1, py >> 1);
                for (int gx = 0; gx < GRID_W; ++gx) {
                    float nx = (gx + 0.5f) / GRID_W;
                    int px = Math.min(w - 1, (int) (nx * w));
                    int cx = Math.min(w / 2 - 1, px >> 1);

                    int yIdx = py * yRow + px * yPix;
                    int uIdx = cy * uRow + cx * uPix;
                    int vIdx = cy * vRow + cx * vPix;
                    if (yIdx < 0 || yIdx >= yCap || uIdx < 0 || uIdx >= uCap || vIdx < 0 || vIdx >= vCap) continue;

                    int yVal = yBuf.get(yIdx) & 0xFF;
                    int idx = gy * GRID_W + gx;
                    int diff = sHasPrevLuma ? Math.abs(yVal - (sPrevLuma[idx] & 0xFF)) : 0;
                    sPrevLuma[idx] = (byte) yVal;

                    boolean moving = diff > 22;
                    if (moving) totalMovingCells++;

                    // Only accept raised hand zone (beside/above face, never static background or shoulders)
                    if (!moving || ny > maxHandY) continue;
                    if (nx >= exL && nx <= exR && ny >= exT && ny <= exB) continue;
                    if (nx >= fL && nx <= fR) continue;

                    int uVal = uBuf.get(uIdx) & 0xFF;
                    int vVal = vBuf.get(vIdx) & 0xFF;
                    float chromaDist = Math.abs(uVal - sSkinU) + Math.abs(vVal - sSkinV);
                    boolean isSkin = (chromaDist < 26f) && (yVal > 50 && yVal < 242);

                    if (isSkin) {
                        if (nx < faceMidX) {
                            movingSkinA++;
                            sumXA += nx;
                        } else {
                            movingSkinB++;
                            sumXB += nx;
                        }
                    }
                }
            }
            sHasPrevLuma = true;

            // Reject global camera shake (moving/tilting the phone moves the whole frame)
            boolean cameraStable = (totalMovingCells < 140) && sState.faceDetected && ((now - sState.lastUpdateMs) < 1500L);
            int leftMovSkin = cameraStable ? (front ? movingSkinB : movingSkinA) : 0;
            int rightMovSkin = cameraStable ? (front ? movingSkinA : movingSkinB) : 0;

            if (leftMovSkin >= 7) sWaveStreakL = Math.min(6, sWaveStreakL + 1);
            else sWaveStreakL = Math.max(0, sWaveStreakL - 1);

            if (rightMovSkin >= 7) sWaveStreakR = Math.min(6, sWaveStreakR + 1);
            else sWaveStreakR = Math.max(0, sWaveStreakR - 1);

            boolean triggerL = sWaveStreakL >= 3;
            boolean triggerR = sWaveStreakR >= 3;

            if (triggerL) sLastHandLeftMs = now;
            if (triggerR) sLastHandRightMs = now;

            boolean activeL = (now - sLastHandLeftMs) < 650L;
            boolean activeR = (now - sLastHandRightMs) < 650L;

            sHandLHold += ((activeL ? 1f : 0f) - sHandLHold) * (activeL ? 0.35f : 0.20f);
            sHandRHold += ((activeR ? 1f : 0f) - sHandRHold) * (activeR ? 0.35f : 0.20f);
            if (sHandLHold < 0.01f) sHandLHold = 0f;
            if (sHandRHold < 0.01f) sHandRHold = 0f;

            // Measure horizontal hand centroid velocity for natural wave sync
            float curCX = sPrevHandCX;
            if (triggerR && rightMovSkin > 0) curCX = (front ? (sumXA / Math.max(1, movingSkinA)) : (sumXB / Math.max(1, movingSkinB)));
            else if (triggerL && leftMovSkin > 0) curCX = (front ? (sumXB / Math.max(1, movingSkinB)) : (sumXA / Math.max(1, movingSkinA)));
            float deltaX = (curCX - sPrevHandCX) * 18f;
            sPrevHandCX = curCX;
            sWaveVel = sWaveVel * 0.7f + Math.max(-1f, Math.min(1f, deltaX)) * 0.3f;

            sState.handL = sHandLHold;
            sState.handR = sHandRHold;
            sState.waveOsc = sWaveVel;
        } catch (Throwable ignored) {}
    }

    public static synchronized void startVmcReceiver(int port) {
        if (sVmcRunning) return;
        sVmcRunning = true;
        sVmcThread = new Thread(() -> {
            try (DatagramSocket sock = new DatagramSocket(port)) {
                sVmcSocket = sock;
                sock.setSoTimeout(500);
                byte[] buf = new byte[2048];
                Log.i(TAG, "VMC/OSC face tracker UDP receiver listening on port " + port);
                while (sVmcRunning) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        sock.receive(pkt);
                        parseOscPacket(buf, 0, pkt.getLength());
                    } catch (java.net.SocketTimeoutException ignored) {}
                }
            } catch (Throwable t) {
                Log.w(TAG, "VMC UDP receiver stopped: " + t.getMessage());
            } finally {
                sVmcSocket = null;
                sVmcRunning = false;
            }
        }, "obs-vmc-osc");
        sVmcThread.start();
    }

    public static synchronized void stopVmcReceiver() {
        sVmcRunning = false;
        DatagramSocket s = sVmcSocket;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private static int readPaddedString(byte[] data, int off, int end, StringBuilder out) {
        int i = off;
        while (i < end && data[i] != 0) i++;
        out.append(new String(data, off, i - off, StandardCharsets.UTF_8));
        int next = ((i + 1) + 3) & ~3;
        return Math.min(next, end);
    }

    private static void parseOscPacket(byte[] data, int off, int len) {
        int end = off + len;
        if (len < 8) return;
        StringBuilder addr = new StringBuilder();
        int p = readPaddedString(data, off, end, addr);
        String address = addr.toString();
        if ("#bundle".equals(address)) {
            p += 8; // skip timetag
            while (p + 4 <= end) {
                int elemLen = ByteBuffer.wrap(data, p, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                p += 4;
                if (elemLen <= 0 || p + elemLen > end) break;
                parseOscPacket(data, p, elemLen);
                p += elemLen;
            }
            return;
        }
        if (p >= end || data[p] != ',') return;
        StringBuilder tags = new StringBuilder();
        p = readPaddedString(data, p, end, tags);
        String tagStr = tags.toString();

        if ("/VMC/Ext/Blend/Val".equals(address) && tagStr.startsWith(",sf")) {
            StringBuilder name = new StringBuilder();
            p = readPaddedString(data, p, end, name);
            if (p + 4 <= end) {
                float val = Math.max(0f, Math.min(1f, ByteBuffer.wrap(data, p, 4).order(ByteOrder.BIG_ENDIAN).getFloat()));
                String rawName = name.toString();
                sState.blendshapes.put(rawName, val);
                String k = rawName.toLowerCase(Locale.US);
                if (k.equals("a") || k.equals("aa") || k.equals("jawopen")) { sState.mouthOpen = val; sState.mouthA = val; }
                else if (k.equals("i") || k.equals("ih")) sState.mouthI = val;
                else if (k.equals("u") || k.equals("ou")) sState.mouthU = val;
                else if (k.equals("e") || k.equals("ee")) sState.mouthE = val;
                else if (k.equals("o") || k.equals("oh")) sState.mouthO = val;
                else if (k.equals("blink_l") || k.equals("blinkleft") || k.equals("eyeblinkleft")) sState.eyeL = 1f - val;
                else if (k.equals("blink_r") || k.equals("blinkright") || k.equals("eyeblinkright")) sState.eyeR = 1f - val;
                else if (k.equals("joy") || k.equals("happy") || k.equals("fun")) sState.happy = val;
                else if (k.equals("sorrow") || k.equals("sad") || k.equals("sadness")) sState.sad = val;
                else if (k.equals("angry") || k.equals("anger")) sState.angry = val;
                else if (k.equals("surprised") || k.equals("surprise")) sState.surprised = val;
                sState.vmcActive = true;
                sState.faceDetected = true;
                sState.lastVmcMs = System.currentTimeMillis();
            }
        } else if ("/VMC/Ext/Bone/Pos".equals(address) && tagStr.startsWith(",sfffffff")) {
            StringBuilder bone = new StringBuilder();
            p = readPaddedString(data, p, end, bone);
            if (p + 28 <= end) {
                ByteBuffer bb = ByteBuffer.wrap(data, p, 28).order(ByteOrder.BIG_ENDIAN);
                bb.getFloat(); bb.getFloat(); bb.getFloat(); // px, py, pz
                float qx = bb.getFloat(), qy = bb.getFloat(), qz = bb.getFloat(), qw = bb.getFloat();
                String boneName = bone.toString();
                // Store full humanoid skeleton bone quaternion for VRM/IK consumers
                if (!boneName.isEmpty() && sState.vmcBones.size() < 64) {
                    sState.vmcBones.put(boneName, new float[]{qx, qy, qz, qw});
                }
                if ("Head".equalsIgnoreCase(boneName)) {
                    double sinp = 2.0 * (qw * qx + qy * qz);
                    double cosp = 1.0 - 2.0 * (qx * qx + qy * qy);
                    double pitchRad = Math.atan2(sinp, cosp);
                    double siny = 2.0 * (qw * qy - qz * qx);
                    double yawRad = Math.abs(siny) >= 1.0 ? Math.copySign(Math.PI / 2.0, siny) : Math.asin(siny);
                    double sinr = 2.0 * (qw * qz + qx * qy);
                    double cosr = 1.0 - 2.0 * (qy * qy + qz * qz);
                    double rollRad = Math.atan2(sinr, cosr);
                    sState.pitch = (float) Math.toDegrees(pitchRad);
                    sState.yaw = (float) Math.toDegrees(yawRad);
                    sState.roll = (float) Math.toDegrees(rollRad);
                }
                sState.vmcActive = true;
                sState.faceDetected = true;
                sState.lastVmcMs = System.currentTimeMillis();
            }
        }
    }

    public static void triggerGesture(String gestureId, long durationMs) {
        if (gestureId == null || "reset".equalsIgnoreCase(gestureId)) {
            sState.activeGesture = "";
            sState.activeGestureUntilMs = 0L;
            return;
        }
        sState.activeGesture = gestureId.toLowerCase(Locale.US);
        sState.activeGestureUntilMs = System.currentTimeMillis() + Math.max(500L, durationMs);
    }

    public static void injectVtsParameter(String paramId, float value) {
        long now = System.currentTimeMillis();
        switch (paramId) {
            case "FaceAngleX": sState.yaw = Math.max(-45f, Math.min(45f, value)); break;
            case "FaceAngleY": sState.pitch = Math.max(-35f, Math.min(35f, value)); break;
            case "FaceAngleZ": sState.roll = Math.max(-40f, Math.min(40f, value)); break;
            case "MouthOpen": sState.mouthOpen = Math.max(0f, Math.min(1f, value)); sState.mouthA = sState.mouthOpen; break;
            case "MouthSmile": sState.happy = Math.max(0f, Math.min(1f, value)); break;
            case "EyeOpenLeft": sState.eyeL = Math.max(0f, Math.min(1f, value)); break;
            case "EyeOpenRight": sState.eyeR = Math.max(0f, Math.min(1f, value)); break;
            case "Brows": sState.browL = sState.browR = Math.max(-1f, Math.min(1f, value)); break;
            case "CheekPuff": sState.cheekPuff = Math.max(0f, Math.min(1f, value)); break;
            case "HandLeft": sState.handL = Math.max(0f, Math.min(1f, value)); break;
            case "HandRight": sState.handR = Math.max(0f, Math.min(1f, value)); break;
            default:
                sState.blendshapes.put(paramId, Math.max(0f, Math.min(1f, value)));
                break;
        }
        sState.vmcActive = true;
        sState.faceDetected = true;
        sState.lastVmcMs = now;
    }

    public static String buildWebTrackingPayloadJson(float mic) {
        TrackingState ts = sState;
        long now = System.currentTimeMillis();
        boolean vmcFresh = ts.vmcActive && (now - ts.lastVmcMs) < 1500L;
        boolean mpFresh = ts.mpFaceActive && (now - ts.lastMpFaceMs) < 1200L;
        boolean exprFresh = vmcFresh || mpFresh;
        String gesture = (ts.activeGestureUntilMs > now && ts.activeGesture != null) ? ts.activeGesture : "";

        StringBuilder sb = new StringBuilder(512);
        sb.append(String.format(Locale.US,
            "{\"yaw\":%.2f,\"pitch\":%.2f,\"roll\":%.2f,\"eyeL\":%.3f,\"eyeR\":%.3f,"
            + "\"lookX\":%.3f,\"lookY\":%.3f,\"browL\":%.3f,\"browR\":%.3f,\"cheekPuff\":%.3f,"
            + "\"mouthOpen\":%.3f,\"mA\":%.3f,\"mI\":%.3f,\"mU\":%.3f,\"mE\":%.3f,\"mO\":%.3f,"
            + "\"happy\":%.3f,\"sad\":%.3f,\"angry\":%.3f,\"surprised\":%.3f,"
            + "\"faceX\":%.3f,\"faceY\":%.3f,\"mic\":%.4f,\"detected\":%s,\"mp\":%s,\"vmc\":%s,"
            + "\"handL\":%.3f,\"handR\":%.3f,\"handLX\":%.3f,\"handLY\":%.3f,\"handRX\":%.3f,\"handRY\":%.3f,"
            + "\"curlL\":%.3f,\"curlR\":%.3f,\"gestL\":%d,\"gestR\":%d,\"waveOsc\":%.3f,\"gesture\":\"%s\"",
            ts.yaw, ts.pitch, ts.roll, ts.eyeL, ts.eyeR,
            exprFresh ? ts.lookX : 0f, exprFresh ? ts.lookY : 0f,
            exprFresh ? ts.browL : 0f, exprFresh ? ts.browR : 0f, exprFresh ? ts.cheekPuff : 0f,
            ts.mouthOpen, exprFresh ? ts.mouthA : ts.mouthOpen, exprFresh ? ts.mouthI : 0f,
            exprFresh ? ts.mouthU : 0f, exprFresh ? ts.mouthE : 0f, exprFresh ? ts.mouthO : 0f,
            exprFresh ? ts.happy : 0f, exprFresh ? ts.sad : 0f, exprFresh ? ts.angry : 0f, exprFresh ? ts.surprised : 0f,
            ts.faceX, ts.faceY, mic, ts.faceDetected ? "true" : "false",
            mpFresh ? "true" : "false", vmcFresh ? "true" : "false",
            ts.handL, ts.handR, ts.handLX, ts.handLY, ts.handRX, ts.handRY,
            ts.fingerCurlL, ts.fingerCurlR, ts.handGestureL, ts.handGestureR, ts.waveOsc, gesture));

        if (vmcFresh && !ts.vmcBones.isEmpty()) {
            sb.append(",\"bones\":{");
            boolean first = true;
            for (Map.Entry<String, float[]> e : ts.vmcBones.entrySet()) {
                float[] q = e.getValue();
                if (q == null || q.length < 4) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey().replace("\"", "")).append("\":[")
                  .append(String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", q[0], q[1], q[2], q[3])).append(']');
            }
            sb.append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    private FaceTrackerManager() {}
}
