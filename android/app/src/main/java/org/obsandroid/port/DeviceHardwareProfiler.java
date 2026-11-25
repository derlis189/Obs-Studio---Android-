// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

final class DeviceHardwareProfiler {
    private static final int COLOR_FORMAT_SURFACE = 0x7F000789;

    private DeviceHardwareProfiler() {}

    private static String getSystemProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            String val = (String) get.invoke(null, key, "");
            return val != null ? val.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readFirstLine(File f) {
        if (f == null || !f.canRead()) return "";
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            return line != null ? line.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String capitalizeWords(String s) {
        if (s == null || s.isEmpty()) return "";
        String[] parts = s.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            if (p.length() <= 3 && p.equals(p.toUpperCase(Locale.ROOT))) {
                sb.append(p);
            } else {
                sb.append(Character.toUpperCase(p.charAt(0)));
                if (p.length() > 1) sb.append(p.substring(1));
            }
        }
        return sb.toString();
    }

    private static String friendlySocName(String socMfg, String socModel, String board, String hardware) {
        String code = (socModel != null && !socModel.isEmpty() && !"unknown".equalsIgnoreCase(socModel))
            ? socModel.trim()
            : (!board.isEmpty() ? board.trim() : hardware.trim());
        String upper = code.toUpperCase(Locale.ROOT);
        String mfg = (socMfg != null && !socMfg.isEmpty() && !"unknown".equalsIgnoreCase(socMfg))
            ? capitalizeWords(socMfg)
            : "";

        String marketing = "";
        // Qualcomm Snapdragon mappings
        if (upper.contains("SM8750")) marketing = "Snapdragon 8 Elite";
        else if (upper.contains("SM8735")) marketing = "Snapdragon 8s Gen 4";
        else if (upper.contains("SM8650")) marketing = "Snapdragon 8 Gen 3";
        else if (upper.contains("SM8635")) marketing = "Snapdragon 8s Gen 3";
        else if (upper.contains("SM8550")) marketing = "Snapdragon 8 Gen 2";
        else if (upper.contains("SM8475")) marketing = "Snapdragon 8+ Gen 1";
        else if (upper.contains("SM8450")) marketing = "Snapdragon 8 Gen 1";
        else if (upper.contains("SM8350")) marketing = "Snapdragon 888";
        else if (upper.contains("SM8250")) marketing = "Snapdragon 865 / 870";
        else if (upper.contains("SM7675")) marketing = "Snapdragon 7+ Gen 3";
        else if (upper.contains("SM7635")) marketing = "Snapdragon 7s Gen 3";
        else if (upper.contains("SM7550")) marketing = "Snapdragon 7 Gen 3";
        else if (upper.contains("SM7475")) marketing = "Snapdragon 7+ Gen 2";
        else if (upper.contains("SM7435")) marketing = "Snapdragon 7s Gen 2";
        else if (upper.contains("SM7325")) marketing = "Snapdragon 778G";
        else if (upper.startsWith("SM7")) marketing = "Snapdragon Serie 7";
        else if (upper.startsWith("SM6")) marketing = "Snapdragon Serie 6";
        else if (upper.startsWith("SM4")) marketing = "Snapdragon Serie 4";
        // Samsung Exynos mappings
        else if (upper.contains("S5E9955")) marketing = "Exynos 2500";
        else if (upper.contains("S5E9945")) marketing = "Exynos 2400";
        else if (upper.contains("S5E9925")) marketing = "Exynos 2200";
        else if (upper.contains("S5E8855")) marketing = "Exynos 1580";
        else if (upper.contains("S5E8845")) marketing = "Exynos 1480";
        else if (upper.contains("S5E8835")) marketing = "Exynos 1380";
        else if (upper.contains("S5E8825")) marketing = "Exynos 1280";
        else if (upper.startsWith("S5E") || upper.startsWith("EXYNOS")) marketing = "Samsung Exynos";
        // MediaTek Dimensity / Helio mappings
        else if (upper.contains("MT6991")) marketing = "Dimensity 9400";
        else if (upper.contains("MT6989")) marketing = "Dimensity 9300";
        else if (upper.contains("MT6985")) marketing = "Dimensity 9200";
        else if (upper.contains("MT6983")) marketing = "Dimensity 9000";
        else if (upper.contains("MT6897")) marketing = "Dimensity 8300";
        else if (upper.contains("MT6896") || upper.contains("MT6895")) marketing = "Dimensity 8200 / 8100";
        else if (upper.contains("MT6886")) marketing = "Dimensity 7200";
        else if (upper.startsWith("MT68") || upper.startsWith("MT69")) marketing = "MediaTek Dimensity";
        else if (upper.startsWith("MT67")) marketing = "MediaTek Helio";
        // Google Tensor
        else if (upper.contains("ZUMA") && upper.contains("PRO")) marketing = "Google Tensor G4";
        else if (upper.contains("ZUMA")) marketing = "Google Tensor G3";
        else if (upper.contains("GS201")) marketing = "Google Tensor G2";
        else if (upper.contains("GS101") || upper.contains("TENSOR")) marketing = "Google Tensor";

        StringBuilder sb = new StringBuilder();
        if (!mfg.isEmpty() && (marketing.isEmpty() || !marketing.toLowerCase(Locale.ROOT).contains(mfg.toLowerCase(Locale.ROOT)))) {
            sb.append(mfg).append(' ');
        }
        if (!marketing.isEmpty()) {
            sb.append(marketing);
            if (!code.isEmpty() && !marketing.equalsIgnoreCase(code)) {
                sb.append(" (").append(code).append(')');
            }
        } else if (!code.isEmpty()) {
            sb.append(code);
        } else {
            sb.append("ARM64 SoC");
        }
        return sb.toString().trim();
    }

    private static boolean checkSizeAndRate(MediaCodecInfo.VideoCapabilities vcaps, int w, int h, int fps) {
        if (vcaps == null) return false;
        try {
            if (vcaps.areSizeAndRateSupported(w, h, fps)) return true;
            // Some vendor encoders declare portrait/landscape transposed bounds
            return vcaps.areSizeAndRateSupported(h, w, fps);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String collectJson(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            String mfg = capitalizeWords(Build.MANUFACTURER);
            String model = Build.MODEL != null ? Build.MODEL.trim() : "";
            String marketName = getSystemProp("ro.product.marketname");
            if (marketName.isEmpty()) marketName = getSystemProp("ro.config.marketing_name");
            if (marketName.isEmpty() && ctx != null) {
                try {
                    String globalName = Settings.Global.getString(ctx.getContentResolver(), "device_name");
                    if (globalName != null && !globalName.trim().isEmpty() && globalName.length() <= 48) {
                        marketName = globalName.trim();
                    }
                } catch (Throwable ignored) {}
            }

            String displayPhone;
            if (!marketName.isEmpty()) {
                displayPhone = marketName;
                if (!mfg.isEmpty() && !displayPhone.toLowerCase(Locale.ROOT).startsWith(mfg.toLowerCase(Locale.ROOT))) {
                    displayPhone = mfg + " " + displayPhone;
                }
                if (!model.isEmpty() && !displayPhone.contains(model)) {
                    displayPhone = displayPhone + " (" + model + ")";
                }
            } else if (!mfg.isEmpty() && !model.toLowerCase(Locale.ROOT).startsWith(mfg.toLowerCase(Locale.ROOT))) {
                displayPhone = mfg + " " + model;
            } else {
                displayPhone = !model.isEmpty() ? model : "Dispositivo Android ARM64";
            }

            out.put("manufacturer", mfg);
            out.put("model", model);
            out.put("device_name", displayPhone);
            out.put("android_release", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "15");
            out.put("sdk_int", Build.VERSION.SDK_INT);

            // SoC / Processor detection
            String socMfg = "";
            String socModel = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                socMfg = Build.SOC_MANUFACTURER != null ? Build.SOC_MANUFACTURER : "";
                socModel = Build.SOC_MODEL != null ? Build.SOC_MODEL : "";
            }
            if (socMfg.isEmpty() || "unknown".equalsIgnoreCase(socMfg)) socMfg = getSystemProp("ro.soc.manufacturer");
            if (socModel.isEmpty() || "unknown".equalsIgnoreCase(socModel)) socModel = getSystemProp("ro.soc.model");
            String board = Build.BOARD != null ? Build.BOARD : getSystemProp("ro.board.platform");
            String hardware = Build.HARDWARE != null ? Build.HARDWARE : getSystemProp("ro.hardware");
            String socFriendly = friendlySocName(socMfg, socModel, board, hardware);

            // Per-core frequencies & big.LITTLE cluster topology
            int runtimeCores = Math.max(1, Runtime.getRuntime().availableProcessors());
            List<Integer> coreMaxMhz = new ArrayList<>();
            for (int i = 0; i < 16; ++i) {
                File cpuDir = new File("/sys/devices/system/cpu/cpu" + i);
                if (!cpuDir.exists()) {
                    if (i >= runtimeCores) break;
                    continue;
                }
                String khzStr = readFirstLine(new File(cpuDir, "cpufreq/cpuinfo_max_freq"));
                if (khzStr.isEmpty()) khzStr = readFirstLine(new File(cpuDir, "cpufreq/scaling_max_freq"));
                int mhz = 0;
                if (!khzStr.isEmpty()) {
                    try { mhz = Math.round(Long.parseLong(khzStr) / 1000.0f); } catch (NumberFormatException ignored) {}
                }
                coreMaxMhz.add(mhz);
            }
            int totalCores = Math.max(runtimeCores, coreMaxMhz.size());
            int peakMhz = 0;
            int bigCores = 0;
            int perfCores = 0;
            for (int mhz : coreMaxMhz) {
                if (mhz > peakMhz) peakMhz = mhz;
                if (mhz >= 2500) bigCores++;
                if (mhz >= 2000) perfCores++;
            }

            // Group cores by rounded 0.05 GHz bucket for clean cluster display
            List<Integer> sortedMhz = new ArrayList<>(coreMaxMhz);
            sortedMhz.sort(Collections.reverseOrder());
            Map<String, Integer> clusterCounts = new LinkedHashMap<>();
            for (int mhz : sortedMhz) {
                if (mhz <= 0) continue;
                String ghz = String.format(Locale.US, "%.2f GHz", mhz / 1000.0f);
                clusterCounts.put(ghz, clusterCounts.getOrDefault(ghz, 0) + 1);
            }
            StringBuilder clusterSummary = new StringBuilder();
            clusterSummary.append(totalCores).append(" núcleos");
            if (!clusterCounts.isEmpty()) {
                clusterSummary.append(" (");
                int idx = 0;
                for (Map.Entry<String, Integer> e : clusterCounts.entrySet()) {
                    if (idx++ > 0) clusterSummary.append(" + ");
                    clusterSummary.append(e.getValue()).append("×").append(e.getKey());
                }
                clusterSummary.append(')');
            }

            out.put("soc_name", socFriendly);
            out.put("soc_model", socModel);
            out.put("cpu_cores", totalCores);
            out.put("big_cores", bigCores);
            out.put("perf_cores", perfCores);
            out.put("max_freq_mhz", peakMhz);
            out.put("cpu_clusters", clusterSummary.toString());

            // Memory (RAM)
            long ramTotalMb = 0;
            long ramAvailMb = 0;
            boolean lowMem = false;
            if (ctx != null) {
                ActivityManager am = ctx.getSystemService(ActivityManager.class);
                if (am != null) {
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    ramTotalMb = mi.totalMem / (1024L * 1024L);
                    ramAvailMb = mi.availMem / (1024L * 1024L);
                    lowMem = mi.lowMemory;
                }
            }
            out.put("ram_total_mb", ramTotalMb);
            out.put("ram_avail_mb", ramAvailMb);
            out.put("low_memory", lowMem);

            // Display physical landscape resolution & refresh rate
            int screenW = 1920;
            int screenH = 1080;
            int refreshHz = 60;
            if (ctx != null) {
                try {
                    WindowManager wm = ctx.getSystemService(WindowManager.class);
                    if (wm != null) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
                            screenW = Math.max(b.width(), b.height());
                            screenH = Math.min(b.width(), b.height());
                        } else if (wm.getDefaultDisplay() != null) {
                            DisplayMetrics dm = new DisplayMetrics();
                            wm.getDefaultDisplay().getRealMetrics(dm);
                            screenW = Math.max(dm.widthPixels, dm.heightPixels);
                            screenH = Math.min(dm.widthPixels, dm.heightPixels);
                        }
                        Display d = wm.getDefaultDisplay();
                        if (d != null) refreshHz = Math.round(d.getRefreshRate());
                    }
                } catch (Throwable ignored) {}
            }
            out.put("screen_width", screenW);
            out.put("screen_height", screenH);
            out.put("refresh_hz", refreshHz);

            // Hardware MediaCodec capabilities (H.264, HEVC, AAC)
            MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            inspectVideoEncoder(codecs, "video/avc", "h264", out);
            inspectVideoEncoder(codecs, "video/hevc", "hevc", out);
            inspectAudioEncoder(codecs, out);

            // Thermal, Battery & Power Save
            int thermal = 0;
            boolean powerSave = false;
            int batteryPct = -1;
            float batteryTempC = 0.0f;
            boolean charging = false;
            if (ctx != null) {
                try {
                    PowerManager pm = ctx.getSystemService(PowerManager.class);
                    if (pm != null) {
                        if (Build.VERSION.SDK_INT >= 29) thermal = pm.getCurrentThermalStatus();
                        powerSave = pm.isPowerSaveMode();
                    }
                } catch (Throwable ignored) {}
                try {
                    Intent bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (bi != null) {
                        int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                        if (lvl >= 0 && sc > 0) batteryPct = Math.round(lvl * 100f / sc);
                        int tempRaw = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                        if (tempRaw > 0) batteryTempC = tempRaw / 10.0f;
                        int st = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        charging = (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
                    }
                } catch (Throwable ignored) {}
            }
            out.put("thermal_status", thermal);
            out.put("power_save", powerSave);
            out.put("battery_pct", batteryPct);
            out.put("battery_temp_c", batteryTempC);
            out.put("charging", charging);

            // Active Network & Upstream Link Estimation
            String netType = "Sin red";
            int netUpKbps = 0;
            int netDownKbps = 0;
            boolean netMetered = false;
            if (ctx != null) {
                try {
                    ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
                    if (cm != null) {
                        Network active = cm.getActiveNetwork();
                        if (active != null) {
                            NetworkCapabilities nc = cm.getNetworkCapabilities(active);
                            if (nc != null) {
                                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) netType = "Ethernet / USB";
                                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) netType = "Wi-Fi";
                                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) netType = "Datos móviles (5G/4G)";
                                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) netType = "VPN";
                                else netType = "Conectada";
                                netUpKbps = nc.getLinkUpstreamBandwidthKbps();
                                netDownKbps = nc.getLinkDownstreamBandwidthKbps();
                                netMetered = !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            out.put("network_type", netType);
            out.put("net_up_kbps", netUpKbps);
            out.put("net_down_kbps", netDownKbps);
            out.put("net_metered", netMetered);

            // Compute objective device performance tier (1..4)
            boolean h264Hw = out.optBoolean("h264_hw", false) && out.optBoolean("h264_surface", false);
            boolean h264_1080p60 = out.optBoolean("h264_1080p60", false);
            boolean h264_1080p30 = out.optBoolean("h264_1080p30", false);

            int tier;
            String tierLabel;
            if (h264Hw && h264_1080p60 && ramTotalMb >= 7200 && (peakMhz >= 2800 || bigCores >= 2)) {
                tier = 4;
                tierLabel = "Gama Alta (Ultra · 1080p60 GPU Zero-Copy)";
            } else if (h264Hw && (h264_1080p60 || h264_1080p30) && ramTotalMb >= 5200 && peakMhz >= 2200) {
                tier = 3;
                tierLabel = "Gama Media-Alta (1080p30 / 720p60 GPU)";
            } else if (h264Hw && ramTotalMb >= 3500) {
                tier = 2;
                tierLabel = "Gama Media (720p30 Equilibrado)";
            } else {
                tier = 1;
                tierLabel = "Modo Ligero / Ahorro (540p/720p 30 FPS)";
            }
            out.put("tier", tier);
            out.put("tier_label", tierLabel);
        } catch (Throwable t) {
            try { out.put("error", t.toString()); } catch (Throwable ignored) {}
        }
        return out.toString();
    }

    private static void inspectVideoEncoder(MediaCodecInfo[] codecs, String mime, String prefix, JSONObject out) {
        try {
            for (MediaCodecInfo codec : codecs) {
                if (!codec.isEncoder() || codec.isAlias() || !codec.isHardwareAccelerated()) continue;
                MediaCodecInfo.CodecCapabilities caps;
                try {
                    caps = codec.getCapabilitiesForType(mime);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                MediaCodecInfo.VideoCapabilities vcaps = caps.getVideoCapabilities();
                if (vcaps == null) continue;

                boolean surfaceOk = false;
                if (caps.colorFormats != null) {
                    for (int cf : caps.colorFormats) {
                        if (cf == COLOR_FORMAT_SURFACE) {
                            surfaceOk = true;
                            break;
                        }
                    }
                }
                if (!surfaceOk) continue;
                MediaCodecInfo.EncoderCapabilities ecaps = caps.getEncoderCapabilities();
                boolean cbrOk = ecaps != null && ecaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                boolean vbrOk = ecaps != null && ecaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);

                out.put(prefix + "_hw", true);
                out.put(prefix + "_codec", codec.getName());
                out.put(prefix + "_surface", surfaceOk);
                out.put(prefix + "_max_instances", caps.getMaxSupportedInstances());
                out.put(prefix + "_cbr", cbrOk);
                out.put(prefix + "_vbr", vbrOk);
                out.put(prefix + "_max_w", vcaps.getSupportedWidths().getUpper());
                out.put(prefix + "_max_h", vcaps.getSupportedHeights().getUpper());
                out.put(prefix + "_max_bitrate_kbps", vcaps.getBitrateRange().getUpper() / 1000);
                out.put(prefix + "_2160p60", checkSizeAndRate(vcaps, 3840, 2160, 60));
                out.put(prefix + "_2160p30", checkSizeAndRate(vcaps, 3840, 2160, 30));
                out.put(prefix + "_1440p60", checkSizeAndRate(vcaps, 2560, 1440, 60));
                out.put(prefix + "_1440p30", checkSizeAndRate(vcaps, 2560, 1440, 30));
                out.put(prefix + "_1080p60", checkSizeAndRate(vcaps, 1920, 1080, 60));
                out.put(prefix + "_1080p30", checkSizeAndRate(vcaps, 1920, 1080, 30));
                out.put(prefix + "_720p60", checkSizeAndRate(vcaps, 1280, 720, 60));
                out.put(prefix + "_720p30", checkSizeAndRate(vcaps, 1280, 720, 30));
                return;
            }
            out.put(prefix + "_hw", false);
        } catch (Throwable ignored) {}
    }

    private static void inspectAudioEncoder(MediaCodecInfo[] codecs, JSONObject out) {
        try {
            MediaFormat audio = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2);
            audio.setInteger(MediaFormat.KEY_AAC_PROFILE, 2);
            audio.setInteger(MediaFormat.KEY_BIT_RATE, 160000);
            String bestName = "";
            boolean bestHw = false;
            for (MediaCodecInfo codec : codecs) {
                if (!codec.isEncoder() || codec.isAlias()) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType("audio/mp4a-latm");
                    if (caps.isFormatSupported(audio) && (bestName.isEmpty() || codec.isHardwareAccelerated())) {
                        bestName = codec.getName();
                        bestHw = codec.isHardwareAccelerated();
                        if (bestHw) break;
                    }
                } catch (IllegalArgumentException ignored) {}
            }
            out.put("aac_codec", bestName);
            out.put("aac_hw", bestHw);
        } catch (Throwable ignored) {}
    }
}
