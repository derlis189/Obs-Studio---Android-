// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

/** Limits auxiliary work only; output/encoder resolution and FPS are independent. */
final class ThermalBudget {
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
