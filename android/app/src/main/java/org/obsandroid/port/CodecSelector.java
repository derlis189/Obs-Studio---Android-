// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import org.json.JSONObject;

final class CodecSelector {
    static String videoConfig(String json) {
        JSONObject out = new JSONObject();
        try {
            JSONObject req = new JSONObject(json);
            String mime = req.getString("mime");
            int w = req.getInt("width"), h = req.getInt("height");
            double fps = req.getDouble("fps");
            int bitrate = req.getInt("bitrate");
            int mode = req.getInt("mode");
            String requested = req.optString("codec", "");
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!codec.isEncoder() || !codec.isHardwareAccelerated() || codec.isAlias()) continue;
                if (!requested.isEmpty() && !"auto".equals(requested) && !requested.equals(codec.getName())) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(mime);
                    MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                    if (vc == null || !vc.areSizeAndRateSupported(w, h, fps) || !vc.getBitrateRange().contains(bitrate)) continue;
                    boolean surface = false;
                    for (int cf : caps.colorFormats) if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
                    if (!surface || !caps.getEncoderCapabilities().isBitrateModeSupported(mode)) continue;
                    out.put("codec", codec.getName());
                    out.put("max_instances", Math.max(1, caps.getMaxSupportedInstances()));
                    out.put("hardware", true);
                    return out.toString();
                } catch (IllegalArgumentException ignored) { }
            }
            out.put("error", "No hay codificador hardware Surface compatible con la resolución, FPS, bitrate y modo seleccionados");
        } catch (Exception e) {
            try { out.put("error", "No se pudieron consultar las capacidades del codificador"); } catch (Exception ignored) { }
        }
        return out.toString();
    }

    static JSONObject settings(String server, String key, int bitrate, int keyint) throws Exception {
        return settings(server, key, bitrate, keyint, 160);
    }

    static JSONObject settings(String server, String key, int bitrate, int keyint, int audioBitrate) throws Exception {
        if (audioBitrate < 128 || audioBitrate > 320 || audioBitrate % 32 != 0)
            throw new IllegalArgumentException("El bitrate AAC debe estar entre 128 y 320 kbps, en pasos de 32");
        JSONObject result = new JSONObject();
        MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        for (MediaCodecInfo codec : codecs) {
            if (!codec.isEncoder() || !codec.isHardwareAccelerated() || codec.isAlias()) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType("video/avc");
                if (!caps.getVideoCapabilities().areSizeAndRateSupported(1280, 720, 30) || !caps.getVideoCapabilities().getBitrateRange().contains(bitrate*1000)) continue;
                boolean baseline = false;
                for (MediaCodecInfo.CodecProfileLevel profile : caps.profileLevels) if (profile.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) baseline = true;
                if (!baseline) continue;
                int color = 0;
                for (int supported : caps.colorFormats) if (supported == 21) color = 21;
                if (color == 0) for (int supported : caps.colorFormats) if (supported == 19) color = 19;
                if (color == 0) continue;
                MediaCodecInfo.EncoderCapabilities encoding = caps.getEncoderCapabilities();
                int mode = encoding.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ? 2 :
                    encoding.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ? 1 : -1;
                if (mode < 0) continue;
                result.put("codec_name", codec.getName()); result.put("hardware", true);
                result.put("color_format", color); result.put("bitrate_mode", mode); break;
            } catch (IllegalArgumentException ignored) { }
        }
        if (!result.has("codec_name")) throw new IllegalStateException("No hay codificador H.264 hardware compatible con 720p30 y buffers YUV");
        MediaFormat audio = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2);
        audio.setInteger(MediaFormat.KEY_AAC_PROFILE, 2); audio.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate * 1000);
        // Prefer hardware AAC. Most phones expose AAC as an efficient system software codec.
        String aac = null; boolean hardware = false;
        for (MediaCodecInfo codec : codecs) {
            if (!codec.isEncoder() || codec.isAlias()) continue;
            try {
                if (codec.getCapabilitiesForType("audio/mp4a-latm").isFormatSupported(audio) && (aac == null || codec.isHardwareAccelerated())) {
                    aac = codec.getName(); hardware = codec.isHardwareAccelerated(); if (hardware) break;
                }
            } catch (IllegalArgumentException ignored) { }
        }
        if (aac == null) throw new IllegalStateException("El dispositivo no ofrece AAC LC estéreo 48 kHz a " + audioBitrate + " kbps; prueba una tasa menor");
        result.put("audio_codec", aac); result.put("audio_hardware", hardware);
        result.put("audio_bitrate", audioBitrate);
        result.put("server", server); result.put("key", key); result.put("bitrate", bitrate); result.put("keyint_sec", keyint);
        return result;
    }
}
