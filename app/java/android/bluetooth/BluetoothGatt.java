/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package android.bluetooth;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import com.gesher.server.WindowsBluetooth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BluetoothGatt with the Bluetooth module's public API over Windows' GATT client
 * (Windows.Devices.Bluetooth.GenericAttributeProfile through {@link WindowsBluetooth};
 * docs/bluetooth.md). It runs in the app's process: a GattSession with MaintainConnection
 * keeps the link up while the app holds it.
 *
 * <p>As in the module, one request (read, write, reliable write) is outstanding at a time
 * (the next answers false until its callback came), and callbacks come on the Handler given
 * to connectGatt or, without one, on a thread of this connection, in order.
 *
 * <p>Windows owns the Client Characteristic Configuration descriptor (0x2902): writing it
 * subscribes through Windows (WriteClientCharacteristicConfigurationDescriptorAsync), and a
 * characteristic that can notify or indicate always has one here, as apps expect.
 * {@link #requestMtu} reports the MTU Windows negotiated (never more than asked for).
 */
public final class BluetoothGatt implements BluetoothProfile {
    private static final String TAG = "BluetoothGatt";

    public static final int GATT_SUCCESS = 0;
    public static final int GATT_READ_NOT_PERMITTED = 0x2;
    public static final int GATT_WRITE_NOT_PERMITTED = 0x3;
    public static final int GATT_INSUFFICIENT_AUTHENTICATION = 0x5;
    public static final int GATT_REQUEST_NOT_SUPPORTED = 0x6;
    public static final int GATT_INSUFFICIENT_ENCRYPTION = 0xf;
    public static final int GATT_INVALID_OFFSET = 0x7;
    public static final int GATT_INSUFFICIENT_AUTHORIZATION = 0x8;
    public static final int GATT_INVALID_ATTRIBUTE_LENGTH = 0xd;
    public static final int GATT_CONNECTION_CONGESTED = 0x8f;
    public static final int GATT_CONNECTION_TIMEOUT = 0x93;
    public static final int GATT_FAILURE = 0x101;
    public static final int CONNECTION_PRIORITY_BALANCED = 0;
    public static final int CONNECTION_PRIORITY_HIGH = 1;
    public static final int CONNECTION_PRIORITY_LOW_POWER = 2;
    public static final int CONNECTION_PRIORITY_DCK = 3;
    public static final int SUBRATE_MODE_OFF = 0;
    public static final int SUBRATE_MODE_LOW = 1;
    public static final int SUBRATE_MODE_BALANCED = 2;
    public static final int SUBRATE_MODE_HIGH = 3;
    public static final int SUBRATE_MODE_SYSTEM_UPDATE = 99;
    public static final int SUBRATE_MODE_NOT_UPDATED = 255;

    /** @hide The stack's generic error (a lost or unreachable link). */
    public static final int GATT_ERROR = 0x85;
    /** The status Android reports when the peer ends the link (HCI 0x13). */
    static final int REMOTE_USER_TERMINATED = 0x13;

    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    /** Instance ids of descriptors Gesher adds (above any 16-bit ATT handle). */
    static final int SYNTHETIC = 0x10000;
    private static final int CONNECT_TIMEOUT_MS = 30000;

    /** Connected GATT clients of this process, per device (two clients may share one). */
    private static final Map<String, Integer> sConnected = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean mCounted =
            new java.util.concurrent.atomic.AtomicBoolean();

    private final BluetoothDevice mDevice;
    private final Context mContext;
    private final BluetoothGattCallback mCallback;
    private final boolean mAutoConnect;
    private final Handler mHandler;
    private final HandlerThread mOwnThread;
    private final ExecutorService mWorker;
    private final Object mLock = new Object();
    private final List<BluetoothGattService> mServices = new ArrayList<>();
    private final Map<Integer, BluetoothGattCharacteristic> mCharacteristics = new HashMap<>();
    private final Map<Integer, BluetoothGattDescriptor> mDescriptors = new HashMap<>();
    private final Map<Integer, byte[]> mSyntheticValues = new HashMap<>();
    private long mNative;          // the WindowsBluetooth GATT handle while connecting/connected
    private int mState = STATE_DISCONNECTED;
    private int mGeneration;       // bumped by disconnect/close: stale work stops
    private boolean mBusy;
    private boolean mClosed;
    private boolean mReliable;
    private int mMtu = 23;

    BluetoothGatt(BluetoothDevice device, Context context, BluetoothGattCallback callback,
            Handler handler, boolean autoConnect) {
        mDevice = device;
        mContext = context;
        mCallback = callback;
        mAutoConnect = autoConnect;
        if (handler != null) {
            mHandler = handler;
            mOwnThread = null;
        } else {
            mOwnThread = new HandlerThread("gesher-gatt-callbacks " + device.getAddress());
            mOwnThread.start();
            mHandler = new Handler(mOwnThread.getLooper());
        }
        mWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gesher-gatt " + device.getAddress());
            t.setDaemon(true);
            return t;
        });
    }

    /** Whether some GATT connection of this process to {@code address} is up. */
    static boolean isConnected(String address) {
        synchronized (sConnected) {
            return sConnected.containsKey(address);
        }
    }

    /** This client's link counts for its device while it is up. */
    private void counted(boolean up) {
        if (mCounted.getAndSet(up) == up) return;
        String address = mDevice.getAddress();
        synchronized (sConnected) {
            int n = sConnected.getOrDefault(address, 0) + (up ? 1 : -1);
            if (n > 0) sConnected.put(address, n); else sConnected.remove(address);
        }
    }

    static List<BluetoothDevice> connectedDevices() {
        List<BluetoothDevice> out = new ArrayList<>();
        synchronized (sConnected) {
            for (String address : sConnected.keySet()) out.add(new BluetoothDevice(address, 0));
        }
        return out;
    }

    private void post(Runnable r) {
        mHandler.post(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "callback failed", e);
                throw e;
            }
        });
    }

    private boolean submit(Runnable r) {
        try {
            mWorker.execute(r);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return false;
        }
    }

    // --- connection -----------------------------------------------------------------------

    void connectAsync() {
        final int generation;
        synchronized (mLock) {
            if (mClosed || mState != STATE_DISCONNECTED) return;
            mState = STATE_CONNECTING;
            generation = mGeneration;
        }
        submit(() -> connectNow(generation));
    }

    private void connectNow(int generation) {
        for (;;) {
            int[] hr = new int[1];
            long handle = WindowsBluetooth.nativeGattOpen(mDevice.value(), mDevice.getAddressType(), hr);
            int[] mtu = new int[] {23};
            int result = handle == 0 ? hr[0]
                    : WindowsBluetooth.nativeGattConnect(handle, CONNECT_TIMEOUT_MS, mtu);
            boolean connected = handle != 0 && result >= 0;
            Log.i(TAG, "connect " + mDevice + ": " + (connected ? "connected, MTU " + mtu[0]
                    : WindowsBluetooth.hresult(result)));
            synchronized (mLock) {
                if (generation != mGeneration || mClosed) {
                    if (handle != 0) WindowsBluetooth.nativeGattClose(handle);
                    return;
                }
                if (connected) {
                    mNative = handle;
                    mState = STATE_CONNECTED;
                    mMtu = mtu[0];
                } else if (handle != 0) {
                    WindowsBluetooth.nativeGattClose(handle);
                }
            }
            if (connected) {
                counted(true);
                startEvents(handle, generation);
                post(() -> mCallback.onConnectionStateChange(this, GATT_SUCCESS, STATE_CONNECTED));
                return;
            }
            if (!mAutoConnect) {
                synchronized (mLock) {
                    if (generation == mGeneration) mState = STATE_DISCONNECTED;
                }
                post(() -> mCallback.onConnectionStateChange(this, GATT_ERROR, STATE_DISCONNECTED));
                return;
            }
            // autoConnect: wait for the device as Android does, until disconnect or close.
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void startEvents(long handle, int generation) {
        Thread t = new Thread(() -> events(handle, generation), "gesher-gatt-events " + mDevice);
        t.setDaemon(true);
        t.start();
    }

    private void events(long handle, int generation) {
        int[] meta = new int[3];
        for (;;) {
            byte[] data = WindowsBluetooth.nativeGattNext(handle, 1000, meta);
            synchronized (mLock) {
                if (generation != mGeneration || mClosed) return;
            }
            switch (meta[0]) {
                case WindowsBluetooth.EVENT_NONE:
                    break;
                case WindowsBluetooth.EVENT_NOTIFICATION: {
                    BluetoothGattCharacteristic c;
                    synchronized (mLock) {
                        c = mCharacteristics.get(meta[1]);
                    }
                    if (c == null || data == null) break;
                    final byte[] value = data;
                    post(() -> {
                        c.setValue(value);
                        mCallback.onCharacteristicChanged(this, c, value);
                    });
                    break;
                }
                case WindowsBluetooth.EVENT_CONNECTION:
                    if (meta[2] == 0) {
                        lost(generation, REMOTE_USER_TERMINATED);
                        return;
                    }
                    break;
                case WindowsBluetooth.EVENT_MTU:
                    synchronized (mLock) {
                        mMtu = meta[2];
                    }
                    break;
                case WindowsBluetooth.EVENT_SERVICES_CHANGED:
                    post(() -> mCallback.onServiceChanged(this));
                    break;
                case WindowsBluetooth.EVENT_CLOSED:
                default:
                    return;
            }
        }
    }

    /** The link went down without the app asking. */
    private void lost(int generation, int status) {
        long handle;
        synchronized (mLock) {
            if (generation != mGeneration || mState == STATE_DISCONNECTED) return;
            handle = mNative;
            mNative = 0;
            mState = STATE_DISCONNECTED;
            mBusy = false;
            mGeneration++;
        }
        counted(false);
        if (handle != 0) WindowsBluetooth.nativeGattClose(handle);
        post(() -> mCallback.onConnectionStateChange(this, status, STATE_DISCONNECTED));
        if (mAutoConnect) connectAsync();
    }

    public boolean connect() {
        BluetoothHost.enforceConnect(mContext);
        synchronized (mLock) {
            if (mClosed) return false;
            if (mState != STATE_DISCONNECTED) return true;
        }
        connectAsync();
        return true;
    }

    public void disconnect() {
        BluetoothHost.enforceConnect(mContext);
        long handle;
        boolean wasUp;
        synchronized (mLock) {
            handle = mNative;
            wasUp = mState != STATE_DISCONNECTED;
            mNative = 0;
            mState = STATE_DISCONNECTED;
            mBusy = false;
            mGeneration++;
        }
        counted(false);
        if (handle != 0) WindowsBluetooth.nativeGattClose(handle);
        if (wasUp) post(() -> mCallback.onConnectionStateChange(this, GATT_SUCCESS, STATE_DISCONNECTED));
    }

    public void close() {
        long handle;
        synchronized (mLock) {
            if (mClosed) return;
            mClosed = true;
            handle = mNative;
            mNative = 0;
            mState = STATE_DISCONNECTED;
            mGeneration++;
        }
        counted(false);
        if (handle != 0) WindowsBluetooth.nativeGattClose(handle);
        mWorker.shutdownNow();
        if (mOwnThread != null) mOwnThread.quitSafely();
    }

    /** @hide */
    public BluetoothAdapter getAdapter() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    public BluetoothDevice getDevice() {
        return mDevice;
    }

    /** The native handle when connected, else 0, and the generation it belongs to. */
    private long connected(int[] generation) {
        synchronized (mLock) {
            generation[0] = mGeneration;
            return mState == STATE_CONNECTED ? mNative : 0;
        }
    }

    private boolean current(int generation) {
        synchronized (mLock) {
            return generation == mGeneration && !mClosed;
        }
    }

    // --- services ------------------------------------------------------------------------

    public boolean discoverServices() {
        BluetoothHost.enforceConnect(mContext);
        int[] generation = new int[1];
        long handle = connected(generation);
        if (handle == 0) return false;
        return submit(() -> {
            int[] status = new int[1];
            String[] lines = WindowsBluetooth.nativeGattDiscover(handle, status);
            if (!current(generation[0])) return;
            if (status[0] == GATT_SUCCESS) build(lines);
            final int result = status[0];
            post(() -> mCallback.onServicesDiscovered(this, result));
        });
    }

    /** @hide */
    public boolean discoverServiceByUuid(UUID uuid) {
        return discoverServices();
    }

    private void build(String[] lines) {
        List<BluetoothGattService> services = new ArrayList<>();
        Map<Integer, BluetoothGattCharacteristic> characteristics = new HashMap<>();
        Map<Integer, BluetoothGattDescriptor> descriptors = new HashMap<>();
        Map<Integer, BluetoothGattService> byHandle = new HashMap<>();
        List<int[]> includes = new ArrayList<>();  // {including service, included service}
        BluetoothGattService service = null;
        BluetoothGattCharacteristic characteristic = null;
        for (String line : lines) {
            String[] f = line.split("\t");
            if (f.length < 4) continue;
            int kind = Integer.parseInt(f[0]);
            UUID uuid = UUID.fromString(f[1]);
            int handle = Integer.parseInt(f[2]);
            int properties = Integer.parseInt(f[3]);
            if (kind == WindowsBluetooth.ATTRIBUTE_SERVICE) {
                addCccd(characteristic, descriptors);
                characteristic = null;
                // properties: 0 primary, 1 secondary (reached as an included service).
                service = new BluetoothGattService(mDevice, uuid, handle, properties == 1
                        ? BluetoothGattService.SERVICE_TYPE_SECONDARY
                        : BluetoothGattService.SERVICE_TYPE_PRIMARY);
                services.add(service);
                byHandle.put(handle, service);
            } else if (kind == WindowsBluetooth.ATTRIBUTE_INCLUDED) {
                includes.add(new int[] {properties, handle});
            } else if (kind == WindowsBluetooth.ATTRIBUTE_CHARACTERISTIC && service != null) {
                addCccd(characteristic, descriptors);
                characteristic = new BluetoothGattCharacteristic(service, uuid, handle, properties, 0);
                service.addCharacteristic(characteristic);
                characteristics.put(handle, characteristic);
            } else if (kind == WindowsBluetooth.ATTRIBUTE_DESCRIPTOR && characteristic != null) {
                BluetoothGattDescriptor d = new BluetoothGattDescriptor(characteristic, uuid, handle, 0);
                characteristic.addDescriptor(d);
                descriptors.put(handle, d);
            }
        }
        addCccd(characteristic, descriptors);
        for (int[] pair : includes) {
            BluetoothGattService including = byHandle.get(pair[0]);
            BluetoothGattService included = byHandle.get(pair[1]);
            if (including != null && included != null) including.addIncludedService(included);
        }
        synchronized (mLock) {
            mServices.clear();
            mServices.addAll(services);
            mCharacteristics.clear();
            mCharacteristics.putAll(characteristics);
            mDescriptors.clear();
            mDescriptors.putAll(descriptors);
        }
    }

    /** A notifying characteristic without a listed CCCD gets one (Windows may hide it). */
    private static void addCccd(BluetoothGattCharacteristic c,
            Map<Integer, BluetoothGattDescriptor> descriptors) {
        if (c == null) return;
        int notify = BluetoothGattCharacteristic.PROPERTY_NOTIFY
                | BluetoothGattCharacteristic.PROPERTY_INDICATE;
        if ((c.getProperties() & notify) == 0 || c.getDescriptor(CCCD) != null) return;
        int id = SYNTHETIC | c.getInstanceId();
        BluetoothGattDescriptor d = new BluetoothGattDescriptor(c, CCCD, id,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        c.addDescriptor(d);
        descriptors.put(id, d);
    }

    public List<BluetoothGattService> getServices() {
        synchronized (mLock) {
            return new ArrayList<>(mServices);
        }
    }

    public BluetoothGattService getService(UUID uuid) {
        synchronized (mLock) {
            for (BluetoothGattService s : mServices) {
                if (s.getUuid().equals(uuid)) return s;
            }
        }
        return null;
    }

    /** @hide Clears the app's view of the services (apps call it through reflection). */
    public boolean refresh() {
        synchronized (mLock) {
            mServices.clear();
            mCharacteristics.clear();
            mDescriptors.clear();
        }
        return true;
    }

    // --- reads and writes ---------------------------------------------------------------

    /** Takes the one request slot; false when a request is outstanding or not connected. */
    private long claim(int[] generation) {
        synchronized (mLock) {
            if (mBusy || mState != STATE_CONNECTED || mClosed) return 0;
            mBusy = true;
            generation[0] = mGeneration;
            return mNative;
        }
    }

    private void release(int generation) {
        synchronized (mLock) {
            if (generation == mGeneration) mBusy = false;
        }
    }

    public boolean readCharacteristic(BluetoothGattCharacteristic characteristic) {
        BluetoothHost.enforceConnect(mContext);
        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            return false;
        }
        int[] generation = new int[1];
        long handle = claim(generation);
        if (handle == 0) return false;
        int id = characteristic.getInstanceId();
        return submit(() -> {
            int[] status = new int[1];
            byte[] value = WindowsBluetooth.nativeGattRead(handle,
                    WindowsBluetooth.ATTRIBUTE_CHARACTERISTIC, id, status);
            if (!current(generation[0])) return;
            final byte[] result = value != null ? value : new byte[0];
            final int s = status[0];
            post(() -> {
                release(generation[0]);
                if (s == GATT_SUCCESS) characteristic.setValue(result);
                mCallback.onCharacteristicRead(this, characteristic, result, s);
            });
        }) || releaseFalse(generation[0]);
    }

    private boolean releaseFalse(int generation) {
        release(generation);
        return false;
    }

    /**
     * @hide Reads the first discovered characteristic of {@code uuid} whose handle is in
     * [startHandle, endHandle]; onCharacteristicRead reports it, as in the module (which
     * also drops results for characteristics it has not discovered). False when none is.
     */
    public boolean readUsingCharacteristicUuid(UUID uuid, int startHandle, int endHandle) {
        BluetoothHost.enforceConnect(mContext);
        BluetoothGattCharacteristic match = null;
        synchronized (mLock) {
            for (Map.Entry<Integer, BluetoothGattCharacteristic> e : new java.util.TreeMap<>(mCharacteristics).entrySet()) {
                int handle = e.getKey();
                if (handle >= startHandle && handle <= endHandle
                        && e.getValue().getUuid().equals(uuid)) {
                    match = e.getValue();
                    break;
                }
            }
        }
        return match != null && readCharacteristic(match);
    }

    @Deprecated
    public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        byte[] value = characteristic.getValue();
        if (value == null) return false;
        return writeCharacteristic(characteristic, value, characteristic.getWriteType())
                == BluetoothStatusCodes.SUCCESS;
    }

    public int writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value,
            int writeType) {
        if (characteristic == null) throw new IllegalArgumentException("characteristic must not be null");
        if (value == null) throw new IllegalArgumentException("value must not be null");
        BluetoothHost.enforceConnect(mContext);
        int writable = BluetoothGattCharacteristic.PROPERTY_WRITE
                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
        if ((characteristic.getProperties() & writable) == 0) {
            return BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED;
        }
        int[] generation = new int[1];
        long handle = claim(generation);
        if (handle == 0) {
            synchronized (mLock) {
                return mState != STATE_CONNECTED ? BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED
                        : BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY;
            }
        }
        int id = characteristic.getInstanceId();
        boolean withResponse = writeType != BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        final byte[] copy = value.clone();
        boolean queued = submit(() -> {
            int status = WindowsBluetooth.nativeGattWrite(handle,
                    WindowsBluetooth.ATTRIBUTE_CHARACTERISTIC, id, copy, withResponse);
            if (!current(generation[0])) return;
            post(() -> {
                release(generation[0]);
                mCallback.onCharacteristicWrite(this, characteristic, status);
            });
        });
        if (!queued) {
            release(generation[0]);
            return BluetoothStatusCodes.ERROR_UNKNOWN;
        }
        return BluetoothStatusCodes.SUCCESS;
    }

    public boolean readDescriptor(BluetoothGattDescriptor descriptor) {
        BluetoothHost.enforceConnect(mContext);
        int[] generation = new int[1];
        long handle = claim(generation);
        if (handle == 0) return false;
        int id = descriptor.getInstanceId();
        return submit(() -> {
            int[] status = new int[1];
            byte[] value;
            if ((id & SYNTHETIC) != 0) {
                synchronized (mLock) {
                    value = mSyntheticValues.getOrDefault(id,
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE).clone();
                }
            } else {
                value = WindowsBluetooth.nativeGattRead(handle, WindowsBluetooth.ATTRIBUTE_DESCRIPTOR,
                        id, status);
            }
            if (!current(generation[0])) return;
            final byte[] result = value != null ? value : new byte[0];
            final int s = status[0];
            post(() -> {
                release(generation[0]);
                if (s == GATT_SUCCESS) descriptor.setValue(result);
                mCallback.onDescriptorRead(this, descriptor, s, result);
            });
        }) || releaseFalse(generation[0]);
    }

    @Deprecated
    public boolean writeDescriptor(BluetoothGattDescriptor descriptor) {
        byte[] value = descriptor.getValue();
        if (value == null) return false;
        return writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS;
    }

    public int writeDescriptor(BluetoothGattDescriptor descriptor, byte[] value) {
        if (descriptor == null) throw new IllegalArgumentException("descriptor must not be null");
        if (value == null) throw new IllegalArgumentException("value must not be null");
        BluetoothHost.enforceConnect(mContext);
        int[] generation = new int[1];
        long handle = claim(generation);
        if (handle == 0) {
            synchronized (mLock) {
                return mState != STATE_CONNECTED ? BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED
                        : BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY;
            }
        }
        int id = descriptor.getInstanceId();
        BluetoothGattCharacteristic owner = descriptor.getCharacteristic();
        boolean cccd = CCCD.equals(descriptor.getUuid()) && owner != null;
        final byte[] copy = value.clone();
        boolean queued = submit(() -> {
            int status;
            if (cccd) {
                // Windows owns the CCCD: subscribe through it (0 none, 1 notify, 2 indicate).
                int mode = copy.length > 0 ? copy[0] & 3 : 0;
                status = WindowsBluetooth.nativeGattNotify(handle, owner.getInstanceId(), mode);
                if (status == GATT_SUCCESS) {
                    synchronized (mLock) {
                        mSyntheticValues.put(id, copy);
                    }
                }
            } else {
                status = WindowsBluetooth.nativeGattWrite(handle, WindowsBluetooth.ATTRIBUTE_DESCRIPTOR,
                        id, copy, true);
            }
            if (!current(generation[0])) return;
            final int s = status;
            post(() -> {
                release(generation[0]);
                mCallback.onDescriptorWrite(this, descriptor, s);
            });
        });
        if (!queued) {
            release(generation[0]);
            return BluetoothStatusCodes.ERROR_UNKNOWN;
        }
        return BluetoothStatusCodes.SUCCESS;
    }

    /**
     * Registers the app for the characteristic's notifications, as the module does; the
     * peer starts sending them when the app writes the CCCD (as on Android).
     */
    public boolean setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
            boolean enable) {
        BluetoothHost.enforceConnect(mContext);
        synchronized (mLock) {
            return mState == STATE_CONNECTED && characteristic != null
                    && characteristic.getService() != null;
        }
    }

    /** Windows offers no reliable (queued) writes to applications. */
    public boolean beginReliableWrite() {
        BluetoothHost.enforceConnect(mContext);
        synchronized (mLock) {
            mReliable = mState == STATE_CONNECTED;
            return false;
        }
    }

    public boolean executeReliableWrite() {
        BluetoothHost.enforceConnect(mContext);
        synchronized (mLock) {
            mReliable = false;
        }
        return false;
    }

    public void abortReliableWrite() {
        synchronized (mLock) {
            mReliable = false;
        }
    }

    /** @hide */
    @Deprecated
    public void abortReliableWrite(BluetoothDevice device) {
        abortReliableWrite();
    }

    // --- link parameters -------------------------------------------------------------

    /** The RSSI of the device's last advertisement this process saw (Windows has no call). */
    public boolean readRemoteRssi() {
        BluetoothHost.enforceConnect(mContext);
        synchronized (mLock) {
            if (mState != STATE_CONNECTED) return false;
        }
        Integer rssi = android.bluetooth.le.BluetoothLeScanner.lastRssi(mDevice.getAddress());
        post(() -> mCallback.onReadRemoteRssi(this, rssi != null ? rssi : -127,
                rssi != null ? GATT_SUCCESS : GATT_FAILURE));
        return true;
    }

    /** Windows negotiates the MTU itself: this reports it (at most what was asked for). */
    public boolean requestMtu(int mtu) {
        BluetoothHost.enforceConnect(mContext);
        int[] generation = new int[1];
        long handle = connected(generation);
        if (handle == 0) return false;
        return submit(() -> {
            int now = WindowsBluetooth.nativeGattMtu(handle);
            if (!current(generation[0])) return;
            synchronized (mLock) {
                mMtu = now;
            }
            final int reported = Math.max(23, Math.min(mtu, now));
            post(() -> mCallback.onMtuChanged(this, reported, GATT_SUCCESS));
        });
    }

    /** Accepted; Windows chooses the connection parameters. */
    public boolean requestConnectionPriority(int connectionPriority) {
        if (connectionPriority < CONNECTION_PRIORITY_BALANCED
                || connectionPriority > CONNECTION_PRIORITY_DCK) {
            throw new IllegalArgumentException("connectionPriority not within valid range");
        }
        BluetoothHost.enforceConnect(mContext);
        synchronized (mLock) {
            return mState == STATE_CONNECTED;
        }
    }

    /** @hide */
    public boolean requestLeConnectionUpdate(int minConnectionInterval, int maxConnectionInterval,
            int slaveLatency, int supervisionTimeout, int minConnectionEventLen,
            int maxConnectionEventLen) {
        return requestConnectionPriority(CONNECTION_PRIORITY_BALANCED);
    }

    public int requestSubrateMode(int subrateMode) {
        return BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
    }

    /**
     * Windows chooses the PHY and offers applications no way to ask for another: the
     * request is declined (GATT_REQUEST_NOT_SUPPORTED, as a controller that refuses it),
     * reporting the PHY Windows says the link uses (Windows 11), else 1M.
     */
    public void setPreferredPhy(int txPhy, int rxPhy, int phyOptions) {
        BluetoothHost.enforceConnect(mContext);
        reportPhy(true);
    }

    /** The link's PHY as Windows 11 reports it; before that Windows does not say. */
    public void readPhy() {
        BluetoothHost.enforceConnect(mContext);
        reportPhy(false);
    }

    private void reportPhy(boolean update) {
        int[] generation = new int[1];
        long handle = connected(generation);
        submit(() -> {
            int[] phy = new int[] {BluetoothDevice.PHY_LE_1M, BluetoothDevice.PHY_LE_1M};
            int status = handle == 0 ? GATT_FAILURE : WindowsBluetooth.nativeGattPhy(handle, phy);
            if (status != GATT_SUCCESS) {
                phy[0] = BluetoothDevice.PHY_LE_1M;
                phy[1] = BluetoothDevice.PHY_LE_1M;
            }
            final int result = update ? GATT_REQUEST_NOT_SUPPORTED : status;
            post(() -> {
                if (update) {
                    mCallback.onPhyUpdate(this, phy[0], phy[1], result);
                } else {
                    mCallback.onPhyRead(this, phy[0], phy[1], result);
                }
            });
        });
    }

    // --- BluetoothProfile (not supported by BluetoothGatt, as in the module) ----------------

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
