/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package android.bluetooth;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/**
 * BluetoothManager with the Bluetooth module's public API over the host radio
 * (docs/bluetooth.md). {@link #getAdapter()} is an adapter for this context when Windows
 * has a Bluetooth radio, else null, as on a device without Bluetooth. Registered as
 * {@code bluetooth} by {@link BluetoothFrameworkInitializer}.
 */
public final class BluetoothManager {
    private final Context mContext;
    private BluetoothAdapter mAdapter;

    /** @hide */
    public BluetoothManager(Context context) {
        mContext = context;
    }

    public BluetoothAdapter getAdapter() {
        synchronized (this) {
            if (mAdapter == null) mAdapter = BluetoothAdapter.createAdapter(mContext);
            return mAdapter;
        }
    }

    /** GATT connections of this process (GATT); other profiles: none. */
    public List<BluetoothDevice> getConnectedDevices(int profile) {
        BluetoothHost.enforceConnect(mContext);
        if (profile != BluetoothProfile.GATT && profile != BluetoothProfile.GATT_SERVER) {
            throw new IllegalArgumentException("Profile not supported: " + profile);
        }
        return profile == BluetoothProfile.GATT ? BluetoothGatt.connectedDevices() : new ArrayList<>();
    }

    public int getConnectionState(BluetoothDevice device, int profile) {
        BluetoothHost.enforceConnect(mContext);
        if (profile != BluetoothProfile.GATT && profile != BluetoothProfile.GATT_SERVER) {
            throw new IllegalArgumentException("Profile not supported: " + profile);
        }
        return profile == BluetoothProfile.GATT && device != null
                && BluetoothGatt.isConnected(device.getAddress())
                ? BluetoothProfile.STATE_CONNECTED : BluetoothProfile.STATE_DISCONNECTED;
    }

    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int profile, int[] states) {
        List<BluetoothDevice> connected = getConnectedDevices(profile);
        List<BluetoothDevice> out = new ArrayList<>();
        if (states == null) return out;
        for (int s : states) {
            if (s == BluetoothProfile.STATE_CONNECTED) out.addAll(connected);
        }
        return out;
    }

    /** No GATT server on this device yet (docs/bluetooth.md): null, as when it is unavailable. */
    public BluetoothGattServer openGattServer(Context context, BluetoothGattServerCallback callback) {
        BluetoothHost.enforceConnect(context != null ? context : mContext);
        return null;
    }

    /** @hide */
    public BluetoothGattServer openGattServer(Context context, BluetoothGattServerCallback callback,
            int transport) {
        return openGattServer(context, callback);
    }
}
