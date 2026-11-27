// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On-device MediaPipe Face Landmarker (52 ARKit Blendshapes + 4x4 head pose matrix)
 * and Hand Landmarker (2 hands x 21 landmarks -> wrist position + finger curl + gestures).
 * Runs asynchronously on a single low-priority worker thread so Camera2 is never stalled.
 */
final class MediaPipeVtuberTracker {
    private static final String TAG = "ObsMpVtuber";
    private static final String FACE_MODEL = "models/face_landmarker.task";
    private static final String HAND_MODEL = "models/hand_landmarker.task";
    private static final int DST_W = 256;
    private static final int DST_H = 192;

    private static final AtomicBoolean sBusy = new AtomicBoolean(false);
    private static final AtomicBoolean sInitAttempted = new AtomicBoolean(false);
    private static volatile boolean sHandTrackingWanted = true;
    private static volatile boolean sInitialized = false;
    private static ExecutorService sExecutor;
    private static FaceLandmarker sFaceLandmarker;
    private static HandLandmarker sHandLandmarker;
    private static int[] sRgbStaging;
    private static Bitmap sReusableBitmap;
    private static int sReusableW = 0, sReusableH = 0;
    private static volatile int sFrontSensorOrient = -1;
    private static volatile int sBackSensorOrient = -1;
    private static long sLastSubmitMs = 0L;
    private static long sLastTimestampMs = 0L;
    private static int sFrameCounter = 0;
    private static int sHandStreakL = 0;
    private static int sHandStreakR = 0;
    private static float sPrevWristX = 0.5f;
    private static float sPitchBaseline = 0f;
    private static boolean sHasPitchBaseline = false;

    private static int getSensorOrientation(Context ctx, boolean front) {
        int cached = front ? sFrontSensorOrient : sBackSensorOrient;
        if (cached >= 0) return cached;
        int fallback = front ? 270 : 90;
        try {
            CameraManager mgr = ctx.getSystemService(CameraManager.class);
            if (mgr != null) {
                int want = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
                for (String id : mgr.getCameraIdList()) {
                    CameraCharacteristics cc = mgr.getCameraCharacteristics(id);
                    Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == want) {
                        Integer ori = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        if (ori != null) {
                            if (front) sFrontSensorOrient = ori;
                            else sBackSensorOrient = ori;
                            return ori;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static int getDisplayRotationDegrees(Context ctx) {
        try {
            int rot = Surface.ROTATION_0;
            QtObsActivity act = QtObsActivity.getActiveInstance();
            if (act != null && Build.VERSION.SDK_INT >= 30 && act.getDisplay() != null) {
                rot = act.getDisplay().getRotation();
            } else {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    @SuppressWarnings("deprecation")
                    Display d = wm.getDefaultDisplay();
                    if (d != null) rot = d.getRotation();
                }
            }
            switch (rot) {
                case Surface.ROTATION_90: return 90;
                case Surface.ROTATION_180: return 180;
                case Surface.ROTATION_270: return 270;
                default: return 0;
            }
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static void setHandTrackingWanted(boolean wanted) {
        sHandTrackingWanted = wanted;
        if (!wanted) {
            FaceTrackerManager.TrackingState st = FaceTrackerManager.getState();
            st.handL = 0f;
            st.handR = 0f;
            st.waveOsc = 0f;
            st.handGestureL = 0;
            st.handGestureR = 0;
            st.mpHandsActive = false;
            sHandStreakL = 0;
            sHandStreakR = 0;
        }
    }

    static boolean isHandTrackingWanted() {
        return sHandTrackingWanted;
    }

    static boolean isReady() {
        return sInitialized && sFaceLandmarker != null;
    }

    static boolean isHandTrackerReady() {
        return sInitialized && sHandLandmarker != null;
    }

    private static synchronized void ensureInitialized(Context ctx) {
        if (sInitAttempted.get() || ctx == null) return;
        sInitAttempted.set(true);
        final Context app = ctx.getApplicationContext();
        sExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OBS-MpVtuber");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        sExecutor.execute(() -> {
            try {
                sFaceLandmarker = createFaceLandmarker(app, true);
                if (sFaceLandmarker == null) sFaceLandmarker = createFaceLandmarker(app, false);
            } catch (Throwable t) {
                Log.w(TAG, "FaceLandmarker unavailable: " + t.getMessage());
            }
            try {
                sHandLandmarker = createHandLandmarker(app, true);
                if (sHandLandmarker == null) sHandLandmarker = createHandLandmarker(app, false);
            } catch (Throwable t) {
                Log.w(TAG, "HandLandmarker unavailable: " + t.getMessage());
            }
            sInitialized = (sFaceLandmarker != null);
            Log.i(TAG, "MediaPipe VTuber initialized: face=" + (sFaceLandmarker != null)
                + " hands=" + (sHandLandmarker != null));
        });
    }

    private static FaceLandmarker createFaceLandmarker(Context app, boolean useGpu) {
        try {
            BaseOptions base = BaseOptions.builder()
                .setModelAssetPath(FACE_MODEL)
                .setDelegate(useGpu ? Delegate.GPU : Delegate.CPU)
                .build();
            FaceLandmarker.FaceLandmarkerOptions opts = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.45f)
                .setMinFacePresenceConfidence(0.45f)
                .setMinTrackingConfidence(0.45f)
                .setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(true)
                .build();
            FaceLandmarker fl = FaceLandmarker.createFromOptions(app, opts);
            Log.i(TAG, "FaceLandmarker created with " + (useGpu ? "GPU" : "CPU") + " delegate");
            return fl;
        } catch (Throwable t) {
            if (useGpu) Log.w(TAG, "FaceLandmarker GPU failed, retrying CPU: " + t.getMessage());
            return null;
        }
    }

    private static HandLandmarker createHandLandmarker(Context app, boolean useGpu) {
        try {
            BaseOptions base = BaseOptions.builder()
                .setModelAssetPath(HAND_MODEL)
                .setDelegate(useGpu ? Delegate.GPU : Delegate.CPU)
                .build();
            HandLandmarker.HandLandmarkerOptions opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.68f)
                .setMinHandPresenceConfidence(0.68f)
                .setMinTrackingConfidence(0.65f)
                .build();
            HandLandmarker hl = HandLandmarker.createFromOptions(app, opts);
            Log.i(TAG, "HandLandmarker created with " + (useGpu ? "GPU" : "CPU") + " delegate");
            return hl;
        } catch (Throwable t) {
            if (useGpu) Log.w(TAG, "HandLandmarker GPU failed, retrying CPU: " + t.getMessage());
            return null;
        }
    }

    static boolean submitYuvFrame(Image img, boolean front, FaceTrackerManager.TrackingState state) {
        if (img == null || state == null) return false;
        Context ctx = QtObsActivity.getAppContext();
        if (ctx == null) return false;
        if (!sInitAttempted.get()) ensureInitialized(ctx);
        if (!sInitialized || sFaceLandmarker == null || sExecutor == null) return false;

        long now = SystemClock.uptimeMillis();
        // ~20 Hz cap balances crisp ARKit expressions with low thermal footprint
        if (now - sLastSubmitMs < 48L) return true;
        if (!sBusy.compareAndSet(false, true)) return true;
        sLastSubmitMs = now;

        try {
            Image.Plane[] planes = img.getPlanes();
            if (planes == null || planes.length < 3) { sBusy.set(false); return false; }
            int srcW = img.getWidth();
            int srcH = img.getHeight();
            if (srcW < 32 || srcH < 24) { sBusy.set(false); return false; }

            // Compute exact upright clockwise rotation so MediaPipe always sees an upright face
            int sensorOri = getSensorOrientation(ctx, front);
            int dispDeg = getDisplayRotationDegrees(ctx);
            int cwDeg = front ? ((sensorOri + dispDeg) % 360) : ((sensorOri - dispDeg + 360) % 360);
            final int outW = (cwDeg == 90 || cwDeg == 270) ? DST_H : DST_W;
            final int outH = (cwDeg == 90 || cwDeg == 270) ? DST_W : DST_H;

            if (sRgbStaging == null || sRgbStaging.length != outW * outH) {
                sRgbStaging = new int[outW * outH];
            }
            ByteBuffer yBuf = planes[0].getBuffer();
            ByteBuffer uBuf = planes[1].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();
            int yRow = planes[0].getRowStride(), yPix = planes[0].getPixelStride(), yCap = yBuf.limit();
            int uRow = planes[1].getRowStride(), uPix = planes[1].getPixelStride(), uCap = uBuf.limit();
            int vRow = planes[2].getRowStride(), vPix = planes[2].getPixelStride(), vCap = vBuf.limit();

            // Fast nearest-neighbor downsample + upright rotation + front-camera mirror
            for (int dy = 0; dy < outH; ++dy) {
                int dstOff = dy * outW;
                for (int dx = 0; dx < outW; ++dx) {
                    int ux = front ? (outW - 1 - dx) : dx;
                    int uy = dy;
                    int sx, sy;
                    if (cwDeg == 90) {
                        sx = (uy * srcW) / outH;
                        sy = ((outW - 1 - ux) * srcH) / outW;
                    } else if (cwDeg == 180) {
                        sx = ((outW - 1 - ux) * srcW) / outW;
                        sy = ((outH - 1 - uy) * srcH) / outH;
                    } else if (cwDeg == 270) {
                        sx = ((outH - 1 - uy) * srcW) / outH;
                        sy = (ux * srcH) / outW;
                    } else {
                        sx = (ux * srcW) / outW;
                        sy = (uy * srcH) / outH;
                    }
                    if (sx < 0) sx = 0; else if (sx >= srcW) sx = srcW - 1;
                    if (sy < 0) sy = 0; else if (sy >= srcH) sy = srcH - 1;
                    int cx = sx >> 1;
                    int cy = sy >> 1;
                    int yi = sy * yRow + sx * yPix;
                    int ui = cy * uRow + cx * uPix;
                    int vi = cy * vRow + cx * vPix;
                    if (yi >= yCap || ui >= uCap || vi >= vCap) {
                        sRgbStaging[dstOff + dx] = 0xFF000000;
                        continue;
                    }
                    int y = (yBuf.get(yi) & 0xFF) - 16;
                    int u = (uBuf.get(ui) & 0xFF) - 128;
                    int v = (vBuf.get(vi) & 0xFF) - 128;
                    if (y < 0) y = 0;
                    int y1192 = 1192 * y;
                    int r = (y1192 + 1634 * v) >> 10;
                    int g = (y1192 - 833 * v - 400 * u) >> 10;
                    int b = (y1192 + 2066 * u) >> 10;
                    if (r < 0) r = 0; else if (r > 255) r = 255;
                    if (g < 0) g = 0; else if (g > 255) g = 255;
                    if (b < 0) b = 0; else if (b > 255) b = 255;
                    sRgbStaging[dstOff + dx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }

            final int[] pixels = sRgbStaging;
            final boolean runHands = sHandTrackingWanted && sHandLandmarker != null && ((++sFrameCounter & 1) == 0);
            sExecutor.execute(() -> {
                try {
                    if (sReusableBitmap == null || sReusableBitmap.isRecycled() || sReusableW != outW || sReusableH != outH) {
                        if (sReusableBitmap != null && !sReusableBitmap.isRecycled()) sReusableBitmap.recycle();
                        sReusableBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                        sReusableW = outW;
                        sReusableH = outH;
                    }
                    sReusableBitmap.setPixels(pixels, 0, outW, 0, 0, outW, outH);
                    MPImage mpImg = new BitmapImageBuilder(sReusableBitmap).build();
                    long ts = Math.max(SystemClock.uptimeMillis(), sLastTimestampMs + 1L);
                    sLastTimestampMs = ts;

                    long wallNow = System.currentTimeMillis();
                    boolean vmcFresh = state.vmcActive && (wallNow - state.lastVmcMs) < 1500L;

                    FaceLandmarker fl = sFaceLandmarker;
                    if (fl != null && !vmcFresh) {
                        FaceLandmarkerResult fRes = fl.detectForVideo(mpImg, ts);
                        if (fRes != null) applyFaceResult(fRes, state, wallNow);
                    }

                    HandLandmarker hl = sHandLandmarker;
                    if (runHands && hl != null) {
                        HandLandmarkerResult hRes = hl.detectForVideo(mpImg, ts);
                        if (hRes != null) applyHandResult(hRes, state, wallNow);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "MediaPipe inference error: " + t.getMessage());
                } finally {
                    sBusy.set(false);
                }
            });
            return true;
        } catch (Throwable t) {
            sBusy.set(false);
            return false;
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static void applyFaceResult(FaceLandmarkerResult res, FaceTrackerManager.TrackingState st, long now) {
        Optional<List<List<Category>>> optBs = res.faceBlendshapes();
        if (!optBs.isPresent() || optBs.get().isEmpty()) return;
        List<Category> cats = optBs.get().get(0);
        if (cats == null || cats.isEmpty()) return;

        float blinkL = 0f, blinkR = 0f;
        float lookInL = 0f, lookOutL = 0f, lookUpL = 0f, lookDownL = 0f;
        float lookInR = 0f, lookOutR = 0f, lookUpR = 0f, lookDownR = 0f;
        float jawOpen = 0f, funnel = 0f, pucker = 0f, smileL = 0f, smileR = 0f;
        float frownL = 0f, frownR = 0f, stretchL = 0f, stretchR = 0f;
        float browInnerUp = 0f, browOuterUpL = 0f, browOuterUpR = 0f, browDownL = 0f, browDownR = 0f;
        float eyeWideL = 0f, eyeWideR = 0f, cheekPuff = 0f;

        for (Category c : cats) {
            String name = c.categoryName();
            float score = clamp01(c.score());
            st.blendshapes.put(name, score);
            switch (name) {
                case "eyeBlinkLeft": blinkL = score; break;
                case "eyeBlinkRight": blinkR = score; break;
                case "eyeLookInLeft": lookInL = score; break;
                case "eyeLookOutLeft": lookOutL = score; break;
                case "eyeLookUpLeft": lookUpL = score; break;
                case "eyeLookDownLeft": lookDownL = score; break;
                case "eyeLookInRight": lookInR = score; break;
                case "eyeLookOutRight": lookOutR = score; break;
                case "eyeLookUpRight": lookUpR = score; break;
                case "eyeLookDownRight": lookDownR = score; break;
                case "jawOpen": jawOpen = score; break;
                case "mouthFunnel": funnel = score; break;
                case "mouthPucker": pucker = score; break;
                case "mouthSmileLeft": smileL = score; break;
                case "mouthSmileRight": smileR = score; break;
                case "mouthFrownLeft": frownL = score; break;
                case "mouthFrownRight": frownR = score; break;
                case "mouthStretchLeft": stretchL = score; break;
                case "mouthStretchRight": stretchR = score; break;
                case "browInnerUp": browInnerUp = score; break;
                case "browOuterUpLeft": browOuterUpL = score; break;
                case "browOuterUpRight": browOuterUpR = score; break;
                case "browDownLeft": browDownL = score; break;
                case "browDownRight": browDownR = score; break;
                case "eyeWideLeft": eyeWideL = score; break;
                case "eyeWideRight": eyeWideR = score; break;
                case "cheekPuff": cheekPuff = score; break;
                default: break;
            }
        }

        // Smooth ARKit -> high-level tracking channels (mirrored selfie orientation)
        st.eyeL = 1f - clamp01(blinkL * 1.15f);
        st.eyeR = 1f - clamp01(blinkR * 1.15f);
        st.lookX = Math.max(-1f, Math.min(1f, ((lookInL - lookOutL) + (lookOutR - lookInR)) * 0.85f));
        st.lookY = Math.max(-1f, Math.min(1f, ((lookUpL + lookUpR) - (lookDownL + lookDownR)) * 0.75f));

        // 5-vowel viseme decomposition from ARKit mouth blendshapes (Kalidokit-inspired)
        float smile = (smileL + smileR) * 0.5f;
        float frown = (frownL + frownR) * 0.5f;
        float stretch = (stretchL + stretchR) * 0.5f;
        st.mouthOpen = clamp01(jawOpen * 1.25f);
        st.mouthA = clamp01(jawOpen * 1.2f - funnel * 0.35f - pucker * 0.3f);
        st.mouthO = clamp01(funnel * 0.85f + jawOpen * 0.45f * (1f - stretch));
        st.mouthU = clamp01(pucker * 1.15f);
        st.mouthI = clamp01(smile * 0.55f + stretch * 0.65f - jawOpen * 0.25f);
        st.mouthE = clamp01(stretch * 0.55f + jawOpen * 0.45f * (1f - pucker));

        st.browL = Math.max(-1f, Math.min(1f, (browInnerUp * 0.6f + browOuterUpL * 0.7f) - browDownL * 1.1f));
        st.browR = Math.max(-1f, Math.min(1f, (browInnerUp * 0.6f + browOuterUpR * 0.7f) - browDownR * 1.1f));
        st.cheekPuff = cheekPuff;

        // High-level emotions derived from ARKit blendshapes
        st.happy = clamp01(smile * 1.15f);
        st.sad = clamp01(frown * 0.85f + browInnerUp * 0.45f);
        st.angry = clamp01(((browDownL + browDownR) * 0.5f) * 1.1f + frown * 0.3f);
        st.surprised = clamp01(((eyeWideL + eyeWideR) * 0.5f) * 0.9f + browInnerUp * 0.45f + jawOpen * 0.35f);

        // Extract exact 3D head orientation from 4x4 facial transformation matrix (column-major)
        // Only accept valid front-facing rotations (r22 > 0.25) to prevent 180-deg gimbal flips.
        Optional<List<float[]>> optMat = res.facialTransformationMatrixes();
        if (optMat.isPresent() && !optMat.get().isEmpty()) {
            float[] m = optMat.get().get(0);
            if (m != null && m.length >= 16) {
                double r00 = m[0], r10 = m[1], r20 = m[2];
                double r21 = m[6], r22 = m[10];
                if (r22 > 0.25) {
                    double sy = Math.sqrt(r00 * r00 + r10 * r10);
                    // Positive pitch = looking UP, positive yaw = viewer's RIGHT, positive roll = clockwise
                    double pitchDeg = Math.toDegrees(Math.atan2(-r21, r22));
                    double yawDeg = Math.toDegrees(Math.atan2(r20, Math.max(1e-4, sy)));
                    double rollDeg = Math.toDegrees(Math.atan2(r10, r00));
                    if (Math.abs(yawDeg) < 60.0 && Math.abs(pitchDeg) < 55.0 && Math.abs(rollDeg) < 55.0) {
                        // Gently adapt vertical baseline for natural phone-holding angle (clamped +-14 deg)
                        float clampedBase = Math.max(-14f, Math.min(14f, (float) pitchDeg));
                        if (!sHasPitchBaseline) {
                            sPitchBaseline = clampedBase;
                            sHasPitchBaseline = true;
                        } else if (Math.abs(pitchDeg - sPitchBaseline) < 16.0) {
                            sPitchBaseline += (clampedBase - sPitchBaseline) * 0.012f;
                        }
                        float centeredPitch = (float) (pitchDeg - sPitchBaseline) * 1.35f;
                        float rawYaw = Math.max(-35f, Math.min(35f, (float) yawDeg));
                        float rawPitch = Math.max(-30f, Math.min(30f, centeredPitch));
                        float rawRoll = Math.max(-30f, Math.min(30f, (float) rollDeg));
                        // Micro-deadzone + responsive EMA; per-frame 60 FPS damping happens in WebGL
                        float a = 0.36f;
                        if (Math.abs(rawYaw - st.yaw) > 0.35f) st.yaw = st.yaw + (rawYaw - st.yaw) * a;
                        if (Math.abs(rawPitch - st.pitch) > 0.35f) st.pitch = st.pitch + (rawPitch - st.pitch) * a;
                        if (Math.abs(rawRoll - st.roll) > 0.35f) st.roll = st.roll + (rawRoll - st.roll) * a;
                    }
                }
            }
        }

        List<List<NormalizedLandmark>> lms = res.faceLandmarks();
        if (lms != null && !lms.isEmpty() && lms.get(0).size() > 1) {
            NormalizedLandmark nose = lms.get(0).get(1);
            st.faceX = (nose.x() - 0.5f) * 2f;
            st.faceY = (nose.y() - 0.5f) * 2f;
        }

        st.faceDetected = true;
        st.mpFaceActive = true;
        st.lastMpFaceMs = now;
        st.lastUpdateMs = now;
    }

    private static float fingerCurl(List<NormalizedLandmark> hand, int mcp, int tip) {
        NormalizedLandmark w = hand.get(0);
        NormalizedLandmark m = hand.get(mcp);
        NormalizedLandmark t = hand.get(tip);
        double dMcp = Math.hypot(m.x() - w.x(), m.y() - w.y());
        double dTip = Math.hypot(t.x() - w.x(), t.y() - w.y());
        if (dMcp < 1e-4) return 0f;
        double ratio = dTip / dMcp;
        // Extended finger ~1.8..2.2, curled fist ~0.8..1.1
        return clamp01((float) ((1.85 - ratio) / 0.85));
    }

    private static int classifyHandGesture(float indexCurl, float midCurl, float ringCurl, float pinkyCurl) {
        boolean idxOpen = indexCurl < 0.35f;
        boolean midOpen = midCurl < 0.35f;
        boolean ringOpen = ringCurl < 0.35f;
        boolean pinkyOpen = pinkyCurl < 0.35f;
        if (idxOpen && midOpen && ringOpen && pinkyOpen) return 1; // Open / Wave
        if (!idxOpen && !midOpen && !ringOpen && !pinkyOpen) return 2; // Fist
        if (idxOpen && midOpen && !ringOpen && !pinkyOpen) return 3; // Peace / Victory
        if (idxOpen && !midOpen && !ringOpen && !pinkyOpen) return 4; // Point
        return 1;
    }

    private static void applyHandResult(HandLandmarkerResult res, FaceTrackerManager.TrackingState st, long now) {
        List<List<NormalizedLandmark>> hands = res.landmarks();
        List<List<Category>> handed = res.handedness();
        boolean sawL = false, sawR = false;
        if (hands != null) {
            for (int i = 0; i < hands.size(); ++i) {
                List<NormalizedLandmark> lm = hands.get(i);
                if (lm == null || lm.size() < 21) continue;
                float score = 1f;
                boolean isLeft = (lm.get(0).x() < 0.5f);
                if (handed != null && i < handed.size() && !handed.get(i).isEmpty()) {
                    Category c = handed.get(i).get(0);
                    score = c.score();
                    String cat = c.categoryName();
                    if ("Left".equalsIgnoreCase(cat)) isLeft = true;
                    else if ("Right".equalsIgnoreCase(cat)) isLeft = false;
                }
                // Reject low-confidence detections (e.g., face/chin/clothing mistaken for a hand)
                if (score < 0.65f) continue;

                NormalizedLandmark wrist = lm.get(0);
                NormalizedLandmark midMcp = lm.get(9);
                // Reject tiny bogus detections or hands resting low at the bottom edge
                double handSpan = Math.hypot(midMcp.x() - wrist.x(), midMcp.y() - wrist.y());
                if (handSpan < 0.055 || wrist.y() > 0.76f) continue;

                float cIdx = fingerCurl(lm, 5, 8);
                float cMid = fingerCurl(lm, 9, 12);
                float cRing = fingerCurl(lm, 13, 16);
                float cPinky = fingerCurl(lm, 17, 20);
                float avgCurl = (cIdx + cMid + cRing + cPinky) * 0.25f;
                int gesture = classifyHandGesture(cIdx, cMid, cRing, cPinky);
                float raise = clamp01((0.76f - wrist.y()) / 0.44f);
                if (raise < 0.12f) continue;

                float dx = (wrist.x() - sPrevWristX) * 14f;
                sPrevWristX = wrist.x();
                st.waveOsc = st.waveOsc * 0.65f + Math.max(-1f, Math.min(1f, dx)) * 0.35f;

                if (isLeft) {
                    sawL = true;
                    sHandStreakL = Math.min(6, sHandStreakL + 1);
                    if (sHandStreakL >= 3) {
                        st.handL = st.handL + (raise - st.handL) * 0.32f;
                        st.handLX = st.handLX + (wrist.x() - st.handLX) * 0.32f;
                        st.handLY = st.handLY + (wrist.y() - st.handLY) * 0.32f;
                        st.fingerCurlL = avgCurl;
                        st.handGestureL = gesture;
                    }
                } else {
                    sawR = true;
                    sHandStreakR = Math.min(6, sHandStreakR + 1);
                    if (sHandStreakR >= 3) {
                        st.handR = st.handR + (raise - st.handR) * 0.32f;
                        st.handRX = st.handRX + (wrist.x() - st.handRX) * 0.32f;
                        st.handRY = st.handRY + (wrist.y() - st.handRY) * 0.32f;
                        st.fingerCurlR = avgCurl;
                        st.handGestureR = gesture;
                    }
                }
            }
        }
        if (!sawL) {
            sHandStreakL = 0;
            st.handL *= 0.60f;
            if (st.handL < 0.03f) { st.handL = 0f; st.handGestureL = 0; }
        }
        if (!sawR) {
            sHandStreakR = 0;
            st.handR *= 0.60f;
            if (st.handR < 0.03f) { st.handR = 0f; st.handGestureR = 0; }
        }
        if (!sawL && !sawR) {
            st.waveOsc *= 0.55f;
            if (Math.abs(st.waveOsc) < 0.02f) st.waveOsc = 0f;
        }
        st.mpHandsActive = (sHandStreakL >= 3) || (sHandStreakR >= 3);
        st.lastMpHandsMs = now;
    }

    static synchronized void shutdown() {
        sInitialized = false;
        sInitAttempted.set(false);
        ExecutorService exec = sExecutor;
        sExecutor = null;
        if (exec != null) {
            exec.execute(() -> {
                try { if (sFaceLandmarker != null) sFaceLandmarker.close(); } catch (Throwable ignored) {}
                try { if (sHandLandmarker != null) sHandLandmarker.close(); } catch (Throwable ignored) {}
                sFaceLandmarker = null;
                sHandLandmarker = null;
                if (sReusableBitmap != null && !sReusableBitmap.isRecycled()) {
                    sReusableBitmap.recycle();
                    sReusableBitmap = null;
                }
            });
            exec.shutdown();
        }
    }

    private MediaPipeVtuberTracker() {}
}
