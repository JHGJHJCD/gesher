// Gesher (docs/bluetooth.md): the Bluetooth backend (compat/devices/bluetooth*.cc)
// on its own, without ART: what Windows answers on this machine, and, with
// GESHER_BLUETOOTH_TEST=1, every path of the test radio. Built and run by
// .github/workflows/bluetooth.yml; useful by hand on a PC with a radio:
//
//   bluetooth_probe.exe            adapter, bonded devices, a 4 s LE scan
//   bluetooth_probe.exe --inquiry  ... and a classic inquiry
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
  for (int i = 1; i < argc; ++i) {
    if (strcmp(argv[i], "--inquiry") == 0) inquiry = true;
    if (strcmp(argv[i], "--check") == 0) check = true;
  }
  bt::Backend& backend = bt::Get();
  if (check) {
    CheckTestRadio(backend);
    printf("bluetooth-probe: %d failure(s)\n", gFailures);
    return gFailures == 0 ? 0 : 1;
  }
  Report(backend, inquiry);
  printf("bluetooth-probe: done\n");
  return 0;
}
