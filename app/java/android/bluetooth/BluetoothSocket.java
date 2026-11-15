/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package android.bluetooth;

import android.util.Log;
import com.gesher.server.WindowsBluetooth;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * BluetoothSocket with the Bluetooth module's public API: RFCOMM over Winsock's AF_BTH
 * sockets ({@link WindowsBluetooth}; docs/bluetooth.md). {@link #connect()} looks the
 * service UUID up in the device's SDP records (Windows does) or uses the channel of
 * createRfcommSocket. As on Android, reading at the end of the stream throws IOException.
 *
 * <p>Windows applies its own security: a device must be paired with Windows (Settings,
 * Bluetooth &amp; devices) for most RFCOMM services; "insecure" sockets skip the explicit
 * authentication request.
 */
public final class BluetoothSocket implements Closeable {
    private static final String TAG = "BluetoothSocket";

    public static final int TYPE_RFCOMM = 1;
    public static final int TYPE_SCO = 2;
    public static final int TYPE_L2CAP = 3;
    public static final int TYPE_L2CAP_LE = 4;
    public static final int TYPE_LE = TYPE_L2CAP_LE;
    /** RFCOMM's default frame size, what Android reports for RFCOMM sockets. */
    private static final int RFCOMM_PACKET = 990;

    private final BluetoothDevice mDevice;
    private final UUID mUuid;
    private final boolean mSecure;
    private int mChannel;
    private final Object mLock = new Object();
    private long mNative;
    private boolean mConnected;
    private boolean mClosed;
    private final InputStream mInput = new Input();
    private final OutputStream mOutput = new Output();

    BluetoothSocket(BluetoothDevice device, UUID uuid, int channel, boolean secure) {
        mDevice = device;
        mUuid = uuid;
        mChannel = channel;
        mSecure = secure;
    }

    /** An accepted connection of a BluetoothServerSocket. */
    BluetoothSocket(BluetoothDevice device, long handle, int channel) {
        this(device, null, channel, true);
        mNative = handle;
        mConnected = true;
    }

    public void connect() throws IOException {
        synchronized (mLock) {
            if (mClosed) throw new IOException("socket closed");
            if (mConnected) throw new IOException("already connected");
        }
        BluetoothHost.enforceConnect(mDevice.context());
        if (!BluetoothHost.on()) throw new IOException("Bluetooth is off");
        // A discovery in progress slows a connection down; Android apps are told to cancel it.
        int[] out = new int[2];
        long handle = WindowsBluetooth.nativeRfcommConnect(mDevice.value(),
                mUuid == null ? null : mUuid.toString(), mChannel, mSecure, out);
        if (handle == 0) {
            Log.w(TAG, "connect " + mDevice + " " + (mUuid != null ? mUuid : "channel " + mChannel)
                    + ": " + WindowsBluetooth.hresult(out[0]));
            throw new IOException("read failed, socket might closed or timeout, read ret: -1 ("
                    + WindowsBluetooth.hresult(out[0]) + ")");
        }
        synchronized (mLock) {
            if (mClosed) {
                WindowsBluetooth.nativeRfcommClose(handle);
                throw new IOException("socket closed");
            }
            mNative = handle;
            mChannel = out[1];
            mConnected = true;
        }
    }

    public boolean isConnected() {
        synchronized (mLock) {
            return mConnected && !mClosed;
        }
    }

    @Override
    public void close() throws IOException {
        long handle;
        synchronized (mLock) {
            if (mClosed) return;
            mClosed = true;
            mConnected = false;
            handle = mNative;
            mNative = 0;
        }
        if (handle != 0) WindowsBluetooth.nativeRfcommClose(handle);
    }

    public InputStream getInputStream() throws IOException {
        return mInput;
    }

    public OutputStream getOutputStream() throws IOException {
        return mOutput;
    }

    public BluetoothDevice getRemoteDevice() {
        return mDevice;
    }

    public int getConnectionType() {
        return TYPE_RFCOMM;
    }

    public int getMaxTransmitPacketSize() {
        return RFCOMM_PACKET;
    }

    public int getMaxReceivePacketSize() {
        return RFCOMM_PACKET;
    }

    /** @hide The RFCOMM channel. */
    public int getPort() {
        return mChannel;
    }

    private long handle() throws IOException {
        synchronized (mLock) {
            if (mClosed || mNative == 0) throw new IOException("socket closed");
            return mNative;
        }
    }

    private final class Input extends InputStream {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
            if (len == 0) return 0;
            int n = WindowsBluetooth.nativeRfcommRead(handle(), b, off, len);
            if (n <= 0) {
                synchronized (mLock) {
                    mConnected = false;
                }
                // The module throws at the end of the stream too.
                throw new IOException("bt socket closed, read return: " + (n == 0 ? -1 : n));
            }
            return n;
        }

        @Override
        public int available() throws IOException {
            return WindowsBluetooth.nativeRfcommAvailable(handle());
        }

        @Override
        public void close() throws IOException {
            BluetoothSocket.this.close();
        }
    }

    private final class Output extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
            if (len == 0) return;
            int n = WindowsBluetooth.nativeRfcommWrite(handle(), b, off, len);
            if (n < 0) {
                synchronized (mLock) {
                    mConnected = false;
                }
                throw new IOException("bt socket closed, write return: " + n);
            }
        }

        @Override
        public void close() throws IOException {
            BluetoothSocket.this.close();
        }
    }

    @Override
    public String toString() {
        return "BluetoothSocket{" + mDevice + ", RFCOMM channel " + mChannel + "}";
    }
}
