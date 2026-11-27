// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebMessageCompat;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BrowserSourceManager {
    private static final String TAG = "ObsBrowserSource";
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final Map<Long, BrowserInstance> sInstances = new ConcurrentHashMap<>();
    private static volatile int sThermalFpsCap = 60;
    private static String audioScript;
    private static final HandlerThread sCaptureThread0 = new HandlerThread("OBS-BrowserCap0");
    private static final HandlerThread sCaptureThread1 = new HandlerThread("OBS-BrowserCap1");
    private static final Handler[] sCapturePool;
    static {
        sCaptureThread0.start();
        sCaptureThread1.start();
        sCapturePool = new Handler[]{
            new Handler(sCaptureThread0.getLooper()),
            new Handler(sCaptureThread1.getLooper())
        };
    }

    private static Handler captureHandlerFor(long id) {
        return sCapturePool[(int) (Math.abs(id) & 1L)];
    }

    private static int activeVisibleBrowserCount() {
        int n = 0;
        for (BrowserInstance bi : sInstances.values()) {
            if (bi != null && !bi.destroyed && bi.captureActive()) n++;
        }
        return Math.max(1, n);
    }

    private static final class BrowserInstance {
        final long id;
        final Object frameLock = new Object();
        WebView webView;
        Bitmap bitmap;
        Presentation presentation;
        FrameLayout presentationRoot;
        VirtualDisplay virtualDisplay;
        ImageReader displayReader;
        int surfaceWidth;
        int surfaceHeight;
        volatile boolean surfaceZeroCopy;
        volatile boolean vtuberAlphaPack;
        volatile boolean capturePending;
        volatile long capturedFrames;
        final BrowserFrameGate frameGate = new BrowserFrameGate();
        final AtomicBoolean tickQueued = new AtomicBoolean();
        volatile long nextCaptureAtMs;
        long lastCaptureReportMs;
        volatile long lastCaptureWarningMs;
        String url = "";
        String customCss = "";
        int width = 1280;
        int height = 720;
        int fps = 30;
        boolean transparent = true;
        volatile boolean captureAudio;
        PageAudioBridge audioBridge;
        volatile boolean visible = true;
        volatile boolean interacting = false;
        boolean pausePageWhenHidden;
        volatile boolean destroyed = false;
        Dialog interactDialog;
        Runnable onPageReady;

        final Runnable tickRunnable = new Runnable() {
            @Override
            public void run() {
                tickQueued.set(false);
                if (destroyed || !captureActive() || capturePending || !frameGate.hasFreshFrame()) return;
                long delayMs = nextCaptureAtMs - SystemClock.uptimeMillis();
                if (delayMs > 0) {
                    if (tickQueued.compareAndSet(false, true)) sMain.postDelayed(this, delayMs);
                    return;
                }
                captureFrame();
            }
        };

        BrowserInstance(long id) {
            this.id = id;
        }

        boolean captureActive() { return (id > 0 || vtuberAlphaPack) && (visible || interacting); }

        void markFrameChanged() {
            frameGate.changed();
            scheduleTick();
        }

        void cancelTick() {
            sMain.removeCallbacks(tickRunnable);
            tickQueued.set(false);
        }

        void updateVisibility() {
            boolean active = visible || interacting;
            if (webView != null) {
                if (active || !pausePageWhenHidden) webView.onResume();
                else webView.onPause();
            }
            if (webView != null && captureAudio) {
                webView.evaluateJavascript("if(window.__obsAudioCapture)window.__obsAudioCapture.context."
                    + (active ? "resume" : "suspend") + "().catch(function(){});", null);
            }
            if (presentation != null) {
                if (visible && !interacting) presentation.show();
                else presentation.hide();
            }
            if (active) {
                markFrameChanged();
            } else {
                cancelTick();
            }
            Log.i(TAG, "Browser capture " + (active ? "active" : "paused") + " id=" + id
                + " frames=" + capturedFrames + " pageKept=" + !pausePageWhenHidden);
        }

        void ensureBuffers(int w, int h) {
            int cw = Math.max(64, Math.min(3840, w));
            int ch = Math.max(64, Math.min(2160, h));
            synchronized (frameLock) {
                if (capturePending) return;
                if (bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() == cw && bitmap.getHeight() == ch) {
                    return;
                }
                if (bitmap != null) {
                    bitmap.recycle();
                    bitmap = null;
                }
                bitmap = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
                width = cw;
                height = ch;
            }
        }

        void releaseBuffers() {
            synchronized (frameLock) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                bitmap = null;
            }
        }

        void releasePresentation() {
            if (presentationRoot != null && webView != null && webView.getParent() == presentationRoot) {
                presentationRoot.removeView(webView);
            }
            if (presentation != null) presentation.dismiss();
            presentation = null;
            presentationRoot = null;
            if (virtualDisplay != null) virtualDisplay.release();
            virtualDisplay = null;
            if (displayReader != null) displayReader.close();
            displayReader = null;
        }

        boolean wantsZeroCopy() {
            return Build.VERSION.SDK_INT >= 29 && !interacting && ((id > 0 && !transparent) || (id < 0 && vtuberAlphaPack));
        }

        void ensurePresentation(Activity activity) {
            if (interacting || destroyed) return;
            boolean wantZc = wantsZeroCopy();
            int surfW = (id < 0 && vtuberAlphaPack && wantZc) ? Math.min(2560, width * 2) : width;
            int surfH = height;
            if (presentation != null && presentation.isShowing() &&
                    surfaceWidth == surfW && surfaceHeight == surfH && surfaceZeroCopy == wantZc) return;
            releasePresentation();
            try {
                // Chromium's supported offscreen path: an actual accelerated window
                // on a private display. For opaque sources (e.g. WebRTC video guests) and
                // alpha-packed VTuber WebGL2, use HardwareBuffer zero-copy GPU textures.
                ImageReader createdReader = null;
                boolean actualZc = false;
                if (wantZc && Build.VERSION.SDK_INT >= 29) {
                    try {
                        createdReader = ImageReader.newInstance(surfW, surfH, PixelFormat.RGBA_8888, 3,
                            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
                        actualZc = true;
                    } catch (Throwable ignored) {
                        createdReader = null;
                        actualZc = false;
                    }
                }
                if (createdReader == null) {
                    surfW = width;
                    createdReader = ImageReader.newInstance(surfW, surfH, PixelFormat.RGBA_8888, 3);
                }
                displayReader = createdReader;
                surfaceZeroCopy = actualZc;
                if (actualZc) {
                    releaseBuffers(); // Free unused CPU Bitmap RAM immediately in zero-copy mode
                }
                displayReader.setOnImageAvailableListener(reader -> {
                    // Always drain the display consumer, including while PixelCopy is busy.
                    // Blocking this queue would stall Android's shared render thread.
                    try (Image image = reader.acquireLatestImage()) {
                        if (image == null) return;
                        if (surfaceZeroCopy && !destroyed && captureActive() && !interacting && Build.VERSION.SDK_INT >= 29) {
                            long now = SystemClock.uptimeMillis();
                            if (now < nextCaptureAtMs) return;
                            HardwareBuffer hb = image.getHardwareBuffer();
                            if (hb != null) {
                                int activeCount = activeVisibleBrowserCount();
                                int effFps = fps;
                                if (activeCount >= 3) effFps = Math.min(effFps, 24);
                                long baseInterval = ThermalBudget.captureIntervalMs(effFps, sThermalFpsCap);
                                nextCaptureAtMs = now + baseInterval;
                                boolean accepted = false;
                                try {
                                    if (id < 0 && vtuberAlphaPack) {
                                        accepted = NativeBridge.pushVtuberHardwareBuffer(-id, hb, width, height, true);
                                    } else {
                                        accepted = NativeBridge.pushBrowserHardwareBuffer(id, hb, image.getWidth(), image.getHeight());
                                    }
                                } finally {
                                    hb.close();
                                }
                                if (accepted) {
                                    ++capturedFrames;
                                    if (capturedFrames == 1 || now - lastCaptureReportMs >= 10000) {
                                        lastCaptureReportMs = now;
                                        Log.i(TAG, "Zero-copy GPU " + (id < 0 ? "vtuber" : "browser") + " frames id=" + id
                                            + " frames=" + capturedFrames + " cap=" + Math.min(effFps, sThermalFpsCap));
                                    }
                                    return;
                                }
                            }
                        }
                        markFrameChanged();
                    } catch (IllegalStateException ignored) {
                        // The display can be released while a callback is queued.
                    }
                }, captureHandlerFor(id));
                DisplayManager manager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
                virtualDisplay = manager.createVirtualDisplay("OBS Browser " + id, surfW, surfH,
                    activity.getResources().getDisplayMetrics().densityDpi, displayReader.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
                if (virtualDisplay == null) throw new IllegalStateException("VirtualDisplay no disponible");
                presentation = new Presentation(activity, virtualDisplay.getDisplay(),
                    android.R.style.Theme_Translucent_NoTitleBar);
                presentation.setCancelable(false);
                Window window = presentation.getWindow();
                if (window == null) throw new IllegalStateException("Ventana de navegador no disponible");
                window.setBackgroundDrawable(new ColorDrawable(actualZc ? Color.BLACK : Color.TRANSPARENT));
                window.setFormat(actualZc ? PixelFormat.RGBA_8888 : PixelFormat.TRANSLUCENT);
                window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED |
                    WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                presentationRoot = new FrameLayout(presentation.getContext());
                ViewGroup parent = (ViewGroup) webView.getParent();
                if (parent != null) parent.removeView(webView);
                presentationRoot.addView(webView, new FrameLayout.LayoutParams(surfW, surfH));
                presentation.setContentView(presentationRoot);
                presentation.show();
                window.setLayout(surfW, surfH);
                surfaceWidth = surfW;
                surfaceHeight = surfH;
                Log.i(TAG, "Hardware browser window ready id=" + id + " " + surfW + "x" + surfH + " zeroCopy=" + actualZc);
                if (!actualZc) frameGate.changed();
            } catch (RuntimeException failure) {
                releasePresentation();
                warnCapture("No se pudo preparar la captura GPU: " + failure.getClass().getSimpleName());
            }
        }

        void warnCapture(String message) {
            long now = SystemClock.uptimeMillis();
            if (now - lastCaptureWarningMs >= 5000) {
                lastCaptureWarningMs = now;
                Log.w(TAG, "Browser id=" + id + ": " + message);
            }
        }

        void layoutOffscreen() {
            if (webView == null || interacting) return;
            int lw = (id < 0 && vtuberAlphaPack && surfaceZeroCopy) ? Math.min(2560, width * 2) : width;
            int lh = height;
            ViewGroup.LayoutParams lp = webView.getLayoutParams();
            if (lp == null || lp.width != lw || lp.height != lh) {
                webView.setLayoutParams(new FrameLayout.LayoutParams(lw, lh));
            }
            int wSpec = View.MeasureSpec.makeMeasureSpec(lw, View.MeasureSpec.EXACTLY);
            int hSpec = View.MeasureSpec.makeMeasureSpec(lh, View.MeasureSpec.EXACTLY);
            webView.measure(wSpec, hSpec);
            webView.layout(0, 0, lw, lh);
        }

        void injectCustomCss() {
            if (webView == null || destroyed) return;
            String css = customCss != null ? customCss : "";
            String b64 = Base64.encodeToString(css.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String js = "(function(){"
                + "var id='obs-android-custom-css';"
                + "var el=document.getElementById(id);"
                + "if(!el){el=document.createElement('style');el.id=id;(document.head||document.documentElement).appendChild(el);}"
                + "try{el.textContent=decodeURIComponent(escape(atob('" + b64 + "')));}catch(e){el.textContent=atob('" + b64 + "');}"
                + "})();";
            webView.evaluateJavascript(js, null);
        }

        void captureFrame() {
            if (destroyed || webView == null || capturePending || !captureActive() || !frameGate.hasFreshFrame()) return;
            if (surfaceZeroCopy && !interacting) return;
            try {
                QtObsActivity activity = QtObsActivity.getActiveInstance();
                if (activity == null || activity.isFinishing()) return;
                if (!interacting) ensurePresentation(activity);
                Window target = interacting && interactDialog != null ? interactDialog.getWindow()
                    : presentation != null ? presentation.getWindow() : null;
                if (target == null || !webView.isAttachedToWindow() ||
                        webView.getWidth() <= 0 || webView.getHeight() <= 0) {
                    // Retry shortly if layout hasn't completed yet so static pages don't stall
                    if (!destroyed && captureActive() && tickQueued.compareAndSet(false, true)) {
                        sMain.postDelayed(tickRunnable, 60);
                    }
                    return;
                }
                final Bitmap frameBitmap;
                synchronized (frameLock) {
                    if (destroyed || capturePending) return;
                    ensureBuffers(width, height);
                    frameBitmap = bitmap;
                    if (frameBitmap == null || frameBitmap.isRecycled()) return;
                    capturePending = true;
                }
                int[] location = new int[2];
                webView.getLocationInWindow(location);
                Rect rect = new Rect(location[0], location[1], location[0] + webView.getWidth(),
                    location[1] + webView.getHeight());
                final long copiedRevision = frameGate.revision();
                int activeCount = activeVisibleBrowserCount();
                int effFps = fps;
                if (activeCount >= 3) effFps = Math.min(effFps, 24);
                long baseInterval = ThermalBudget.captureIntervalMs(effFps, sThermalFpsCap);
                // Stagger multi-guest captures slightly so multiple PixelCopy calls don't collide on the same vsync
                long stagger = activeCount > 1 ? ((Math.abs(id) % activeCount) * Math.max(4L, baseInterval / (activeCount * 2L))) : 0L;
                nextCaptureAtMs = SystemClock.uptimeMillis() + baseInterval + (capturedFrames == 0 ? stagger : 0L);
                // Copy the window surface, before the virtual display compositor flattens
                // alpha against black. This preserves transparent HTML and captures video/WebGL.
                PixelCopy.request(target, rect, frameBitmap, result -> {
                    try {
                        synchronized (frameLock) {
                            if (!destroyed && captureActive() && result == PixelCopy.SUCCESS && !frameBitmap.isRecycled()) {
                                boolean accepted = id < 0 ? NativeBridge.pushVtuberBitmap(-id, frameBitmap)
                                    : NativeBridge.pushBrowserBitmap(id, frameBitmap);
                                if (accepted) {
                                    frameGate.delivered(copiedRevision);
                                    ++capturedFrames;
                                    long now = SystemClock.uptimeMillis();
                                    if (capturedFrames == 1 || now - lastCaptureReportMs >= 10000) {
                                        lastCaptureReportMs = now;
                                        Log.i(TAG, "GPU browser frames id=" + id + " frames=" + capturedFrames
                                            + " revision=" + copiedRevision + " cap=" + Math.min(fps, sThermalFpsCap));
                                    }
                                }
                            } else if (!destroyed && result != PixelCopy.SUCCESS) {
                                nextCaptureAtMs = SystemClock.uptimeMillis() + 250;
                                warnCapture("PixelCopy=" + result);
                            }
                        }
                    } catch (RuntimeException failure) {
                        if (!destroyed) warnCapture("Error al entregar el fotograma GPU");
                    } finally {
                        synchronized (frameLock) {
                            capturePending = false;
                            if (destroyed) releaseBuffers();
                        }
                        scheduleTick();
                    }
                }, captureHandlerFor(id));
            } catch (Throwable t) {
                synchronized (frameLock) {
                    capturePending = false;
                    if (destroyed) releaseBuffers();
                }
                nextCaptureAtMs = SystemClock.uptimeMillis() + 250;
                warnCapture("Error de captura: " + t.getClass().getSimpleName());
                scheduleTick();
            }
        }

        void scheduleTick() {
            if (!destroyed && captureActive() && !capturePending && frameGate.hasFreshFrame()
                    && tickQueued.compareAndSet(false, true)) {
                sMain.post(tickRunnable);
            }
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "deprecation"})
    private static WebView createConfiguredWebView(Activity activity, BrowserInstance inst) {
        WebView wv = new WebView(activity);
        WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        String ua = settings.getUserAgentString();
        if (ua != null) {
            ua = ua.replace("Mobile ", "").replace("; wv", "") + " OBS/31.0";
            settings.setUserAgentString(ua);
        }
        wv.setVerticalScrollBarEnabled(false);
        wv.setHorizontalScrollBarEnabled(false);
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        wv.setBackgroundColor(inst.transparent ? Color.TRANSPARENT : Color.BLACK);
        wv.getViewTreeObserver().addOnDrawListener(() -> {
            // The private display supplies frame notifications normally. During
            // interaction WebView belongs to the main dialog's window instead.
            if (inst.interacting) inst.markFrameChanged();
        });
        if (inst.id < 0 && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) {
            String origin = "http://127.0.0.1:" + WebRemoteServer.getPort();
            WebViewCompat.addWebMessageListener(wv, "ObsVtuberFrames", java.util.Collections.singleton(origin),
                (view, message, sourceOrigin, mainFrame, reply) -> {
                    if (inst.destroyed || !inst.visible || !mainFrame || !origin.equals(sourceOrigin.toString())
                            || message.getType() != WebMessageCompat.TYPE_ARRAY_BUFFER) { reply.postMessage("drop"); return; }
                    byte[] bytes = message.getArrayBuffer();
                    if (bytes.length >= 16 && bytes.length <= 1280 * 1280 * 4 + 16) {
                        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(bytes, 0, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        int magic = header.getInt(), w = header.getInt(), h = header.getInt(), flip = header.getInt();
                        if (magic == 0x4f425352 && w > 0 && h > 0 && w <= 1280 && h <= 1280
                                && bytes.length == w * h * 4 + 16) {
                            NativeBridge.pushVtuberArray(-inst.id, bytes, 16, w, h, flip != 0);
                        }
                    }
                    reply.postMessage("ok"); // One frame in flight, including the JNI handoff.
                });
        }
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                inst.injectCustomCss();
                if (inst.captureAudio && audioScript != null) view.evaluateJavascript(audioScript, null);
                if (inst.onPageReady != null) inst.onPageReady.run();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) Log.w(TAG, "Browser page error id=" + inst.id + " code=" + error.getErrorCode());
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) Log.w(TAG, "Browser HTTP error id=" + inst.id + " status=" + response.getStatusCode());
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(android.webkit.PermissionRequest request) {
                if (request == null) return;
                Uri originUri = request.getOrigin();
                String scheme = originUri != null && originUri.getScheme() != null ? originUri.getScheme().toLowerCase(java.util.Locale.US) : "";
                String host = originUri != null && originUri.getHost() != null ? originUri.getHost().toLowerCase(java.util.Locale.US) : "";
                boolean isLoopback = "http".equals(scheme) && ("127.0.0.1".equals(host) || "localhost".equals(host));
                boolean isTrustedWebRtc = "https".equals(scheme) && (
                    "vdo.ninja".equals(host) || host.endsWith(".vdo.ninja")
                    || "obs.ninja".equals(host) || host.endsWith(".obs.ninja")
                    || "meet.jit.si".equals(host) || host.endsWith(".jitsi.net")
                    || "127.0.0.1".equals(host) || "localhost".equals(host));
                if (!isLoopback && !isTrustedWebRtc) {
                    request.deny();
                    return;
                }
                java.util.List<String> allowed = new java.util.ArrayList<>();
                String[] reqRes = request.getResources();
                if (reqRes != null) {
                    for (String r : reqRes) {
                        if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                                || android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                            allowed.add(r);
                        }
                    }
                }
                if (!allowed.isEmpty()) {
                    request.grant(allowed.toArray(new String[0]));
                } else {
                    request.deny();
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    // URLs can contain room passwords or access tokens; never log the page text.
                    Log.w(TAG, "Browser JavaScript error id=" + inst.id + " line=" + consoleMessage.lineNumber());
                }
                return true;
            }
        });
        return wv;
    }

    public static void createOrUpdate(long id, String url, int width, int height, int fps,
                                      boolean transparent, String customCss, boolean visible, boolean pausePageWhenHidden, boolean captureAudio) {
        sMain.post(() -> {
            QtObsActivity activity = QtObsActivity.getActiveInstance();
            if (activity == null || activity.isFinishing()) return;
            BrowserInstance inst = sInstances.get(id);
            boolean isNew = (inst == null);
            if (isNew) {
                inst = new BrowserInstance(id);
                sInstances.put(id, inst);
            }
            inst.width = Math.max(64, Math.min(3840, width));
            inst.height = Math.max(64, Math.min(2160, height));
            inst.fps = Math.max(1, Math.min(60, fps));
            boolean audioChanged = inst.captureAudio != captureAudio;
            inst.captureAudio = captureAudio;
            inst.transparent = transparent;
            inst.visible = visible;
            inst.pausePageWhenHidden = pausePageWhenHidden;
            boolean cssChanged = (customCss != null && !customCss.equals(inst.customCss));
            inst.customCss = customCss != null ? customCss : "";
            String targetUrl = (url != null && !url.isEmpty()) ? url : "about:blank";
            boolean urlChanged = isNew || audioChanged || !targetUrl.equals(inst.url);
            inst.url = targetUrl;

            if (audioScript == null) {
                try (java.io.InputStream input = activity.getAssets().open("browser-audio.js")) {
                    java.io.ByteArrayOutputStream scriptBytes = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[4096]; int count;
                    while ((count = input.read(chunk)) != -1) scriptBytes.write(chunk, 0, count);
                    audioScript = scriptBytes.toString("UTF-8");
                } catch (java.io.IOException error) { Log.w(TAG, "Browser audio script unavailable"); }
            }
            if (inst.webView == null) {
                inst.webView = createConfiguredWebView(activity, inst);
                inst.audioBridge = new PageAudioBridge(inst);
                inst.webView.addJavascriptInterface(inst.audioBridge, "ObsPageAudio");
            } else {
                inst.webView.setBackgroundColor(inst.transparent ? Color.TRANSPARENT : Color.BLACK);
            }

            inst.ensurePresentation(activity);
            inst.layoutOffscreen();

            if (urlChanged) {
                Log.i(TAG, "Loading browser source id=" + id + " (" + inst.width + "x" + inst.height + " @" + inst.fps + "fps) host=" + Uri.parse(targetUrl).getHost());
                inst.audioBridge.reset();
                inst.webView.loadUrl(targetUrl);
            } else if (cssChanged) {
                inst.injectCustomCss();
            }

            inst.updateVisibility();
        });
    }

    // Negative IDs reserve an independent namespace for the local VTuber renderer.
    static void createVtuber(long id, String url, int width, int height, int fps, boolean zeroCopyAlphaPack,
                             java.util.function.Consumer<WebView> ready) {
        sMain.post(() -> {
            BrowserInstance existing = sInstances.get(-id);
            if (existing != null) existing.vtuberAlphaPack = zeroCopyAlphaPack;
        });
        createOrUpdate(-id, url, width, height, fps, !zeroCopyAlphaPack, "", true, true, false);
        sMain.post(() -> {
            BrowserInstance inst = sInstances.get(-id);
            if (inst == null || inst.destroyed) return;
            boolean changed = inst.vtuberAlphaPack != zeroCopyAlphaPack;
            inst.vtuberAlphaPack = zeroCopyAlphaPack;
            inst.onPageReady = () -> ready.accept(inst.webView);
            if (changed) {
                QtObsActivity activity = QtObsActivity.getActiveInstance();
                if (activity != null && !activity.isFinishing()) {
                    inst.ensurePresentation(activity);
                    inst.layoutOffscreen();
                }
            }
        });
    }

    public static void setVisible(long id, boolean visible) {
        sMain.post(() -> {
            BrowserInstance inst = sInstances.get(id);
            if (inst == null || inst.destroyed) return;
            inst.visible = visible;
            inst.updateVisibility();
        });
    }

    static void setThermalFpsCap(int cap) {
        sThermalFpsCap = Math.max(1, Math.min(60, cap));
        sMain.post(() -> {
            for (BrowserInstance inst : sInstances.values()) inst.scheduleTick();
        });
    }

    public static void reload(long id, boolean noCache) {
        sMain.post(() -> {
            BrowserInstance inst = sInstances.get(id);
            if (inst == null || inst.destroyed || inst.webView == null) return;
            if (noCache) {
                inst.webView.clearCache(false);
            }
            if (inst.url != null && !inst.url.isEmpty()) {
                inst.webView.loadUrl(inst.url);
            } else {
                inst.webView.reload();
            }
        });
    }

    public static void interact(long id) {
        sMain.post(() -> {
            QtObsActivity activity = QtObsActivity.getActiveInstance();
            BrowserInstance inst = sInstances.get(id);
            if (activity == null || activity.isFinishing() || inst == null || inst.destroyed || inst.webView == null) {
                return;
            }
            if (inst.interactDialog != null && inst.interactDialog.isShowing()) {
                return;
            }

            float density = activity.getResources().getDisplayMetrics().density;
            int pad = Math.round(8 * density);

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.parseColor("#1D1F27"));

            LinearLayout topBar = new LinearLayout(activity);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            topBar.setPadding(pad, Math.round(4 * density), pad, Math.round(4 * density));
            topBar.setBackgroundColor(Color.parseColor("#2A2E39"));

            TextView title = new TextView(activity);
            String hostOnly = null;
            if (inst.url != null && !inst.url.isEmpty()) {
                try { hostOnly = Uri.parse(inst.url).getHost(); } catch (Throwable ignored) {}
            }
            title.setText("Interactuar: " + (hostOnly != null && !hostOnly.isEmpty() ? hostOnly : "Navegador"));
            title.setTextColor(Color.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTextSize(14f);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            topBar.addView(title, titleLp);

            Button backBtn = new Button(activity);
            backBtn.setText("←");
            backBtn.setContentDescription("Atrás");
            backBtn.setOnClickListener(v -> {
                if (inst.webView != null && inst.webView.canGoBack()) inst.webView.goBack();
            });
            topBar.addView(backBtn);

            Button reloadBtn = new Button(activity);
            reloadBtn.setText("Recargar");
            reloadBtn.setOnClickListener(v -> {
                if (inst.webView != null) inst.webView.reload();
            });
            topBar.addView(reloadBtn);

            Button closeBtn = new Button(activity);
            closeBtn.setText("Cerrar");
            topBar.addView(closeBtn);

            root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout webSlot = new FrameLayout(activity);
            webSlot.setBackgroundColor(Color.parseColor("#12141A"));
            root.addView(webSlot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            ViewGroup parent = (ViewGroup) inst.webView.getParent();
            if (parent != null) parent.removeView(inst.webView);
            inst.interacting = true;
            if (inst.presentation != null) inst.presentation.hide();
            inst.webView.setFocusable(true);
            inst.webView.setFocusableInTouchMode(true);
            webSlot.addView(inst.webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(root);
            dialog.setCancelable(true);
            closeBtn.setOnClickListener(v -> dialog.dismiss());

            dialog.setOnDismissListener(d -> {
                inst.interactDialog = null;
                inst.interacting = false;
                if (!inst.destroyed && inst.webView != null) {
                    ViewGroup curParent = (ViewGroup) inst.webView.getParent();
                    if (curParent != null) curParent.removeView(inst.webView);
                    inst.webView.setFocusable(false);
                    inst.webView.setFocusableInTouchMode(false);
                    inst.releasePresentation();
                    inst.ensurePresentation(activity);
                    inst.layoutOffscreen();
                    inst.updateVisibility();
                }
                activity.reapplyImmersiveMode();
            });

            Window w = dialog.getWindow();
            if (w != null) {
                DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                w.setLayout(Math.round(dm.widthPixels * 0.92f), Math.round(dm.heightPixels * 0.90f));
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            inst.interactDialog = dialog;
            dialog.show();
            inst.updateVisibility();
        });
    }

    public static void destroy(long id) {
        sMain.post(() -> {
            BrowserInstance inst = sInstances.remove(id);
            if (inst == null) return;
            synchronized (inst.frameLock) {
                inst.destroyed = true;
            }
            inst.cancelTick();
            if (inst.interactDialog != null && inst.interactDialog.isShowing()) {
                inst.interactDialog.dismiss();
                inst.interactDialog = null;
            }
            inst.releasePresentation();
            if (inst.webView != null) {
                ViewGroup parent = (ViewGroup) inst.webView.getParent();
                if (parent != null) parent.removeView(inst.webView);
                inst.webView.stopLoading();
                inst.webView.loadUrl("about:blank");
                inst.webView.destroy();
                inst.webView = null;
            }
            synchronized (inst.frameLock) {
                if (!inst.capturePending) inst.releaseBuffers();
            }
        });
    }

    public static void destroyAll() {
        sMain.post(() -> {
            for (Long id : new HashMap<>(sInstances).keySet()) {
                destroy(id);
            }
        });
    }

    // The page receives only a bounded PCM sink for its own source: no files,
    // navigation, credentials, or application control are exposed to JavaScript.
    private static final class PageAudioBridge {
        final BrowserInstance instance;
        long base, frames, lastDelivery, nextAllowed;
        int previousRate;
        PageAudioBridge(BrowserInstance instance) { this.instance = instance; }
        synchronized void reset() { base = frames = lastDelivery = nextAllowed = 0; }
        @JavascriptInterface public synchronized void pcm(String encoded, int sampleRate, int channels) {
            if (!instance.captureAudio || instance.destroyed || !instance.captureActive() || instance.id <= 0
                    || encoded == null || encoded.length() > 12000 || sampleRate < 8000 || sampleRate > 96000
                    || channels < 1 || channels > 2) return;
            try {
                byte[] pcm = Base64.decode(encoded, Base64.DEFAULT);
                if (pcm.length < 128 || pcm.length > 8192 || pcm.length % (channels * 2) != 0) return;
                long now = System.nanoTime();
                if (now + 100_000_000L < nextAllowed) return;
                int count = pcm.length / (channels * 2);
                long duration = count * 1_000_000_000L / sampleRate;
                nextAllowed = Math.max(now - 100_000_000L, nextAllowed) + duration;
                if (base == 0 || sampleRate != previousRate || now - lastDelivery > 250_000_000L) {
                    base = now - duration; frames = 0; previousRate = sampleRate;
                }
                long timestamp = base + frames * 1_000_000_000L / sampleRate;
                NativeBridge.pushBrowserAudio(instance.id, pcm, channels, sampleRate, timestamp);
                frames += count; lastDelivery = now;
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private BrowserSourceManager() {}
}
