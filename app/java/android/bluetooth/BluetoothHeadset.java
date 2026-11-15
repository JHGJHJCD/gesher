/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package android.bluetooth;

import android.content.Context;
import com.gesher.server.WindowsBluetooth;

/**
 * BluetoothHeadset with the Bluetooth module's public API: the paired hands-free and
 * headset devices Windows has connected (docs/bluetooth.md). SCO audio and voice
 * recognition are Windows' (the communications endpoint): not started from here.
 */
public final class BluetoothHeadset extends BluetoothAudioProfile {
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED";
    public static final String ACTION_AUDIO_STATE_CHANGED =
            "android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED";
    public static final String ACTION_VENDOR_SPECIFIC_HEADSET_EVENT =
            "android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT";
    public static final String EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_CMD";
    public static final String EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE";
    public static final String EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS =
            "android.bluetooth.headset.extra.VENDOR_SPECIFIC_HEADSET_EVENT_ARGS";
    public static final String VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY =
            "android.bluetooth.headset.intent.category.companyid";
    public static final int AT_CMD_TYPE_READ = 0;
    public static final int AT_CMD_TYPE_TEST = 1;
    public static final int AT_CMD_TYPE_SET = 2;
    public static final int AT_CMD_TYPE_BASIC = 3;
    public static final int AT_CMD_TYPE_ACTION = 4;
    public static final int STATE_AUDIO_DISCONNECTED = 10;
    public static final int STATE_AUDIO_CONNECTING = 11;
    public static final int STATE_AUDIO_CONNECTED = 12;

    BluetoothHeadset(Context context) {
        super(context);
    }

    @Override
    boolean offers(WindowsBluetooth.Device device) {
        return isHeadset(device);
    }

    public boolean isAudioConnected(BluetoothDevice device) {
        BluetoothHost.enforceConnect(context());
        return false;
    }

    public boolean startVoiceRecognition(BluetoothDevice device) {
        BluetoothHost.enforceConnect(context());
        return false;
    }

    public boolean stopVoiceRecognition(BluetoothDevice device) {
        BluetoothHost.enforceConnect(context());
        return false;
    }

    public boolean isNoiseReductionSupported(BluetoothDevice device) {
        return false;
    }

    public boolean isVoiceRecognitionSupported(BluetoothDevice device) {
        return false;
    }

    public boolean sendVendorSpecificResultCode(BluetoothDevice device, String command,
            String arg) {
        return false;
    }
}
