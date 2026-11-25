// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

final class ThermalController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static PowerManager power;
    private static int appliedStatus = -1;
    private static int latestStatus;
    private static volatile int previewLimit = 15;
    private static volatile int browserLimit = 60;
    private static final PowerManager.OnThermalStatusChangedListener LISTENER = ThermalController::onStatus;
    private static final Runnable RECOVER = () -> apply(latestStatus);

    static void start(Context context) {
        if (power != null) return;
        power = context.getApplicationContext().getSystemService(PowerManager.class);
        if (power == null) { apply(0); return; }
        try {
            latestStatus = power.getCurrentThermalStatus();
            apply(latestStatus);
            power.addThermalStatusListener(command -> MAIN.post(command), LISTENER);
        } catch (RuntimeException error) {
            apply(0);
            Log.w("ObsThermal", "Thermal listener unavailable; using baseline savings");
        }
    }

    private static void onStatus(int status) {
        if (power == null) return;
        latestStatus = Math.max(0, status);
        MAIN.removeCallbacks(RECOVER);
        if (latestStatus >= appliedStatus) apply(latestStatus);
        else MAIN.postDelayed(RECOVER, 20000); // Avoid rapid quality changes while cooling.
    }

    private static void apply(int status) {
        appliedStatus = status;
        previewLimit = ThermalBudget.previewFps(status);
        browserLimit = ThermalBudget.browserFpsCap(status);
        NativeBridge.setPreviewFpsLimit(previewLimit);
        BrowserSourceManager.setThermalFpsCap(browserLimit);
        Log.i("ObsThermal", "Auxiliary budget: thermal=" + status + " preview=" + previewLimit
            + " browserCap=" + browserLimit + " outputFPS=unchanged");
    }

    static int previewFpsLimit() { return previewLimit; }
    static int browserFpsLimit() { return browserLimit; }

    static void stop() {
        MAIN.removeCallbacks(RECOVER);
        if (power != null) {
            try { power.removeThermalStatusListener(LISTENER); } catch (RuntimeException ignored) {}
            power = null;
        }
        appliedStatus = -1;
    }

    private ThermalController() {}
}
