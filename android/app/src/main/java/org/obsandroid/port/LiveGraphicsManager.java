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
    private static volatile int sThermalTier = 0;

    static void setThermalTier(int tier) {
        sThermalTier = Math.max(0, Math.min(3, tier));
    }

    private static String sanitizeLogoPath(String raw) {
        if (raw == null) return "";
        String p = raw.trim();
        if (p.isEmpty()) return "";
        try {
            File f = new File(p).getCanonicalFile();
            if (!f.isFile() || !f.canRead()) return "";
            String lower = f.getName().toLowerCase(Locale.US);
            if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp"))) {
                return "";
            }
            String canon = f.getPath();
            if (canon.startsWith("/data/")) {
                Context ctx = sAppCtx;
                if (ctx == null) return "";
                String ownFiles = ctx.getFilesDir().getCanonicalPath() + File.separator;
                if (!canon.startsWith(ownFiles)) return "";
            }
            return canon;
        } catch (Throwable ignored) {
            return "";
        }
    }

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

        // Advanced Animated Title Generator
        boolean atOnAir = false;
        int atTemplate = 0; // 0..7
        int atAnimIn = 0;   // 0..6
        int atAnimOut = 0;  // 0..3
        int atInMs = 650;
        int atOutMs = 450;
        int atAutoHideSec = 0;
        boolean atShimmer = true;
        String atBadge = "EN VIVO";
        String atTitle = "TÍTULO PRINCIPAL DEL PROGRAMA";
        String atSubtitle = "Subtítulo o descripción en pantalla";
        String atTicker = "ÚLTIMO MOMENTO  •  TRANSMISIÓN EN DIRECTO  •  SÍGUENOS EN REDES SOCIALES  •  ";
        String atAccent = "#2563EB";
        String atBg = "#0F172A";
        String atText = "#FFFFFF";
        int atAlign = 0;
        int atScale = 100;
        String atLogo = "";
        long atTriggerSeq = 0L;
        final List<JSONObject> atPresets = new ArrayList<>();
    }

    private static final State sState = new State();
    private static String sLoadedHomeLogoPath = "";
    private static String sLoadedAwayLogoPath = "";
    private static String sLoadedAtLogoPath = "";
    private static Bitmap sHomeLogoBmp = null;
    private static Bitmap sAwayLogoBmp = null;
    private static Bitmap sAtLogoBmp = null;

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

    private static Bitmap getCachedAtLogo(String path) {
        String p = path != null ? path.trim() : "";
        if (!p.equals(sLoadedAtLogoPath)) {
            sLoadedAtLogoPath = p;
            if (sAtLogoBmp != null) { sAtLogoBmp.recycle(); sAtLogoBmp = null; }
            if (!p.isEmpty() && new File(p).isFile()) {
                try { sAtLogoBmp = decodeLogo(p); } catch (Throwable ignored) {}
            }
        }
        return sAtLogoBmp;
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
        JSONObject localDtwConfig;
        JSONObject localAtConfig;
        // Advanced Title animation state machine
        float atProgress = 0f;
        boolean atWasOnAir = false;
        long atLastTriggerSeq = -1L;
        long atLastTickMs = 0L;
        long atOnAirSinceMs = 0L;
        float atTickerX = 0f;
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
                JSONObject at = j.optJSONObject("advancedTitle");
                if (at != null) {
                    sState.atOnAir = at.optBoolean("onAir", false);
                    sState.atTemplate = at.optInt("template", 0);
                    sState.atAnimIn = at.optInt("animIn", 0);
                    sState.atAnimOut = at.optInt("animOut", 0);
                    sState.atInMs = Math.max(150, at.optInt("inMs", 650));
                    sState.atOutMs = Math.max(150, at.optInt("outMs", 450));
                    sState.atAutoHideSec = Math.max(0, at.optInt("autoHideSec", 0));
                    sState.atShimmer = at.optBoolean("shimmer", true);
                    sState.atBadge = at.optString("badge", "EN VIVO");
                    sState.atTitle = at.optString("title", "TÍTULO PRINCIPAL DEL PROGRAMA");
                    sState.atSubtitle = at.optString("subtitle", "Subtítulo o descripción en pantalla");
                    sState.atTicker = at.optString("ticker", sState.atTicker);
                    sState.atAccent = at.optString("accentColor", "#2563EB");
                    sState.atBg = at.optString("bgColor", "#0F172A");
                    sState.atText = at.optString("textColor", "#FFFFFF");
                    sState.atAlign = at.optInt("align", 0);
                    sState.atScale = Math.max(60, Math.min(140, at.optInt("scalePct", 100)));
                    sState.atLogo = at.optString("logoPath", "");
                    JSONArray ap = at.optJSONArray("presets");
                    sState.atPresets.clear();
                    if (ap != null) {
                        for (int i = 0; i < ap.length(); ++i) {
                            JSONObject po = ap.optJSONObject(i);
                            if (po != null) sState.atPresets.add(po);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to load graphics state", t);
            }
        }
    }

    public static void saveState() {
        File f = stateFile();
        if (f == null) return;
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try {
            String json = buildStateJsonObject(sAppCtx).toString(2);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(bytes);
                fos.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(bytes);
                }
                tmp.delete();
            }
        } catch (Throwable t) {
            tmp.delete();
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
            JSONObject localDtwConfig = null;
            JSONObject localAtConfig = null;
            try {
                JSONObject obj = new JSONObject(configJson != null ? configJson : "{}");
                if ("lowerthird".equals(kind) && obj.optBoolean("localContent")) localLowerThird = obj;
                if ("datetime_weather".equals(kind)) {
                    localDtwConfig = obj;
                    if (obj.optBoolean("autoWeather", true)) {
                        requestWeatherAsync(obj.optString("city", "Asuncion"));
                    }
                }
                if ("advanced_title".equals(kind) && obj.optBoolean("localMode", false)) {
                    localAtConfig = obj;
                }
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
            inst.localDtwConfig = localDtwConfig;
            inst.localAtConfig = localAtConfig;
            if ("advanced_title".equals(kind) && localAtConfig != null) {
                // Local standalone title plays its entrance animation when updated/shown
                inst.atProgress = 0f;
                inst.atLastTickMs = 0L;
                inst.atOnAirSinceMs = System.currentTimeMillis();
            }
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
            if (sAtLogoBmp != null) sAtLogoBmp.recycle();
            sHomeLogoBmp = null;
            sAwayLogoBmp = null;
            sAtLogoBmp = null;
            sLoadedHomeLogoPath = "";
            sLoadedAwayLogoPath = "";
            sLoadedAtLogoPath = "";
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

            boolean targetVis = true;
            long clockDelay = 0;
            boolean isAdvancedTitle = "advanced_title".equals(inst.kind);
            boolean atWantOnAir = false;
            boolean atLocalMode = false;
            int atAutoHideSec = 0;
            int atTpl = 0;
            boolean atShimmer = false;
            long atNowMs = System.currentTimeMillis();

            synchronized (sStateLock) {
                long now = atNowMs;
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
                    case "datetime_weather": {
                        targetVis = true;
                        JSONObject dtw = inst.localDtwConfig;
                        int cm = dtw != null ? dtw.optInt("contentMode", 0) : 0;
                        boolean hasTime = (cm == 0 || cm == 1 || cm == 3 || cm == 4);
                        boolean showSec = dtw != null && dtw.optBoolean("showSeconds", false);
                        if (hasTime && showSec) {
                            clockDelay = 1000L - now % 1000L;
                        } else {
                            clockDelay = 60000L - now % 60000L;
                        }
                        if (dtw != null && dtw.optBoolean("autoWeather", true) &&
                            (cm == 0 || cm == 1 || cm == 2 || cm == 6)) {
                            requestWeatherAsync(dtw.optString("city", "Asuncion"));
                        }
                        break;
                    }
                    case "advanced_title": {
                        // Snapshot state under lock, then draw + push JNI OUTSIDE sStateLock!
                        atLocalMode = (inst.localAtConfig != null);
                        atWantOnAir = atLocalMode || sState.atOnAir;
                        int inMs = Math.max(150, atLocalMode ? inst.localAtConfig.optInt("inMs", 650) : sState.atInMs);
                        int outMs = Math.max(150, atLocalMode ? inst.localAtConfig.optInt("outMs", 450) : sState.atOutMs);
                        atAutoHideSec = Math.max(0, atLocalMode ? inst.localAtConfig.optInt("autoHideSec", 0) : sState.atAutoHideSec);
                        atTpl = atLocalMode ? inst.localAtConfig.optInt("template", 0) : sState.atTemplate;
                        atShimmer = (atLocalMode ? inst.localAtConfig.optBoolean("shimmer", true) : sState.atShimmer) && sThermalTier < 2;
                        long trigSeq = atLocalMode ? 0L : sState.atTriggerSeq;

                        if (!atLocalMode && trigSeq != inst.atLastTriggerSeq) {
                            inst.atLastTriggerSeq = trigSeq;
                            if (atWantOnAir) {
                                inst.atProgress = 0f;
                                inst.atOnAirSinceMs = now;
                                inst.atLastTickMs = now;
                            }
                        }
                        if (atWantOnAir && !inst.atWasOnAir) {
                            inst.atOnAirSinceMs = now;
                            inst.atLastTickMs = now;
                        }
                        if (atWantOnAir && atAutoHideSec > 0 && inst.atOnAirSinceMs > 0 &&
                            (now - inst.atOnAirSinceMs) >= atAutoHideSec * 1000L) {
                            if (!atLocalMode) sState.atOnAir = false;
                            atWantOnAir = false;
                        }
                        inst.atWasOnAir = atWantOnAir;

                        long dtMs = inst.atLastTickMs > 0 ? Math.min(100L, Math.max(16L, now - inst.atLastTickMs)) : 33L;
                        inst.atLastTickMs = now;
                        if (atWantOnAir) {
                            inst.atProgress = Math.min(1f, inst.atProgress + dtMs / (float) inMs);
                        } else {
                            inst.atProgress = Math.max(0f, inst.atProgress - dtMs / (float) outMs);
                        }
                        if (atTpl == 4 && inst.atProgress > 0.01f) {
                            inst.atTickerX += dtMs * 0.14f;
                        }
                        break;
                    }
                    default:
                        targetVis = true;
                }
            }

            if (isAdvancedTitle) {
                if (inst.atProgress > 0.005f) {
                    drawAdvancedTitle(c, w, h, inst.atProgress, !atWantOnAir,
                        Math.max(0L, atNowMs - inst.atOnAirSinceMs), inst.atTickerX, inst.localAtConfig);
                }
                inst.rgba.clear();
                inst.bitmap.copyPixelsToBuffer(inst.rgba);
                NativeBridge.pushGraphicsFrame(inst.id, inst.rgba, w, h, System.nanoTime());

                boolean animating = (atWantOnAir && inst.atProgress < 0.999f) || (!atWantOnAir && inst.atProgress > 0.001f);
                long frameMs = sThermalTier >= 2 ? 50L : 33L;
                if (animating) return frameMs;
                if (atWantOnAir && (atTpl == 4 || atShimmer)) return frameMs;
                if (atWantOnAir && atAutoHideSec > 0) {
                    long rem = atAutoHideSec * 1000L - (atNowMs - inst.atOnAirSinceMs);
                    return Math.max(50L, Math.min(500L, rem));
                }
                return 0L;
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
                else if ("datetime_weather".equals(inst.kind)) drawDateTimeWeather(c, w, h, inst.animAlpha, inst.localDtwConfig);
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

                JSONObject at = new JSONObject();
                at.put("onAir", sState.atOnAir);
                at.put("template", sState.atTemplate);
                at.put("animIn", sState.atAnimIn);
                at.put("animOut", sState.atAnimOut);
                at.put("inMs", sState.atInMs);
                at.put("outMs", sState.atOutMs);
                at.put("autoHideSec", sState.atAutoHideSec);
                at.put("shimmer", sState.atShimmer);
                at.put("badge", sState.atBadge);
                at.put("title", sState.atTitle);
                at.put("subtitle", sState.atSubtitle);
                at.put("ticker", sState.atTicker);
                at.put("accentColor", sState.atAccent);
                at.put("bgColor", sState.atBg);
                at.put("textColor", sState.atText);
                at.put("align", sState.atAlign);
                at.put("scalePct", sState.atScale);
                at.put("logoPath", sState.atLogo);
                JSONArray ap = new JSONArray();
                for (JSONObject p : sState.atPresets) ap.put(p);
                at.put("presets", ap);
                root.put("advancedTitle", at);
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
                        if (req.has("homeLogo")) sState.sbHomeLogo = sanitizeLogoPath(req.optString("homeLogo", ""));
                        if (req.has("awayLogo")) sState.sbAwayLogo = sanitizeLogoPath(req.optString("awayLogo", ""));
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
                    case "at_update":
                        if (req.has("template")) sState.atTemplate = req.optInt("template", sState.atTemplate);
                        if (req.has("animIn")) sState.atAnimIn = req.optInt("animIn", sState.atAnimIn);
                        if (req.has("animOut")) sState.atAnimOut = req.optInt("animOut", sState.atAnimOut);
                        if (req.has("inMs")) sState.atInMs = Math.max(150, Math.min(2500, req.optInt("inMs", sState.atInMs)));
                        if (req.has("outMs")) sState.atOutMs = Math.max(150, Math.min(2000, req.optInt("outMs", sState.atOutMs)));
                        if (req.has("autoHideSec")) sState.atAutoHideSec = Math.max(0, Math.min(120, req.optInt("autoHideSec", sState.atAutoHideSec)));
                        if (req.has("shimmer")) sState.atShimmer = req.optBoolean("shimmer", sState.atShimmer);
                        if (req.has("badge")) sState.atBadge = req.optString("badge", sState.atBadge);
                        if (req.has("title")) sState.atTitle = req.optString("title", sState.atTitle);
                        if (req.has("subtitle")) sState.atSubtitle = req.optString("subtitle", sState.atSubtitle);
                        if (req.has("ticker")) sState.atTicker = req.optString("ticker", sState.atTicker);
                        if (req.has("accentColor")) sState.atAccent = req.optString("accentColor", sState.atAccent);
                        if (req.has("bgColor")) sState.atBg = req.optString("bgColor", sState.atBg);
                        if (req.has("textColor")) sState.atText = req.optString("textColor", sState.atText);
                        if (req.has("align")) sState.atAlign = req.optInt("align", sState.atAlign);
                        if (req.has("scalePct")) sState.atScale = Math.max(60, Math.min(140, req.optInt("scalePct", sState.atScale)));
                        if (req.has("logoPath")) sState.atLogo = sanitizeLogoPath(req.optString("logoPath", sState.atLogo));
                        if (req.has("onAir")) {
                            boolean nextAir = req.optBoolean("onAir", sState.atOnAir);
                            if (nextAir) sState.atTriggerSeq++;
                            sState.atOnAir = nextAir;
                        }
                        break;
                    case "at_trigger": {
                        boolean nextAir = req.has("onAir") ? req.optBoolean("onAir", true) : !sState.atOnAir;
                        if (nextAir) sState.atTriggerSeq++;
                        sState.atOnAir = nextAir;
                        break;
                    }
                    case "at_save_preset": {
                        JSONObject p = new JSONObject();
                        p.put("name", req.optString("name", sState.atTitle));
                        p.put("template", sState.atTemplate);
                        p.put("animIn", sState.atAnimIn);
                        p.put("animOut", sState.atAnimOut);
                        p.put("inMs", sState.atInMs);
                        p.put("outMs", sState.atOutMs);
                        p.put("autoHideSec", sState.atAutoHideSec);
                        p.put("shimmer", sState.atShimmer);
                        p.put("badge", sState.atBadge);
                        p.put("title", sState.atTitle);
                        p.put("subtitle", sState.atSubtitle);
                        p.put("ticker", sState.atTicker);
                        p.put("accentColor", sState.atAccent);
                        p.put("bgColor", sState.atBg);
                        p.put("textColor", sState.atText);
                        p.put("align", sState.atAlign);
                        p.put("scalePct", sState.atScale);
                        p.put("logoPath", sState.atLogo);
                        sState.atPresets.add(p);
                        break;
                    }
                    case "at_apply_preset": {
                        int idx = req.optInt("index", -1);
                        if (idx >= 0 && idx < sState.atPresets.size()) {
                            JSONObject p = sState.atPresets.get(idx);
                            sState.atTemplate = p.optInt("template", 0);
                            sState.atAnimIn = p.optInt("animIn", 0);
                            sState.atAnimOut = p.optInt("animOut", 0);
                            sState.atInMs = Math.max(150, p.optInt("inMs", 650));
                            sState.atOutMs = Math.max(150, p.optInt("outMs", 450));
                            sState.atAutoHideSec = Math.max(0, p.optInt("autoHideSec", 0));
                            sState.atShimmer = p.optBoolean("shimmer", true);
                            sState.atBadge = p.optString("badge", "");
                            sState.atTitle = p.optString("title", "");
                            sState.atSubtitle = p.optString("subtitle", "");
                            sState.atTicker = p.optString("ticker", sState.atTicker);
                            sState.atAccent = p.optString("accentColor", "#2563EB");
                            sState.atBg = p.optString("bgColor", "#0F172A");
                            sState.atText = p.optString("textColor", "#FFFFFF");
                            sState.atAlign = p.optInt("align", 0);
                            sState.atScale = Math.max(60, Math.min(140, p.optInt("scalePct", 100)));
                            sState.atLogo = p.optString("logoPath", "");
                            if (req.optBoolean("launch", true)) {
                                sState.atTriggerSeq++;
                                sState.atOnAir = true;
                            }
                        }
                        break;
                    }
                    case "at_delete_preset": {
                        int idx = req.optInt("index", -1);
                        if (idx >= 0 && idx < sState.atPresets.size()) sState.atPresets.remove(idx);
                        break;
                    }
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

    // ---- 5. Date, Time & Weather (Open-Meteo + 6 Broadcast Styles) ----
    private static final class WeatherEntry {
        double tempC;
        int cond; // 0=sun, 1=partly, 2=cloudy, 3=rain, 4=storm, 5=clear-night, 6=fog
        String condLabel;
        String resolvedCity;
        long fetchedAtMs;
        boolean valid;
    }

    private static final Map<String, WeatherEntry> sWeatherCache = new HashMap<>();
    private static final Map<String, Long> sWeatherInFlight = new HashMap<>();
    private static final Map<String, Long> sWeatherRetryAfter = new HashMap<>();
    private static final Object sWeatherLock = new Object();

    private static String normalizeCityKey(String city) {
        String s = city != null ? city.trim() : "";
        return s.isEmpty() ? "asuncion" : s.toLowerCase(Locale.ROOT);
    }

    private static int wmoToCondition(int wmo, boolean isDay) {
        if (wmo == 0) return isDay ? 0 : 5;
        if (wmo == 1 || wmo == 2) return isDay ? 1 : 2;
        if (wmo == 3) return 2;
        if (wmo == 45 || wmo == 48) return 6;
        if ((wmo >= 51 && wmo <= 67) || (wmo >= 80 && wmo <= 82)) return 3;
        if (wmo >= 95) return 4;
        if (wmo >= 71 && wmo <= 86) return 3;
        return isDay ? 1 : 2;
    }

    private static String conditionLabelEs(int cond) {
        switch (cond) {
            case 0: return "Despejado";
            case 1: return "Parc. nublado";
            case 2: return "Nublado";
            case 3: return "Lluvia";
            case 4: return "Tormenta";
            case 5: return "Despejado";
            case 6: return "Niebla";
            default: return "Clima";
        }
    }

    private static String httpGetSimple(String urlStr) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(6000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "OBSAndroidPort/1.0");
        try {
            if (conn.getResponseCode() != 200) return null;
            try (java.io.InputStream is = conn.getInputStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void requestWeatherAsync(String rawCity) {
        final String city = (rawCity != null && !rawCity.trim().isEmpty()) ? rawCity.trim() : "Asuncion";
        final String key = normalizeCityKey(city);
        final long now = System.currentTimeMillis();
        synchronized (sWeatherLock) {
            WeatherEntry cached = sWeatherCache.get(key);
            if (cached != null && cached.valid && (now - cached.fetchedAtMs) < 15L * 60L * 1000L) {
                return;
            }
            Long retryAt = sWeatherRetryAfter.get(key);
            if (retryAt != null && now < retryAt) {
                return;
            }
            Long started = sWeatherInFlight.get(key);
            if (started != null && (now - started) < 60_000L) {
                return;
            }
            sWeatherInFlight.put(key, now);
        }
        new Thread(() -> {
            boolean ok = false;
            try {
                String q = java.net.URLEncoder.encode(city, "UTF-8");
                String geoRaw = httpGetSimple("https://geocoding-api.open-meteo.com/v1/search?name=" + q + "&count=1&language=es&format=json");
                if (geoRaw == null) return;
                JSONObject geo = new JSONObject(geoRaw);
                JSONArray results = geo.optJSONArray("results");
                if (results == null || results.length() == 0) return;
                JSONObject first = results.getJSONObject(0);
                double lat = first.getDouble("latitude");
                double lon = first.getDouble("longitude");
                String resolved = first.optString("name", city);

                String wxUrl = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day&timezone=auto",
                    lat, lon);
                String wxRaw = httpGetSimple(wxUrl);
                if (wxRaw == null) return;
                JSONObject wx = new JSONObject(wxRaw);
                JSONObject cur = wx.optJSONObject("current");
                if (cur == null) return;
                double tempC = cur.optDouble("temperature_2m", 25.0);
                int wmo = cur.optInt("weather_code", 0);
                boolean isDay = cur.optInt("is_day", 1) != 0;

                WeatherEntry entry = new WeatherEntry();
                entry.tempC = tempC;
                entry.cond = wmoToCondition(wmo, isDay);
                entry.condLabel = conditionLabelEs(entry.cond);
                entry.resolvedCity = resolved;
                entry.fetchedAtMs = System.currentTimeMillis();
                entry.valid = true;

                synchronized (sWeatherLock) {
                    sWeatherCache.put(key, entry);
                    sWeatherRetryAfter.remove(key);
                }
                ok = true;
                sRender.post(LiveGraphicsManager::refreshFrames);
            } catch (Throwable t) {
                Log.w(TAG, "Weather fetch skipped for " + city + ": " + t.getMessage());
            } finally {
                synchronized (sWeatherLock) {
                    sWeatherInFlight.remove(key);
                    if (!ok) {
                        // Back off 60s when offline so 1-second clock ticks never spawn thread storms
                        sWeatherRetryAfter.put(key, System.currentTimeMillis() + 60_000L);
                    }
                }
            }
        }, "OBS-WeatherFetch").start();
    }

    private static String formatSpanishDate(int fmt) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        String[] daysShort = {"", "Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb"};
        String[] daysFull = {"", "Domingo", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado"};
        String[] monthsShort = {"Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"};
        String[] monthsFull = {"Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"};
        int dow = cal.get(java.util.Calendar.DAY_OF_WEEK);
        int day = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int mon = cal.get(java.util.Calendar.MONTH);
        int year = cal.get(java.util.Calendar.YEAR);
        switch (fmt) {
            case 1:
                return String.format(Locale.US, "%02d/%02d/%04d", day, mon + 1, year);
            case 2:
                return daysFull[dow] + " " + day + " de " + monthsFull[mon];
            case 3:
                return String.format(Locale.US, "%02d %s", day, monthsShort[mon].toUpperCase(Locale.ROOT));
            default:
                return daysShort[dow] + ", " + day + " " + monthsShort[mon] + " " + year;
        }
    }

    private static String formatCurrentTime(boolean format24, boolean showSeconds) {
        java.util.Date now = new java.util.Date();
        String pattern;
        if (format24) {
            pattern = showSeconds ? "HH:mm:ss" : "HH:mm";
        } else {
            pattern = showSeconds ? "hh:mm:ss a" : "hh:mm a";
        }
        return new java.text.SimpleDateFormat(pattern, Locale.US).format(now);
    }

    private static void drawWeatherIcon(Canvas c, float cx, float cy, float r, int cond, int a255, boolean darkTheme) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int sunCol = Color.argb(a255, 250, 204, 21);
        int cloudCol = darkTheme ? Color.argb(a255, 226, 232, 240) : Color.argb(a255, 100, 116, 139);
        int rainCol = Color.argb(a255, 56, 189, 248);

        if (cond == 0) {
            // Sun
            p.setColor(sunCol);
            c.drawCircle(cx, cy, r * 0.48f, p);
            p.setStrokeWidth(Math.max(2f, r * 0.12f));
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 8; ++i) {
                double ang = i * Math.PI / 4.0;
                c.drawLine(
                    (float) (cx + Math.cos(ang) * r * 0.64f),
                    (float) (cy + Math.sin(ang) * r * 0.64f),
                    (float) (cx + Math.cos(ang) * r * 0.92f),
                    (float) (cy + Math.sin(ang) * r * 0.92f), p);
            }
            return;
        }
        if (cond == 5) {
            // Clear night crescent moon
            p.setColor(Color.argb(a255, 253, 224, 71));
            c.drawCircle(cx, cy, r * 0.62f, p);
            p.setColor(darkTheme ? Color.argb(a255, 15, 23, 42) : Color.argb(a255, 241, 245, 249));
            c.drawCircle(cx + r * 0.28f, cy - r * 0.18f, r * 0.52f, p);
            return;
        }
        if (cond == 1) {
            // Partly cloudy (sun behind cloud)
            p.setColor(sunCol);
            c.drawCircle(cx - r * 0.24f, cy - r * 0.22f, r * 0.36f, p);
        }
        // Cloud body for 1, 2, 3, 4, 6
        float cloudY = (cond == 3 || cond == 4) ? cy - r * 0.12f : cy + r * 0.08f;
        p.setColor(cloudCol);
        c.drawCircle(cx - r * 0.28f, cloudY + r * 0.04f, r * 0.30f, p);
        c.drawCircle(cx + r * 0.04f, cloudY - r * 0.14f, r * 0.36f, p);
        c.drawCircle(cx + r * 0.34f, cloudY + r * 0.06f, r * 0.26f, p);
        c.drawRoundRect(new RectF(cx - r * 0.54f, cloudY, cx + r * 0.56f, cloudY + r * 0.34f), r * 0.18f, r * 0.18f, p);

        if (cond == 3) {
            // Rain drops
            p.setColor(rainCol);
            p.setStrokeWidth(Math.max(2f, r * 0.12f));
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = -1; i <= 1; ++i) {
                float rx = cx + i * r * 0.32f;
                c.drawLine(rx, cloudY + r * 0.44f, rx - r * 0.10f, cloudY + r * 0.76f, p);
            }
        } else if (cond == 4) {
            // Lightning bolt
            p.setColor(sunCol);
            p.setStrokeWidth(Math.max(2.5f, r * 0.14f));
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx + r * 0.06f, cloudY + r * 0.34f, cx - r * 0.12f, cloudY + r * 0.62f, p);
            c.drawLine(cx - r * 0.12f, cloudY + r * 0.62f, cx + r * 0.10f, cloudY + r * 0.62f, p);
            c.drawLine(cx + r * 0.10f, cloudY + r * 0.62f, cx - r * 0.08f, cloudY + r * 0.90f, p);
        } else if (cond == 6) {
            // Fog lines
            p.setColor(Color.argb(Math.round(a255 * 0.78f), 148, 163, 184));
            p.setStrokeWidth(Math.max(2f, r * 0.11f));
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx - r * 0.55f, cloudY + r * 0.48f, cx + r * 0.55f, cloudY + r * 0.48f, p);
            c.drawLine(cx - r * 0.40f, cloudY + r * 0.68f, cx + r * 0.40f, cloudY + r * 0.68f, p);
        }
    }

    private static void drawTextFit(Canvas c, String text, float x, float y, float maxW, float baseSize, Paint paint) {
        if (text == null || text.isEmpty()) return;
        float sz = baseSize;
        paint.setTextSize(sz);
        float tw = paint.measureText(text);
        if (tw > maxW && tw > 1f) {
            sz = Math.max(12f, sz * (maxW / tw));
            paint.setTextSize(sz);
        }
        c.drawText(text, x, y, paint);
    }

    private static float drawTextFitCenteredY(Canvas c, String text, float x, float centerY, float maxW, float baseSize, Paint paint) {
        if (text == null || text.isEmpty()) return 0f;
        float sz = baseSize;
        paint.setTextSize(sz);
        float tw = paint.measureText(text);
        if (tw > maxW && tw > 1f) {
            sz = Math.max(12f, sz * (maxW / tw));
            paint.setTextSize(sz);
            tw = paint.measureText(text);
        }
        float baseline = centerY - (paint.ascent() + paint.descent()) * 0.5f;
        c.drawText(text, x, baseline, paint);
        return tw;
    }

    private static void drawDateTimeWeather(Canvas c, int w, int h, float alpha, JSONObject cfg) {
        int contentMode = cfg != null ? cfg.optInt("contentMode", 0) : 0;
        int layout = cfg != null ? cfg.optInt("layout", 0) : 0; // 0=Horizontal, 1=Vert Center, 2=Vert Left, 3=Vert Right
        int style = cfg != null ? cfg.optInt("style", 0) : 0;
        boolean fmt24 = cfg == null || cfg.optBoolean("timeFormat24", true);
        boolean showSec = cfg != null && cfg.optBoolean("showSeconds", false);
        int dateFmt = cfg != null ? cfg.optInt("dateFormat", 0) : 0;
        String city = cfg != null ? cfg.optString("city", "Asuncion").trim() : "Asuncion";
        boolean showCity = cfg == null || cfg.optBoolean("showCity", true);
        String unit = cfg != null ? cfg.optString("tempUnit", "C") : "C";
        boolean autoWx = cfg == null || cfg.optBoolean("autoWeather", true);
        int manualTemp = cfg != null ? cfg.optInt("manualTemp", 26) : 26;
        int manualCond = cfg != null ? cfg.optInt("manualCondition", 0) : 0;
        int accent = parseColorSafe(cfg != null ? cfg.optString("accentColor", "#2563EB") : "#2563EB", Color.parseColor("#2563EB"));

        boolean showDate = (contentMode == 0 || contentMode == 2 || contentMode == 3 || contentMode == 5);
        boolean showTime = (contentMode == 0 || contentMode == 1 || contentMode == 3 || contentMode == 4);
        boolean showWeather = (contentMode == 0 || contentMode == 1 || contentMode == 2 || contentMode == 6);
        boolean vertical = (layout >= 1);

        String timeStr = showTime ? formatCurrentTime(fmt24, showSec) : "";
        String dateStr = showDate ? formatSpanishDate(dateFmt) : "";

        double tempC = manualTemp;
        int cond = manualCond;
        String displayCity = city;
        if (autoWx) {
            synchronized (sWeatherLock) {
                WeatherEntry entry = sWeatherCache.get(normalizeCityKey(city));
                if (entry != null && entry.valid) {
                    tempC = entry.tempC;
                    cond = entry.cond;
                    if (displayCity.isEmpty() && entry.resolvedCity != null) displayCity = entry.resolvedCity;
                }
            }
        }
        int displayTemp = "F".equalsIgnoreCase(unit)
            ? (int) Math.round(tempC * 9.0 / 5.0 + 32.0)
            : (int) Math.round(tempC);
        String tempStr = displayTemp + "°" + ("F".equalsIgnoreCase(unit) ? "F" : "C");
        String wxSub = showCity && !displayCity.isEmpty()
            ? (displayCity + " · " + conditionLabelEs(cond))
            : conditionLabelEs(cond);

        int a255 = Math.round(alpha * 255);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        float padX = vertical ? w * 0.045f : w * 0.025f;
        float padY = vertical ? h * 0.045f : h * 0.09f;
        RectF card = new RectF(padX, padY, w - padX, h - padY);
        float cw = card.width();
        float ch = card.height();

        boolean lightStyle = (style == 4);
        int primaryText = lightStyle ? Color.argb(a255, 15, 23, 42) : Color.argb(a255, 248, 250, 252);
        int secondaryText = lightStyle ? Color.argb(a255, 71, 85, 105) : Color.argb(a255, 203, 213, 225);
        int dividerCol = lightStyle ? Color.argb(Math.round(90 * alpha), 15, 23, 42) : Color.argb(Math.round(90 * alpha), 255, 255, 255);

        // Background per style
        if (style == 0) {
            // 0: Noticiero TV (Barra / Panel con acento)
            p.setShader(new LinearGradient(card.left, card.top, card.right, card.bottom,
                Color.argb(Math.round(240 * alpha), 12, 20, 38),
                Color.argb(Math.round(235 * alpha), 23, 37, 68), Shader.TileMode.CLAMP));
            c.drawRoundRect(card, vertical ? 14f : 10f, vertical ? 14f : 10f, p);
            p.setShader(null);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            if (vertical) {
                c.drawRoundRect(new RectF(card.left, card.top, card.right, card.top + Math.max(8f, ch * 0.024f)), 6f, 6f, p);
                c.drawRect(card.left, card.bottom - 5f, card.right, card.bottom, p);
            } else {
                c.drawRoundRect(new RectF(card.left, card.top, card.left + Math.max(10f, cw * 0.016f), card.bottom), 6f, 6f, p);
                c.drawRect(card.left, card.bottom - 4f, card.right, card.bottom, p);
            }
        } else if (style == 1) {
            // 1: Pildora / Tarjeta Glass Moderna
            float rad = vertical ? Math.min(cw, ch) * 0.12f : Math.min(ch * 0.5f, cw * 0.22f);
            p.setShader(new LinearGradient(card.left, card.top, card.left, card.bottom,
                Color.argb(Math.round(215 * alpha), 15, 23, 42),
                Color.argb(Math.round(200 * alpha), 30, 41, 59), Shader.TileMode.CLAMP));
            c.drawRoundRect(card, rad, rad, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f);
            p.setColor(Color.argb(Math.round(115 * alpha), 255, 255, 255));
            c.drawRoundRect(card, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
        } else if (style == 2) {
            // 2: Tarjeta Broadcast
            p.setShader(new LinearGradient(card.left, card.top, card.right, card.bottom,
                Color.argb(Math.round(238 * alpha), 15, 23, 42),
                Color.argb(Math.round(228 * alpha), 30, 41, 59), Shader.TileMode.CLAMP));
            c.drawRoundRect(card, 18f, 18f, p);
            p.setShader(null);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRoundRect(new RectF(card.left + 18f, card.top, card.right - 18f, card.top + 6f), 3f, 3f, p);
        } else if (style == 3) {
            // 3: Minimalista Transparente (sin fondo, con sombra)
            txt.setShadowLayer(6f, 2f, 2f, Color.argb(Math.round(215 * alpha), 0, 0, 0));
        } else if (style == 4) {
            // 4: Editorial Claro (Fondo blanco)
            p.setColor(Color.argb(Math.round(244 * alpha), 248, 250, 252));
            c.drawRoundRect(card, 14f, 14f, p);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            if (vertical) {
                c.drawRoundRect(new RectF(card.left, card.top, card.right, card.top + Math.max(8f, ch * 0.024f)), 6f, 6f, p);
            } else {
                c.drawRoundRect(new RectF(card.left, card.top, card.left + Math.max(10f, cw * 0.018f), card.bottom), 6f, 6f, p);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(Color.argb(Math.round(60 * alpha), 15, 23, 42));
            c.drawRoundRect(card, 14f, 14f, p);
            p.setStyle(Paint.Style.FILL);
        } else {
            // 5: Digital LED Alto Contraste
            p.setColor(Color.argb(Math.round(245 * alpha), 5, 8, 15));
            c.drawRoundRect(card, 12f, 12f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3.5f);
            p.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            c.drawRoundRect(card, 12f, 12f, p);
            p.setStyle(Paint.Style.FILL);
            txt.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        }

        // Count active sections
        int sections = (showTime ? 1 : 0) + (showDate ? 1 : 0) + (showWeather ? 1 : 0);
        if (sections == 0) return;

        // Vertical stacked layout (layout 1=Center, 2=Left, 3=Right)
        if (vertical) {
            float innerL = card.left + cw * 0.075f;
            float innerR = card.right - cw * 0.075f;
            float innerW = Math.max(40f, innerR - innerL);
            float innerT = card.top + ch * (style == 0 || style == 4 ? 0.075f : 0.055f);
            float innerB = card.bottom - ch * 0.055f;
            float innerH = Math.max(40f, innerB - innerT);

            Paint.Align align = (layout == 2) ? Paint.Align.LEFT : (layout == 3 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            float anchorX = (layout == 2) ? innerL : (layout == 3 ? innerR : card.centerX());

            float wTime = showTime ? 1.35f : 0f;
            float wDate = showDate ? 0.78f : 0f;
            float wWx = showWeather ? (showCity && !displayCity.isEmpty() ? 1.18f : 0.96f) : 0f;
            float totalW = Math.max(0.1f, wTime + wDate + wWx);

            float cursorY = innerT;
            int drawn = 0;

            if (showTime) {
                float rowH = innerH * (wTime / totalW);
                float rowMidY = cursorY + rowH * 0.5f;
                if (style == 0 && sections > 1) {
                    p.setColor((accent & 0x00FFFFFF) | (Math.round(220 * alpha) << 24));
                    c.drawRoundRect(new RectF(innerL, cursorY + rowH * 0.08f, innerR, cursorY + rowH * 0.90f), 10f, 10f, p);
                    txt.setColor(Color.argb(a255, 255, 255, 255));
                } else if (style == 5) {
                    txt.setColor((accent & 0x00FFFFFF) | (a255 << 24));
                } else {
                    txt.setColor(primaryText);
                }
                txt.setTextAlign(align);
                float padInline = (style == 0 && sections > 1) ? cw * 0.04f : 0f;
                float tx = (layout == 2) ? (innerL + padInline) : (layout == 3 ? (innerR - padInline) : card.centerX());
                drawTextFitCenteredY(c, timeStr, tx, rowMidY, Math.max(40f, innerW - padInline * 2f),
                    Math.min(rowH * 0.70f, innerW * 0.34f), txt);
                cursorY += rowH;
                drawn++;
            }

            if (showDate) {
                if (drawn > 0 && style != 3) {
                    p.setColor(dividerCol);
                    c.drawRect(innerL, cursorY - 1f, innerR, cursorY + 1f, p);
                }
                float rowH = innerH * (wDate / totalW);
                float rowMidY = cursorY + rowH * 0.5f;
                txt.setTextAlign(align);
                txt.setColor(sections == 1 ? primaryText : secondaryText);
                drawTextFitCenteredY(c, dateStr, anchorX, rowMidY, innerW,
                    Math.min(rowH * 0.54f, innerW * 0.14f), txt);
                cursorY += rowH;
                drawn++;
            }

            if (showWeather) {
                if (drawn > 0 && style != 3) {
                    p.setColor(dividerCol);
                    c.drawRect(innerL, cursorY - 1f, innerR, cursorY + 1f, p);
                }
                float rowH = innerH * (wWx / totalW);
                boolean hasSub = showCity && !displayCity.isEmpty();
                float topLineY = hasSub ? (cursorY + rowH * 0.38f) : (cursorY + rowH * 0.50f);
                float iconR = Math.min(rowH * (hasSub ? 0.24f : 0.32f), innerW * 0.11f);
                float gap = iconR * 0.75f;

                Paint tempPaint = new Paint(txt);
                tempPaint.setColor(primaryText);
                float tempSz = Math.min(rowH * (hasSub ? 0.44f : 0.56f), innerW * 0.24f);
                tempPaint.setTextSize(tempSz);
                float maxTempW = Math.max(30f, innerW - iconR * 2.2f - gap);
                float tempTw = tempPaint.measureText(tempStr);
                if (tempTw > maxTempW && tempTw > 1f) {
                    tempSz = Math.max(12f, tempSz * (maxTempW / tempTw));
                    tempPaint.setTextSize(tempSz);
                    tempTw = tempPaint.measureText(tempStr);
                }
                float groupW = iconR * 2f + gap + tempTw;
                float groupLeft;
                if (layout == 2) {
                    groupLeft = innerL;
                } else if (layout == 3) {
                    groupLeft = innerR - groupW;
                } else {
                    groupLeft = card.centerX() - groupW * 0.5f;
                }
                drawWeatherIcon(c, groupLeft + iconR, topLineY, iconR, cond, a255, !lightStyle);
                tempPaint.setTextAlign(Paint.Align.LEFT);
                float tempBase = topLineY - (tempPaint.ascent() + tempPaint.descent()) * 0.5f;
                c.drawText(tempStr, groupLeft + iconR * 2f + gap, tempBase, tempPaint);

                if (hasSub) {
                    Paint subP = new Paint(txt);
                    subP.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
                    subP.setColor(secondaryText);
                    subP.setTextAlign(align);
                    drawTextFitCenteredY(c, wxSub, anchorX, cursorY + rowH * 0.78f, innerW,
                        Math.min(rowH * 0.27f, innerW * 0.11f), subP);
                }
            }
            return;
        }

        // Style 2 (Tarjeta 2 lineas): top row = Time + Weather, bottom row = Date + City/Condition
        if (style == 2 && sections >= 2) {
            float innerL = card.left + cw * 0.05f;
            float innerR = card.right - cw * 0.05f;
            float innerW = innerR - innerL;
            float topY = card.top + ch * 0.50f;
            float botY = card.top + ch * 0.84f;

            if (showTime && showWeather) {
                txt.setTextAlign(Paint.Align.LEFT);
                txt.setColor(primaryText);
                drawTextFit(c, timeStr, innerL, topY, innerW * 0.54f, ch * 0.40f, txt);

                float iconR = ch * 0.19f;
                txt.setTextAlign(Paint.Align.RIGHT);
                txt.setColor(primaryText);
                drawTextFit(c, tempStr, innerR, topY, innerW * 0.30f, ch * 0.38f, txt);
                float tempW = txt.measureText(tempStr);
                drawWeatherIcon(c, innerR - tempW - iconR * 1.45f, topY - ch * 0.13f, iconR, cond, a255, !lightStyle);
            } else if (showTime) {
                txt.setTextAlign(Paint.Align.CENTER);
                txt.setColor(primaryText);
                drawTextFit(c, timeStr, card.centerX(), topY, innerW, ch * 0.44f, txt);
            } else if (showWeather) {
                float iconR = ch * 0.21f;
                txt.setTextAlign(Paint.Align.LEFT);
                txt.setColor(primaryText);
                drawWeatherIcon(c, card.centerX() - ch * 0.55f, topY - ch * 0.13f, iconR, cond, a255, !lightStyle);
                drawTextFit(c, tempStr, card.centerX() - ch * 0.18f, topY, innerW * 0.55f, ch * 0.42f, txt);
            }

            p.setColor(dividerCol);
            c.drawRect(innerL, card.top + ch * 0.58f, innerR, card.top + ch * 0.595f, p);

            txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            txt.setColor(secondaryText);
            if (showDate && showWeather) {
                txt.setTextAlign(Paint.Align.LEFT);
                drawTextFit(c, dateStr, innerL, botY, innerW * 0.52f, ch * 0.20f, txt);
                txt.setTextAlign(Paint.Align.RIGHT);
                drawTextFit(c, wxSub, innerR, botY, innerW * 0.44f, ch * 0.20f, txt);
            } else if (showDate) {
                txt.setTextAlign(Paint.Align.CENTER);
                drawTextFit(c, dateStr, card.centerX(), botY, innerW, ch * 0.22f, txt);
            } else if (showWeather) {
                txt.setTextAlign(Paint.Align.CENTER);
                drawTextFit(c, wxSub, card.centerX(), botY, innerW, ch * 0.22f, txt);
            }
            return;
        }

        // Horizontal adaptive broadcast layout for Styles 0, 1, 3, 4, 5 (and single-element Style 2)
        float innerL = card.left + (style == 1 ? Math.min(ch * 0.32f, cw * 0.12f) : Math.max(22f, cw * 0.035f));
        float innerR = card.right - (style == 1 ? Math.min(ch * 0.32f, cw * 0.12f) : Math.max(18f, cw * 0.035f));
        float innerW = Math.max(40f, innerR - innerL);

        // Build ordered slots: [Time] [Date] [Weather]
        float cursorX = innerL;
        int drawn = 0;

        float timeWeight = showTime ? ((showSec && !fmt24) ? 1.35f : ((showSec || !fmt24) ? 1.18f : 0.95f)) : 0f;
        float dateWeight = showDate ? (dateFmt == 2 ? 1.70f : (dateFmt == 0 ? 1.30f : (dateFmt == 1 ? 1.05f : 0.85f))) : 0f;
        float wxWeight = showWeather ? (showCity && !displayCity.isEmpty() ? 1.25f : 0.92f) : 0f;
        float totalWeight = Math.max(0.1f, timeWeight + dateWeight + wxWeight);

        if (showTime) {
            float slotW = innerW * (timeWeight / totalWeight);
            float centerX = cursorX + slotW * 0.5f;
            if (style == 0 && sections > 1) {
                // Highlight chip behind time in TV News bar
                p.setColor((accent & 0x00FFFFFF) | (Math.round(220 * alpha) << 24));
                c.drawRoundRect(new RectF(cursorX + 4f, card.top + ch * 0.14f, cursorX + slotW - 10f, card.bottom - ch * 0.14f), 8f, 8f, p);
                txt.setColor(Color.argb(a255, 255, 255, 255));
            } else if (style == 5) {
                txt.setColor((accent & 0x00FFFFFF) | (a255 << 24));
            } else {
                txt.setColor(primaryText);
            }
            txt.setTextAlign(Paint.Align.CENTER);
            drawTextFitCenteredY(c, timeStr, centerX - (style == 0 && sections > 1 ? 3f : 0f),
                card.centerY(), Math.max(30f, slotW - 22f), ch * 0.48f, txt);
            cursorX += slotW;
            drawn++;
        }

        if (showDate) {
            if (drawn > 0 && style != 0 && style != 3) {
                p.setColor(dividerCol);
                c.drawRect(cursorX - 1.5f, card.top + ch * 0.22f, cursorX + 1.5f, card.bottom - ch * 0.22f, p);
            }
            float slotW = innerW * (dateWeight / totalWeight);
            float centerX = cursorX + slotW * 0.5f;
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(sections == 1 ? primaryText : secondaryText);
            drawTextFitCenteredY(c, dateStr, centerX, card.centerY(),
                Math.max(30f, slotW - 20f), ch * (sections == 1 ? 0.36f : 0.28f), txt);
            cursorX += slotW;
            drawn++;
        }

        if (showWeather) {
            if (drawn > 0 && style != 3) {
                p.setColor(dividerCol);
                c.drawRect(cursorX - 1.5f, card.top + ch * 0.22f, cursorX + 1.5f, card.bottom - ch * 0.22f, p);
            }
            float slotW = innerW * (wxWeight / totalWeight);
            boolean hasCityLine = showCity && !displayCity.isEmpty();
            float iconR = Math.min(ch * 0.28f, slotW * 0.17f);
            float gap = iconR * 0.65f;
            float maxTextW = Math.max(30f, slotW - iconR * 2.1f - gap - 14f);

            Paint tempP = new Paint(txt);
            tempP.setColor(primaryText);
            tempP.setTextAlign(Paint.Align.LEFT);
            float tempSz = ch * (hasCityLine ? 0.36f : 0.46f);
            tempP.setTextSize(tempSz);
            float tempW = tempP.measureText(tempStr);
            if (tempW > maxTextW && tempW > 1f) {
                tempSz = Math.max(12f, tempSz * (maxTextW / tempW));
                tempP.setTextSize(tempSz);
                tempW = tempP.measureText(tempStr);
            }

            Paint subP = null;
            float subW = 0f;
            if (hasCityLine) {
                subP = new Paint(txt);
                subP.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
                subP.setColor(secondaryText);
                subP.setTextAlign(Paint.Align.LEFT);
                float subSz = ch * 0.22f;
                subP.setTextSize(subSz);
                subW = subP.measureText(displayCity);
                if (subW > maxTextW && subW > 1f) {
                    subSz = Math.max(11f, subSz * (maxTextW / subW));
                    subP.setTextSize(subSz);
                    subW = subP.measureText(displayCity);
                }
            }

            float groupW = iconR * 2f + gap + Math.max(tempW, subW);
            float groupLeft = cursorX + Math.max(6f, (slotW - groupW) * 0.5f);
            drawWeatherIcon(c, groupLeft + iconR, card.centerY(), iconR, cond, a255, !lightStyle);

            float textL = groupLeft + iconR * 2f + gap;
            if (hasCityLine) {
                float tempBase = (card.centerY() - ch * 0.14f) - (tempP.ascent() + tempP.descent()) * 0.5f;
                c.drawText(tempStr, textL, tempBase, tempP);
                float subBase = (card.centerY() + ch * 0.22f) - (subP.ascent() + subP.descent()) * 0.5f;
                c.drawText(displayCity, textL, subBase, subP);
            } else {
                float tempBase = card.centerY() - (tempP.ascent() + tempP.descent()) * 0.5f;
                c.drawText(tempStr, textL, tempBase, tempP);
            }
        }
    }

    // ---- 6. Advanced Animated Title Generator (30 FPS Motion Engine + 8 Broadcast Templates) ----
    private static float easeOutCubic(float t) {
        float u = 1f - Math.max(0f, Math.min(1f, t));
        return 1f - u * u * u;
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float x = Math.max(0f, Math.min(1f, t)) - 1f;
        return 1f + c3 * x * x * x + c1 * x * x;
    }

    private static String typewriterSlice(String text, float t) {
        if (text == null || text.isEmpty()) return "";
        int n = Math.round(text.length() * Math.max(0f, Math.min(1f, t)));
        if (n <= 0) return "";
        if (n >= text.length()) return text;
        return text.substring(0, n) + "▌";
    }

    private static void drawShimmerOverlay(Canvas c, RectF rect, float radius, long elapsedMs, float alpha) {
        float phase = ((elapsedMs % 3200L) / 3200f);
        if (phase > 0.45f) return;
        float norm = phase / 0.45f; // 0..1 sweep
        float bandW = rect.width() * 0.28f;
        float x = rect.left - bandW + (rect.width() + bandW * 2f) * norm;
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        int shineAlpha = Math.round(75 * alpha);
        sp.setShader(new LinearGradient(x, rect.top, x + bandW, rect.bottom,
            new int[]{Color.TRANSPARENT, Color.argb(shineAlpha, 255, 255, 255), Color.TRANSPARENT},
            new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW);
        c.clipPath(clip);
        c.drawRect(rect, sp);
        c.restore();
    }

    private static void drawAdvancedTitle(Canvas c, int w, int h, float rawProgress, boolean exiting,
                                          long elapsedOnAirMs, float tickerX, JSONObject localCfg) {
        int template, animIn, animOut, align, scalePct;
        boolean shimmer;
        String badge, title, subtitle, ticker, logoPath;
        int accentCol, bgCol, textCol;

        synchronized (sStateLock) {
            template = localCfg != null ? localCfg.optInt("template", 0) : sState.atTemplate;
            animIn = localCfg != null ? localCfg.optInt("animIn", 0) : sState.atAnimIn;
            animOut = localCfg != null ? localCfg.optInt("animOut", 0) : sState.atAnimOut;
            shimmer = localCfg != null ? localCfg.optBoolean("shimmer", true) : sState.atShimmer;
            badge = localCfg != null ? localCfg.optString("badge", "EN VIVO") : sState.atBadge;
            title = localCfg != null ? localCfg.optString("title", "TÍTULO PRINCIPAL") : sState.atTitle;
            subtitle = localCfg != null ? localCfg.optString("subtitle", "") : sState.atSubtitle;
            ticker = localCfg != null ? localCfg.optString("ticker", "") : sState.atTicker;
            accentCol = parseColorSafe(localCfg != null ? localCfg.optString("accentColor", "#2563EB") : sState.atAccent, Color.parseColor("#2563EB"));
            bgCol = parseColorSafe(localCfg != null ? localCfg.optString("bgColor", "#0F172A") : sState.atBg, Color.parseColor("#0F172A"));
            textCol = parseColorSafe(localCfg != null ? localCfg.optString("textColor", "#FFFFFF") : sState.atText, Color.WHITE);
            align = localCfg != null ? localCfg.optInt("align", 0) : sState.atAlign;
            scalePct = Math.max(60, Math.min(140, localCfg != null ? localCfg.optInt("scalePct", 100) : sState.atScale));
            logoPath = localCfg != null ? localCfg.optString("logoPath", "") : sState.atLogo;
        }

        float p = Math.max(0f, Math.min(1f, rawProgress));
        float eased = easeOutCubic(p);
        float textScale = scalePct / 100f;
        int effAnim = animIn;
        if (exiting && animOut > 0) {
            if (animOut == 1) effAnim = 5;      // Fade
            else if (animOut == 2) effAnim = 3; // Rise/Drop
            else if (animOut == 3) effAnim = 1; // Wipe
        }

        // Apply global transform / clip for the selected animation
        float alpha = Math.min(1f, p * 1.4f);
        int a255 = Math.round(alpha * 255);
        c.save();
        if (effAnim == 0) {
            // Slide Left Broadcast
            float dx = (1f - eased) * (align == 2 ? w * 0.28f : -w * 0.28f);
            c.translate(dx, 0f);
        } else if (effAnim == 1) {
            // Curtain Wipe Reveal
            if (align == 2) c.clipRect(w * (1f - eased), 0f, w, h);
            else if (align == 1) {
                float half = w * 0.5f * eased;
                c.clipRect(w * 0.5f - half, 0f, w * 0.5f + half, h);
            } else {
                c.clipRect(0f, 0f, w * eased, h);
            }
        } else if (effAnim == 2) {
            // Elastic Pop Zoom
            float sc = exiting ? (0.82f + 0.18f * eased) : Math.max(0.05f, easeOutBack(p));
            c.scale(sc, sc, w * 0.5f, h * 0.5f);
        } else if (effAnim == 3) {
            // Rise Up from Baseline
            c.clipRect(0f, 0f, w, h);
            c.translate(0f, (1f - eased) * h * 0.45f);
        } else if (effAnim == 5) {
            // Cinematic Fade
            alpha = eased;
            a255 = Math.round(alpha * 255);
        }

        // Typewriter text slicing when effAnim == 4
        String shownTitle = (effAnim == 4 && !exiting) ? typewriterSlice(title, p) : (title != null ? title : "");
        String shownSub = (effAnim == 4 && !exiting) ? typewriterSlice(subtitle, Math.max(0f, (p - 0.35f) / 0.65f)) : (subtitle != null ? subtitle : "");

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        Bitmap logo = getCachedAtLogo(logoPath);

        int bgWithA = (bgCol & 0x00FFFFFF) | (Math.round(238 * alpha) << 24);
        int bgDarkWithA = Color.argb(Math.round(244 * alpha), Math.max(0, Color.red(bgCol) - 18), Math.max(0, Color.green(bgCol) - 18), Math.max(0, Color.blue(bgCol) - 18));
        int accWithA = (accentCol & 0x00FFFFFF) | (a255 << 24);
        int txtWithA = (textCol & 0x00FFFFFF) | (a255 << 24);
        int subWithA = (textCol & 0x00FFFFFF) | (Math.round(205 * alpha) << 24);

        // Template 4: Marquesina / Ticker Continuo (Scrolling News Ribbon)
        if (template == 4) {
            float barH = Math.min(h * 0.42f, 110f);
            float top = (h - barH) * 0.5f;
            float left = w * 0.02f;
            float right = w * 0.98f;
            RectF bar = new RectF(left, top, right, top + barH);
            paint.setColor(bgWithA);
            c.drawRoundRect(bar, 10f, 10f, paint);
            paint.setColor(accWithA);
            c.drawRect(left, top + barH - 5f, right, top + barH, paint);

            float badgeW = 0f;
            if (badge != null && !badge.trim().isEmpty()) {
                txt.setTextSize(barH * 0.38f * textScale);
                badgeW = Math.min((right - left) * 0.30f, txt.measureText(badge.toUpperCase(Locale.ROOT)) + 44f);
                paint.setColor(accWithA);
                c.drawRoundRect(new RectF(left, top, left + badgeW, top + barH), 10f, 10f, paint);
                txt.setTextAlign(Paint.Align.CENTER);
                txt.setColor(Color.argb(a255, 255, 255, 255));
                drawTextFitCenteredY(c, badge.toUpperCase(Locale.ROOT), left + badgeW * 0.5f, top + barH * 0.5f, badgeW - 18f, barH * 0.38f * textScale, txt);
            }

            String scrollStr = (ticker != null && !ticker.trim().isEmpty()) ? ticker : (shownTitle + "   •   " + shownSub + "   •   ");
            if (!scrollStr.endsWith("   ")) scrollStr = scrollStr + "   •   ";
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(txtWithA);
            txt.setTextSize(barH * 0.44f * textScale);
            float unitW = Math.max(80f, txt.measureText(scrollStr));
            float scrollAreaL = left + badgeW + 18f;
            float scrollAreaR = right - 18f;
            float offset = tickerX % unitW;
            c.save();
            c.clipRect(scrollAreaL, top, scrollAreaR, top + barH);
            float baseline = (top + barH * 0.5f) - (txt.ascent() + txt.descent()) * 0.5f;
            for (float x = scrollAreaL - offset; x < scrollAreaR + unitW; x += unitW) {
                c.drawText(scrollStr, x, baseline, txt);
            }
            c.restore();
            if (shimmer && !exiting) drawShimmerOverlay(c, bar, 10f, elapsedOnAirMs, alpha);
            c.restore();
            return;
        }

        // Template 5: Intro Pantalla Completa (Centered Hero Title Card)
        if (template == 5) {
            float cw = w * 0.84f;
            float ch = h * 0.78f;
            RectF box = new RectF((w - cw) * 0.5f, (h - ch) * 0.5f, (w + cw) * 0.5f, (h + ch) * 0.5f);
            paint.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom, bgWithA, bgDarkWithA, Shader.TileMode.CLAMP));
            c.drawRoundRect(box, 22f, 22f, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            paint.setColor(accWithA);
            c.drawRoundRect(box, 22f, 22f, paint);
            paint.setStyle(Paint.Style.FILL);

            // Animated horizontal accent line expanding from center
            float lineHalf = cw * 0.34f * eased;
            paint.setColor(accWithA);
            c.drawRoundRect(new RectF(box.centerX() - lineHalf, box.centerY() + ch * 0.08f, box.centerX() + lineHalf, box.centerY() + ch * 0.08f + 6f), 3f, 3f, paint);

            if (logo != null) {
                drawLogoFit(c, logo, box.centerX(), box.top + ch * 0.22f, ch * 0.26f, a255);
            } else if (badge != null && !badge.trim().isEmpty()) {
                txt.setTextAlign(Paint.Align.CENTER);
                txt.setColor(accWithA);
                drawTextFitCenteredY(c, badge.toUpperCase(Locale.ROOT), box.centerX(), box.top + ch * 0.22f, cw * 0.82f, ch * 0.11f * textScale, txt);
            }

            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(txtWithA);
            float splitDy = (effAnim == 6) ? (1f - eased) * ch * 0.14f : 0f;
            drawTextFitCenteredY(c, shownTitle, box.centerX(), box.centerY() - ch * 0.06f + splitDy, cw * 0.88f, ch * 0.24f * textScale, txt);

            if (!shownSub.isEmpty()) {
                txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
                txt.setColor(subWithA);
                drawTextFitCenteredY(c, shownSub, box.centerX(), box.centerY() + ch * 0.26f - splitDy, cw * 0.84f, ch * 0.13f * textScale, txt);
            }
            if (shimmer && !exiting) drawShimmerOverlay(c, box, 22f, elapsedOnAirMs, alpha);
            c.restore();
            return;
        }

        // Standard lower-third / card geometry for Templates 0, 1, 2, 3, 6, 7
        float boxW = (template == 6) ? w * 0.62f : w * 0.84f;
        float boxH = Math.min(h * 0.72f, 230f);
        float left = (align == 1) ? (w - boxW) * 0.5f : (align == 2 ? (w - boxW - w * 0.04f) : w * 0.04f);
        float top = (h - boxH) * 0.5f;
        RectF card = new RectF(left, top, left + boxW, top + boxH);

        if (template == 1) {
            // Deportes / Impacto Diagonal (skewed plate)
            c.save();
            c.skew(-0.14f, 0f);
            float adjL = left + top * 0.14f;
            RectF skCard = new RectF(adjL, top, adjL + boxW, top + boxH);
            paint.setColor(bgWithA);
            c.drawRect(skCard, paint);
            paint.setColor(accWithA);
            c.drawRect(adjL, top, adjL + 22f, top + boxH, paint);
            c.drawRect(adjL + 28f, top, adjL + 36f, top + boxH, paint);
            c.drawRect(adjL, top + boxH - 8f, adjL + boxW, top + boxH, paint);
            if (shimmer && !exiting) drawShimmerOverlay(c, skCard, 0f, elapsedOnAirMs, alpha);
            c.restore();
        } else if (template == 2) {
            // Minimalista Cinematográfico (no card, expanding rule + shadow)
            txt.setShadowLayer(8f, 0f, 3f, Color.argb(Math.round(220 * alpha), 0, 0, 0));
            float ruleY = top + boxH * 0.56f;
            paint.setColor(accWithA);
            if (align == 1) {
                float half = boxW * 0.42f * eased;
                c.drawRoundRect(new RectF(card.centerX() - half, ruleY, card.centerX() + half, ruleY + 6f), 3f, 3f, paint);
            } else if (align == 2) {
                c.drawRoundRect(new RectF(card.right - boxW * 0.82f * eased, ruleY, card.right, ruleY + 6f), 3f, 3f, paint);
            } else {
                c.drawRoundRect(new RectF(card.left, ruleY, card.left + boxW * 0.82f * eased, ruleY + 6f), 3f, 3f, paint);
            }
        } else if (template == 3) {
            // Glassmorphism / Podcast
            paint.setShader(new LinearGradient(card.left, card.top, card.right, card.bottom,
                (bgCol & 0x00FFFFFF) | (Math.round(196 * alpha) << 24),
                (bgCol & 0x00FFFFFF) | (Math.round(165 * alpha) << 24), Shader.TileMode.CLAMP));
            c.drawRoundRect(card, 24f, 24f, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f);
            paint.setColor(Color.argb(Math.round(125 * alpha), 255, 255, 255));
            c.drawRoundRect(card, 24f, 24f, paint);
            paint.setStyle(Paint.Style.FILL);
            if (shimmer && !exiting) drawShimmerOverlay(c, card, 24f, elapsedOnAirMs, alpha);
        } else if (template == 6) {
            // Tarjeta Redes Sociales (capsule card + accent circle badge)
            float r = boxH * 0.48f;
            paint.setColor(bgWithA);
            c.drawRoundRect(card, r, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3.5f);
            paint.setColor(accWithA);
            c.drawRoundRect(card, r, r, paint);
            paint.setStyle(Paint.Style.FILL);
            if (shimmer && !exiting) drawShimmerOverlay(c, card, r, elapsedOnAirMs, alpha);
        } else if (template == 7) {
            // Cita / Frase Destacada
            paint.setColor(bgWithA);
            c.drawRoundRect(card, 16f, 16f, paint);
            paint.setColor(accWithA);
            c.drawRoundRect(new RectF(card.left, card.top, card.left + 12f, card.bottom), 6f, 6f, paint);
            Paint qP = new Paint(txt);
            qP.setColor((accentCol & 0x00FFFFFF) | (Math.round(115 * alpha) << 24));
            qP.setTextSize(boxH * 0.72f);
            qP.setTextAlign(Paint.Align.LEFT);
            c.drawText("“", card.left + 22f, card.top + boxH * 0.62f, qP);
            if (shimmer && !exiting) drawShimmerOverlay(c, card, 16f, elapsedOnAirMs, alpha);
        } else {
            // Template 0: Noticiero Doble Barra (staggered dual tier)
            float topH = boxH * 0.56f;
            paint.setColor(Color.argb(Math.round(246 * alpha), 248, 250, 252));
            c.drawRoundRect(new RectF(card.left, card.top, card.right, card.top + topH), 8f, 8f, paint);
            paint.setColor(bgDarkWithA);
            c.drawRoundRect(new RectF(card.left, card.top + topH - 4f, card.right * 0.96f, card.bottom), 8f, 8f, paint);
            paint.setColor(accWithA);
            c.drawRect(card.left, card.top, card.left + 16f, card.bottom, paint);
            if (shimmer && !exiting) drawShimmerOverlay(c, card, 8f, elapsedOnAirMs, alpha);
        }

        // Content placement (Logo + Badge + Title + Subtitle)
        float textLeft = card.left + (template == 1 ? 56f : (template == 7 ? 88f : 34f));
        float textRight = card.right - 28f;
        if (logo != null) {
            float logoBox = boxH * 0.64f;
            drawLogoFit(c, logo, card.left + 28f + logoBox * 0.5f, card.centerY(), logoBox, a255);
            textLeft = card.left + 44f + logoBox;
        } else if (template == 6) {
            // Social icon circle on left
            float cr = boxH * 0.34f;
            float cx = card.left + boxH * 0.50f;
            paint.setColor(accWithA);
            c.drawCircle(cx, card.centerY(), cr, paint);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(Color.argb(a255, 255, 255, 255));
            String sym = (badge != null && !badge.trim().isEmpty()) ? badge.trim().substring(0, Math.min(2, badge.trim().length())).toUpperCase(Locale.ROOT) : "@";
            drawTextFitCenteredY(c, sym, cx, card.centerY(), cr * 1.5f, cr * 0.92f, txt);
            textLeft = cx + cr + 24f;
        }

        float availW = Math.max(80f, textRight - textLeft);
        Paint.Align tAlign = (align == 1 && logo == null && template != 6) ? Paint.Align.CENTER
            : (align == 2 && logo == null && template != 6 ? Paint.Align.RIGHT : Paint.Align.LEFT);
        float anchorX = (tAlign == Paint.Align.CENTER) ? card.centerX() : (tAlign == Paint.Align.RIGHT ? textRight : textLeft);
        float splitDy = (effAnim == 6) ? (1f - eased) * boxH * 0.16f : 0f;

        if (template == 0) {
            // Template 0: Dark headline on top white strip, light subtitle on bottom navy strip
            float topH = boxH * 0.56f;
            float botH = boxH - topH;
            float titleStartX = textLeft;
            if (badge != null && !badge.trim().isEmpty()) {
                txt.setTextAlign(Paint.Align.LEFT);
                txt.setTextSize(topH * 0.30f * textScale);
                String bt = badge.toUpperCase(Locale.ROOT);
                float chipW = Math.min(availW * 0.34f, txt.measureText(bt) + 28f);
                paint.setColor(accWithA);
                c.drawRoundRect(new RectF(titleStartX, card.top + topH * 0.22f, titleStartX + chipW, card.top + topH * 0.78f), 6f, 6f, paint);
                txt.setColor(Color.argb(a255, 255, 255, 255));
                txt.setTextAlign(Paint.Align.CENTER);
                drawTextFitCenteredY(c, bt, titleStartX + chipW * 0.5f, card.top + topH * 0.5f, chipW - 12f, topH * 0.30f * textScale, txt);
                titleStartX += chipW + 18f;
            }
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(Color.argb(a255, 15, 23, 42));
            drawTextFitCenteredY(c, shownTitle, titleStartX, card.top + topH * 0.50f + splitDy,
                Math.max(60f, textRight - titleStartX), topH * 0.48f * textScale, txt);
            if (!shownSub.isEmpty()) {
                txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
                txt.setColor(Color.argb(a255, 241, 245, 249));
                drawTextFitCenteredY(c, shownSub, textLeft, card.top + topH + botH * 0.50f - splitDy,
                    availW, botH * 0.46f * textScale, txt);
            }
        } else {
            // Templates 1, 2, 3, 6, 7
            boolean hasBadge = (template != 6 && badge != null && !badge.trim().isEmpty());
            if (hasBadge) {
                txt.setTextAlign(tAlign);
                txt.setColor(accWithA);
                drawTextFitCenteredY(c, badge.toUpperCase(Locale.ROOT), anchorX, card.top + boxH * 0.20f,
                    availW, boxH * 0.14f * textScale, txt);
            }
            txt.setTextAlign(tAlign);
            txt.setColor(txtWithA);
            float titleY = hasBadge ? (card.top + boxH * 0.47f) : (card.top + boxH * 0.38f);
            drawTextFitCenteredY(c, shownTitle, anchorX, titleY + splitDy, availW, boxH * 0.27f * textScale, txt);

            if (!shownSub.isEmpty()) {
                txt.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
                txt.setColor(subWithA);
                float subY = hasBadge ? (card.top + boxH * 0.77f) : (card.top + boxH * 0.72f);
                drawTextFitCenteredY(c, shownSub, anchorX, subY - splitDy, availW, boxH * 0.18f * textScale, txt);
            }
        }

        c.restore();
    }

    private LiveGraphicsManager() {}
}
