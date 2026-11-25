package org.obsandroid.port;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class EngineRuntime {
    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    static final AtomicInteger UI_CLIENTS = new AtomicInteger();
    static void releaseIfIdle() {
        EXECUTOR.execute(() -> { if (UI_CLIENTS.get() == 0 && !CaptureService.isCapturing()) NativeBridge.stop(); });
    }
    private EngineRuntime() {}
}
