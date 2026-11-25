// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.system.Os;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.qtproject.qt.android.bindings.QtActivity;

public final class QtObsActivity extends QtActivity {
    private static final String PREFS_NAME = "obs_android_prefs";
    private static final String KEY_SAF_TREE_URI = "saf_export_tree_uri";
    private static volatile QtObsActivity sInstance;
    private static volatile Context sAppCtx;
    private AlertDialog mActiveTextDialog;
    private long pendingDirReqId = 0;
    private String pendingCameraDevice = "default";
    private float pendingCameraZoom = 1.0f;
    private int pendingCameraExposure = 0;
    private int pendingScreenMaxDim = 1920;
    private String pendingMicDevice = "default";

    static QtObsActivity getActiveInstance() {
        return sInstance;
    }

    void reapplyImmersiveMode() {
        applyImmersiveMode();
    }

    private void copyAssets(String path, File destination) throws Exception {
        String[] children = getAssets().list(path);
        if (children != null && children.length > 0) {
            if (!destination.isDirectory() && !destination.mkdirs()) {
                throw new IllegalStateException("Cannot create assets directory: " + destination);
            }
            for (String child : children) {
                copyAssets(path + "/" + child, new File(destination, child));
            }
        } else {
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            try (InputStream input = getAssets().open(path);
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
        }
    }

    private void prepareObsAssets() {
        File files = getFilesDir();
        File data = new File(files, "obs-data");
        File stamp = new File(files, ".obs-qt-assets-v4");
        try {
            // Native plugins and their locales can change without versionCode
            // changing in debug builds. Refresh bundled assets after each APK
            // update, without touching the user's OBS configuration directory.
            String installedRevision = Long.toString(getPackageManager()
                    .getPackageInfo(getPackageName(), 0).lastUpdateTime);
            String extractedRevision = stamp.exists()
                    ? new String(java.nio.file.Files.readAllBytes(stamp.toPath()),
                            java.nio.charset.StandardCharsets.US_ASCII) : "";
            if (!installedRevision.equals(extractedRevision)
                    || !new File(data, "obs-studio/striped_line.effect").exists()) {
                copyAssets("libobs", data);
                copyAssets("obs-modules", new File(files, "obs-modules"));
                copyAssets("obs-studio", new File(data, "obs-studio"));
                try (FileOutputStream output = new FileOutputStream(stamp)) {
                    output.write(installedRevision.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }
            }
        } catch (Exception e) {
            Log.e("QtObsActivity", "Failed to extract OBS assets", e);
        }
    }

    public static String resolveContentUri(String uriStr) {
        QtObsActivity activity = sInstance;
        if (activity == null || uriStr == null || !uriStr.startsWith("content://")) {
            return uriStr != null ? uriStr : "";
        }
        try {
            Uri uri = Uri.parse(uriStr);
            if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                try {
                    String docId = DocumentsContract.getDocumentId(uri);
                    if (docId != null && docId.startsWith("primary:")) {
                        File direct = new File(Environment.getExternalStorageDirectory(), docId.substring("primary:".length()));
                        if (direct.isFile() && direct.canRead()) {
                            Log.i("QtObsActivity", "Resolved external storage URI directly -> " + direct.getAbsolutePath());
                            return direct.getAbsolutePath();
                        }
                    }
                } catch (Exception ignored) {}
            }
            ContentResolver cr = activity.getContentResolver();
            String displayName = null;
            long expectedSize = -1;
            try (Cursor cursor = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx);
                    int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) expectedSize = cursor.getLong(sizeIdx);
                }
            } catch (Exception ignored) {}

            if (displayName == null || displayName.isEmpty()) {
                String last = uri.getLastPathSegment();
                displayName = (last != null && !last.isEmpty()) ? last : "media";
            }
            displayName = displayName.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (!displayName.contains(".")) {
                String mime = cr.getType(uri);
                String ext = mime != null ? MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) : null;
                if (ext != null && !ext.isEmpty()) displayName = displayName + "." + ext;
            }

            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(uriStr.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; ++i) hex.append(String.format(Locale.US, "%02x", digest[i]));

            File cacheDir = new File(activity.getFilesDir(), "obs-media-cache");
            if (!cacheDir.isDirectory()) cacheDir.mkdirs();
            File dst = new File(cacheDir, hex + "_" + displayName);
            if (dst.exists() && dst.length() > 0 && (expectedSize <= 0 || dst.length() == expectedSize)) {
                return dst.getAbsolutePath();
            }

            File tmp = new File(cacheDir, hex + "_" + displayName + ".part");
            try (InputStream in = cr.openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                if (in == null) return "";
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) return tmp.getAbsolutePath();
            trimMediaCacheLru(cacheDir, 150L * 1024L * 1024L, dst);
            Log.i("QtObsActivity", "Resolved content URI -> " + dst.getAbsolutePath() + " (" + dst.length() + " bytes)");
            return dst.getAbsolutePath();
        } catch (Exception e) {
            Log.e("QtObsActivity", "resolveContentUri failed for " + uriStr, e);
            return "";
        }
    }

    private static void trimMediaCacheLru(File cacheDir, long maxBytes, File keepFile) {
        try {
            File[] files = cacheDir.listFiles();
            if (files == null || files.length == 0) return;
            long total = 0L;
            for (File f : files) if (f.isFile()) total += f.length();
            if (total <= maxBytes) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : files) {
                if (total <= maxBytes) break;
                if (f.equals(keepFile) || !f.isFile()) continue;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        } catch (Throwable ignored) {}
    }

    public static String enumerateDevices(int kind) {
        QtObsActivity activity = sInstance;
        if (activity == null) return "";
        if (kind == 0) return CameraController.listCameras(activity);
        if (kind == 1) return AudioCaptureController.listAudioInputs(activity);
        if (kind == 4) return UsbUvcController.listUvcDevices(activity);
        return "";
    }

    public static String getDefaultRecordingPath() {
        QtObsActivity activity = sInstance;
        if (activity == null) return "/storage/emulated/0/Movies/OBS Studio";
        try {
            File movies = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "OBS Studio");
            if ((movies.isDirectory() || movies.mkdirs()) && movies.canWrite()) {
                return movies.getAbsolutePath();
            }
            File dcim = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "OBS Studio");
            if ((dcim.isDirectory() || dcim.mkdirs()) && dcim.canWrite()) {
                return dcim.getAbsolutePath();
            }
            File[] mediaDirs = activity.getExternalMediaDirs();
            if (mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null) {
                File recDir = new File(mediaDirs[0], "OBS Studio");
                if (recDir.isDirectory() || recDir.mkdirs()) {
                    return recDir.getAbsolutePath();
                }
            }
            File extMovies = activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (extMovies != null && (extMovies.isDirectory() || extMovies.mkdirs())) {
                return extMovies.getAbsolutePath();
            }
        } catch (Throwable t) {
            Log.w("QtObsActivity", "getDefaultRecordingPath fallback", t);
        }
        return activity.getFilesDir().getAbsolutePath();
    }

    public static String listLocalNetworkAddresses(int port, int latencyMs) {
        StringBuilder sb = new StringBuilder();
        java.util.LinkedHashMap<String, String> found = new java.util.LinkedHashMap<>();
        int safePort = Math.max(1024, Math.min(65535, port));
        int safeLatUs = Math.max(20, Math.min(4000, latencyMs)) * 1000;
        try {
            java.util.Enumeration<java.net.NetworkInterface> nics = java.net.NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                try {
                    if (!nic.isUp() || nic.isLoopback()) continue;
                    String name = nic.getName() != null ? nic.getName().toLowerCase(Locale.ROOT) : "";
                    String label = "Red";
                    if (name.startsWith("wlan") || name.startsWith("wifi")) label = "Wi-Fi";
                    else if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")) label = "Hotspot";
                    else if (name.startsWith("rndis") || name.startsWith("ncm") || name.startsWith("usb") || name.startsWith("eth")) label = "USB/Ethernet";
                    else if (name.startsWith("tun") || name.startsWith("wg") || name.startsWith("tailscale") || name.startsWith("zt")) label = "VPN";
                    else if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") || name.startsWith("wwan")) label = "Datos moviles";
                    else continue;

                    java.util.Enumeration<java.net.InetAddress> addrs = nic.getInetAddresses();
                    while (addrs != null && addrs.hasMoreElements()) {
                        java.net.InetAddress a = addrs.nextElement();
                        if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress() && !a.isAnyLocalAddress()) {
                            String ip = a.getHostAddress();
                            if (ip != null && !ip.isEmpty() && !found.containsKey(ip)) {
                                found.put(ip, label);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            QtObsActivity act = sInstance;
            if (act != null) {
                ConnectivityManager cm = act.getSystemService(ConnectivityManager.class);
                if (cm != null) {
                    Network active = cm.getActiveNetwork();
                    if (active != null) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                        LinkProperties lp = cm.getLinkProperties(active);
                        if (caps != null && lp != null) {
                            String label = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Datos moviles"
                                : caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN"
                                : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet/USB" : "Red";
                            for (LinkAddress la : lp.getLinkAddresses()) {
                                java.net.InetAddress a = la.getAddress();
                                if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                                    String ip = a.getHostAddress();
                                    if (ip != null && !ip.isEmpty() && !found.containsKey(ip)) found.put(ip, label);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        for (java.util.Map.Entry<String, String> e : found.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getValue()).append(": srt://").append(e.getKey()).append(':')
              .append(safePort).append("?mode=caller&latency=").append(safeLatUs);
        }
        return sb.toString();
    }

    public static String installPlugin(String sourcePath) {
        QtObsActivity activity = sInstance;
        if (activity == null) return "ERR\tActividad de Android no disponible.";
        return PluginInstaller.installFromPath(activity, sourcePath);
    }

    public static String listNdiMdnsSources() {
        Context ctx = sAppCtx != null ? sAppCtx : sInstance;
        if (ctx != null) NdiDiscoveryManager.startDiscovery(ctx);
        return NdiDiscoveryManager.listMdnsSources();
    }

    public static void setBroadcastKeepalive(boolean active) {
        Context ctx = sAppCtx != null ? sAppCtx : sInstance;
        if (ctx == null) return;
        if (!active && !CaptureService.isCapturing()) return;
        try {
            Intent i = new Intent(ctx, CaptureService.class)
                .setAction(CaptureService.STREAM_KEEPALIVE)
                .putExtra("active", active);
            ctx.startService(i);
        } catch (Throwable t) {
            Log.w("QtObsActivity", "setBroadcastKeepalive error: " + t.getMessage());
        }
    }

    // Runs from Qt. Native deliveries are already gated; UI cleanup must not block Qt.
    public static void onNativeShutdown() {
        Context ctx = sAppCtx;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            FloatingSceneOverlay.hide();
            ThermalController.stop();
            VtuberSourceManager.destroyAll();
            BrowserSourceManager.destroyAll();
            LiveGraphicsManager.destroyAll();
            CustomBrowserDockManager.destroyAll();
            FaceTrackerManager.stopBackgroundFrontCamera();
            FaceTrackerManager.stopVmcReceiver();
            NdiDiscoveryManager.stopDiscovery();
            WebRemoteServer.stop();
            if (ctx != null) ctx.stopService(new Intent(ctx, CaptureService.class));
        });
    }

    public static String getVideoCodecConfigJson(String request) { return CodecSelector.videoConfig(request); }

    public static String getDeviceHardwareProfileJson() {
        Context ctx = sAppCtx != null ? sAppCtx : sInstance;
        return DeviceHardwareProfiler.collectJson(ctx);
    }

    public static String getDeviceHealthJson() {
        Context ctx = sAppCtx != null ? sAppCtx : sInstance;
        int thermal = 0;
        int battery = -1;
        float tempC = 0.0f;
        boolean charging = false;
        if (ctx != null) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
                    if (pm != null) thermal = pm.getCurrentThermalStatus();
                }
            } catch (Throwable ignored) {}
            try {
                Intent bi = ctx.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bi != null) {
                    int lvl = bi.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int scale = bi.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                    if (lvl >= 0 && scale > 0) battery = Math.round(lvl * 100f / scale);
                    int t = bi.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0);
                    if (t > 0) tempC = t / 10.0f;
                    int st = bi.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                    charging = (st == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                        || st == android.os.BatteryManager.BATTERY_STATUS_FULL);
                }
            } catch (Throwable ignored) {}
        }
        org.json.JSONObject result = new org.json.JSONObject();
        try {
            result.put("thermal", thermal); result.put("temp_c", tempC);
            result.put("battery", battery); result.put("charging", charging);
            result.put("preview_fps_limit", ThermalController.previewFpsLimit());
            result.put("browser_fps_limit", ThermalController.browserFpsLimit());
            float headroom = ThermalController.thermalHeadroom();
            if (Float.isFinite(headroom)) result.put("thermal_headroom", headroom);
            result.put("java_heap_mb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576);
            result.put("native_heap_mb", android.os.Debug.getNativeHeapAllocatedSize() / 1048576);
        } catch (org.json.JSONException ignored) { }
        return result.toString();
    }

    public static void pickNdiLibrary() {
        QtObsActivity activity = sInstance;
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                activity.startActivityForResult(intent, 206);
            } catch (Exception e) {
                Log.e("QtObsActivity", "pickNdiLibrary failed", e);
                Toast.makeText(activity, "No se pudo abrir el selector de archivos.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public static void openWebRemoteQr() {
        QtObsActivity activity = sInstance;
        if (activity != null) WebRemoteServer.showQrDialog(activity);
    }

    public static void openGraphicsPanel() {
        LiveGraphicsManager.openControlPanel("scoreboard");
    }

    public static void toggleFloatingOverlay() {
        QtObsActivity activity = sInstance;
        if (activity != null) FloatingSceneOverlay.toggleFromTools(activity);
    }

    public static void vtuberCreateOrUpdate(long id, String configJson, int width, int height, int fps) {
        VtuberSourceManager.createOrUpdate(id, configJson, width, height, fps);
    }

    public static void vtuberSetVisible(long id, boolean visible) { VtuberSourceManager.setVisible(id, visible); }

    public static void vtuberDestroy(long id) {
        VtuberSourceManager.destroy(id);
    }

    public static void vtuberDestroyAll() {
        VtuberSourceManager.destroyAll();
    }

    public static void pickLive2dCubismCore() {
        QtObsActivity activity = sInstance;
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                activity.startActivityForResult(intent, 207);
            } catch (Exception e) {
                Log.e("QtObsActivity", "pickLive2dCubismCore failed", e);
            }
        });
    }

    public static void pickDirectory(long requestId, String title, String initialPath) {
        QtObsActivity activity = sInstance;
        if (activity == null) {
            NativeBridge.onDirectoryPicked(requestId, "");
            return;
        }
        activity.runOnUiThread(() -> activity.startDirectoryPickerFlow(requestId, title));
    }

    private void startDirectoryPickerFlow(long requestId, String title) {
        pendingDirReqId = requestId;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle((title != null && !title.isEmpty()) ? title : "Carpeta de grabaciones")
                .setMessage("Para grabar directamente en cualquier carpeta del teléfono o tarjeta SD (como Movies, Descargas o DCIM), puedes otorgar acceso a archivos a OBS Studio, o elegir una carpeta para exportar al terminar cada grabación.")
                .setPositiveButton("Permitir acceso", (d, w) -> {
                    try {
                        Intent perm = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                        startActivityForResult(perm, 205);
                    } catch (Exception e) {
                        launchDocumentTreePicker();
                    }
                })
                .setNeutralButton("Elegir carpeta", (d, w) -> launchDocumentTreePicker())
                .setNegativeButton("Cancelar", (d, w) -> {
                    long id = pendingDirReqId;
                    pendingDirReqId = 0;
                    if (id != 0) NativeBridge.onDirectoryPicked(id, "");
                })
                .setOnCancelListener(d -> {
                    long id = pendingDirReqId;
                    pendingDirReqId = 0;
                    if (id != 0) NativeBridge.onDirectoryPicked(id, "");
                })
                .show();
            return;
        }
        launchDocumentTreePicker();
    }

    private void launchDocumentTreePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, 204);
        } catch (Exception e) {
            Log.e("QtObsActivity", "ACTION_OPEN_DOCUMENT_TREE failed", e);
            long id = pendingDirReqId;
            pendingDirReqId = 0;
            if (id != 0) NativeBridge.onDirectoryPicked(id, "");
        }
    }

    private static String translateTreeUriToPosix(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId == null || docId.isEmpty()) return null;
            int colon = docId.indexOf(':');
            String volume = colon >= 0 ? docId.substring(0, colon) : docId;
            String rel = colon >= 0 ? docId.substring(colon + 1) : "";
            String base;
            if ("primary".equalsIgnoreCase(volume)) {
                base = Environment.getExternalStorageDirectory().getAbsolutePath();
            } else if ("home".equalsIgnoreCase(volume)) {
                base = new File(Environment.getExternalStorageDirectory(), "Documents").getAbsolutePath();
            } else {
                base = "/storage/" + volume;
            }
            if (rel.isEmpty()) return base;
            return base + "/" + rel;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isPosixDirWritable(String path) {
        if (path == null || path.isEmpty()) return false;
        File dir = new File(path);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File probe = new File(dir, ".obs_write_probe_" + System.currentTimeMillis());
        try {
            if (probe.createNewFile()) {
                probe.delete();
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private File getSafStagingDir() {
        File[] mediaDirs = getExternalMediaDirs();
        File base = (mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null)
            ? mediaDirs[0]
            : getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = getFilesDir();
        File staging = new File(base, "Recordings/SAF-Export");
        if (!staging.isDirectory()) staging.mkdirs();
        return staging;
    }

    public static void onRecordingFinished(String filePath) {
        final Context appCtx = sAppCtx != null ? sAppCtx : (sInstance != null ? sInstance.getApplicationContext() : null);
        if (appCtx == null || filePath == null || filePath.isEmpty()) return;
        new Thread(() -> {
            try {
                File src = new File(filePath);
                if (!src.isFile()) return;
                File baseExt = appCtx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                File stagingDir = new File(baseExt != null ? baseExt : appCtx.getFilesDir(), "saf-staging");
                SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String safTreeStr = prefs.getString(KEY_SAF_TREE_URI, "");
                if (!safTreeStr.isEmpty() && src.getAbsolutePath().startsWith(stagingDir.getAbsolutePath())) {
                    Uri treeUri = Uri.parse(safTreeStr);
                    String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
                    Uri parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId);
                    String ext = "";
                    int dot = src.getName().lastIndexOf('.');
                    if (dot >= 0) ext = src.getName().substring(dot + 1).toLowerCase(Locale.US);
                    String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                    if (mime == null || mime.isEmpty()) mime = "video/mp4";

                    ContentResolver cr = appCtx.getContentResolver();
                    Uri created = DocumentsContract.createDocument(cr, parentDocUri, mime, src.getName());
                    if (created != null) {
                        long copied = 0;
                        try (InputStream in = new FileInputStream(src);
                             OutputStream out = cr.openOutputStream(created, "w")) {
                            if (out != null) {
                                byte[] buf = new byte[131072];
                                int n;
                                while ((n = in.read(buf)) != -1) {
                                    out.write(buf, 0, n);
                                    copied += n;
                                }
                            }
                        }
                        if (copied == src.length()) {
                            src.delete();
                            Log.i("QtObsActivity", "Exported recording to SAF folder: " + created + " (" + copied + " bytes)");
                            QtObsActivity act = sInstance;
                            if (act != null && !act.isFinishing()) {
                                act.runOnUiThread(() -> Toast.makeText(
                                    appCtx, "Grabacion guardada en carpeta seleccionada", Toast.LENGTH_SHORT).show());
                            }
                            String posixTarget = translateTreeUriToPosix(treeUri);
                            if (posixTarget != null) {
                                MediaScannerConnection.scanFile(
                                    appCtx, new String[]{posixTarget + "/" + src.getName()}, new String[]{mime}, null);
                            }
                            return;
                        }
                    }
                }
                String ext = "";
                int dot = src.getName().lastIndexOf('.');
                if (dot >= 0) ext = src.getName().substring(dot + 1).toLowerCase(Locale.US);
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime == null || mime.isEmpty()) mime = "video/mp4";

                String absPath = src.getAbsolutePath();
                boolean inPublicGallery = absPath.startsWith("/storage/emulated/0/Movies/")
                    || absPath.startsWith("/storage/emulated/0/DCIM/")
                    || absPath.startsWith("/storage/emulated/0/Download/");
                if (!inPublicGallery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.DISPLAY_NAME, src.getName());
                    cv.put(MediaStore.Video.Media.MIME_TYPE, mime);
                    cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/OBS Studio");
                    cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                    ContentResolver cr = appCtx.getContentResolver();
                    Uri item = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                    if (item != null) {
                        long copied = 0;
                        boolean copyOk = false;
                        try (InputStream in = new FileInputStream(src);
                             OutputStream out = cr.openOutputStream(item, "w")) {
                            if (out != null) {
                                byte[] buf = new byte[131072];
                                int n;
                                while ((n = in.read(buf)) != -1) {
                                    out.write(buf, 0, n);
                                    copied += n;
                                }
                                copyOk = (copied > 0 && copied == src.length());
                            }
                        } catch (Throwable copyErr) {
                            copyOk = false;
                            Log.e("QtObsActivity", "MediaStore copy failed", copyErr);
                        }
                        if (copyOk) {
                            ContentValues done = new ContentValues();
                            done.put(MediaStore.Video.Media.IS_PENDING, 0);
                            try {
                                if (cr.update(item, done, null, null) != 1) {
                                    copyOk = false;
                                }
                            } catch (RuntimeException publishError) {
                                copyOk = false;
                                Log.e("QtObsActivity", "MediaStore publish failed", publishError);
                            }
                        }
                        if (copyOk) {
                            src.delete();
                            Log.i("QtObsActivity", "Published recording to Movies/OBS Studio: " + item);
                            QtObsActivity act = sInstance;
                            if (act != null && !act.isFinishing()) {
                                act.runOnUiThread(() -> Toast.makeText(
                                    appCtx, "Grabacion guardada en Movies/OBS Studio", Toast.LENGTH_SHORT).show());
                            }
                            return;
                        } else {
                            try { cr.delete(item, null, null); } catch (Throwable ignored) {}
                        }
                    }
                }
                MediaScannerConnection.scanFile(appCtx, new String[]{absPath}, new String[]{mime}, null);
                Log.i("QtObsActivity", "MediaScanner indexed recording: " + absPath);
            } catch (Throwable t) {
                Log.e("QtObsActivity", "onRecordingFinished post-process error", t);
            }
        }, "obs-rec-post").start();
    }

    public static void onNativeCaptureConfigured(int kind, boolean enabled, String deviceId, float zoom, int exposure) {
        QtObsActivity activity = sInstance;
        if (activity == null) return;
        activity.runOnUiThread(() -> activity.handleCaptureRequest(kind, enabled, deviceId, zoom, exposure));
    }

    public static void onNativeCaptureRequested(int kind, boolean enabled, boolean frontCamera) {
        onNativeCaptureConfigured(kind, enabled, frontCamera ? "front" : "default", 1.0f, 0);
    }

    public static void openNativeTextInput(long requestId, String title, String initialText, boolean isPassword) {
        QtObsActivity activity = sInstance;
        if (activity == null) {
            NativeBridge.onNativeTextInputResult(requestId, false, "");
            return;
        }
        activity.runOnUiThread(() -> activity.showNativeTextDialog(requestId, title, initialText, isPassword));
    }

    private void showNativeTextDialog(long requestId, String title, String initialText, boolean isPassword) {
        if (mActiveTextDialog != null && mActiveTextDialog.isShowing()) {
            mActiveTextDialog.dismiss();
            mActiveTextDialog = null;
        }
        final boolean[] completed = new boolean[]{false};
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, Math.round(8 * density), pad, Math.round(4 * density));

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(isPassword
            ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        String safeInit = initialText != null ? initialText : "";
        edit.setText(safeInit);
        edit.setSelection(safeInit.length());
        container.addView(edit, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = Math.round(8 * density);

        Button pasteBtn = new Button(this);
        pasteBtn.setText("Pegar");
        pasteBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence pasted = clip.getItemAt(0).coerceToText(this);
                    if (pasted != null) {
                        int start = Math.max(0, edit.getSelectionStart());
                        int end = Math.max(0, edit.getSelectionEnd());
                        if (start == 0 && end == 0 && edit.length() > 0) {
                            edit.setText(pasted.toString());
                            edit.setSelection(edit.length());
                        } else {
                            int lo = Math.min(start, end);
                            int hi = Math.max(start, end);
                            edit.getText().replace(lo, hi, pasted.toString());
                        }
                    }
                }
            }
        });

        Button clearBtn = new Button(this);
        clearBtn.setText("Borrar todo");
        clearBtn.setOnClickListener(v -> {
            edit.setText("");
            edit.requestFocus();
        });

        actions.addView(clearBtn);
        actions.addView(pasteBtn);
        container.addView(actions, actionsParams);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle((title != null && !title.isEmpty()) ? title : "Editar texto")
            .setView(container)
            .setPositiveButton("Aceptar", (d, w) -> {
                if (!completed[0]) {
                    completed[0] = true;
                    NativeBridge.onNativeTextInputResult(requestId, true, edit.getText().toString());
                }
            })
            .setNegativeButton("Cancelar", (d, w) -> {
                if (!completed[0]) {
                    completed[0] = true;
                    NativeBridge.onNativeTextInputResult(requestId, false, "");
                }
            })
            .create();

        dialog.setOnCancelListener(d -> {
            if (!completed[0]) {
                completed[0] = true;
                NativeBridge.onNativeTextInputResult(requestId, false, "");
            }
        });
        dialog.setOnDismissListener(d -> {
            if (mActiveTextDialog == dialog) mActiveTextDialog = null;
            applyImmersiveMode();
        });
        edit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (!completed[0]) {
                    completed[0] = true;
                    NativeBridge.onNativeTextInputResult(requestId, true, edit.getText().toString());
                }
                dialog.dismiss();
                return true;
            }
            return false;
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        mActiveTextDialog = dialog;
        dialog.show();
        edit.requestFocus();
        edit.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
        }, 100);
    }

    private void handleCaptureRequest(int kind, boolean enabled, String deviceId, float zoom, int exposure) {
        try {
            if (kind == 0) {
                pendingCameraDevice = (deviceId != null && !deviceId.isEmpty()) ? deviceId : "default";
                pendingCameraZoom = zoom;
                pendingCameraExposure = exposure;
                if (!enabled && !CaptureService.isCapturing()) return;
                if (enabled && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, 200);
                    return;
                }
                startService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.CAMERA_CONFIG)
                    .putExtra("enabled", enabled)
                    .putExtra("deviceId", pendingCameraDevice)
                    .putExtra("zoom", zoom)
                    .putExtra("exposure", exposure));
            } else if (kind == 1) {
                pendingMicDevice = (deviceId != null && !deviceId.isEmpty()) ? deviceId : "default";
                if (!enabled && !CaptureService.isCapturing()) return;
                if (enabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 201);
                    return;
                }
                startService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.MIC_CONFIG)
                    .putExtra("enabled", enabled)
                    .putExtra("deviceId", pendingMicDevice));
            } else if (kind == 2 || kind == 3) {
                boolean wantAudio = (kind == 3) || (exposure != 0);
                if (kind == 2 && deviceId != null && !deviceId.isEmpty()) {
                    try {
                        int parsed = Integer.parseInt(deviceId);
                        if (parsed >= 720 && parsed <= 2560) pendingScreenMaxDim = parsed;
                    } catch (NumberFormatException ignored) {}
                }
                if (enabled && !CaptureService.screenActive) {
                    MediaProjectionManager mgr = getSystemService(MediaProjectionManager.class);
                    if (mgr != null) {
                        startActivityForResult(mgr.createScreenCaptureIntent(), wantAudio ? 203 : 202);
                    }
                } else if (enabled && CaptureService.screenActive && kind == 2) {
                    startService(new Intent(this, CaptureService.class)
                        .setAction(CaptureService.SCREEN_AUDIO)
                        .putExtra("audio", wantAudio)
                        .putExtra("maxDim", pendingScreenMaxDim));
                } else if (!enabled && CaptureService.screenActive) {
                    startService(new Intent(this, CaptureService.class).setAction(CaptureService.SCREEN));
                }
            } else if (kind == 4) {
                if (!enabled && !CaptureService.isCapturing()) return;
                startService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.UVC_CONFIG)
                    .putExtra("enabled", enabled)
                    .putExtra("deviceId", deviceId != null ? deviceId : "default"));
            }
        } catch (Exception e) {
            Log.e("QtObsActivity", "handleCaptureRequest failed", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults == null || grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (requestCode == 200) {
            startService(new Intent(this, CaptureService.class)
                .setAction(CaptureService.CAMERA_CONFIG)
                .putExtra("enabled", true)
                .putExtra("deviceId", pendingCameraDevice)
                .putExtra("zoom", pendingCameraZoom)
                .putExtra("exposure", pendingCameraExposure));
        } else if (requestCode == 201) {
            startService(new Intent(this, CaptureService.class)
                .setAction(CaptureService.MIC_CONFIG)
                .putExtra("enabled", true)
                .putExtra("deviceId", pendingMicDevice));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == 202 || requestCode == 203) && resultCode == RESULT_OK && data != null) {
            startService(new Intent(this, CaptureService.class)
                .setAction(CaptureService.SCREEN)
                .putExtra("resultCode", resultCode)
                .putExtra("consent", data)
                .putExtra("audio", requestCode == 203)
                .putExtra("maxDim", pendingScreenMaxDim));
        } else if (requestCode == 205) {
            if (pendingDirReqId != 0) {
                launchDocumentTreePicker();
            }
        } else if (requestCode == 204) {
            long reqId = pendingDirReqId;
            pendingDirReqId = 0;
            String chosenPath = "";
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Throwable ignored) {}
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String posix = translateTreeUriToPosix(treeUri);
                if (posix != null && isPosixDirWritable(posix)) {
                    prefs.edit().remove(KEY_SAF_TREE_URI).apply();
                    chosenPath = posix;
                    Log.i("QtObsActivity", "Direct recording directory selected: " + chosenPath);
                } else {
                    prefs.edit().putString(KEY_SAF_TREE_URI, treeUri.toString()).apply();
                    chosenPath = getSafStagingDir().getAbsolutePath();
                    Log.i("QtObsActivity", "SAF export tree selected: " + treeUri + " (staging=" + chosenPath + ")");
                    Toast.makeText(
                        this,
                        "Carpeta seleccionada (las grabaciones se exportarán automáticamente al detenerse)",
                        Toast.LENGTH_LONG).show();
                }
            }
            if (reqId != 0) {
                NativeBridge.onDirectoryPicked(reqId, chosenPath);
            }
        } else if (requestCode == 206 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri pickedUri = data.getData();
            new Thread(() -> {
                String localPath = resolveContentUri(pickedUri.toString());
                String res = NdiDiscoveryManager.installNdiLibrary(this, localPath);
                String[] parts = res.split("\t", 3);
                if (parts.length >= 2 && "OK".equals(parts[0])) {
                    boolean loaded = NativeBridge.reloadNdiRuntime(parts[1]);
                    final String msg = loaded
                        ? "Runtime NDI (libndi.so) instalado y cargado correctamente. Cierra y reabre Propiedades para conectar."
                        : "libndi.so copiado, pero dlopen() falló (verifica que sea Android arm64-v8a y contenga NDIlib_*).";
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("Runtime NDI")
                                .setMessage(msg)
                                .setPositiveButton("Aceptar", null)
                                .show();
                        }
                    });
                } else {
                    final String err = parts.length >= 2 ? parts[1] : "No se pudo instalar libndi.so.";
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("Error instalando libndi.so")
                                .setMessage(err)
                                .setPositiveButton("Aceptar", null)
                                .show();
                        }
                    });
                }
            }, "obs-ndi-install").start();
        } else if (requestCode == 207 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri pickedUri = data.getData();
            new Thread(() -> {
                String localPath = resolveContentUri(pickedUri.toString());
                String res = VtuberSourceManager.installCubismCore(this, localPath);
                String[] parts = res.split("\t", 2);
                final String msg = (parts.length >= 1 && "OK".equals(parts[0]))
                    ? "live2dcubismcore.min.js instalado correctamente. Vuelve a seleccionar tu modelo Live2D (.zip / .model3.json)."
                    : ("No se pudo instalar Live2D Cubism Core: " + (parts.length >= 2 ? parts[1] : ""));
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Live2D Cubism Core")
                            .setMessage(msg)
                            .setPositiveButton("Aceptar", null)
                            .show();
                    }
                });
            }, "obs-l2d-core").start();
        }
    }

    private void applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            @SuppressWarnings("deprecation")
            int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        sInstance = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (lp != null && lp.preferredRefreshRate != 60.0f) {
                lp.preferredRefreshRate = 60.0f;
                getWindow().setAttributes(lp);
            }
        } catch (Throwable ignored) {}
        prepareObsAssets();
        File files = getFilesDir();
        File data = new File(files, "obs-data");
        File frontendData = new File(data, "obs-studio");
        String libs = getApplicationInfo().nativeLibraryDir;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxDim = Math.max(dm.widthPixels, dm.heightPixels);
        int minDim = Math.min(dm.widthPixels, dm.heightPixels);
        float targetDpr = Math.min(maxDim / 1280.0f, minDim / 600.0f);
        targetDpr = Math.max(1.0f, Math.min(targetDpr, dm.density));
        float qtScaleFactor = dm.density > 0.0f ? (targetDpr / dm.density) : 1.0f;
        try {
            Os.setenv("HOME", files.getAbsolutePath(), true);
            Os.setenv("OBS_DATA_PATH", frontendData.getAbsolutePath() + "/", true);
            Os.setenv("OBS_ANDROID_FILES_PATH", files.getAbsolutePath(), true);
            File omtStorage = new File(files, "omt");
            if (new File(getApplicationInfo().nativeLibraryDir, "libomtplugin.so").isFile()
                    && (omtStorage.isDirectory() || omtStorage.mkdirs())) {
                Os.setenv("OMT_STORAGE_PATH", omtStorage.getAbsolutePath(), true);
            }
            Os.setenv("OBS_ANDROID_DATA_PATH", data.getAbsolutePath(), true);
            Os.setenv("OBS_ANDROID_LIBRARIES_PATH", libs, true);
            Os.setenv("QT_QPA_PLATFORM", "android", true);
            Os.setenv("QT_SCALE_FACTOR", String.format(Locale.US, "%.3f", qtScaleFactor), true);
        } catch (Exception e) {
            Log.e("QtObsActivity", "setenv failed", e);
        }
        NativeBridge.configurePaths(files.getAbsolutePath(), data.getAbsolutePath(), libs);
        NativeBridge.registerCaptureHost(QtObsActivity.class);
        NativeBridge.registerActivityHost(QtObsActivity.class);
        NativeBridge.registerBrowserHost(BrowserSourceManager.class);
        NativeBridge.registerBrowserDockHost(CustomBrowserDockManager.class);
        ThermalController.start(this);
        NativeBridge.registerNdiHost(QtObsActivity.class);
        NativeBridge.registerVtuberHost(QtObsActivity.class);
        NativeBridge.registerGraphicsHost(LiveGraphicsManager.class);
        NativeBridge.registerBgSegmenterHost(BgSegmenterManager.class);
        sAppCtx = getApplicationContext();
        LiveGraphicsManager.init(this);
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            PluginInstaller.syncExternalDropInPlugins(this);
            NdiDiscoveryManager.syncExternalDropInNdiLib(this);
        }
        // NdiDiscoveryManager and WebRemoteServer start lazily on first use (NDI / Web Remote QR / VTuber WebGL)
        super.onCreate(savedInstanceState);
        applyImmersiveMode();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                new OnBackInvokedCallback() {
                    @Override
                    public void onBackInvoked() {
                        if (!NativeBridge.handleAndroidBack()) {
                            moveTaskToBack(true);
                        }
                    }
                });
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!NativeBridge.handleAndroidBack()) {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        CustomBrowserDockManager.onActivityPaused(false);
        FloatingSceneOverlay.onAppForegrounded();
        if (CaptureService.sessionBgActive) {
            try {
                startService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.SESSION_KEEPALIVE)
                    .putExtra("active", false));
            } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onPause() {
        CustomBrowserDockManager.onActivityPaused(true);
        try { NativeBridge.saveAllState(); } catch (Throwable ignored) {}
        LiveGraphicsManager.saveState();
        super.onPause();
    }

    @Override
    protected void onStop() {
        try { NativeBridge.saveAllState(); } catch (Throwable ignored) {}
        LiveGraphicsManager.saveState();
        if (!isFinishing()) {
            FloatingSceneOverlay.onAppBackgrounded(this);
            try {
                Intent keepalive = new Intent(this, CaptureService.class)
                    .setAction(CaptureService.SESSION_KEEPALIVE)
                    .putExtra("active", true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(keepalive);
                } else {
                    startService(keepalive);
                }
            } catch (Throwable t) {
                Log.w("QtObsActivity", "Background session keepalive skipped: " + t.getMessage());
            }
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) NativeBridge.quiesceCallbacks();
        try { NativeBridge.saveAllState(); } catch (Throwable ignored) {}
        LiveGraphicsManager.saveState();
        FloatingSceneOverlay.hide();
        WebRemoteServer.stop();
        FaceTrackerManager.stopVmcReceiver();
        VtuberSourceManager.destroyAll();
        LiveGraphicsManager.destroyAll();
        NdiDiscoveryManager.stopDiscovery();
        BrowserSourceManager.destroyAll();
        CustomBrowserDockManager.destroyAll();
        ThermalController.stop();
        if (mActiveTextDialog != null && mActiveTextDialog.isShowing()) {
            mActiveTextDialog.dismiss();
            mActiveTextDialog = null;
        }
        if (sInstance == this) sInstance = null;
        boolean finishing = isFinishing();
        super.onDestroy();
        if (finishing) {
            stopService(new Intent(this, CaptureService.class));
        }
    }
}
