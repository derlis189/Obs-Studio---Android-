// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.os.SystemClock;
import android.util.Log;
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
    private static long sLastSubmitMs = 0L;
    private static long sLastTimestampMs = 0L;
    private static int sFrameCounter = 0;

    static void setHandTrackingWanted(boolean wanted) {
        sHandTrackingWanted = wanted;
    }

    static boolean isReady() {
        return sInitialized && sFaceLandmarker != null;
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
                .setMinHandDetectionConfidence(0.45f)
                .setMinHandPresenceConfidence(0.45f)
                .setMinTrackingConfidence(0.45f)
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

            if (sRgbStaging == null || sRgbStaging.length != DST_W * DST_H) {
                sRgbStaging = new int[DST_W * DST_H];
            }
            ByteBuffer yBuf = planes[0].getBuffer();
            ByteBuffer uBuf = planes[1].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();
            int yRow = planes[0].getRowStride(), yPix = planes[0].getPixelStride(), yCap = yBuf.limit();
            int uRow = planes[1].getRowStride(), uPix = planes[1].getPixelStride(), uCap = uBuf.limit();
            int vRow = planes[2].getRowStride(), vPix = planes[2].getPixelStride(), vCap = vBuf.limit();

            // Fast nearest-neighbor downsample YUV_420_888 -> 256x192 ARGB (mirrored if front camera)
            for (int dy = 0; dy < DST_H; ++dy) {
                int sy = (dy * srcH) / DST_H;
                int cy = sy >> 1;
                int dstOff = dy * DST_W;
                for (int dx = 0; dx < DST_W; ++dx) {
                    int sx = ((front ? (DST_W - 1 - dx) : dx) * srcW) / DST_W;
                    int cx = sx >> 1;
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
                    if (sReusableBitmap == null || sReusableBitmap.isRecycled()) {
                        sReusableBitmap = Bitmap.createBitmap(DST_W, DST_H, Bitmap.Config.ARGB_8888);
                    }
                    sReusableBitmap.setPixels(pixels, 0, DST_W, 0, 0, DST_W, DST_H);
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

        // Smooth ARKit -> high-level tracking channels
        st.eyeL = 1f - clamp01(blinkL * 1.15f);
        st.eyeR = 1f - clamp01(blinkR * 1.15f);
        st.lookX = Math.max(-1f, Math.min(1f, ((lookOutL - lookInL) + (lookInR - lookOutR)) * 0.85f));
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
        Optional<List<float[]>> optMat = res.facialTransformationMatrixes();
        if (optMat.isPresent() && !optMat.get().isEmpty()) {
            float[] m = optMat.get().get(0);
            if (m != null && m.length >= 16) {
                // Rotation submatrix R (column-major: m[col*4 + row])
                double r00 = m[0], r10 = m[1], r20 = m[2];
                double r21 = m[6], r22 = m[10];
                double sy = Math.sqrt(r00 * r00 + r10 * r10);
                double pitchRad, yawRad, rollRad;
                if (sy > 1e-6) {
                    pitchRad = Math.atan2(r21, r22);
                    yawRad = Math.atan2(-r20, sy);
                    rollRad = Math.atan2(r10, r00);
                } else {
                    pitchRad = Math.atan2(-m[9], m[5]);
                    yawRad = Math.atan2(-r20, sy);
                    rollRad = 0.0;
                }
                float rawYaw = Math.max(-42f, Math.min(42f, (float) Math.toDegrees(yawRad)));
                float rawPitch = Math.max(-32f, Math.min(32f, (float) Math.toDegrees(pitchRad)));
                float rawRoll = Math.max(-38f, Math.min(38f, (float) Math.toDegrees(rollRad)));
                float a = 0.42f;
                st.yaw = st.yaw + (rawYaw - st.yaw) * a;
                st.pitch = st.pitch + (rawPitch - st.pitch) * a;
                st.roll = st.roll + (rawRoll - st.roll) * a;
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
                NormalizedLandmark wrist = lm.get(0);
                // Determine left/right (input bitmap is already mirrored for selfie view)
                boolean isLeft = (wrist.x() < 0.5f);
                if (handed != null && i < handed.size() && !handed.get(i).isEmpty()) {
                    String cat = handed.get(i).get(0).categoryName();
                    if ("Left".equalsIgnoreCase(cat)) isLeft = true;
                    else if ("Right".equalsIgnoreCase(cat)) isLeft = false;
                }
                float cIdx = fingerCurl(lm, 5, 8);
                float cMid = fingerCurl(lm, 9, 12);
                float cRing = fingerCurl(lm, 13, 16);
                float cPinky = fingerCurl(lm, 17, 20);
                float avgCurl = (cIdx + cMid + cRing + cPinky) * 0.25f;
                int gesture = classifyHandGesture(cIdx, cMid, cRing, cPinky);
                float raise = clamp01((0.92f - wrist.y()) / 0.65f);

                if (isLeft) {
                    sawL = true;
                    st.handL = st.handL + (Math.max(0.25f, raise) - st.handL) * 0.45f;
                    st.handLX = st.handLX + (wrist.x() - st.handLX) * 0.45f;
                    st.handLY = st.handLY + (wrist.y() - st.handLY) * 0.45f;
                    st.fingerCurlL = avgCurl;
                    st.handGestureL = gesture;
                } else {
                    sawR = true;
                    st.handR = st.handR + (Math.max(0.25f, raise) - st.handR) * 0.45f;
                    st.handRX = st.handRX + (wrist.x() - st.handRX) * 0.45f;
                    st.handRY = st.handRY + (wrist.y() - st.handRY) * 0.45f;
                    st.fingerCurlR = avgCurl;
                    st.handGestureR = gesture;
                }
            }
        }
        if (!sawL) {
            st.handL *= 0.72f;
            if (st.handL < 0.02f) { st.handL = 0f; st.handGestureL = 0; }
        }
        if (!sawR) {
            st.handR *= 0.72f;
            if (st.handR < 0.02f) { st.handR = 0f; st.handGestureR = 0; }
        }
        st.mpHandsActive = sawL || sawR;
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
