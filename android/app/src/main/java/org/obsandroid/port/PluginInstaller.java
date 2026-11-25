// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.content.Context;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class PluginInstaller {
    private static final String TAG = "ObsPluginInstaller";
    private static final int ELF_CLASS_64 = 2;
    private static final int ELF_DATA_LSB = 1;
    private static final int EM_386 = 3;
    private static final int EM_X86_64 = 62;
    private static final int EM_ARM = 40;
    private static final int EM_AARCH64 = 183;

    private static final class ElfCheck {
        boolean ok;
        boolean page16kAligned;
        String error;
    }

    private static ElfCheck inspectElf(byte[] data) {
        ElfCheck res = new ElfCheck();
        if (data == null || data.length < 64) {
            res.error = "El archivo es demasiado pequeño para ser una biblioteca compartida ELF válida.";
            return res;
        }
        if (data[0] == 'M' && data[1] == 'Z') {
            res.error = "Este archivo es un plugin de Windows (.dll/.exe). OBS en Android solo puede cargar plugins compilados para Android ARM64 (arm64-v8a, .so o .zip ARM64).";
            return res;
        }
        if (data[0] != 0x7F || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') {
            res.error = "El archivo no es un binario ELF válido para Android (.so).";
            return res;
        }
        int eiClass = data[4] & 0xFF;
        int eiData = data[5] & 0xFF;
        if (eiClass != ELF_CLASS_64 || eiData != ELF_DATA_LSB) {
            res.error = "Se requiere un binario ELF de 64 bits little-endian (arm64-v8a).";
            return res;
        }
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int machine = bb.getShort(18) & 0xFFFF;
        if (machine != EM_AARCH64) {
            if (machine == EM_X86_64 || machine == EM_386) {
                res.error = "Este plugin .so está compilado para PC (x86/x86_64), no para Android ARM64 (arm64-v8a).";
            } else if (machine == EM_ARM) {
                res.error = "Este plugin .so es ARM 32 bits (armeabi-v7a); OBS Android requiere ARM 64 bits (arm64-v8a).";
            } else {
                res.error = "Arquitectura ELF incompatible (e_machine=" + machine + "); se requiere ARM64 (arm64-v8a).";
            }
            return res;
        }
        res.ok = true;
        res.page16kAligned = true;
        long phoff = bb.getLong(32);
        int phentsize = bb.getShort(54) & 0xFFFF;
        int phnum = bb.getShort(56) & 0xFFFF;
        if (phoff > 0 && phentsize >= 56 && phnum > 0 && phoff + (long) phentsize * phnum <= data.length) {
            for (int i = 0; i < phnum; ++i) {
                int off = (int) (phoff + (long) i * phentsize);
                int pType = bb.getInt(off);
                if (pType == 1) { // PT_LOAD
                    long pAlign = bb.getLong(off + 48);
                    if (pAlign < 16384L) {
                        res.page16kAligned = false;
                    }
                }
            }
        }
        return res;
    }

    private static byte[] readHead(File f, int maxBytes) throws Exception {
        int len = (int) Math.min(f.length(), maxBytes);
        byte[] buf = new byte[len];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) break;
                off += n;
            }
        }
        return buf;
    }

    private static String sanitizeName(String raw) {
        if (raw == null) return "obs-plugin";
        String s = raw.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        if (s.endsWith(".zip")) s = s.substring(0, s.length() - 4);
        if (s.endsWith(".so")) s = s.substring(0, s.length() - 3);
        if (s.startsWith("lib") && s.length() > 3) s = s.substring(3);
        s = s.replaceAll("[^a-zA-Z0-9._-]", "_");
        return s.isEmpty() ? "obs-plugin" : s;
    }

    private static void markReadOnlyBinary(File f) {
        try {
            f.setReadable(true, false);
            f.setExecutable(true, true);
            f.setWritable(false, false);
        } catch (Throwable ignored) {}
    }

    private static void copyFile(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        if (dst.exists()) {
            dst.setWritable(true, false);
            dst.delete();
        }
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    public static File getPluginsRoot(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "obs-studio/plugins");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    public static File getExternalDropInDir(Context ctx) {
        File[] mediaDirs = ctx.getExternalMediaDirs();
        if (mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null) {
            File dir = new File(mediaDirs[0], "plugins");
            if (!dir.isDirectory()) dir.mkdirs();
            return dir;
        }
        return null;
    }

    public static void syncExternalDropInPlugins(Context ctx) {
        try {
            File dropIn = getExternalDropInDir(ctx);
            if (dropIn == null || !dropIn.isDirectory()) return;
            File[] files = dropIn.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (!f.isFile()) continue;
                String lower = f.getName().toLowerCase(Locale.US);
                if (!lower.endsWith(".so") && !lower.endsWith(".zip")) continue;
                String markerName = ".staged_" + f.getName() + "_" + f.length() + "_" + f.lastModified();
                File marker = new File(getPluginsRoot(ctx), markerName);
                if (marker.exists()) continue;
                String res = installFromPath(ctx, f.getAbsolutePath());
                Log.i(TAG, "Drop-in plugin " + f.getName() + " -> " + res);
                if (res.startsWith("OK\t")) {
                    try { new FileOutputStream(marker).close(); } catch (Exception ignored) {}
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncExternalDropInPlugins failed", t);
        }
    }

    public static String installFromPath(Context ctx, String sourcePath) {
        if (ctx == null || sourcePath == null || sourcePath.isEmpty()) {
            return "ERR\tRuta de archivo de plugin vacía.";
        }
        File src = new File(sourcePath);
        if (!src.isFile() || !src.canRead()) {
            return "ERR\tNo se puede leer el archivo seleccionado: " + sourcePath;
        }
        String lower = src.getName().toLowerCase(Locale.US);
        try {
            if (lower.endsWith(".so")) {
                return installSingleSo(ctx, src);
            } else if (lower.endsWith(".zip")) {
                return installZipBundle(ctx, src);
            } else {
                // Inspect header to give helpful message
                byte[] head = readHead(src, 4096);
                if (head.length >= 4 && head[0] == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') {
                    return installSingleSo(ctx, src);
                }
                if (head.length >= 2 && head[0] == 'P' && head[1] == 'K') {
                    return installZipBundle(ctx, src);
                }
                ElfCheck chk = inspectElf(head);
                return "ERR\t" + (chk.error != null ? chk.error : "Formato no soportado. Selecciona un archivo .zip o .so compilado para Android ARM64.");
            }
        } catch (Exception e) {
            Log.e(TAG, "installFromPath failed", e);
            return "ERR\tError instalando plugin: " + e.getMessage();
        }
    }

    private static String installSingleSo(Context ctx, File src) throws Exception {
        byte[] head = readHead(src, 8192);
        ElfCheck chk = inspectElf(head);
        if (!chk.ok) {
            return "ERR\t" + chk.error;
        }
        String pluginName = sanitizeName(src.getName());
        File pluginDir = new File(getPluginsRoot(ctx), pluginName);
        File dataDir = new File(pluginDir, "data");
        if (!dataDir.isDirectory()) dataDir.mkdirs();

        File dstBin = new File(pluginDir, "lib" + pluginName + ".so");
        copyFile(src, dstBin);
        markReadOnlyBinary(dstBin);

        String warn = chk.page16kAligned ? "" : " (aviso: binario no alineado a 16 KB)";
        Log.i(TAG, "Installed .so plugin " + pluginName + " -> " + dstBin.getAbsolutePath() + warn);
        return "OK\t" + pluginName + "\t" + dstBin.getAbsolutePath() + "\t" + dataDir.getAbsolutePath() + "\t" + warn;
    }

    private static String installZipBundle(Context ctx, File zipFile) throws Exception {
        // First pass: find all .so entries and pick the best ARM64 one
        String bestSoEntry = null;
        String anySoError = null;
        boolean bestAligned16k = true;
        List<String> allEntries = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || name.contains("..")) continue;
                allEntries.add(name);
                String lName = name.toLowerCase(Locale.US);
                if (lName.endsWith(".so") || lName.endsWith(".dll")) {
                    ByteArrayOutputStream headOut = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int total = 0;
                    int n;
                    while (total < 8192 && (n = zis.read(buf, 0, Math.min(buf.length, 8192 - total))) > 0) {
                        headOut.write(buf, 0, n);
                        total += n;
                    }
                    ElfCheck chk = inspectElf(headOut.toByteArray());
                    if (chk.ok) {
                        boolean isArm64Path = lName.contains("arm64") || lName.contains("aarch64");
                        if (bestSoEntry == null || isArm64Path) {
                            bestSoEntry = name;
                            bestAligned16k = chk.page16kAligned;
                        }
                    } else if (anySoError == null) {
                        anySoError = chk.error;
                    }
                }
            }
        }

        if (bestSoEntry == null) {
            if (anySoError != null) {
                return "ERR\tEl archivo .zip no contiene ningún binario Android ARM64 válido. " + anySoError;
            }
            return "ERR\tEl archivo .zip no contiene ninguna biblioteca de plugin (.so) para Android ARM64.";
        }

        String soBaseName = bestSoEntry.substring(bestSoEntry.lastIndexOf('/') + 1);
        String pluginName = sanitizeName(soBaseName);
        File pluginDir = new File(getPluginsRoot(ctx), pluginName);
        File dataDir = new File(pluginDir, "data");
        if (!dataDir.isDirectory()) dataDir.mkdirs();

        File dstBin = new File(pluginDir, "lib" + pluginName + ".so");
        String canonicalData = dataDir.getCanonicalPath();

        // Second pass: extract primary .so and data/ assets safely
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buf = new byte[65536];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || name.contains("..")) continue;

                File target = null;
                boolean isPrimaryBin = name.equals(bestSoEntry);
                if (isPrimaryBin) {
                    target = dstBin;
                } else {
                    int dataIdx = name.indexOf("/data/");
                    if (name.startsWith("data/")) {
                        target = new File(dataDir, name.substring(5));
                    } else if (dataIdx >= 0) {
                        target = new File(dataDir, name.substring(dataIdx + 6));
                    } else if (name.endsWith("manifest.json")) {
                        target = new File(dataDir, "manifest.json");
                    }
                }
                if (target == null) continue;
                if (!isPrimaryBin && !target.getCanonicalPath().startsWith(canonicalData)) continue;

                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                if (target.exists()) {
                    target.setWritable(true, false);
                    target.delete();
                }
                try (FileOutputStream out = new FileOutputStream(target)) {
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
                if (isPrimaryBin) {
                    markReadOnlyBinary(target);
                }
            }
        }

        String warn = bestAligned16k ? "" : " (aviso: binario no alineado a 16 KB)";
        Log.i(TAG, "Installed .zip plugin " + pluginName + " -> " + dstBin.getAbsolutePath() + warn);
        return "OK\t" + pluginName + "\t" + dstBin.getAbsolutePath() + "\t" + dataDir.getAbsolutePath() + "\t" + warn;
    }

    private PluginInstaller() {}
}
