// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.util.Base64;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Lightweight RFC-6455 WebSocket server compatible with VTube Studio Plugin API 1.0
 * on localhost:8001 (loopback-only by default). Allows Stream Deck, SAMMI, MixItUp,
 * and VTuber community plugins to query model state, trigger hotkeys/gestures,
 * toggle expressions, and inject live face/body tracking parameters.
 */
public final class VtsApiServer {
    private static final String TAG = "ObsVtsApi";
    private static final int DEFAULT_PORT = 8001;
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static ServerSocket sServer;
    private static Thread sAcceptThread;
    private static ExecutorService sWorkers;
    private static volatile boolean sRunning = false;
    private static final Set<String> sValidTokens = ConcurrentHashMap.newKeySet();

    private static final String[][] HOTKEYS = {
        {"wave", "Saludar"},
        {"happy", "Feliz / Sonrisa"},
        {"peace", "Victoria (Paz)"},
        {"clap", "Aplaudir"},
        {"think", "Pensando"},
        {"dance", "Baile suave"},
        {"sad", "Tristeza"},
        {"angry", "Enojo"},
        {"surprised", "Sorpresa"},
        {"reset", "Pose neutral"}
    };

    public static synchronized void ensureStarted() {
        if (sRunning) return;
        try {
            ServerSocket srv = new ServerSocket(DEFAULT_PORT, 8, InetAddress.getByName("127.0.0.1"));
            sServer = srv;
            sRunning = true;
            ExecutorService pool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "OBS-VtsApiClient");
                t.setDaemon(true);
                return t;
            });
            sWorkers = pool;
            sAcceptThread = new Thread(() -> {
                Log.i(TAG, "VTube Studio compatible WebSocket API listening on ws://127.0.0.1:" + DEFAULT_PORT);
                while (sRunning && !srv.isClosed()) {
                    try {
                        Socket client = srv.accept();
                        pool.execute(() -> handleClient(client));
                    } catch (IOException e) {
                        if (sRunning && !srv.isClosed()) Log.w(TAG, "Accept error: " + e.getMessage());
                    }
                }
            }, "OBS-VtsApiAccept");
            sAcceptThread.setDaemon(true);
            sAcceptThread.start();
        } catch (IOException e) {
            Log.w(TAG, "VTS API server could not bind port " + DEFAULT_PORT + ": " + e.getMessage());
            sRunning = false;
        }
    }

    public static synchronized void stop() {
        sRunning = false;
        if (sServer != null) {
            try { sServer.close(); } catch (IOException ignored) {}
            sServer = null;
        }
        if (sWorkers != null) {
            sWorkers.shutdownNow();
            sWorkers = null;
        }
        sValidTokens.clear();
    }

    private static String randomToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xFF));
        return sb.toString();
    }

    private static void handleClient(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            // 1. Read HTTP WebSocket upgrade request
            ByteArrayOutputStream hdr = new ByteArrayOutputStream();
            int tail = 0, ch;
            while (hdr.size() < 8192 && (ch = in.read()) != -1) {
                hdr.write(ch);
                tail = (tail << 8) | ch;
                if (tail == 0x0d0a0d0a) break;
            }
            String req = new String(hdr.toByteArray(), StandardCharsets.UTF_8);
            String wsKey = null;
            for (String line : req.split("\r\n")) {
                int idx = line.indexOf(':');
                if (idx > 0 && "sec-websocket-key".equalsIgnoreCase(line.substring(0, idx).trim())) {
                    wsKey = line.substring(idx + 1).trim();
                }
            }
            if (wsKey == null || wsKey.isEmpty()) {
                out.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest((wsKey + WS_MAGIC).getBytes(StandardCharsets.UTF_8));
            String accept = Base64.encodeToString(sha1, Base64.NO_WRAP);
            String upgrade = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(upgrade.getBytes(StandardCharsets.UTF_8));
            out.flush();

            boolean authed = false;
            // 2. WebSocket frame loop
            while (sRunning && !s.isClosed()) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.read();
                if (b1 < 0) break;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long payloadLen = b1 & 0x7F;
                if (payloadLen == 126) {
                    int h = in.read(), l = in.read();
                    if (h < 0 || l < 0) break;
                    payloadLen = ((h & 0xFF) << 8) | (l & 0xFF);
                } else if (payloadLen == 127) {
                    payloadLen = 0;
                    for (int i = 0; i < 8; ++i) {
                        int x = in.read();
                        if (x < 0) return;
                        payloadLen = (payloadLen << 8) | (x & 0xFF);
                    }
                }
                if (payloadLen < 0 || payloadLen > 262144) break;
                byte[] mask = new byte[4];
                if (masked) {
                    if (readFully(in, mask, 4) < 4) break;
                }
                byte[] payload = new byte[(int) payloadLen];
                if (payloadLen > 0 && readFully(in, payload, (int) payloadLen) < payloadLen) break;
                if (masked) {
                    for (int i = 0; i < payload.length; ++i) payload[i] ^= mask[i & 3];
                }

                if (opcode == 0x8) { // Close
                    sendFrame(out, 0x8, new byte[0]);
                    break;
                } else if (opcode == 0x9) { // Ping -> Pong
                    sendFrame(out, 0xA, payload);
                    continue;
                } else if (opcode != 0x1) { // Only text JSON messages
                    continue;
                }

                String text = new String(payload, StandardCharsets.UTF_8);
                try {
                    JSONObject msg = new JSONObject(text);
                    String type = msg.optString("messageType", "");
                    String reqId = msg.optString("requestID", "obs");
                    JSONObject data = msg.optJSONObject("data");
                    if (data == null) data = new JSONObject();

                    JSONObject respData = new JSONObject();
                    String respType = type.replace("Request", "Response");

                    if ("APIStateRequest".equals(type)) {
                        respData.put("active", true);
                        respData.put("vTubeStudioVersion", "1.30.0-obs-android");
                        respData.put("currentSessionAuthenticated", authed);
                    } else if ("AuthenticationTokenRequest".equals(type)) {
                        String tok = randomToken();
                        sValidTokens.add(tok);
                        respData.put("authenticationToken", tok);
                    } else if ("AuthenticationRequest".equals(type)) {
                        String tok = data.optString("authenticationToken", "");
                        authed = !tok.isEmpty() && sValidTokens.contains(tok);
                        respData.put("authenticated", authed);
                        respData.put("reason", authed ? "OK" : "Invalid token");
                    } else if ("CurrentModelRequest".equals(type)) {
                        respData.put("modelLoaded", true);
                        respData.put("modelName", "OBS Android VTuber");
                        respData.put("modelID", "obs_android_vtuber");
                        respData.put("vtsModelName", "OBS Android VTuber");
                    } else if ("HotkeysInCurrentModelRequest".equals(type)) {
                        JSONArray arr = new JSONArray();
                        for (String[] hk : HOTKEYS) {
                            JSONObject o = new JSONObject();
                            o.put("name", hk[1]);
                            o.put("type", "TriggerAnimation");
                            o.put("hotkeyID", hk[0]);
                            arr.put(o);
                        }
                        respData.put("modelLoaded", true);
                        respData.put("availableHotkeys", arr);
                    } else if ("HotkeyTriggerRequest".equals(type)) {
                        String hkId = data.optString("hotkeyID", "");
                        if (authed && !hkId.isEmpty()) {
                            FaceTrackerManager.triggerGesture(hkId, 3500L);
                        }
                        respData.put("hotkeyID", hkId);
                    } else if ("ExpressionActivationRequest".equals(type)) {
                        String exp = data.optString("expressionFile", "");
                        boolean act = data.optBoolean("active", true);
                        if (authed && !exp.isEmpty()) {
                            String clean = exp.replace(".exp3.json", "").toLowerCase(Locale.US);
                            FaceTrackerManager.triggerGesture(act ? clean : "reset", 4000L);
                        }
                    } else if ("InjectParameterDataRequest".equals(type)) {
                        JSONArray vals = data.optJSONArray("parameterValues");
                        if (authed && vals != null) {
                            for (int i = 0; i < vals.length(); ++i) {
                                JSONObject pv = vals.optJSONObject(i);
                                if (pv == null) continue;
                                String pid = pv.optString("id", "");
                                float v = (float) pv.optDouble("value", 0.0);
                                if (!pid.isEmpty()) FaceTrackerManager.injectVtsParameter(pid, v);
                            }
                        }
                    } else if ("InputParameterListRequest".equals(type)) {
                        JSONArray def = new JSONArray();
                        for (String p : new String[]{"FaceAngleX", "FaceAngleY", "FaceAngleZ", "MouthOpen",
                                "MouthSmile", "EyeOpenLeft", "EyeOpenRight", "Brows", "CheekPuff", "HandLeft", "HandRight"}) {
                            JSONObject o = new JSONObject();
                            o.put("name", p);
                            o.put("min", -30.0);
                            o.put("max", 30.0);
                            o.put("defaultValue", 0.0);
                            def.put(o);
                        }
                        respData.put("modelLoaded", true);
                        respData.put("defaultParameters", def);
                        respData.put("customParameters", new JSONArray());
                    }

                    JSONObject envelope = new JSONObject();
                    envelope.put("apiName", "VTubeStudioPublicAPI");
                    envelope.put("apiVersion", "1.0");
                    envelope.put("timestamp", System.currentTimeMillis());
                    envelope.put("messageType", respType);
                    envelope.put("requestID", reqId);
                    envelope.put("data", respData);
                    sendFrame(out, 0x1, envelope.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) return off;
            off += r;
        }
        return off;
    }

    private static void sendFrame(OutputStream out, int opcode, byte[] data) throws IOException {
        out.write(0x80 | (opcode & 0x0F));
        int len = data.length;
        if (len < 126) {
            out.write(len);
        } else if (len < 65536) {
            out.write(126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; --i) out.write((int) (((long) len >> (i * 8)) & 0xFF));
        }
        if (len > 0) out.write(data);
        out.flush();
    }

    private VtsApiServer() {}
}
