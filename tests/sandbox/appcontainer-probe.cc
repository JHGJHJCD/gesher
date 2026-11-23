// Copyright (C) 2026 The Gesher Authors
// SPDX-License-Identifier: Apache-2.0
//
// AppContainer isolation, milestone M1 (docs/decisions/0090-appcontainer-app-isolation.md):
// the production launch path (gesher::zygote::SpawnInAppContainer) and binderd's
// sandbox admission, with real Windows processes, tokens and a running binderd.
//
//   appcontainer-probe <binder device>
//
// binderd must serve <device> with GESHER_SANDBOX=appcontainer. The probe starts
// copies of itself in a throwaway AppContainer, with and without the binder-client
// capability, and each reports what the kernel and binderd let it do. Prints
// "all passed" and exits 0 when every expectation holds.
#include <windows.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "app_container.h"
#include "posix/src/binder_wire.h"
#include "services_zygote.h"

namespace wire = gesher::binder_wire;
namespace {

std::u16string U16(const std::wstring& value) {
  return {reinterpret_cast<const char16_t*>(value.data()), value.size()};
}

std::wstring Wide(const char* text) { return std::wstring(text, text + std::strlen(text)); }

bool Transfer(HANDLE pipe, void* bytes, DWORD size, bool write) {
  auto* cursor = static_cast<unsigned char*>(bytes);
  while (size) {
    DWORD count = 0;
    if (!(write ? WriteFile(pipe, cursor, size, &count, nullptr)
                : ReadFile(pipe, cursor, size, &count, nullptr)) ||
        !count) {
      return false;
    }
    cursor += count;
    size -= count;
  }
  return true;
}

// Opens the device's pipe and says hello as (pid, uid). Returns the reply's status,
// or a description of how the connection failed.
std::string Hello(const std::wstring& device, uint32_t pid, uint32_t uid) {
  std::wstring name = wire::kPipePrefix + device;
  HANDLE pipe = CreateFileW(name.c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr,
                            OPEN_EXISTING, 0, nullptr);
  if (pipe == INVALID_HANDLE_VALUE) return "open error " + std::to_string(GetLastError());
  uint32_t hello[]{5 * sizeof(uint32_t), wire::kHelloProc, wire::kMagic, wire::kVersion, pid, uid};
  uint32_t length = 0;
  int32_t status = 1;
  std::string result;
  if (!Transfer(pipe, hello, sizeof(hello), true) ||
      !Transfer(pipe, &length, sizeof(length), false) || length < sizeof(status) ||
      !Transfer(pipe, &status, sizeof(status), false)) {
    result = "io error " + std::to_string(GetLastError());
  } else {
    result = "status " + std::to_string(status);
  }
  CloseHandle(pipe);
  return result;
}

// In the container: what it may do. One line per check: "<check> <result>".
int Child(const std::wstring& device, const std::wstring& canary, DWORD parent) {
  HANDLE file = CreateFileW(canary.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                            OPEN_EXISTING, 0, nullptr);
  if (file != INVALID_HANDLE_VALUE) {
    std::printf("canary opened\n");
    CloseHandle(file);
  } else {
    std::printf("canary error %lu\n", GetLastError());
  }
  const DWORD self = GetCurrentProcessId();
  std::printf("hello-app %s\n", Hello(device, self, 10123).c_str());
  std::printf("hello-system %s\n", Hello(device, self, 1000).c_str());
  std::printf("hello-other-pid %s\n", Hello(device, parent, 10123).c_str());
  std::fflush(stdout);
  return 0;
}

std::string ReadAll(const std::wstring& path) {
  std::string text;
  HANDLE file = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr,
                            OPEN_EXISTING, 0, nullptr);
  if (file == INVALID_HANDLE_VALUE) return text;
  char buffer[4096];
  DWORD count = 0;
  while (ReadFile(file, buffer, sizeof(buffer), &count, nullptr) && count) text.append(buffer, count);
  CloseHandle(file);
  return text;
}

// Starts this program in the container with `capabilities`; returns its output.
std::string RunContained(const std::wstring& exe, const std::wstring& container,
                         const std::vector<std::u16string>& capabilities,
                         const std::wstring& device, const std::wstring& canary,
                         const std::wstring& log) {
  DeleteFileW(log.c_str());
  gesher::zygote::AppContainerSpec spec{U16(container), U16(container), capabilities};
  std::wstring command = L"\"" + exe + L"\" --child " + device + L" \"" + canary + L"\" " +
                         std::to_wstring(GetCurrentProcessId());
  int pid = gesher::zygote::SpawnInAppContainer(U16(command), {}, {}, U16(log), spec);
  if (pid <= 0) return "spawn error " + std::to_string(-pid) + "\n";
  for (int i = 0; i < 300 && gesher::zygote::ExitCode(pid) == STILL_ACTIVE; ++i) Sleep(100);
  return ReadAll(log);
}

bool Expect(const std::string& output, const char* line, const char* what) {
  bool ok = output.find(line) != std::string::npos;
  std::printf("%s  %s\n", ok ? "PASS" : "FAIL", what);
  return ok;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc == 5 && !std::strcmp(argv[1], "--child")) {
    return Child(Wide(argv[2]), Wide(argv[3]), static_cast<DWORD>(std::strtoul(argv[4], nullptr, 10)));
  }
  if (argc != 2) {
    std::fprintf(stderr, "usage: appcontainer-probe <binder device>\n");
    return 2;
  }
  const std::wstring device = Wide(argv[1]);
  wchar_t exe[MAX_PATH], temp[MAX_PATH];
  GetModuleFileNameW(nullptr, exe, MAX_PATH);
  GetTempPathW(MAX_PATH, temp);
  const std::wstring stamp = std::to_wstring(GetCurrentProcessId());
  const std::wstring container = L"Gesher.Test.Probe-" + stamp;
  // The user's own file: no ACE names a container, so the kernel must refuse it.
  const std::wstring canary = std::wstring(temp) + L"gesher-sandbox-canary-" + stamp + L".txt";
  HANDLE file = CreateFileW(canary.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, 0, nullptr);
  if (file == INVALID_HANDLE_VALUE) return 3;
  DWORD written = 0;
  WriteFile(file, "secret", 6, &written, nullptr);
  CloseHandle(file);
  // The probe's own directory (its exe) is opened to the runtime capability, as the
  // system server opens the runtime's; the log is an inherited handle.
  std::wstring dir(exe);
  dir.resize(dir.find_last_of(L'\\'));
  int failures = 0;
  if (gesher::zygote::GrantToCapability(U16(dir), u"gesherRuntimeFiles", 0x001200A9, true) != 0) {
    std::printf("FAIL  granting the runtime capability on %ls\n", dir.c_str());
    ++failures;
  }
  const std::wstring log = std::wstring(temp) + L"gesher-sandbox-probe-" + stamp + L".log";

  std::string admitted = RunContained(exe, container, {u"gesherRuntimeFiles", u"gesherBinderClient"},
                                      device, canary, log);
  std::printf("-- contained, with gesherBinderClient:\n%s", admitted.c_str());
  failures += !Expect(admitted, "canary error 5", "the user's file is closed to the container (ERROR_ACCESS_DENIED)");
  failures += !Expect(admitted, "hello-app status 0", "binderd admits the container as an app UID at its own pid");
  failures += !Expect(admitted, "hello-system status -1", "binderd refuses the container's claim of SYSTEM_UID (EPERM)");
  failures += !Expect(admitted, "hello-other-pid status -1", "binderd refuses the container's claim of another pid (EPERM)");

  std::string refused = RunContained(exe, container, {u"gesherRuntimeFiles"}, device, canary, log);
  std::printf("-- contained, without gesherBinderClient:\n%s", refused.c_str());
  failures += !Expect(refused, "hello-app open error 5", "binderd's pipe is closed to a container without the capability");

  gesher::zygote::DeleteAppContainer(U16(container));
  DeleteFileW(canary.c_str());
  DeleteFileW(log.c_str());
  if (failures == 0) std::printf("all passed\n");
  return failures == 0 ? 0 : 1;
}
