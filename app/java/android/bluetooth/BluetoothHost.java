/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package android.bluetooth;

import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import com.gesher.server.WindowsBluetooth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gesher's link between the android.bluetooth classes and the host radio
 * ({@link WindowsBluetooth}, docs/bluetooth.md): the app's permissions as the Bluetooth
 * module checks them, the adapter state, and what the process knows about remote devices
 * (paired ones from Windows, names and types from scans).
 *
 * @hide
 */
public final class BluetoothHost {
    static final String TAG = "Bluetooth";

    private BluetoothHost() {
    }

    /** The context permissions are checked against: the one given, else the application. */
    public static Context context(Context given) {
        if (given != null) return given;
        try {
            return ActivityThread.currentApplication();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static int targetSdk(Context context) {
        try {
            return context == null || context.getApplicationInfo() == null
                    ? Build.VERSION_CODES.CUR_DEVELOPMENT
                    : context.getApplicationInfo().targetSdkVersion;
        } catch (RuntimeException e) {
            return Build.VERSION_CODES.CUR_DEVELOPMENT;
        }
    }

    /** No context (a bare runtime, a test without an app): nothing to check against. */
    static boolean granted(Context context, String permission) {
        if (context == null) return true;
        try {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static SecurityException denied(Context context, String permission) {
        String who = context == null ? "this app" : context.getPackageName();
        return new SecurityException("Need " + permission + " permission for " + who
                + ": the Bluetooth module requires it (" + permission + " in the manifest)");
    }

    /** BLUETOOTH_CONNECT (API 31+), else the legacy BLUETOOTH permission. */
    public static void enforceConnect(Context given) {
        Context context = context(given);
        String permission = targetSdk(context) >= Build.VERSION_CODES.S
                ? "android.permission.BLUETOOTH_CONNECT" : "android.permission.BLUETOOTH";
        if (!granted(context, permission)) throw denied(context, permission);
    }

    /** BLUETOOTH_SCAN (API 31+), else the legacy BLUETOOTH_ADMIN permission. */
    public static void enforceScan(Context given) {
        Context context = context(given);
        String permission = targetSdk(context) >= Build.VERSION_CODES.S
                ? "android.permission.BLUETOOTH_SCAN" : "android.permission.BLUETOOTH_ADMIN";
        if (!granted(context, permission)) throw denied(context, permission);
    }

    /** BLUETOOTH_CONNECT (API 31+), else BLUETOOTH_ADMIN (enable, disable, bonding). */
    static void enforceAdmin(Context given) {
        Context context = context(given);
        String permission = targetSdk(context) >= Build.VERSION_CODES.S
                ? "android.permission.BLUETOOTH_CONNECT" : "android.permission.BLUETOOTH_ADMIN";
        if (!granted(context, permission)) throw denied(context, permission);
    }

    /**
     * Whether scan results may reach the app: on API 31+ a BLUETOOTH_SCAN declared with
     * neverForLocation needs no location permission; otherwise ACCESS_FINE_LOCATION
     * (API 29+) or either location permission, as the Bluetooth module filters them.
     */
    public static boolean mayReceiveScanResults(Context given) {
        Context context = context(given);
        if (context == null) return true;
        int target = targetSdk(context);
        if (target >= Build.VERSION_CODES.S && disavowsLocation(context)) return true;
        if (granted(context, "android.permission.ACCESS_FINE_LOCATION")) return true;
        return target < Build.VERSION_CODES.Q
                && granted(context, "android.permission.ACCESS_COARSE_LOCATION");
    }

    private static boolean disavowsLocation(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
                    PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null || info.requestedPermissionsFlags == null) return false;
            for (int i = 0; i < info.requestedPermissions.length; i++) {
                if ("android.permission.BLUETOOTH_SCAN".equals(info.requestedPermissions[i])) {
                    return (info.requestedPermissionsFlags[i]
                            & PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION) != 0;
                }
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w(TAG, "neverForLocation unknown: " + e);
        }
        return false;
    }

    // --- the radio ---------------------------------------------------------------------------

    static WindowsBluetooth.Adapter adapter() {
        return WindowsBluetooth.adapter(1000);
    }

    public static boolean present() {
        return adapter().present;
    }

    public static boolean on() {
        WindowsBluetooth.Adapter a = adapter();
        return a.present && a.on;
    }

    // --- remote devices ------------------------------------------------------------------

    /** What the process knows about a remote device. */
    static final class Known {
        String name;
        int kind;
        int classOfDevice;
        boolean bonded;
        boolean connected;
        List<java.util.UUID> uuids = new ArrayList<>();
    }

    private static final Map<String, Known> sKnown = new HashMap<>();
    private static long sBondedAt = Long.MIN_VALUE / 2;
    private static List<WindowsBluetooth.Device> sBonded = new ArrayList<>();

    /** Windows' paired devices (empty while the radio is off, as Android's), asked every 2 s. */
    static List<WindowsBluetooth.Device> bonded() {
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (sKnown) {
            if (now - sBondedAt < 2000) return new ArrayList<>(sBonded);
        }
        List<WindowsBluetooth.Device> list = on() ? WindowsBluetooth.bonded() : new ArrayList<>();
        synchronized (sKnown) {
            for (Known k : sKnown.values()) {
                k.bonded = false;
                k.connected = false;
            }
            for (WindowsBluetooth.Device d : list) {
                Known k = knownLocked(WindowsBluetooth.address(d.address));
                if (!d.name.isEmpty()) k.name = d.name;
                k.kind |= d.kind;
                k.classOfDevice = d.classOfDevice;
                k.bonded = true;
                k.connected = d.connected;
                if (!d.uuids.isEmpty()) k.uuids = new ArrayList<>(d.uuids);
            }
            sBonded = list;
            sBondedAt = now;
        }
        return new ArrayList<>(list);
    }

    private static Known knownLocked(String address) {
        Known k = sKnown.get(address);
        if (k == null) {
            k = new Known();
            sKnown.put(address, k);
        }
        return k;
    }

    /** A snapshot of what is known about {@code address} (paired devices refreshed first). */
    static Known known(String address) {
        bonded();
        synchronized (sKnown) {
            Known k = sKnown.get(address);
            Known copy = new Known();
            if (k != null) {
                copy.name = k.name;
                copy.kind = k.kind;
                copy.classOfDevice = k.classOfDevice;
                copy.bonded = k.bonded;
                copy.connected = k.connected;
                copy.uuids = new ArrayList<>(k.uuids);
            }
            return copy;
        }
    }

    /** An LE advertisement was seen: its name and kind. */
    public static void sawLe(String address, String name) {
        synchronized (sKnown) {
            Known k = knownLocked(address);
            k.kind |= WindowsBluetooth.KIND_LE;
            if (name != null && !name.isEmpty()) k.name = name;
        }
    }

    /** SDP was asked again: the service classes. */
    static void sawUuids(String address, List<java.util.UUID> uuids) {
        synchronized (sKnown) {
            knownLocked(address).uuids = new ArrayList<>(uuids);
        }
    }

    /** A device of the adapter's process, carrying {@code context} for permission checks. */
    public static BluetoothDevice device(String address, int addressType, Context context) {
        return new BluetoothDevice(address, addressType).withContext(context);
    }
}
