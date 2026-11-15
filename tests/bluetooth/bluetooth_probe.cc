// Gesher (docs/bluetooth.md): the Bluetooth backend (compat/devices/bluetooth*.cc)
// on its own, without ART: what Windows answers on this machine, and, with
// GESHER_BLUETOOTH_TEST=1, every path of the test radio. Built and run by
// .github/workflows/bluetooth.yml; useful by hand on a PC with a radio:
//
//   bluetooth_probe.exe            adapter, bonded devices, a 4 s LE scan
//   bluetooth_probe.exe --inquiry  ... and a classic inquiry
//   bluetooth_probe.exe --hw "Liberty 4 Pro"
//                                  a real radio and one paired device, read-only: adapter,
//                                  paired devices with SDP service classes, profiles, a 10 s
//                                  passive scan (count only), an RFCOMM connection to the
//                                  device's vendor service (or SPP) that only reads for 2 s,
//                                  and the event subscriptions; never writes, pairs or
//                                  switches the radio
//   set GESHER_BLUETOOTH_TEST=1 && bluetooth_probe.exe --check
//                                  the test radio, with checks (exit 1 on failure)
//
// Prints "bluetooth-probe: ..." lines; never connects to a real device.

#include "bluetooth.h"

#include <stdio.h>
#include <string.h>

#include <map>
#include <set>
#include <thread>

namespace bt = gesher::devices::bluetooth;

namespace {

int gFailures = 0;

void Check(bool ok, const char* what) {
  printf("bluetooth-probe: %s %s\n", ok ? "PASS" : "FAIL", what);
  if (!ok) ++gFailures;
}

std::string Address(uint64_t a) {
  char text[32];
  snprintf(text, sizeof(text), "%02X:%02X:%02X:%02X:%02X:%02X",
           static_cast<unsigned>((a >> 40) & 0xff), static_cast<unsigned>((a >> 32) & 0xff),
           static_cast<unsigned>((a >> 24) & 0xff), static_cast<unsigned>((a >> 16) & 0xff),
           static_cast<unsigned>((a >> 8) & 0xff), static_cast<unsigned>(a & 0xff));
  return text;
}

void PrintDevice(const char* what, const bt::Device& d) {
  std::string uuids;
  for (const GUID& g : d.uuids) uuids += " " + bt::GuidText(g);
  printf("bluetooth-probe: %s %s \"%s\" class 0x%06X kind %d bonded %d connected %d uuids:%s\n",
         what, Address(d.address).c_str(), d.name.c_str(), static_cast<unsigned>(d.classOfDevice),
         d.kind, d.bonded ? 1 : 0, d.connected ? 1 : 0, uuids.c_str());
}

// Counts reports per address for `ms`.
std::map<uint64_t, int> ScanFor(bt::Scan* scan, int ms, bt::Advertisement* last) {
  std::map<uint64_t, int> seen;
  ULONGLONG end = GetTickCount64() + ms;
  while (GetTickCount64() < end) {
    bt::Advertisement a;
    if (scan->Next(&a, 100)) {
      ++seen[a.address];
      if (last != nullptr) *last = a;
    } else if (FAILED(scan->Error())) {
      break;
    }
  }
  return seen;
}

void Report(bt::Backend& backend, bool inquiry) {
  bt::Adapter a = backend.GetAdapter();
  printf("bluetooth-probe: adapter present %d on %d address %s name \"%s\" classic %d le %d "
         "central %d peripheral %d ext-adv %d le2m %d coded %d max-adv %d via %s hr 0x%08lX\n",
         a.present, a.on, Address(a.address).c_str(), a.name.c_str(), a.classic, a.le, a.central,
         a.peripheral, a.extendedAdvertising, a.le2mPhy, a.leCodedPhy,
         a.maxAdvertisementDataLength, a.via.c_str(), static_cast<unsigned long>(a.hr));
  if (!a.present) return;
  std::vector<bt::Device> bonded;
  HRESULT hr = backend.Bonded(&bonded);
  printf("bluetooth-probe: bonded: %zu device(s), hr 0x%08lX\n", bonded.size(),
         static_cast<unsigned long>(hr));
  for (const bt::Device& d : bonded) PrintDevice("bonded", d);
  if (!a.on) {
    printf("bluetooth-probe: the radio is off: no scan\n");
    return;
  }
  bt::Advertisement last;
  std::shared_ptr<bt::Scan> scan = backend.StartScan(true, &hr);
  printf("bluetooth-probe: LE scan started: hr 0x%08lX\n", static_cast<unsigned long>(hr));
  if (scan) {
    std::map<uint64_t, int> seen = ScanFor(scan.get(), 4000, &last);
    printf("bluetooth-probe: LE scan: %zu address(es) in 4 s, error 0x%08lX\n", seen.size(),
           static_cast<unsigned long>(scan->Error()));
    scan->Stop();
  }
  if (inquiry) {
    std::atomic<bool> cancel{false};
    hr = backend.Inquiry(5, &cancel, [](const bt::Device& d) { PrintDevice("inquiry", d); });
    printf("bluetooth-probe: inquiry ended: hr 0x%08lX\n", static_cast<unsigned long>(hr));
  }
}

bool IsVendor(const GUID& g) {
  // Soundcore's vendor RFCOMM services: 0cf12d31-fac3-4553-bd80-d6832e7xxxxx.
  return bt::GuidText(g).compare(0, 31, "0cf12d31-fac3-4553-bd80-d6832e7") == 0;
}

void HwDevice(const char* what, const bt::Device& d) {
  std::string uuids;
  for (const GUID& g : d.uuids) uuids += " " + bt::GuidText(g);
  printf("bluetooth-probe: %s \"%s\" class 0x%06X kind %d bonded %d connected %d uuids:%s\n",
         what, d.name.c_str(), static_cast<unsigned>(d.classOfDevice), d.kind, d.bonded ? 1 : 0,
         d.connected ? 1 : 0, uuids.c_str());
}

// A real radio, read-only (see the usage above). Other devices are counted, never named.
void HwCheck(bt::Backend& backend, const std::string& want) {
  bt::Adapter a = backend.GetAdapter();
  printf("bluetooth-probe: hw adapter present %d on %d classic %d le %d central %d peripheral %d "
         "ext-adv %d le2m %d coded %d max-adv %d hr 0x%08lX\n",
         a.present, a.on, a.classic, a.le, a.central, a.peripheral, a.extendedAdvertising,
         a.le2mPhy, a.leCodedPhy, a.maxAdvertisementDataLength, static_cast<unsigned long>(a.hr));
  printf("bluetooth-probe: hw features: bluetooth %d, bluetooth_le %d\n", a.present ? 1 : 0,
         a.present && a.le ? 1 : 0);
  if (!a.present) return;
  std::vector<bt::Device> bonded;
  HRESULT hr = backend.Bonded(&bonded);
  bt::WatchStatus watch = bt::Watching();
  printf("bluetooth-probe: hw bonded: %zu device(s), hr 0x%08lX; watching radio %d, %d device(s)\n",
         bonded.size(), static_cast<unsigned long>(hr), watch.radio ? 1 : 0, watch.devices);
  const bt::Device* target = nullptr;
  for (const bt::Device& d : bonded) {
    if (target == nullptr && d.name.find(want) != std::string::npos) target = &d;
  }
  if (target == nullptr) {
    printf("bluetooth-probe: hw no paired device named like \"%s\"\n", want.c_str());
    return;
  }
  bt::Device device = *target;
  HwDevice("hw target (cached SDP)", device);
  std::vector<GUID> fresh;
  hr = backend.RemoteUuids(device.address, true, &fresh);
  std::string uuids;
  for (const GUID& g : fresh) uuids += " " + bt::GuidText(g);
  printf("bluetooth-probe: hw target SDP asked again: hr 0x%08lX, %zu class(es):%s\n",
         static_cast<unsigned long>(hr), fresh.size(), uuids.c_str());
  for (const GUID& g : fresh) {
    bool have = false;
    for (const GUID& h : device.uuids) have = have || h == g;
    if (!have) device.uuids.push_back(g);
  }
  auto has = [&device](uint16_t id) {
    for (const GUID& g : device.uuids) {
      if (g == bt::ShortUuid(id)) return true;
    }
    return false;
  };
  bool a2dp = has(0x110B) || has(0x110D);
  bool headset = has(0x111E) || has(0x1108);
  printf("bluetooth-probe: hw profiles: A2DP %s, headset %s\n",
         a2dp ? (device.connected ? "connected" : "disconnected") : "not offered",
         headset ? (device.connected ? "connected" : "disconnected") : "not offered");

  if (a.on) {
    std::shared_ptr<bt::Scan> scan = backend.StartScan(false, &hr);
    printf("bluetooth-probe: hw passive LE scan started: hr 0x%08lX\n", static_cast<unsigned long>(hr));
    if (scan) {
      std::set<uint64_t> seen;
      int reports = 0;
      ULONGLONG end = GetTickCount64() + 10000;
      while (GetTickCount64() < end) {
        bt::Advertisement adv;
        if (scan->Next(&adv, 200)) {
          ++reports;
          seen.insert(adv.address);
        } else if (FAILED(scan->Error())) {
          break;
        }
      }
      printf("bluetooth-probe: hw passive LE scan: %d report(s) from %zu address(es) in 10 s, "
             "error 0x%08lX\n", reports, seen.size(), static_cast<unsigned long>(scan->Error()));
      scan->Stop();
    }
  }

  GUID service = bt::ShortUuid(0x1101);
  const char* kind = "SPP";
  for (const GUID& g : device.uuids) {
    if (IsVendor(g)) {
      service = g;
      kind = "vendor";
      break;
    }
  }
  if (!has(0x1101) && strcmp(kind, "SPP") == 0) {
    printf("bluetooth-probe: hw no vendor or SPP service listed; trying SPP anyway\n");
  }
  ULONGLONG start = GetTickCount64();
  std::shared_ptr<bt::Socket> socket = backend.OpenRfcomm(device.address, service, 0, false, &hr);
  HRESULT connected = socket ? socket->Connect() : hr;
  printf("bluetooth-probe: hw RFCOMM %s %s: hr 0x%08lX after %llu ms, channel %d\n", kind,
         bt::GuidText(service).c_str(), static_cast<unsigned long>(connected),
         static_cast<unsigned long long>(GetTickCount64() - start),
         socket && SUCCEEDED(connected) ? socket->Channel() : -1);
  if (socket && SUCCEEDED(connected)) {
    // Read only: whatever the device sends by itself within 2 s.
    int total = 0;
    std::string first;
    ULONGLONG end = GetTickCount64() + 2000;
    while (GetTickCount64() < end) {
      int available = socket->Available();
      if (available <= 0) {
        Sleep(20);
        continue;
      }
      uint8_t buffer[512];
      int n = socket->Read(buffer, available < 512 ? available : 512);
      if (n <= 0) break;
      for (int i = 0; i < n && first.size() < 96; ++i) {
        char hex[4];
        snprintf(hex, sizeof(hex), "%02x", buffer[i]);
        first += hex;
      }
      total += n;
    }
    printf("bluetooth-probe: hw RFCOMM read %d byte(s) in 2 s unasked%s%s\n", total,
           total > 0 ? ": " : "", first.c_str());
  }
  if (socket) socket->Close();
  printf("bluetooth-probe: hw RFCOMM closed\n");

  // Event subscriptions: what Windows announces while we wait (a connection change, a
  // radio switch); nothing is asked of the device.
  backend.WaitForChange(0);
  bool changed = backend.WaitForChange(15000);
  std::vector<bt::Device> after;
  backend.Bonded(&after);
  int connectedNow = -1;
  for (const bt::Device& d : after) {
    if (d.address == device.address) connectedNow = d.connected ? 1 : 0;
  }
  watch = bt::Watching();
  printf("bluetooth-probe: hw events: change announced in 15 s %d; target connected %d; "
         "watching radio %d, %d device(s)\n", changed ? 1 : 0, connectedNow, watch.radio ? 1 : 0,
         watch.devices);
}

// GESHER_BLUETOOTH_TEST's radio, end to end.
void CheckTestRadio(bt::Backend& backend) {
  bt::Adapter a = backend.GetAdapter();
  Check(a.present && a.on && a.address == 0x001A7DDA7113ULL && a.name == "Gesher Test Radio",
        "test adapter present, on, 00:1A:7D:DA:71:13 \"Gesher Test Radio\"");
  std::vector<bt::Device> bonded;
  Check(SUCCEEDED(backend.Bonded(&bonded)) && bonded.size() == 1 &&
            bonded[0].name == "Gesher Test Headset" && bonded[0].connected &&
            bonded[0].uuids.size() == 4,
        "one bonded, connected headset with 4 service classes");
  std::vector<std::string> found;
  std::atomic<bool> cancel{false};
  HRESULT hr = backend.Inquiry(1, &cancel, [&found](const bt::Device& d) {
    found.push_back(d.name);
  });
  Check(SUCCEEDED(hr) && found.size() == 2 && found[1] == "Gesher Test Speaker",
        "inquiry finds the headset and the speaker");
  {
    backend.WaitForChange(0);  // forget earlier changes
    int result = -1;
    Check(SUCCEEDED(backend.Pair(0x00025B00B0B0ULL, false, &result)) && result == 0,
          "pairing the speaker (Windows' UI, accepted)");
    Check(backend.WaitForChange(1000), "the pairing is announced as a change");
    std::vector<bt::Device> now;
    Check(SUCCEEDED(backend.Bonded(&now)) && now.size() == 2, "two bonded devices after pairing");
    Check(SUCCEEDED(backend.Pair(0x00025B00A5A5ULL, false, &result)) && result == 1,
          "the headset was paired already");
    Check(SUCCEEDED(backend.Unpair(0x00025B00B0B0ULL, &result)) && result == 0 &&
              SUCCEEDED(backend.Bonded(&now)) && now.size() == 1,
          "unpairing the speaker");
    backend.WaitForChange(0);
    Check(!backend.WaitForChange(100), "no change without an event");
  }
  std::vector<GUID> uuids;
  Check(SUCCEEDED(backend.RemoteUuids(0x00025B00A5A5ULL, true, &uuids)) && uuids.size() == 4 &&
            uuids[0] == bt::ShortUuid(0x1101),
        "SDP service classes of the headset (SPP first)");

  std::shared_ptr<bt::Scan> scan = backend.StartScan(true, &hr);
  bt::Advertisement last;
  int responses = 0, reports = 0;
  if (scan) {
    ULONGLONG end = GetTickCount64() + 1000;
    while (GetTickCount64() < end) {
      bt::Advertisement adv;
      if (!scan->Next(&adv, 100)) continue;
      ++reports;
      if (adv.scanResponse) ++responses; else last = adv;
    }
    scan->Stop();
  }
  Check(scan && reports >= 4 && responses >= 2 && last.address == 0xC0FFEE000001ULL &&
            last.addressType == 1 && last.data.size() == 25 && last.data[0] == 2 &&
            last.data[1] == 1,
        "active LE scan: advertisements and scan responses of C0:FF:EE:00:00:01 (random)");

  std::shared_ptr<bt::Gatt> unknown = backend.OpenGatt(0x112233445566ULL, 0, &hr);
  Check(!unknown && FAILED(hr), "GATT to an unknown address fails");
  std::shared_ptr<bt::Gatt> gatt = backend.OpenGatt(0xC0FFEE000001ULL, 1, &hr);
  int mtu = 0;
  Check(gatt && SUCCEEDED(gatt->Connect(5000, &mtu)) && mtu == 247, "GATT connect, MTU 247");
  if (gatt) {
    std::vector<bt::Attribute> attributes;
    int status = gatt->Discover(&attributes);
    Check(status == bt::kGattSuccess && attributes.size() == 12 &&
              attributes[3].uuid == bt::ShortUuid(0x2A19) && attributes[3].properties == 0x12,
          "GATT discovery: 3 primary services, 4 characteristics, 2 descriptors, an included one");
    Check(attributes[9].kind == bt::kIncluded && attributes[9].handle == 30 &&
              attributes[9].properties == 20 && attributes[10].kind == bt::kService &&
              attributes[10].properties == 1 && attributes[11].handle == 32,
          "the UART service includes a secondary service, listed after the primaries");
    int tx = 0, rx = 0;
    Check(gatt->Phy(&tx, &rx) == bt::kGattSuccess && tx == 2 && rx == 2, "PHY: LE 2M both ways");
    std::vector<uint8_t> value;
    status = gatt->Read(bt::kCharacteristic, 12, &value);
    Check(status == bt::kGattSuccess && value.size() == 1 && value[0] == 87,
          "read battery level 87");
    status = gatt->Read(bt::kCharacteristic, 22, &value);
    Check(status == bt::kGattReadNotPermitted, "read of a write-only characteristic: not permitted");
    Check(gatt->SetNotify(12, 1) == bt::kGattSuccess, "battery notifications on");
    bt::GattEvent e;
    Check(gatt->NextEvent(&e, 2000) && e.type == bt::kNotification && e.handle == 12 &&
              e.data.size() == 1 && e.data[0] == 86,
          "battery notification 86");
    Check(gatt->Write(bt::kDescriptor, 25, {1, 0}, true) == bt::kGattSuccess,
          "TX notifications on through its CCCD");
    Check(gatt->Write(bt::kCharacteristic, 22, {'h', 'i'}, false) == bt::kGattSuccess,
          "write without response to RX");
    Check(gatt->NextEvent(&e, 2000) && e.type == bt::kNotification && e.handle == 24 &&
              e.data == std::vector<uint8_t>({'h', 'i'}),
          "RX echoed as a TX notification");
    gatt->Close();
    Check(gatt->NextEvent(&e, 100) && e.type == bt::kClosed, "closed connection reports closed");
  }

  std::shared_ptr<bt::Socket> refused =
      backend.ConnectRfcomm(0x00025B00A5A5ULL, bt::ShortUuid(0x1234), 0, false, &hr);
  Check(!refused && hr == HRESULT_FROM_WIN32(10061), "RFCOMM to an absent service is refused");
  GUID vendor;
  bt::ParseGuid("0cf12d31-fac3-4553-bd80-d6832e70f1a5", &vendor);
  std::shared_ptr<bt::Socket> socket = backend.ConnectRfcomm(0x00025B00A5A5ULL, vendor, 0, true,
                                                             &hr);
  Check(socket && socket->Channel() == 2, "RFCOMM to the vendor service (channel 2)");
  if (socket) {
    const uint8_t hello[] = {0x08, 0xEE, 0x00, 0x00, 0x00, 0x01, 0x01, 0x0A, 0x00, 0x02};
    uint8_t back[16] = {};
    Check(socket->Write(hello, sizeof(hello)) == static_cast<int>(sizeof(hello)) &&
              socket->Available() == static_cast<int>(sizeof(hello)) &&
              socket->Read(back, sizeof(back)) == static_cast<int>(sizeof(hello)) &&
              memcmp(back, hello, sizeof(hello)) == 0,
          "RFCOMM echo of a 10-byte packet");
    socket->Close();
    Check(socket->Read(back, sizeof(back)) == 0, "RFCOMM read after close: end of stream");
  }
  {
    // Channel 3 of the headset never answers: Close() from another thread ends Connect().
    std::shared_ptr<bt::Socket> slow = backend.OpenRfcomm(0x00025B00A5A5ULL, GUID{}, 3, false, &hr);
    std::thread closer([slow] {
      Sleep(300);
      slow->Close();
    });
    ULONGLONG start = GetTickCount64();
    HRESULT result = slow ? slow->Connect() : E_FAIL;
    closer.join();
    Check(result == E_ABORT && GetTickCount64() - start < 5000, "Close() cancels a blocked connect");
  }
  GUID chat;
  bt::ParseGuid("fa87c0d0-afac-11de-8a39-0800200c9a66", &chat);
  std::shared_ptr<bt::Server> server = backend.ListenRfcomm(L"Gesher chat", chat, true, &hr);
  Check(server != nullptr && server->Channel() >= 10, "RFCOMM listener registered");
  if (server) {
    std::shared_ptr<bt::Socket> client =
        backend.ConnectRfcomm(0x001A7DDA7113ULL, chat, 0, true, &hr);
    std::shared_ptr<bt::Socket> accepted = server->Accept(2000, &hr);
    uint8_t byte = 0;
    const uint8_t ping = 0x5A;
    Check(client && accepted && client->Write(&ping, 1) == 1 && accepted->Read(&byte, 1) == 1 &&
              byte == 0x5A,
          "RFCOMM loopback: accept and read");
    server->Close();
    std::shared_ptr<bt::Socket> late = server->Accept(100, &hr);
    Check(!late, "accept after close fails");
  }
  Check(SUCCEEDED(backend.SetEnabled(false)) && !backend.GetAdapter().on, "radio off");
  scan = backend.StartScan(false, &hr);
  Check(!scan && FAILED(hr), "no scan while off");
  Check(SUCCEEDED(backend.SetEnabled(true)) && backend.GetAdapter().on, "radio on again");
}

}  // namespace

int main(int argc, char** argv) {
  setvbuf(stdout, nullptr, _IONBF, 0);
  bool inquiry = false, check = false;
  const char* hw = nullptr;
  for (int i = 1; i < argc; ++i) {
    if (strcmp(argv[i], "--inquiry") == 0) inquiry = true;
    if (strcmp(argv[i], "--check") == 0) check = true;
    if (strcmp(argv[i], "--hw") == 0) hw = i + 1 < argc ? argv[i + 1] : "Liberty 4 Pro";
  }
  bt::Backend& backend = bt::Get();
  if (hw != nullptr) {
    HwCheck(backend, hw);
    printf("bluetooth-probe: done\n");
    return 0;
  }
  if (check) {
    CheckTestRadio(backend);
    printf("bluetooth-probe: %d failure(s)\n", gFailures);
    return gFailures == 0 ? 0 : 1;
  }
  Report(backend, inquiry);
  printf("bluetooth-probe: done\n");
  return 0;
}
