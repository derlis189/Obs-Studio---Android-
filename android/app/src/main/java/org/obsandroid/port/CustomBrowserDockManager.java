// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.util.HashMap;
import java.util.Map;

/**
 * Hosts real hardware-accelerated Android WebViews synchronized to Qt OBSDock
 * panels ("Paneles de navegador personalizados"), preserving persistent cookies,
 * interactive chat typing, smooth scrolling, and automatic occlusion hiding
 * whenever OBS menus or modal dialogs open.
 */
public final class CustomBrowserDockManager {
    private static final String TAG = "ObsBrowserDock";
    private static final String DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final Map<Long, DockWebInstance> sDocks = new HashMap<>();
    private static FrameLayout sOverlayHost;
    private static volatile boolean sQtSuspended = false;
    private static volatile boolean sActivityPaused = false;

    private static final class DockWebInstance {
        final long id;
        WebView webView;
        String url = "";
        boolean desktopMode = false;
        int zoomPercent = 100;
        int x, y, w, h;
        boolean qtVisible = false;
        boolean destroyed = false;

        DockWebInstance(long id) {
            this.id = id;
        }
    }

    private static FrameLayout ensureOverlayHost(Activity activity) {
        if (sOverlayHost != null && sOverlayHost.isAttachedToWindow()) {
            return sOverlayHost;
        }
        View root = activity.findViewById(android.R.id.content);
        if (!(root instanceof ViewGroup)) {
            root = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        }
        if (!(root instanceof ViewGroup)) return null;
        FrameLayout host = new FrameLayout(activity);
        host.setClickable(false);
        host.setFocusable(false);
        host.setBackgroundColor(Color.TRANSPARENT);
        ((ViewGroup) root).addView(host, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sOverlayHost = host;
        return host;
    }

    private static String normalizeUrl(String raw) {
        if (raw == null) return "about:blank";
        String s = raw.trim();
        if (s.isEmpty()) return "about:blank";
        if (!s.contains("://") && !s.startsWith("about:")) {
            s = "https://" + s;
        }
        return s;
    }

    private static void applyEffectiveVisibility(DockWebInstance inst) {
        if (inst == null || inst.webView == null || inst.destroyed) return;
        boolean show = inst.qtVisible && !sQtSuspended && !sActivityPaused && inst.w >= 32 && inst.h >= 32;
        if (show) {
            if (inst.webView.getVisibility() != View.VISIBLE) {
                inst.webView.setVisibility(View.VISIBLE);
                inst.webView.onResume();
            }
        } else {
            if (inst.webView.getVisibility() != View.GONE) {
                inst.webView.setVisibility(View.GONE);
                inst.webView.onPause();
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void configureWebView(WebView wv, boolean desktopMode, int zoomPercent) {
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setTextZoom(Math.max(50, Math.min(200, zoomPercent)));
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        if (desktopMode) {
            ws.setUserAgentString(DESKTOP_UA);
        } else {
            ws.setUserAgentString(null);
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
    }

    public static void createOrUpdate(long id, String url, boolean desktopMode) {
        sMain.post(() -> {
            QtObsActivity activity = QtObsActivity.getActiveInstance();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            FrameLayout host = ensureOverlayHost(activity);
            if (host == null) return;

            String normalized = normalizeUrl(url);
            DockWebInstance inst = sDocks.get(id);
            boolean created = false;
            if (inst == null) {
                inst = new DockWebInstance(id);
                sDocks.put(id, inst);
                created = true;
            }
            if (inst.webView == null) {
                WebView wv = new WebView(activity);
                wv.setBackgroundColor(Color.parseColor("#181A20"));
                wv.setFocusable(true);
                wv.setFocusableInTouchMode(true);
                wv.setVisibility(View.GONE);
                configureWebView(wv, desktopMode, inst.zoomPercent);
                final DockWebInstance target = inst;
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        Uri u = request != null ? request.getUrl() : null;
                        if (u == null) return false;
                        String scheme = u.getScheme();
                        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme)) {
                            return false;
                        }
                        return true;
                    }

                    @Override
                    public void onPageFinished(WebView view, String finishedUrl) {
                        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
                        if (finishedUrl != null && finishedUrl.contains("twitch.tv/popout") && finishedUrl.contains("dashboard/live")) {
                            // Match desktop OBS Twitch Dashboard referrer helper
                            view.evaluateJavascript(
                                "try{if(!window.__obsRefSet){window.__obsRefSet=true;}}catch(e){}", null);
                        }
                    }

                    @Override
                    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                        if (request != null && request.isForMainFrame() && error != null) {
                            Log.w(TAG, "Dock " + target.id + " load error: " + error.getErrorCode() + " " + error.getDescription());
                        }
                    }
                });
                wv.setWebChromeClient(new WebChromeClient());
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1);
                lp.gravity = Gravity.TOP | Gravity.START;
                host.addView(wv, lp);
                inst.webView = wv;
                inst.desktopMode = desktopMode;
                inst.url = normalized;
                wv.loadUrl(normalized);
                Log.i(TAG, "Created custom browser dock id=" + id + " url=" + normalized);
            } else {
                boolean uaChanged = (inst.desktopMode != desktopMode);
                boolean urlChanged = !normalized.equals(inst.url);
                if (uaChanged) {
                    inst.desktopMode = desktopMode;
                    configureWebView(inst.webView, desktopMode, inst.zoomPercent);
                }
                if (urlChanged) {
                    inst.url = normalized;
                    inst.webView.loadUrl(normalized);
                } else if (uaChanged && !created) {
                    inst.webView.reload();
                }
            }
            applyEffectiveVisibility(inst);
        });
    }

    public static void setBounds(long id, int x, int y, int w, int h, boolean visible) {
        sMain.post(() -> {
            DockWebInstance inst = sDocks.get(id);
            if (inst == null || inst.webView == null || inst.destroyed) return;
            int hostW = sOverlayHost != null ? sOverlayHost.getWidth() : 0;
            int hostH = sOverlayHost != null ? sOverlayHost.getHeight() : 0;
            int cx = Math.max(0, x);
            int cy = Math.max(0, y);
            int cw = Math.max(0, w);
            int ch = Math.max(0, h);
            if (hostW > 0 && cx + cw > hostW) cw = Math.max(0, hostW - cx);
            if (hostH > 0 && cy + ch > hostH) ch = Math.max(0, hostH - cy);

            boolean changed = (inst.x != cx || inst.y != cy || inst.w != cw || inst.h != ch);
            inst.x = cx;
            inst.y = cy;
            inst.w = cw;
            inst.h = ch;
            inst.qtVisible = visible;
            if (changed && cw >= 32 && ch >= 32) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw, ch);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.leftMargin = cx;
                lp.topMargin = cy;
                inst.webView.setLayoutParams(lp);
            }
            applyEffectiveVisibility(inst);
        });
    }

    public static void navigate(long id, int action) {
        sMain.post(() -> {
            DockWebInstance inst = sDocks.get(id);
            if (inst == null || inst.webView == null || inst.destroyed) return;
            switch (action) {
                case 0: // reload
                    inst.webView.reload();
                    break;
                case 1: // back
                    if (inst.webView.canGoBack()) inst.webView.goBack();
                    break;
                case 2: // forward
                    if (inst.webView.canGoForward()) inst.webView.goForward();
                    break;
                case 3: // toggle desktop mode
                    inst.desktopMode = !inst.desktopMode;
                    configureWebView(inst.webView, inst.desktopMode, inst.zoomPercent);
                    inst.webView.reload();
                    break;
                case 4: // zoom in
                    inst.zoomPercent = Math.min(200, inst.zoomPercent + 15);
                    inst.webView.getSettings().setTextZoom(inst.zoomPercent);
                    break;
                case 5: // zoom out
                    inst.zoomPercent = Math.max(55, inst.zoomPercent - 15);
                    inst.webView.getSettings().setTextZoom(inst.zoomPercent);
                    break;
                case 6: // reset zoom
                    inst.zoomPercent = 100;
                    inst.webView.getSettings().setTextZoom(100);
                    break;
                default:
                    break;
            }
        });
    }

    public static void setQtSuspended(boolean suspended) {
        if (sQtSuspended == suspended) return;
        sQtSuspended = suspended;
        sMain.post(() -> {
            for (DockWebInstance inst : sDocks.values()) {
                applyEffectiveVisibility(inst);
            }
        });
    }

    public static void onActivityPaused(boolean paused) {
        sActivityPaused = paused;
        sMain.post(() -> {
            for (DockWebInstance inst : sDocks.values()) {
                applyEffectiveVisibility(inst);
            }
            if (paused) {
                try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
            }
        });
    }

    public static void destroy(long id) {
        sMain.post(() -> {
            DockWebInstance inst = sDocks.remove(id);
            if (inst == null) return;
            inst.destroyed = true;
            if (inst.webView != null) {
                ViewGroup p = (ViewGroup) inst.webView.getParent();
                if (p != null) p.removeView(inst.webView);
                try { inst.webView.loadUrl("about:blank"); } catch (Throwable ignored) {}
                try { inst.webView.destroy(); } catch (Throwable ignored) {}
                inst.webView = null;
            }
        });
    }

    public static void destroyAll() {
        sMain.post(() -> {
            for (Long id : sDocks.keySet().toArray(new Long[0])) {
                destroy(id);
            }
            if (sOverlayHost != null) {
                ViewGroup p = (ViewGroup) sOverlayHost.getParent();
                if (p != null) p.removeView(sOverlayHost);
                sOverlayHost = null;
            }
        });
    }

    private CustomBrowserDockManager() {}
}
