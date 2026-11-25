// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class UsbUvcController {
    private static final String ACTION_USB_PERMISSION = "org.obsandroid.port.USB_UVC_PERMISSION";
    private final Context context;
    private final Runnable failure;
    private UsbDevice activeDevice;
    private UsbDeviceConnection connection;
    private final List<UsbInterface> claimedInterfaces = new ArrayList<>();
    private BroadcastReceiver receiver;
    private int requestedHeight = 720;
    private volatile boolean stopped;

    UsbUvcController(Context context, Runnable failure) {
        this.context = context.getApplicationContext();
        this.failure = failure;
    }

    private static boolean isUvcDevice(UsbDevice dev) {
        if (dev == null) return false;
        for (int i = 0; i < dev.getInterfaceCount(); ++i) {
            UsbInterface intf = dev.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }

    static String listUvcDevices(Context context) {
        StringBuilder sb = new StringBuilder();
        try {
            UsbManager mgr = context.getSystemService(UsbManager.class);
            if (mgr == null) return "";
            for (Map.Entry<String, UsbDevice> entry : mgr.getDeviceList().entrySet()) {
                UsbDevice dev = entry.getValue();
                if (!isUvcDevice(dev)) continue;
                String name = dev.getProductName();
                if (name == null || name.isEmpty()) name = dev.getManufacturerName();
                if (name == null || name.isEmpty()) name = "Camara/Capturadora USB";
                String label = String.format(Locale.US, "USB UVC: %s (%04X:%04X)",
                    name, dev.getVendorId(), dev.getProductId());
                sb.append(dev.getDeviceName()).append('\t').append(label).append('\n');
            }
        } catch (Exception e) {
            Log.w("ObsUsbUvc", "listUvcDevices failed", e);
        }
        return sb.toString();
    }

    void start(String preferredDeviceSpec) {
        String preferredDeviceName = preferredDeviceSpec != null ? preferredDeviceSpec : "default";
        int at = preferredDeviceName.indexOf('@');
        if (at >= 0) {
            try { requestedHeight = Integer.parseInt(preferredDeviceName.substring(at + 1)); } catch (NumberFormatException ignored) {}
            preferredDeviceName = preferredDeviceName.substring(0, at);
            if (preferredDeviceName.isEmpty()) preferredDeviceName = "default";
        }
        UsbManager mgr = context.getSystemService(UsbManager.class);
        if (mgr == null) {
            failure.run();
            return;
        }
        UsbDevice chosen = null;
        for (UsbDevice dev : mgr.getDeviceList().values()) {
            if (!isUvcDevice(dev)) continue;
            if (!"default".equals(preferredDeviceName) && preferredDeviceName.equals(dev.getDeviceName())) {
                chosen = dev;
                break;
            }
            if (chosen == null) chosen = dev;
        }
        if (chosen == null) {
            Log.w("ObsUsbUvc", "No USB UVC video device connected");
            failure.run();
            return;
        }
        activeDevice = chosen;
        registerReceiver();
        if (mgr.hasPermission(chosen)) {
            openAndStream(mgr, chosen);
        } else {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            Intent permIntent = new Intent(ACTION_USB_PERMISSION);
            permIntent.setPackage(context.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(context, 401, permIntent, flags);
            mgr.requestPermission(chosen, pi);
        }
    }

    private void registerReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (stopped) return;
                String act = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(act)) {
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (granted && dev != null) {
                        UsbManager mgr = context.getSystemService(UsbManager.class);
                        if (mgr != null) openAndStream(mgr, dev);
                    } else {
                        Log.w("ObsUsbUvc", "USB UVC permission denied");
                        failure.run();
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(act)) {
                    UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (dev != null && activeDevice != null && dev.getDeviceName().equals(activeDevice.getDeviceName())) {
                        Log.i("ObsUsbUvc", "USB UVC device detached");
                        stop();
                        failure.run();
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    private void openAndStream(UsbManager mgr, UsbDevice dev) {
        try {
            connection = mgr.openDevice(dev);
            if (connection == null) {
                Log.e("ObsUsbUvc", "UsbManager.openDevice returned null");
                failure.run();
                return;
            }
            // Claim all VideoControl / VideoStreaming interfaces with force=true so kernel uvcvideo detaches cleanly
            claimedInterfaces.clear();
            for (int i = 0; i < dev.getInterfaceCount(); ++i) {
                UsbInterface intf = dev.getInterface(i);
                if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                    boolean ok = connection.claimInterface(intf, true);
                    Log.i("ObsUsbUvc", "claimInterface(id=" + intf.getId() + " alt=" + intf.getAlternateSetting()
                        + " sub=" + intf.getInterfaceSubclass() + ") -> " + ok);
                    if (ok) claimedInterfaces.add(intf);
                }
            }
            int fd = connection.getFileDescriptor();
            byte[] descriptors = connection.getRawDescriptors();
            if (fd < 0 || descriptors == null || descriptors.length == 0) {
                Log.e("ObsUsbUvc", "Invalid USB fd or descriptors");
                stop();
                failure.run();
                return;
            }
            NativeBridge.enableCapture(4, true);
            Log.i("ObsUsbUvc", "Starting native USB UVC capture fd=" + fd + " descBytes=" + descriptors.length + " targetH=" + requestedHeight);
            if (!NativeBridge.startUvcCapture(fd, descriptors, requestedHeight)) {
                Log.e("ObsUsbUvc", "NativeBridge.startUvcCapture failed");
                stop();
                failure.run();
            }
        } catch (Exception e) {
            Log.e("ObsUsbUvc", "openAndStream failed", e);
            stop();
            failure.run();
        }
    }

    void stop() {
        stopped = true;
        try { NativeBridge.stopUvcCapture(); } catch (Throwable ignored) {}
        if (connection != null) {
            for (UsbInterface intf : claimedInterfaces) {
                try { connection.releaseInterface(intf); } catch (Throwable ignored) {}
            }
            claimedInterfaces.clear();
            try { connection.close(); } catch (Throwable ignored) {}
            connection = null;
        }
        if (receiver != null) {
            try { context.unregisterReceiver(receiver); } catch (Throwable ignored) {}
            receiver = null;
        }
        activeDevice = null;
    }
}
