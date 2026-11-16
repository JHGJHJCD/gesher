/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package android.bluetooth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BluetoothGattServer's type, for code that names it. Gesher has no GATT server yet
 * (Windows' GattServiceProvider could be one; docs/bluetooth.md): BluetoothManager's
 * openGattServer answers null, so no instance exists.
 */
public final class BluetoothGattServer implements BluetoothProfile {
    private BluetoothGattServer() {
    }

    public void close() {}

    public boolean addService(BluetoothGattService service) {
        return false;
    }

    public boolean removeService(BluetoothGattService service) {
        return false;
    }

    public void clearServices() {}

    public BluetoothGattService getService(UUID uuid) {
        return null;
    }

    public List<BluetoothGattService> getServices() {
        return new ArrayList<>();
    }

    public boolean connect(BluetoothDevice device, boolean autoConnect) {
        return false;
    }

    public void cancelConnection(BluetoothDevice device) {}

    public boolean sendResponse(BluetoothDevice device, int requestId, int status, int offset,
            byte[] value) {
        return false;
    }

    public boolean notifyCharacteristicChanged(BluetoothDevice device,
            BluetoothGattCharacteristic characteristic, boolean confirm) {
        return false;
    }

    @Override
    public int getConnectionState(BluetoothDevice device) {
        throw new UnsupportedOperationException("Use BluetoothManager#getConnectionState instead.");
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices() {
        throw new UnsupportedOperationException("Use BluetoothManager#getConnectedDevices instead.");
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        throw new UnsupportedOperationException(
                "Use BluetoothManager#getDevicesMatchingConnectionStates instead.");
    }
}
