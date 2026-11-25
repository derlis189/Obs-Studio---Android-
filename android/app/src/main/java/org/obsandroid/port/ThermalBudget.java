// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

/** Limits auxiliary work only; output/encoder resolution and FPS are independent. */
final class ThermalBudget {
    static int effectiveStatus(int status, float headroom, boolean powerSave) {
        int effective = Math.max(0, Math.min(6, status));
        // Headroom 1.0 corresponds to the severe throttling threshold. NaN
        // means the device cannot forecast; keep the reported Android status.
        if (!Float.isNaN(headroom) && headroom >= 0.9f) effective = Math.max(effective, 2);
        else if (!Float.isNaN(headroom) && headroom >= 0.7f) effective = Math.max(effective, 1);
        if (powerSave) effective = Math.max(effective, 1);
        return effective;
    }
    static int previewFps(int status) {
        if (status >= 4) return 5;
        if (status >= 3) return 10;
        if (status >= 2) return 12;
        return 15;
    }

    static int browserFpsCap(int status) {
        if (status >= 4) return 10;
        if (status >= 3) return 15;
        if (status >= 2) return 24;
        if (status >= 1) return 30;
        return 60;
    }

    static int captureIntervalMs(int requestedFps, int cap) {
        int fps = Math.max(1, Math.min(Math.min(60, requestedFps), Math.max(1, cap)));
        return (1000 + fps - 1) / fps;
    }

    private ThermalBudget() {}
}
