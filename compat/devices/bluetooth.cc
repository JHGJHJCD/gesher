// Gesher (devices track, docs/bluetooth.md): the backend choice and the
// helpers of bluetooth.h.

#include "bluetooth.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

namespace gesher::devices::bluetooth {

Backend& Get() {
  static Backend* backend = [] {
    const char* test = getenv("GESHER_BLUETOOTH_TEST");
    return test != nullptr && strcmp(test, "1") == 0 ? &TestBackend() : &WindowsBackend();
  }();
  return *backend;
}

std::string Utf8(const std::wstring& s) {
  if (s.empty()) return std::string();
  int n = WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0,
                              nullptr, nullptr);
  std::string out(n > 0 ? n : 0, '\0');
  if (n > 0) {
    WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), &out[0], n, nullptr,
                        nullptr);
  }
  return out;
}

std::wstring Wide(const std::string& s) {
  if (s.empty()) return std::wstring();
  int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
  std::wstring out(n > 0 ? n : 0, L'\0');
  if (n > 0) MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), &out[0], n);
  return out;
}

std::string GuidText(const GUID& g) {
  char text[40];
  snprintf(text, sizeof(text), "%08lx-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
           static_cast<unsigned long>(g.Data1), g.Data2, g.Data3, g.Data4[0], g.Data4[1],
           g.Data4[2], g.Data4[3], g.Data4[4], g.Data4[5], g.Data4[6], g.Data4[7]);
  return text;
}

namespace {

int Hex(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

}  // namespace

bool ParseGuid(const std::string& text, GUID* out) {
  // 8-4-4-4-12 hexadecimal digits.
  if (text.size() != 36 || text[8] != '-' || text[13] != '-' || text[18] != '-' ||
      text[23] != '-') {
    return false;
  }
  uint8_t bytes[16];
  int k = 0;
  for (size_t i = 0; i < text.size();) {
    if (text[i] == '-') {
      ++i;
      continue;
    }
    int hi = Hex(text[i]), lo = Hex(text[i + 1]);
    if (hi < 0 || lo < 0 || k >= 16) return false;
    bytes[k++] = static_cast<uint8_t>(hi << 4 | lo);
    i += 2;
  }
  if (k != 16) return false;
  out->Data1 = static_cast<unsigned long>(bytes[0]) << 24 | bytes[1] << 16 | bytes[2] << 8 |
               bytes[3];
  out->Data2 = static_cast<unsigned short>(bytes[4] << 8 | bytes[5]);
  out->Data3 = static_cast<unsigned short>(bytes[6] << 8 | bytes[7]);
  memcpy(out->Data4, bytes + 8, 8);
  return true;
}

GUID ShortUuid(uint16_t id) {
  // 0000xxxx-0000-1000-8000-00805f9b34fb
  GUID g = {id, 0x0000, 0x1000, {0x80, 0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb}};
  return g;
}

}  // namespace gesher::devices::bluetooth
