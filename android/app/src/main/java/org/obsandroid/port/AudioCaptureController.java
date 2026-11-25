// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class AudioCaptureController {
    private final Context context;
    private final int kind;
    private final MediaProjection projection;
    private final String preferredDeviceSpec;
    private final Runnable failure;
    private volatile boolean running;
    private AudioRecord recorder;
    private Thread thread;
    private final Object lifecycle = new Object();

    AudioCaptureController(MediaProjection projection, Runnable failure) {
        this(null, projection, "default", failure);
    }

    AudioCaptureController(Context context, MediaProjection projection, int preferredDeviceId, Runnable failure) {
        this(context, projection, preferredDeviceId > 0 ? String.valueOf(preferredDeviceId) : "default", failure);
    }

    AudioCaptureController(Context context, MediaProjection projection, String preferredDeviceSpec, Runnable failure) {
        this.context = context;
        this.projection = projection;
        this.preferredDeviceSpec = (preferredDeviceSpec == null || preferredDeviceSpec.isEmpty()) ? "default" : preferredDeviceSpec;
        this.kind = projection == null ? 1 : 3;
        this.failure = failure;
    }

    static boolean isUsbOrExternalAudio(int type) {
        if (type == AudioDeviceInfo.TYPE_USB_DEVICE
            || type == AudioDeviceInfo.TYPE_USB_HEADSET
            || type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            || type == AudioDeviceInfo.TYPE_LINE_ANALOG
            || type == AudioDeviceInfo.TYPE_LINE_DIGITAL
            || type == AudioDeviceInfo.TYPE_HDMI
            || type == AudioDeviceInfo.TYPE_HDMI_ARC
            || type == AudioDeviceInfo.TYPE_AUX_LINE) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_HDMI_EARC) return true;
        return false;
    }

    private static boolean isIgnoredInputType(int type) {
        return type == AudioDeviceInfo.TYPE_TELEPHONY
            || type == AudioDeviceInfo.TYPE_FM_TUNER
            || type == AudioDeviceInfo.TYPE_TV_TUNER
            || type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            || type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX
            || type == AudioDeviceInfo.TYPE_IP;
    }

    private static String normalizeName(CharSequence cs) {
        if (cs == null) return "";
        return cs.toString().replace('\t', ' ').replace('\n', ' ').replace('|', ' ').trim();
    }

    private static Set<String> findUsbVideoAudioNames(Context context) {
        Set<String> videoAudioNames = new LinkedHashSet<>();
        if (context == null) return videoAudioNames;
        try {
            UsbManager um = context.getSystemService(UsbManager.class);
            if (um == null) return videoAudioNames;
            for (UsbDevice ud : um.getDeviceList().values()) {
                boolean hasVideo = false;
                boolean hasAudio = false;
                for (int i = 0; i < ud.getInterfaceCount(); ++i) {
                    int cls = ud.getInterface(i).getInterfaceClass();
                    if (cls == UsbConstants.USB_CLASS_VIDEO) hasVideo = true;
                    if (cls == UsbConstants.USB_CLASS_AUDIO) hasAudio = true;
                }
                if (hasVideo && hasAudio) {
                    String prod = normalizeName(ud.getProductName()).toLowerCase(java.util.Locale.ROOT);
                    if (!prod.isEmpty()) videoAudioNames.add(prod);
                }
            }
        } catch (Throwable ignored) {}
        return videoAudioNames;
    }

    static boolean isWebcamAudioDevice(Context context, AudioDeviceInfo dev) {
        if (dev == null) return false;
        int type = dev.getType();
        if (type != AudioDeviceInfo.TYPE_USB_DEVICE && type != AudioDeviceInfo.TYPE_USB_HEADSET) {
            return false;
        }
        String nameLower = normalizeName(dev.getProductName()).toLowerCase(java.util.Locale.ROOT);
        Set<String> uvcNames = findUsbVideoAudioNames(context);
        for (String uvc : uvcNames) {
            if (!nameLower.isEmpty() && (nameLower.contains(uvc) || uvc.contains(nameLower))) {
                return true;
            }
        }
        if (nameLower.contains("webcam") || nameLower.contains("uvc") || nameLower.contains("camera")
            || nameLower.contains("camara") || nameLower.contains("c920") || nameLower.contains("c922")
            || nameLower.contains("c270") || nameLower.contains("brio") || nameLower.contains("elgato cam")) {
            return true;
        }
        return false;
    }

    private static String describeInputType(int type, boolean isWebcam) {
        if (isWebcam) return "Micrófono Webcam USB";
        if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_ACCESSORY) return "Interfaz Audio USB";
        if (type == AudioDeviceInfo.TYPE_USB_HEADSET) return "Audio USB";
        if (type == AudioDeviceInfo.TYPE_LINE_ANALOG || type == AudioDeviceInfo.TYPE_LINE_DIGITAL || type == AudioDeviceInfo.TYPE_AUX_LINE) return "Entrada de Línea";
        if (type == AudioDeviceInfo.TYPE_HDMI || type == AudioDeviceInfo.TYPE_HDMI_ARC) return "Audio HDMI";
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) return "Micrófono con cable";
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET) return "Micrófono Bluetooth";
        if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) return "Micrófono integrado";
        return "Entrada externa";
    }

    private static String makeDeviceToken(AudioDeviceInfo dev) {
        String name = normalizeName(dev.getProductName());
        if (!name.isEmpty()) {
            return "dev:" + dev.getType() + "|" + name;
        }
        return "id:" + dev.getId();
    }

    static boolean matchesDeviceSpec(String spec, AudioDeviceInfo dev) {
        if (spec == null || spec.isEmpty() || "default".equals(spec) || dev == null) return false;
        if ("builtin".equals(spec)) return dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC;
        if (spec.startsWith("dev:")) {
            String[] parts = spec.substring(4).split("\\|", 2);
            if (parts.length == 2 && !parts[1].isEmpty()) {
                try {
                    int wantType = Integer.parseInt(parts[0]);
                    if (dev.getType() == wantType && parts[1].equalsIgnoreCase(normalizeName(dev.getProductName()))) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {}
            }
            return false;
        }
        if (spec.startsWith("id:")) {
            try { return Integer.parseInt(spec.substring(3)) == dev.getId(); } catch (NumberFormatException ignored) { return false; }
        }
        try {
            return Integer.parseInt(spec) == dev.getId();
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    static String listAudioInputs(Context context) {
        StringBuilder ext = new StringBuilder();
        StringBuilder other = new StringBuilder();
        StringBuilder webcams = new StringBuilder();
        try {
            AudioManager am = context.getSystemService(AudioManager.class);
            if (am == null) return "";
            AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
            Log.i("ObsAudio", "Enumerating " + devs.length + " Android audio input devices");
            int builtinCount = 0;
            Set<String> seenTokens = new LinkedHashSet<>();
            for (AudioDeviceInfo dev : devs) {
                int type = dev.getType();
                if (isIgnoredInputType(type)) continue;
                boolean webcam = isWebcamAudioDevice(context, dev);
                String name = normalizeName(dev.getProductName());
                if (name.isEmpty()) name = "Dispositivo " + dev.getId();
                String prefix = describeInputType(type, webcam);
                String addr = "";
                if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    builtinCount++;
                    String rawAddr = dev.getAddress();
                    if (rawAddr != null && !rawAddr.isEmpty()) addr = " [" + rawAddr + "]";
                    else if (builtinCount > 1) addr = " #" + builtinCount;
                }
                String token = makeDeviceToken(dev);
                if (!seenTokens.add(token)) {
                    token = "id:" + dev.getId();
                    seenTokens.add(token);
                }
                String line = token + "\t" + prefix + ": " + name + addr + " (ID " + dev.getId() + ")\n";
                Log.i("ObsAudio", "  input id=" + dev.getId() + " type=" + type + " webcam=" + webcam + " name=" + name);
                if (webcam) webcams.append(line);
                else if (isUsbOrExternalAudio(type)) ext.append(line);
                else other.append(line);
            }
            // Log any USB Audio Class devices visible to UsbManager for diagnostics
            try {
                UsbManager um = context.getSystemService(UsbManager.class);
                if (um != null) {
                    for (UsbDevice ud : um.getDeviceList().values()) {
                        for (int i = 0; i < ud.getInterfaceCount(); ++i) {
                            UsbInterface intf = ud.getInterface(i);
                            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) {
                                Log.i("ObsAudio", "  UsbManager audio class dev=" + ud.getProductName()
                                    + " vid=" + ud.getVendorId() + " pid=" + ud.getProductId());
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Exception e) {
            Log.w("ObsAudio", "listAudioInputs failed", e);
        }
        return ext.toString() + other.toString() + webcams.toString();
    }

    private AudioDeviceInfo findPreferredDevice() {
        if (context == null) return null;
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am == null) return null;
        AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        AudioDeviceInfo firstDedicatedUsb = null;
        AudioDeviceInfo firstBuiltin = null;
        for (AudioDeviceInfo dev : devs) {
            int type = dev.getType();
            if (isIgnoredInputType(type)) continue;
            if (matchesDeviceSpec(preferredDeviceSpec, dev)) {
                return dev;
            }
            if (firstBuiltin == null && type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                firstBuiltin = dev;
            }
            if (firstDedicatedUsb == null && isUsbOrExternalAudio(type) && !isWebcamAudioDevice(context, dev)) {
                firstDedicatedUsb = dev;
            }
        }
        if ("builtin".equals(preferredDeviceSpec)) return firstBuiltin;
        // On default (or if a previously selected USB device is unplugged): prefer a dedicated USB audio interface;
        // if only a USB webcam is connected, explicitly stay on the phone's built-in microphone instead of hijacking!
        return firstDedicatedUsb != null ? firstDedicatedUsb : firstBuiltin;
    }

    private AudioRecord buildMicRecorder(AudioDeviceInfo preferred, int sampleRate, int channels, int audioSource, boolean useIndexMask) {
        int channelMask = channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
        int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = sampleRate * channels * 2 / 10;
        int buffer = Math.max(minBuf * 2, 16384);
        try {
            AudioFormat.Builder fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate);
            if (useIndexMask) {
                fmt.setChannelIndexMask(channels == 1 ? 0x1 : 0x3);
            } else {
                fmt.setChannelMask(channelMask);
            }
            AudioRecord rec = new AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(fmt.build())
                .setBufferSizeInBytes(buffer)
                .build();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                return null;
            }
            if (preferred != null) {
                boolean ok = rec.setPreferredDevice(preferred);
                Log.i("ObsAudio", "setPreferredDevice(" + preferred.getProductName() + " id=" + preferred.getId()
                    + " type=" + preferred.getType() + " idxMask=" + useIndexMask + " sr=" + sampleRate
                    + " ch=" + channels + " src=" + audioSource + ") -> " + ok);
            }
            return rec;
        } catch (Throwable t) {
            Log.w("ObsAudio", "buildMicRecorder failed sr=" + sampleRate + " ch=" + channels
                + " src=" + audioSource + " idxMask=" + useIndexMask + ": " + t.getMessage());
            return null;
        }
    }

    private List<Integer> candidateSampleRates(AudioDeviceInfo dev) {
        Set<Integer> rates = new LinkedHashSet<>();
        if (dev != null) {
            int[] supported = dev.getSampleRates();
            if (supported != null && supported.length > 0) {
                // Prefer 48000 / 44100 first if device natively supports them, then remaining native rates
                for (int pref : new int[]{48000, 44100, 32000, 16000}) {
                    for (int r : supported) {
                        if (r == pref) rates.add(r);
                    }
                }
                for (int r : supported) {
                    if (r >= 8000 && r <= 96000) rates.add(r);
                }
            }
        }
        rates.add(48000);
        rates.add(44100);
        rates.add(32000);
        rates.add(16000);
        return new ArrayList<>(rates);
    }

    private int[] candidateChannels(AudioDeviceInfo dev, boolean ext, boolean webcam) {
        if (dev != null) {
            int[] chs = dev.getChannelCounts();
            if (chs != null && chs.length > 0) {
                boolean has1 = false, has2 = false;
                for (int c : chs) {
                    if (c == 1) has1 = true;
                    if (c >= 2) has2 = true;
                }
                if (has1 && !has2) return new int[]{1};
                if (has2 && !has1) return new int[]{2};
            }
        }
        return (ext && !webcam) ? new int[]{2, 1} : new int[]{1, 2};
    }

    @SuppressWarnings("MissingPermission") void start() {
        running = true;
        EngineRuntime.EXECUTOR.execute(() -> {
            synchronized (lifecycle) {
                if (!running) return;
                try {
                    if (!NativeBridge.enableCapture(kind, true)) throw new IllegalStateException("OBS audio source unavailable");
                    int channels = kind == 1 ? 1 : 2;
                    int sampleRate = 48000;
                    if (projection == null) {
                        AudioDeviceInfo preferred = findPreferredDevice();
                        boolean ext = preferred != null && isUsbOrExternalAudio(preferred.getType());
                        boolean webcam = preferred != null && isWebcamAudioDevice(context, preferred);
                        List<Integer> rates = candidateSampleRates(preferred);
                        int[] chOrder = candidateChannels(preferred, ext, webcam);
                        int[] srcOrder = ext
                            ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.CAMCORDER}
                            : new int[]{MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.CAMCORDER};
                        // For USB/external audio devices (interfaces AND webcams), try channelIndexMask first!
                        // On Android AIDL/Qualcomm/Samsung HALs, "primary input" has maxActiveCount=1 and shares
                        // Built-In Mic + USB, whereas "hifi_input" is a dedicated USB-only MixPort that supports
                        // AUDIO_CHANNEL_INDEX_MASK_2 (0x80000003) and runs CONCURRENTLY with Built-In Mic!
                        boolean[] idxOrder = ext ? new boolean[]{true, false} : new boolean[]{false};

                        AudioRecord fallbackRec = null;
                        int fallbackCh = channels;
                        int fallbackSr = sampleRate;
                        boolean matchedExact = false;

                        outer:
                        for (boolean useIdx : idxOrder) {
                            for (int src : srcOrder) {
                                for (int ch : chOrder) {
                                    for (int sr : rates) {
                                        AudioRecord cand = buildMicRecorder(preferred, sr, ch, src, useIdx);
                                        if (cand == null) continue;
                                        try {
                                            cand.startRecording();
                                        } catch (Throwable t) {
                                            cand.release();
                                            continue;
                                        }
                                        AudioDeviceInfo routed = cand.getRoutedDevice();
                                        boolean exactRoute = (preferred == null)
                                            || (routed != null && routed.getId() == preferred.getId());
                                        if (exactRoute) {
                                            if (fallbackRec != null) {
                                                try { fallbackRec.stop(); } catch (Throwable ignored) {}
                                                fallbackRec.release();
                                                fallbackRec = null;
                                            }
                                            recorder = cand;
                                            int actualCh = cand.getChannelCount();
                                            channels = actualCh > 0 ? actualCh : ch;
                                            sampleRate = cand.getSampleRate();
                                            matchedExact = true;
                                            break outer;
                                        }
                                        Log.w("ObsAudio", "Route mismatch for spec=" + preferredDeviceSpec
                                            + " (wanted id=" + preferred.getId() + " " + preferred.getProductName()
                                            + ", got id=" + (routed != null ? routed.getId() : -1)
                                            + " " + (routed != null ? routed.getProductName() : "null")
                                            + ") idxMask=" + useIdx + " src=" + src + " ch=" + ch + " sr=" + sr);
                                        if (fallbackRec == null) {
                                            fallbackRec = cand;
                                            int actualCh = cand.getChannelCount();
                                            fallbackCh = actualCh > 0 ? actualCh : ch;
                                            fallbackSr = cand.getSampleRate();
                                        } else {
                                            try { cand.stop(); } catch (Throwable ignored) {}
                                            cand.release();
                                        }
                                    }
                                }
                            }
                        }
                        if (!matchedExact && fallbackRec != null) {
                            recorder = fallbackRec;
                            channels = fallbackCh;
                            sampleRate = fallbackSr;
                        }
                        if (recorder != null) {
                            try {
                                recorder.addOnRoutingChangedListener(routing -> {
                                    AudioDeviceInfo r = routing != null ? routing.getRoutedDevice() : null;
                                    Log.i("ObsAudio", "Routing changed for spec=" + preferredDeviceSpec
                                        + " -> " + (r != null ? (r.getProductName() + " id=" + r.getId() + " type=" + r.getType()) : "null"));
                                }, null);
                            } catch (Throwable ignored) {}
                        }
                    } else {
                        int channelMask = AudioFormat.CHANNEL_IN_STEREO;
                        int buffer = Math.max(AudioRecord.getMinBufferSize(48000, channelMask, AudioFormat.ENCODING_PCM_16BIT), 16384);
                        recorder = new AudioRecord.Builder()
                            .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(48000)
                                .setChannelMask(channelMask)
                                .build())
                            .setBufferSizeInBytes(buffer)
                            .setAudioPlaybackCaptureConfig(new AudioPlaybackCaptureConfiguration.Builder(projection)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                .build())
                            .build();
                        sampleRate = recorder.getSampleRate();
                        recorder.startRecording();
                    }
                    if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                        throw new IllegalStateException("AudioRecord not initialized");
                    }
                    AudioRecord active = recorder;
                    int finalChannels = channels;
                    int finalSr = sampleRate > 0 ? sampleRate : 48000;
                    thread = new Thread(() -> capture(active, finalChannels, finalSr),
                        kind == 1 ? ("OBS-Mic-" + preferredDeviceSpec) : "OBS-PlaybackAudio");
                    thread.start();
                    AudioDeviceInfo routed = recorder.getRoutedDevice();
                    Log.i("ObsAudio", "AudioRecord started kind=" + kind + " preferredSpec=" + preferredDeviceSpec
                        + " routed=" + (routed != null ? (routed.getProductName() + " id=" + routed.getId() + " type=" + routed.getType()) : "null")
                        + " sampleRate=" + finalSr + " channels=" + finalChannels);
                } catch (Exception error) {
                    Log.e("ObsAudio", "Audio capture start failed", error);
                    failure.run();
                }
            }
        });
    }

    private void capture(AudioRecord active, int channels, int sampleRate) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * channels * 2).order(ByteOrder.nativeOrder());
        AudioTimestamp timestamp = new AudioTimestamp();
        long framesRead = 0;
        try {
            while (running) {
                buffer.clear();
                int bytes = active.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING);
                if (bytes <= 0) { if (running) throw new IllegalStateException("AudioRecord read " + bytes); break; }
                int frames = bytes / (2 * channels);
                long start = System.nanoTime() - frames * 1_000_000_000L / sampleRate;
                if (kind == 1 && active.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                    start = timestamp.nanoTime + (framesRead - timestamp.framePosition) * 1_000_000_000L / sampleRate;
                if (kind == 1) {
                    NativeBridge.audioFrameForDevice(preferredDeviceSpec, buffer, bytes, channels, sampleRate, start);
                } else {
                    NativeBridge.audioFrame(kind, buffer, bytes, channels, sampleRate, start);
                }
                framesRead += frames;
            }
        } catch (Exception error) { if (running) { Log.e("ObsAudio", "Audio capture failed", error); failure.run(); } }
        finally {
            synchronized (lifecycle) {
                // stop() may time out while a blocking AudioRecord read is unwinding.
                // Keep the recorder alive until this thread has left the native read call.
                if (!running && recorder == active) {
                    try { active.release(); } catch (IllegalStateException ignored) { }
                    recorder = null;
                    if (thread == Thread.currentThread()) thread = null;
                }
            }
        }
    }

    void stop() {
        stop(true);
    }

    void stop(boolean notifyDisable) {
        AudioRecord active;
        Thread worker;
        synchronized (lifecycle) {
            running = false;
            active = recorder;
            worker = thread;
        }
        if (active != null) {
            try { active.stop(); } catch (IllegalStateException ignored) { }
        }
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(1500); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
        boolean workerStopped = worker == null || !worker.isAlive();
        synchronized (lifecycle) {
            if (active != null && recorder == active && workerStopped) {
                try { active.release(); } catch (IllegalStateException ignored) { }
                recorder = null;
                if (thread == worker) thread = null;
            }
        }
        if (!workerStopped) Log.e("ObsAudio", "Audio producer did not stop within 1500 ms (kind=" + kind + ")");
        if (notifyDisable) {
            EngineRuntime.EXECUTOR.execute(() -> NativeBridge.enableCapture(kind, false));
        }
    }
}
