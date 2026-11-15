// Gesher (devices track, docs/bluetooth.md): GESHER_BLUETOOTH_TEST=1's radio,
// below the JNI layer, for machines without one (the CI runners). It has:
// - an adapter "Gesher Test Radio" 00:1A:7D:DA:71:13 (on; SetEnabled turns it
//   off and on);
// - a bonded, connected classic headset "Gesher Test Headset"
//   00:02:5B:00:A5:A5 (SPP, A2DP sink, hands-free and a Soundcore-style vendor
//   RFCOMM service), and an unbonded "Gesher Test Speaker" an inquiry finds;
// - an advertising LE peripheral "Gesher BLE" C0:FF:EE:00:00:01 (random) with a
//   GATT server: device name, battery level (read, notify) and a Nordic UART
//   style echo service (writes to RX come back as TX notifications);
// - RFCOMM: the headset's SPP and vendor services echo what they are sent;
//   listeners registered with ListenRfcomm accept connections made to the test
//   radio's own address.

#include "bluetooth.h"

#include <string.h>

#include <condition_variable>
#include <deque>
#include <map>
#include <mutex>
#include <thread>

#include "bluetooth_queue.h"

namespace gesher::devices::bluetooth {

namespace {

constexpr uint64_t kAdapterAddress = 0x001A7DDA7113ULL;
constexpr uint64_t kHeadsetAddress = 0x00025B00A5A5ULL;
constexpr uint64_t kSpeakerAddress = 0x00025B00B0B0ULL;
constexpr uint64_t kPeripheralAddress = 0xC0FFEE000001ULL;
constexpr int kTestMtu = 247;
constexpr int kWsaConnRefused = 10061;  // WSAECONNREFUSED
constexpr int kWsaHostUnreach = 10065;  // WSAEHOSTUNREACH
constexpr int kWsaNotConn = 10057;      // WSAENOTCONN

// 0cf12d31-fac3-4553-bd80-d6832e70f1a5: inside Soundcore's vendor RFCOMM UUID range.
const GUID kVendorUuid = {0x0cf12d31, 0xfac3, 0x4553,
                          {0xbd, 0x80, 0xd6, 0x83, 0x2e, 0x70, 0xf1, 0xa5}};
// The Nordic UART service and its RX (write) and TX (notify) characteristics.
const GUID kUartService = {0x6e400001, 0xb5a3, 0xf393,
                           {0xe0, 0xa9, 0xe5, 0x0e, 0x24, 0xdc, 0xca, 0x9e}};
const GUID kUartRx = {0x6e400002, 0xb5a3, 0xf393,
                      {0xe0, 0xa9, 0xe5, 0x0e, 0x24, 0xdc, 0xca, 0x9e}};
const GUID kUartTx = {0x6e400003, 0xb5a3, 0xf393,
                      {0xe0, 0xa9, 0xe5, 0x0e, 0x24, 0xdc, 0xca, 0x9e}};
// A secondary service the UART service includes, with one readable characteristic.
const GUID kIncludedService = {0x6e400010, 0xb5a3, 0xf393,
                               {0xe0, 0xa9, 0xe5, 0x0e, 0x24, 0xdc, 0xca, 0x9e}};
const GUID kIncludedValue = {0x6e400011, 0xb5a3, 0xf393,
                             {0xe0, 0xa9, 0xe5, 0x0e, 0x24, 0xdc, 0xca, 0x9e}};

std::atomic<bool> gOn{true};

Device Headset() {
  Device d;
  d.address = kHeadsetAddress;
  d.name = "Gesher Test Headset";
  d.classOfDevice = 0x240404;  // audio/video, wearable headset; audio and rendering services
  d.kind = kClassic;
  d.bonded = true;
  d.connected = true;
  d.uuids = {ShortUuid(0x1101), ShortUuid(0x110B), ShortUuid(0x111E), kVendorUuid};
  return d;
}

Device Speaker() {
  Device d;
  d.address = kSpeakerAddress;
  d.name = "Gesher Test Speaker";
  d.classOfDevice = 0x240414;  // audio/video, loudspeaker
  d.kind = kClassic;
  return d;
}

void AddStructure(std::vector<uint8_t>* data, uint8_t type, const std::vector<uint8_t>& payload) {
  data->push_back(static_cast<uint8_t>(payload.size() + 1));
  data->push_back(type);
  data->insert(data->end(), payload.begin(), payload.end());
}

// --- the LE scan ----------------------------------------------------------------------------

class TestScan final : public Scan {
 public:
  explicit TestScan(bool active) : active_(active) {
    thread_ = std::thread([this] { Run(); });
  }
  ~TestScan() override {
    Stop();
    if (thread_.joinable()) thread_.join();
  }

  bool Next(Advertisement* out, int timeoutMs) override { return queue_.Pop(out, timeoutMs); }
  HRESULT Error() override { return error_.load(); }
  void Stop() override {
    stop_.store(true);
    queue_.Close();
  }

 private:
  void Run() {
    while (!stop_.load()) {
      if (!gOn.load()) {
        // The radio was turned off under the scan: BluetoothError.RadioNotAvailable.
        error_.store(MAKE_HRESULT(SEVERITY_ERROR, FACILITY_ITF, 0x200 + 1));
        queue_.Close();
        return;
      }
      Advertisement a;
      a.address = kPeripheralAddress;
      a.addressType = 1;
      a.rssi = -50;
      a.connectable = true;
      AddStructure(&a.data, 0x01, {0x06});                          // flags
      AddStructure(&a.data, 0x03, {0x0F, 0x18});                    // 16-bit UUIDs: battery
      AddStructure(&a.data, 0x09, {'G', 'e', 's', 'h', 'e', 'r', ' ', 'B', 'L', 'E'});
      AddStructure(&a.data, 0xFF, {0x34, 0x12, 0x01, 0x02});        // manufacturer 0x1234
      queue_.Push(a);
      if (active_) {
        Advertisement r;
        r.address = kPeripheralAddress;
        r.addressType = 1;
        r.rssi = -50;
        r.connectable = true;
        r.scanResponse = true;
        AddStructure(&r.data, 0x0A, {0xFC});  // TX power level: -4 dBm
        queue_.Push(r);
      }
      for (int i = 0; i < 10 && !stop_.load(); ++i) Sleep(10);
    }
  }

  const bool active_;
  Queue<Advertisement> queue_{256};
  std::atomic<bool> stop_{false};
  std::atomic<HRESULT> error_{S_OK};
  std::thread thread_;
};

// --- the GATT server of the LE peripheral ------------------------------------------------------

class TestGatt final : public Gatt {
 public:
  HRESULT Connect(int, int* mtu) override {
    if (!gOn.load()) return HRESULT_FROM_WIN32(ERROR_DEVICE_NOT_CONNECTED);
    connected_ = true;
    *mtu = kTestMtu;
    return S_OK;
  }

  int Discover(std::vector<Attribute>* out) override {
    if (!connected_) return kGattError;
    auto add = [out](int kind, const GUID& uuid, uint16_t handle, uint32_t properties) {
      Attribute a;
      a.kind = kind;
      a.uuid = uuid;
      a.handle = handle;
      a.properties = properties;
      out->push_back(a);
    };
    add(kService, ShortUuid(0x1800), 1, 0);
    add(kCharacteristic, ShortUuid(0x2A00), 3, 0x02);
    add(kService, ShortUuid(0x180F), 10, 0);
    add(kCharacteristic, ShortUuid(0x2A19), 12, 0x12);
    add(kDescriptor, ShortUuid(0x2902), 13, 0);
    add(kService, kUartService, 20, 0);
    add(kCharacteristic, kUartRx, 22, 0x0C);
    add(kCharacteristic, kUartTx, 24, 0x10);
    add(kDescriptor, ShortUuid(0x2902), 25, 0);
    add(kIncluded, kIncludedService, 30, 20);  // included by the UART service (handle 20)
    add(kService, kIncludedService, 30, 1);    // secondary
    add(kCharacteristic, kIncludedValue, 32, 0x02);
    return kGattSuccess;
  }

  int Read(int kind, uint16_t handle, std::vector<uint8_t>* value) override {
    std::lock_guard<std::mutex> lock(mu_);
    if (!connected_) return kGattError;
    if (kind == kCharacteristic && handle == 3) {
      const char* name = "Gesher BLE";
      value->assign(name, name + strlen(name));
      return kGattSuccess;
    }
    if (kind == kCharacteristic && handle == 12) {
      value->assign(1, battery_);
      return kGattSuccess;
    }
    if (kind == kCharacteristic && handle == 32) {
      value->assign({'i', 'n', 'c'});
      return kGattSuccess;
    }
    if (kind == kDescriptor && (handle == 13 || handle == 25)) {
      int mode = notify_[handle - 1];
      value->assign({static_cast<uint8_t>(mode == 1 ? 1 : mode == 2 ? 2 : 0), 0});
      return kGattSuccess;
    }
    return kGattReadNotPermitted;
  }

  int Write(int kind, uint16_t handle, const std::vector<uint8_t>& value, bool) override {
    if (!connected_) return kGattError;
    if (kind == kCharacteristic && handle == 22) {
      bool echo;
      {
        std::lock_guard<std::mutex> lock(mu_);
        echo = notify_[24] != 0;
      }
      if (echo) {
        GattEvent e;
        e.type = kNotification;
        e.handle = 24;
        e.data = value;
        events_.Push(std::move(e));
      }
      return kGattSuccess;
    }
    if (kind == kDescriptor && (handle == 13 || handle == 25) && value.size() == 2) {
      return SetNotify(static_cast<uint16_t>(handle - 1), value[0] & 3);
    }
    return kGattWriteNotPermitted;
  }

  int SetNotify(uint16_t handle, int mode) override {
    if (!connected_) return kGattError;
    if (handle != 12 && handle != 24) return kGattRequestNotSupported;
    bool battery = false;
    {
      std::lock_guard<std::mutex> lock(mu_);
      notify_[handle] = mode;
      if (handle == 12 && mode != 0) {
        battery_ = static_cast<uint8_t>(battery_ - 1);
        battery = true;
      }
    }
    if (battery) {
      // The battery drained a little: the subscription's first notification.
      GattEvent e;
      e.type = kNotification;
      e.handle = 12;
      std::lock_guard<std::mutex> lock(mu_);
      e.data.assign(1, battery_);
      events_.Push(std::move(e));
    }
    return kGattSuccess;
  }

  int Mtu() override { return kTestMtu; }

  // The test link runs on LE 2M both ways.
  int Phy(int* tx, int* rx) override {
    if (!connected_) return kGattError;
    *tx = 2;
    *rx = 2;
    return kGattSuccess;
  }

  bool NextEvent(GattEvent* out, int timeoutMs) override {
    if (events_.Pop(out, timeoutMs)) return true;
    if (events_.closed()) {
      out->type = kClosed;
      return true;
    }
    return false;
  }

  void Close() override {
    connected_ = false;
    events_.Close();
  }

 private:
  std::mutex mu_;
  std::atomic<bool> connected_{false};
  uint8_t battery_ = 87;
  std::map<int, int> notify_;
  Queue<GattEvent> events_{1024};
};

// --- RFCOMM ---------------------------------------------------------------------------------

// One direction of a connection.
struct Pipe {
  std::mutex mu;
  std::condition_variable cv;
  std::deque<uint8_t> bytes;
  bool closed = false;

  void Write(const uint8_t* data, int length) {
    {
      std::lock_guard<std::mutex> lock(mu);
      bytes.insert(bytes.end(), data, data + length);
    }
    cv.notify_all();
  }
  int Read(uint8_t* data, int length) {
    std::unique_lock<std::mutex> lock(mu);
    cv.wait(lock, [this] { return !bytes.empty() || closed; });
    int n = 0;
    while (n < length && !bytes.empty()) {
      data[n++] = bytes.front();
      bytes.pop_front();
    }
    return n;  // 0: closed
  }
  void Close() {
    {
      std::lock_guard<std::mutex> lock(mu);
      closed = true;
    }
    cv.notify_all();
  }
};

class TestSocket final : public Socket {
 public:
  TestSocket(std::shared_ptr<Pipe> in, std::shared_ptr<Pipe> out, uint64_t remote, int channel)
      : in_(std::move(in)), out_(std::move(out)), remote_(remote), channel_(channel) {}
  ~TestSocket() override { Close(); }

  HRESULT Connect() override { return S_OK; }

  int Read(uint8_t* buffer, int length) override {
    if (closed_.load()) return 0;
    return in_->Read(buffer, length);
  }
  int Write(const uint8_t* buffer, int length) override {
    if (closed_.load()) return -kWsaNotConn;
    {
      std::lock_guard<std::mutex> lock(out_->mu);
      if (out_->closed) return -kWsaNotConn;
    }
    out_->Write(buffer, length);
    return length;
  }
  int Available() override {
    if (closed_.load()) return 0;
    std::lock_guard<std::mutex> lock(in_->mu);
    return static_cast<int>(in_->bytes.size());
  }
  int Channel() override { return channel_; }
  uint64_t Remote() override { return remote_; }
  void Close() override {
    if (closed_.exchange(true)) return;
    in_->Close();
    out_->Close();
  }

 private:
  std::shared_ptr<Pipe> in_, out_;
  uint64_t remote_;
  int channel_;
  std::atomic<bool> closed_{false};
};

class TestServer;
std::mutex gServersMu;
std::map<std::string, TestServer*> gServers;  // by UUID text
int gNextChannel = 10;

class TestServer final : public Server {
 public:
  TestServer(GUID uuid, int channel) : uuid_(uuid), channel_(channel) {}
  ~TestServer() override { Close(); }

  std::shared_ptr<Socket> Accept(int timeoutMs, HRESULT* hr) override {
    std::shared_ptr<Socket> s;
    if (pending_.Pop(&s, timeoutMs)) {
      *hr = S_OK;
      return s;
    }
    *hr = pending_.closed() ? E_ABORT : HRESULT_FROM_WIN32(10060 /* WSAETIMEDOUT */);
    return nullptr;
  }
  int Channel() override { return channel_; }
  void Close() override {
    {
      std::lock_guard<std::mutex> lock(gServersMu);
      auto it = gServers.find(GuidText(uuid_));
      if (it != gServers.end() && it->second == this) gServers.erase(it);
    }
    pending_.Close();
  }
  void Offer(std::shared_ptr<Socket> s) { pending_.Push(std::move(s)); }

 private:
  GUID uuid_;
  int channel_;
  Queue<std::shared_ptr<Socket>> pending_{16};
};

// A socket of OpenRfcomm: Connect() reaches the headset's echo services or a listener of
// the test radio's own address; the headset's channel 3 never answers, so that a
// Close() from another thread has a connect to end (E_ABORT).
std::shared_ptr<Socket> ConnectTest(uint64_t address, const GUID& uuid, int channel,
                                  HRESULT* hr) {
  if (!gOn.load()) {
    *hr = HRESULT_FROM_WIN32(kWsaHostUnreach);
    return nullptr;
  }
  if (address == kHeadsetAddress) {
    int service = 0;
    if (channel == 1 || (channel <= 0 && uuid == ShortUuid(0x1101))) service = 1;
    if (channel == 2 || (channel <= 0 && uuid == kVendorUuid)) service = 2;
    if (service == 0) {
      *hr = HRESULT_FROM_WIN32(kWsaConnRefused);
      return nullptr;
    }
    auto echo = std::make_shared<Pipe>();
    *hr = S_OK;
    return std::make_shared<TestSocket>(echo, echo, address, service);
  }
  if (address == kAdapterAddress) {
    std::lock_guard<std::mutex> lock(gServersMu);
    auto it = gServers.find(GuidText(uuid));
    if (it == gServers.end()) {
      *hr = HRESULT_FROM_WIN32(kWsaConnRefused);
      return nullptr;
    }
    auto there = std::make_shared<Pipe>();
    auto back = std::make_shared<Pipe>();
    int serverChannel = it->second->Channel();
    it->second->Offer(std::make_shared<TestSocket>(there, back, kAdapterAddress, serverChannel));
    *hr = S_OK;
    return std::make_shared<TestSocket>(back, there, kAdapterAddress, serverChannel);
  }
  *hr = HRESULT_FROM_WIN32(kWsaHostUnreach);
  return nullptr;
}

class PendingTestSocket final : public Socket {
 public:
  PendingTestSocket(uint64_t address, GUID uuid, int channel)
      : address_(address), uuid_(uuid), channel_(channel) {}
  ~PendingTestSocket() override { Close(); }

  HRESULT Connect() override {
    if (address_ == kHeadsetAddress && channel_ == 3) {
      std::unique_lock<std::mutex> lock(mu_);
      cv_.wait(lock, [this] { return closed_; });
      return E_ABORT;
    }
    HRESULT hr = S_OK;
    std::shared_ptr<Socket> s = ConnectTest(address_, uuid_, channel_, &hr);
    std::lock_guard<std::mutex> lock(mu_);
    if (closed_) {
      if (s) s->Close();
      return E_ABORT;
    }
    inner_ = s;
    return hr;
  }
  int Read(uint8_t* buffer, int length) override {
    std::shared_ptr<Socket> s = inner();
    return s ? s->Read(buffer, length) : 0;
  }
  int Write(const uint8_t* buffer, int length) override {
    std::shared_ptr<Socket> s = inner();
    return s ? s->Write(buffer, length) : -kWsaNotConn;
  }
  int Available() override {
    std::shared_ptr<Socket> s = inner();
    return s ? s->Available() : 0;
  }
  int Channel() override {
    std::shared_ptr<Socket> s = inner();
    return s ? s->Channel() : channel_;
  }
  uint64_t Remote() override { return address_; }
  void Close() override {
    std::shared_ptr<Socket> s;
    {
      std::lock_guard<std::mutex> lock(mu_);
      closed_ = true;
      s = inner_;
    }
    cv_.notify_all();
    if (s) s->Close();
  }

 private:
  std::shared_ptr<Socket> inner() {
    std::lock_guard<std::mutex> lock(mu_);
    return inner_;
  }

  const uint64_t address_;
  const GUID uuid_;
  const int channel_;
  std::mutex mu_;
  std::condition_variable cv_;
  bool closed_ = false;
  std::shared_ptr<Socket> inner_;
};

// --- the backend ---------------------------------------------------------------------------

class Test final : public Backend {
 public:
  Adapter GetAdapter() override {
    Adapter a;
    a.present = true;
    a.on = gOn.load();
    a.address = kAdapterAddress;
    a.name = "Gesher Test Radio";
    a.classic = a.le = a.central = true;
    a.le2mPhy = true;
    a.via = "test radio (GESHER_BLUETOOTH_TEST)";
    return a;
  }

  HRESULT SetEnabled(bool on) override {
    gOn.store(on);
    return S_OK;
  }

  HRESULT Bonded(std::vector<Device>* out) override {
    out->clear();
    if (gOn.load()) out->push_back(Headset());
    return S_OK;
  }

  HRESULT Inquiry(int seconds, const std::atomic<bool>* cancel,
                  const std::function<void(const Device&)>& found) override {
    if (!gOn.load()) return HRESULT_FROM_WIN32(ERROR_DEVICE_NOT_CONNECTED);
    int steps = seconds * 10 < 6 ? 6 : seconds * 10 > 30 ? 30 : seconds * 10;
    for (int i = 0; i < steps; ++i) {
      if (cancel != nullptr && cancel->load()) return S_OK;
      if (i == 2) found(Headset());
      if (i == 4) found(Speaker());
      Sleep(100);
    }
    return S_OK;
  }

  HRESULT RemoteUuids(uint64_t address, bool, std::vector<GUID>* out) override {
    out->clear();
    if (address == kHeadsetAddress) {
      *out = Headset().uuids;
      return S_OK;
    }
    if (address == kSpeakerAddress) {
      *out = {ShortUuid(0x110B)};
      return S_OK;
    }
    return HRESULT_FROM_WIN32(ERROR_NOT_FOUND);
  }

  std::shared_ptr<Scan> StartScan(bool active, HRESULT* hr) override {
    if (!gOn.load()) {
      *hr = HRESULT_FROM_WIN32(ERROR_DEVICE_NOT_AVAILABLE);
      return nullptr;
    }
    *hr = S_OK;
    return std::make_shared<TestScan>(active);
  }

  std::shared_ptr<Gatt> OpenGatt(uint64_t address, int, HRESULT* hr) override {
    if (address != kPeripheralAddress) {
      *hr = HRESULT_FROM_WIN32(ERROR_DEVICE_NOT_CONNECTED);
      return nullptr;
    }
    *hr = S_OK;
    return std::make_shared<TestGatt>();
  }

  std::shared_ptr<Socket> OpenRfcomm(uint64_t address, const GUID& uuid, int channel, bool,
                                     HRESULT* hr) override {
    *hr = S_OK;
    return std::make_shared<PendingTestSocket>(address, uuid, channel);
  }

  std::shared_ptr<Server> ListenRfcomm(const std::wstring&, const GUID& uuid, bool,
                                       HRESULT* hr) override {
    std::lock_guard<std::mutex> lock(gServersMu);
    if (gServers.count(GuidText(uuid)) != 0) {
      *hr = HRESULT_FROM_WIN32(10048 /* WSAEADDRINUSE */);
      return nullptr;
    }
    auto server = std::make_shared<TestServer>(uuid, gNextChannel++);
    gServers[GuidText(uuid)] = server.get();
    *hr = S_OK;
    return server;
  }
};

}  // namespace

Backend& TestBackend() {
  static Test* backend = new Test();
  return *backend;
}

}  // namespace gesher::devices::bluetooth
