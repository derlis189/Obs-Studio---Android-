// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

public final class ThermalBudgetTest {
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        int previousPreview = 30, previousBrowser = 60;
        for (int status = 0; status <= 6; ++status) {
            int preview = ThermalBudget.previewFps(status), browser = ThermalBudget.browserFpsCap(status);
            check(preview <= previousPreview && browser <= previousBrowser, "Hotter must not raise limits");
            check(preview >= 5 && preview <= 15, "Preview budget bounds");
            for (int requested : new int[]{1, 15, 24, 30, 60}) {
                int interval = ThermalBudget.captureIntervalMs(requested, browser);
                check(interval * Math.min(requested, browser) >= 1000, "Capture exceeds requested/capped FPS");
            }
            previousPreview = preview;
            previousBrowser = browser;
        }
        check(ThermalBudget.previewFps(0) == 15 && ThermalBudget.previewFps(3) == 10, "Baseline/severe preview");
        check(ThermalBudget.browserFpsCap(3) == 15, "Severe browser");
        check(ThermalBudget.captureIntervalMs(0, 0) == 1000, "Invalid FPS clamped");

        BrowserFrameGate gate = new BrowserFrameGate();
        check(!gate.hasFreshFrame(), "Static source must not copy repeatedly");
        gate.changed();
        long copying = gate.revision();
        gate.changed(); // A new frame arrives while the first PixelCopy is outstanding.
        gate.delivered(copying);
        check(gate.hasFreshFrame(), "An in-flight copy consumed a newer frame");
        gate.delivered(gate.revision());
        check(!gate.hasFreshFrame(), "A delivered frame is copied twice");
        Thread a = new Thread(() -> { for (int i=0; i<10000; ++i) gate.changed(); });
        Thread b = new Thread(() -> { for (int i=0; i<10000; ++i) gate.changed(); });
        a.start(); b.start(); a.join(); b.join();
        check(gate.revision() == 20002, "Concurrent notifications were lost");
        check(gate.hasFreshFrame(), "Concurrent frames not pending");
        System.out.println("THERMAL_POLICY_PASS: limits, caps, static frames and in-flight/concurrent notifications");
    }
}
