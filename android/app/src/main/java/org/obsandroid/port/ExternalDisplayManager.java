package org.obsandroid.port;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

final class ExternalDisplayManager {
    private static final String TAG = "ExternalDisplayManager";
    private static ObsExternalPresentation sPresentation;
    private static int sActiveDisplayId = -1;

    private static final class ObsExternalPresentation extends Presentation {
        private SurfaceView surfaceView;

        ObsExternalPresentation(Context outerContext, Display display) {
            super(outerContext, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (getWindow() != null) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.BLACK);
            surfaceView = new SurfaceView(getContext());
            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    int w = surfaceView.getWidth();
                    int h = surfaceView.getHeight();
                    if (w > 0 && h > 0) {
                        NativeBridge.attachExternalDisplay(holder.getSurface(), w, h);
                    }
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    if (width > 0 && height > 0) {
                        NativeBridge.attachExternalDisplay(holder.getSurface(), width, height);
                    }
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    NativeBridge.detachExternalDisplay();
                }
            });
            root.addView(surfaceView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(root);
        }
    }

    private static Map<Integer, Display> collectExternalDisplays(DisplayManager dm) {
        Map<Integer, Display> result = new LinkedHashMap<>();
        if (dm == null) return result;
        Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (pres != null) {
            for (Display d : pres) {
                if (d != null && d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    result.put(d.getDisplayId(), d);
                }
            }
        }
        Display[] all = dm.getDisplays();
        if (all != null) {
            for (Display d : all) {
                if (d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
                int flags = d.getFlags();
                if ((flags & Display.FLAG_PRIVATE) != 0) continue;
                if (!result.containsKey(d.getDisplayId())) {
                    result.put(d.getDisplayId(), d);
                }
            }
        }
        return result;
    }

    static void openWirelessDisplaySettings(Activity activity) {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            String[] actions = new String[] {
                Settings.ACTION_CAST_SETTINGS,
                "android.settings.WIFI_DISPLAY_SETTINGS",
                Settings.ACTION_DISPLAY_SETTINGS
            };
            for (String action : actions) {
                try {
                    Intent intent = new Intent(action);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                    return;
                } catch (Throwable ignored) {
                }
            }
        });
    }

    static String listExternalDisplaysJson(Context context) {
        JSONArray arr = new JSONArray();
        if (context == null) return arr.toString();
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            for (Display d : collectExternalDisplays(dm).values()) {
                Point size = new Point();
                d.getRealSize(size);
                JSONObject o = new JSONObject();
                o.put("id", d.getDisplayId());
                String name = d.getName() != null ? d.getName() : ("Pantalla " + d.getDisplayId());
                o.put("name", name);
                o.put("width", size.x);
                o.put("height", size.y);
                o.put("active", d.getDisplayId() == sActiveDisplayId && sPresentation != null && sPresentation.isShowing());
                arr.put(o);
            }
        } catch (Throwable t) {
            Log.w(TAG, "listExternalDisplaysJson failed: " + t.getMessage());
        }
        return arr.toString();
    }

    static boolean start(Activity activity, int displayId) {
        if (activity == null) return false;
        DisplayManager dm = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return false;
        Display target = null;
        for (Display d : collectExternalDisplays(dm).values()) {
            if (displayId < 0 || d.getDisplayId() == displayId) {
                target = d;
                break;
            }
        }
        if (target == null) return false;
        final Display chosen = target;
        final boolean[] ok = {false};
        Runnable task = () -> {
            try {
                stopOnUiThread();
                ObsExternalPresentation p = new ObsExternalPresentation(activity, chosen);
                p.setOnDismissListener(dialog -> {
                    NativeBridge.detachExternalDisplay();
                    if (sPresentation == p) {
                        sPresentation = null;
                        sActiveDisplayId = -1;
                    }
                });
                p.show();
                sPresentation = p;
                sActiveDisplayId = chosen.getDisplayId();
                ok[0] = true;
            } catch (Throwable t) {
                Log.e(TAG, "Failed to show external Presentation: " + t.getMessage());
                stopOnUiThread();
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            task.run();
        } else {
            final Object latch = new Object();
            final boolean[] done = {false};
            activity.runOnUiThread(() -> {
                try {
                    task.run();
                } finally {
                    synchronized (latch) {
                        done[0] = true;
                        latch.notifyAll();
                    }
                }
            });
            synchronized (latch) {
                long deadline = System.currentTimeMillis() + 3000;
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    try {
                        latch.wait(200);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
        }
        return ok[0];
    }

    static void stop(Activity activity) {
        if (activity == null) {
            NativeBridge.detachExternalDisplay();
            sPresentation = null;
            sActiveDisplayId = -1;
            return;
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            stopOnUiThread();
        } else {
            activity.runOnUiThread(ExternalDisplayManager::stopOnUiThread);
        }
    }

    private static void stopOnUiThread() {
        try {
            if (sPresentation != null) {
                sPresentation.dismiss();
            }
        } catch (Throwable ignored) {
        }
        sPresentation = null;
        sActiveDisplayId = -1;
        NativeBridge.detachExternalDisplay();
    }

    private ExternalDisplayManager() {}
}
