// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

public final class VtuberSourceManager {
    private static final String TAG = "ObsVtuber";
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService sPrepExec =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OBS-VtuberPrep");
            t.setDaemon(true);
            return t;
        });
    private static final Map<Long, VtuberInstance> sInstances = new ConcurrentHashMap<>();
    private static FrameLayout sHost;
    private static volatile int thermalFpsCap = 60;
    static void setThermalFpsCap(int fps) { thermalFpsCap = Math.max(1, Math.min(60, fps)); }

    private static final class VtuberInstance {
        final long id;
        String mode = "png";
        int width = 512;
        int height = 512;
        int fps = 30;
        boolean bounce = true;
        boolean lipSync = true;
        boolean faceTracking = true;
        float voiceThreshold = 0.04f;
        int veadoStateIndex = 0;

        // Native PNGTuber / .veadomini state
        String veadominiPath = "";
        VeadominiParser.Avatar veadoAvatar;
        String pngIdlePath = "", pngTalkPath = "", pngBlinkPath = "", pngBlinkTalkPath = "";
        Bitmap bmpIdle, bmpTalk, bmpBlink, bmpBlinkTalk;
        Bitmap outBitmap;
        Canvas outCanvas;
        ByteBuffer outRgba;
        final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float bounceY = 0f, bounceVel = 0f;
        boolean wasTalking = false;
        long lastTickMs = 0;
        float blinkTimer = 2.5f, blinkDur = 0f;

        // WebView WebGL2 state (for VRM 3D & Live2D)
        WebView webView;
        boolean pageReady = false;
        boolean visible = true;
        String pendingConfigJson = "{}";
        boolean veadoAutoEmotion = false;
        int veadoHappyState = 1, veadoSadState = 2, veadoAngryState = 3, veadoSurprisedState = 4;
        int veadoShownState = -1, veadoCandidateState = -1;
        long veadoCandidateSinceMs = 0;
        final Object webGlBufLock = new Object();
        ByteBuffer webGlRgba;
        int webGlW = 0, webGlH = 0;

        volatile boolean destroyed = false;

        final Runnable nativeTick = new Runnable() {
            @Override
            public void run() {
                if (destroyed || !visible || !"png".equals(mode)) return;
                renderNativePngFrame();
                long delay = Math.max(16L, 1000L / Math.max(1, Math.min(thermalFpsCap, fps)));
                sMain.postDelayed(this, delay);
            }
        };

        final Runnable webTrackTick = new Runnable() {
            @Override
            public void run() {
                if (destroyed || !visible || "png".equals(mode) || webView == null) return;
                if (pageReady) {
                    float mic = NativeBridge.getMicLevel();
                    String payload = FaceTrackerManager.buildWebTrackingPayloadJson(mic);
                    webView.evaluateJavascript(
                        "if(window.OBS_VTUBER){window.OBS_VTUBER.updateTrackingV2(" + payload
                            + ");window.OBS_VTUBER.setRenderBudget(" + Math.min(fps, thermalFpsCap) + ");}",
                        null);
                }
                long delay = Math.max(16L, 1000L / Math.max(1, Math.min(thermalFpsCap, fps)));
                sMain.postDelayed(this, delay);
            }
        };

        VtuberInstance(long id) { this.id = id; }

        void ensureNativeBuffers(int w, int h) {
            int cw = Math.max(128, Math.min(1920, w));
            int ch = Math.max(128, Math.min(1920, h));
            if (outBitmap != null && outBitmap.getWidth() == cw && outBitmap.getHeight() == ch) return;
            if (outBitmap != null) outBitmap.recycle();
            outBitmap = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            outCanvas = new Canvas(outBitmap);

            width = cw;
            height = ch;
        }

        void renderNativePngFrame() {
            try {
                ensureNativeBuffers(width, height);
                long nowMs = System.currentTimeMillis();
                float dt = lastTickMs > 0 ? Math.min(0.05f, (nowMs - lastTickMs) / 1000f) : 0.033f;
                lastTickMs = nowMs;

                float mic = lipSync ? NativeBridge.getMicLevel() : 0f;
                FaceTrackerManager.TrackingState ts = FaceTrackerManager.getState();
                boolean talking = (mic > voiceThreshold) || (ts.mouthOpen > 0.25f);

                blinkTimer -= dt;
                if (blinkTimer <= 0f) {
                    blinkTimer = 2.4f + (float) (Math.random() * 3.0);
                    blinkDur = 0.13f;
                }
                if (blinkDur > 0f) blinkDur -= dt;
                boolean blink = (blinkDur > 0f) || (ts.faceDetected && ts.eyeL < 0.35f && ts.eyeR < 0.35f);

                if (bounce) {
                    if (talking && !wasTalking) bounceVel = height * 0.42f;
                    bounceVel -= height * 2.6f * dt;
                    bounceY += bounceVel * dt;
                    if (bounceY < 0f) { bounceY = 0f; bounceVel = 0f; }
                } else {
                    bounceY = 0f;
                }
                wasTalking = talking;

                // Pick active Bitmap (.veadomini state first, then individual PNGs)
                Bitmap idleB = bmpIdle, talkB = bmpTalk, blinkB = bmpBlink, blinkTalkB = bmpBlinkTalk;
                if (veadoAvatar != null && !veadoAvatar.states.isEmpty()) {
                    int selectedState = Math.max(0, Math.min(veadoStateIndex, veadoAvatar.states.size() - 1));
                    if (veadoAutoEmotion) {
                        int targetState = veadoStateIndex;
                        float targetScore = 0.56f;
                        boolean emotionFresh = (ts.vmcActive && nowMs - ts.lastVmcMs < 1500)
                            || (ts.mpFaceActive && nowMs - ts.lastMpFaceMs < 1200);
                        float happy = emotionFresh ? ts.happy : 0f;
                        if (happy > targetScore) { targetScore = happy; targetState = veadoHappyState; }
                        if (emotionFresh && ts.sad > targetScore) { targetScore = ts.sad; targetState = veadoSadState; }
                        if (emotionFresh && ts.angry > targetScore) { targetScore = ts.angry; targetState = veadoAngryState; }
                        if (emotionFresh && ts.surprised > targetScore) { targetScore = ts.surprised; targetState = veadoSurprisedState; }
                        if (targetState < 0 || targetState >= veadoAvatar.states.size()) targetState = veadoStateIndex;
                        long now = System.currentTimeMillis();
                        if (targetState != veadoShownState) {
                            if (targetState != veadoCandidateState) {
                                veadoCandidateState = targetState;
                                veadoCandidateSinceMs = now;
                            } else if (now - veadoCandidateSinceMs >= 450) {
                                veadoShownState = targetState;
                            }
                        } else {
                            veadoCandidateState = targetState;
                            veadoCandidateSinceMs = now;
                        }
                        if (veadoShownState >= 0) selectedState = veadoShownState;
                    } else {
                        veadoShownState = -1;
                        veadoCandidateState = -1;
                        veadoCandidateSinceMs = 0;
                    }
                    VeadominiParser.AvatarState st = veadoAvatar.getState(selectedState);
                    if (st != null) {
                        idleB = st.closed;
                        talkB = st.open != null ? st.open : st.closed;
                        blinkB = st.blinkClosed;
                        blinkTalkB = st.blinkOpen;
                    }
                }

                Bitmap chosen = null;
                if (talking && blink && blinkTalkB != null) chosen = blinkTalkB;
                else if (talking && talkB != null) chosen = talkB;
                else if (blink && blinkB != null) chosen = blinkB;
                else if (idleB != null) chosen = idleB;

                outCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                if (chosen != null && !chosen.isRecycled()) {
                    float scale = Math.min((width * 0.92f) / chosen.getWidth(), (height * 0.88f) / chosen.getHeight());
                    float dw = chosen.getWidth() * scale;
                    float dh = chosen.getHeight() * scale;
                    float dx = (width - dw) * 0.5f;
                    float dy = (height - dh) * 0.96f - bounceY;
                    Rect src = new Rect(0, 0, chosen.getWidth(), chosen.getHeight());
                    RectF dst = new RectF(dx, dy, dx + dw, dy + dh);
                    outCanvas.drawBitmap(chosen, src, dst, drawPaint);
                } else {
                    drawNativeFallbackAvatar(outCanvas, width, height, talking, blink, ts);
                }

                NativeBridge.pushVtuberBitmap(id, outBitmap);
            } catch (Throwable t) {
                Log.w(TAG, "renderNativePngFrame error: " + t.getMessage());
            }
        }

        private void drawNativeFallbackAvatar(Canvas c, int w, int h, boolean talking, boolean blink,
                                              FaceTrackerManager.TrackingState ts) {
            c.save();
            float cx = w * 0.5f + ts.faceX * w * 0.04f;
            float cy = h * 0.54f + ts.faceY * h * 0.03f - bounceY;
            float s = Math.min(w, h) * 0.34f;
            c.translate(cx, cy);
            c.rotate(ts.roll * 0.6f);

            // Shoulders
            shapePaint.setColor(Color.parseColor("#1E293B"));
            c.drawOval(new RectF(-s * 0.85f, s * 0.50f, s * 0.85f, s * 1.55f), shapePaint);

            // Back hair
            shapePaint.setColor(Color.parseColor("#2563EB"));
            c.drawOval(new RectF(-s * 0.86f, -s * 0.98f, s * 0.86f, s * 0.88f), shapePaint);

            // Face
            float fx = ts.yaw * s * 0.005f;
            float fy = -ts.pitch * s * 0.004f;
            shapePaint.setColor(Color.parseColor("#FFE4D0"));
            c.drawOval(new RectF(fx - s * 0.68f, fy - s * 0.74f, fx + s * 0.68f, fy + s * 0.74f), shapePaint);

            // Eyes
            float eyeH = blink ? s * 0.02f : s * 0.15f;
            shapePaint.setColor(Color.parseColor("#0F172A"));
            c.drawOval(new RectF(fx - s * 0.37f, fy - s * 0.04f - eyeH, fx - s * 0.13f, fy - s * 0.04f + eyeH), shapePaint);
            c.drawOval(new RectF(fx + s * 0.13f, fy - s * 0.04f - eyeH, fx + s * 0.37f, fy - s * 0.04f + eyeH), shapePaint);
            if (!blink) {
                shapePaint.setColor(Color.WHITE);
                c.drawCircle(fx - s * 0.21f, fy - s * 0.09f, s * 0.042f, shapePaint);
                c.drawCircle(fx + s * 0.29f, fy - s * 0.09f, s * 0.042f, shapePaint);
            }

            // Mouth
            float mH = talking ? s * 0.16f : s * 0.035f;
            shapePaint.setColor(Color.parseColor("#E11D48"));
            c.drawOval(new RectF(fx - s * 0.16f, fy + s * 0.28f - mH, fx + s * 0.16f, fy + s * 0.28f + mH), shapePaint);

            // Bangs
            shapePaint.setColor(Color.parseColor("#3B82F6"));
            c.drawArc(new RectF(fx * 0.5f - s * 0.72f, fy - s * 0.90f, fx * 0.5f + s * 0.72f, fy + s * 0.54f),
                190f, 160f, true, shapePaint);
            c.restore();
        }

        void releaseNativeImages() {
            if (veadoAvatar != null) { veadoAvatar.recycle(); veadoAvatar = null; }
            if (bmpIdle != null) { bmpIdle.recycle(); bmpIdle = null; }
            if (bmpTalk != null) { bmpTalk.recycle(); bmpTalk = null; }
            if (bmpBlink != null) { bmpBlink.recycle(); bmpBlink = null; }
            if (bmpBlinkTalk != null) { bmpBlinkTalk.recycle(); bmpBlinkTalk = null; }
        }
    }

    public static void onWebGlRgbaFrame(long id, byte[] raw, int w, int h, boolean flipVertical) {
        VtuberInstance inst = sInstances.get(id);
        if (inst == null || inst.destroyed || "png".equals(inst.mode)) return;
        if (!inst.visible || raw == null || w < 1 || h < 1 || w > 1280 || h > 1280 || raw.length != w * h * 4) return;
        NativeBridge.pushVtuberArray(id, raw, 0, w, h, flipVertical);
    }

    private static FrameLayout ensureHost(Activity activity) {
        if (sHost != null && sHost.isAttachedToWindow()) return sHost;
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (!(decor instanceof ViewGroup)) return null;
        FrameLayout f = new FrameLayout(activity) {
            @Override public boolean dispatchTouchEvent(MotionEvent ev) { return false; }
        };
        f.setClickable(false);
        f.setFocusable(false);
        // Full alpha (1.0) at index 0 behind Qt's opaque SurfaceView so Chromium renders WebGL2 at full speed
        ((ViewGroup) decor).addView(f, 0, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sHost = f;
        return f;
    }

    private static String toLoopbackFileUrl(Context context, String localPath) {
        if (localPath == null || localPath.isEmpty()) return "";
        try {
            // Preserve '/' path separators so relative URLs inside Live2D .model3.json
            // (e.g. "Asuka.moc3", "Asuka.4096/texture_00.png") resolve against the model folder.
            if (context == null) return "";
            File root = new File(context.getFilesDir(), "obs-studio/vtuber").getCanonicalFile();
            File model = new File(localPath).getCanonicalFile();
            String prefix = root.getPath() + File.separator;
            if (!model.getPath().startsWith(prefix)) {
                return WebRemoteServer.registerVtuberModel(model);
            }
            String[] parts = model.getPath().substring(prefix.length()).split("/");
            StringBuilder sb = new StringBuilder(WebRemoteServer.getLocalLoopbackBase());
            sb.append("/vtuber-file/").append(WebRemoteServer.getLocalToken());
            for (String part : parts) {
                if (part.isEmpty()) continue;
                sb.append('/').append(URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String prepareLive2dModelPath(Context ctx, String path) {
        if (path == null || path.isEmpty()) return "";
        File src = new File(path);
        if (!src.isFile()) return "";
        String lower = src.getName().toLowerCase(Locale.US);
        File cacheDir = new File(ctx.getFilesDir(), "obs-media-cache");
        File staleAuto = new File(cacheDir, "auto.model3.json");
        if (staleAuto.isFile()) staleAuto.delete();
        boolean inFlatCache = src.getParentFile() != null
            && src.getParentFile().getAbsolutePath().equals(cacheDir.getAbsolutePath());

        if (lower.endsWith(".model3.json") || lower.endsWith(".json")) {
            if (inFlatCache) {
                String origName = stripCachePrefix(src.getName());
                File matched = findLive2dFileByName(ctx, origName);
                if (matched != null) return toLoopbackFileUrl(ctx, matched.getAbsolutePath());
            }
            return toLoopbackFileUrl(ctx, src.getAbsolutePath());
        }
        if (lower.endsWith(".moc3")) {
            if (inFlatCache) {
                String origMocName = stripCachePrefix(src.getName());
                File matchedMoc = findLive2dFileByName(ctx, origMocName);
                if (matchedMoc != null) {
                    src = matchedMoc;
                    inFlatCache = false;
                } else {
                    Log.w(TAG, "Isolated .moc3 in flat cache without textures: " + src.getName());
                    return "";
                }
            }
            File dir = src.getParentFile();
            String sibling = findDirectModel3Json(dir);
            if (sibling != null) return toLoopbackFileUrl(ctx, sibling);
            String generated = generateAutoModel3Json(src);
            return generated != null ? toLoopbackFileUrl(ctx, generated) : "";
        }
        if (!lower.endsWith(".zip")) {
            return toLoopbackFileUrl(ctx, src.getAbsolutePath());
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((src.getAbsolutePath() + ":" + src.length()).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; ++i) hex.append(String.format(Locale.US, "%02x", d[i]));
            File outDir = new File(ctx.getFilesDir(), "obs-studio/vtuber/models/" + hex);
            String model3 = findModel3Json(outDir);
            if (model3 == null) {
                if (!outDir.isDirectory()) outDir.mkdirs();
                String canonPrefix = outDir.getCanonicalPath() + File.separator;
                final int MAX_ENTRIES = 2000;
                final long MAX_TOTAL_BYTES = 256L * 1024L * 1024L;
                int entries = 0;
                long totalBytes = 0;
                try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(src)))) {
                    ZipEntry e;
                    byte[] buf = new byte[65536];
                    while ((e = zis.getNextEntry()) != null) {
                        if (++entries > MAX_ENTRIES) {
                            throw new SecurityException("Live2D zip exceeds entry limit");
                        }
                        String n = e.getName().replace('\\', '/');
                        if (e.isDirectory() || n.contains("..")) continue;
                        File dst = new File(outDir, n);
                        if (!dst.getCanonicalPath().startsWith(canonPrefix)) continue;
                        File p = dst.getParentFile();
                        if (p != null && !p.isDirectory()) p.mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(dst)) {
                            int r;
                            while ((r = zis.read(buf)) != -1) {
                                totalBytes += r;
                                if (totalBytes > MAX_TOTAL_BYTES) {
                                    throw new SecurityException("Live2D zip exceeds max size");
                                }
                                fos.write(buf, 0, r);
                            }
                        }
                    }
                }
                model3 = findModel3Json(outDir);
                if (model3 == null) {
                    File moc = findMoc3File(outDir);
                    if (moc != null) model3 = generateAutoModel3Json(moc);
                }
            }
            Log.i(TAG, "Prepared Live2D model3=" + model3);
            return model3 != null ? toLoopbackFileUrl(ctx, model3) : "";
        } catch (Exception e) {
            Log.e(TAG, "prepareLive2dModelPath failed", e);
            return "";
        }
    }

    private static String stripCachePrefix(String name) {
        if (name != null && name.length() > 13 && name.charAt(12) == '_') {
            boolean allHex = true;
            for (int i = 0; i < 12; ++i) {
                char c = name.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                    allHex = false;
                    break;
                }
            }
            if (allHex) return name.substring(13);
        }
        return name != null ? name : "";
    }

    private static File findLive2dFileByName(Context ctx, String targetName) {
        if (targetName == null || targetName.isEmpty()) return null;
        File modelsRoot = new File(ctx.getFilesDir(), "obs-studio/vtuber/models");
        File found = findFileByNameRecursive(modelsRoot, targetName, 6);
        if (found != null) return found;
        try {
            File ext = android.os.Environment.getExternalStorageDirectory();
            if (ext != null && ext.isDirectory()) {
                for (String sub : new String[]{"Download", "Documents"}) {
                    found = findFileByNameRecursive(new File(ext, sub), targetName, 5);
                    if (found != null) return found;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static File findFileByNameRecursive(File dir, String targetName, int maxDepth) {
        if (dir == null || !dir.isDirectory() || maxDepth < 0) return null;
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File f : list) {
            if (f.isFile() && f.getName().equalsIgnoreCase(targetName)) return f;
        }
        if (maxDepth > 0) {
            for (File f : list) {
                if (f.isDirectory()) {
                    File sub = findFileByNameRecursive(f, targetName, maxDepth - 1);
                    if (sub != null) return sub;
                }
            }
        }
        return null;
    }

    private static String findDirectModel3Json(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File f : list) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".model3.json")) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static File findMoc3File(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File f : list) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".moc3")) return f;
            if (f.isDirectory()) {
                File sub = findMoc3File(f);
                if (sub != null) return sub;
            }
        }
        return null;
    }

    private static void collectTextures(File baseDir, File curDir, java.util.List<String> out) {
        File[] list = curDir.listFiles();
        if (list == null) return;
        java.util.Arrays.sort(list);
        for (File f : list) {
            if (f.isDirectory()) {
                collectTextures(baseDir, f, out);
            } else if (f.isFile()) {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".jpg") || n.endsWith(".jpeg")) {
                    String rel = baseDir.toURI().relativize(f.toURI()).getPath();
                    if (rel != null && !rel.isEmpty()) out.add(rel);
                }
            }
        }
    }

    private static String generateAutoModel3Json(File mocFile) {
        try {
            File dir = mocFile.getParentFile();
            if (dir == null || !dir.isDirectory()) return null;
            java.util.List<String> textures = new java.util.ArrayList<>();
            collectTextures(dir, dir, textures);
            if (textures.isEmpty()) {
                Log.w(TAG, "No textures found next to " + mocFile.getName() + "; select the full .zip instead.");
                return null;
            }
            org.json.JSONArray texArr = new org.json.JSONArray();
            for (String t : textures) texArr.put(t);
            JSONObject refs = new JSONObject();
            refs.put("Moc", mocFile.getName());
            refs.put("Textures", texArr);
            JSONObject root = new JSONObject();
            root.put("Version", 3);
            root.put("FileReferences", refs);
            File out = new File(dir, "auto.model3.json");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "generateAutoModel3Json failed", e);
            return null;
        }
    }

    private static String findModel3Json(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File f : list) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".model3.json")) {
                return f.getAbsolutePath();
            }
            if (f.isDirectory()) {
                String sub = findModel3Json(f);
                if (sub != null) return sub;
            }
        }
        return null;
    }

    private static Bitmap loadSafeBitmap(String path) {
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        if (!f.isFile()) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            int sample = 1;
            while (maxDim / sample > 2048) sample *= 2;
            BitmapFactory.Options dec = new BitmapFactory.Options();
            dec.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), dec);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readHeaderMagic(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) return 0;
            return ((b0 & 0xFF) << 8) | (b1 & 0xFF);
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean readFully(InputStream in, byte[] buf, int len) throws java.io.IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) return false;
            off += r;
        }
        return true;
    }

    private static void skipFully(InputStream in, long n) throws java.io.IOException {
        byte[] scratch = new byte[16384];
        long rem = n;
        while (rem > 0) {
            int toRead = (int) Math.min(scratch.length, rem);
            int r = in.read(scratch, 0, toRead);
            if (r < 0) break;
            rem -= r;
        }
    }

    private static String readTarString(byte[] hdr, int off, int maxLen) {
        int end = off;
        int limit = off + maxLen;
        while (end < limit && hdr[end] != 0) end++;
        return new String(hdr, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static long parseTarOctal(byte[] hdr, int off, int len) {
        String s = readTarString(hdr, off, len);
        if (s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean isCubismCoreEntryName(String rawName) {
        if (rawName == null || rawName.isEmpty()) return false;
        String n = rawName.replace('\\', '/').toLowerCase(Locale.US);
        int slash = n.lastIndexOf('/');
        String base = slash >= 0 ? n.substring(slash + 1) : n;
        return "live2dcubismcore.min.js".equals(base) || "live2dcubismcore.js".equals(base);
    }

    private static boolean extractCubismFromZip(File src, File dst) {
        File tmp = new File(dst.getParentFile(), "live2dcubismcore.min.js.tmp");
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(src)))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                if (isCubismCoreEntryName(e.getName())) {
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        int r;
                        while ((r = zis.read(buf)) != -1) out.write(buf, 0, r);
                    }
                    if (tmp.length() > 1024 && tmp.renameTo(dst)) {
                        Log.i(TAG, "Extracted " + e.getName() + " from ZIP (" + dst.length() + " bytes)");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "extractCubismFromZip failed: " + e.getMessage());
        }
        if (tmp.exists()) tmp.delete();
        return false;
    }

    private static boolean extractCubismFromTgz(File src, File dst) {
        File tmp = new File(dst.getParentFile(), "live2dcubismcore.min.js.tmp");
        try (InputStream gis = new BufferedInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(src)), 65536))) {
            byte[] hdr = new byte[512];
            byte[] buf = new byte[65536];
            while (readFully(gis, hdr, 512)) {
                boolean allZero = true;
                for (int i = 0; i < 512; ++i) {
                    if (hdr[i] != 0) { allZero = false; break; }
                }
                if (allZero) break;
                String name = readTarString(hdr, 0, 100);
                String prefix = readTarString(hdr, 345, 155);
                String fullName = prefix.isEmpty() ? name : (prefix + "/" + name);
                long size = parseTarOctal(hdr, 124, 12);
                byte typeFlag = hdr[156];
                boolean isFile = (typeFlag == 0 || typeFlag == '0');
                if (isFile && size > 1024 && size < 16L * 1024L * 1024L && isCubismCoreEntryName(fullName)) {
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        long rem = size;
                        while (rem > 0) {
                            int r = gis.read(buf, 0, (int) Math.min(buf.length, rem));
                            if (r < 0) break;
                            out.write(buf, 0, r);
                            rem -= r;
                        }
                    }
                    if (tmp.length() == size) {
                        if (dst.exists()) dst.delete();
                        if (tmp.renameTo(dst)) {
                            Log.i(TAG, "Extracted " + fullName + " from TGZ (" + dst.length() + " bytes)");
                            return true;
                        }
                    }
                } else {
                    long padded = ((size + 511L) / 512L) * 512L;
                    if (padded > 0) skipFully(gis, padded);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "extractCubismFromTgz failed: " + e.getMessage());
        }
        if (tmp.exists()) tmp.delete();
        return false;
    }

    private static void autoRepairCubismCoreAsync(Context appCtx) {
        File dst = new File(appCtx.getFilesDir(), "obs-studio/vtuber/live2dcubismcore.min.js");
        if (!dst.isFile()) return;
        int magic = readHeaderMagic(dst);
        if (magic != 0x1F8B && magic != 0x504B) return;
        Log.i(TAG, "Detected archived Cubism Core (" + dst.length() + " bytes); auto-extracting live2dcubismcore.min.js");
        sPrepExec.execute(() -> installCubismCore(appCtx, dst.getAbsolutePath()));
    }

    public static String installCubismCore(Context ctx, String sourcePath) {
        if (ctx == null || sourcePath == null || sourcePath.isEmpty()) return "ERR\tArchivo vacío.";
        File src = new File(sourcePath);
        if (!src.isFile() || !src.canRead()) return "ERR\tNo se puede leer el archivo.";
        File dstDir = new File(ctx.getFilesDir(), "obs-studio/vtuber");
        if (!dstDir.isDirectory()) dstDir.mkdirs();
        File dst = new File(dstDir, "live2dcubismcore.min.js");
        try {
            int magic = readHeaderMagic(src);
            boolean extracted = false;
            if (magic == 0x504B) { // ZIP archive (e.g. CubismSdkForWeb-*.zip)
                extracted = extractCubismFromZip(src, dst);
                if (!extracted) return "ERR\tEl archivo .zip no contiene live2dcubismcore.min.js.";
            } else if (magic == 0x1F8B) { // GZIP / .tgz archive (e.g. live2dcubismcore-*.tgz)
                extracted = extractCubismFromTgz(src, dst);
                if (!extracted) return "ERR\tEl archivo .tgz/.gz no contiene live2dcubismcore.min.js.";
            } else {
                if (src.length() > 8L * 1024L * 1024L) {
                    return "ERR\tEl archivo es demasiado grande; selecciona live2dcubismcore.min.js, el .zip o el .tgz del SDK.";
                }
                File tmp = new File(dstDir, "live2dcubismcore.min.js.tmp");
                try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                }
                if (dst.exists()) dst.delete();
                if (!tmp.renameTo(dst)) return "ERR\tNo se pudo guardar live2dcubismcore.min.js.";
                Log.i(TAG, "Installed live2dcubismcore.min.js (" + dst.length() + " bytes)");
            }
            sMain.post(() -> {
                for (VtuberInstance inst : sInstances.values()) {
                    if (inst != null && !inst.destroyed && inst.webView != null && "live2d".equals(inst.mode)) {
                        inst.webView.evaluateJavascript("if(window.OBS_VTUBER && window.OBS_VTUBER.onCubismInstalled)window.OBS_VTUBER.onCubismInstalled();", null);
                    }
                }
            });
            return "OK\t" + dst.getAbsolutePath();
        } catch (Exception e) {
            return "ERR\t" + e.getMessage();
        }
    }

    public static boolean isFaceTrackingNeeded() {
        for (VtuberInstance inst : sInstances.values()) {
            if (inst != null && !inst.destroyed && inst.visible && inst.faceTracking) {
                return true;
            }
        }
        return false;
    }

    private static void updateHeadlessFaceTracking(Context ctx) {
        boolean needBgFace = false;
        for (VtuberInstance inst : sInstances.values()) {
            if (inst != null && !inst.destroyed && inst.visible && inst.faceTracking
                    && (!"png".equals(inst.mode) || inst.veadoAutoEmotion)) {
                needBgFace = true;
                break;
            }
        }
        CameraController.refreshFaceTrackingMode();
        if (needBgFace && !CaptureService.cameraActive) {
            FaceTrackerManager.startBackgroundFrontCamera(ctx);
        } else {
            FaceTrackerManager.stopBackgroundFrontCamera();
        }
    }

    public static void onMainCameraStateChanged(Context ctx, boolean mainCameraActive) {
        sMain.post(() -> {
            if (mainCameraActive) {
                FaceTrackerManager.stopBackgroundFrontCamera();
            } else if (ctx != null) {
                updateHeadlessFaceTracking(ctx);
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static void createOrUpdate(long id, String configJson, int width, int height, int fps) {
        sMain.post(() -> {
            QtObsActivity activity = QtObsActivity.getActiveInstance();
            if (activity == null || activity.isFinishing()) return;
            WebRemoteServer.ensureStarted(activity);
            FaceTrackerManager.startVmcReceiver(39539);

            VtuberInstance inst = sInstances.get(id);
            if (inst == null) {
                inst = new VtuberInstance(id);
                sInstances.put(id, inst);
            }
            inst.width = Math.max(128, Math.min(1280, width));
            inst.height = Math.max(128, Math.min(1280, height));
            inst.fps = Math.max(1, Math.min(60, fps));

            String mode = "png";
            String veadoFile = "", pIdle = "", pTalk = "", pBlink = "", pBlinkTalk = "";
            String rawLive2dPath = "";
            String resolvedJson = configJson;
            boolean useZeroCopy = (Build.VERSION.SDK_INT >= 29);
            boolean enableVtsApi = true;
            boolean wantHands = true;
            try {
                JSONObject obj = new JSONObject(configJson);
                mode = obj.optString("mode", "png");
                String quality = obj.optString("quality", "high");
                if (!"png".equals(mode)) {
                    int minDim = "ultra".equals(quality) ? 1024 : ("high".equals(quality) ? 768 : 0);
                    if (minDim > 0 && Math.max(inst.width, inst.height) < minDim) {
                        float scale = minDim / (float) Math.max(inst.width, inst.height);
                        inst.width = Math.min(1280, Math.round(inst.width * scale));
                        inst.height = Math.min(1280, Math.round(inst.height * scale));
                    }
                }
                inst.bounce = obj.optBoolean("bounce", true);
                inst.lipSync = obj.optBoolean("lipSync", true);
                inst.faceTracking = obj.optBoolean("faceTracking", true);
                wantHands = obj.optBoolean("handTracking", true);
                enableVtsApi = obj.optBoolean("vtsApi", true);
                useZeroCopy = (Build.VERSION.SDK_INT >= 29) && obj.optBoolean("zeroCopy", true);
                inst.voiceThreshold = (float) obj.optDouble("voiceThreshold", 0.04);
                inst.veadoStateIndex = obj.optInt("veadoState", 0);
                inst.veadoAutoEmotion = obj.optBoolean("veadoAutoEmotion", false);
                inst.veadoHappyState = obj.optInt("veadoHappyState", 1);
                inst.veadoSadState = obj.optInt("veadoSadState", 2);
                inst.veadoAngryState = obj.optInt("veadoAngryState", 3);
                inst.veadoSurprisedState = obj.optInt("veadoSurprisedState", 4);
                veadoFile = obj.optString("veadominiFile", "");
                pIdle = obj.optString("pngIdle", "");
                pTalk = obj.optString("pngTalk", "");
                pBlink = obj.optString("pngBlink", "");
                pBlinkTalk = obj.optString("pngBlinkTalk", "");
                rawLive2dPath = obj.optString("live2dFile", "");

                obj.put("width", inst.width);
                obj.put("height", inst.height);
                obj.put("fps", inst.fps);
                obj.put("zeroCopy", useZeroCopy);
                obj.put("vrmFile", toLoopbackFileUrl(activity, obj.optString("vrmFile", "")));
                obj.put("vrmaFile", toLoopbackFileUrl(activity, obj.optString("vrmaFile", "")));
                obj.put("inochiFile", toLoopbackFileUrl(activity, obj.optString("inochiFile", "")));
                boolean asyncZip = "live2d".equals(mode) && rawLive2dPath.toLowerCase(Locale.US).endsWith(".zip");
                if (asyncZip) {
                    obj.put("live2dFile", "");
                    obj.put("live2dPreparing", true);
                } else {
                    obj.put("live2dFile", prepareLive2dModelPath(activity, rawLive2dPath));
                    obj.put("live2dPreparing", false);
                }
                resolvedJson = obj.toString();
            } catch (Exception e) {
                Log.w(TAG, "Config parse failed", e);
            }

            if ("live2d".equals(mode)) {
                autoRepairCubismCoreAsync(activity.getApplicationContext());
            }

            if ("live2d".equals(mode) && rawLive2dPath.toLowerCase(Locale.US).endsWith(".zip")) {
                final String zipPath = rawLive2dPath;
                final VtuberInstance zipTarget = inst;
                final Context appCtx = activity.getApplicationContext();
                final String baseJson = resolvedJson;
                sPrepExec.execute(() -> {
                    String modelUrl = prepareLive2dModelPath(appCtx, zipPath);
                    sMain.post(() -> {
                        if (zipTarget.destroyed || !"live2d".equals(zipTarget.mode)) return;
                        try {
                            JSONObject upd = new JSONObject(baseJson);
                            upd.put("live2dFile", modelUrl);
                            upd.put("live2dPreparing", false);
                            upd.put("live2dPrepError", modelUrl.isEmpty() ? "No se encontró .model3.json/.moc3 dentro del .zip" : "");
                            String finalJson = upd.toString();
                            zipTarget.pendingConfigJson = finalJson;
                            if (zipTarget.webView != null && zipTarget.pageReady) {
                                zipTarget.webView.evaluateJavascript("window.__OBS_VTUBER_PENDING_CFG=" + finalJson
                                    + ";if(window.OBS_VTUBER)window.OBS_VTUBER.setConfig(window.__OBS_VTUBER_PENDING_CFG);", null);
                            }
                        } catch (Exception ignored) {}
                    });
                });
            }

            MediaPipeVtuberTracker.setHandTrackingWanted(wantHands);
            if (enableVtsApi) {
                VtsApiServer.ensureStarted();
            }

            inst.mode = mode;

            if ("png".equals(mode)) {
                sMain.removeCallbacks(inst.webTrackTick);
                if (!veadoFile.equals(inst.veadominiPath)) {
                    inst.veadominiPath = veadoFile;
                    if (inst.veadoAvatar != null) { inst.veadoAvatar.recycle(); inst.veadoAvatar = null; }
                    if (!veadoFile.isEmpty()) {
                        inst.veadoAvatar = VeadominiParser.load(veadoFile);
                    }
                }
                if (!pIdle.equals(inst.pngIdlePath)) {
                    inst.pngIdlePath = pIdle;
                    if (inst.bmpIdle != null) inst.bmpIdle.recycle();
                    inst.bmpIdle = loadSafeBitmap(pIdle);
                }
                if (!pTalk.equals(inst.pngTalkPath)) {
                    inst.pngTalkPath = pTalk;
                    if (inst.bmpTalk != null) inst.bmpTalk.recycle();
                    inst.bmpTalk = loadSafeBitmap(pTalk);
                }
                if (!pBlink.equals(inst.pngBlinkPath)) {
                    inst.pngBlinkPath = pBlink;
                    if (inst.bmpBlink != null) inst.bmpBlink.recycle();
                    inst.bmpBlink = loadSafeBitmap(pBlink);
                }
                if (!pBlinkTalk.equals(inst.pngBlinkTalkPath)) {
                    inst.pngBlinkTalkPath = pBlinkTalk;
                    if (inst.bmpBlinkTalk != null) inst.bmpBlinkTalk.recycle();
                    inst.bmpBlinkTalk = loadSafeBitmap(pBlinkTalk);
                }
                BrowserSourceManager.destroy(-id);
                inst.webView = null;
                inst.pageReady = false;
                sMain.removeCallbacks(inst.nativeTick);
                sMain.post(inst.nativeTick);
                updateHeadlessFaceTracking(activity);
                return;
            }

            // Accelerated private window: GPU Zero-Copy HardwareBuffer side-by-side alpha-pack (API 29+)
            // with automatic WebMessage/PBO fallback when disabled or on older APIs.
            sMain.removeCallbacks(inst.nativeTick);
            updateHeadlessFaceTracking(activity);
            inst.pendingConfigJson = resolvedJson;
            final VtuberInstance target = inst;
            String pageUrl = WebRemoteServer.getLocalLoopbackBase() + "/vtuber/index.html?id=" + inst.id
                + "&transport=bridge" + (useZeroCopy ? "&zc=1" : "")
                + "#localToken=" + WebRemoteServer.getLocalToken();
            BrowserSourceManager.createVtuber(inst.id, pageUrl, inst.width, inst.height, inst.fps, useZeroCopy, view -> {
                if (target.destroyed || "png".equals(target.mode)) return;
                target.webView = view;
                target.pageReady = true;
                BrowserSourceManager.setVisible(-target.id, target.visible);
                view.evaluateJavascript("window.__OBS_VTUBER_PENDING_CFG=" + target.pendingConfigJson
                    + ";if(window.OBS_VTUBER)window.OBS_VTUBER.setConfig(window.__OBS_VTUBER_PENDING_CFG);", null);
                sMain.removeCallbacks(target.webTrackTick);
                sMain.post(target.webTrackTick);
            });
            if (inst.webView != null && inst.pageReady) {
                inst.webView.evaluateJavascript("window.__OBS_VTUBER_PENDING_CFG=" + resolvedJson
                    + ";if(window.OBS_VTUBER)window.OBS_VTUBER.setConfig(window.__OBS_VTUBER_PENDING_CFG);", null);
            }

            sMain.removeCallbacks(inst.webTrackTick);
            sMain.post(inst.webTrackTick);
        });
    }

    public static void setVisible(long id, boolean visible) {
        sMain.post(() -> {
            VtuberInstance inst = sInstances.get(id);
            if (inst == null || inst.destroyed) return;
            inst.visible = visible;
            sMain.removeCallbacks(inst.nativeTick);
            sMain.removeCallbacks(inst.webTrackTick);
            BrowserSourceManager.setVisible(-id, visible);
            if (visible) sMain.post("png".equals(inst.mode) ? inst.nativeTick : inst.webTrackTick);
            QtObsActivity activity = QtObsActivity.getActiveInstance();
            if (activity != null) updateHeadlessFaceTracking(activity);
        });
    }

    public static void destroy(long id) {
        sMain.post(() -> {
            VtuberInstance inst = sInstances.remove(id);
            if (inst == null) return;
            inst.destroyed = true;
            sMain.removeCallbacks(inst.nativeTick);
            sMain.removeCallbacks(inst.webTrackTick);
            inst.releaseNativeImages();
            if (inst.outBitmap != null) { inst.outBitmap.recycle(); inst.outBitmap = null; }
            inst.outRgba = null;
            BrowserSourceManager.destroy(-id);
            inst.webView = null;
            QtObsActivity activity = QtObsActivity.getActiveInstance();
            if (activity != null) updateHeadlessFaceTracking(activity);
        });
    }

    public static void triggerQuickGesture(String gestureId, int durationMs) {
        FaceTrackerManager.triggerGesture(gestureId, durationMs > 0 ? durationMs : 4000L);
        if (gestureId == null || "reset".equalsIgnoreCase(gestureId)) {
            triggerLive2dExpression("reset", false);
        }
    }

    public static void triggerLive2dExpression(String exprKey, boolean active) {
        final String safeKey = (exprKey == null ? "reset" : exprKey.replace("'", "").replace("\\", ""));
        sMain.post(() -> {
            for (VtuberInstance inst : sInstances.values()) {
                if (inst != null && !inst.destroyed && inst.webView != null && inst.pageReady) {
                    inst.webView.evaluateJavascript(
                        "if(window.OBS_VTUBER && window.OBS_VTUBER.setManualExpression)window.OBS_VTUBER.setManualExpression('"
                            + safeKey + "'," + (active ? "true" : "false") + ");",
                        null);
                }
            }
        });
    }

    public static void destroyAll() {
        sMain.post(() -> {
            FaceTrackerManager.stopBackgroundFrontCamera();
            VtsApiServer.stop();
            MediaPipeVtuberTracker.shutdown();
            for (Long id : sInstances.keySet().toArray(new Long[0])) destroy(id);
            if (sHost != null) {
                ViewGroup p = (ViewGroup) sHost.getParent();
                if (p != null) p.removeView(sHost);
                sHost = null;
            }
        });
    }

    private VtuberSourceManager() {}
}
