package org.obsandroid.port;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

public final class CaptureService extends Service {
    static final String CAMERA = "org.obsandroid.port.CAMERA", MIC = "org.obsandroid.port.MIC", SCREEN = "org.obsandroid.port.SCREEN",
        STOP = "org.obsandroid.port.STOP", SWITCH_CAMERA = "org.obsandroid.port.SWITCH_CAMERA", ZOOM = "org.obsandroid.port.ZOOM", EXPOSURE = "org.obsandroid.port.EXPOSURE",
        CAMERA_CONFIG = "org.obsandroid.port.CAMERA_CONFIG", MIC_CONFIG = "org.obsandroid.port.MIC_CONFIG", UVC_CONFIG = "org.obsandroid.port.UVC_CONFIG",
        SCREEN_AUDIO = "org.obsandroid.port.SCREEN_AUDIO", STREAM_KEEPALIVE = "org.obsandroid.port.STREAM_KEEPALIVE",
        SESSION_KEEPALIVE = "org.obsandroid.port.SESSION_KEEPALIVE";
    static volatile boolean cameraActive, microphoneActive, screenActive, uvcActive, qtBroadcastActive, sessionBgActive;
    static volatile boolean streamRequested;
    static volatile String streamError = "";
    static final String STREAM_START = "org.obsandroid.port.STREAM_START", STREAM_STOP = "org.obsandroid.port.STREAM_STOP";
    private CameraController camera;
    private final java.util.Map<String, AudioCaptureController> microphones = new java.util.LinkedHashMap<>();
    private ScreenController screen;
    private UsbUvcController uvc;
    private boolean front;
    private String currentCameraId = "default";
    private float currentZoom = 1.0f;
    private int currentExposure = 0;
    private String currentUvcDevice = "default";
    private PowerManager.WakeLock wakeLock;
    private AudioDeviceCallback audioHotplugCb;
    private boolean audioHotplugPrimed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService STOP_EXECUTOR =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OBS-CaptureStop");
            t.setDaemon(true);
            return t;
        });

    private static void stopCameraAsync(CameraController c) {
        if (c != null) STOP_EXECUTOR.execute(() -> { try { c.stop(); } catch (Throwable ignored) {} });
    }
    private static void stopMicAsync(AudioCaptureController m, boolean notifyDisable) {
        if (m != null) STOP_EXECUTOR.execute(() -> { try { m.stop(notifyDisable); } catch (Throwable ignored) {} });
    }
    private void stopAllMicsLocked() {
        java.util.List<AudioCaptureController> old = new java.util.ArrayList<>(microphones.values());
        microphones.clear();
        for (int i = 0; i < old.size(); ++i) {
            stopMicAsync(old.get(i), i == old.size() - 1);
        }
    }
    private void restartMicSpec(String spec) {
        if (!microphoneActive || !microphones.containsKey(spec)) return;
        AudioCaptureController oldMic = microphones.remove(spec);
        final AudioCaptureController[] ref = new AudioCaptureController[1];
        AudioCaptureController nextMic = new AudioCaptureController(
            CaptureService.this, null, spec, () -> failed("microphone", ref[0]));
        ref[0] = nextMic;
        microphones.put(spec, nextMic);
        STOP_EXECUTOR.execute(() -> {
            if (oldMic != null) { try { oldMic.stop(false); } catch (Throwable ignored) {} }
            main.post(() -> {
                if (microphones.get(spec) == nextMic && microphoneActive) nextMic.start();
            });
        });
    }
    private static void stopScreenAsync(ScreenController s) {
        if (s != null) STOP_EXECUTOR.execute(() -> { try { s.stop(); } catch (Throwable ignored) {} });
    }
    private static void stopUvcAsync(UsbUvcController u) {
        if (u != null) STOP_EXECUTOR.execute(() -> { try { u.stop(); } catch (Throwable ignored) {} });
    }
    private final Runnable streamMonitor = new Runnable() {
        @Override public void run() {
            if (!streamRequested) return;
            EngineRuntime.EXECUTOR.execute(() -> {
                try {
                    org.json.JSONObject output = new org.json.JSONObject(NativeBridge.state()).getJSONObject("stream");
                    if (output.optBoolean("finished")) main.post(() -> {
                        streamError = "Transmisión detenida (" + output.optInt("stop_code") + "): " + output.optString("error");
                        streamRequested = false;
                        EngineRuntime.EXECUTOR.execute(() -> NativeBridge.stopStream()); foreground();
                    });
                } catch (Exception error) { Log.e("ObsCaptureService", "Output state", error); }
            });
            main.postDelayed(this, 1500);
        }
    };
    static boolean isCapturing() { return cameraActive || microphoneActive || screenActive || uvcActive || streamRequested || qtBroadcastActive || sessionBgActive; }
    @Override public void onCreate() {
        super.onCreate();
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "obsandroid:capture");
        wakeLock.setReferenceCounted(false);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("obs_capture", "Captura OBS", NotificationManager.IMPORTANCE_LOW));
        try {
            AudioManager am = getSystemService(AudioManager.class);
            if (am != null) {
                audioHotplugCb = new AudioDeviceCallback() {
                    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                        if (!audioHotplugPrimed) {
                            audioHotplugPrimed = true;
                            return;
                        }
                        if (!microphoneActive || added == null || microphones.isEmpty()) return;
                        java.util.List<String> toRestart = new java.util.ArrayList<>();
                        for (String spec : microphones.keySet()) {
                            if ("builtin".equals(spec)) continue; // Never hijack explicit phone mic
                            for (AudioDeviceInfo d : added) {
                                if (!d.isSource()) continue;
                                if ("default".equals(spec)) {
                                    if (AudioCaptureController.isUsbOrExternalAudio(d.getType())
                                        && !AudioCaptureController.isWebcamAudioDevice(CaptureService.this, d)) {
                                        toRestart.add(spec);
                                        break;
                                    }
                                } else if (AudioCaptureController.matchesDeviceSpec(spec, d)) {
                                    toRestart.add(spec);
                                    break;
                                }
                            }
                        }
                        if (!toRestart.isEmpty()) {
                            main.post(() -> {
                                for (String spec : toRestart) {
                                    Log.i("ObsAudio", "Audio input hot-plugged -> rebinding spec=" + spec);
                                    restartMicSpec(spec);
                                }
                            });
                        }
                    }
                    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                        if (!microphoneActive || removed == null || microphones.isEmpty()) return;
                        java.util.List<String> toRestart = new java.util.ArrayList<>();
                        for (String spec : microphones.keySet()) {
                            if ("builtin".equals(spec)) continue;
                            for (AudioDeviceInfo d : removed) {
                                if (d.isSource() && AudioCaptureController.isUsbOrExternalAudio(d.getType())) {
                                    toRestart.add(spec);
                                    break;
                                }
                            }
                        }
                        if (!toRestart.isEmpty()) {
                            main.post(() -> {
                                for (String spec : toRestart) {
                                    Log.i("ObsAudio", "External audio input removed -> refreshing spec=" + spec);
                                    restartMicSpec(spec);
                                }
                            });
                        }
                    }
                };
                am.registerAudioDeviceCallback(audioHotplugCb, main);
            }
        } catch (Throwable ignored) {}
    }
    private void foreground() {
        int specialFlag = android.os.Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
        int types = (cameraActive ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA : 0) |
            (microphoneActive ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0) |
            (screenActive ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : 0) |
            ((streamRequested || uvcActive || qtBroadcastActive || sessionBgActive) ? specialFlag : 0);
        boolean anyWork = isCapturing();
        if (!anyWork) {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
            stopSelf();
            return;
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, QtObsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CaptureService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String statusText = (streamRequested || qtBroadcastActive)
            ? "Transmisión / grabación OBS activa"
            : (cameraActive || microphoneActive || screenActive || uvcActive)
                ? "Captura activa · cámara, micrófono, USB o pantalla"
                : "Sesión de estudio activa en segundo plano";
        Notification.Builder nb = new Notification.Builder(this, "obs_capture")
            .setSmallIcon(R.drawable.ic_stat_obs)
            .setLargeIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setContentTitle("OBS Studio")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(open);
        if (cameraActive || microphoneActive || screenActive || uvcActive || streamRequested) {
            nb.addAction(android.R.drawable.ic_media_pause, "Detener captura", stop);
        }
        Notification notification = nb.build();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29 && types != 0) {
                startForeground(301, notification, types);
            } else {
                startForeground(301, notification);
            }
        } catch (Throwable t) {
            Log.w("ObsCaptureService", "startForeground fallback (types=" + types + "): " + t.getMessage());
            try {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    startForeground(301, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(301, notification);
                }
            } catch (Throwable t2) {
                Log.w("ObsCaptureService", "startForeground specialUse fallback failed: " + t2.getMessage());
                stopSelf();
                return;
            }
        }
        if (wakeLock != null && (cameraActive || microphoneActive || screenActive || uvcActive || streamRequested || qtBroadcastActive)) {
            try { wakeLock.acquire(4L * 60L * 60L * 1000L); } catch (Throwable ignored) {}
        } else if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Throwable ignored) {}
        }
    }
    private void failed(String source, Object controller) {
        main.post(() -> {
            Log.e("ObsCaptureService", "Capture stopped: " + source);
            if ("camera".equals(source) && (controller == null || controller == camera)) {
                cameraActive = false; CameraController c = camera; camera = null; stopCameraAsync(c);
            } else if ("microphone".equals(source)) {
                if (controller == null) {
                    microphoneActive = false;
                    stopAllMicsLocked();
                } else {
                    String foundKey = null;
                    for (java.util.Map.Entry<String, AudioCaptureController> e : microphones.entrySet()) {
                        if (e.getValue() == controller) { foundKey = e.getKey(); break; }
                    }
                    if (foundKey == null) return; // Stale callback from replaced controller
                    AudioCaptureController m = microphones.remove(foundKey);
                    microphoneActive = !microphones.isEmpty();
                    stopMicAsync(m, !microphoneActive);
                }
            } else if ("screen".equals(source) && (controller == null || controller == screen)) {
                screenActive = false; ScreenController s = screen; screen = null; stopScreenAsync(s);
            } else if ("uvc".equals(source) && (controller == null || controller == uvc)) {
                uvcActive = false; UsbUvcController u = uvc; uvc = null; stopUvcAsync(u);
            } else {
                return; // Stale callback from a replaced controller
            }
            foreground();
        });
    }
    private void failed(String source) { failed(source, null); }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        try {
            if (STOP.equals(action)) { stopSelf(); return START_NOT_STICKY; }
            if (STREAM_KEEPALIVE.equals(action)) {
                qtBroadcastActive = intent.getBooleanExtra("active", false);
                foreground();
                return START_NOT_STICKY;
            }
            if (SESSION_KEEPALIVE.equals(action)) {
                sessionBgActive = intent.getBooleanExtra("active", false);
                foreground();
                return START_NOT_STICKY;
            }
            if (STREAM_START.equals(action)) {
                if (streamRequested) return START_NOT_STICKY;
                streamRequested = true; streamError = ""; foreground();
                main.removeCallbacks(streamMonitor); main.postDelayed(streamMonitor, 1500);
                String settings = intent.getStringExtra("settings");
                EngineRuntime.EXECUTOR.execute(() -> {
                    String error;
                    try { error = NativeBridge.startStream(settings); } catch (Exception failure) { error = failure.toString(); }
                    if (!error.isEmpty()) {
                        streamError = error;
                        main.post(() -> { streamRequested = false; foreground(); });
                    }
                });
            } else if (STREAM_STOP.equals(action)) {
                streamRequested = false;
                EngineRuntime.EXECUTOR.execute(() -> NativeBridge.stopStream()); foreground();
            } else if (CAMERA_CONFIG.equals(action)) {
                boolean enabled = intent.getBooleanExtra("enabled", true);
                String devId = intent.getStringExtra("deviceId");
                if (devId == null || devId.isEmpty()) devId = "default";
                float zoom = intent.getFloatExtra("zoom", 1.0f);
                int exposure = intent.getIntExtra("exposure", 0);
                if (!enabled) {
                    cameraActive = false;
                    CameraController oldCam = camera; camera = null;
                    stopCameraAsync(oldCam);
                    foreground();
                    VtuberSourceManager.onMainCameraStateChanged(this, false);
                } else if (!cameraActive || camera == null || !devId.equals(currentCameraId)) {
                    currentCameraId = devId;
                    currentZoom = zoom;
                    currentExposure = exposure;
                    cameraActive = true;
                    foreground();
                    CameraController oldCam = camera;
                    final CameraController[] ref = new CameraController[1];
                    CameraController nextCam = new CameraController(this, devId, zoom, exposure, () -> failed("camera", ref[0]));
                    ref[0] = nextCam;
                    camera = nextCam;
                    STOP_EXECUTOR.execute(() -> {
                        try { FaceTrackerManager.stopBackgroundFrontCamera(); } catch (Throwable ignored) {}
                        if (oldCam != null) { try { oldCam.stop(); } catch (Throwable ignored) {} }
                        main.post(() -> {
                            if (camera == nextCam && cameraActive) nextCam.start();
                        });
                    });
                } else {
                    if (Math.abs(zoom - currentZoom) > 0.01f) {
                        currentZoom = zoom;
                        camera.setZoom(zoom);
                    }
                    if (exposure != currentExposure) {
                        currentExposure = exposure;
                        camera.setExposure(exposure);
                    }
                }
            } else if (MIC_CONFIG.equals(action)) {
                boolean enabled = intent.getBooleanExtra("enabled", true);
                String devIdStr = intent.getStringExtra("deviceId");
                java.util.Set<String> desiredSpecs = new java.util.LinkedHashSet<>();
                if (enabled && devIdStr != null && !devIdStr.isEmpty()) {
                    for (String line : devIdStr.split("\n")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) desiredSpecs.add(trimmed);
                    }
                }
                if (enabled && desiredSpecs.isEmpty()) desiredSpecs.add("default");

                if (!enabled || desiredSpecs.isEmpty()) {
                    microphoneActive = false;
                    stopAllMicsLocked();
                    foreground();
                } else {
                    microphoneActive = true;
                    foreground();
                    // 1) Stop any previously active mic specs no longer requested
                    java.util.List<AudioCaptureController> toStop = new java.util.ArrayList<>();
                    java.util.Iterator<java.util.Map.Entry<String, AudioCaptureController>> it = microphones.entrySet().iterator();
                    while (it.hasNext()) {
                        java.util.Map.Entry<String, AudioCaptureController> entry = it.next();
                        if (!desiredSpecs.contains(entry.getKey())) {
                            toStop.add(entry.getValue());
                            it.remove();
                        }
                    }
                    // 2) Start any newly requested mic specs concurrently
                    java.util.List<AudioCaptureController> toStart = new java.util.ArrayList<>();
                    for (String spec : desiredSpecs) {
                        if (!microphones.containsKey(spec)) {
                            final AudioCaptureController[] ref = new AudioCaptureController[1];
                            AudioCaptureController nextMic = new AudioCaptureController(this, null, spec, () -> failed("microphone", ref[0]));
                            ref[0] = nextMic;
                            microphones.put(spec, nextMic);
                            toStart.add(nextMic);
                        }
                    }
                    if (!toStop.isEmpty() || !toStart.isEmpty()) {
                        STOP_EXECUTOR.execute(() -> {
                            for (AudioCaptureController oldMic : toStop) {
                                try { oldMic.stop(false); } catch (Throwable ignored) {}
                            }
                            main.post(() -> {
                                if (!microphoneActive) return;
                                for (AudioCaptureController nextMic : toStart) {
                                    if (microphones.containsValue(nextMic)) nextMic.start();
                                }
                            });
                        });
                    }
                }
            } else if (UVC_CONFIG.equals(action)) {
                boolean enabled = intent.getBooleanExtra("enabled", true);
                String devName = intent.getStringExtra("deviceId");
                if (devName == null || devName.isEmpty()) devName = "default";
                if (!enabled) {
                    uvcActive = false;
                    UsbUvcController oldUvc = uvc; uvc = null;
                    stopUvcAsync(oldUvc);
                    foreground();
                } else if (!uvcActive || uvc == null || !devName.equals(currentUvcDevice)) {
                    currentUvcDevice = devName;
                    uvcActive = true;
                    foreground();
                    UsbUvcController oldUvc = uvc;
                    final UsbUvcController[] ref = new UsbUvcController[1];
                    UsbUvcController nextUvc = new UsbUvcController(this, () -> failed("uvc", ref[0]));
                    ref[0] = nextUvc;
                    uvc = nextUvc;
                    final String targetDev = devName;
                    STOP_EXECUTOR.execute(() -> {
                        if (oldUvc != null) { try { oldUvc.stop(); } catch (Throwable ignored) {} }
                        main.post(() -> {
                            if (uvc == nextUvc && uvcActive) nextUvc.start(targetDev);
                        });
                    });
                }
            } else if (CAMERA.equals(action)) {
                cameraActive = !cameraActive;
                foreground();
                if (cameraActive) { camera = new CameraController(this, front, () -> failed("camera")); camera.start(); }
                else { CameraController oldCam = camera; camera = null; stopCameraAsync(oldCam); }
            } else if (MIC.equals(action)) {
                microphoneActive = !microphoneActive;
                foreground();
                if (microphoneActive) {
                    final AudioCaptureController[] ref = new AudioCaptureController[1];
                    AudioCaptureController m = new AudioCaptureController(this, null, "default", () -> failed("microphone", ref[0]));
                    ref[0] = m;
                    microphones.put("default", m);
                    m.start();
                } else {
                    stopAllMicsLocked();
                }
            } else if (SCREEN_AUDIO.equals(action)) {
                if (screenActive && screen != null) {
                    screen.setAudioEnabled(intent.getBooleanExtra("audio", true));
                    int maxDim = intent.getIntExtra("maxDim", 0);
                    if (maxDim >= 720) screen.setMaxDimension(maxDim);
                }
            } else if (SCREEN.equals(action)) {
                if (screenActive) { screenActive = false; ScreenController oldSc = screen; screen = null; stopScreenAsync(oldSc); foreground(); }
                else {
                    Intent consent = intent.getParcelableExtra("consent");
                    if (consent == null) throw new IllegalArgumentException("A fresh MediaProjection consent is required");
                    screenActive = true; foreground();
                    screen = new ScreenController(this, () -> failed("screen"));
                    screen.start(intent.getIntExtra("resultCode", 0), consent,
                                 intent.getBooleanExtra("audio", false),
                                 intent.getIntExtra("maxDim", 1920));
                }
            } else if (SWITCH_CAMERA.equals(action)) {
                front = !front;
                currentCameraId = front ? "front" : "default";
                if (cameraActive) {
                    CameraController oldCam = camera; camera = null;
                    stopCameraAsync(oldCam);
                    camera = new CameraController(this, currentCameraId, currentZoom, currentExposure, () -> failed("camera")); camera.start();
                }
            } else if (ZOOM.equals(action) && camera != null) {
                currentZoom = intent.getFloatExtra("zoom", 1f);
                camera.setZoom(currentZoom);
            } else if (EXPOSURE.equals(action) && camera != null) {
                currentExposure = intent.getIntExtra("ev", 0);
                camera.setExposure(currentExposure);
            }
        } catch (Exception error) { Log.e("ObsCaptureService", "Capture service command error", error); }
        return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId) {
        Log.w("ObsCaptureService", "Foreground service timeout (startId=" + startId + "), stopping service cleanly");
        sessionBgActive = false;
        if (!cameraActive && !microphoneActive && !screenActive && !uvcActive && !streamRequested && !qtBroadcastActive) {
            stopSelf(startId);
        } else {
            foreground();
        }
    }
    @Override public void onTimeout(int startId, int fgsType) {
        Log.w("ObsCaptureService", "Foreground service type timeout (fgsType=" + fgsType + "), preserving process");
        sessionBgActive = false;
        if (!cameraActive && !microphoneActive && !screenActive && !uvcActive && !streamRequested && !qtBroadcastActive) {
            stopSelf(startId);
        } else {
            foreground();
        }
    }
    @Override public void onDestroy() {
        cameraActive = microphoneActive = screenActive = uvcActive = false;
        streamRequested = false;
        qtBroadcastActive = false;
        sessionBgActive = false;
        main.removeCallbacks(streamMonitor);
        try {
            AudioManager am = getSystemService(AudioManager.class);
            if (am != null && audioHotplugCb != null) {
                am.unregisterAudioDeviceCallback(audioHotplugCb);
                audioHotplugCb = null;
            }
        } catch (Throwable ignored) {}
        CameraController oldCam = camera; camera = null;
        ScreenController oldSc = screen; screen = null;
        UsbUvcController oldUvc = uvc; uvc = null;
        stopCameraAsync(oldCam);
        stopAllMicsLocked();
        stopScreenAsync(oldSc);
        stopUvcAsync(oldUvc);
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        // NEVER call EngineRuntime.releaseIfIdle() / obs_shutdown() here: Qt OBS owns libobs lifecycle!
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
