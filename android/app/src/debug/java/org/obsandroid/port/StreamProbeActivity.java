// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.widget.TextView;
import android.graphics.Color;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.FileOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Debug-only controlled RTMP destination, real service + hardware encoder + original OBS output. */
public final class StreamProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this); label.setTextColor(Color.WHITE); label.setBackgroundColor(Color.rgb(13,18,26));
        label.setTextSize(18); label.setPadding(40,100,40,0); label.setText("Verificando RTMP desde libobs…"); setContentView(label);
        final boolean inputs = getIntent().getBooleanExtra("capture", false);
        if (inputs) {
            if (!CaptureService.cameraActive) startForegroundService(new Intent(this, CaptureService.class).setAction(CaptureService.CAMERA));
            if (!CaptureService.microphoneActive) startForegroundService(new Intent(this, CaptureService.class).setAction(CaptureService.MIC));
        }
        new Thread(() -> {
            JSONObject report = new JSONObject(); JSONArray samples = new JSONArray();
            try {
                JSONObject settings = CodecSelector.settings("rtmp://127.0.0.1:1935/live", "android", 2500, 2);
                report.put("selected_codecs", settings);
                runOnUiThread(() -> startForegroundService(new Intent(this, CaptureService.class).setAction(CaptureService.STREAM_START).putExtra("settings", settings.toString())));
                for (int i = 0; i < 26; ++i) {
                    Thread.sleep(1000);
                    JSONObject snapshot = new JSONObject(EngineRuntime.EXECUTOR.submit(() -> NativeBridge.state()).get()); samples.put(snapshot);
                    final String message = "RTMP · " + snapshot.getJSONObject("stream").toString(); runOnUiThread(() -> label.setText(message));
                    if (i == 12) EngineRuntime.EXECUTOR.submit(() -> NativeBridge.transition(true)).get();
                }
                report.put("samples", samples);
                JSONObject output = samples.getJSONObject(samples.length()-1).getJSONObject("stream");
                report.put("pass", output.getBoolean("active") && output.getLong("bytes") > 100000 && output.getLong("frames") > 300);
            } catch (Exception error) { try { report.put("error", error.toString()); report.put("pass", false); } catch (Exception ignored) {} }
            finally { runOnUiThread(() -> { if (inputs) stopService(new Intent(this, CaptureService.class)); else startService(new Intent(this, CaptureService.class).setAction(CaptureService.STREAM_STOP)); }); }
            try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "stream-probe.json"))) { output.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
            catch (Exception error) { android.util.Log.e("ObsStreamProbe", "Report", error); }
            android.util.Log.i("ObsStreamProbe", report.toString());
            runOnUiThread(() -> label.setText("Prueba RTMP terminada: " + report.optBoolean("pass") + "\n" + report.optString("error")));
        }, "OBS-StreamProbe").start();
    }
}
