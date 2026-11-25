// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;

public final class LiveGraphicsManager {
    private static final String TAG = "ObsLiveGfx";
    private static final HandlerThread sRenderThread = new HandlerThread("OBS-LiveGraphics");
    private static final Handler sRender;

    static {
        sRenderThread.start();
        sRender = new Handler(sRenderThread.getLooper());
    }
    private static final Map<Long, GfxSourceInstance> sInstances = new HashMap<>();
    private static final Object sStateLock = new Object();
    private static volatile Context sAppCtx;
    private static volatile boolean sStateLoaded = false;

    // Shared persistent state across OBS sources, native panel, floating overlay, and Web Remote
    static final class State {
        // Scoreboard
        boolean sbVisible = true;
        int sbStyle = 0; // 0=Barra clasica, 1=Esquina TV, 2=Tarjeta torneo, 3=Esports Neon, 4=Minimalista Claro
        String sbHomeName = "LOCAL";
        String sbAwayName = "VISITA";
        String sbLeague = "EN VIVO";
        String sbHomeColor = "#2563EB";
        String sbAwayColor = "#DC2626";
        String sbHomeLogo = "";
        String sbAwayLogo = "";
        int sbHomeScore = 0;
        int sbAwayScore = 0;
        String sbPeriod = "1T";
        boolean sbShowClock = true;
        long sbClockBaseMs = 0L;
        boolean sbClockRunning = false;
        long sbClockStartMs = 0L;

        // Lower Third
        boolean ltVisible = true;
        String ltBadge = "EN VIVO";
        String ltTitle = "Nombre del Presentador";
        String ltSubtitle = "Cargo o Tema del Programa";
        String ltAccent = "#2563EB";
        int ltStyle = 0; // 0=Barra moderna, 1=Tarjeta, 2=Minimalista, 3=Noticiero, 4=Centrado
        final List<JSONObject> ltPresets = new ArrayList<>();

        // Timer / Stopwatch
        boolean tmVisible = true;
        int tmStyle = 0; // 0=Tarjeta oscura, 1=Pildora, 2=Anillo circular, 3=LED contraste, 4=Minimalista
        String tmMode = "countdown"; // "countdown" | "stopwatch"
        String tmLabel = "COMENZAMOS EN";
        String tmDone = "EN VIVO";
        String tmAccent = "#2563EB";
        int tmDurationSec = 300;
        long tmElapsedBaseMs = 0L;
        boolean tmRunning = false;
        long tmStartMs = 0L;

        // Bible
        boolean bbVisible = true;
        int bbBook = 42; // Juan
        int bbChapter = 3;
        int bbVerse = 16;
        int bbVerseEnd = 16;
        boolean bbUseCustom = false;
        String bbCustomRef = "";
        String bbCustomText = "";
        String bbAccent = "#D97706";
        int bbStyle = 0;
        int bbFontSize = 32;
    }

    private static final State sState = new State();
    private static String sLoadedHomeLogoPath = "";
    private static String sLoadedAwayLogoPath = "";
    private static Bitmap sHomeLogoBmp = null;
    private static Bitmap sAwayLogoBmp = null;

    private static Bitmap decodeLogo(String path) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        int largest = Math.max(options.outWidth, options.outHeight);
        if (largest <= 0) return null;
        options.inSampleSize = 1;
        while (largest / options.inSampleSize > 256) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, options);
    }

    private static Bitmap getCachedLogo(String path, boolean isHome) {
        String p = path != null ? path.trim() : "";
        if (isHome) {
            if (!p.equals(sLoadedHomeLogoPath)) {
                sLoadedHomeLogoPath = p;
                if (sHomeLogoBmp != null) { sHomeLogoBmp.recycle(); sHomeLogoBmp = null; }
                if (!p.isEmpty() && new File(p).isFile()) {
                    try { sHomeLogoBmp = decodeLogo(p); } catch (Throwable ignored) {}
                }
            }
            return sHomeLogoBmp;
        } else {
            if (!p.equals(sLoadedAwayLogoPath)) {
                sLoadedAwayLogoPath = p;
                if (sAwayLogoBmp != null) { sAwayLogoBmp.recycle(); sAwayLogoBmp = null; }
                if (!p.isEmpty() && new File(p).isFile()) {
                    try { sAwayLogoBmp = decodeLogo(p); } catch (Throwable ignored) {}
                }
            }
            return sAwayLogoBmp;
        }
    }

    private static void drawLogoFit(Canvas c, Bitmap bmp, float cx, float cy, float maxBox, int alpha255) {
        if (bmp == null || bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return;
        float sc = Math.min(maxBox / bmp.getWidth(), maxBox / bmp.getHeight());
        float dw = bmp.getWidth() * sc;
        float dh = bmp.getHeight() * sc;
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        bp.setAlpha(alpha255);
        c.drawBitmap(bmp, new Rect(0, 0, bmp.getWidth(), bmp.getHeight()),
            new RectF(cx - dw * 0.5f, cy - dh * 0.5f, cx + dw * 0.5f, cy + dh * 0.5f), bp);
    }

    private static final class GfxSourceInstance {
        final long id;
        String kind;
        int width;
        int height;
        Bitmap bitmap;
        Canvas canvas;
        ByteBuffer rgba;
        float animAlpha = 1f;
        JSONObject localLowerThird;
        boolean showing = false;
        volatile boolean destroyed = false;

        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (destroyed || !showing) return;
                long next = renderFrame(GfxSourceInstance.this);
                if (next > 0 && showing && !destroyed) sRender.postDelayed(this, next);
            }
        };

        GfxSourceInstance(long id, String kind, int w, int h) {
            this.id = id;
            this.kind = kind;
            this.width = w;
            this.height = h;
        }

        void ensureBuffers() {
            int cw = Math.max(256, Math.min(1920, width));
            int ch = Math.max(120, Math.min(1080, height));
            if (bitmap != null && bitmap.getWidth() == cw && bitmap.getHeight() == ch && rgba != null) return;
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
            rgba = ByteBuffer.allocateDirect(cw * ch * 4);
            width = cw;
            height = ch;
        }
    }

    public static void init(Context ctx) {
        if (ctx != null) sAppCtx = ctx.getApplicationContext();
        ensureStateLoaded();
    }

    private static File stateFile() {
        if (sAppCtx == null) return null;
        File dir = new File(sAppCtx.getFilesDir(), "obs-studio");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, "android-graphics.json");
    }

    private static void ensureStateLoaded() {
        if (sStateLoaded || sAppCtx == null) return;
        synchronized (sStateLock) {
            if (sStateLoaded) return;
            sStateLoaded = true;
            File f = stateFile();
            if (f == null || !f.isFile()) return;
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] b = new byte[(int) Math.min(f.length(), 262144)];
                int r = fis.read(b);
                if (r <= 0) return;
                JSONObject j = new JSONObject(new String(b, 0, r, StandardCharsets.UTF_8));
                JSONObject sb = j.optJSONObject("scoreboard");
                if (sb != null) {
                    sState.sbVisible = sb.optBoolean("visible", true);
                    sState.sbStyle = sb.optInt("style", 0);
                    sState.sbHomeName = sb.optString("homeName", "LOCAL");
                    sState.sbAwayName = sb.optString("awayName", "VISITA");
                    sState.sbLeague = sb.optString("league", "EN VIVO");
                    sState.sbHomeColor = sb.optString("homeColor", "#2563EB");
                    sState.sbAwayColor = sb.optString("awayColor", "#DC2626");
                    sState.sbHomeLogo = sb.optString("homeLogo", "");
                    sState.sbAwayLogo = sb.optString("awayLogo", "");
                    sState.sbHomeScore = sb.optInt("homeScore", 0);
                    sState.sbAwayScore = sb.optInt("awayScore", 0);
                    sState.sbPeriod = sb.optString("period", "1T");
                    sState.sbShowClock = sb.optBoolean("showClock", true);
                    sState.sbClockBaseMs = sb.optLong("clockMs", 0L);
                }
                JSONObject lt = j.optJSONObject("lowerthird");
                if (lt != null) {
                    sState.ltVisible = lt.optBoolean("visible", true);
                    sState.ltBadge = lt.optString("badge", "EN VIVO");
                    sState.ltTitle = lt.optString("title", "Nombre del Presentador");
                    sState.ltSubtitle = lt.optString("subtitle", "Cargo o Tema del Programa");
                    sState.ltAccent = lt.optString("accentColor", "#2563EB");
                    sState.ltStyle = lt.optInt("style", 0);
                    JSONArray pr = lt.optJSONArray("presets");
                    sState.ltPresets.clear();
                    if (pr != null) {
                        for (int i = 0; i < pr.length(); ++i) {
                            JSONObject p = pr.optJSONObject(i);
                            if (p != null) sState.ltPresets.add(p);
                        }
                    }
                }
                JSONObject tm = j.optJSONObject("timer");
                if (tm != null) {
                    sState.tmVisible = tm.optBoolean("visible", true);
                    sState.tmStyle = tm.optInt("style", 0);
                    sState.tmMode = tm.optString("mode", "countdown");
                    sState.tmLabel = tm.optString("label", "COMENZAMOS EN");
                    sState.tmDone = tm.optString("doneText", "EN VIVO");
                    sState.tmAccent = tm.optString("accentColor", "#2563EB");
                    sState.tmDurationSec = tm.optInt("durationSec", 300);
                    sState.tmElapsedBaseMs = tm.optLong("elapsedMs", 0L);
                }
                JSONObject bb = j.optJSONObject("bible");
                if (bb != null) {
                    sState.bbVisible = bb.optBoolean("visible", true);
                    sState.bbBook = bb.optInt("book", 42);
                    sState.bbChapter = bb.optInt("chapter", 3);
                    sState.bbVerse = bb.optInt("verse", 16);
                    sState.bbVerseEnd = bb.optInt("verseEnd", 16);
                    sState.bbUseCustom = bb.optBoolean("useCustom", false);
                    sState.bbCustomRef = bb.optString("customRef", "");
                    sState.bbCustomText = bb.optString("customText", "");
                    sState.bbAccent = bb.optString("accentColor", "#D97706");
                    sState.bbStyle = bb.optInt("style", 0);
                    sState.bbFontSize = bb.optInt("fontSize", 32);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to load graphics state", t);
            }
        }
    }

    public static void saveState() {
        File f = stateFile();
        if (f == null) return;
        try {
            String json = buildStateJsonObject(sAppCtx).toString(2);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to save graphics state", t);
        }
    }

    private static long getScoreboardClockMsLocked() {
        long ms = sState.sbClockBaseMs;
        if (sState.sbClockRunning) ms += Math.max(0L, System.currentTimeMillis() - sState.sbClockStartMs);
        return ms;
    }

    private static long getTimerCurrentMsLocked() {
        long elapsed = sState.tmElapsedBaseMs;
        if (sState.tmRunning) elapsed += Math.max(0L, System.currentTimeMillis() - sState.tmStartMs);
        if ("stopwatch".equals(sState.tmMode)) return elapsed;
        long total = sState.tmDurationSec * 1000L;
        return Math.max(0L, total - elapsed);
    }

    private static String formatClock(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long hrs = totalSec / 3600L;
        long min = (totalSec % 3600L) / 60L;
        long sec = totalSec % 60L;
        if (hrs > 0) return String.format(Locale.US, "%02d:%02d:%02d", hrs, min, sec);
        return String.format(Locale.US, "%02d:%02d", min, sec);
    }

    private static int parseColorSafe(String hex, int fallback) {
        if (hex == null || hex.trim().isEmpty()) return fallback;
        try { return Color.parseColor(hex.trim()); } catch (Throwable t) { return fallback; }
    }

    public static void createOrUpdate(long id, String kind, String configJson, int width, int height) {
        sRender.post(() -> {
            QtObsActivity act = QtObsActivity.getActiveInstance();
            if (act != null) init(act);
            ensureStateLoaded();
            JSONObject localLowerThird = null;
            try {
                JSONObject obj = new JSONObject(configJson != null ? configJson : "{}");
                if ("lowerthird".equals(kind) && obj.optBoolean("localContent")) localLowerThird = obj;
                boolean apply = obj.optBoolean("applyText", false);
                synchronized (sStateLock) {
                    if ("scoreboard".equals(kind)) {
                        sState.sbShowClock = obj.optBoolean("showClock", sState.sbShowClock);
                        if (obj.has("style")) sState.sbStyle = obj.optInt("style", sState.sbStyle);
                        String hl = obj.optString("homeLogo", "");
                        String al = obj.optString("awayLogo", "");
                        if (!hl.isEmpty()) sState.sbHomeLogo = hl;
                        if (!al.isEmpty()) sState.sbAwayLogo = al;
                        if (apply) {
                            sState.sbHomeName = obj.optString("homeName", sState.sbHomeName);
                            sState.sbAwayName = obj.optString("awayName", sState.sbAwayName);
                            sState.sbLeague = obj.optString("league", sState.sbLeague);
                            sState.sbHomeColor = obj.optString("homeColor", sState.sbHomeColor);
                            sState.sbAwayColor = obj.optString("awayColor", sState.sbAwayColor);
                            saveState();
                        }
                    } else if ("lowerthird".equals(kind) && localLowerThird == null) {
                        sState.ltStyle = obj.optInt("style", sState.ltStyle);
                        if (apply) {
                            sState.ltBadge = obj.optString("badge", sState.ltBadge);
                            sState.ltTitle = obj.optString("title", sState.ltTitle);
                            sState.ltSubtitle = obj.optString("subtitle", sState.ltSubtitle);
                            sState.ltAccent = obj.optString("accentColor", sState.ltAccent);
                            saveState();
                        }
                    } else if ("timer".equals(kind)) {
                        if (obj.has("style")) sState.tmStyle = obj.optInt("style", sState.tmStyle);
                        if (apply) {
                            sState.tmMode = obj.optString("mode", sState.tmMode);
                            sState.tmLabel = obj.optString("label", sState.tmLabel);
                            sState.tmDone = obj.optString("doneText", sState.tmDone);
                            sState.tmAccent = obj.optString("accentColor", sState.tmAccent);
                            sState.tmDurationSec = Math.max(5, obj.optInt("durationSec", sState.tmDurationSec));
                            saveState();
                        }
                    } else if ("bible".equals(kind)) {
                        sState.bbStyle = obj.optInt("style", sState.bbStyle);
                        sState.bbFontSize = Math.max(18, Math.min(64, obj.optInt("fontSize", sState.bbFontSize)));
                        String ac = obj.optString("accentColor", "");
                        if (!ac.isEmpty()) sState.bbAccent = ac;
                    }
                }
            } catch (Throwable ignored) {}

            GfxSourceInstance inst = sInstances.get(id);
            if (inst == null) {
                inst = new GfxSourceInstance(id, kind, width, height);
                sInstances.put(id, inst);
            } else {
                inst.kind = kind;
                inst.width = width;
                inst.height = height;
            }
            inst.localLowerThird = localLowerThird;
            if (inst.showing) {
                sRender.removeCallbacks(inst.tick);
                sRender.post(inst.tick);
            }
        });
    }

    public static void setSourceVisible(long id, boolean visible) {
        sRender.post(() -> {
            GfxSourceInstance inst = sInstances.get(id);
            if (inst == null || inst.destroyed) return;
            if (inst.showing == visible) return;
            inst.showing = visible;
            sRender.removeCallbacks(inst.tick);
            if (visible) {
                sRender.post(inst.tick);
            } else {
                // Free offscreen Bitmap/RGBA buffer (~4-8 MB per source) while hidden in another scene
                if (inst.bitmap != null) { inst.bitmap.recycle(); inst.bitmap = null; }
                inst.canvas = null;
                inst.rgba = null;
            }
        });
    }

    public static void destroy(long id) {
        sRender.post(() -> destroyOnRenderThread(id));
    }

    private static void destroyOnRenderThread(long id) {
        GfxSourceInstance inst = sInstances.remove(id);
        if (inst == null) return;
        inst.destroyed = true;
        sRender.removeCallbacks(inst.tick);
        if (inst.bitmap != null) { inst.bitmap.recycle(); inst.bitmap = null; }
        inst.rgba = null;
    }

    public static void destroyAll() {
        sRender.post(() -> {
            for (Long id : sInstances.keySet().toArray(new Long[0])) destroyOnRenderThread(id);
            if (sHomeLogoBmp != null) sHomeLogoBmp.recycle();
            if (sAwayLogoBmp != null) sAwayLogoBmp.recycle();
            sHomeLogoBmp = null;
            sAwayLogoBmp = null;
            sLoadedHomeLogoPath = "";
            sLoadedAwayLogoPath = "";
        });
    }

    private static void refreshFrames() {
        for (GfxSourceInstance instance : sInstances.values()) {
            if (instance == null || instance.destroyed || !instance.showing) continue;
            sRender.removeCallbacks(instance.tick);
            sRender.post(instance.tick);
        }
    }

    private static long renderFrame(GfxSourceInstance inst) {
        try {
            inst.ensureBuffers();
            Canvas c = inst.canvas;
            int w = inst.width, h = inst.height;
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

            boolean targetVis;
            long clockDelay = 0;
            synchronized (sStateLock) {
                long now = System.currentTimeMillis();
                switch (inst.kind) {
                    case "scoreboard":
                        targetVis = sState.sbVisible;
                        if (sState.sbShowClock && sState.sbClockRunning) {
                            clockDelay = 1000L - getScoreboardClockMsLocked() % 1000L;
                        }
                        break;
                    case "lowerthird":
                        targetVis = sState.ltVisible;
                        int style = inst.localLowerThird == null ? sState.ltStyle
                            : inst.localLowerThird.optInt("style");
                        if (style == 6) clockDelay = 60000L - now % 60000L;
                        break;
                    case "timer":
                        targetVis = sState.tmVisible;
                        if (sState.tmRunning) {
                            long timer = getTimerCurrentMsLocked();
                            if ("stopwatch".equals(sState.tmMode)) clockDelay = 1000L - timer % 1000L;
                            else if (timer > 0) clockDelay = timer % 1000L + 1L;
                        }
                        break;
                    case "bible":
                        targetVis = sState.bbVisible;
                        break;
                    default:
                        targetVis = true;
                }
            }
            float targetAlpha = targetVis ? 1f : 0f;
            float change = Math.max(-0.22f, Math.min(0.22f, targetAlpha - inst.animAlpha));
            inst.animAlpha += change;

            if (inst.animAlpha < 0.01f) inst.animAlpha = 0f;
            if (inst.animAlpha > 0.99f) inst.animAlpha = 1f;

            if (inst.animAlpha > 0.01f) {
                if ("scoreboard".equals(inst.kind)) drawScoreboard(c, w, h, inst.animAlpha);
                else if ("lowerthird".equals(inst.kind)) drawLowerThird(c, w, h, inst.animAlpha, inst.localLowerThird);
                else if ("timer".equals(inst.kind)) drawTimer(c, w, h, inst.animAlpha);
                else if ("bible".equals(inst.kind)) drawBible(c, w, h, inst.animAlpha);
            }

            inst.rgba.clear();
            inst.bitmap.copyPixelsToBuffer(inst.rgba);
            NativeBridge.pushGraphicsFrame(inst.id, inst.rgba, w, h, System.nanoTime());
            if (inst.animAlpha != targetAlpha) return 66L;
            return targetVis ? clockDelay : 0;
        } catch (Throwable t) {
            Log.w(TAG, "renderFrame error: " + t.getMessage());
            return 0;
        }
    }

    private static void drawScoreboard(Canvas c, int w, int h, float alpha) {
        String home, away, league, period, clockStr, homeLogoPath, awayLogoPath;
        int hScore, aScore, hCol, aCol, style;
        boolean showClock;
        synchronized (sStateLock) {
            home = sState.sbHomeName; away = sState.sbAwayName;
            league = sState.sbLeague; period = sState.sbPeriod;
            hScore = sState.sbHomeScore; aScore = sState.sbAwayScore;
            hCol = parseColorSafe(sState.sbHomeColor, Color.parseColor("#2563EB"));
            aCol = parseColorSafe(sState.sbAwayColor, Color.parseColor("#DC2626"));
            homeLogoPath = sState.sbHomeLogo; awayLogoPath = sState.sbAwayLogo;
            showClock = sState.sbShowClock;
            style = sState.sbStyle;
            clockStr = formatClock(getScoreboardClockMsLocked());
        }
        Bitmap homeLogo = getCachedLogo(homeLogoPath, true);
        Bitmap awayLogo = getCachedLogo(awayLogoPath, false);
        int a255 = Math.round(alpha * 255);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        txt.setTextAlign(Paint.Align.CENTER);

        if (style == 1) {
            // 1: Esquina Compacta TV (left-aligned broadcast sports bug)
            float pad = h * 0.12f;
            float boxW = Math.min(w * 0.56f, h * 3.4f);
            float boxH = h - pad * 2f;
            float left = pad;
            float top = pad;
            p.setColor(Color.argb(Math.round(236 * alpha), 15, 23, 42));
            c.drawRoundRect(new RectF(left, top, left + boxW, top + boxH), 12f, 12f, p);
            float rowH = boxH * 0.36f;
            p.setColor(Color.argb(Math.round(245 * alpha), 30, 41, 59));
            c.drawRoundRect(new RectF(left, top, left + boxW, top + boxH * 0.28f), 12f, 12f, p);
            txt.setTextSize(boxH * 0.17f);
            txt.setColor(Color.argb(a255, 226, 232, 240));
            String topStr = period + (showClock ? ("  |  " + clockStr) : "") + (league.isEmpty() ? "" : ("  |  " + league));
            c.drawText(topStr, left + boxW * 0.5f, top + boxH * 0.20f, txt);
            // Home row
            float y1 = top + boxH * 0.30f;
            p.setColor((hCol & 0x00FFFFFF) | (a255 << 24));
            c.drawRect(left + 8f, y1 + 4f, left + 18f, y1 + rowH - 4f, p);
            float nameX1 = left + 28f;
            if (homeLogo != null) {
                drawLogoFit(c, homeLogo, left + 28f + rowH * 0.38f, y1 + rowH * 0.5f, rowH * 0.76f, a255);
                nameX1 += rowH * 0.88f;
            }
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setTextSize(rowH * 0.58f);
            txt.setColor(Color.argb(a255, 255, 255, 255));
            c.drawText(home.toUpperCase(Locale.ROOT), nameX1, y1 + rowH * 0.72f, txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(hScore), left + boxW - 22f, y1 + rowH * 0.74f, txt);
            // Away row
            float y2 = y1 + rowH;
            p.setColor((aCol & 0x00FFFFFF) | (a255 << 24));
            c.drawRect(left + 8f, y2 + 4f, left + 18f, y2 + rowH - 4f, p);
            float nameX2 = left + 28f;
            if (awayLogo != null) {
                drawLogoFit(c, awayLogo, left + 28f + rowH * 0.38f, y2 + rowH * 0.5f, rowH * 0.76f, a255);
                nameX2 += rowH * 0.88f;
            }
            txt.setTextAlign(Paint.Align.LEFT);
            c.drawText(away.toUpperCase(Locale.ROOT), nameX2, y2 + rowH * 0.72f, txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(aScore), left + boxW - 22f, y2 + rowH * 0.74f, txt);
            return;
        }

        if (style == 2) {
            // 2: Tarjeta Central Torneo (big tournament card with bold header & scores)
            float pad = h * 0.08f;
            float cw = Math.min(w - pad * 2f, h * 4.4f);
            float ch = h - pad * 2f;
            float left = (w - cw) * 0.5f;
            float top = pad;
            p.setShader(new LinearGradient(left, top, left, top + ch,
                Color.argb(Math.round(240 * alpha), 15, 23, 42),
                Color.argb(Math.round(240 * alpha), 30, 41, 59), Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(left, top, left + cw, top + ch), 18f, 18f, p);
            p.setShader(null);
            p.setColor(Color.argb(Math.round(235 * alpha), 2, 6, 23));
            c.drawRoundRect(new RectF(left + cw * 0.28f, top, left + cw * 0.72f, top + ch * 0.28f), 10f, 10f, p);
            txt.setTextSize(ch * 0.16f);
            txt.setColor(Color.argb(a255, 248, 250, 252));
            c.drawText((league.isEmpty() ? "TORNEO" : league.toUpperCase(Locale.ROOT)) + "  |  " + period + (showClock ? "  " + clockStr : ""),
                w * 0.5f, top + ch * 0.20f, txt);
            float cardTop = top + ch * 0.34f;
            float cardBot = top + ch - 12f;
            float cardMidY = (cardTop + cardBot) * 0.5f;
            float logoBox = (cardBot - cardTop) * 0.78f;
            p.setColor((hCol & 0x00FFFFFF) | (a255 << 24));
            c.drawRoundRect(new RectF(left + 12f, cardTop, left + cw * 0.43f, cardBot), 12f, 12f, p);
            p.setColor((aCol & 0x00FFFFFF) | (a255 << 24));
            c.drawRoundRect(new RectF(left + cw * 0.57f, cardTop, left + cw - 12f, cardBot), 12f, 12f, p);
            if (homeLogo != null) drawLogoFit(c, homeLogo, left + 18f + logoBox * 0.55f, cardMidY, logoBox, a255);
            if (awayLogo != null) drawLogoFit(c, awayLogo, left + cw - 18f - logoBox * 0.55f, cardMidY, logoBox, a255);
            txt.setTextSize(ch * 0.28f);
            txt.setColor(Color.argb(a255, 255, 255, 255));
            float hTxtX = homeLogo != null ? (left + cw * 0.26f) : (left + cw * 0.22f);
            float aTxtX = awayLogo != null ? (left + cw * 0.74f) : (left + cw * 0.78f);
            c.drawText(home.toUpperCase(Locale.ROOT), hTxtX, cardMidY - (txt.descent() + txt.ascent()) * 0.5f, txt);
            c.drawText(away.toUpperCase(Locale.ROOT), aTxtX, cardMidY - (txt.descent() + txt.ascent()) * 0.5f, txt);
            txt.setTextSize(ch * 0.46f);
            c.drawText(hScore + " - " + aScore, w * 0.5f, cardMidY - (txt.descent() + txt.ascent()) * 0.5f, txt);
            return;
        }

        // Styles 0 (Clasica), 3 (Esports Neon), 4 (Minimalista Claro)
        boolean light = (style == 4);
        boolean neon = (style == 3);
        float pad = h * 0.14f;
        float barH = h - pad * 2f;
        float barW = Math.min(w - pad * 2f, h * 5.2f);
        float left = (w - barW) * 0.5f;
        float top = pad;
        float r = neon ? 4f : (barH * 0.18f);

        p.setColor(light ? Color.argb(Math.round(242 * alpha), 248, 250, 252)
                         : Color.argb(Math.round(235 * alpha), neon ? 9 : 15, neon ? 9 : 23, neon ? 16 : 42));
        c.drawRoundRect(new RectF(left, top, left + barW, top + barH), r, r, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(neon ? Math.max(4f, h * 0.024f) : Math.max(2f, h * 0.014f));
        p.setColor(neon ? Color.argb(a255, 56, 189, 248)
                        : (light ? Color.argb(Math.round(140 * alpha), 15, 23, 42) : Color.argb(Math.round(90 * alpha), 255, 255, 255)));
        c.drawRoundRect(new RectF(left, top, left + barW, top + barH), r, r, p);
        p.setStyle(Paint.Style.FILL);

        float stripW = Math.max(10f, barW * 0.015f);
        p.setColor((hCol & 0x00FFFFFF) | (a255 << 24));
        c.drawRoundRect(new RectF(left, top, left + stripW * 1.4f, top + barH), r, r, p);
        p.setColor((aCol & 0x00FFFFFF) | (a255 << 24));
        c.drawRoundRect(new RectF(left + barW - stripW * 1.4f, top, left + barW, top + barH), r, r, p);

        float midW = showClock ? barW * 0.20f : barW * 0.13f;
        float midL = left + (barW - midW) * 0.5f;
        p.setColor(light ? Color.argb(Math.round(235 * alpha), 226, 232, 240) : Color.argb(Math.round(240 * alpha), 30, 41, 59));
        c.drawRect(midL, top + 2f, midL + midW, top + barH - 2f, p);

        float scoreW = barW * 0.115f;
        float hScoreL = midL - scoreW;
        float aScoreL = midL + midW;
        p.setColor((hCol & 0x00FFFFFF) | (Math.round(220 * alpha) << 24));
        c.drawRect(hScoreL, top + 2f, midL, top + barH - 2f, p);
        p.setColor((aCol & 0x00FFFFFF) | (Math.round(220 * alpha) << 24));
        c.drawRect(aScoreL, top + 2f, aScoreL + scoreW, top + barH - 2f, p);

        float logoSz = barH * 0.72f;
        float hAreaL = left + stripW * 1.6f;
        float aAreaR = left + barW - stripW * 1.6f;
        if (homeLogo != null) {
            drawLogoFit(c, homeLogo, hAreaL + logoSz * 0.62f, top + barH * 0.5f, logoSz, a255);
            hAreaL += logoSz * 1.20f;
        }
        if (awayLogo != null) {
            drawLogoFit(c, awayLogo, aAreaR - logoSz * 0.62f, top + barH * 0.5f, logoSz, a255);
            aAreaR -= logoSz * 1.20f;
        }

        txt.setColor(light ? Color.argb(a255, 15, 23, 42) : Color.argb(a255, 255, 255, 255));
        txt.setTextSize(barH * 0.32f);
        float textMidY = top + barH * 0.5f - (txt.descent() + txt.ascent()) * 0.5f;
        float hNameCenter = hAreaL + (hScoreL - hAreaL) * 0.5f;
        float aNameCenter = (aScoreL + scoreW) + (aAreaR - (aScoreL + scoreW)) * 0.5f;
        c.drawText(home.toUpperCase(Locale.ROOT), hNameCenter, textMidY, txt);
        c.drawText(away.toUpperCase(Locale.ROOT), aNameCenter, textMidY, txt);

        txt.setColor(Color.argb(a255, 255, 255, 255));
        txt.setTextSize(barH * 0.52f);
        float scoreY = top + barH * 0.5f - (txt.descent() + txt.ascent()) * 0.5f;
        c.drawText(String.valueOf(hScore), hScoreL + scoreW * 0.5f, scoreY, txt);
        c.drawText(String.valueOf(aScore), aScoreL + scoreW * 0.5f, scoreY, txt);

        if (showClock) {
            txt.setTextSize(barH * 0.36f);
            txt.setColor(light ? Color.argb(a255, 15, 23, 42) : Color.argb(a255, 248, 250, 252));
            c.drawText(clockStr, midL + midW * 0.5f, top + barH * 0.48f, txt);
            txt.setTextSize(barH * 0.21f);
            txt.setColor(light ? Color.argb(a255, 71, 85, 105) : Color.argb(a255, 148, 163, 184));
            c.drawText(period + (league.isEmpty() ? "" : "  |  " + league), midL + midW * 0.5f, top + barH * 0.80f, txt);
        } else {
            txt.setTextSize(barH * 0.34f);
            txt.setColor(light ? Color.argb(a255, 15, 23, 42) : Color.argb(a255, 255, 255, 255));
            c.drawText(period, midL + midW * 0.5f, textMidY, txt);
        }
    }

    private static void drawLowerThird(Canvas c, int w, int h, float alpha, JSONObject localContent) {
        String badge, title, subtitle;
        int accent, style;
        synchronized (sStateLock) {
            badge = sState.ltBadge; title = sState.ltTitle; subtitle = sState.ltSubtitle;
            accent = parseColorSafe(sState.ltAccent, Color.parseColor("#2563EB"));
            style = sState.ltStyle;
        }
        if (localContent != null) {
            badge = localContent.optString("badge");
            title = localContent.optString("title");
            subtitle = localContent.optString("subtitle");
            accent = parseColorSafe(localContent.optString("accentColor"), Color.parseColor("#2563EB"));
            style = localContent.optInt("style");
        }
        float slideX = -(1f - alpha) * w * 0.08f;
        int a255 = Math.round(alpha * 255);
        c.save();
        if (style != 4) c.translate(slideX, 0f);
        else c.translate(0f, (1f - alpha) * h * 0.20f);

        float margin = h * 0.12f;
        float boxH = h - margin * 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        if (style == 5) {
            // 5: Noticiero CNN / Ultimo Momento (bold red badge + crisp white headline + dark ticker bar)
            float left = w * 0.02f;
            float bw = w * 0.96f;
            float topH = boxH * 0.58f;
            float botH = boxH * 0.42f;
            float badgeW = bw * 0.24f;
            // Subtle drop shadow bar
            p.setColor(Color.argb(Math.round(120 * alpha), 0, 0, 0));
            c.drawRect(left + 4f, margin + 6f, left + bw + 4f, margin + boxH + 6f, p);
            // Red breaking badge block
            p.setShader(new LinearGradient(left, margin, left + badgeW, margin + topH,
                Color.argb(a255, 220, 38, 38), Color.argb(a255, 153, 27, 27), Shader.TileMode.CLAMP));
            c.drawRect(left, margin, left + badgeW, margin + topH, p);
            p.setShader(null);
            // White headline bar
            p.setColor(Color.argb(Math.round(250 * alpha), 248, 250, 252));
            c.drawRect(left + badgeW, margin, left + bw, margin + topH, p);
            // Red top accent line
            p.setColor(Color.argb(a255, 239, 68, 68));
            c.drawRect(left, margin, left + bw, margin + 5f, p);
            // Dark navy lower ticker bar
            p.setColor(Color.argb(Math.round(242 * alpha), 15, 23, 42));
            c.drawRect(left, margin + topH, left + bw, margin + boxH, p);
            // Red vertical marker on lower ticker
            p.setColor(Color.argb(a255, 220, 38, 38));
            c.drawRect(left, margin + topH, left + 10f, margin + boxH, p);

            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(Color.argb(a255, 255, 255, 255));
            txt.setTextSize(topH * 0.36f);
            String bTxt = (badge == null || badge.trim().isEmpty()) ? "ULTIMO MOMENTO" : badge.toUpperCase(Locale.ROOT);
            c.drawText(bTxt, left + badgeW * 0.5f, margin + topH * 0.64f, txt);
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(Color.argb(a255, 15, 23, 42));
            txt.setTextSize(topH * 0.50f);
            c.drawText(title != null ? title : "", left + badgeW + 22f, margin + topH * 0.68f, txt);
            txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            txt.setColor(Color.argb(a255, 241, 245, 249));
            txt.setTextSize(botH * 0.52f);
            c.drawText(subtitle != null ? subtitle : "", left + 26f, margin + topH + botH * 0.68f, txt);
            c.restore();
            return;
        }

        if (style == 6) {
            // 6: Noticiero 24h Doble Banda (BBC/TN style with live HH:MM clock)
            float left = w * 0.025f;
            float bw = w * 0.95f;
            float topH = boxH * 0.56f;
            float botH = boxH * 0.44f;
            float clockW = bw * 0.12f;
            // Top white headline strip
            p.setColor(Color.argb(Math.round(248 * alpha), 255, 255, 255));
            c.drawRect(left, margin, left + bw, margin + topH, p);
            // Left crimson news pillar
            p.setColor(Color.argb(a255, 185, 28, 28));
            c.drawRect(left, margin, left + 16f, margin + topH, p);
            // Bottom deep royal blue band
            p.setShader(new LinearGradient(left, margin + topH, left + bw, margin + topH,
                Color.argb(Math.round(244 * alpha), 15, 39, 92),
                Color.argb(Math.round(244 * alpha), 30, 58, 138), Shader.TileMode.CLAMP));
            c.drawRect(left, margin + topH, left + bw - clockW, margin + boxH, p);
            p.setShader(null);
            // Live clock box on bottom-right
            p.setColor(Color.argb(a255, 185, 28, 28));
            c.drawRect(left + bw - clockW, margin + topH, left + bw, margin + boxH, p);

            // Badge chip inside top-left
            float titleX = left + 30f;
            if (badge != null && !badge.trim().isEmpty()) {
                txt.setTextAlign(Paint.Align.LEFT);
                txt.setTextSize(topH * 0.34f);
                String bt = badge.toUpperCase(Locale.ROOT);
                float chipW = txt.measureText(bt) + 24f;
                p.setColor(Color.argb(a255, 185, 28, 28));
                c.drawRoundRect(new RectF(titleX, margin + topH * 0.20f, titleX + chipW, margin + topH * 0.80f), 4f, 4f, p);
                txt.setColor(Color.argb(a255, 255, 255, 255));
                c.drawText(bt, titleX + 12f, margin + topH * 0.62f, txt);
                titleX += chipW + 18f;
            }
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(Color.argb(a255, 15, 23, 42));
            txt.setTextSize(topH * 0.52f);
            c.drawText(title != null ? title : "", titleX, margin + topH * 0.69f, txt);

            txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            txt.setColor(Color.argb(a255, 241, 245, 249));
            txt.setTextSize(botH * 0.54f);
            c.drawText(subtitle != null ? subtitle : "", left + 26f, margin + topH + botH * 0.68f, txt);

            String hhmm = new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date());
            txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(Color.argb(a255, 255, 255, 255));
            txt.setTextSize(botH * 0.54f);
            c.drawText(hhmm, left + bw - clockW * 0.5f, margin + topH + botH * 0.69f, txt);
            c.restore();
            return;
        }

        if (style == 7) {
            // 7: Noticiero Editorial / Estudio (glass navy desk plate + metallic rule + angled accent)
            float left = w * 0.03f;
            float bw = w * 0.86f;
            float topBarH = boxH * 0.26f;
            // Badge ribbon above main plate
            String bTxt = (badge == null || badge.trim().isEmpty()) ? "EDICION CENTRAL" : badge.toUpperCase(Locale.ROOT);
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setTextSize(topBarH * 0.72f);
            float ribW = Math.max(bw * 0.24f, txt.measureText(bTxt) + 46f);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRect(left, margin, left + ribW, margin + topBarH, p);
            txt.setColor(Color.argb(a255, 255, 255, 255));
            c.drawText(bTxt, left + 18f, margin + topBarH * 0.76f, txt);
            // Main studio glass plate
            p.setShader(new LinearGradient(left, margin + topBarH, left + bw, margin + boxH,
                Color.argb(Math.round(238 * alpha), 10, 18, 36),
                Color.argb(Math.round(215 * alpha), 24, 38, 66), Shader.TileMode.CLAMP));
            c.drawRect(left, margin + topBarH, left + bw, margin + boxH, p);
            p.setShader(null);
            // Metallic cyan/silver rule
            p.setColor(Color.argb(Math.round(210 * alpha), 148, 163, 184));
            c.drawRect(left + ribW, margin + topBarH - 3f, left + bw, margin + topBarH, p);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRect(left, margin + boxH - 5f, left + bw * 0.45f, margin + boxH, p);

            float bodyH = boxH - topBarH;
            txt.setColor(Color.argb(a255, 255, 255, 255));
            txt.setTextSize(bodyH * 0.40f);
            c.drawText(title != null ? title : "", left + 24f, margin + topBarH + bodyH * 0.48f, txt);
            txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            txt.setColor(Color.argb(a255, 203, 213, 225));
            txt.setTextSize(bodyH * 0.28f);
            c.drawText(subtitle != null ? subtitle : "", left + 24f, margin + topBarH + bodyH * 0.82f, txt);
            c.restore();
            return;
        }

        if (style == 8) {
            // 8: Noticiero Alerta Roja (Breaking urgent crimson + amber highlight strip)
            float left = w * 0.02f;
            float bw = w * 0.96f;
            float hdrH = boxH * 0.28f;
            float midH = boxH * 0.44f;
            float botH = boxH * 0.28f;
            // Header amber/yellow urgent bar
            p.setColor(Color.argb(a255, 250, 204, 21));
            c.drawRect(left, margin, left + bw * 0.34f, margin + hdrH, p);
            p.setColor(Color.argb(Math.round(230 * alpha), 127, 29, 29));
            c.drawRect(left + bw * 0.34f, margin, left + bw, margin + hdrH, p);
            // Mid deep crimson title band
            p.setShader(new LinearGradient(left, margin + hdrH, left + bw, margin + hdrH + midH,
                Color.argb(Math.round(246 * alpha), 185, 28, 28),
                Color.argb(Math.round(240 * alpha), 127, 29, 29), Shader.TileMode.CLAMP));
            c.drawRect(left, margin + hdrH, left + bw, margin + hdrH + midH, p);
            p.setShader(null);
            // Bottom crisp black ticker
            p.setColor(Color.argb(Math.round(242 * alpha), 9, 9, 11));
            c.drawRect(left, margin + hdrH + midH, left + bw, margin + boxH, p);

            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(Color.argb(a255, 15, 23, 42));
            txt.setTextSize(hdrH * 0.68f);
            String bTxt = (badge == null || badge.trim().isEmpty()) ? "ALERTA INFORMATIVA" : badge.toUpperCase(Locale.ROOT);
            c.drawText(bTxt, left + 18f, margin + hdrH * 0.76f, txt);
            txt.setColor(Color.argb(a255, 255, 255, 255));
            txt.setTextSize(midH * 0.62f);
            c.drawText(title != null ? title : "", left + 20f, margin + hdrH + midH * 0.72f, txt);
            txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            txt.setColor(Color.argb(a255, 254, 240, 138));
            txt.setTextSize(botH * 0.64f);
            c.drawText(subtitle != null ? subtitle : "", left + 20f, margin + hdrH + midH + botH * 0.72f, txt);
            c.restore();
            return;
        }

        if (style == 3) {
            // 3: Noticiero / Breaking (high-contrast two-tier news ticker)
            float left = w * 0.03f;
            float bw = w * 0.90f;
            float topH = boxH * 0.56f;
            float botH = boxH * 0.44f;
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRect(left, margin, left + bw * 0.22f, margin + topH, p);
            p.setColor(Color.argb(Math.round(244 * alpha), 248, 250, 252));
            c.drawRect(left + bw * 0.22f, margin, left + bw, margin + topH, p);
            p.setColor(Color.argb(Math.round(238 * alpha), 15, 23, 42));
            c.drawRect(left, margin + topH, left + bw, margin + boxH, p);

            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(Color.argb(a255, 255, 255, 255));
            txt.setTextSize(topH * 0.42f);
            c.drawText((badge == null || badge.isEmpty() ? "NOTICIAS" : badge).toUpperCase(Locale.ROOT),
                left + bw * 0.11f, margin + topH * 0.66f, txt);
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(Color.argb(a255, 15, 23, 42));
            txt.setTextSize(topH * 0.52f);
            c.drawText(title != null ? title : "", left + bw * 0.24f, margin + topH * 0.68f, txt);
            txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            txt.setColor(Color.argb(a255, 226, 232, 240));
            txt.setTextSize(botH * 0.52f);
            c.drawText(subtitle != null ? subtitle : "", left + 22f, margin + topH + botH * 0.68f, txt);
            c.restore();
            return;
        }

        if (style == 4) {
            // 4: Centrado / Conferencia (centered translucent plate with top accent bar)
            float cw = w * 0.74f;
            float left = (w - cw) * 0.5f;
            p.setColor(Color.argb(Math.round(224 * alpha), 15, 23, 42));
            c.drawRoundRect(new RectF(left, margin, left + cw, margin + boxH), 14f, 14f, p);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRoundRect(new RectF(left + cw * 0.25f, margin, left + cw * 0.75f, margin + 8f), 4f, 4f, p);
            txt.setTextAlign(Paint.Align.CENTER);
            if (badge != null && !badge.trim().isEmpty()) {
                txt.setTextSize(boxH * 0.16f);
                txt.setColor((accent & 0x00FFFFFF) | (a255 << 24));
                c.drawText(badge.toUpperCase(Locale.ROOT), w * 0.5f, margin + boxH * 0.28f, txt);
            }
            txt.setTextSize(boxH * 0.30f);
            txt.setColor(Color.argb(a255, 255, 255, 255));
            c.drawText(title != null ? title : "", w * 0.5f, margin + boxH * 0.62f, txt);
            txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            txt.setTextSize(boxH * 0.19f);
            txt.setColor(Color.argb(a255, 203, 213, 225));
            c.drawText(subtitle != null ? subtitle : "", w * 0.5f, margin + boxH * 0.86f, txt);
            c.restore();
            return;
        }

        float boxW = w * 0.78f;
        float left = w * 0.04f;
        float top = margin;

        if (style == 2) {
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRoundRect(new RectF(left, top + boxH * 0.12f, left + Math.max(8f, w * 0.008f), top + boxH * 0.88f), 4f, 4f, p);
        } else {
            float rad = style == 1 ? boxH * 0.16f : 10f;
            p.setShader(new LinearGradient(left, top, left + boxW, top,
                Color.argb(Math.round(235 * alpha), 15, 23, 42),
                Color.argb(Math.round(185 * alpha), 30, 41, 59),
                Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(left, top, left + boxW, top + boxH), rad, rad, p);
            p.setShader(null);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRoundRect(new RectF(left, top, left + Math.max(12f, w * 0.012f), top + boxH), rad, rad, p);
        }

        float textX = left + Math.max(28f, w * 0.032f);
        float curY = top + boxH * 0.24f;
        if (badge != null && !badge.trim().isEmpty()) {
            txt.setTextSize(boxH * 0.15f);
            float bw = txt.measureText(badge.toUpperCase(Locale.ROOT)) + boxH * 0.24f;
            float bh = boxH * 0.22f;
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRoundRect(new RectF(textX, top + boxH * 0.10f, textX + bw, top + boxH * 0.10f + bh), 6f, 6f, p);
            txt.setColor(Color.argb(a255, 255, 255, 255));
            c.drawText(badge.toUpperCase(Locale.ROOT), textX + boxH * 0.12f, top + boxH * 0.26f, txt);
            curY = top + boxH * 0.60f;
        } else {
            curY = top + boxH * 0.48f;
        }

        txt.setTextSize(boxH * 0.28f);
        txt.setColor(Color.argb(a255, 255, 255, 255));
        if (style == 2) txt.setShadowLayer(6f, 0f, 2f, Color.argb(a255, 0, 0, 0));
        c.drawText(title != null ? title : "", textX, curY, txt);

        if (subtitle != null && !subtitle.trim().isEmpty()) {
            txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            txt.setTextSize(boxH * 0.19f);
            txt.setColor(Color.argb(a255, 203, 213, 225));
            c.drawText(subtitle, textX, curY + boxH * 0.26f, txt);
        }
        c.restore();
    }

    private static void drawTimer(Canvas c, int w, int h, float alpha) {
        String label, doneText, mode;
        int accent, durSec, style;
        long curMs;
        synchronized (sStateLock) {
            label = sState.tmLabel; doneText = sState.tmDone; mode = sState.tmMode;
            accent = parseColorSafe(sState.tmAccent, Color.parseColor("#2563EB"));
            durSec = Math.max(1, sState.tmDurationSec);
            style = sState.tmStyle;
            curMs = getTimerCurrentMsLocked();
        }
        int a255 = Math.round(alpha * 255);
        float prog = "countdown".equals(mode) ? Math.max(0f, Math.min(1f, curMs / (durSec * 1000f))) : 1f;
        boolean finished = "countdown".equals(mode) && curMs <= 0L;
        String display = finished && doneText != null && !doneText.isEmpty() ? doneText : formatClock(curMs);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setTextAlign(Paint.Align.CENTER);

        if (style == 2) {
            // 2: Anillo Circular de Progreso
            float r = Math.min(w, h) * 0.42f;
            float cx = w * 0.5f, cy = h * 0.5f;
            p.setColor(Color.argb(Math.round(215 * alpha), 15, 23, 42));
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(8f, r * 0.11f));
            p.setColor(Color.argb(Math.round(70 * alpha), 148, 163, 184));
            c.drawCircle(cx, cy, r * 0.88f, p);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(new RectF(cx - r * 0.88f, cy - r * 0.88f, cx + r * 0.88f, cy + r * 0.88f), -90f, 360f * prog, false, p);
            txt.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            txt.setTextSize(r * 0.46f);
            txt.setColor(Color.argb(a255, 255, 255, 255));
            c.drawText(display, cx, cy - (txt.descent() + txt.ascent()) * 0.35f, txt);
            if (label != null && !label.trim().isEmpty()) {
                txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                txt.setTextSize(r * 0.18f);
                txt.setColor(Color.argb(a255, 148, 163, 184));
                c.drawText(label.toUpperCase(Locale.ROOT), cx, cy + r * 0.42f, txt);
            }
            return;
        }

        float pad = Math.min(w, h) * 0.08f;
        if (style == 4) {
            // 4: Minimalista transparente (sin caja)
            txt.setShadowLayer(8f, 0f, 3f, Color.argb(a255, 0, 0, 0));
            if (label != null && !label.trim().isEmpty()) {
                txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                txt.setTextSize(h * 0.15f);
                txt.setColor(Color.argb(a255, 226, 232, 240));
                c.drawText(label.toUpperCase(Locale.ROOT), w * 0.5f, h * 0.25f, txt);
            }
            txt.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            txt.setTextSize(h * 0.50f);
            txt.setColor(Color.argb(a255, 255, 255, 255));
            c.drawText(display, w * 0.5f, h * 0.68f, txt);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRoundRect(new RectF(w * 0.15f, h * 0.84f, w * 0.15f + w * 0.70f * prog, h * 0.88f), 4f, 4f, p);
            return;
        }

        float rad = (style == 1) ? h * 0.44f : (style == 3 ? 6f : Math.min(w, h) * 0.12f);
        p.setColor(style == 3 ? Color.argb(Math.round(242 * alpha), 2, 6, 23) : Color.argb(Math.round(228 * alpha), 15, 23, 42));
        c.drawRoundRect(new RectF(pad, pad, w - pad, h - pad), rad, rad, p);

        if (style == 3) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4f);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRoundRect(new RectF(pad, pad, w - pad, h - pad), rad, rad, p);
            p.setStyle(Paint.Style.FILL);
        } else {
            float barH = Math.max(6f, h * 0.045f);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRoundRect(new RectF(pad + rad * 0.3f, h - pad - barH, pad + rad * 0.3f + (w - pad * 2f - rad * 0.6f) * prog, h - pad), 4f, 4f, p);
        }

        if (label != null && !label.trim().isEmpty()) {
            txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            txt.setTextSize(h * 0.14f);
            txt.setColor(Color.argb(a255, 148, 163, 184));
            c.drawText(label.toUpperCase(Locale.ROOT), w * 0.5f, pad + h * 0.22f, txt);
        }

        txt.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        txt.setTextSize(h * 0.44f);
        txt.setColor(style == 3 ? Color.argb(a255, 56, 189, 248) : Color.argb(a255, 255, 255, 255));
        float cy = h * 0.60f - (txt.descent() + txt.ascent()) * 0.5f;
        c.drawText(display, w * 0.5f, cy, txt);
    }

    private static void drawBible(Canvas c, int w, int h, float alpha) {
        String ref, verseText;
        int accent, style, fontSize;
        synchronized (sStateLock) {
            accent = parseColorSafe(sState.bbAccent, Color.parseColor("#D97706"));
            style = sState.bbStyle;
            fontSize = sState.bbFontSize;
            if (sState.bbUseCustom && !sState.bbCustomText.trim().isEmpty()) {
                ref = sState.bbCustomRef.trim();
                verseText = sState.bbCustomText.trim();
            } else {
                ref = BibleDatabase.formatRef(sState.bbBook, sState.bbChapter, sState.bbVerse, sState.bbVerseEnd);
                verseText = sAppCtx != null
                    ? BibleDatabase.getRangeText(sAppCtx, sState.bbBook, sState.bbChapter, sState.bbVerse, sState.bbVerseEnd)
                    : "";
            }
        }
        int a255 = Math.round(alpha * 255);
        boolean centered = (style == 1 || style == 2 || style == 4);
        boolean churchGold = (style == 2);
        boolean minimal = (style == 3);
        boolean preachFull = (style == 4);

        float padX = preachFull ? w * 0.04f : (centered ? w * 0.07f : w * 0.03f);
        float padY = h * 0.07f;
        float rad = minimal ? 8f : 16f;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (churchGold) {
            p.setShader(new LinearGradient(padX, padY, padX, h - padY,
                Color.argb(Math.round(240 * alpha), 28, 22, 16),
                Color.argb(Math.round(235 * alpha), 48, 36, 22), Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(padX, padY, w - padX, h - padY), rad, rad, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f);
            p.setColor(Color.argb(a255, 217, 173, 92));
            c.drawRoundRect(new RectF(padX + 6f, padY + 6f, w - padX - 6f, h - padY - 6f), rad - 4f, rad - 4f, p);
            p.setStyle(Paint.Style.FILL);
        } else if (minimal) {
            p.setColor(Color.argb(Math.round(205 * alpha), 15, 23, 42));
            c.drawRoundRect(new RectF(padX, padY, w - padX, h - padY), rad, rad, p);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRoundRect(new RectF(padX, padY, padX + 12f, h - padY), 4f, 4f, p);
        } else {
            p.setShader(new LinearGradient(padX, padY, padX, h - padY,
                Color.argb(Math.round(preachFull ? 245 * alpha : 235 * alpha), 15, 23, 42),
                Color.argb(Math.round(preachFull ? 242 * alpha : 220 * alpha), 30, 41, 59),
                Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(padX, padY, w - padX, h - padY), rad, rad, p);
            p.setShader(null);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRoundRect(new RectF(padX, padY, w - padX, padY + Math.max(6f, h * 0.025f)), 4f, 4f, p);
        }

        // Reference pill
        Paint refP = new Paint(Paint.ANTI_ALIAS_FLAG);
        refP.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        refP.setTextSize(Math.max(20f, fontSize * 0.72f));
        String refLabel = ref.isEmpty() ? "BIBLIA" : ref.toUpperCase(Locale.ROOT);
        float pillW = refP.measureText(refLabel) + 32f;
        float pillH = refP.getTextSize() * 1.55f;
        float innerX = padX + 32f;
        float pillX = centered ? (w - pillW) * 0.5f : innerX;
        float pillTop = padY + 22f;
        p.setColor(churchGold ? Color.argb(a255, 180, 130, 48) : ((accent & 0x00FFFFFF) | (a255 << 24)));
        c.drawRoundRect(new RectF(pillX, pillTop, pillX + pillW, pillTop + pillH), 8f, 8f, p);
        refP.setColor(Color.argb(a255, 255, 255, 255));
        float refY = pillTop + pillH * 0.5f - (refP.descent() + refP.ascent()) * 0.5f;
        c.drawText(refLabel, pillX + 16f, refY, refP);

        // Multi-line wrapped verse body
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(churchGold ? Color.argb(a255, 254, 243, 199) : Color.argb(a255, 248, 250, 252));
        tp.setTypeface(Typeface.create(preachFull ? Typeface.SANS_SERIF : Typeface.SERIF,
            preachFull ? Typeface.BOLD : Typeface.NORMAL));
        int textW = Math.max(120, Math.round((w - padX * 2f) - 64f));
        float availH = Math.max(60f, (h - padY) - (pillTop + pillH + 26f));

        float sz = Math.max(18f, preachFull ? fontSize * 1.25f : fontSize);
        StaticLayout layout = null;
        while (sz >= 16f) {
            tp.setTextSize(sz);
            layout = StaticLayout.Builder.obtain(verseText, 0, verseText.length(), tp, textW)
                .setAlignment(centered ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4f, 1.16f)
                .build();
            if (layout.getHeight() <= availH) break;
            sz -= 2f;
        }
        if (layout != null) {
            c.save();
            float textY = pillTop + pillH + 16f + Math.max(0f, (availH - layout.getHeight()) * 0.35f);
            c.translate(innerX, textY);
            layout.draw(c);
            c.restore();
        }
    }

    public static JSONObject buildStateJsonObject(Context ctx) {
        if (ctx != null) init(ctx);
        ensureStateLoaded();
        JSONObject root = new JSONObject();
        try {
            synchronized (sStateLock) {
                JSONObject sb = new JSONObject();
                sb.put("visible", sState.sbVisible);
                sb.put("style", sState.sbStyle);
                sb.put("homeName", sState.sbHomeName);
                sb.put("awayName", sState.sbAwayName);
                sb.put("league", sState.sbLeague);
                sb.put("homeColor", sState.sbHomeColor);
                sb.put("awayColor", sState.sbAwayColor);
                sb.put("homeLogo", sState.sbHomeLogo);
                sb.put("awayLogo", sState.sbAwayLogo);
                sb.put("homeScore", sState.sbHomeScore);
                sb.put("awayScore", sState.sbAwayScore);
                sb.put("period", sState.sbPeriod);
                sb.put("showClock", sState.sbShowClock);
                sb.put("clockRunning", sState.sbClockRunning);
                sb.put("clockMs", getScoreboardClockMsLocked());
                sb.put("clockText", formatClock(getScoreboardClockMsLocked()));
                root.put("scoreboard", sb);

                JSONObject lt = new JSONObject();
                lt.put("visible", sState.ltVisible);
                lt.put("badge", sState.ltBadge);
                lt.put("title", sState.ltTitle);
                lt.put("subtitle", sState.ltSubtitle);
                lt.put("accentColor", sState.ltAccent);
                lt.put("style", sState.ltStyle);
                JSONArray pr = new JSONArray();
                for (JSONObject p : sState.ltPresets) pr.put(p);
                lt.put("presets", pr);
                root.put("lowerthird", lt);

                JSONObject tm = new JSONObject();
                tm.put("visible", sState.tmVisible);
                tm.put("style", sState.tmStyle);
                tm.put("mode", sState.tmMode);
                tm.put("label", sState.tmLabel);
                tm.put("doneText", sState.tmDone);
                tm.put("accentColor", sState.tmAccent);
                tm.put("durationSec", sState.tmDurationSec);
                tm.put("running", sState.tmRunning);
                tm.put("elapsedMs", sState.tmElapsedBaseMs);
                tm.put("currentText", formatClock(getTimerCurrentMsLocked()));
                root.put("timer", tm);

                JSONObject bb = new JSONObject();
                bb.put("visible", sState.bbVisible);
                bb.put("book", sState.bbBook);
                bb.put("chapter", sState.bbChapter);
                bb.put("verse", sState.bbVerse);
                bb.put("verseEnd", sState.bbVerseEnd);
                bb.put("useCustom", sState.bbUseCustom);
                bb.put("customRef", sState.bbCustomRef);
                bb.put("customText", sState.bbCustomText);
                bb.put("accentColor", sState.bbAccent);
                bb.put("style", sState.bbStyle);
                bb.put("fontSize", sState.bbFontSize);
                bb.put("ref", sState.bbUseCustom && !sState.bbCustomRef.isEmpty()
                    ? sState.bbCustomRef : BibleDatabase.formatRef(sState.bbBook, sState.bbChapter, sState.bbVerse, sState.bbVerseEnd));
                bb.put("text", sState.bbUseCustom && !sState.bbCustomText.isEmpty()
                    ? sState.bbCustomText
                    : (sAppCtx != null ? BibleDatabase.getRangeText(sAppCtx, sState.bbBook, sState.bbChapter, sState.bbVerse, sState.bbVerseEnd) : ""));
                bb.put("chapters", sAppCtx != null ? BibleDatabase.getChapterCount(sAppCtx, sState.bbBook) : 1);
                bb.put("verses", sAppCtx != null ? BibleDatabase.getVerseCount(sAppCtx, sState.bbBook, sState.bbChapter) : 1);
                root.put("bible", bb);
            }
        } catch (Throwable ignored) {}
        return root;
    }

    public static String getBibleBooksJson() {
        JSONArray arr = new JSONArray();
        for (String b : BibleDatabase.BOOK_NAMES) arr.put(b);
        return arr.toString();
    }

    public static String searchBibleJson(Context ctx, String q) {
        JSONArray arr = new JSONArray();
        for (BibleDatabase.VerseEntry e : BibleDatabase.search(ctx, q, 25)) {
            try {
                JSONObject o = new JSONObject();
                o.put("book", e.book);
                o.put("chapter", e.chapter);
                o.put("verse", e.verse);
                o.put("ref", e.ref());
                o.put("text", e.text);
                arr.put(o);
            } catch (Throwable ignored) {}
        }
        return arr.toString();
    }

    public static String getStateJsonStatic() {
        return buildStateJsonObject(sAppCtx).toString();
    }

    public static String executeActionJsonStatic(String body) {
        return executeActionJson(sAppCtx, body);
    }

    public static String searchBibleJsonStatic(String query) {
        return searchBibleJson(sAppCtx, query);
    }

    public static String executeActionJson(Context ctx, String body) {
        if (ctx != null) init(ctx);
        ensureStateLoaded();
        try {
            JSONObject req = new JSONObject(body != null ? body : "{}");
            String act = req.optString("action", "");
            synchronized (sStateLock) {
                switch (act) {
                    case "sb_score": {
                        String side = req.optString("side", "home");
                        int delta = req.optInt("delta", 1);
                        if ("home".equals(side)) sState.sbHomeScore = Math.max(0, sState.sbHomeScore + delta);
                        else sState.sbAwayScore = Math.max(0, sState.sbAwayScore + delta);
                        break;
                    }
                    case "sb_reset":
                        sState.sbHomeScore = 0; sState.sbAwayScore = 0;
                        sState.sbClockRunning = false; sState.sbClockBaseMs = 0L;
                        break;
                    case "sb_update":
                        if (req.has("homeName")) sState.sbHomeName = req.optString("homeName", sState.sbHomeName);
                        if (req.has("awayName")) sState.sbAwayName = req.optString("awayName", sState.sbAwayName);
                        if (req.has("league")) sState.sbLeague = req.optString("league", sState.sbLeague);
                        if (req.has("period")) sState.sbPeriod = req.optString("period", sState.sbPeriod);
                        if (req.has("homeColor")) sState.sbHomeColor = req.optString("homeColor", sState.sbHomeColor);
                        if (req.has("awayColor")) sState.sbAwayColor = req.optString("awayColor", sState.sbAwayColor);
                        if (req.has("homeLogo")) sState.sbHomeLogo = req.optString("homeLogo", "");
                        if (req.has("awayLogo")) sState.sbAwayLogo = req.optString("awayLogo", "");
                        if (req.has("style")) sState.sbStyle = req.optInt("style", sState.sbStyle);
                        if (req.has("visible")) sState.sbVisible = req.optBoolean("visible", sState.sbVisible);
                        break;
                    case "sb_clock": {
                        String op = req.optString("op", "toggle");
                        if ("reset".equals(op)) {
                            sState.sbClockRunning = false; sState.sbClockBaseMs = 0L;
                        } else if ("toggle".equals(op)) {
                            if (sState.sbClockRunning) {
                                sState.sbClockBaseMs = getScoreboardClockMsLocked();
                                sState.sbClockRunning = false;
                            } else {
                                sState.sbClockStartMs = System.currentTimeMillis();
                                sState.sbClockRunning = true;
                            }
                        }
                        break;
                    }
                    case "lt_update":
                        if (req.has("badge")) sState.ltBadge = req.optString("badge", sState.ltBadge);
                        if (req.has("title")) sState.ltTitle = req.optString("title", sState.ltTitle);
                        if (req.has("subtitle")) sState.ltSubtitle = req.optString("subtitle", sState.ltSubtitle);
                        if (req.has("accentColor")) sState.ltAccent = req.optString("accentColor", sState.ltAccent);
                        if (req.has("style")) sState.ltStyle = req.optInt("style", sState.ltStyle);
                        if (req.has("visible")) sState.ltVisible = req.optBoolean("visible", sState.ltVisible);
                        break;
                    case "lt_toggle":
                        sState.ltVisible = !sState.ltVisible;
                        break;
                    case "lt_save_preset": {
                        JSONObject p = new JSONObject();
                        p.put("badge", sState.ltBadge);
                        p.put("title", sState.ltTitle);
                        p.put("subtitle", sState.ltSubtitle);
                        sState.ltPresets.add(p);
                        break;
                    }
                    case "lt_apply_preset": {
                        int idx = req.optInt("index", -1);
                        if (idx >= 0 && idx < sState.ltPresets.size()) {
                            JSONObject p = sState.ltPresets.get(idx);
                            sState.ltBadge = p.optString("badge", "");
                            sState.ltTitle = p.optString("title", "");
                            sState.ltSubtitle = p.optString("subtitle", "");
                            sState.ltVisible = true;
                        }
                        break;
                    }
                    case "lt_delete_preset": {
                        int idx = req.optInt("index", -1);
                        if (idx >= 0 && idx < sState.ltPresets.size()) sState.ltPresets.remove(idx);
                        break;
                    }
                    case "tm_control": {
                        String op = req.optString("op", "toggle");
                        if ("toggle".equals(op)) {
                            if (sState.tmRunning) {
                                sState.tmElapsedBaseMs += Math.max(0L, System.currentTimeMillis() - sState.tmStartMs);
                                sState.tmRunning = false;
                            } else {
                                sState.tmStartMs = System.currentTimeMillis();
                                sState.tmRunning = true;
                            }
                        } else if ("reset".equals(op)) {
                            sState.tmRunning = false; sState.tmElapsedBaseMs = 0L;
                        } else if ("add_sec".equals(op)) {
                            int d = req.optInt("seconds", 60);
                            sState.tmDurationSec = Math.max(5, sState.tmDurationSec + d);
                        }
                        break;
                    }
                    case "tm_update":
                        if (req.has("mode")) { sState.tmMode = req.optString("mode", sState.tmMode); sState.tmRunning = false; sState.tmElapsedBaseMs = 0L; }
                        if (req.has("label")) sState.tmLabel = req.optString("label", sState.tmLabel);
                        if (req.has("doneText")) sState.tmDone = req.optString("doneText", sState.tmDone);
                        if (req.has("style")) sState.tmStyle = req.optInt("style", sState.tmStyle);
                        if (req.has("durationSec")) { sState.tmDurationSec = Math.max(5, req.optInt("durationSec", sState.tmDurationSec)); sState.tmRunning = false; sState.tmElapsedBaseMs = 0L; }
                        if (req.has("visible")) sState.tmVisible = req.optBoolean("visible", sState.tmVisible);
                        break;
                    case "bb_set":
                        sState.bbBook = Math.max(0, Math.min(65, req.optInt("book", sState.bbBook)));
                        sState.bbChapter = Math.max(1, req.optInt("chapter", sState.bbChapter));
                        sState.bbVerse = Math.max(1, req.optInt("verse", sState.bbVerse));
                        sState.bbVerseEnd = Math.max(sState.bbVerse, req.optInt("verseEnd", sState.bbVerse));
                        if (req.has("style")) sState.bbStyle = req.optInt("style", sState.bbStyle);
                        sState.bbUseCustom = false;
                        if (req.has("visible")) sState.bbVisible = req.optBoolean("visible", sState.bbVisible);
                        break;
                    case "bb_style":
                        sState.bbStyle = req.optInt("style", sState.bbStyle);
                        break;
                    case "bb_step": {
                        int delta = req.optInt("delta", 1);
                        int[] next = BibleDatabase.stepVerse(sAppCtx, sState.bbBook, sState.bbChapter, sState.bbVerse, delta);
                        sState.bbBook = next[0]; sState.bbChapter = next[1]; sState.bbVerse = next[2]; sState.bbVerseEnd = next[2];
                        sState.bbUseCustom = false;
                        break;
                    }
                    case "bb_toggle":
                        sState.bbVisible = !sState.bbVisible;
                        break;
                    case "bb_custom":
                        sState.bbCustomRef = req.optString("ref", "");
                        sState.bbCustomText = req.optString("text", "");
                        sState.bbUseCustom = !sState.bbCustomText.trim().isEmpty();
                        if (req.has("visible")) sState.bbVisible = req.optBoolean("visible", sState.bbVisible);
                        break;
                    default:
                        return "{\"ok\":false}";
                }
            }
            sRender.post(LiveGraphicsManager::refreshFrames);
            saveState();
            JSONObject resp = new JSONObject();
            resp.put("ok", true);
            resp.put("graphics", buildStateJsonObject(sAppCtx));
            return resp.toString();
        } catch (Throwable t) {
            return "{\"ok\":false}";
        }
    }

    // Native touch Control Panel inside OBS (Tools -> Control de Graficos en Vivo)
    public static void openControlPanel(String initialTab) {
        QtObsActivity act = QtObsActivity.getActiveInstance();
        if (act == null || act.isFinishing()) return;
        init(act);
        act.runOnUiThread(() -> {
            if (!act.isFinishing() && !act.isDestroyed()) {
                showControlDialog(act, initialTab != null ? initialTab : "scoreboard");
            }
        });
    }

    private static void showControlDialog(Activity act, String initialTab) {
        float d = act.getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * d);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        // Top tab bar
        LinearLayout tabRow = new LinearLayout(act);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] tabKeys = {"scoreboard", "lowerthird", "timer", "bible"};
        String[] tabLabels = {"Marcador", "Lower Third", "Cronometro", "Biblia"};
        Button[] tabBtns = new Button[4];

        ScrollView scroll = new ScrollView(act);
        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);

        final String[] activeTab = {initialTab};
        Runnable[] rebuildRef = new Runnable[1];

        for (int i = 0; i < 4; ++i) {
            final int idx = i;
            Button b = new Button(act);
            b.setText(tabLabels[i]);
            b.setAllCaps(false);
            b.setTextSize(12f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            b.setOnClickListener(v -> {
                activeTab[0] = tabKeys[idx];
                rebuildRef[0].run();
            });
            tabBtns[i] = b;
            tabRow.addView(b, lp);
        }
        root.addView(tabRow);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(320 * d));
        scLp.topMargin = Math.round(8 * d);
        root.addView(scroll, scLp);

        rebuildRef[0] = () -> {
            content.removeAllViews();
            for (int i = 0; i < 4; ++i) {
                tabBtns[i].setAlpha(tabKeys[i].equals(activeTab[0]) ? 1.0f : 0.55f);
            }
            if ("scoreboard".equals(activeTab[0])) buildScoreboardUi(act, content, rebuildRef[0]);
            else if ("lowerthird".equals(activeTab[0])) buildLowerThirdUi(act, content, rebuildRef[0]);
            else if ("timer".equals(activeTab[0])) buildTimerUi(act, content, rebuildRef[0]);
            else buildBibleUi(act, content, rebuildRef[0]);
        };
        rebuildRef[0].run();

        AlertDialog dlg = new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Control de Graficos en Vivo")
            .setView(root)
            .setPositiveButton("Cerrar", null)
            .create();
        dlg.setOnDismissListener(x -> {
            if (act instanceof QtObsActivity) ((QtObsActivity) act).reapplyImmersiveMode();
        });
        dlg.show();
    }

    private static Button makeBtn(Activity act, String label, View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private static void buildScoreboardUi(Activity act, LinearLayout p, Runnable refresh) {
        TextView scoreTv = new TextView(act);
        scoreTv.setTextSize(18f);
        scoreTv.setTextColor(Color.WHITE);
        scoreTv.setGravity(Gravity.CENTER);
        synchronized (sStateLock) {
            scoreTv.setText(sState.sbHomeName + "  " + sState.sbHomeScore + "  -  " + sState.sbAwayScore + "  " + sState.sbAwayName
                + "   [" + sState.sbPeriod + " · " + formatClock(getScoreboardClockMsLocked()) + "]");
        }
        p.addView(scoreTv);

        LinearLayout row1 = new LinearLayout(act);
        row1.addView(makeBtn(act, "Local +1", v -> { executeActionJson(act, "{\"action\":\"sb_score\",\"side\":\"home\",\"delta\":1}"); refresh.run(); }));
        row1.addView(makeBtn(act, "Local -1", v -> { executeActionJson(act, "{\"action\":\"sb_score\",\"side\":\"home\",\"delta\":-1}"); refresh.run(); }));
        row1.addView(makeBtn(act, "Visita +1", v -> { executeActionJson(act, "{\"action\":\"sb_score\",\"side\":\"away\",\"delta\":1}"); refresh.run(); }));
        row1.addView(makeBtn(act, "Visita -1", v -> { executeActionJson(act, "{\"action\":\"sb_score\",\"side\":\"away\",\"delta\":-1}"); refresh.run(); }));
        p.addView(row1);

        LinearLayout row2 = new LinearLayout(act);
        row2.addView(makeBtn(act, sState.sbClockRunning ? "Pausar reloj" : "Iniciar reloj", v -> { executeActionJson(act, "{\"action\":\"sb_clock\",\"op\":\"toggle\"}"); refresh.run(); }));
        row2.addView(makeBtn(act, "Reiniciar reloj", v -> { executeActionJson(act, "{\"action\":\"sb_clock\",\"op\":\"reset\"}"); refresh.run(); }));
        row2.addView(makeBtn(act, sState.sbVisible ? "Ocultar" : "Mostrar", v -> { executeActionJson(act, "{\"action\":\"sb_update\",\"visible\":" + (!sState.sbVisible) + "}"); refresh.run(); }));
        row2.addView(makeBtn(act, "Reset 0-0", v -> { executeActionJson(act, "{\"action\":\"sb_reset\"}"); refresh.run(); }));
        p.addView(row2);

        EditText edHome = new EditText(act); edHome.setHint("Nombre Local"); edHome.setText(sState.sbHomeName);
        EditText edAway = new EditText(act); edAway.setHint("Nombre Visitante"); edAway.setText(sState.sbAwayName);
        EditText edPer = new EditText(act); edPer.setHint("Periodo (1T, 2T, Q1...)"); edPer.setText(sState.sbPeriod);
        EditText edLeague = new EditText(act); edLeague.setHint("Torneo / Etiqueta"); edLeague.setText(sState.sbLeague);
        p.addView(edHome); p.addView(edAway); p.addView(edPer); p.addView(edLeague);
        p.addView(makeBtn(act, "Aplicar nombres y periodo", v -> {
            try {
                JSONObject o = new JSONObject();
                o.put("action", "sb_update");
                o.put("homeName", edHome.getText().toString().trim());
                o.put("awayName", edAway.getText().toString().trim());
                o.put("period", edPer.getText().toString().trim());
                o.put("league", edLeague.getText().toString().trim());
                executeActionJson(act, o.toString());
                refresh.run();
            } catch (Throwable ignored) {}
        }));
    }

    private static void buildLowerThirdUi(Activity act, LinearLayout p, Runnable refresh) {
        EditText edBadge = new EditText(act); edBadge.setHint("Etiqueta (ej. EN VIVO, INVITADO)"); edBadge.setText(sState.ltBadge);
        EditText edTitle = new EditText(act); edTitle.setHint("Titulo principal"); edTitle.setText(sState.ltTitle);
        EditText edSub = new EditText(act); edSub.setHint("Subtitulo / Cargo"); edSub.setText(sState.ltSubtitle);
        p.addView(edBadge); p.addView(edTitle); p.addView(edSub);

        LinearLayout row = new LinearLayout(act);
        row.addView(makeBtn(act, "Aplicar texto", v -> {
            try {
                JSONObject o = new JSONObject();
                o.put("action", "lt_update");
                o.put("badge", edBadge.getText().toString().trim());
                o.put("title", edTitle.getText().toString().trim());
                o.put("subtitle", edSub.getText().toString().trim());
                executeActionJson(act, o.toString());
                refresh.run();
            } catch (Throwable ignored) {}
        }));
        row.addView(makeBtn(act, sState.ltVisible ? "Ocultar en pantalla" : "Mostrar en pantalla", v -> {
            executeActionJson(act, "{\"action\":\"lt_toggle\"}");
            refresh.run();
        }));
        row.addView(makeBtn(act, "Guardar preset", v -> {
            try {
                JSONObject o = new JSONObject();
                o.put("action", "lt_update");
                o.put("badge", edBadge.getText().toString().trim());
                o.put("title", edTitle.getText().toString().trim());
                o.put("subtitle", edSub.getText().toString().trim());
                executeActionJson(act, o.toString());
                executeActionJson(act, "{\"action\":\"lt_save_preset\"}");
                refresh.run();
            } catch (Throwable ignored) {}
        }));
        p.addView(row);

        synchronized (sStateLock) {
            if (!sState.ltPresets.isEmpty()) {
                TextView hdr = new TextView(act);
                hdr.setText("Presets guardados (toca para lanzar):");
                hdr.setTextColor(Color.parseColor("#94A3B8"));
                p.addView(hdr);
                for (int i = 0; i < sState.ltPresets.size(); ++i) {
                    final int idx = i;
                    JSONObject pr = sState.ltPresets.get(i);
                    LinearLayout prow = new LinearLayout(act);
                    prow.addView(makeBtn(act, pr.optString("title", "Preset") + " - " + pr.optString("subtitle", ""), v -> {
                        executeActionJson(act, "{\"action\":\"lt_apply_preset\",\"index\":" + idx + "}");
                        refresh.run();
                    }));
                    prow.addView(makeBtn(act, "Borrar", v -> {
                        executeActionJson(act, "{\"action\":\"lt_delete_preset\",\"index\":" + idx + "}");
                        refresh.run();
                    }));
                    p.addView(prow);
                }
            }
        }
    }

    private static void buildTimerUi(Activity act, LinearLayout p, Runnable refresh) {
        TextView tv = new TextView(act);
        tv.setTextSize(22f);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        synchronized (sStateLock) {
            tv.setText(formatClock(getTimerCurrentMsLocked()) + "  (" + ("countdown".equals(sState.tmMode) ? "Cuenta regresiva" : "Cronometro") + ")");
        }
        p.addView(tv);

        LinearLayout row = new LinearLayout(act);
        row.addView(makeBtn(act, sState.tmRunning ? "Pausar" : "Iniciar", v -> { executeActionJson(act, "{\"action\":\"tm_control\",\"op\":\"toggle\"}"); refresh.run(); }));
        row.addView(makeBtn(act, "Reiniciar", v -> { executeActionJson(act, "{\"action\":\"tm_control\",\"op\":\"reset\"}"); refresh.run(); }));
        row.addView(makeBtn(act, "+1 min", v -> { executeActionJson(act, "{\"action\":\"tm_control\",\"op\":\"add_sec\",\"seconds\":60}"); refresh.run(); }));
        row.addView(makeBtn(act, "-1 min", v -> { executeActionJson(act, "{\"action\":\"tm_control\",\"op\":\"add_sec\",\"seconds\":-60}"); refresh.run(); }));
        row.addView(makeBtn(act, sState.tmVisible ? "Ocultar" : "Mostrar", v -> { executeActionJson(act, "{\"action\":\"tm_update\",\"visible\":" + (!sState.tmVisible) + "}"); refresh.run(); }));
        p.addView(row);

        LinearLayout row2 = new LinearLayout(act);
        row2.addView(makeBtn(act, "Modo Cuenta regresiva", v -> { executeActionJson(act, "{\"action\":\"tm_update\",\"mode\":\"countdown\"}"); refresh.run(); }));
        row2.addView(makeBtn(act, "Modo Cronometro", v -> { executeActionJson(act, "{\"action\":\"tm_update\",\"mode\":\"stopwatch\"}"); refresh.run(); }));
        p.addView(row2);

        EditText edLabel = new EditText(act); edLabel.setHint("Etiqueta superior"); edLabel.setText(sState.tmLabel);
        EditText edMin = new EditText(act); edMin.setHint("Minutos iniciales"); edMin.setText(String.valueOf(sState.tmDurationSec / 60));
        p.addView(edLabel); p.addView(edMin);
        p.addView(makeBtn(act, "Aplicar etiqueta y minutos", v -> {
            try {
                int mins = Math.max(1, Integer.parseInt(edMin.getText().toString().trim()));
                JSONObject o = new JSONObject();
                o.put("action", "tm_update");
                o.put("label", edLabel.getText().toString().trim());
                o.put("durationSec", mins * 60);
                executeActionJson(act, o.toString());
                refresh.run();
            } catch (Throwable ignored) {}
        }));
    }

    private static void buildBibleUi(Activity act, LinearLayout p, Runnable refresh) {
        BibleDatabase.ensureLoaded(act);
        TextView preview = new TextView(act);
        preview.setTextColor(Color.WHITE);
        preview.setTextSize(14f);
        synchronized (sStateLock) {
            String ref = BibleDatabase.formatRef(sState.bbBook, sState.bbChapter, sState.bbVerse, sState.bbVerseEnd);
            String txt = BibleDatabase.getRangeText(act, sState.bbBook, sState.bbChapter, sState.bbVerse, sState.bbVerseEnd);
            preview.setText(ref + "\n" + txt);
        }
        p.addView(preview);

        LinearLayout navRow = new LinearLayout(act);
        navRow.addView(makeBtn(act, "Anterior", v -> { executeActionJson(act, "{\"action\":\"bb_step\",\"delta\":-1}"); refresh.run(); }));
        navRow.addView(makeBtn(act, "Siguiente", v -> { executeActionJson(act, "{\"action\":\"bb_step\",\"delta\":1}"); refresh.run(); }));
        navRow.addView(makeBtn(act, sState.bbVisible ? "Ocultar en pantalla" : "Mostrar en pantalla", v -> { executeActionJson(act, "{\"action\":\"bb_toggle\"}"); refresh.run(); }));
        p.addView(navRow);

        Spinner spBook = new Spinner(act);
        ArrayAdapter<String> bookAdapter = new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, BibleDatabase.BOOK_NAMES);
        spBook.setAdapter(bookAdapter);
        spBook.setSelection(Math.max(0, Math.min(65, sState.bbBook)));
        p.addView(spBook);

        LinearLayout cvRow = new LinearLayout(act);
        EditText edCh = new EditText(act); edCh.setHint("Cap"); edCh.setText(String.valueOf(sState.bbChapter));
        EditText edV1 = new EditText(act); edV1.setHint("Vers"); edV1.setText(String.valueOf(sState.bbVerse));
        EditText edV2 = new EditText(act); edV2.setHint("Hasta"); edV2.setText(String.valueOf(sState.bbVerseEnd));
        cvRow.addView(edCh, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cvRow.addView(edV1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cvRow.addView(edV2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cvRow.addView(makeBtn(act, "Mostrar cita", v -> {
            try {
                int bk = spBook.getSelectedItemPosition();
                int ch = Math.max(1, Integer.parseInt(edCh.getText().toString().trim()));
                int v1 = Math.max(1, Integer.parseInt(edV1.getText().toString().trim()));
                int v2 = Math.max(v1, Integer.parseInt(edV2.getText().toString().trim()));
                executeActionJson(act, "{\"action\":\"bb_set\",\"book\":" + bk + ",\"chapter\":" + ch + ",\"verse\":" + v1 + ",\"verseEnd\":" + v2 + ",\"visible\":true}");
                refresh.run();
            } catch (Throwable ignored) {}
        }));
        p.addView(cvRow);

        LinearLayout searchRow = new LinearLayout(act);
        EditText edSearch = new EditText(act);
        edSearch.setHint("Buscar texto o cita (ej. Juan 3:16 o amor)");
        LinearLayout resultsBox = new LinearLayout(act);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        searchRow.addView(edSearch, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchRow.addView(makeBtn(act, "Buscar", v -> {
            resultsBox.removeAllViews();
            List<BibleDatabase.VerseEntry> list = BibleDatabase.search(act, edSearch.getText().toString(), 8);
            for (BibleDatabase.VerseEntry e : list) {
                resultsBox.addView(makeBtn(act, e.ref() + " - " + (e.text.length() > 60 ? e.text.substring(0, 60) + "..." : e.text), x -> {
                    executeActionJson(act, "{\"action\":\"bb_set\",\"book\":" + e.book + ",\"chapter\":" + e.chapter + ",\"verse\":" + e.verse + ",\"verseEnd\":" + e.verse + ",\"visible\":true}");
                    refresh.run();
                }));
            }
        }));
        p.addView(searchRow);
        p.addView(resultsBox);
    }

    private LiveGraphicsManager() {}
}
