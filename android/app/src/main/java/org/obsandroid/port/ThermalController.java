// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

final class ThermalController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static PowerManager power;
    private static int appliedStatus = -1;
    private static int latestStatus;
    private static long coolSince;
    private static volatile float headroom = Float.NaN;
    private static volatile int previewLimit = 15;
    private static volatile int browserLimit = 60;
    private static final PowerManager.OnThermalStatusChangedListener LISTENER = ThermalController::onStatus;
    private static final Runnable POLL = new Runnable() {
        @Override public void run() {
            if (power == null) return;
            try {
                if (Build.VERSION.SDK_INT >= 30) headroom = power.getThermalHeadroom(10);
                onStatus(power.getCurrentThermalStatus());
            } catch (RuntimeException ignored) { }
            MAIN.postDelayed(this, 15000); // Android requires infrequent headroom requests.
        }
    };
    private static final Runnable RECOVER = () -> onStatus(latestStatus);

    static void start(Context context) {
        if (power != null) return;
        power = context.getApplicationContext().getSystemService(PowerManager.class);
        if (power == null) { apply(0); return; }
        try {
            latestStatus = power.getCurrentThermalStatus();
            apply(latestStatus);
            power.addThermalStatusListener(command -> MAIN.post(command), LISTENER);
            MAIN.post(POLL);
        } catch (RuntimeException error) {
            apply(0);
            Log.w("ObsThermal", "Thermal listener unavailable; using baseline savings");
        }
    }

    private static void onStatus(int status) {
        if (power == null) return;
        latestStatus = Math.max(0, status);
        int effective = ThermalBudget.effectiveStatus(latestStatus, headroom, power.isPowerSaveMode());
        MAIN.removeCallbacks(RECOVER);
        if (effective >= appliedStatus) {
            coolSince = 0;
            if (effective != appliedStatus) apply(effective);
        } else {
            long now = SystemClock.uptimeMillis();
            if (coolSince == 0) coolSince = now;
            long remaining = 20000 - (now - coolSince);
            if (remaining <= 0) { coolSince = 0; apply(effective); }
            else MAIN.postDelayed(RECOVER, remaining);
        }
    }

    private static void apply(int status) {
        appliedStatus = status;
        previewLimit = ThermalBudget.previewFps(status);
        browserLimit = ThermalBudget.browserFpsCap(status);
        int tier = status >= 4 ? 3 : (status >= 3 ? 2 : (status >= 2 ? 1 : 0));
        NativeBridge.setPreviewFpsLimit(previewLimit);
        NativeBridge.setThermalTier(tier);
        BrowserSourceManager.setThermalFpsCap(browserLimit);
        VtuberSourceManager.setThermalFpsCap(browserLimit);
        LiveGraphicsManager.setThermalTier(tier);
        Log.i("ObsThermal", "Auxiliary budget: thermal=" + status + " tier=" + tier + " preview=" + previewLimit
            + " browserCap=" + browserLimit + " outputFPS=unchanged");
    }

    static int previewFpsLimit() { return previewLimit; }
    static int browserFpsLimit() { return browserLimit; }
    static float thermalHeadroom() { return headroom; }

    static void stop() {
        MAIN.removeCallbacks(RECOVER);
        MAIN.removeCallbacks(POLL);
        if (power != null) {
            try { power.removeThermalStatusListener(LISTENER); } catch (RuntimeException ignored) {}
            power = null;
        }
        appliedStatus = -1;
        coolSince = 0;
        headroom = Float.NaN;
    }

    private ThermalController() {}
}
