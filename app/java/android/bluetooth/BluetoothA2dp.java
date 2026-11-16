/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package android.bluetooth;

import android.content.Context;
import com.gesher.server.WindowsBluetooth;

/**
 * BluetoothA2dp with the Bluetooth module's public API: the paired audio sinks Windows has
 * connected (docs/bluetooth.md). Windows plays the PC's audio to them; whether a stream
 * plays right now is not known (isA2dpPlaying answers false), and connecting or
 * disconnecting is Windows' (Settings).
 */
public final class BluetoothA2dp extends BluetoothAudioProfile {
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    public static final String ACTION_PLAYING_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED";
    /** @hide */
    public static final String ACTION_ACTIVE_DEVICE_CHANGED =
            "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED";
    public static final int STATE_PLAYING = 10;
    public static final int STATE_NOT_PLAYING = 11;

    BluetoothA2dp(Context context) {
        super(context);
    }

    @Override
    boolean offers(WindowsBluetooth.Device device) {
        return isA2dp(device);
    }

    public boolean isA2dpPlaying(BluetoothDevice device) {
        BluetoothHost.enforceConnect(context());
        return false;
    }

    /** @hide Windows connects audio devices itself. */
    public boolean connect(BluetoothDevice device) {
        return false;
    }

    /** @hide */
    public boolean disconnect(BluetoothDevice device) {
        return false;
    }

    /** @hide The connected sink, if any (Windows chooses the audio endpoint). */
    public BluetoothDevice getActiveDevice() {
        java.util.List<BluetoothDevice> connected = getConnectedDevices();
        return connected.isEmpty() ? null : connected.get(0);
    }
}
