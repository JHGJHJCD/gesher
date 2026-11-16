/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package android.bluetooth;

import java.util.List;

/**
 * BluetoothProfile with the Bluetooth module's public API. Gesher's adapter hands out
 * {@link BluetoothA2dp} and {@link BluetoothHeadset} proxies (devices Windows has connected
 * for audio, docs/bluetooth.md); other profiles are not available.
 */
public interface BluetoothProfile {
    String EXTRA_STATE = "android.bluetooth.profile.extra.STATE";
    String EXTRA_PREVIOUS_STATE = "android.bluetooth.profile.extra.PREVIOUS_STATE";
    String EXTRA_PROFILE = "android.bluetooth.profile.extra.PROFILE";

    int STATE_DISCONNECTED = 0;
    int STATE_CONNECTING = 1;
    int STATE_CONNECTED = 2;
    int STATE_DISCONNECTING = 3;

    int HEADSET = 1;
    int A2DP = 2;
    int HEALTH = 3;
    /** @hide */
    int HID_HOST = 4;
    /** @hide */
    int PAN = 5;
    /** @hide */
    int PBAP = 6;
    int GATT = 7;
    int GATT_SERVER = 8;
    /** @hide */
    int MAP = 9;
    int SAP = 10;
    /** @hide */
    int A2DP_SINK = 11;
    /** @hide */
    int AVRCP_CONTROLLER = 12;
    int AVRCP = 13;
    /** @hide */
    int HEADSET_CLIENT = 16;
    /** @hide */
    int PBAP_CLIENT = 17;
    /** @hide */
    int MAP_CLIENT = 18;
    int HID_DEVICE = 19;
    /** @hide */
    int OPP = 20;
    int HEARING_AID = 21;
    int LE_AUDIO = 22;
    /** @hide */
    int VOLUME_CONTROL = 23;
    int MCP_SERVER = 24;
    int CSIP_SET_COORDINATOR = 25;
    /** @hide */
    int LE_AUDIO_BROADCAST = 26;
    int LE_CALL_CONTROL = 27;
    int HAP_CLIENT = 28;
    /** @hide */
    int LE_AUDIO_BROADCAST_ASSISTANT = 29;
    int BATTERY = 30;
    int GMAP = 31;
    int PRIORITY_AUTO_CONNECT = 1000;

    List<BluetoothDevice> getConnectedDevices();

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states);

    int getConnectionState(BluetoothDevice device);

    interface ServiceListener {
        void onServiceConnected(int profile, BluetoothProfile proxy);

        void onServiceDisconnected(int profile);
    }
}
