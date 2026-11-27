// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

public final class WebRemoteServer {
    private static final String TAG = "ObsWebRemote";
    private static final String PREFS = "obs_web_remote";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_PORT = "port";
    private static final int DEFAULT_PORT = 4455;

    private static Context sAppCtx;
    private static ServerSocket sSocket;
    private static Thread sAcceptThread;
    private static ExecutorService sWorkers;
    private static volatile boolean sRunning = false;
    private static int sPort = DEFAULT_PORT;
    private static volatile String sToken = "";
    private static final String LOCAL_TOKEN = generateToken();

    static String getLocalToken() {
        return LOCAL_TOKEN;
    }

    private static boolean tokenMatches(String expected, String supplied) {
        return supplied != null && !expected.isEmpty() &&
            java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    public static synchronized void ensureStarted(Context ctx) {
        ensureStarted(ctx, false);
    }

    private static synchronized void ensureStarted(Context ctx, boolean lan) {
        if (ctx != null) sAppCtx = ctx.getApplicationContext();
        if (sAppCtx == null) return;
        if (sRunning) {
            if (!lan || sSocket.getInetAddress().isAnyLocalAddress()) return;
            stop();
        }
        SharedPreferences prefs = sAppCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sPort = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        // Ephemeral per-session LAN token: clear any legacy persisted token and rotate when LAN opens
        if (prefs.contains(KEY_TOKEN)) {
            prefs.edit().remove(KEY_TOKEN).apply();
        }
        if (lan || sToken == null || sToken.length() < 32) {
            sToken = generateToken();
        }
        try {
            ServerSocket listener = new ServerSocket(sPort, 32,
                lan ? null : InetAddress.getByName("127.0.0.1"));
            sSocket = listener;
            sRunning = true;
            ExecutorService workers = new java.util.concurrent.ThreadPoolExecutor(
                2, 16, 15, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(64));
            sWorkers = workers;
            sAcceptThread = new Thread(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket client = listener.accept();
                        try {
                            workers.execute(() -> handleClient(client));
                        } catch (java.util.concurrent.RejectedExecutionException busy) {
                            client.close();
                        }
                    } catch (IOException e) {
                        if (!listener.isClosed()) Log.w(TAG, "Accept error: " + e.getMessage());
                    }
                }
            }, "obs-web-remote");
            sAcceptThread.start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to bind Web Remote on port " + sPort, e);
            sRunning = false;
        }
    }

    public static synchronized void stop() {
        sRunning = false;
        if (sSocket != null) {
            try { sSocket.close(); } catch (IOException ignored) {}
            sSocket = null;
        }
        if (sWorkers != null) {
            sWorkers.shutdownNow();
            sWorkers = null;
        }
    }

    public static boolean isRunning() {
        return sRunning;
    }

    public static int getPort() {
        return sPort;
    }

    public static String getLocalLoopbackBase() {
        return "http://127.0.0.1:" + sPort;
    }

    private static String generateToken() {
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        return sb.toString();
    }

    public static synchronized String regenerateToken() {
        sToken = generateToken();
        return getRemoteUrl();
    }

    public static String getLanIpAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            for (NetworkInterface nif : Collections.list(ifaces)) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName().toLowerCase(Locale.US);
                boolean preferred = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap");
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (preferred) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
            if (fallback != null) return fallback;
        } catch (Throwable ignored) {}
        return "127.0.0.1";
    }

    public static String getRemoteUrl() {
        return "http://" + getLanIpAddress() + ":" + sPort + "/#token=" + sToken;
    }

    public static void showQrDialog(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            ensureStarted(activity, true);
            if (!isRunning()) {
                Toast.makeText(activity, "No se pudo iniciar el control remoto", Toast.LENGTH_LONG).show();
                ensureStarted(activity);
                return;
            }
            float d = activity.getResources().getDisplayMetrics().density;
            int pad = Math.round(14 * d);

            ScrollView scroll = new ScrollView(activity);
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setPadding(pad, pad, pad, pad);
            scroll.addView(root);

            TextView subtitle = new TextView(activity);
            subtitle.setText("Escanea el código QR desde otro teléfono, tablet o PC en la misma red Wi-Fi para controlar escenas, transmisión, grabación y audio en vivo.\nUsa solo una red Wi-Fi de confianza (HTTP local).");
            subtitle.setTextSize(13f);
            subtitle.setTextColor(Color.parseColor("#D0D4DC"));
            root.addView(subtitle);

            ImageView qrView = new ImageView(activity);
            qrView.setBackgroundColor(Color.WHITE);
            qrView.setContentDescription("Código QR de Control Remoto Web");
            int qrPx = Math.round(200 * d);
            LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrPx, qrPx);
            qrLp.topMargin = Math.round(10 * d);
            qrLp.bottomMargin = Math.round(8 * d);
            root.addView(qrView, qrLp);

            TextView urlText = new TextView(activity);
            urlText.setTextSize(13f);
            urlText.setTextColor(Color.parseColor("#6EA8FE"));
            urlText.setGravity(Gravity.CENTER);
            urlText.setTextIsSelectable(true);
            root.addView(urlText);

            Runnable refreshUi = () -> {
                String lanIp = getLanIpAddress();
                boolean hasLan = !"127.0.0.1".equals(lanIp);
                String url = getRemoteUrl();
                if (!sRunning) {
                    urlText.setText("Servidor detenido");
                    urlText.setTextColor(Color.parseColor("#F87171"));
                    qrView.setImageBitmap(null);
                } else if (!hasLan) {
                    urlText.setText("Sin conexión Wi-Fi/LAN detectada.\nConecta el teléfono a una red Wi-Fi y pulsa 'Nuevo QR'.");
                    urlText.setTextColor(Color.parseColor("#FBBF24"));
                    qrView.setImageBitmap(null);
                } else {
                    urlText.setText(url);
                    urlText.setTextColor(Color.parseColor("#6EA8FE"));
                    qrView.setImageBitmap(QrCodeGenerator.generateBitmap(url, 520));
                }
            };
            refreshUi.run();

            LinearLayout btnRow1 = new LinearLayout(activity);
            btnRow1.setOrientation(LinearLayout.HORIZONTAL);
            btnRow1.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = Math.round(10 * d);

            Button copyBtn = new Button(activity);
            copyBtn.setText("Copiar enlace");
            copyBtn.setOnClickListener(v -> {
                String url = getRemoteUrl();
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("OBS Control Remoto", url));
                    Toast.makeText(activity, "Enlace copiado al portapapeles", Toast.LENGTH_SHORT).show();
                }
            });

            Button shareBtn = new Button(activity);
            shareBtn.setText("Enviar enlace");
            shareBtn.setOnClickListener(v -> {
                String url = getRemoteUrl();
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_SUBJECT, "Control Remoto OBS Studio");
                share.putExtra(Intent.EXTRA_TEXT, "Controla OBS Studio desde tu navegador:\n" + url);
                activity.startActivity(Intent.createChooser(share, "Enviar enlace de Control Remoto OBS"));
            });

            Button regenBtn = new Button(activity);
            regenBtn.setText("Nuevo QR / Actualizar IP");
            regenBtn.setOnClickListener(v -> {
                regenerateToken();
                refreshUi.run();
                Toast.makeText(activity, "Clave y QR actualizados", Toast.LENGTH_SHORT).show();
            });

            LinearLayout.LayoutParams halfLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            btnRow1.addView(copyBtn, halfLp);
            btnRow1.addView(shareBtn, halfLp);
            root.addView(btnRow1, rowLp);
            LinearLayout.LayoutParams regenLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            regenLp.topMargin = Math.round(6 * d);
            root.addView(regenBtn, regenLp);

            AlertDialog dlg = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setIcon(R.mipmap.ic_launcher)
                .setTitle("Control Remoto Web (QR)")
                .setView(scroll)
                .setPositiveButton("Cerrar", null)
                .setNeutralButton("Desactivar LAN", (dialog, button) -> {
                    stop();
                    ensureStarted(activity);
                    Toast.makeText(activity, "Acceso LAN desactivado (solo modo local)", Toast.LENGTH_SHORT).show();
                })
                .create();
            dlg.setOnDismissListener(x -> {
                if (activity instanceof QtObsActivity) ((QtObsActivity) activity).reapplyImmersiveMode();
            });
            dlg.show();
        });
    }

    private static void handleClient(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(3000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int tail = 0, cur, headerEnd = -1;
            int totalRead = 0;
            while (totalRead < 16384 && (cur = in.read()) != -1) {
                headerBuf.write(cur);
                totalRead++;
                tail = (tail << 8) | cur;
                if (tail == 0x0d0a0d0a) {
                    headerEnd = totalRead;
                    break;
                }
            }
            if (headerEnd <= 0) return;

            String headerText = new String(headerBuf.toByteArray(), StandardCharsets.UTF_8);
            String[] lines = headerText.split("\r\n");
            if (lines.length == 0) return;
            String[] reqParts = lines[0].split(" ");
            if (reqParts.length < 2) return;
            String method = reqParts[0];
            String fullTarget = reqParts[1];
            String path = fullTarget;
            String query = "";
            int qIdx = fullTarget.indexOf('?');
            if (qIdx >= 0) {
                path = fullTarget.substring(0, qIdx);
                query = fullTarget.substring(qIdx + 1);
            }

            int contentLength = 0;
            String authToken = null;
            String hostHeader = null;
            for (int i = 1; i < lines.length; ++i) {
                String l = lines[i];
                int colon = l.indexOf(':');
                if (colon <= 0) continue;
                String k = l.substring(0, colon).trim().toLowerCase(Locale.US);
                String v = l.substring(colon + 1).trim();
                if ("content-length".equals(k)) {
                    try { contentLength = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                } else if ("x-obs-token".equals(k) && (authToken == null || authToken.isEmpty())) {
                    authToken = v;
                } else if ("host".equals(k)) {
                    hostHeader = v.toLowerCase(Locale.US);
                }
            }

            if (hostHeader != null && !isAllowedHostHeader(hostHeader)) {
                sendResponse(out, 403, "text/plain", "Forbidden Host".getBytes(StandardCharsets.UTF_8));
                return;
            }

            boolean isLoopback = s.getInetAddress() != null && s.getInetAddress().isLoopbackAddress();

            // Local loopback-only raw RGBA frame receiver from VTuber WebGL2 engine (VRM / Live2D)
            if (isLoopback && tokenMatches(LOCAL_TOKEN, authToken) && "POST".equals(method) && path.startsWith("/vtuber-frame/")) {
                long instId = 0;
                int fw = 0, fh = 0;
                boolean flip = false;
                try {
                    instId = Long.parseLong(path.substring(14));
                    fw = Integer.parseInt(extractQueryParam(query, "w"));
                    fh = Integer.parseInt(extractQueryParam(query, "h"));
                    flip = "1".equals(extractQueryParam(query, "flip"));
                } catch (Exception ignored) {}
                long expected = fw > 16384 || fh > 16384 ? -1 : 4L * fw * fh;
                if (instId > 0 && fw > 0 && fh > 0 && contentLength == expected && expected <= 16 * 1024 * 1024) {
                    byte[] raw = new byte[contentLength];
                    int off = 0;
                    while (off < contentLength) {
                        int r = in.read(raw, off, contentLength - off);
                        if (r < 0) break;
                        off += r;
                    }
                    if (off == contentLength) {
                        VtuberSourceManager.onWebGlRgbaFrame(instId, raw, fw, fh, flip);
                    }
                }
                sendResponse(out, 204, "text/plain", new byte[0]);
                return;
            }

            String body = "";
            if (contentLength > 0 && contentLength < 262144) {
                byte[] b = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int r = in.read(b, off, contentLength - off);
                    if (r < 0) break;
                    off += r;
                }
                body = new String(b, 0, off, StandardCharsets.UTF_8);
            }

            // Local loopback-only static file server for VTuber offline WebGL engine (VRM / Live2D / PNG)
            if (isLoopback && path.startsWith("/vtuber/")) {
                serveVtuberAsset(path.substring(8), out);
                return;
            }
            String modelPrefix = "/vtuber-file/" + LOCAL_TOKEN + "/";
            if (isLoopback && "GET".equals(method) && path.startsWith(modelPrefix)) {
                serveVtuberUserFile(path.substring(modelPrefix.length()), out);
                return;
            }

            if ("OPTIONS".equals(method)) {
                sendResponse(out, 204, "text/plain", new byte[0]);
                return;
            }

            if ("/".equals(path) || "/index.html".equals(path)) {
                serveAsset("web-remote/index.html", "text/html; charset=utf-8", out);
                return;
            }
            if ("/logo.png".equals(path) || "/favicon.ico".equals(path)) {
                serveAsset("web-remote/logo.png", "image/png", out);
                return;
            }

            if (!tokenMatches(sToken, authToken)) {
                sendResponse(out, 401, "application/json; charset=utf-8",
                    "{\"error\":\"Token inválido\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("/api/program.jpg".equals(path) && "GET".equals(method)) {
                byte[] jpg = captureProgramJpeg();
                if (jpg != null && jpg.length > 0) {
                    sendResponse(out, 200, "image/jpeg", jpg);
                } else {
                    sendResponse(out, 204, "text/plain", new byte[0]);
                }
                return;
            }

            if ("/api/state".equals(path) && "GET".equals(method)) {
                String json = NativeBridge.remoteGetState();
                if (json == null || json.isEmpty()) json = "{}";
                sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("/api/action".equals(path) && "POST".equals(method)) {
                String res = NativeBridge.remoteExecuteAction(body);
                if (res == null || res.isEmpty()) res = "{\"ok\":false}";
                sendResponse(out, 200, "application/json; charset=utf-8", res.getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("/api/graphics".equals(path) && "GET".equals(method)) {
                String json = LiveGraphicsManager.buildStateJsonObject(sAppCtx).toString();
                sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("/api/graphics".equals(path) && "POST".equals(method)) {
                String res = LiveGraphicsManager.executeActionJson(sAppCtx, body);
                sendResponse(out, 200, "application/json; charset=utf-8", res.getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("/api/bible/books".equals(path) && "GET".equals(method)) {
                sendResponse(out, 200, "application/json; charset=utf-8",
                    LiveGraphicsManager.getBibleBooksJson().getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("/api/bible/search".equals(path) && "GET".equals(method)) {
                String q = URLDecoder.decode(extractQueryParam(query, "q").replace("+", "%20"), "UTF-8");
                sendResponse(out, 200, "application/json; charset=utf-8",
                    LiveGraphicsManager.searchBibleJson(sAppCtx, q).getBytes(StandardCharsets.UTF_8));
                return;
            }

            sendResponse(out, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.d(TAG, "Client handler ended: " + t.getMessage());
        }
    }

    private static void serveVtuberAsset(String relPath, OutputStream out) throws IOException {
        if (sAppCtx == null || relPath.contains("..")) {
            sendResponse(out, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (serveAsset("vtuber/" + relPath, guessMime(relPath), out)) return;
        File customFile = "live2dcubismcore.min.js".equals(relPath) ? vtuberFile(relPath) : null;
        if (customFile != null && customFile.isFile()) {
            sendFileResponse(out, customFile);
            return;
        }
        sendResponse(out, 404, "text/plain", "Asset Not Found".getBytes(StandardCharsets.UTF_8));
    }

    private static boolean serveAsset(String path, String contentType, OutputStream out)
        throws IOException {
        InputStream asset;
        try {
            asset = sAppCtx.getAssets().open(path);
        } catch (IOException missing) {
            if (path.startsWith("web-remote/")) {
                sendResponse(out, 404, "text/plain", new byte[0]);
            }
            return false;
        }
        try (InputStream in = asset; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            sendResponse(out, 200, contentType, bytes.toByteArray());
            return true;
        }
    }

    private static final java.util.Map<String, File> registeredModels = new java.util.LinkedHashMap<>();

    static synchronized String registerVtuberModel(File model) {
        String name = model.getName().toLowerCase(Locale.ROOT);
        if (!model.isFile() || !(name.endsWith(".vrm") || name.endsWith(".glb") ||
                name.endsWith(".model3.json"))) return "";
        String key = generateToken();
        if (registeredModels.size() >= 256) {
            registeredModels.remove(registeredModels.keySet().iterator().next());
        }
        registeredModels.put(key, model);
        return getLocalLoopbackBase() + "/vtuber-file/" + LOCAL_TOKEN + "/registered/" + key + "/" + android.net.Uri.encode(model.getName());
    }

    private static synchronized File registeredModelFile(String path) throws IOException {
        String[] parts = path.split("/", 3);
        if (parts.length != 3) return null;
        File model = registeredModels.get(parts[1]);
        if (model == null) return null;
        if (parts[2].equals(model.getName())) return model;
        if (!model.getName().toLowerCase(Locale.ROOT).endsWith(".model3.json")) return null;
        String name = parts[2].toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".png") || name.endsWith(".moc3") || name.endsWith(".motion3.json") ||
              name.endsWith(".exp3.json") || name.endsWith(".physics3.json") ||
              name.endsWith(".pose3.json") || name.endsWith(".cdi3.json"))) return null;
        File parent = model.getParentFile();
        File file = new File(parent, parts[2]).getCanonicalFile();
        return file.getPath().startsWith(parent.getPath() + File.separator) ? file : null;
    }

    private static void serveVtuberUserFile(String encodedPath, OutputStream out) throws IOException {
        if (sAppCtx == null) {
            sendResponse(out, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String path = URLDecoder.decode(encodedPath.replace("+", "%2B"), "UTF-8");
        File f = path.startsWith("registered/") ? registeredModelFile(path) : vtuberFile(path);
        if (f == null || !f.isFile() || !f.canRead()) {
            sendResponse(out, 404, "text/plain", "File Not Found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        sendFileResponse(out, f);
    }

    private static File vtuberFile(String relative) throws IOException {
        File root = new File(sAppCtx.getFilesDir(), "obs-studio/vtuber").getCanonicalFile();
        File file = new File(root, relative).getCanonicalFile();
        return file.getPath().startsWith(root.getPath() + File.separator) ? file : null;
    }

    private static boolean isAllowedHostHeader(String hostHeader) {
        String h = hostHeader;
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            if (end > 1) h = h.substring(1, end);
        } else {
            int colon = h.lastIndexOf(':');
            if (colon > 0) h = h.substring(0, colon);
        }
        if ("127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h)) return true;
        // Allow direct IPv4 literals (LAN IPs) and block external DNS rebinding hostnames
        return h.matches("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$");
    }

    private static void sendFileResponse(OutputStream out, File f) throws IOException {
        String mime = guessMime(f.getName());
        String hdr = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: " + mime + "\r\n"
            + "Content-Length: " + f.length() + "\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "X-Frame-Options: SAMEORIGIN\r\n"
            + "Cache-Control: no-cache\r\n"
            + "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        out.flush();
    }

    private static String guessMime(String name) {
        String l = name.toLowerCase(Locale.US);
        if (l.endsWith(".html") || l.endsWith(".htm")) return "text/html; charset=utf-8";
        if (l.endsWith(".js") || l.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (l.endsWith(".json") || l.endsWith(".model3.json") || l.endsWith(".physics3.json")) return "application/json; charset=utf-8";
        if (l.endsWith(".css")) return "text/css; charset=utf-8";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".webp")) return "image/webp";
        if (l.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    private static String extractQueryParam(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return "";
    }

    private static void sendResponse(OutputStream out, int code, String contentType, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 204 ? "No Content" : code == 401 ? "Unauthorized" : code == 403 ? "Forbidden" : "Not Found";
        String hdr = "HTTP/1.1 " + code + " " + status + "\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "X-Frame-Options: SAMEORIGIN\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    private static long sLastProgJpgMs;
    private static byte[] sLastProgJpg;
    private static Bitmap sProgramBitmap;
    private static final Object sProgJpgLock = new Object();

    private static byte[] captureProgramJpeg() {
        synchronized (sProgJpgLock) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (sLastProgJpg != null && (now - sLastProgJpgMs) < 180L) return sLastProgJpg;
            try {
                int[] dims = new int[2];
                byte[] rgba = NativeBridge.captureProgramRgba(dims);
                int w = dims[0], h = dims[1];
                if (rgba == null || w <= 0 || h <= 0 || rgba.length < 4L * w * h) return null;
                if (sProgramBitmap == null || sProgramBitmap.getWidth() != w || sProgramBitmap.getHeight() != h) {
                    if (sProgramBitmap != null) sProgramBitmap.recycle();
                    sProgramBitmap = null;
                    sProgramBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                }
                sProgramBitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba, 0, w * h * 4));
                ByteArrayOutputStream out = new ByteArrayOutputStream(24576);
                if (!sProgramBitmap.compress(Bitmap.CompressFormat.JPEG, 72, out)) return null;
                sLastProgJpg = out.toByteArray();
                sLastProgJpgMs = now;
                return sLastProgJpg;
            } catch (Exception e) {
                return null;
            }
        }
    }


    private WebRemoteServer() {}
}
