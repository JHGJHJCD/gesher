/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.gesher.server;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code gesher.bluetooth}: the part of Android's Bluetooth stack that belongs to the device
 * rather than to an app (docs/bluetooth.md). It watches Windows' radio and paired devices
 * and sends the system's Bluetooth broadcasts ({@code ACTION_STATE_CHANGED},
 * {@code ACTION_ACL_CONNECTED}/{@code DISCONNECTED}, {@code ACTION_BOND_STATE_CHANGED}, the
 * A2DP and headset profiles' {@code ACTION_CONNECTION_STATE_CHANGED}), runs classic discovery
 * ({@code ACTION_DISCOVERY_STARTED}, {@code ACTION_FOUND}, {@code ACTION_DISCOVERY_FINISHED})
 * and SDP refreshes ({@code ACTION_UUID}), and turns the radio on and off.
 *
 * <p>Scanning, GATT and sockets run in the app's own process, straight on
 * {@link WindowsBluetooth} (Windows arbitrates the radio, as for cameras): only what Android
 * broadcasts as the system needs the system server. A plain Binder with its own transaction
 * codes, called by {@link Client}; the watch starts with the first call, so nothing polls
 * while no app uses Bluetooth.
 */
public final class BluetoothManagerService extends Binder {
    public static final String NAME = "gesher.bluetooth";
    public static final String DESCRIPTOR = "com.gesher.IBluetooth";

    static final int STATE = IBinder.FIRST_CALL_TRANSACTION;
    static final int SET_ENABLED = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int START_DISCOVERY = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int CANCEL_DISCOVERY = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int IS_DISCOVERING = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int FETCH_UUIDS = IBinder.FIRST_CALL_TRANSACTION + 5;

    static final UUID AUDIO_SINK = UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb");
    static final UUID ADVANCED_AUDIO = UUID.fromString("0000110d-0000-1000-8000-00805f9b34fb");
    static final UUID HANDSFREE = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb");
    static final UUID HEADSET = UUID.fromString("00001108-0000-1000-8000-00805f9b34fb");

    /** How long a discovery runs (Android's is about 12 s). */
    static final int DISCOVERY_SECONDS = 10;

    /** Where the broadcasts go; the system server's ActivityManagerService. */
    public interface Broadcaster {
        void send(Intent intent);
    }

    private final Broadcaster mBroadcaster;
    private final PackageManagerService mPm;
    private final CallerIdentity mCallers;
    private final Object mLock = new Object();
    private final Object mPollLock = new Object();
    private Thread mWatch;
    private Thread mDiscovery;
    private volatile boolean mDiscovering;
    private int mState = -1;  // BluetoothAdapter.STATE_*, -1 before the first look
    private Map<Long, WindowsBluetooth.Device> mDevices = new HashMap<>();
    private long mPollMs = 2000;

    BluetoothManagerService(ActivityManagerService am, PackageManagerService pm) {
        this(intent -> am.sendBroadcastFor("android", intent, null), pm,
                am == null ? null : am.callers());
    }

    /** For tests: broadcasts go to {@code broadcaster}; no caller checks without {@code pm}. */
    public BluetoothManagerService(Broadcaster broadcaster, PackageManagerService pm,
            CallerIdentity callers) {
        super(DESCRIPTOR);
        mBroadcaster = broadcaster;
        mPm = pm;
        mCallers = callers;
    }

    /** For tests: how often the watch looks at Windows. */
    public void setPollInterval(long ms) {
        mPollMs = ms;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code < STATE || code > FETCH_UUIDS) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(DESCRIPTOR);
        startWatch();
        try {
            switch (code) {
                case STATE: {
                    int state = state();
                    reply.writeNoException();
                    reply.writeInt(state);
                    reply.writeBoolean(mDiscovering);
                    return true;
                }
                case SET_ENABLED: {
                    boolean on = data.readBoolean();
                    requirePermission(true);
                    boolean ok = setEnabled(on);
                    reply.writeNoException();
                    reply.writeBoolean(ok);
                    return true;
                }
                case START_DISCOVERY: {
                    requirePermission(false);
                    boolean ok = startDiscovery();
                    reply.writeNoException();
                    reply.writeBoolean(ok);
                    return true;
                }
                case CANCEL_DISCOVERY: {
                    requirePermission(false);
                    boolean ok = cancelDiscovery();
                    reply.writeNoException();
                    reply.writeBoolean(ok);
                    return true;
                }
                case IS_DISCOVERING:
                    reply.writeNoException();
                    reply.writeBoolean(mDiscovering);
                    return true;
                case FETCH_UUIDS: {
                    String address = data.readString();
                    requirePermission(true);
                    boolean ok = fetchUuids(address);
                    reply.writeNoException();
                    reply.writeBoolean(ok);
                    return true;
                }
                default:
                    return false;
            }
        } catch (SecurityException | IllegalArgumentException e) {
            reply.writeException(e);
            return true;
        }
    }

    /** The caller holds some Bluetooth permission (the app side checks the exact one). */
    private void requirePermission(boolean connect) {
        if (mCallers == null || mPm == null || mCallers.isSystem()) return;
        String pkg = mCallers.callerPackage();
        String[] any = connect
                ? new String[] {"android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADMIN",
                    "android.permission.BLUETOOTH"}
                : new String[] {"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADMIN"};
        for (String permission : any) {
            if (mPm.permissions().checkPermission(pkg, permission) == PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        throw new SecurityException(pkg + " has no Bluetooth permission (" + any[0] + ")");
    }

    // --- state ---------------------------------------------------------------------------

    /** BluetoothAdapter.STATE_* of Windows' radio now (no adapter: STATE_OFF). */
    public static int stateOf(WindowsBluetooth.Adapter adapter) {
        return adapter.present && adapter.on ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF;
    }

    public int state() {
        synchronized (mLock) {
            if (mState != -1) return mState;
        }
        return stateOf(WindowsBluetooth.adapter(0));
    }

    public boolean setEnabled(boolean on) {
        WindowsBluetooth.Adapter adapter = WindowsBluetooth.adapter(0);
        if (!adapter.present) return false;
        if (adapter.on == on) return true;
        int turning = on ? BluetoothAdapter.STATE_TURNING_ON : BluetoothAdapter.STATE_TURNING_OFF;
        synchronized (mPollLock) {
            // The baseline first, so that the turn's devices are compared with what was.
            if (mState == -1) pollLocked();
            broadcastState(turning, stateOf(adapter));
            synchronized (mLock) {
                mState = turning;
            }
            int hr = WindowsBluetooth.setEnabled(on);
            SystemServer.log("bluetooth: turning the radio " + (on ? "on" : "off") + ": "
                    + WindowsBluetooth.hresult(hr));
            // ON/OFF (or, when Windows refused: Settings, airplane mode, policy, back to what
            // it is) comes from looking at the radio again.
            pollLocked();
            if (hr < 0) return false;
        }
        return true;
    }

    private void startWatch() {
        synchronized (mLock) {
            if (mWatch != null) return;
            mWatch = new Thread(() -> {
                for (;;) {
                    try {
                        poll();
                        Thread.sleep(mPollMs);
                    } catch (InterruptedException e) {
                        return;
                    } catch (RuntimeException e) {
                        SystemServer.log("bluetooth: watch: " + e);
                    }
                }
            }, "gesher-bluetooth-watch");
            mWatch.setDaemon(true);
            mWatch.start();
        }
        poll();
    }

    /** Looks at Windows once and broadcasts what changed. */
    public void poll() {
        synchronized (mPollLock) {
            pollLocked();
        }
    }

    private void pollLocked() {
        WindowsBluetooth.Adapter adapter = WindowsBluetooth.adapter(0);
        int state = stateOf(adapter);
        int before;
        synchronized (mLock) {
            before = mState;
            mState = state;
        }
        if (before == -1) {
            SystemServer.log("bluetooth: " + adapter.describe());
        } else if (before != state) {
            SystemServer.log("bluetooth: the radio is " + (state == BluetoothAdapter.STATE_ON ? "on" : "off"));
            if (state == BluetoothAdapter.STATE_ON) {
                if (before != BluetoothAdapter.STATE_TURNING_ON) {
                    broadcastState(BluetoothAdapter.STATE_TURNING_ON, before);
                }
                broadcastState(BluetoothAdapter.STATE_ON, BluetoothAdapter.STATE_TURNING_ON);
            } else {
                if (before != BluetoothAdapter.STATE_TURNING_OFF) {
                    broadcastState(BluetoothAdapter.STATE_TURNING_OFF, before);
                }
                broadcastState(BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF);
            }
        }
        Map<Long, WindowsBluetooth.Device> now = new HashMap<>();
        if (state == BluetoothAdapter.STATE_ON) {
            for (WindowsBluetooth.Device d : WindowsBluetooth.bonded()) now.put(d.address, d);
        }
        Map<Long, WindowsBluetooth.Device> old;
        synchronized (mLock) {
            old = mDevices;
            mDevices = now;
        }
        if (before == -1) return;  // the first look sets the baseline
        // Bonds are compared only while the radio stays on: Android keeps them across an
        // off/on cycle (Windows lists no devices while its radio is off).
        boolean bonds = before == BluetoothAdapter.STATE_ON && state == BluetoothAdapter.STATE_ON;
        for (WindowsBluetooth.Device d : now.values()) {
            WindowsBluetooth.Device was = old.get(d.address);
            if (was == null) {
                if (bonds) bond(d, BluetoothDevice.BOND_BONDED, BluetoothDevice.BOND_NONE);
                if (d.connected) connection(d, true);
            } else if (was.connected != d.connected) {
                connection(d, d.connected);
            }
        }
        for (WindowsBluetooth.Device was : old.values()) {
            if (now.containsKey(was.address)) continue;
            if (was.connected) connection(was, false);
            if (bonds) bond(was, BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDED);
        }
    }

    /** The paired devices the watch saw last. */
    public List<WindowsBluetooth.Device> devices() {
        synchronized (mLock) {
            return new ArrayList<>(mDevices.values());
        }
    }

    // --- broadcasts ----------------------------------------------------------------------

    private void send(Intent intent) {
        try {
            mBroadcaster.send(intent);
        } catch (RuntimeException e) {
            SystemServer.log("bluetooth: " + intent.getAction() + " failed: " + e);
        }
    }

    private void broadcastState(int state, int previous) {
        Intent i = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        i.putExtra(BluetoothAdapter.EXTRA_STATE, state);
        i.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, previous);
        send(i);
    }

    static BluetoothDevice device(WindowsBluetooth.Device d) {
        return BluetoothDevice.createFromHost(WindowsBluetooth.address(d.address), d.addressType);
    }

    private void bond(WindowsBluetooth.Device d, int state, int previous) {
        Intent i = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        i.putExtra(BluetoothDevice.EXTRA_DEVICE, device(d));
        i.putExtra(BluetoothDevice.EXTRA_BOND_STATE, state);
        i.putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, previous);
        send(i);
    }

    private void connection(WindowsBluetooth.Device d, boolean connected) {
        BluetoothDevice device = device(d);
        Intent acl = new Intent(connected ? BluetoothDevice.ACTION_ACL_CONNECTED
                : BluetoothDevice.ACTION_ACL_DISCONNECTED);
        acl.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        acl.putExtra(BluetoothDevice.EXTRA_TRANSPORT,
                (d.kind & WindowsBluetooth.KIND_CLASSIC) != 0 ? BluetoothDevice.TRANSPORT_BREDR
                        : BluetoothDevice.TRANSPORT_LE);
        send(acl);
        int state = connected ? BluetoothProfile.STATE_CONNECTED : BluetoothProfile.STATE_DISCONNECTED;
        int previous = connected ? BluetoothProfile.STATE_DISCONNECTED : BluetoothProfile.STATE_CONNECTED;
        if (isA2dp(d)) profile(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, device, state, previous);
        if (isHeadset(d)) profile(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED, device, state, previous);
        Intent adapter = new Intent(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        adapter.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        adapter.putExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, state);
        adapter.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_CONNECTION_STATE, previous);
        send(adapter);
    }

    private void profile(String action, BluetoothDevice device, int state, int previous) {
        Intent i = new Intent(action);
        i.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        i.putExtra(BluetoothProfile.EXTRA_STATE, state);
        i.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, previous);
        send(i);
    }

    /** A device Windows plays audio to (A2DP sink). */
    public static boolean isA2dp(WindowsBluetooth.Device d) {
        return d.has(AUDIO_SINK) || d.has(ADVANCED_AUDIO);
    }

    /** A device with a hands-free or headset service. */
    public static boolean isHeadset(WindowsBluetooth.Device d) {
        return d.has(HANDSFREE) || d.has(HEADSET);
    }

    // --- discovery ---------------------------------------------------------------------------

    public boolean startDiscovery() {
        if (state() != BluetoothAdapter.STATE_ON) return false;
        synchronized (mLock) {
            if (mDiscovery != null) return true;
            mDiscovering = true;
            mDiscovery = new Thread(this::discover, "gesher-bluetooth-discovery");
            mDiscovery.setDaemon(true);
        }
        send(new Intent(BluetoothAdapter.ACTION_DISCOVERY_STARTED));
        mDiscovery.start();
        return true;
    }

    private void discover() {
        try {
            List<WindowsBluetooth.Device> found = WindowsBluetooth.inquiry(DISCOVERY_SECONDS);
            SystemServer.log("bluetooth: discovery found " + found.size() + " device(s)");
            for (WindowsBluetooth.Device d : found) {
                if (!mDiscovering) break;
                Intent i = new Intent(BluetoothDevice.ACTION_FOUND);
                i.putExtra(BluetoothDevice.EXTRA_DEVICE, device(d));
                i.putExtra(BluetoothDevice.EXTRA_CLASS, new BluetoothClass(d.classOfDevice));
                i.putExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                if (!d.name.isEmpty()) i.putExtra(BluetoothDevice.EXTRA_NAME, d.name);
                send(i);
            }
        } finally {
            synchronized (mLock) {
                mDiscovering = false;
                mDiscovery = null;
            }
            send(new Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED));
        }
    }

    public boolean cancelDiscovery() {
        synchronized (mLock) {
            if (mDiscovery == null) return true;
            mDiscovering = false;
        }
        WindowsBluetooth.cancelInquiry();
        return true;
    }

    public boolean isDiscovering() {
        return mDiscovering;
    }

    /** Asks the device for its SDP records again, then broadcasts ACTION_UUID. */
    public boolean fetchUuids(String address) {
        long value = WindowsBluetooth.address(address);
        if (value < 0) throw new IllegalArgumentException(address + " is not a Bluetooth address");
        if (state() != BluetoothAdapter.STATE_ON) return false;
        Thread t = new Thread(() -> {
            List<UUID> uuids = WindowsBluetooth.remoteUuids(value, true);
            Intent i = new Intent(BluetoothDevice.ACTION_UUID);
            i.putExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.createFromHost(address,
                    BluetoothDevice.ADDRESS_TYPE_PUBLIC));
            Parcelable[] list = new Parcelable[uuids.size()];
            for (int k = 0; k < list.length; k++) list[k] = new ParcelUuid(uuids.get(k));
            i.putExtra(BluetoothDevice.EXTRA_UUID, list);
            send(i);
        }, "gesher-bluetooth-sdp");
        t.setDaemon(true);
        t.start();
        return true;
    }

    // --- the app side ------------------------------------------------------------------------

    /** Calls the system server's service; answers "none" where there is no system server. */
    public static final class Client {
        private Client() {
        }

        private static IBinder service() {
            try {
                return ServiceManager.getService(NAME);
            } catch (RuntimeException e) {
                return null;
            }
        }

        public static boolean available() {
            return service() != null;
        }

        /** {state, discovering}; without the service, the radio's state from Windows. */
        public static int[] state() {
            IBinder b = service();
            if (b == null) return new int[] {stateOf(WindowsBluetooth.adapter()), 0};
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                b.transact(STATE, data, reply, 0);
                reply.readException();
                int state = reply.readInt();
                return new int[] {state, reply.readBoolean() ? 1 : 0};
            } catch (RemoteException e) {
                return new int[] {stateOf(WindowsBluetooth.adapter()), 0};
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        private static boolean call(int code, Object argument) {
            IBinder b = service();
            if (b == null) return false;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                if (argument instanceof Boolean) data.writeBoolean((Boolean) argument);
                if (argument instanceof String) data.writeString((String) argument);
                b.transact(code, data, reply, 0);
                reply.readException();
                return reply.readBoolean();
            } catch (RemoteException e) {
                return false;
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        public static boolean setEnabled(boolean on) {
            if (service() == null) {
                // No system server (a bare runtime): straight to Windows.
                return WindowsBluetooth.setEnabled(on) >= 0;
            }
            return call(SET_ENABLED, on);
        }

        public static boolean startDiscovery() {
            return call(START_DISCOVERY, null);
        }

        public static boolean cancelDiscovery() {
            return call(CANCEL_DISCOVERY, null);
        }

        public static boolean isDiscovering() {
            return call(IS_DISCOVERING, null);
        }

        public static boolean fetchUuids(String address) {
            return call(FETCH_UUIDS, address);
        }
    }
}
