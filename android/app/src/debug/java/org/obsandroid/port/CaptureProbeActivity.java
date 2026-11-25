// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.os.Bundle;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/** Debug-only device stimulus: real playback -> MediaProjection -> OBS -> GPU readback/mixer. */
public final class CaptureProbeActivity extends Activity {
    private volatile boolean cancelled;
    private AudioTrack track;
    private static <T> T engine(Callable<T> work) throws Exception { return EngineRuntime.EXECUTOR.submit(work).get(); }
    private JSONObject snapshot() throws Exception { return new JSONObject(engine(() -> NativeBridge.state())); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView pattern = new TextView(this);
        pattern.setBackgroundColor(Color.rgb(200, 20, 180)); pattern.setTextColor(Color.WHITE);
        pattern.setText("OBS · Prueba real de pantalla y audio interno\nTono de 440 Hz durante 16 segundos");
        pattern.setTextSize(22); pattern.setPadding(40, 80, 40, 0); setContentView(pattern);
        new Thread(() -> runProbe(pattern), "OBS-CaptureProbe").start();
    }
    private void runProbe(TextView label) {
        JSONObject report = new JSONObject();
        double oldGain = 1; boolean oldMute = false;
        try {
            JSONObject before = snapshot();
            report.put("before", before);
            JSONObject mic = before.getJSONObject("captures").getJSONObject("android_microphone");
            oldGain = mic.optDouble("volume", 1); oldMute = mic.optBoolean("muted");
            engine(() -> { NativeBridge.captureGain(1, 1, true); NativeBridge.captureGain(3, 1, false); NativeBridge.probe(true); return null; });
            int samples = 48000;
            short[] tone = new short[samples * 2];
            for (int i = 0; i < samples; ++i) tone[2*i] = tone[2*i+1] = (short)(8192 * Math.sin(2*Math.PI*440*i/48000));
            track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(tone.length*2).build();
            track.write(tone, 0, tone.length); track.setLoopPoints(0, samples, -1); track.play();
            double audibleRaw = 0, audibleMixed = 0, mutedMixed = 0;
            for (int i = 0; i < 16 && !cancelled; ++i) {
                Thread.sleep(1000);
                JSONObject state = snapshot(); JSONObject captures = state.getJSONObject("captures");
                if (i >= 3 && i <= 7) {
                    audibleRaw = Math.max(audibleRaw, captures.getJSONObject("android_playback_audio").getDouble("peak"));
                    audibleMixed = Math.max(audibleMixed, captures.getDouble("mixed_peak"));
                }
                if (i == 8) engine(() -> { NativeBridge.captureGain(3, 1, true); return null; });
                if (i >= 11) mutedMixed = Math.max(mutedMixed, captures.getDouble("mixed_peak"));
                report.put("after", state);
            }
            JSONObject after = report.getJSONObject("after");
            report.put("playback_raw_peak", audibleRaw); report.put("audible_mixed_peak", audibleMixed); report.put("muted_mixed_peak", mutedMixed);
            boolean video = after.getLong("probe_matches") > 20;
            boolean audio = audibleRaw > .02 && audibleMixed > .02 && mutedMixed < .001;
            report.put("video_pass", video); report.put("audio_pass", audio); report.put("pass", video && audio && !cancelled);
        } catch (Exception error) { try { report.put("error", error.toString()); report.put("pass", false); } catch (Exception ignored) {} }
        finally {
            if (track != null) { track.stop(); track.release(); track = null; }
            final float gain = (float)oldGain; final boolean mute = oldMute;
            try { engine(() -> { NativeBridge.probe(false); NativeBridge.captureGain(1, gain, mute); NativeBridge.captureGain(3, 1, false); return null; }); } catch (Exception ignored) {}
        }
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "capture-probe.json"))) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { android.util.Log.e("ObsProbe", "Report", error); }
        android.util.Log.i("ObsProbe", report.toString());
        runOnUiThread(() -> label.setText("Prueba terminada: " + report.optBoolean("pass") + "\n" + report.optString("error", "Consulta capture-probe.json")));
    }
    @Override public void onDestroy() { cancelled = true; super.onDestroy(); }
}
