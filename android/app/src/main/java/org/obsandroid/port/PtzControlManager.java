// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native Android PTZ Camera Controller inspired by glikely/obs-ptz.
 * Supports VISCA over IP (UDP 52381 / TCP 5678), ONVIF Profile S (SOAP/HTTP + WS-Discovery),
 * Pelco-D over IP (TCP/UDP), and native Bluetooth/USB PlayStation DualShock 4 / DualSense / Gamepad input.
 */
public final class PtzControlManager {
    private static final String TAG = "ObsPtzControl";
    private static final HandlerThread sNetThread = new HandlerThread("OBS-PTZ");
    private static final Handler sNet;

    static {
        sNetThread.start();
        sNet = new Handler(sNetThread.getLooper());
    }

    private static final Object sLock = new Object();
    private static volatile Context sAppCtx;
    private static boolean sLoaded = false;

    private static final List<CameraConfig> sCameras = new ArrayList<>();
    private static int sActiveIndex = 0;
    private static volatile boolean sGamepadEnabled = true;
    private static volatile float sMasterSpeed = 0.65f; // 0.15 .. 1.0
    private static volatile String sLastStatus = "Listo (conecta cámara IP o mando PlayStation)";
    private static volatile boolean sSaveModifierHeld = false;

    // Network transport state (accessed only on sNet thread)
    private static final Map<String, Integer> sViscaSeq = new HashMap<>();
    private static final Map<String, Socket> sTcpSockets = new HashMap<>();
    private static final Map<String, String> sOnvifProfileTokens = new HashMap<>();
    private static final Map<String, String> sOnvifPtzUrls = new HashMap<>();

    // Gamepad state
    private static float sPadPan = 0f;
    private static float sPadTilt = 0f;
    private static float sPadZoom = 0f;
    private static long sLastPadSendMs = 0L;
    private static boolean sPadWasMoving = false;
    private static int sLastHatDir = 0;

    static final class CameraConfig {
        String id;
        String name;
        String protocol; // "visca_udp", "visca_tcp", "onvif", "pelco_d_tcp", "pelco_d_udp"
        String host;
        int port;
        int address; // VISCA 1..7 or Pelco 1..255
        String username;
        String password;
        boolean invertPan;
        boolean invertTilt;
        float speedScale; // 0.2 .. 1.0

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("protocol", protocol);
                o.put("host", host);
                o.put("port", port);
                o.put("address", address);
                o.put("username", username);
                o.put("password", password);
                o.put("invertPan", invertPan);
                o.put("invertTilt", invertTilt);
                o.put("speedScale", speedScale);
            } catch (Throwable ignored) {}
            return o;
        }

        static CameraConfig fromJson(JSONObject o) {
            CameraConfig c = new CameraConfig();
            c.id = o.optString("id", UUID.randomUUID().toString());
            c.name = o.optString("name", "Cámara PTZ");
            c.protocol = o.optString("protocol", "visca_udp");
            c.host = o.optString("host", "192.168.1.100");
            c.port = o.optInt("port", defaultPortFor(c.protocol));
            c.address = Math.max(1, Math.min(255, o.optInt("address", 1)));
            c.username = o.optString("username", "admin");
            c.password = o.optString("password", "");
            c.invertPan = o.optBoolean("invertPan", false);
            c.invertTilt = o.optBoolean("invertTilt", false);
            c.speedScale = (float) Math.max(0.2, Math.min(1.0, o.optDouble("speedScale", 1.0)));
            return c;
        }
    }

    private static int defaultPortFor(String proto) {
        if ("visca_tcp".equals(proto)) return 5678;
        if ("onvif".equals(proto)) return 80;
        if ("pelco_d_tcp".equals(proto) || "pelco_d_udp".equals(proto)) return 4001;
        return 52381; // visca_udp
    }

    public static void init(Context ctx) {
        if (ctx != null) sAppCtx = ctx.getApplicationContext();
        ensureLoaded();
    }

    public static void shutdown() {
        sNet.post(() -> {
            for (Socket s : sTcpSockets.values()) {
                try { s.close(); } catch (Throwable ignored) {}
            }
            sTcpSockets.clear();
        });
    }

    private static File configFile() {
        if (sAppCtx == null) return null;
        File dir = new File(sAppCtx.getFilesDir(), "obs-studio");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, "android-ptz.json");
    }

    private static void ensureLoaded() {
        synchronized (sLock) {
            if (sLoaded) return;
            sLoaded = true;
            File f = configFile();
            if (f != null && f.isFile()) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] data = new byte[(int) Math.min(f.length(), 262144)];
                    int n = fis.read(data);
                    if (n > 0) {
                        JSONObject root = new JSONObject(new String(data, 0, n, StandardCharsets.UTF_8));
                        sGamepadEnabled = root.optBoolean("gamepadEnabled", true);
                        sMasterSpeed = (float) Math.max(0.15, Math.min(1.0, root.optDouble("masterSpeed", 0.65)));
                        sActiveIndex = root.optInt("activeIndex", 0);
                        JSONArray arr = root.optJSONArray("cameras");
                        sCameras.clear();
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); ++i) {
                                JSONObject co = arr.optJSONObject(i);
                                if (co != null) sCameras.add(CameraConfig.fromJson(co));
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to load PTZ config: " + t.getMessage());
                }
            }
            if (sActiveIndex < 0 || sActiveIndex >= sCameras.size()) sActiveIndex = 0;
        }
    }

    private static void saveLocked() {
        File f = configFile();
        if (f == null) return;
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("gamepadEnabled", sGamepadEnabled);
            root.put("masterSpeed", sMasterSpeed);
            root.put("activeIndex", sActiveIndex);
            JSONArray arr = new JSONArray();
            for (CameraConfig c : sCameras) arr.put(c.toJson());
            root.put("cameras", arr);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                tmp.delete();
            }
        } catch (Throwable t) {
            tmp.delete();
            Log.w(TAG, "Failed to save PTZ config: " + t.getMessage());
        }
    }

    public static String getConnectedGamepadName() {
        try {
            int[] ids = InputDevice.getDeviceIds();
            for (int id : ids) {
                InputDevice dev = InputDevice.getDevice(id);
                if (dev == null) continue;
                int src = dev.getSources();
                boolean isPad = ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                    || ((src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
                if (isPad && !dev.isVirtual()) {
                    return dev.getName() != null ? dev.getName() : "Gamepad conectado";
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static CameraConfig getActiveCameraCopy() {
        ensureLoaded();
        synchronized (sLock) {
            if (sCameras.isEmpty()) return null;
            if (sActiveIndex < 0 || sActiveIndex >= sCameras.size()) sActiveIndex = 0;
            return CameraConfig.fromJson(sCameras.get(sActiveIndex).toJson());
        }
    }

    public static String getStateJson() {
        ensureLoaded();
        JSONObject out = new JSONObject();
        try {
            synchronized (sLock) {
                out.put("gamepadEnabled", sGamepadEnabled);
                out.put("gamepadName", getConnectedGamepadName());
                out.put("masterSpeed", sMasterSpeed);
                out.put("activeIndex", sActiveIndex);
                out.put("status", sLastStatus);
                JSONArray arr = new JSONArray();
                for (CameraConfig c : sCameras) arr.put(c.toJson());
                out.put("cameras", arr);
            }
        } catch (Throwable ignored) {}
        return out.toString();
    }

    public static String executeActionJson(String actionJson) {
        ensureLoaded();
        if (actionJson == null || actionJson.isEmpty()) return getStateJson();
        try {
            JSONObject req = new JSONObject(actionJson);
            String action = req.optString("action", "");
            switch (action) {
                case "add_camera": {
                    CameraConfig c = CameraConfig.fromJson(req);
                    if (c.id == null || c.id.isEmpty()) c.id = UUID.randomUUID().toString();
                    synchronized (sLock) {
                        sCameras.add(c);
                        sActiveIndex = sCameras.size() - 1;
                        sLastStatus = "Cámara agregada: " + c.name + " (" + c.host + ")";
                        saveLocked();
                    }
                    break;
                }
                case "update_camera": {
                    CameraConfig updated = CameraConfig.fromJson(req);
                    synchronized (sLock) {
                        int idx = req.optInt("index", sActiveIndex);
                        if (idx >= 0 && idx < sCameras.size()) {
                            updated.id = sCameras.get(idx).id;
                            sCameras.set(idx, updated);
                            sLastStatus = "Cámara actualizada: " + updated.name;
                            saveLocked();
                        }
                    }
                    sNet.post(() -> resetTransportFor(updated.id));
                    break;
                }
                case "delete_camera": {
                    String removedId = null;
                    synchronized (sLock) {
                        int idx = req.optInt("index", sActiveIndex);
                        if (idx >= 0 && idx < sCameras.size()) {
                            removedId = sCameras.get(idx).id;
                            sCameras.remove(idx);
                            if (sActiveIndex >= sCameras.size()) sActiveIndex = Math.max(0, sCameras.size() - 1);
                            sLastStatus = "Cámara eliminada";
                            saveLocked();
                        }
                    }
                    if (removedId != null) {
                        final String rid = removedId;
                        sNet.post(() -> resetTransportFor(rid));
                    }
                    break;
                }
                case "select_camera": {
                    int idx = req.optInt("index", 0);
                    synchronized (sLock) {
                        if (idx >= 0 && idx < sCameras.size()) {
                            sActiveIndex = idx;
                            sLastStatus = "Cámara activa: " + sCameras.get(idx).name;
                            saveLocked();
                        }
                    }
                    break;
                }
                case "set_gamepad_enabled": {
                    synchronized (sLock) {
                        sGamepadEnabled = req.optBoolean("enabled", true);
                        sLastStatus = sGamepadEnabled ? "Mando PlayStation / Gamepad activado" : "Mando desactivado";
                        saveLocked();
                    }
                    break;
                }
                case "set_speed": {
                    synchronized (sLock) {
                        sMasterSpeed = (float) Math.max(0.15, Math.min(1.0, req.optDouble("speed", 0.65)));
                        saveLocked();
                    }
                    break;
                }
                case "move": {
                    float pan = (float) Math.max(-1.0, Math.min(1.0, req.optDouble("pan", 0.0)));
                    float tilt = (float) Math.max(-1.0, Math.min(1.0, req.optDouble("tilt", 0.0)));
                    float zoom = (float) Math.max(-1.0, Math.min(1.0, req.optDouble("zoom", 0.0)));
                    sendMoveAsync(pan, tilt, zoom);
                    break;
                }
                case "stop": {
                    sendMoveAsync(0f, 0f, 0f);
                    break;
                }
                case "home": {
                    CameraConfig cam = getActiveCameraCopy();
                    if (cam != null) sNet.post(() -> doHome(cam));
                    break;
                }
                case "focus": {
                    String mode = req.optString("mode", "auto");
                    CameraConfig cam = getActiveCameraCopy();
                    if (cam != null) sNet.post(() -> doFocus(cam, mode));
                    break;
                }
                case "preset_recall": {
                    int num = Math.max(1, Math.min(128, req.optInt("preset", 1)));
                    CameraConfig cam = getActiveCameraCopy();
                    if (cam != null) sNet.post(() -> doPreset(cam, num, false));
                    break;
                }
                case "preset_save": {
                    int num = Math.max(1, Math.min(128, req.optInt("preset", 1)));
                    CameraConfig cam = getActiveCameraCopy();
                    if (cam != null) sNet.post(() -> doPreset(cam, num, true));
                    break;
                }
                case "discover_onvif": {
                    sNet.post(PtzControlManager::runOnvifDiscovery);
                    sLastStatus = "Buscando cámaras ONVIF en la red Wi-Fi...";
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "executeActionJson error: " + t.getMessage());
        }
        return getStateJson();
    }

    // ---- PlayStation DualShock 4 / DualSense / Android Gamepad input hooks ----
    private static boolean isGamepadEvent(int source) {
        return ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
            || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
    }

    private static float applyDeadzoneAndCurve(float v) {
        float av = Math.abs(v);
        if (av < 0.14f) return 0f;
        float norm = (av - 0.14f) / 0.86f;
        float curved = (float) Math.pow(Math.min(1f, norm), 1.45);
        return Math.copySign(curved, v);
    }

    public static boolean handleGenericMotionEvent(MotionEvent ev) {
        if (!sGamepadEnabled || ev == null || !isGamepadEvent(ev.getSource())) return false;
        if (ev.getAction() != MotionEvent.ACTION_MOVE) return false;
        ensureLoaded();
        CameraConfig cam = getActiveCameraCopy();
        if (cam == null) return false;

        // Left stick -> Pan / Tilt (note Android Y axis is negative when pushed up)
        float rawX = ev.getAxisValue(MotionEvent.AXIS_X);
        float rawY = -ev.getAxisValue(MotionEvent.AXIS_Y);
        float pan = applyDeadzoneAndCurve(rawX);
        float tilt = applyDeadzoneAndCurve(rawY);

        // Right stick vertical OR R2/L2 triggers -> Zoom
        float rz = -ev.getAxisValue(MotionEvent.AXIS_RZ);
        float r2 = Math.max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS));
        float l2 = Math.max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE));
        float zoom = applyDeadzoneAndCurve(rz);
        if (Math.abs(zoom) < 0.05f) {
            float trig = r2 - l2;
            zoom = Math.abs(trig) > 0.10f ? trig : 0f;
        }

        // Hat D-Pad (DualShock 4 / DualSense reports D-Pad as HAT_X / HAT_Y) -> Presets 5..8
        float hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int hatDir = 0;
        if (hatY < -0.5f) hatDir = 5;      // Up -> Preset 5
        else if (hatX > 0.5f) hatDir = 6;  // Right -> Preset 6
        else if (hatY > 0.5f) hatDir = 7;  // Down -> Preset 7
        else if (hatX < -0.5f) hatDir = 8; // Left -> Preset 8
        if (hatDir != 0 && hatDir != sLastHatDir) {
            final int pNum = hatDir;
            final boolean save = sSaveModifierHeld;
            sNet.post(() -> doPreset(cam, pNum, save));
            showPadToast((save ? "Guardado " : "Ir a ") + "Preset " + pNum + " (" + cam.name + ")");
        }
        sLastHatDir = hatDir;

        boolean moving = (Math.abs(pan) > 0.01f || Math.abs(tilt) > 0.01f || Math.abs(zoom) > 0.01f);
        long now = SystemClock.uptimeMillis();
        if (!moving) {
            if (sPadWasMoving) {
                sPadWasMoving = false;
                sPadPan = 0f; sPadTilt = 0f; sPadZoom = 0f;
                sLastPadSendMs = now;
                sendMoveAsync(0f, 0f, 0f);
            }
            return true;
        }

        boolean changed = Math.abs(pan - sPadPan) > 0.06f
            || Math.abs(tilt - sPadTilt) > 0.06f
            || Math.abs(zoom - sPadZoom) > 0.06f;
        if (!sPadWasMoving || (changed && now - sLastPadSendMs >= 45L) || (now - sLastPadSendMs >= 160L)) {
            sPadWasMoving = true;
            sPadPan = pan;
            sPadTilt = tilt;
            sPadZoom = zoom;
            sLastPadSendMs = now;
            sendMoveAsync(pan, tilt, zoom);
        }
        return true;
    }

    public static boolean handleKeyEvent(KeyEvent ev) {
        if (!sGamepadEnabled || ev == null || !isGamepadEvent(ev.getSource())) return false;
        ensureLoaded();
        synchronized (sLock) {
            if (sCameras.isEmpty()) return false;
        }
        int code = ev.getKeyCode();
        boolean down = (ev.getAction() == KeyEvent.ACTION_DOWN);
        boolean up = (ev.getAction() == KeyEvent.ACTION_UP);
        if (ev.getRepeatCount() > 0 && down) return true;

        // OPTIONS / START / SHARE / SELECT acts as "Save Preset" modifier while held
        if (code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BUTTON_SELECT
                || code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (down) sSaveModifierHeld = true;
            else if (up) sSaveModifierHeld = false;
            return true;
        }

        if (!down) {
            // Consume key-up for mapped buttons so Qt doesn't trigger UI actions
            return isMappedPadKey(code);
        }

        if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
            int dir = (code == KeyEvent.KEYCODE_BUTTON_R1) ? 1 : -1;
            CameraConfig prevCam = getActiveCameraCopy();
            if (sPadWasMoving && prevCam != null) {
                sPadWasMoving = false;
                sPadPan = 0f; sPadTilt = 0f; sPadZoom = 0f;
                sNet.post(() -> doMove(prevCam, 0f, 0f, 0f));
            }
            String switchedName = null;
            synchronized (sLock) {
                if (!sCameras.isEmpty()) {
                    sActiveIndex = (sActiveIndex + dir + sCameras.size()) % sCameras.size();
                    switchedName = sCameras.get(sActiveIndex).name;
                    sLastStatus = "Mando -> Cámara: " + switchedName;
                    saveLocked();
                }
            }
            if (switchedName != null) showPadToast("PTZ activa: " + switchedName);
            return true;
        }

        CameraConfig cam = getActiveCameraCopy();
        if (cam == null) return false;

        if (code == KeyEvent.KEYCODE_BUTTON_THUMBL) {
            sNet.post(() -> doHome(cam));
            showPadToast("PTZ Home: " + cam.name);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            sNet.post(() -> doFocus(cam, "auto"));
            showPadToast("PTZ Auto-Focus: " + cam.name);
            return true;
        }

        int preset = -1;
        if (code == KeyEvent.KEYCODE_BUTTON_A) preset = 1;       // Cross (✕)
        else if (code == KeyEvent.KEYCODE_BUTTON_B) preset = 2;  // Circle (○)
        else if (code == KeyEvent.KEYCODE_BUTTON_X) preset = 3;  // Square (□)
        else if (code == KeyEvent.KEYCODE_BUTTON_Y) preset = 4;  // Triangle (△)
        else if (code == KeyEvent.KEYCODE_DPAD_UP) preset = 5;
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) preset = 6;
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) preset = 7;
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT) preset = 8;

        if (preset > 0) {
            final int pNum = preset;
            final boolean save = sSaveModifierHeld;
            sNet.post(() -> doPreset(cam, pNum, save));
            showPadToast((save ? "Guardado " : "Ir a ") + "Preset " + pNum + " (" + cam.name + ")");
            return true;
        }
        return false;
    }

    private static boolean isMappedPadKey(int code) {
        return code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1
            || code == KeyEvent.KEYCODE_BUTTON_THUMBL || code == KeyEvent.KEYCODE_BUTTON_THUMBR
            || code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_B
            || code == KeyEvent.KEYCODE_BUTTON_X || code == KeyEvent.KEYCODE_BUTTON_Y
            || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_RIGHT
            || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT
            || code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BUTTON_SELECT;
    }

    private static void showPadToast(String msg) {
        QtObsActivity act = QtObsActivity.getActiveInstance();
        if (act != null && !act.isFinishing()) {
            act.runOnUiThread(() -> Toast.makeText(act, msg, Toast.LENGTH_SHORT).show());
        }
    }

    // ---- Protocol execution on sNet thread ----
    private static void resetTransportFor(String camId) {
        Socket old = sTcpSockets.remove(camId);
        if (old != null) {
            try { old.close(); } catch (Throwable ignored) {}
        }
        sViscaSeq.remove(camId);
        sOnvifProfileTokens.remove(camId);
        sOnvifPtzUrls.remove(camId);
    }

    private static void sendMoveAsync(float pan, float tilt, float zoom) {
        CameraConfig cam = getActiveCameraCopy();
        if (cam == null) {
            sLastStatus = "Agrega una cámara PTZ primero";
            return;
        }
        float scale = sMasterSpeed * cam.speedScale;
        float p = (cam.invertPan ? -pan : pan) * scale;
        float t = (cam.invertTilt ? -tilt : tilt) * scale;
        float z = zoom * scale;
        sNet.post(() -> doMove(cam, p, t, z));
    }

    private static void doMove(CameraConfig cam, float pan, float tilt, float zoom) {
        try {
            if ("onvif".equals(cam.protocol)) {
                if (Math.abs(pan) < 0.01f && Math.abs(tilt) < 0.01f && Math.abs(zoom) < 0.01f) {
                    onvifStop(cam);
                } else {
                    onvifContinuousMove(cam, pan, tilt, zoom);
                }
                return;
            }
            if ("pelco_d_tcp".equals(cam.protocol) || "pelco_d_udp".equals(cam.protocol)) {
                sendPelcoMove(cam, pan, tilt, zoom);
                return;
            }
            // VISCA over UDP or TCP
            int addr = Math.max(1, Math.min(7, cam.address));
            byte hdr = (byte) (0x80 | addr);
            int pSpd = Math.max(1, Math.min(0x18, Math.round(Math.abs(pan) * 0x18)));
            int tSpd = Math.max(1, Math.min(0x14, Math.round(Math.abs(tilt) * 0x14)));
            byte pDir = Math.abs(pan) < 0.01f ? (byte) 0x03 : (pan < 0f ? (byte) 0x01 : (byte) 0x02);
            byte tDir = Math.abs(tilt) < 0.01f ? (byte) 0x03 : (tilt > 0f ? (byte) 0x01 : (byte) 0x02);
            if (pDir == 0x03) pSpd = 0x01;
            if (tDir == 0x03) tSpd = 0x01;
            sendVisca(cam, new byte[]{hdr, 0x01, 0x06, 0x01, (byte) pSpd, (byte) tSpd, pDir, tDir, (byte) 0xFF});

            byte zByte;
            if (Math.abs(zoom) < 0.01f) {
                zByte = 0x00;
            } else {
                int zSpd = Math.max(0, Math.min(7, Math.round(Math.abs(zoom) * 7)));
                zByte = (byte) ((zoom > 0f ? 0x20 : 0x30) | zSpd);
            }
            sendVisca(cam, new byte[]{hdr, 0x01, 0x04, 0x07, zByte, (byte) 0xFF});
            sLastStatus = (pDir == 0x03 && tDir == 0x03 && zByte == 0x00)
                ? ("Detenido · " + cam.name)
                : String.format(Locale.US, "Moviendo %s (P:%.0f%% T:%.0f%% Z:%.0f%%)", cam.name, pan * 100f, tilt * 100f, zoom * 100f);
        } catch (Throwable e) {
            sLastStatus = "Error PTZ (" + cam.name + "): " + e.getMessage();
            Log.w(TAG, sLastStatus);
        }
    }

    private static void doHome(CameraConfig cam) {
        try {
            if ("onvif".equals(cam.protocol)) {
                onvifHome(cam);
            } else if ("pelco_d_tcp".equals(cam.protocol) || "pelco_d_udp".equals(cam.protocol)) {
                sendPelcoPacket(cam, 0x00, 0x07, 0x00, 0x01); // Preset 1 fallback
            } else {
                byte hdr = (byte) (0x80 | Math.max(1, Math.min(7, cam.address)));
                sendVisca(cam, new byte[]{hdr, 0x01, 0x06, 0x04, (byte) 0xFF});
            }
            sLastStatus = "Home enviado a " + cam.name;
        } catch (Throwable e) {
            sLastStatus = "Error Home: " + e.getMessage();
        }
    }

    private static void doFocus(CameraConfig cam, String mode) {
        try {
            if ("visca_udp".equals(cam.protocol) || "visca_tcp".equals(cam.protocol)) {
                byte hdr = (byte) (0x80 | Math.max(1, Math.min(7, cam.address)));
                if ("auto".equals(mode)) {
                    sendVisca(cam, new byte[]{hdr, 0x01, 0x04, 0x38, 0x02, (byte) 0xFF});
                } else if ("far".equals(mode)) {
                    sendVisca(cam, new byte[]{hdr, 0x01, 0x04, 0x38, 0x03, (byte) 0xFF});
                    sendVisca(cam, new byte[]{hdr, 0x01, 0x04, 0x08, 0x22, (byte) 0xFF});
                } else if ("near".equals(mode)) {
                    sendVisca(cam, new byte[]{hdr, 0x01, 0x04, 0x38, 0x03, (byte) 0xFF});
                    sendVisca(cam, new byte[]{hdr, 0x01, 0x04, 0x08, 0x32, (byte) 0xFF});
                } else {
                    sendVisca(cam, new byte[]{hdr, 0x01, 0x04, 0x08, 0x00, (byte) 0xFF});
                }
            } else if ("pelco_d_tcp".equals(cam.protocol) || "pelco_d_udp".equals(cam.protocol)) {
                if ("near".equals(mode)) sendPelcoPacket(cam, 0x01, 0x00, 0x00, 0x00);
                else if ("far".equals(mode)) sendPelcoPacket(cam, 0x00, 0x80, 0x00, 0x00);
                else sendPelcoPacket(cam, 0x00, 0x00, 0x00, 0x00);
            }
            sLastStatus = "Enfoque (" + mode + ") -> " + cam.name;
        } catch (Throwable e) {
            sLastStatus = "Error Enfoque: " + e.getMessage();
        }
    }

    private static void doPreset(CameraConfig cam, int presetNum, boolean save) {
        try {
            if ("onvif".equals(cam.protocol)) {
                onvifPreset(cam, presetNum, save);
            } else if ("pelco_d_tcp".equals(cam.protocol) || "pelco_d_udp".equals(cam.protocol)) {
                sendPelcoPacket(cam, 0x00, save ? 0x03 : 0x07, 0x00, presetNum & 0xFF);
            } else {
                byte hdr = (byte) (0x80 | Math.max(1, Math.min(7, cam.address)));
                byte pIdx = (byte) Math.max(0, Math.min(127, presetNum - 1));
                sendVisca(cam, new byte[]{hdr, 0x01, 0x04, 0x3F, save ? (byte) 0x01 : (byte) 0x02, pIdx, (byte) 0xFF});
            }
            sLastStatus = (save ? "Preset guardado #" : "Preset cargado #") + presetNum + " (" + cam.name + ")";
        } catch (Throwable e) {
            sLastStatus = "Error Preset #" + presetNum + ": " + e.getMessage();
        }
    }

    // ---- VISCA UDP (Sony/PTZOptics 8-byte header) & VISCA TCP ----
    private static void sendVisca(CameraConfig cam, byte[] payload) throws Exception {
        if ("visca_tcp".equals(cam.protocol)) {
            sendTcpBytes(cam, payload);
            return;
        }
        InetAddress addr = InetAddress.getByName(cam.host);
        int port = cam.port > 0 ? cam.port : 52381;
        Integer seqObj = sViscaSeq.get(cam.id);
        int seq;
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(350);
            if (seqObj == null) {
                // Send VISCA-over-IP sequence reset first (standard Sony/PTZOptics spec)
                byte[] rst = new byte[]{0x02, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x01};
                sock.send(new DatagramPacket(rst, rst.length, addr, port));
                seq = 1;
            } else {
                seq = seqObj + 1;
            }
            sViscaSeq.put(cam.id, seq);

            byte[] pkt = new byte[8 + payload.length];
            pkt[0] = 0x01;
            pkt[1] = 0x00;
            pkt[2] = (byte) ((payload.length >> 8) & 0xFF);
            pkt[3] = (byte) (payload.length & 0xFF);
            pkt[4] = (byte) ((seq >> 24) & 0xFF);
            pkt[5] = (byte) ((seq >> 16) & 0xFF);
            pkt[6] = (byte) ((seq >> 8) & 0xFF);
            pkt[7] = (byte) (seq & 0xFF);
            System.arraycopy(payload, 0, pkt, 8, payload.length);
            sock.send(new DatagramPacket(pkt, pkt.length, addr, port));
        }
    }

    private static void sendTcpBytes(CameraConfig cam, byte[] payload) throws Exception {
        Socket sock = sTcpSockets.get(cam.id);
        if (sock == null || sock.isClosed() || !sock.isConnected()) {
            Socket fresh = new Socket();
            try {
                fresh.connect(new InetSocketAddress(cam.host, cam.port > 0 ? cam.port : 5678), 1500);
                fresh.setTcpNoDelay(true);
                sock = fresh;
                sTcpSockets.put(cam.id, sock);
            } catch (Exception e) {
                try { fresh.close(); } catch (Throwable ignored) {}
                throw e;
            }
        }
        try {
            // Drain any pending VISCA/camera ACK bytes so TCP receive buffer never stalls
            InputStream is = sock.getInputStream();
            if (is.available() > 0) {
                byte[] drain = new byte[Math.min(1024, is.available())];
                is.read(drain);
            }
            OutputStream os = sock.getOutputStream();
            os.write(payload);
            os.flush();
        } catch (Exception e) {
            try { sock.close(); } catch (Throwable ignored) {}
            sTcpSockets.remove(cam.id);
            throw e;
        }
    }

    // ---- Pelco-D over TCP / UDP ----
    private static void sendPelcoMove(CameraConfig cam, float pan, float tilt, float zoom) throws Exception {
        int cmd2 = 0;
        if (pan > 0.01f) cmd2 |= 0x02;       // Right
        else if (pan < -0.01f) cmd2 |= 0x04; // Left
        if (tilt > 0.01f) cmd2 |= 0x08;      // Up
        else if (tilt < -0.01f) cmd2 |= 0x10;// Down
        if (zoom > 0.01f) cmd2 |= 0x20;      // Zoom Tele (In)
        else if (zoom < -0.01f) cmd2 |= 0x40;// Zoom Wide (Out)
        int pSpd = Math.min(0x3F, Math.round(Math.abs(pan) * 0x3F));
        int tSpd = Math.min(0x3F, Math.round(Math.abs(tilt) * 0x3F));
        sendPelcoPacket(cam, 0x00, cmd2, pSpd, tSpd);
    }

    private static void sendPelcoPacket(CameraConfig cam, int cmd1, int cmd2, int d1, int d2) throws Exception {
        int addr = Math.max(1, Math.min(255, cam.address));
        int sum = (addr + cmd1 + cmd2 + d1 + d2) & 0xFF;
        byte[] pkt = new byte[]{(byte) 0xFF, (byte) addr, (byte) cmd1, (byte) cmd2, (byte) d1, (byte) d2, (byte) sum};
        if ("pelco_d_tcp".equals(cam.protocol)) {
            sendTcpBytes(cam, pkt);
        } else {
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(cam.host), cam.port > 0 ? cam.port : 4001));
            }
        }
    }

    // ---- ONVIF Profile S SOAP/HTTP ----
    private static String buildWsseHeader(String user, String pass) throws Exception {
        if (user == null || user.isEmpty()) return "";
        byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String created = sdf.format(new Date());
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(nonceBytes);
        sha1.update(created.getBytes(StandardCharsets.UTF_8));
        sha1.update((pass != null ? pass : "").getBytes(StandardCharsets.UTF_8));
        String digestB64 = Base64.encodeToString(sha1.digest(), Base64.NO_WRAP);
        String nonceB64 = Base64.encodeToString(nonceBytes, Base64.NO_WRAP);
        return "<s:Header>"
            + "<Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">"
            + "<UsernameToken>"
            + "<Username>" + xmlEscape(user) + "</Username>"
            + "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" + digestB64 + "</Password>"
            + "<Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">" + nonceB64 + "</Nonce>"
            + "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" + created + "</Created>"
            + "</UsernameToken></Security></s:Header>";
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String sendOnvifSoap(String urlStr, String user, String pass, String bodyXml) throws Exception {
        String env = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
            + " xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\""
            + " xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\""
            + " xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\""
            + " xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
            + buildWsseHeader(user, pass)
            + "<s:Body>" + bodyXml + "</s:Body></s:Envelope>";
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(2500);
        conn.setReadTimeout(2500);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(env.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "";
            try (InputStream in = is; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static boolean isValidCameraEndpointUrl(String urlStr, String expectedHost) {
        if (urlStr == null || expectedHost == null || expectedHost.isEmpty()) return false;
        try {
            URL u = new URL(urlStr);
            String proto = u.getProtocol();
            if (!"http".equalsIgnoreCase(proto) && !"https".equalsIgnoreCase(proto)) return false;
            String h = u.getHost();
            return h != null && h.equalsIgnoreCase(expectedHost);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void ensureOnvifEndpoints(CameraConfig cam) throws Exception {
        if (sOnvifProfileTokens.containsKey(cam.id) && sOnvifPtzUrls.containsKey(cam.id)) return;
        String base = "http://" + cam.host + ":" + (cam.port > 0 ? cam.port : 80);
        String ptzUrl = base + "/onvif/ptz_service";
        String mediaUrl = base + "/onvif/media_service";
        boolean capOk = false;
        try {
            String cap = sendOnvifSoap(base + "/onvif/device_service", cam.username, cam.password,
                "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>");
            capOk = cap != null && !cap.isEmpty();
            Matcher mMedia = Pattern.compile("<[^>]*Media[^>]*>.*?<[^>]*XAddr[^>]*>([^<]+)</", Pattern.DOTALL).matcher(cap);
            if (mMedia.find()) {
                String cand = mMedia.group(1).trim();
                if (isValidCameraEndpointUrl(cand, cam.host)) mediaUrl = cand;
            }
            Matcher mPtz = Pattern.compile("<[^>]*PTZ[^>]*>.*?<[^>]*XAddr[^>]*>([^<]+)</", Pattern.DOTALL).matcher(cap);
            if (mPtz.find()) {
                String cand = mPtz.group(1).trim();
                if (isValidCameraEndpointUrl(cand, cam.host)) ptzUrl = cand;
            }
        } catch (Throwable ignored) {}

        String token = "Profile_1";
        boolean profOk = false;
        try {
            String prof = sendOnvifSoap(mediaUrl, cam.username, cam.password, "<trt:GetProfiles/>");
            Matcher mTok = Pattern.compile("token=\"([^\"]+)\"").matcher(prof);
            if (mTok.find()) {
                token = mTok.group(1);
                profOk = true;
            }
        } catch (Throwable ignored) {}

        // Only cache endpoints permanently if SOAP discovery succeeded; otherwise use defaults for this call only
        if (capOk || profOk) {
            sOnvifPtzUrls.put(cam.id, ptzUrl);
            sOnvifProfileTokens.put(cam.id, token);
        }
    }

    private static String getOnvifPtzUrl(CameraConfig cam) {
        String u = sOnvifPtzUrls.get(cam.id);
        return u != null ? u : ("http://" + cam.host + ":" + (cam.port > 0 ? cam.port : 80) + "/onvif/ptz_service");
    }

    private static String getOnvifToken(CameraConfig cam) {
        String t = sOnvifProfileTokens.get(cam.id);
        return t != null ? t : "Profile_1";
    }

    private static void onvifContinuousMove(CameraConfig cam, float pan, float tilt, float zoom) throws Exception {
        ensureOnvifEndpoints(cam);
        String url = getOnvifPtzUrl(cam);
        String tok = getOnvifToken(cam);
        String body = String.format(Locale.US,
            "<tptz:ContinuousMove><tptz:ProfileToken>%s</tptz:ProfileToken><tptz:Velocity>"
                + "<tt:PanTilt x=\"%.3f\" y=\"%.3f\"/>"
                + "<tt:Zoom x=\"%.3f\"/>"
                + "</tptz:Velocity></tptz:ContinuousMove>",
            xmlEscape(tok), pan, tilt, zoom);
        sendOnvifSoap(url, cam.username, cam.password, body);
        sLastStatus = String.format(Locale.US, "ONVIF %s (P:%.0f%% T:%.0f%% Z:%.0f%%)", cam.name, pan * 100f, tilt * 100f, zoom * 100f);
    }

    private static void onvifStop(CameraConfig cam) throws Exception {
        ensureOnvifEndpoints(cam);
        String url = getOnvifPtzUrl(cam);
        String tok = getOnvifToken(cam);
        String body = "<tptz:Stop><tptz:ProfileToken>" + xmlEscape(tok)
            + "</tptz:ProfileToken><tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>";
        sendOnvifSoap(url, cam.username, cam.password, body);
        sLastStatus = "ONVIF Detenido · " + cam.name;
    }

    private static void onvifHome(CameraConfig cam) throws Exception {
        ensureOnvifEndpoints(cam);
        String url = getOnvifPtzUrl(cam);
        String tok = getOnvifToken(cam);
        sendOnvifSoap(url, cam.username, cam.password,
            "<tptz:GotoHomePosition><tptz:ProfileToken>" + xmlEscape(tok) + "</tptz:ProfileToken></tptz:GotoHomePosition>");
    }

    private static void onvifPreset(CameraConfig cam, int num, boolean save) throws Exception {
        ensureOnvifEndpoints(cam);
        String url = getOnvifPtzUrl(cam);
        String tok = getOnvifToken(cam);
        String pTok = String.valueOf(num);
        if (save) {
            sendOnvifSoap(url, cam.username, cam.password,
                "<tptz:SetPreset><tptz:ProfileToken>" + xmlEscape(tok)
                    + "</tptz:ProfileToken><tptz:PresetName>Preset_" + num
                    + "</tptz:PresetName><tptz:PresetToken>" + pTok + "</tptz:PresetToken></tptz:SetPreset>");
        } else {
            sendOnvifSoap(url, cam.username, cam.password,
                "<tptz:GotoPreset><tptz:ProfileToken>" + xmlEscape(tok)
                    + "</tptz:ProfileToken><tptz:PresetToken>" + pTok + "</tptz:PresetToken></tptz:GotoPreset>");
        }
    }

    private static void runOnvifDiscovery() {
        String probe = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\""
            + " xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
            + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\""
            + " xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">"
            + "<e:Header><w:MessageID>uuid:" + UUID.randomUUID() + "</w:MessageID>"
            + "<w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
            + "<w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>"
            + "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>";
        int added = 0;
        Set<String> seenHosts = new HashSet<>();
        synchronized (sLock) {
            for (CameraConfig c : sCameras) seenHosts.add(c.host + ":" + c.port);
        }
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            sock.setSoTimeout(1800);
            byte[] data = probe.getBytes(StandardCharsets.UTF_8);
            sock.send(new DatagramPacket(data, data.length, InetAddress.getByName("239.255.255.250"), 3702));
            long endAt = SystemClock.uptimeMillis() + 2000L;
            byte[] buf = new byte[8192];
            while (SystemClock.uptimeMillis() < endAt) {
                try {
                    DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    sock.receive(resp);
                    String xml = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                    InetAddress sender = resp.getAddress();
                    if (sender == null || sender.isLoopbackAddress() || sender.isMulticastAddress()) continue;
                    // Use the actual UDP packet sender IP (prevent spoofed XML IPs)
                    String ip = sender.getHostAddress();
                    int port = 80;
                    Matcher m = Pattern.compile("http://([0-9.]+)(?::([0-9]{1,5}))?/onvif").matcher(xml);
                    if (m.find() && m.group(2) != null) {
                        try {
                            int p = Integer.parseInt(m.group(2));
                            if (p >= 1 && p <= 65535) port = p;
                        } catch (NumberFormatException ignored) {}
                    }
                    String key = ip + ":" + port;
                    if (!seenHosts.contains(key)) {
                        seenHosts.add(key);
                        CameraConfig c = new CameraConfig();
                        c.id = UUID.randomUUID().toString();
                        c.name = "ONVIF " + ip;
                        c.protocol = "onvif";
                        c.host = ip;
                        c.port = port;
                        c.address = 1;
                        c.username = "admin";
                        c.password = "";
                        c.speedScale = 1.0f;
                        synchronized (sLock) {
                            sCameras.add(c);
                            added++;
                            saveLocked();
                        }
                    }
                } catch (java.net.SocketTimeoutException done) {
                    break;
                }
            }
            sLastStatus = added > 0
                ? ("ONVIF: " + added + " cámara(s) encontrada(s)")
                : "ONVIF: sin cámaras nuevas (puedes añadir IP manualmente)";
        } catch (Throwable e) {
            sLastStatus = "Búsqueda ONVIF: " + e.getMessage();
        }
    }

    private PtzControlManager() {}
}
