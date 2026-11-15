/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package android.bluetooth;

import android.content.Context;
import com.gesher.server.WindowsBluetooth;
import java.io.Closeable;
import java.io.IOException;
import java.util.UUID;

/**
 * BluetoothServerSocket with the Bluetooth module's public API: an RFCOMM listener on a
 * Winsock AF_BTH socket, registered in Windows' SDP records with the service name and UUID
 * (WSASetService; docs/bluetooth.md).
 */
public final class BluetoothServerSocket implements Closeable {
    private final Context mContext;
    private final long mNative;
    private final int mChannel;
    private final UUID mUuid;
    private volatile boolean mClosed;

    private BluetoothServerSocket(Context context, long handle, int channel, UUID uuid) {
        mContext = context;
        mNative = handle;
        mChannel = channel;
        mUuid = uuid;
    }

    static BluetoothServerSocket listen(Context context, String name, UUID uuid, boolean secure)
            throws IOException {
        int[] out = new int[2];
        long handle = WindowsBluetooth.nativeRfcommListen(name == null ? "" : name,
                uuid.toString(), secure, out);
        if (handle == 0) {
            throw new IOException("Error: " + WindowsBluetooth.hresult(out[0])
                    + " while listening on " + uuid);
        }
        return new BluetoothServerSocket(context, handle, out[1], uuid);
    }

    public BluetoothSocket accept() throws IOException {
        return accept(-1);
    }

    /** {@code timeout} in ms (&lt;= 0: none); IOException on time-out, as in the module. */
    public BluetoothSocket accept(int timeout) throws IOException {
        long deadline = timeout > 0 ? System.currentTimeMillis() + timeout : Long.MAX_VALUE;
        long[] out = new long[3];
        for (;;) {
            if (mClosed) throw new IOException("socket closed");
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) throw new IOException("Try again");
            int step = (int) Math.min(left, 500);
            long handle = WindowsBluetooth.nativeRfcommAccept(mNative, step, out);
            if (handle != 0) {
                BluetoothDevice remote = BluetoothHost.device(WindowsBluetooth.address(out[1]),
                        BluetoothDevice.ADDRESS_TYPE_PUBLIC, mContext);
                return new BluetoothSocket(remote, handle, (int) out[2]);
            }
            // 0x8007274C: WSAETIMEDOUT, the step ended; anything else ends the listener.
            if ((int) out[0] != 0x8007274C) {
                if (mClosed) throw new IOException("socket closed");
                throw new IOException("accept failed: " + WindowsBluetooth.hresult((int) out[0]));
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (mClosed) return;
        mClosed = true;
        WindowsBluetooth.nativeRfcommServerClose(mNative);
    }

    /** RFCOMM listeners have no PSM. */
    public int getPsm() {
        return -1;
    }

    /** @hide */
    public int getChannel() {
        return mChannel;
    }

    @Override
    public String toString() {
        return "ServerSocket: Type: TYPE_RFCOMM Channel: " + mChannel + " UUID: " + mUuid;
    }
}
