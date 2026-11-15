# Bluetooth (bluetooth track)

Design: [0085](decisions/0085-bluetooth-host-radio.md). Tests:
`tests/windows/tracks/61-bluetooth.ps1` with `gesher.services.BluetoothHostTest`
(services-tests.dex), and `.github/workflows/bluetooth.yml` with
`tests/bluetooth/bluetooth_probe.cc` (the backend alone).

## Pieces

| Piece | Where |
|---|---|
| Backend interface | `compat/devices/bluetooth.h` (adapter, paired devices, inquiry, SDP service classes, LE scan, GATT client, RFCOMM client and listener) |
| Windows backend | `compat/devices/bluetooth_win.cc`: Windows.Devices.Bluetooth, .Advertisement, .GenericAttributeProfile, Windows.Devices.Radios and Windows.Devices.Enumeration through their ABI (winrt.h); BluetoothApis.dll (inquiry, loaded at run time); Winsock AF_BTH (RFCOMM, WSASetService) |
| Test radio | `compat/devices/bluetooth_test.cc`, chosen by `GESHER_BLUETOOTH_TEST=1` |
| JNI | `compat/devices/bluetooth_jni.cc` (`com.gesher.server.WindowsBluetooth`, registered by `register_gesher_devices`) |
| Java bridge | `app/java/com/gesher/server/WindowsBluetooth.java` |
| System side | `app/java/com/gesher/server/BluetoothManagerService.java` (`gesher.bluetooth`, in `Services.create`) |
| Module API | `app/java/android/bluetooth/` (see its README) |

## What apps get

- **System features**: `FEATURE_BLUETOOTH` when Windows has an adapter (looked at when
  the system server starts), `FEATURE_BLUETOOTH_LE` when it also reports LE support; both
  absent without a radio (the CI runners). `hasSystemFeature`, `getSystemAvailableFeatures`
  and the features cache in shared memory agree.
- **Adapter**: present when Windows has a radio (WinRT `BluetoothAdapter.GetDefaultAsync`).
  `getState()`/`isEnabled()` follow Windows' radio (Settings, Bluetooth on/off).
  `getName()` is the computer name; `getAddress()` is `02:00:00:00:00:00` as on Android.
  `enable()`/`disable()` ask Windows (`Radio.SetStateAsync`, after `RequestAccessAsync`);
  apps targeting API 33+ get false, as on Android. Without a Radio object (Windows.Devices.
  Radios unavailable) the state comes from BluetoothApis (a local radio is listed only
  while it is on).
- **Permissions**: BLUETOOTH_CONNECT / BLUETOOTH_SCAN for apps targeting API 31+,
  BLUETOOTH / BLUETOOTH_ADMIN below, else SecurityException. Scan results need
  ACCESS_FINE_LOCATION (API 29+, either location permission below) unless BLUETOOTH_SCAN
  is declared `neverForLocation`; without it the scan runs silently, as on Android.
  `gesher.bluetooth` checks its Binder callers itself, by package and target SDK, as the
  module's service does: switching the radio needs BLUETOOTH_CONNECT (API 31+) or
  BLUETOOTH_ADMIN and is refused to apps targeting API 33+; since it is the PC's radio the
  user also confirms it (the runtime-permission consent dialog). Discovery needs
  BLUETOOTH_SCAN / BLUETOOTH_ADMIN, SDP refresh BLUETOOTH_CONNECT / BLUETOOTH.
- **Paired devices**: Windows' pairings, classic and LE (`DeviceInformation.FindAllAsync`
  with the paired selectors), with name, class of device, connection state and the SDP
  service classes of their records (`BluetoothDevice.SdpRecords`, parsed for
  ServiceClassIDList). Empty while the radio is off. `fetchUuidsWithSdp()` asks the device
  again (an uncached RFCOMM service query) and broadcasts ACTION_UUID.
- **Discovery**: `startDiscovery()` runs a classic inquiry (BluetoothFindFirstDevice,
  about 10 s) in the system server: ACTION_DISCOVERY_STARTED, ACTION_FOUND (device, name,
  class) and ACTION_DISCOVERY_FINISHED. LE devices are found by LE scans.
- **LE scan**: one active BluetoothLEAdvertisementWatcher per process for all scans.
  Reports are rebuilt as AD structures from the advertisement's data sections and parsed
  by AOSP's ScanRecord; a scan response is merged with the advertisement it follows.
  Filters, ALL_MATCHES / FIRST_MATCH / MATCH_LOST (10 s), report delays (batches),
  PendingIntent scans and the legacy `startLeScan` work. Callbacks on the main thread.
- **GATT client**: `connectGatt` opens the device (`FromBluetoothAddressAsync`, with the
  address type) and a GattSession with MaintainConnection; Windows connects. Discovery
  reads services, characteristics and descriptors uncached, and included services
  (`GetIncludedServicesAsync`): those that are not primary services are listed as
  SERVICE_TYPE_SECONDARY; reads and writes (with and
  without response) map to Windows' calls with their ATT error as the status (an
  unreachable device is 133, as on Android). One request at a time, as the module.
  Writing a CCCD subscribes through `WriteClientCharacteristicConfigurationDescriptor`;
  notifications and indications arrive as `onCharacteristicChanged`. `requestMtu` reports
  Windows' negotiated MaxPduSize (never above the request). `autoConnect` retries until
  the device is there. `readRemoteRssi` answers the last advertisement's RSSI. The hidden
  `readUsingCharacteristicUuid` reads the first discovered characteristic of the UUID in
  the handle range (false when there is none). `readPhy` reports the PHY Windows 11
  gives (`GetConnectionPhy`), else 1M with GATT_REQUEST_NOT_SUPPORTED; `setPreferredPhy`
  is declined (GATT_REQUEST_NOT_SUPPORTED, the PHY unchanged): Windows chooses it. Several
  clients of the process may connect to one device; it stays connected until the last
  one closes.
- **RFCOMM**: `createRfcommSocketToServiceRecord` / `createInsecure...` connect AF_BTH
  sockets by service UUID (Windows looks it up in SDP); the hidden `createRfcommSocket(int)`
  that SPP apps call by reflection connects to a channel. Streams, `available()`, and an
  IOException at the end of the stream, as on Android. `connect()` is a non-blocking
  connect and a wait, so `close()` from another thread cancels it. Secure sockets require
  authentication and encryption (SO_BTH_AUTHENTICATE, SO_BTH_ENCRYPT); the hidden
  `isEncrypted()` is true only while such a socket of the process is connected to the
  device, since Windows reports no encryption of other links (it answers false then). `listenUsingRfcommWithServiceRecord`
  binds a channel and registers an SDP record (WSASetService).
- **Profiles**: `getProfileProxy` gives BluetoothA2dp and BluetoothHeadset proxies listing
  the paired devices Windows has connected that offer an A2DP sink / hands-free service.
- **Broadcasts** (BluetoothManagerService, from the first app call on, polling every 2 s):
  ACTION_STATE_CHANGED (TURNING_ON/ON, TURNING_OFF/OFF), ACL_CONNECTED/DISCONNECTED,
  BOND_STATE_CHANGED (pairings made or removed while the radio is on), the A2DP and
  headset profiles' CONNECTION_STATE_CHANGED and the adapter's.

## Not there

- LE advertising (BluetoothLeAdvertiser starts fail with FEATURE_UNSUPPORTED; Windows'
  BluetoothLEAdvertisementPublisher could back it), GATT server (`openGattServer` is null;
  GattServiceProvider could back it), L2CAP channels, LE Audio, HID, PAN, PBAP/MAP.
- Pairing from the app: `createBond()` answers false; users pair in Windows' Settings.
  ACTION_REQUEST_ENABLE / REQUEST_DISCOVERABLE have no activity.
- Reliable writes, PHY selection (declined), connection priority (accepted only).
- In device mode each app process has its own scan watcher and GATT sessions; Windows
  shares the radio between them.

## The Soundcore app (com.oceanwing.soundcore)

Anker's earbuds talk to the app over RFCOMM (SPP or a vendor UUID
`0cf12d31-fac3-4553-bd80-d6832e7xxxxx`, OpenSCQ30's notes), on the classic link Windows
already holds for A2DP; some devices use BLE GATT. With this track the app finds the
paired earbuds (getBondedDevices, the A2DP proxy, getUuids), and can open the RFCOMM
channel and exchange packets. Still needed for the app itself: it currently dies before
its UI in its packer's native code (`libexec_x86.so`, another track's work).

## Status

- Verified on CI (both Windows runners): the backend probe (all 27 checks of the test
  radio; Windows' real calls answer "no adapter", HRESULT 0x8007048F, on runners without
  a radio); see the bluetooth track's runs.
- Built and run by ci.yml's tests: BluetoothHostTest (61-bluetooth.ps1).
- Not verified: a real radio and devices (connection timing, Windows' GATT cache, access
  to protected services, RFCOMM to real earbuds). `bluetooth_probe.exe` (bluetooth.yml's
  artifact) prints what a PC with a radio answers: adapter, paired devices, a 4 s scan,
  and with `--inquiry` an inquiry; it never connects to a device.
