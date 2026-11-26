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
    private static final String KEY_X = "floating_overlay_x";
    private static final String KEY_Y = "floating_overlay_y";
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService sBg =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OBS-FloatBg");
            t.setDaemon(true);
            return t;
        });
    private static volatile String sCachedStateJson = "{}";
    private static volatile boolean sPendingStreamPicker = false;

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

    public static void onAppForegrounded(Activity activity) {
        sMain.post(() -> {
            hide();
            if (!sPendingStreamPicker || activity == null || activity.isFinishing()) return;
            sPendingStreamPicker = false;
            sMain.postDelayed(() -> sBg.execute(() -> {
                try {
                    NativeBridge.remoteExecuteAction("{\"action\":\"pick_stream_destinations\"}");
                } catch (Throwable t) {
                    Log.e(TAG, "Could not open stream destination picker", t);
                    sMain.post(() -> Toast.makeText(activity, "No se pudo abrir la selección de destinos", Toast.LENGTH_SHORT).show());
                }
            }), 450L);
        });
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
            SharedPreferences position = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sParams.x = position.getInt(KEY_X, Math.round(16 * d));
            sParams.y = position.getInt(KEY_Y, Math.round(120 * d));

            LinearLayout container = new LinearLayout(ctx);
            container.setOrientation(LinearLayout.VERTICAL);
            sRootView = container;
            sExpanded = false;
            rebuildView(ctx, container);

            sWm.addView(sRootView, sParams);
            sShowing = true;
            sMain.post(sPollRunnable);
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
            pill.setMinimumHeight(Math.round(48 * d));
            pill.setBackground(roundedBg(Color.parseColor("#292B31"), 8 * d, Color.parseColor("#454851"), Math.round(d)));

            ImageView logoIv = new ImageView(ctx);
            logoIv.setImageResource(R.drawable.obs_splash_logo);
            int iconPx = Math.round(22 * d);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconPx, iconPx);
            iconLp.rightMargin = Math.round(6 * d);
            pill.addView(logoIv, iconLp);

            TextView label = new TextView(ctx);
            label.setText("OBS  ·  CONTROL");
            label.setTextColor(Color.WHITE);
            label.setTextSize(13f);
            label.setTypeface(null, android.graphics.Typeface.BOLD);
            pill.addView(label);

            attachDragAndTap(pill, () -> {
                sExpanded = true;
                rebuildView(ctx, container);
                sMain.removeCallbacks(sPollRunnable);
                sMain.post(sPollRunnable);
            });
            container.addView(pill);
        } else {
            int pad = Math.round(10 * d);
            container.setPadding(pad, pad, pad, pad);
            container.setBackground(roundedBg(Color.parseColor("#202127"), 8 * d, Color.parseColor("#41434A"), Math.round(d)));
            populateExpanded(ctx, container);
        }
        try {
            if (sWm != null && sRootView != null && sRootView.isAttachedToWindow()) {
                sWm.updateViewLayout(sRootView, sParams);
                sRootView.post(() -> clampAndApplyPosition(ctx, false));
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
        int screenWidthDp = Math.round(ctx.getResources().getDisplayMetrics().widthPixels / d);
        int panelW = Math.round(Math.min(320, Math.max(220, screenWidthDp - 28)) * d);

        JSONObject st;
        try { st = new JSONObject(sCachedStateJson != null ? sCachedStateJson : "{}"); }
        catch (Throwable t) { st = new JSONObject(); }
        boolean streaming = st.optBoolean("streaming", false);
        boolean recording = st.optBoolean("recording", false);
        String progScene = st.optString("programScene", "");
        JSONArray scenes = st.optJSONArray("scenes");

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Math.round(3 * d), 0, 0, Math.round(7 * d));
        LinearLayout brand = new LinearLayout(ctx);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(ctx);
        logo.setImageResource(R.drawable.obs_splash_logo);
        int icon = Math.round(20 * d);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(icon, icon);
        iconLp.rightMargin = Math.round(7 * d);
        brand.addView(logo, iconLp);
        TextView title = new TextView(ctx);
        title.setText("OBS Studio");
        title.setTextColor(Color.parseColor("#F2F2F3"));
        title.setTextSize(14f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        brand.addView(title);
        header.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        attachDragAndTap(brand, null);

        TextView minimize = makeActionChip(ctx, "—", Color.parseColor("#34363D"), () -> {
            sExpanded = false;
            sMain.removeCallbacks(sPollRunnable);
            container.setPadding(0, 0, 0, 0);
            container.setBackground(null);
            rebuildView(ctx, container);
        });
        minimize.setContentDescription("Minimizar controles OBS");
        header.addView(minimize);
        TextView close = makeActionChip(ctx, "×", Color.parseColor("#34363D"), () -> {
            setEnabled(ctx, false);
            hide();
            Toast.makeText(ctx, "Control flotante desactivado", Toast.LENGTH_SHORT).show();
        });
        close.setContentDescription("Desactivar control flotante");
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = Math.round(4 * d);
        header.addView(close, closeLp);
        container.addView(header, new LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout statusRow = new LinearLayout(ctx);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(Math.round(8 * d), Math.round(5 * d), Math.round(8 * d), Math.round(5 * d));
        statusRow.setBackground(roundedBg(Color.parseColor("#2B2D33"), 5 * d, 0, 0));
        TextView dot = new TextView(ctx);
        dot.setText("●");
        dot.setTextColor(streaming ? Color.parseColor("#F04452") : Color.parseColor("#747780"));
        dot.setTextSize(12f);
        TextView status = new TextView(ctx);
        String stateText = streaming ? "TRANSMITIENDO" : "TRANSMISIÓN DETENIDA";
        if (recording) stateText += "  ·  GRABANDO";
        status.setText(stateText);
        status.setTextColor(Color.parseColor("#D7D8DB"));
        status.setTextSize(10f);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        status.setPadding(Math.round(6 * d), 0, 0, 0);
        statusRow.addView(dot);
        statusRow.addView(status);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.bottomMargin = Math.round(6 * d);
        container.addView(statusRow, statusLp);

        TextView sceneHeader = new TextView(ctx);
        sceneHeader.setText("ESCENAS · PROGRAMA");
        sceneHeader.setTextColor(Color.parseColor("#AEB0B5"));
        sceneHeader.setTextSize(10f);
        sceneHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        sceneHeader.setPadding(Math.round(3 * d), Math.round(2 * d), 0, Math.round(3 * d));
        container.addView(sceneHeader);
        ScrollView sceneScroll = new ScrollView(ctx);
        sceneScroll.setVerticalScrollBarEnabled(false);
        LinearLayout sceneList = new LinearLayout(ctx);
        sceneList.setOrientation(LinearLayout.VERTICAL);
        int sceneCount = 0;
        if (scenes != null) {
            for (int i = 0; i < scenes.length(); i++) {
                String scene = scenes.optString(i, "");
                if (scene.isEmpty()) continue;
                sceneCount++;
                boolean active = scene.equals(progScene);
                TextView sceneButton = makeActionChip(ctx, active ? "●  " + scene : scene,
                    active ? Color.parseColor("#3559B8") : Color.parseColor("#303238"),
                    () -> runRemoteAction(ctx,
                        "{\"action\":\"switch_scene\",\"scene\":" + JSONObject.quote(scene) + "}",
                        "Escena cambiada", "No se pudo cambiar la escena"));
                sceneButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                sceneButton.setSingleLine(true);
                sceneButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams sceneLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(38 * d));
                sceneLp.bottomMargin = Math.round(3 * d);
                sceneList.addView(sceneButton, sceneLp);
            }
        }
        if (sceneCount == 0) {
            TextView empty = new TextView(ctx);
            empty.setText("No se cargaron escenas. Toca para actualizar.");
            empty.setTextColor(Color.parseColor("#AEB0B5"));
            empty.setTextSize(11f);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            empty.setPadding(Math.round(8 * d), 0, Math.round(8 * d), 0);
            empty.setOnClickListener(v -> refreshStateAsync(ctx));
            sceneList.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(44 * d)));
        }
        sceneScroll.addView(sceneList);
        int sceneHeight = Math.round(Math.min(sceneCount == 0 ? 44 : 120, Math.max(44, sceneCount * 41)) * d);
        container.addView(sceneScroll, new LinearLayout.LayoutParams(panelW, sceneHeight));

        LinearLayout firstActions = new LinearLayout(ctx);
        firstActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Math.round(6 * d);
        TextView streamButton = makeActionChip(ctx, streaming ? "Detener transmisión" : "Iniciar transmisión",
            streaming ? Color.parseColor("#C83E48") : Color.parseColor("#3559B8"), () -> {
                if (streaming)
                    runRemoteAction(ctx, "{\"action\":\"stop_all_streams\"}", "Transmisiones detenidas", "No se pudieron detener");
                else
                    requestStreamPicker(ctx);
            });
        TextView recordButton = makeActionChip(ctx, recording ? "Detener grabación" : "Iniciar grabación",
            recording ? Color.parseColor("#C83E48") : Color.parseColor("#34363D"),
            () -> runRemoteAction(ctx, "{\"action\":\"toggle_record\"}", "Grabación actualizada", "No se pudo cambiar la grabación"));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.rightMargin = Math.round(4 * d);
        firstActions.addView(streamButton, half);
        firstActions.addView(recordButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        container.addView(firstActions, rowLp);

        LinearLayout secondActions = new LinearLayout(ctx);
        secondActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = Math.round(4 * d);
        TextView lowerThird = makeActionChip(ctx, "Lower Third", Color.parseColor("#34363D"),
            () -> LiveGraphicsManager.executeActionJson(ctx, "{\"action\":\"lt_toggle\"}"));
        TextView openObs = makeActionChip(ctx, "Abrir OBS", Color.parseColor("#34363D"), () -> {
            try {
                Intent i = new Intent(ctx, QtObsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                ctx.startActivity(i);
            } catch (Throwable t) {
                Log.e(TAG, "Could not reopen OBS", t);
            }
        });
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.rightMargin = Math.round(4 * d);
        secondActions.addView(lowerThird, half2);
        secondActions.addView(openObs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        container.addView(secondActions, row2Lp);
    }

    private static TextView makeActionChip(Context ctx, String text, int bgColor, Runnable onClick) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Math.round(8 * d), Math.round(7 * d), Math.round(8 * d), Math.round(7 * d));
        tv.setMinHeight(Math.round(42 * d));
        tv.setBackground(roundedBg(bgColor, 5 * d, Color.parseColor("#464950"), Math.round(d)));
        tv.setOnClickListener(v -> { if (onClick != null) onClick.run(); });
        return tv;
    }

    private static void requestStreamPicker(Context ctx) {
        try {
            sPendingStreamPicker = true;
            Intent i = new Intent(ctx, QtObsActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            ctx.startActivity(i);
        } catch (Throwable t) {
            sPendingStreamPicker = false;
            Log.e(TAG, "Could not bring OBS to foreground", t);
            Toast.makeText(ctx, "No se pudo abrir OBS", Toast.LENGTH_SHORT).show();
        }
    }

    private static void runRemoteAction(Context ctx, String action, String success, String failure) {
        sBg.execute(() -> {
            boolean ok = false;
            try {
                String response = NativeBridge.remoteExecuteAction(action);
                ok = response != null && new JSONObject(response).optBoolean("ok", false);
                String updated = NativeBridge.remoteGetState();
                if (updated != null && !updated.isEmpty()) sCachedStateJson = updated;
            } catch (Throwable t) {
                Log.e(TAG, "Floating action failed", t);
            }
            final boolean succeeded = ok;
            sMain.post(() -> {
                if (!succeeded) Toast.makeText(ctx, failure, Toast.LENGTH_SHORT).show();
                else if (!success.isEmpty()) Toast.makeText(ctx, success, Toast.LENGTH_SHORT).show();
                refreshExpandedPanel();
            });
        });
    }

    private static void refreshStateAsync(Context ctx) {
        sBg.execute(() -> {
            try {
                String updated = NativeBridge.remoteGetState();
                if (updated != null && !updated.isEmpty()) sCachedStateJson = updated;
            } catch (Throwable t) {
                Log.w(TAG, "Could not refresh OBS state", t);
            }
            sMain.post(FloatingSceneOverlay::refreshExpandedPanel);
        });
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
                        clampAndApplyPosition(view.getContext(), false);
                        try { sWm.updateViewLayout(sRootView, sParams); } catch (Throwable ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) clampAndApplyPosition(view.getContext(), true);
                        if (!moved && onTap != null) onTap.run();
                        return true;
                }
                return false;
            }
        });
    }

    private static void clampAndApplyPosition(Context ctx, boolean snapToSide) {
        if (ctx == null || sParams == null || sRootView == null) return;
        float d = ctx.getResources().getDisplayMetrics().density;
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        int viewW = Math.max(sRootView.getWidth(), sRootView.getMeasuredWidth());
        int viewH = Math.max(sRootView.getHeight(), sRootView.getMeasuredHeight());
        int maxX = Math.max(0, screenW - viewW);
        int maxY = Math.max(Math.round(24 * d), screenH - viewH - Math.round(36 * d));
        sParams.x = Math.max(0, Math.min(maxX, sParams.x));
        sParams.y = Math.max(Math.round(24 * d), Math.min(maxY, sParams.y));
        if (snapToSide) {
            int margin = Math.round(8 * d);
            sParams.x = (sParams.x + viewW / 2 < screenW / 2) ? margin : Math.max(0, maxX - margin);
            if (sAppCtx != null) {
                sAppCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_X, sParams.x).putInt(KEY_Y, sParams.y).apply();
            }
        }
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
