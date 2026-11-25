// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class NdiDiscoveryManager {
    private static final String TAG = "ObsNdiDiscovery";
    private static final String NDI_SERVICE_TYPE = "_ndi._tcp.";
    private static final Map<String, String> sDiscovered = new ConcurrentHashMap<>();
    private static NsdManager sNsdManager;
    private static NsdManager.DiscoveryListener sListener;
    private static WifiManager.MulticastLock sMulticastLock;
    private static boolean sRunning = false;

    public static synchronized void startDiscovery(Context ctx) {
        if (ctx == null || sRunning) return;
        try {
            Context app = ctx.getApplicationContext();
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                sMulticastLock = wm.createMulticastLock("obs-ndi-mdns");
                sMulticastLock.setReferenceCounted(false);
                sMulticastLock.acquire();
            }
            sNsdManager = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
            if (sNsdManager == null) return;
            sListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String regType) {
                    Log.i(TAG, "mDNS NDI discovery started: " + regType);
                }

                @Override
                public void onServiceFound(NsdServiceInfo service) {
                    if (service == null || service.getServiceName() == null) return;
                    final String rawName = service.getServiceName();
                    sDiscovered.putIfAbsent(rawName, "");
                    try {
                        sNsdManager.resolveService(service, new NsdManager.ResolveListener() {
                            @Override
                            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                                Log.d(TAG, "mDNS resolve failed (" + errorCode + ") for " + rawName);
                            }

                            @Override
                            public void onServiceResolved(NsdServiceInfo resolved) {
                                InetAddress host = resolved.getHost();
                                int port = resolved.getPort();
                                String addr = (host != null && host.getHostAddress() != null)
                                    ? (host.getHostAddress() + ":" + port) : "";
                                String name = resolved.getServiceName() != null ? resolved.getServiceName() : rawName;
                                sDiscovered.put(name, addr);
                                Log.i(TAG, "mDNS NDI source resolved: " + name + " -> " + addr);
                            }
                        });
                    } catch (Throwable ignored) {}
                }

                @Override
                public void onServiceLost(NsdServiceInfo service) {
                    if (service != null && service.getServiceName() != null) {
                        sDiscovered.remove(service.getServiceName());
                    }
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {}

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    Log.w(TAG, "mDNS start failed: " + errorCode);
                    sRunning = false;
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            };
            sNsdManager.discoverServices(NDI_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, sListener);
            sRunning = true;
        } catch (Throwable t) {
            Log.w(TAG, "startDiscovery failed", t);
        }
    }

    public static synchronized void stopDiscovery() {
        try {
            if (sRunning && sNsdManager != null && sListener != null) {
                sNsdManager.stopServiceDiscovery(sListener);
            }
        } catch (Throwable ignored) {}
        sRunning = false;
        sListener = null;
        try {
            if (sMulticastLock != null && sMulticastLock.isHeld()) {
                sMulticastLock.release();
            }
        } catch (Throwable ignored) {}
        sMulticastLock = null;
    }

    public static String listMdnsSources() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sDiscovered.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue() != null ? e.getValue() : "").append('\n');
        }
        return sb.toString();
    }

    public static File getInternalNdiLibFile(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "obs-studio/ndi");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, "libndi.so");
    }

    public static void syncExternalDropInNdiLib(Context ctx) {
        try {
            File[] mediaDirs = ctx.getExternalMediaDirs();
            if (mediaDirs == null || mediaDirs.length == 0 || mediaDirs[0] == null) return;
            File ndiDir = new File(mediaDirs[0], "ndi");
            if (!ndiDir.isDirectory()) ndiDir.mkdirs();
            File[] candidates = new File[]{
                new File(ndiDir, "libndi.so"),
                new File(mediaDirs[0], "plugins/libndi.so")
            };
            for (File f : candidates) {
                if (f.isFile() && f.length() > 65536) {
                    File dst = getInternalNdiLibFile(ctx);
                    if (!dst.exists() || dst.length() != f.length()) {
                        String res = installNdiLibrary(ctx, f.getAbsolutePath());
                        Log.i(TAG, "Drop-in libndi.so sync -> " + res);
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncExternalDropInNdiLib failed", t);
        }
    }

    private static String checkArm64Elf(byte[] data) {
        if (data == null || data.length < 64) return "Archivo demasiado pequeño para ser libndi.so.";
        if (data[0] == 'M' && data[1] == 'Z') return "Es un binario de Windows (.dll), no libndi.so para Android ARM64.";
        if (data[0] != 0x7F || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') return "El archivo no es un binario ELF (.so) válido.";
        if ((data[4] & 0xFF) != 2 || (data[5] & 0xFF) != 1) return "Se requiere un ELF de 64 bits (arm64-v8a).";
        int machine = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(18) & 0xFFFF;
        if (machine != 183) return "Arquitectura incompatible (e_machine=" + machine + "); se requiere libndi.so para Android ARM64 (arm64-v8a).";
        return null;
    }

    public static String installNdiLibrary(Context ctx, String sourcePath) {
        if (ctx == null || sourcePath == null || sourcePath.isEmpty()) {
            return "ERR\tRuta de archivo vacía.";
        }
        File src = new File(sourcePath);
        if (!src.isFile() || !src.canRead()) {
            return "ERR\tNo se puede leer el archivo: " + sourcePath;
        }
        File dst = getInternalNdiLibFile(ctx);
        String lower = src.getName().toLowerCase(Locale.US);
        try {
            if (lower.endsWith(".zip") || lower.endsWith(".aar") || lower.endsWith(".apk")) {
                String matchEntry = null;
                try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(src)))) {
                    ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        String n = e.getName().replace('\\', '/');
                        if (e.isDirectory() || !n.toLowerCase(Locale.US).endsWith("libndi.so")) continue;
                        ByteArrayOutputStream head = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int tot = 0, r;
                        while (tot < 4096 && (r = zis.read(buf, 0, Math.min(buf.length, 4096 - tot))) > 0) {
                            head.write(buf, 0, r);
                            tot += r;
                        }
                        if (checkArm64Elf(head.toByteArray()) == null) {
                            if (matchEntry == null || n.toLowerCase(Locale.US).contains("arm64")) {
                                matchEntry = n;
                            }
                        }
                    }
                }
                if (matchEntry == null) {
                    return "ERR\tEl paquete no contiene ningún libndi.so válido para Android ARM64 (arm64-v8a).";
                }
                if (dst.exists()) { dst.setWritable(true, false); dst.delete(); }
                try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(src)))) {
                    ZipEntry e;
                    byte[] buf = new byte[65536];
                    while ((e = zis.getNextEntry()) != null) {
                        if (e.getName().replace('\\', '/').equals(matchEntry)) {
                            try (FileOutputStream out = new FileOutputStream(dst)) {
                                int r;
                                while ((r = zis.read(buf)) != -1) out.write(buf, 0, r);
                            }
                            break;
                        }
                    }
                }
            } else {
                byte[] head = new byte[(int) Math.min(src.length(), 4096)];
                try (InputStream in = new FileInputStream(src)) {
                    int off = 0, r;
                    while (off < head.length && (r = in.read(head, off, head.length - off)) > 0) off += r;
                }
                String err = checkArm64Elf(head);
                if (err != null) return "ERR\t" + err;
                if (dst.exists()) { dst.setWritable(true, false); dst.delete(); }
                try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                }
            }
            dst.setReadable(true, false);
            dst.setExecutable(true, true);
            dst.setWritable(false, false);
            Log.i(TAG, "Installed libndi.so -> " + dst.getAbsolutePath() + " (" + dst.length() + " bytes)");
            return "OK\t" + dst.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "installNdiLibrary failed", e);
            return "ERR\tError instalando libndi.so: " + e.getMessage();
        }
    }

    private NdiDiscoveryManager() {}
}
