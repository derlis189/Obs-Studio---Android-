// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

public final class FloatingSceneOverlay {
    private static final String TAG = "ObsFloating";
    private static final String PREFS = "obs_android_prefs";
    private static final String KEY_ENABLED = "floating_overlay_enabled";
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService sBg =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OBS-FloatBg");
            t.setDaemon(true);
            return t;
        });
    private static volatile String sCachedStateJson = "{}";

    private static WindowManager sWm;
    private static View sRootView;
    private static WindowManager.LayoutParams sParams;
    private static boolean sExpanded = false;
    private static volatile boolean sShowing = false;
    private static Context sAppCtx;

    private static final Runnable sPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sShowing || !sExpanded) return;
            sBg.execute(() -> {
                try {
                    String st = NativeBridge.remoteGetState();
                    if (st != null && !st.isEmpty()) sCachedStateJson = st;
                } catch (Throwable ignored) {}
                sMain.post(() -> {
                    if (!sShowing || !sExpanded) return;
                    refreshExpandedPanel();
                    sMain.postDelayed(sPollRunnable, 1500L);
                });
            });
        }
    };

    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return false;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static void toggleFromTools(Activity act) {
        if (act == null || act.isFinishing()) return;
        sAppCtx = act.getApplicationContext();
        act.runOnUiThread(() -> {
            if (isEnabled(act)) {
                setEnabled(act, false);
                hide();
                Toast.makeText(act, "Boton flotante de escenas desactivado", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!Settings.canDrawOverlays(act)) {
                new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Boton flotante de escenas")
                    .setMessage("Para mostrar el boton flotante sobre otras aplicaciones cuando salgas de OBS, habilita el permiso 'Mostrar sobre otras aplicaciones'.")
                    .setPositiveButton("Abrir permiso", (d, w) -> {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + act.getPackageName()));
                            act.startActivity(i);
                            setEnabled(act, true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open overlay settings", e);
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
                return;
            }
            setEnabled(act, true);
            Toast.makeText(act, "Boton flotante activado: aparecera al salir de OBS", Toast.LENGTH_LONG).show();
        });
    }

    public static void onAppBackgrounded(Context ctx) {
        if (ctx == null) return;
        sAppCtx = ctx.getApplicationContext();
        if (!isEnabled(sAppCtx)) return;
        if (!Settings.canDrawOverlays(sAppCtx)) return;
        sMain.post(() -> showCollapsed(sAppCtx));
    }

    public static void onAppForegrounded() {
        sMain.post(FloatingSceneOverlay::hide);
    }

    private static GradientDrawable roundedBg(int color, float radiusPx, int strokeColor, int strokePx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radiusPx);
        if (strokePx > 0) gd.setStroke(strokePx, strokeColor);
        return gd;
    }

    private static void showCollapsed(Context ctx) {
        if (sShowing && sRootView != null) return;
        try {
            sWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (sWm == null) return;
            float d = ctx.getResources().getDisplayMetrics().density;

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

            sParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            sParams.gravity = Gravity.TOP | Gravity.START;
            sParams.x = Math.round(16 * d);
            sParams.y = Math.round(120 * d);

            LinearLayout container = new LinearLayout(ctx);
            container.setOrientation(LinearLayout.VERTICAL);
            sRootView = container;
            sExpanded = false;
            rebuildView(ctx, container);

            sWm.addView(sRootView, sParams);
            sShowing = true;
        } catch (Throwable t) {
            Log.e(TAG, "showCollapsed failed", t);
        }
    }

    private static void rebuildView(Context ctx, LinearLayout container) {
        container.removeAllViews();
        float d = ctx.getResources().getDisplayMetrics().density;
        if (!sExpanded) {
            LinearLayout pill = new LinearLayout(ctx);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            int px = Math.round(10 * d), py = Math.round(7 * d);
            pill.setPadding(px, py, Math.round(12 * d), py);
            pill.setBackground(roundedBg(Color.argb(238, 10, 14, 20), 24 * d, Color.parseColor("#3B82F6"), Math.round(2 * d)));

            ImageView logoIv = new ImageView(ctx);
            logoIv.setImageResource(R.drawable.obs_splash_logo);
            int iconPx = Math.round(22 * d);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconPx, iconPx);
            iconLp.rightMargin = Math.round(6 * d);
            pill.addView(logoIv, iconLp);

            TextView label = new TextView(ctx);
            label.setText("OBS");
            label.setTextColor(Color.WHITE);
            label.setTextSize(13f);
            label.setTypeface(null, android.graphics.Typeface.BOLD);
            pill.addView(label);

            attachDragAndTap(pill, () -> {
                sExpanded = true;
                rebuildView(ctx, container);
                sMain.removeCallbacks(sPollRunnable);
                sMain.postDelayed(sPollRunnable, 1500L);
            });
            container.addView(pill);
        } else {
            int pad = Math.round(10 * d);
            container.setPadding(pad, pad, pad, pad);
            container.setBackground(roundedBg(Color.argb(242, 15, 23, 42), 14 * d, Color.parseColor("#334155"), Math.round(1.5f * d)));
            populateExpanded(ctx, container);
        }
        try {
            if (sWm != null && sRootView != null && sRootView.isAttachedToWindow()) {
                sWm.updateViewLayout(sRootView, sParams);
            }
        } catch (Throwable ignored) {}
    }

    private static void refreshExpandedPanel() {
        if (!sShowing || !sExpanded || !(sRootView instanceof LinearLayout) || sAppCtx == null) return;
        populateExpanded(sAppCtx, (LinearLayout) sRootView);
    }

    private static void populateExpanded(Context ctx, LinearLayout container) {
        container.removeAllViews();
        float d = ctx.getResources().getDisplayMetrics().density;
        int panelW = Math.round(230 * d);

        // Header row (draggable) + minimize
        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        ImageView hdrLogo = new ImageView(ctx);
        hdrLogo.setImageResource(R.drawable.obs_splash_logo);
        int hdrIconPx = Math.round(20 * d);
        LinearLayout.LayoutParams hdrIconLp = new LinearLayout.LayoutParams(hdrIconPx, hdrIconPx);
        hdrIconLp.rightMargin = Math.round(6 * d);
        hdr.addView(hdrLogo, hdrIconLp);
        TextView title = new TextView(ctx);
        title.setText("OBS Escenas");
        title.setTextColor(Color.WHITE);
        title.setTextSize(13f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        hdr.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView minBtn = makeActionChip(ctx, "Ocultar", Color.parseColor("#334155"), () -> {
            sExpanded = false;
            sMain.removeCallbacks(sPollRunnable);
            container.setPadding(0, 0, 0, 0);
            container.setBackground(null);
            rebuildView(ctx, container);
        });
        hdr.addView(minBtn);
        attachDragAndTap(hdr, null);
        container.addView(hdr, new LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Use cached live OBS state (fetched asynchronously off Android UI thread to prevent ANR/deadlocks)
        String stateStr = sCachedStateJson;
        JSONObject st;
        try { st = new JSONObject(stateStr != null ? stateStr : "{}"); } catch (Throwable t) { st = new JSONObject(); }
        boolean streaming = st.optBoolean("streaming", false);
        boolean recording = st.optBoolean("recording", false);
        String progScene = st.optString("programScene", "");
        JSONArray scenes = st.optJSONArray("scenes");

        // Status line
        TextView statusTv = new TextView(ctx);
        statusTv.setText((streaming ? "EN VIVO" : "OFFLINE") + "  |  " + (recording ? "GRABANDO" : "SIN GRAB"));
        statusTv.setTextColor(streaming ? Color.parseColor("#F87171") : Color.parseColor("#94A3B8"));
        statusTv.setTextSize(11f);
        statusTv.setPadding(0, Math.round(4 * d), 0, Math.round(6 * d));
        container.addView(statusTv);

        // Scene list inside scrollable container
        ScrollView sv = new ScrollView(ctx);
        LinearLayout scList = new LinearLayout(ctx);
        scList.setOrientation(LinearLayout.VERTICAL);
        if (scenes != null) {
            for (int i = 0; i < scenes.length(); ++i) {
                String nm = scenes.optString(i, "");
                if (nm.isEmpty()) continue;
                boolean isActive = nm.equals(progScene);
                TextView btn = makeActionChip(ctx, nm, isActive ? Color.parseColor("#DC2626") : Color.parseColor("#1E293B"), () -> {
                    sBg.execute(() -> {
                        try {
                            JSONObject act = new JSONObject();
                            act.put("action", "switch_scene");
                            act.put("scene", nm);
                            NativeBridge.remoteExecuteAction(act.toString());
                            String updated = NativeBridge.remoteGetState();
                            if (updated != null && !updated.isEmpty()) sCachedStateJson = updated;
                        } catch (Throwable ignored) {}
                        sMain.post(FloatingSceneOverlay::refreshExpandedPanel);
                    });
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = Math.round(4 * d);
                scList.addView(btn, lp);
            }
        }
        sv.addView(scList);
        container.addView(sv, new LinearLayout.LayoutParams(panelW, Math.round(150 * d)));

        // Quick broadcast actions
        LinearLayout qRow = new LinearLayout(ctx);
        qRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT);
        qLp.topMargin = Math.round(6 * d);

        TextView bStream = makeActionChip(ctx, streaming ? "Parar Vivo" : "En Vivo",
            streaming ? Color.parseColor("#DC2626") : Color.parseColor("#2563EB"), () -> {
                sBg.execute(() -> {
                    try {
                        NativeBridge.remoteExecuteAction("{\"action\":\"toggle_stream\"}");
                        String updated = NativeBridge.remoteGetState();
                        if (updated != null && !updated.isEmpty()) sCachedStateJson = updated;
                    } catch (Throwable ignored) {}
                    sMain.post(FloatingSceneOverlay::refreshExpandedPanel);
                });
            });
        TextView bRec = makeActionChip(ctx, recording ? "Parar Grab" : "Grabar",
            recording ? Color.parseColor("#D97706") : Color.parseColor("#334155"), () -> {
                sBg.execute(() -> {
                    try {
                        NativeBridge.remoteExecuteAction("{\"action\":\"toggle_record\"}");
                        String updated = NativeBridge.remoteGetState();
                        if (updated != null && !updated.isEmpty()) sCachedStateJson = updated;
                    } catch (Throwable ignored) {}
                    sMain.post(FloatingSceneOverlay::refreshExpandedPanel);
                });
            });
        LinearLayout.LayoutParams half1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half1.rightMargin = Math.round(4 * d);
        qRow.addView(bStream, half1);
        qRow.addView(bRec, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        container.addView(qRow, qLp);

        LinearLayout qRow2 = new LinearLayout(ctx);
        qRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams qLp2 = new LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT);
        qLp2.topMargin = Math.round(4 * d);
        TextView bLt = makeActionChip(ctx, "Lower Third", Color.parseColor("#334155"), () -> {
            LiveGraphicsManager.executeActionJson(ctx, "{\"action\":\"lt_toggle\"}");
        });
        TextView bOpen = makeActionChip(ctx, "Abrir OBS", Color.parseColor("#0F766E"), () -> {
            try {
                Intent i = new Intent(ctx, QtObsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                ctx.startActivity(i);
            } catch (Throwable ignored) {}
        });
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.rightMargin = Math.round(4 * d);
        qRow2.addView(bLt, half2);
        qRow2.addView(bOpen, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        container.addView(qRow2, qLp2);
    }

    private static TextView makeActionChip(Context ctx, String text, int bgColor, Runnable onClick) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Math.round(8 * d), Math.round(7 * d), Math.round(8 * d), Math.round(7 * d));
        tv.setBackground(roundedBg(bgColor, 8 * d, 0, 0));
        tv.setOnClickListener(v -> { if (onClick != null) onClick.run(); });
        return tv;
    }

    private static void attachDragAndTap(View v, Runnable onTap) {
        v.setOnTouchListener(new View.OnTouchListener() {
            int initX, initY;
            float downX, downY;
            boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent ev) {
                if (sParams == null || sWm == null || sRootView == null) return false;
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = sParams.x; initY = sParams.y;
                        downX = ev.getRawX(); downY = ev.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(ev.getRawX() - downX);
                        int dy = Math.round(ev.getRawY() - downY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true;
                        sParams.x = initX + dx;
                        sParams.y = initY + dy;
                        try { sWm.updateViewLayout(sRootView, sParams); } catch (Throwable ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved && onTap != null) onTap.run();
                        return true;
                }
                return false;
            }
        });
    }

    public static void hide() {
        sMain.removeCallbacks(sPollRunnable);
        if (sWm != null && sRootView != null) {
            try { sWm.removeView(sRootView); } catch (Throwable ignored) {}
        }
        sRootView = null;
        sShowing = false;
        sExpanded = false;
    }

    private FloatingSceneOverlay() {}
}
