/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.os.ServiceManager;
import com.gesher.server.BluetoothManagerService;
import com.gesher.server.WindowsBluetooth;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The android.bluetooth classes end to end over GESHER_BLUETOOTH_TEST's radio
 * (compat/devices/bluetooth_test.cc, below the JNI layer; docs/bluetooth.md), in one bare
 * process with BluetoothManagerService installed in-process: permissions, adapter state,
 * paired devices, profiles, LE scan, GATT, RFCOMM client and listener, discovery and the
 * system's broadcasts. With {@code --no-radio} (no test radio) it checks that a PC without
 * a radio has no adapter and prints what Windows answered.
 */
public final class BluetoothHostTest {
    private static int checks;
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final UUID VENDOR = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e70f1a5");
    private static final UUID BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID UART = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String HEADSET = "00:02:5B:00:A5:A5";
    private static final String PERIPHERAL = "C0:FF:EE:00:00:01";

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }

    private static void step(String text) {
        System.out.println("BluetoothHostTest: " + text);
    }

    /** An app context with the given manifest permissions, target SDK and package. */
    static final class App extends ContextWrapper {
        private final Set<String> mGranted;
        private final int mTarget;

        App(int target, String... granted) {
            super(null);
            mTarget = target;
            mGranted = new HashSet<>(Arrays.asList(granted));
        }

        @Override
        public int checkSelfPermission(String permission) {
            return mGranted.contains(permission) ? PackageManager.PERMISSION_GRANTED
                    : PackageManager.PERMISSION_DENIED;
        }

        @Override
        public String getPackageName() {
            return "fixture.bluetooth";
        }

        @Override
        public ApplicationInfo getApplicationInfo() {
            ApplicationInfo info = new ApplicationInfo();
            info.targetSdkVersion = mTarget;
            info.packageName = getPackageName();
            return info;
        }
    }

    private static <T> T take(LinkedBlockingQueue<T> queue, String what) throws InterruptedException {
        T value = queue.poll(10, TimeUnit.SECONDS);
        check(value != null, "timed out waiting for " + what);
        return value;
    }

    public static void main(String[] args) throws Exception {
        System.loadLibrary("android_runtime");
        if (args.length > 0 && args[0].equals("--no-radio")) {
            noRadio();
            return;
        }
        LinkedBlockingQueue<Intent> broadcasts = new LinkedBlockingQueue<>();
        BluetoothManagerService service = new BluetoothManagerService(broadcasts::add, null, null);
        service.setPollInterval(200);
        ServiceManager.initServiceCache(Map.of(BluetoothManagerService.NAME, service));

        permissions();
        App app = new App(34, "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN", "android.permission.ACCESS_FINE_LOCATION");
        BluetoothAdapter adapter = new BluetoothManager(app).getAdapter();
        adapterAndDevices(adapter);
        profiles(adapter, app);
        scan(adapter);
        gatt(adapter, app);
        rfcomm(adapter);
        broadcastsAndDiscovery(adapter, service, broadcasts);
        System.out.println("BluetoothHostTest all passed: " + checks);
        System.exit(0);
    }

    private static void noRadio() {
        WindowsBluetooth.Adapter a = WindowsBluetooth.adapter(0);
        step("Windows: " + a.describe());
        check(!a.present, "a CI runner has no Bluetooth radio");
        check(BluetoothAdapter.getDefaultAdapter() == null, "no default adapter without a radio");
        check(new BluetoothManager(new App(34)).getAdapter() == null, "no manager adapter without a radio");
        System.out.println("BluetoothHostTest no radio passed: " + checks);
    }

    private static void permissions() {
        App none = new App(34);
        BluetoothAdapter adapter = new BluetoothManager(none).getAdapter();
        check(adapter != null && adapter.isEnabled() && adapter.getState() == BluetoothAdapter.STATE_ON,
                "the test radio's adapter is on, without permissions as well");
        try {
            adapter.getName();
            check(false, "getName without BLUETOOTH_CONNECT");
        } catch (SecurityException expected) {
            check(expected.getMessage().contains("BLUETOOTH_CONNECT"), "the exception names BLUETOOTH_CONNECT");
        }
        try {
            adapter.getBluetoothLeScanner().startScan(new ScanCallback() {});
            check(false, "startScan without BLUETOOTH_SCAN");
        } catch (SecurityException expected) {
            check(expected.getMessage().contains("BLUETOOTH_SCAN"), "the exception names BLUETOOTH_SCAN");
        }
        // Apps targeting API 30 use the legacy BLUETOOTH permission.
        BluetoothAdapter legacy = new BluetoothManager(new App(30, "android.permission.BLUETOOTH")).getAdapter();
        check("Gesher Test Radio".equals(legacy.getName()), "API 30 app with BLUETOOTH reads the name");
        step("permissions: BLUETOOTH_CONNECT/BLUETOOTH_SCAN on API 31+, BLUETOOTH below");
    }

    private static void adapterAndDevices(BluetoothAdapter adapter) throws Exception {
        check("Gesher Test Radio".equals(adapter.getName()), "adapter name");
        check(BluetoothAdapter.DEFAULT_MAC_ADDRESS.equals(adapter.getAddress()), "the local address is hidden");
        check(adapter.getScanMode() == BluetoothAdapter.SCAN_MODE_CONNECTABLE, "connectable");
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        check(bonded.size() == 1, "one bonded device: " + bonded);
        BluetoothDevice headset = bonded.iterator().next();
        check(HEADSET.equals(headset.getAddress()), "the headset's address");
        check("Gesher Test Headset".equals(headset.getName()), "the headset's name");
        check(headset.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC, "classic device");
        check(headset.getBondState() == BluetoothDevice.BOND_BONDED, "bonded");
        BluetoothClass cls = headset.getBluetoothClass();
        check(cls != null && cls.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO
                && cls.hasService(BluetoothClass.Service.AUDIO), "audio/video class of device");
        ParcelUuid[] uuids = headset.getUuids();
        check(uuids != null && Arrays.asList(uuids).contains(new ParcelUuid(SPP))
                && Arrays.asList(uuids).contains(new ParcelUuid(VENDOR)), "SPP and vendor service classes");
        check((Boolean) BluetoothDevice.class.getMethod("isConnected").invoke(headset),
                "isConnected through reflection, as apps call it");
        check(adapter.getRemoteDevice(HEADSET).equals(headset), "getRemoteDevice equals the bonded device");
        try {
            adapter.getRemoteDevice("00:11:22:33:44:5g");
            check(false, "an invalid address is rejected");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        step("adapter: \"Gesher Test Radio\" on, bonded headset 00:02:5B:00:A5:A5 (classic, audio/video, SPP + vendor service)");
    }

    private static void profiles(BluetoothAdapter adapter, Context app) throws Exception {
        LinkedBlockingQueue<BluetoothProfile> proxies = new LinkedBlockingQueue<>();
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                proxies.add(proxy);
            }

            @Override
            public void onServiceDisconnected(int profile) {}
        };
        check(adapter.getProfileProxy(app, listener, BluetoothProfile.A2DP), "A2DP proxy");
        BluetoothProfile a2dp = take(proxies, "the A2DP proxy");
        check(a2dp instanceof BluetoothA2dp && a2dp.getConnectedDevices().size() == 1
                && a2dp.getConnectedDevices().get(0).getAddress().equals(HEADSET), "A2DP: the headset");
        check(adapter.getProfileProxy(app, listener, BluetoothProfile.HEADSET), "headset proxy");
        BluetoothProfile hfp = take(proxies, "the headset proxy");
        check(hfp.getConnectionState(adapter.getRemoteDevice(HEADSET)) == BluetoothProfile.STATE_CONNECTED,
                "hands-free: connected");
        check(adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED,
                "adapter's A2DP state");
        check(!adapter.getProfileProxy(app, listener, BluetoothProfile.HID_DEVICE), "no HID device profile");
        step("profiles: A2DP and headset proxies list the connected headset");
    }

    private static void scan(BluetoothAdapter adapter) throws Exception {
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        LinkedBlockingQueue<ScanResult> matched = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<ScanResult> other = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<Integer> failures = new LinkedBlockingQueue<>();
        ScanCallback battery = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult result) {
                if (type == ScanSettings.CALLBACK_TYPE_ALL_MATCHES) matched.add(result);
            }

            @Override
            public void onScanFailed(int error) {
                failures.add(error);
            }
        };
        ScanCallback heartRate = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult result) {
                other.add(result);
            }
        };
        ScanSettings fast = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(List.of(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(BATTERY)).build()),
                fast, battery);
        scanner.startScan(List.of(new ScanFilter.Builder().setServiceUuid(
                ParcelUuid.fromString("0000180d-0000-1000-8000-00805f9b34fb")).build()), fast, heartRate);
        ScanResult merged = null;
        long end = System.currentTimeMillis() + 5000;
        while (merged == null && System.currentTimeMillis() < end) {
            ScanResult r = matched.poll(1, TimeUnit.SECONDS);
            // The advertisement merged with the scan response that follows it.
            if (r != null && r.getScanRecord() != null && r.getScanRecord().getTxPowerLevel() == -4) merged = r;
        }
        check(merged != null, "a scan result with the advertisement and its scan response");
        check(PERIPHERAL.equals(merged.getDevice().getAddress())
                && merged.getDevice().getAddressType() == BluetoothDevice.ADDRESS_TYPE_RANDOM, "random address");
        check("Gesher BLE".equals(merged.getScanRecord().getDeviceName()), "local name");
        check(merged.getRssi() == -50 && merged.isConnectable() && merged.isLegacy(), "RSSI, connectable, legacy");
        byte[] maker = merged.getScanRecord().getManufacturerSpecificData(0x1234);
        check(maker != null && Arrays.equals(maker, new byte[] {1, 2}), "manufacturer data 0x1234");
        check(merged.getDevice().getType() == BluetoothDevice.DEVICE_TYPE_LE
                && "Gesher BLE".equals(merged.getDevice().getName()), "the advertiser is known as an LE device");
        scanner.startScan(List.of(), fast, battery);
        check(failures.poll(5, TimeUnit.SECONDS) == ScanCallback.SCAN_FAILED_ALREADY_STARTED,
                "starting the same callback twice fails");
        scanner.stopScan(battery);
        scanner.stopScan(heartRate);
        check(other.isEmpty(), "a filter for another service matched nothing");
        LinkedBlockingQueue<byte[]> legacy = new LinkedBlockingQueue<>();
        BluetoothAdapter.LeScanCallback le = (device, rssi, record) -> legacy.add(record);
        check(adapter.startLeScan(new UUID[] {BATTERY}, le), "legacy startLeScan");
        check(take(legacy, "a legacy scan record").length > 0, "legacy scan record bytes");
        adapter.stopLeScan(le);
        step("LE scan: filtered results with merged scan response (name, TX power, manufacturer data), legacy startLeScan");
    }

    private static void gatt(BluetoothAdapter adapter, Context app) throws Exception {
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                events.add("state " + status + " " + state);
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                events.add("discovered " + status);
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v, int status) {
                events.add("read " + status + " " + Arrays.toString(v));
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                events.add("write " + status);
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                events.add("descriptor " + status);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
                events.add("changed " + c.getUuid() + " " + new String(v));
            }

            @Override
            public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                events.add("mtu " + mtu + " " + status);
            }
        };
        BluetoothGatt unknown = adapter.getRemoteDevice("11:22:33:44:55:66").connectGatt(app, false, callback);
        check(take(events, "a failed connection").equals("state 133 0"), "an unknown device: status 133");
        unknown.close();

        BluetoothDevice device = adapter.getRemoteLeDevice(PERIPHERAL, BluetoothDevice.ADDRESS_TYPE_RANDOM);
        BluetoothGatt gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE);
        check(take(events, "connected").equals("state 0 2"), "connected");
        BluetoothManager manager = new BluetoothManager(app);
        check(manager.getConnectedDevices(BluetoothProfile.GATT).contains(device), "listed as a GATT connection");
        check(gatt.discoverServices(), "discoverServices");
        check(take(events, "services").equals("discovered 0"), "services discovered");
        List<BluetoothGattService> services = gatt.getServices();
        check(services.size() == 3, "three services: " + services.size());
        BluetoothGattCharacteristic level = gatt.getService(BATTERY).getCharacteristic(LEVEL);
        check(level != null && gatt.readCharacteristic(level), "read the battery level");
        check(take(events, "the read").equals("read 0 [87]"), "battery level 87");
        check(gatt.setCharacteristicNotification(level, true), "registered for notifications");
        BluetoothGattDescriptor cccd = level.getDescriptor(CCCD);
        check(cccd != null && gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                == android.bluetooth.BluetoothStatusCodes.SUCCESS, "subscribe through the CCCD");
        Set<String> got = new HashSet<>();
        got.add(take(events, "the CCCD write"));
        got.add(take(events, "the battery notification"));
        check(got.contains("descriptor 0") && got.contains("changed " + LEVEL + " " + new String(new byte[] {86})),
                "CCCD written, battery notification 86: " + got);
        BluetoothGattService uart = gatt.getService(UART);
        BluetoothGattCharacteristic tx = uart.getCharacteristic(TX);
        gatt.setCharacteristicNotification(tx, true);
        check(gatt.writeDescriptor(tx.getDescriptor(CCCD), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                == android.bluetooth.BluetoothStatusCodes.SUCCESS, "subscribe to TX");
        check(take(events, "the TX CCCD").equals("descriptor 0"), "TX CCCD written");
        BluetoothGattCharacteristic rx = uart.getCharacteristic(RX);
        check(gatt.writeCharacteristic(rx, "hello".getBytes(), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                == android.bluetooth.BluetoothStatusCodes.SUCCESS, "write RX");
        got.clear();
        got.add(take(events, "the RX write"));
        got.add(take(events, "the echo"));
        check(got.contains("write 0") && got.contains("changed " + TX + " hello"), "RX echoed on TX: " + got);
        check(gatt.requestMtu(517), "requestMtu");
        check(take(events, "the MTU").equals("mtu 247 0"), "MTU 247");
        gatt.disconnect();
        check(take(events, "disconnected").equals("state 0 0"), "disconnected");
        check(!manager.getConnectedDevices(BluetoothProfile.GATT).contains(device), "no longer connected");
        gatt.close();
        step("GATT: connect, discover 3 services, read 87, notifications through the CCCD, write/echo, MTU 247, disconnect");
    }

    private static void rfcomm(BluetoothAdapter adapter) throws Exception {
        BluetoothDevice headset = adapter.getRemoteDevice(HEADSET);
        BluetoothSocket socket = headset.createRfcommSocketToServiceRecord(VENDOR);
        check(!socket.isConnected(), "not connected before connect()");
        socket.connect();
        check(socket.isConnected() && socket.getConnectionType() == BluetoothSocket.TYPE_RFCOMM, "connected");
        byte[] packet = {0x08, (byte) 0xEE, 0x00, 0x00, 0x00, 0x01, 0x01, 0x0A, 0x00, 0x02};
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(packet);
        out.flush();
        long end = System.currentTimeMillis() + 5000;
        while (in.available() < packet.length && System.currentTimeMillis() < end) Thread.sleep(10);
        check(in.available() == packet.length, "available() counts the echo");
        byte[] back = new byte[packet.length];
        int n = 0;
        while (n < back.length) n += in.read(back, n, back.length - n);
        check(Arrays.equals(back, packet), "the vendor service echoes a packet");
        socket.close();
        try {
            in.read();
            check(false, "read after close");
        } catch (IOException expected) {
            checks++;
        }
        try {
            headset.createInsecureRfcommSocketToServiceRecord(UUID.randomUUID()).connect();
            check(false, "an absent service connects");
        } catch (IOException expected) {
            checks++;
        }
        BluetoothSocket channel = (BluetoothSocket) BluetoothDevice.class
                .getMethod("createRfcommSocket", int.class).invoke(headset, 1);
        channel.connect();
        check(channel.isConnected(), "createRfcommSocket(1) through reflection: SPP on channel 1");
        channel.close();

        UUID chat = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");
        BluetoothServerSocket server = adapter.listenUsingRfcommWithServiceRecord("Gesher chat", chat);
        BluetoothSocket client = adapter.getRemoteDevice("00:1A:7D:DA:71:13").createRfcommSocketToServiceRecord(chat);
        client.connect();
        BluetoothSocket accepted = server.accept(5000);
        client.getOutputStream().write(0x5A);
        check(accepted.getInputStream().read() == 0x5A, "the listener's connection reads what the client wrote");
        accepted.getOutputStream().write(0x33);
        check(client.getInputStream().read() == 0x33, "and the client reads the reply");
        try {
            server.accept(300);
            check(false, "accept times out");
        } catch (IOException expected) {
            checks++;
        }
        client.close();
        accepted.close();
        server.close();
        step("RFCOMM: vendor service echo, available(), refused service, createRfcommSocket(1), listener accept and exchange");
    }

    private static List<String> actions(LinkedBlockingQueue<Intent> broadcasts, int count) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Intent intent = broadcasts.poll(10, TimeUnit.SECONDS);
            if (intent == null) break;
            String text = intent.getAction();
            if (intent.hasExtra(BluetoothAdapter.EXTRA_STATE)) text += " " + intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (intent.hasExtra(BluetoothProfile.EXTRA_STATE)) text += " " + intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            if (intent.hasExtra(BluetoothDevice.EXTRA_NAME)) text += " " + intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
            out.add(text);
        }
        return out;
    }

    private static void broadcastsAndDiscovery(BluetoothAdapter adapter, BluetoothManagerService service,
            LinkedBlockingQueue<Intent> broadcasts) throws Exception {
        service.poll();  // the baseline: no broadcast
        broadcasts.clear();
        // An app targeting API 30 may still turn the radio off and on.
        BluetoothAdapter legacy = new BluetoothManager(new App(30, "android.permission.BLUETOOTH",
                "android.permission.BLUETOOTH_ADMIN")).getAdapter();
        check(!adapter.disable(), "API 34 apps cannot turn the radio off");
        check(legacy.disable(), "API 30 app turns the radio off");
        List<String> off = actions(broadcasts, 6);
        step("off: " + off);
        check(off.contains(BluetoothAdapter.ACTION_STATE_CHANGED + " " + BluetoothAdapter.STATE_TURNING_OFF)
                && off.contains(BluetoothAdapter.ACTION_STATE_CHANGED + " " + BluetoothAdapter.STATE_OFF)
                && off.contains(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                && off.contains(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED + " 0"),
                "TURNING_OFF, OFF, ACL_DISCONNECTED and A2DP disconnected");
        check(off.indexOf(BluetoothAdapter.ACTION_STATE_CHANGED + " " + BluetoothAdapter.STATE_TURNING_OFF)
                < off.indexOf(BluetoothAdapter.ACTION_STATE_CHANGED + " " + BluetoothAdapter.STATE_OFF), "in order");
        check(!adapter.isEnabled() && adapter.getBondedDevices().isEmpty()
                && adapter.getBluetoothLeScanner() == null, "off: disabled, no bonded devices, no scanner");
        check(legacy.enable(), "and on again");
        List<String> on = actions(broadcasts, 6);
        step("on: " + on);
        check(on.contains(BluetoothAdapter.ACTION_STATE_CHANGED + " " + BluetoothAdapter.STATE_TURNING_ON)
                && on.contains(BluetoothAdapter.ACTION_STATE_CHANGED + " " + BluetoothAdapter.STATE_ON)
                && on.contains(BluetoothDevice.ACTION_ACL_CONNECTED)
                && !on.contains(BluetoothDevice.ACTION_BOND_STATE_CHANGED), "TURNING_ON, ON, ACL_CONNECTED; the bond is kept");
        broadcasts.clear();
        check(adapter.startDiscovery(), "startDiscovery");
        List<String> discovery = actions(broadcasts, 4);
        step("discovery: " + discovery);
        check(discovery.equals(List.of(BluetoothAdapter.ACTION_DISCOVERY_STARTED,
                BluetoothDevice.ACTION_FOUND + " Gesher Test Headset",
                BluetoothDevice.ACTION_FOUND + " Gesher Test Speaker",
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED)), "discovery broadcasts");
        check(adapter.getRemoteDevice(HEADSET).fetchUuidsWithSdp(), "fetchUuidsWithSdp");
        Intent uuid = broadcasts.poll(10, TimeUnit.SECONDS);
        check(uuid != null && BluetoothDevice.ACTION_UUID.equals(uuid.getAction()), "ACTION_UUID");
        Parcelable[] list = uuid.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
        check(list != null && list.length == 4, "four service classes in ACTION_UUID");
        step("broadcasts: state, ACL and A2DP changes, discovery (FOUND x2), ACTION_UUID");
    }
}
