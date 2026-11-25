// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug-only: private test scene or real Program, local RTMP and MKV only. */
public final class PerformanceProbeActivity extends Activity {
    static { System.loadLibrary("obs-android-jni"); }
    private static native String command(String json);
    private static void writeAtomic(File destination, JSONObject report) throws Exception {
        File temporary = new File(destination.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(report.toString().getBytes(StandardCharsets.UTF_8));
        }
        java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Prueba local de rendimiento OBS en curso");
        setContentView(text);
        String mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "smoke";
        final String selectedMode = mode;
        final int duration = Math.max(12, Math.min(3600, getIntent().getIntExtra("seconds", 15)));
        WebRemoteServer.ensureStarted(this);
        if (getIntent().getBooleanExtra("remote_only", false)) { finish(); return; }
        new Thread(() -> {
            JSONObject report = new JSONObject();
            JSONArray samples = new JSONArray();
            File destination = new File(getFilesDir(), "performance-" + selectedMode + ".json");
            try {
                report.put("run_id", System.currentTimeMillis());
                JSONObject request = new JSONObject().put("action", "begin").put("mode", selectedMode)
                    .put("file", new File(getFilesDir(), "performance-browser.html").getAbsolutePath())
                    .put("recording", new File(getFilesDir(), "performance-" + selectedMode + ".mp4").getAbsolutePath());
                report.put("begin", new JSONObject(command(request.toString())));
                if (!report.getJSONObject("begin").optBoolean("ok")) throw new IllegalStateException(report.getJSONObject("begin").toString());
                for (int second = 0; second < duration; ++second) {
                    Thread.sleep(1000);
                    JSONObject sample = new JSONObject(command("{\"action\":\"sample\"}"));
                    sample.put("second", second + 1);
                    sample.put("health", new JSONObject(QtObsActivity.getDeviceHealthJson()));
                    samples.put(sample);
                    if ("smoke".equals(selectedMode) && second == 7)
                        report.put("stop_first", new JSONObject(command("{\"action\":\"stop_first\"}")));
                    if ("codecs".equals(selectedMode)) {
                        if (second == 3) command("{\"action\":\"independent_start\"}");
                        if (second == 8) command("{\"action\":\"independent_stop\"}");
                    }
                    if (sample.getJSONObject("health").optInt("thermal") >= 4)
                        throw new IllegalStateException("Prueba local detenida por estado térmico crítico de Android");
                    if ("resize".equals(selectedMode)) {
                        if (second == 3 || second == 12) command("{\"action\":\"resize\",\"width\":1280,\"height\":720}");
                        if (second == 6) command("{\"action\":\"visibility\",\"visible\":false}");
                        if (second == 9) command("{\"action\":\"visibility\",\"visible\":true}");
                        if (second == 15) command("{\"action\":\"resize\",\"width\":640,\"height\":360}");
                    }
                    report.put("samples", samples);
                    try (FileOutputStream trace = new FileOutputStream(new File(getFilesDir(), "performance-" + selectedMode + ".ndjson"), second != 0)) {
                        trace.write((sample.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                    JSONObject progress = new JSONObject().put("run_id", report.getLong("run_id"))
                        .put("mode", selectedMode).put("count", samples.length()).put("last", sample);
                    writeAtomic(new File(getFilesDir(), "performance-" + selectedMode + "-progress.json"), progress);
                }
                report.put("completed", true);
            } catch (Exception error) {
                try { report.put("error", error.toString()); } catch (Exception ignored) { }
            } finally {
                try { report.put("end", new JSONObject(command("{\"action\":\"end\"}"))); }
                catch (Exception ignored) { }
            }
            try { writeAtomic(destination, report); } catch (Exception error) { android.util.Log.e("ObsPerformanceProbe", "Report write failed", error); }
            runOnUiThread(() -> { text.setText("Prueba terminada: " + selectedMode); finish(); });
        }, "OBS-PerformanceProbe").start();
    }
}
