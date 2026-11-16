/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package android.bluetooth.le;

import android.os.Handler;
import android.os.Looper;

/**
 * BluetoothLeAdvertiser with the Bluetooth module's legacy advertising API. Gesher does not
 * advertise yet (Windows' BluetoothLEAdvertisementPublisher could; docs/bluetooth.md):
 * every start fails with {@link AdvertiseCallback#ADVERTISE_FAILED_FEATURE_UNSUPPORTED},
 * as on a controller without advertising, and BluetoothAdapter reports
 * isMultipleAdvertisementSupported() false.
 */
public final class BluetoothLeAdvertiser {
    /** @hide */
    public BluetoothLeAdvertiser() {
    }

    public void startAdvertising(AdvertiseSettings settings, AdvertiseData advertiseData,
            AdvertiseCallback callback) {
        startAdvertising(settings, advertiseData, null, callback);
    }

    public void startAdvertising(AdvertiseSettings settings, AdvertiseData advertiseData,
            AdvertiseData scanResponse, AdvertiseCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        Looper main = Looper.getMainLooper();
        Runnable fail = () -> callback.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED);
        if (main == null) {
            fail.run();
        } else {
            new Handler(main).post(fail);
        }
    }

    public void stopAdvertising(AdvertiseCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
    }

    /** @hide */
    public void cleanup() {
    }
}
