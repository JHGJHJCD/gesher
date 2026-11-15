# 0084: Bluetooth over the host radio (bluetooth track)

Status: accepted (see docs/bluetooth.md)

## Context

Android apps reach Bluetooth through the Bluetooth mainline module: BluetoothAdapter,
BluetoothDevice, BluetoothGatt, BluetoothLeScanner and BluetoothSocket talk to the
Bluetooth app process (com.android.bluetooth) over IBluetooth*, which drives the
controller through the HAL. framework.jar compiles against the module's API stubs and
contains none of it; Gesher had facades for a device without Bluetooth (no adapter) and
AOSP's BLE value classes (ScanRecord, ScanFilter, ScanSettings...).

Companion apps (Soundcore for Anker earbuds and speakers, fitness bands, scales) need
the adapter and its paired devices, LE scanning, a GATT client and, often, an RFCOMM
(SPP) channel to a device Windows already holds for audio. Windows offers all of it to
unpackaged desktop programs: Windows.Devices.Bluetooth (adapter, devices, SDP records),
.Advertisement (BluetoothLEAdvertisementWatcher), .GenericAttributeProfile (GattSession,
services, characteristics, descriptors, notifications), Windows.Devices.Radios (radio
on/off), BluetoothApis.dll (classic inquiry) and Winsock's AF_BTH (RFCOMM sockets with
SDP lookup and registration). OpenSCQ30 (an open-source Soundcore client) talks to the
same earbuds from Windows over RFCOMM while Windows keeps the A2DP link.

## Decision

1. **The module's public API is Gesher's own classes in gesher-app.jar** (app/java/
   android/bluetooth), not the module's framework sources and an IBluetooth service
   stack: the module's classes are written against hidden IBluetooth* AIDL, the
   Bluetooth app's state machines and the HAL, none of which would map to Windows. AOSP's
   value classes (BluetoothClass, the GATT service/characteristic/descriptor classes and
   callback, the BLE value classes) are copied unchanged from android-16.0.0_r4 where
   they need nothing of the stack.
2. **Scans, GATT and sockets run in the app's process**, straight on the natives
   (com.gesher.server.WindowsBluetooth), as cameras and sensors do (ADR 0043): Windows
   arbitrates the radio between processes, and the objects (watchers, sessions,
   sockets) belong to the process that uses them. Permissions are checked there with
   the app's context, as the module's manager-side checks do.
3. **What Android broadcasts as the system lives in the system server**
   (BluetoothManagerService, `gesher.bluetooth`, a plain Binder like `gesher`): the radio
   and paired-device watch with ACTION_STATE_CHANGED, ACL, bond and A2DP/headset
   broadcasts, classic discovery with ACTION_FOUND, ACTION_UUID, enable/disable. They are
   protected broadcasts an app process could not send; the watch starts with the first
   call, so nothing polls while no app uses Bluetooth.
4. **Windows' APIs are found at run time** (combase's WinRT functions through winrt.h,
   BluetoothApis.dll by LoadLibrary): their interfaces are declared in
   compat/devices/bluetooth_win.cc by their ABI, method order and interface ids taken
   from the Windows metadata (Windows.Devices.winmd, checked with reflection) and the
   parameterized delegate ids computed by WinRT's algorithm (cross-checked with
   llvm-mingw's widl headers). llvm-mingw's own WinRT headers were not used: they lack
   newer interfaces (IBluetoothLEDevice3/4, IGattSession's peers) the backend needs.
   Winsock (ws2_32) is linked, as elsewhere.
5. **Windows owns pairing and the CCCD.** Apps cannot pair (createBond answers false;
   the user pairs in Settings); writing a characteristic's Client Characteristic
   Configuration descriptor subscribes through Windows' API, and a notifying
   characteristic always has a CCCD object, as Android apps expect.
6. **CI verifies with a test radio below the JNI layer** (`GESHER_BLUETOOTH_TEST=1`,
   bluetooth_test.cc): a bonded headset with SPP and vendor RFCOMM echo services, an
   advertising LE peripheral with a GATT server, RFCOMM listeners reachable at the
   test adapter's address. The real Windows paths run on the runners too and answer
   "no adapter" cleanly (they have no radio); the backend alone is built with the
   pinned llvm-mingw and run on both runners by .github/workflows/bluetooth.yml.

## Consequences

- An app gets an adapter only when the PC has a radio; then paired devices, LE scanning,
  GATT client, RFCOMM client and server and the A2DP/headset proxies work through Windows.
- Not covered: LE advertising (fails with FEATURE_UNSUPPORTED), GATT server
  (openGattServer is null), L2CAP channels, pairing from the app, ACTION_REQUEST_ENABLE
  (no activity resolves it), LE Audio, hearing aids, HID, PAN, PBAP/MAP.
- Real hardware is not exercised in CI: the Windows paths' answers with a radio and a
  device (connection timing, Windows' GATT cache, access denied for protected services
  such as HID) are checked by hand only.
