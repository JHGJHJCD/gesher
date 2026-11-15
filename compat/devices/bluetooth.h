// Gesher (devices track, docs/bluetooth.md): the host's Bluetooth radio for the
// Android Bluetooth API (com.gesher.server.WindowsBluetooth). Two backends sit
// behind one interface:
// - Windows (bluetooth_win.cc): Windows.Devices.Bluetooth and its Advertisement,
//   GenericAttributeProfile and Radios namespaces through their ABI (combase's
//   WinRT functions looked up at run time, winrt.h), BluetoothApis.dll for
//   classic inquiry (loaded at run time), Winsock's AF_BTH for RFCOMM.
// - GESHER_BLUETOOTH_TEST=1 (bluetooth_test.cc): a test radio with a bonded
//   headset, an advertising LE peripheral with a GATT server and RFCOMM echo
//   services, below the JNI layer, so CI runners without a radio exercise
//   everything above it.
// Nothing here throws; failures are HRESULTs or Android GATT status codes.
#pragma once

#include <windows.h>
#include <stdint.h>

#include <atomic>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace gesher::devices::bluetooth {

// The local radio. Absent: no radio (or no WinRT), `hr` says why.
struct Adapter {
  bool present = false;
  bool on = false;
  uint64_t address = 0;  // 48 bits, Windows' order (most significant byte first in text)
  std::string name;      // UTF-8
  bool classic = false, le = false, central = false, peripheral = false;
  bool extendedAdvertising = false, le2mPhy = false, leCodedPhy = false;
  int maxAdvertisementDataLength = 31;
  std::string via;  // "Windows.Devices.Bluetooth", "test radio"
  HRESULT hr = S_OK;
};

// Bit flags of Device::kind.
constexpr int kClassic = 1;
constexpr int kLowEnergy = 2;

struct Device {
  uint64_t address = 0;
  int addressType = 0;  // 0 public, 1 random (Android's ADDRESS_TYPE_*)
  std::string name;     // UTF-8, may be empty
  uint32_t classOfDevice = 0;
  int kind = 0;  // kClassic | kLowEnergy
  bool bonded = false;
  bool connected = false;
  std::vector<GUID> uuids;  // SDP service classes (classic) when known
};

// One advertising report (or scan response) of an LE scan.
struct Advertisement {
  uint64_t address = 0;
  int addressType = 0;
  int rssi = -127;
  int txPower = 127;  // 127: not present (ScanResult.TX_POWER_NOT_PRESENT)
  bool connectable = false;
  bool scanResponse = false;
  bool legacy = true;
  std::vector<uint8_t> data;  // the AD structures: length, type, payload...
};

// GATT attribute kinds of a discovery, in order (services, then their
// characteristics, each followed by its descriptors).
enum AttributeKind : int { kService = 0, kCharacteristic = 1, kDescriptor = 2, kIncluded = 3 };

struct Attribute {
  int kind = kService;
  GUID uuid{};
  uint16_t handle = 0;
  uint32_t properties = 0;  // characteristics: Android's PROPERTY_* bits (Windows' are the same)
};

// Asynchronous GATT events.
enum EventType : int {
  kNotification = 1,  // handle, data: a characteristic's value
  kConnection = 2,    // value: 1 connected, 0 disconnected
  kMtu = 3,           // value: the new ATT MTU
  kServicesChanged = 4,
  kClosed = 5,        // Close() was called: no more events
};

struct GattEvent {
  int type = 0;
  uint16_t handle = 0;
  int value = 0;
  std::vector<uint8_t> data;
};

// Android's BluetoothGatt status codes used here.
constexpr int kGattSuccess = 0;
constexpr int kGattReadNotPermitted = 0x2;
constexpr int kGattWriteNotPermitted = 0x3;
constexpr int kGattRequestNotSupported = 0x6;
constexpr int kGattInsufficientAuthorization = 0x8;
constexpr int kGattError = 0x85;     // 133: what Android reports for a lost/unreachable link
constexpr int kGattFailure = 0x101;  // 257

class Scan {
 public:
  virtual ~Scan() = default;
  // The next report within `timeoutMs`: true with *out, false on time-out or
  // when the scan has ended (then Error() is set).
  virtual bool Next(Advertisement* out, int timeoutMs) = 0;
  // Why Windows stopped the scan (0 while it runs): an HRESULT.
  virtual HRESULT Error() = 0;
  virtual void Stop() = 0;
};

class Gatt {
 public:
  virtual ~Gatt() = default;
  // Opens a session that keeps the link up and waits for it: S_OK when connected,
  // *mtu the ATT MTU.
  virtual HRESULT Connect(int timeoutMs, int* mtu) = 0;
  // Services, characteristics and descriptors from the device (not Windows' cache).
  virtual int Discover(std::vector<Attribute>* out) = 0;
  // Android GATT status; kind is kCharacteristic or kDescriptor.
  virtual int Read(int kind, uint16_t handle, std::vector<uint8_t>* value) = 0;
  virtual int Write(int kind, uint16_t handle, const std::vector<uint8_t>& value,
                    bool withResponse) = 0;
  // A characteristic's client configuration: 0 none, 1 notify, 2 indicate.
  virtual int SetNotify(uint16_t handle, int mode) = 0;
  virtual int Mtu() = 0;
  virtual bool NextEvent(GattEvent* out, int timeoutMs) = 0;
  // Releases the link; NextEvent then answers kClosed.
  virtual void Close() = 0;
};

class Socket {
 public:
  virtual ~Socket() = default;
  // Bytes read (> 0), 0 at the end of the stream, or a negative Winsock error.
  virtual int Read(uint8_t* buffer, int length) = 0;
  // Bytes written, or a negative Winsock error.
  virtual int Write(const uint8_t* buffer, int length) = 0;
  virtual int Channel() = 0;
  virtual uint64_t Remote() = 0;
  virtual void Close() = 0;
};

class Server {
 public:
  virtual ~Server() = default;
  // A connection within timeoutMs (< 0: no limit); null with *hr on time-out
  // (HRESULT_FROM_WIN32(WSAETIMEDOUT)), close or failure.
  virtual std::shared_ptr<Socket> Accept(int timeoutMs, HRESULT* hr) = 0;
  virtual int Channel() = 0;
  virtual void Close() = 0;
};

class Backend {
 public:
  virtual ~Backend() = default;
  virtual Adapter GetAdapter() = 0;
  // Turns the radio on or off (Windows.Devices.Radios); E_ACCESSDENIED when not allowed.
  virtual HRESULT SetEnabled(bool on) = 0;
  // Paired devices (classic and LE), with connection state.
  virtual HRESULT Bonded(std::vector<Device>* out) = 0;
  // Classic inquiry for about `seconds`; `found` is called for each device;
  // returns when it ends or *cancel becomes true.
  virtual HRESULT Inquiry(int seconds, const std::atomic<bool>* cancel,
                          const std::function<void(const Device&)>& found) = 0;
  // A device's SDP service classes; `refresh` asks the device again.
  virtual HRESULT RemoteUuids(uint64_t address, bool refresh, std::vector<GUID>* out) = 0;
  virtual std::shared_ptr<Scan> StartScan(bool active, HRESULT* hr) = 0;
  virtual std::shared_ptr<Gatt> OpenGatt(uint64_t address, int addressType, HRESULT* hr) = 0;
  // RFCOMM to `uuid` (SDP lookup) or, when channel > 0, that channel.
  virtual std::shared_ptr<Socket> ConnectRfcomm(uint64_t address, const GUID& uuid, int channel,
                                                bool secure, HRESULT* hr) = 0;
  // An RFCOMM listener registered in SDP as `name` with `uuid`.
  virtual std::shared_ptr<Server> ListenRfcomm(const std::wstring& name, const GUID& uuid,
                                               bool secure, HRESULT* hr) = 0;
};

// The test radio when GESHER_BLUETOOTH_TEST=1, else Windows'.
Backend& Get();
Backend& WindowsBackend();  // bluetooth_win.cc
Backend& TestBackend();     // bluetooth_test.cc

// Helpers shared by the backends.
std::string Utf8(const std::wstring& s);
std::wstring Wide(const std::string& s);
// "0000110b-0000-1000-8000-00805f9b34fb" and back.
std::string GuidText(const GUID& g);
bool ParseGuid(const std::string& text, GUID* out);
// The Bluetooth base UUID with a 16-bit short id.
GUID ShortUuid(uint16_t id);

}  // namespace gesher::devices::bluetooth
