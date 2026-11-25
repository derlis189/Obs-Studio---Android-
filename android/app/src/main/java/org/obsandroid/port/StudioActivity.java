package org.obsandroid.port;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Two Android surfaces driven by the real OBS renderer, with native scene controls. */
public final class StudioActivity extends Activity {
    private static final ExecutorService ENGINE = EngineRuntime.EXECUTOR;
    private final Handler meterHandler = new Handler(Looper.getMainLooper());
    private TextView status, programLabel, previewLabel;
    private LinearLayout panes;
    private boolean destroyed;
    private float lastX, lastY;
    private JSONObject captureState = new JSONObject();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            ENGINE.execute(() -> { if (!destroyed) showState(NativeBridge.state()); });
            meterHandler.postDelayed(this, 1500);
        }
    };
    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView label(String text, int color, float size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextColor(color); view.setTextSize(size); view.setPadding(dp(8), dp(8), dp(8), dp(8));
        return view;
    }
    private void showState(String result) {
        Log.i("ObsAndroidPort", result);
        runOnUiThread(() -> {
            if (destroyed) return;
            try {
                JSONObject state = new JSONObject(result);
                JSONObject captures = state.optJSONObject("captures");
                if (captures != null) captureState = captures;
                String captureStatus = "";
                if (captures != null) {
                    JSONObject camera = captures.getJSONObject("android_camera"), mic = captures.getJSONObject("android_microphone"), screen = captures.getJSONObject("android_screen");
                    captureStatus = "\nCámara " + camera.getLong("frames") + " frames · Mic " + mic.getLong("frames") + " muestras · Pantalla " + screen.getLong("frames") + " frames";
                }
                status.setText(state.getString("status") + captureStatus);
                JSONObject stream = state.optJSONObject("stream");
                if (stream != null && CaptureService.streamRequested) status.append("\nRTMP: " + (stream.optBoolean("active") ? "activo" : stream.optBoolean("reconnecting") ? "reconectando" : "conectando") + " · " + stream.optLong("bytes")/1024 + " KB");
                if (!CaptureService.streamError.isEmpty()) status.append("\n" + CaptureService.streamError);
                if (stream != null && !stream.optString("error").isEmpty()) status.append("\n" + stream.optString("error"));
                programLabel.setText("PROGRAM · " + state.getString("program"));
                previewLabel.setText("PREVIEW · " + state.getString("preview"));
            } catch (Exception error) { status.setText(result); }
        });
    }
    private void copyAssets(String path, File destination) throws Exception {
        String[] children = getAssets().list(path);
        if (children != null && children.length > 0) {
            if (!destination.isDirectory() && !destination.mkdirs()) throw new IllegalStateException("Cannot create assets directory");
            for (String child : children) copyAssets(path + "/" + child, new File(destination, child));
        } else {
            try (InputStream input = getAssets().open(path); FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[16384]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        EngineRuntime.UI_CLIENTS.incrementAndGet();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(13, 18, 26));
        root.setPadding(dp(10), dp(28), dp(10), dp(16));
        root.addView(label("OBS Android Port · Experimental", Color.WHITE, 18));
        status = label("Iniciando motor…", Color.rgb(153, 174, 199), 13); root.addView(status);
        panes = new LinearLayout(this);
        boolean portrait = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        panes.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        root.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
        for (int i = 0; i < 2; ++i) {
            final int viewIndex = i;
            LinearLayout pane = new LinearLayout(this); pane.setOrientation(LinearLayout.VERTICAL);
            pane.setPadding(dp(4), 0, dp(4), 0);
            panes.addView(pane, portrait ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(0, -1, 1));
            TextView title = label(i == 0 ? "PROGRAM" : "PREVIEW", i == 0 ? Color.rgb(255, 105, 105) : Color.rgb(84, 224, 163), 13);
            pane.addView(title);
            if (i == 0) programLabel = title; else previewLabel = title;
            SurfaceView surface = new SurfaceView(this);
            pane.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
            surface.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) { }
                @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    ENGINE.execute(() -> {
                        try {
                            boolean attached = NativeBridge.attach(holder.getSurface(), viewIndex, width, height);
                            if (!attached) runOnUiThread(() -> status.setText("ERROR: superficie de video no disponible"));
                        } catch (Throwable error) { Log.e("ObsAndroidPort", "Surface attach", error); }
                    });
                }
                @Override public void surfaceDestroyed(SurfaceHolder holder) {
                    // Android may abandon the buffer queue as soon as this callback
                    // returns. Finish OBS rendering before releasing that surface.
                    try { ENGINE.submit(() -> NativeBridge.detach(viewIndex)).get(2, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (Exception error) { Log.e("ObsAndroidPort", "Surface detach deadline", error); }
                }
            });
            if (i == 1) {
                ScaleGestureDetector scaling = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        float factor = detector.getScaleFactor(); ENGINE.execute(() -> NativeBridge.scalePreviewImage(factor)); return true;
                    }
                });
                surface.setOnTouchListener((view, event) -> {
                    scaling.onTouchEvent(event);
                    if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1 && !scaling.isInProgress()) {
                        float factor = Math.min(view.getWidth() / 1280f, view.getHeight() / 720f);
                        if (factor > 0) {
                            float dx = (event.getX() - lastX) / factor, dy = (event.getY() - lastY) / factor;
                            ENGINE.execute(() -> NativeBridge.movePreviewImage(dx, dy));
                        }
                    }
                    lastX = event.getX(); lastY = event.getY();
                    return true;
                });
            }
        }
        root.addView(label("Escenas: Escena 1 / Escena 2    ·    Fuentes: fondo + imagen", Color.rgb(180, 193, 211), 13));
        root.addView(label("Arrastra o pellizca la imagen en Preview para editarla.", Color.rgb(153, 174, 199), 13));
        LinearLayout controls = new LinearLayout(this); root.addView(controls);
        for (int i = 0; i < 2; ++i) {
            final boolean fade = i == 1;
            Button button = new Button(this); button.setText(fade ? "Fundido" : "Corte");
            controls.addView(button, new LinearLayout.LayoutParams(0, dp(56), 1));
            button.setOnClickListener(view -> ENGINE.execute(() -> showState(NativeBridge.transition(fade))));
        }
        Button rotate = new Button(this); rotate.setText("Girar");
        controls.addView(rotate, new LinearLayout.LayoutParams(0, dp(56), 1));
        rotate.setOnClickListener(view -> setRequestedOrientation(
            getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT ?
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        Button sources = new Button(this); sources.setText("Fuentes");
        controls.addView(sources, new LinearLayout.LayoutParams(0, dp(56), 1));
        sources.setOnClickListener(view -> sourcesMenu());
        Button streaming = new Button(this); streaming.setText("Configurar / iniciar RTMP"); root.addView(streaming);
        streaming.setOnClickListener(view -> streamDialog());
        ENGINE.execute(() -> {
            try {
                copyAssets("libobs", new File(getFilesDir(), "libobs"));
                copyAssets("obs-modules", new File(getFilesDir(), "obs-modules"));
                showState(NativeBridge.start(getFilesDir().getAbsolutePath(), new File(getFilesDir(), "libobs").getAbsolutePath(), getApplicationInfo().nativeLibraryDir));
            } catch (Throwable error) { showState("ERROR: " + error); }
        });
        setContentView(root);
        meterHandler.postDelayed(refresh, 2000);
    }
    @Override public void onDestroy() {
        destroyed = true;
        meterHandler.removeCallbacks(refresh);
        EngineRuntime.UI_CLIENTS.decrementAndGet();
        EngineRuntime.releaseIfIdle();
        super.onDestroy();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        boolean portrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        panes.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int i = 0; i < panes.getChildCount(); ++i)
            panes.getChildAt(i).setLayoutParams(portrait ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(0, -1, 1));
    }
    private void command(String action) { startForegroundService(new Intent(this, CaptureService.class).setAction(action)); }
    private void sourcesMenu() {
        String[] entries = {CaptureService.cameraActive ? "Detener cámara" : "Añadir cámara", "Cambiar frontal / trasera",
            CaptureService.microphoneActive ? "Detener micrófono" : "Añadir micrófono", CaptureService.screenActive ? "Detener pantalla" : "Capturar pantalla + audio interno",
            "Volumen y silencio", "Zoom de cámara", "Exposición de cámara", "Detener todas las capturas"};
        new AlertDialog.Builder(this).setTitle("Fuentes OBS").setItems(entries, (dialog, which) -> {
            if (which == 0) {
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.CAMERA}, 101);
                else command(CaptureService.CAMERA);
            } else if (which == 1) {
                if (CaptureService.cameraActive) startService(new Intent(this, CaptureService.class).setAction(CaptureService.SWITCH_CAMERA));
            } else if (which == 2) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 102);
                else command(CaptureService.MIC);
            } else if (which == 3) {
                if (CaptureService.screenActive) command(CaptureService.SCREEN);
                else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 103);
                else requestProjection();
            } else if (which == 4) audioControls();
            else if (which == 5) new AlertDialog.Builder(this).setTitle("Zoom").setItems(new String[]{"1×", "2×", "4×"}, (d, index) -> {
                if (CaptureService.cameraActive) startService(new Intent(this, CaptureService.class).setAction(CaptureService.ZOOM).putExtra("zoom", index == 0 ? 1f : index == 1 ? 2f : 4f));
            }).show();
            else if (which == 6) new AlertDialog.Builder(this).setTitle("Compensación de exposición").setItems(new String[]{"−3 pasos", "0 pasos", "+3 pasos"}, (d, index) -> {
                if (CaptureService.cameraActive) startService(new Intent(this, CaptureService.class).setAction(CaptureService.EXPOSURE).putExtra("ev", (index-1)*3));
            }).show();
            else if (which == 7) stopService(new Intent(this, CaptureService.class));
        }).show();
    }
    private void audioControls() {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        String[] names = {"Micrófono", "Audio interno"};
        int[] kinds = {1, 3};
        for (int index = 0; index < 2; ++index) {
            final int kind = kinds[index];
            JSONObject initial = captureState.optJSONObject(kind == 1 ? "android_microphone" : "android_playback_audio");
            final boolean[] muted = {initial != null && initial.optBoolean("muted", false)};
            content.addView(label(names[index], Color.BLACK, 16));
            SeekBar gain = new SeekBar(this); gain.setMax(200); gain.setProgress((int)Math.round(100 * (initial == null ? 1 : initial.optDouble("volume", 1)))); content.addView(gain);
            gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) { boolean silent = muted[0]; ENGINE.execute(() -> NativeBridge.captureGain(kind, value / 100f, silent)); } }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            Button mute = new Button(this); mute.setText(muted[0] ? "Activar sonido" : "Silenciar"); content.addView(mute);
            mute.setOnClickListener(view -> { boolean silent = !muted[0]; muted[0] = silent; mute.setText(silent ? "Activar sonido" : "Silenciar"); ENGINE.execute(() -> NativeBridge.captureGain(kind, gain.getProgress()/100f, silent)); });
        }
        new AlertDialog.Builder(this).setTitle("Mezclador OBS").setView(content).setPositiveButton("Cerrar", null).show();
    }
    private void requestProjection() { startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 201); }
    private void streamDialog() {
        if (CaptureService.streamRequested) {
            new AlertDialog.Builder(this).setTitle("Transmisión RTMP").setMessage("¿Detener la transmisión actual?")
                .setPositiveButton("Detener", (d,w) -> command(CaptureService.STREAM_STOP)).setNegativeButton("Continuar", null).show(); return;
        }
        android.content.SharedPreferences prefs = getSharedPreferences("stream", MODE_PRIVATE);
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(16), 0, dp(16), 0);
        EditText server = new EditText(this); server.setHint("Servidor rtmp://servidor/aplicación"); server.setText(prefs.getString("server", "")); form.addView(server);
        EditText key = new EditText(this); key.setHint("Clave de transmisión"); key.setInputType(129); form.addView(key);
        EditText bitrate = new EditText(this); bitrate.setHint("Bitrate de video (kbps)"); bitrate.setInputType(2); bitrate.setText(String.valueOf(prefs.getInt("bitrate", 3500))); form.addView(bitrate);
        EditText keyint = new EditText(this); keyint.setHint("Intervalo keyframes (segundos)"); keyint.setInputType(2); keyint.setText("2"); form.addView(keyint);
        TextView format = label("H.264 hardware · 720p30 · AAC 48 kHz\nCBR o VBR según dispositivo", Color.BLACK, 13); form.addView(format);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Transmitir desde libobs").setView(form).setPositiveButton("Iniciar", null).setNegativeButton("Cancelar", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int kbps = Integer.parseInt(bitrate.getText().toString()), interval = Integer.parseInt(keyint.getText().toString());
                String url = server.getText().toString().trim();
                if (!url.startsWith("rtmp://") || kbps < 250 || kbps > 20000 || interval < 1 || interval > 10) throw new IllegalArgumentException("Usa RTMP, 250–20000 kbps y keyframes de 1–10 s");
                JSONObject config = CodecSelector.settings(url, key.getText().toString(), kbps, interval);
                prefs.edit().putString("server", url).putInt("bitrate", kbps).apply();
                startForegroundService(new Intent(this, CaptureService.class).setAction(CaptureService.STREAM_START).putExtra("settings", config.toString()));
                dialog.dismiss();
            } catch (Exception error) { format.setText(error.getMessage()); }
        })); dialog.show();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) { status.setText("El permiso de captura no fue concedido."); return; }
        if (request == 101) command(CaptureService.CAMERA);
        if (request == 102) command(CaptureService.MIC);
        if (request == 103) requestProjection();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 201 && result == RESULT_OK && data != null)
            startForegroundService(new Intent(this, CaptureService.class).setAction(CaptureService.SCREEN).putExtra("consent", data).putExtra("resultCode", result).putExtra("audio", true));
    }
}
