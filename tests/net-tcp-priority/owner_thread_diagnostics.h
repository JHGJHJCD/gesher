// HOST fixture observation only. The original census and assertion stay authoritative.
#pragma once

#include <windows.h>
#include <tlhelp32.h>
#include <winternl.h>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cwchar>

namespace gesher::test {
namespace owner_thread_diagnostics {
struct Snapshot {
  DWORD tids[256]{};
  unsigned captured = 0, observed = 0;
  DWORD error = 0, close_error = 0;
};

Snapshot Capture() {
  Snapshot result;
  HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
  if (snapshot == INVALID_HANDLE_VALUE) { result.error = GetLastError(); return result; }
  THREADENTRY32 entry{};
  entry.dwSize = sizeof(entry);
  if (Thread32First(snapshot, &entry)) {
    do {
      if (entry.th32OwnerProcessID == GetCurrentProcessId()) {
        ++result.observed;
        if (result.captured < 256) result.tids[result.captured++] = entry.th32ThreadID;
      }
      entry.dwSize = sizeof(entry);
    } while (Thread32Next(snapshot, &entry));
    const DWORD error = GetLastError();
    if (error != ERROR_NO_MORE_FILES) result.error = error;
  } else result.error = GetLastError();
  if (!CloseHandle(snapshot)) result.close_error = GetLastError();
  return result;
}

struct SavedErrors {
  DWORD windows = GetLastError();
  int c = errno;
  ~SavedErrors() { errno = c; SetLastError(windows); }
};
}  // namespace owner_thread_diagnostics

// Keep only bounded numeric observations at the first census, never thread handles.
// Subsequent successful modes do no work. On failure, take a separate snapshot:
// neither snapshot can replace the counts already used by the original gate.
void ObserveOwnerThreads(int mode, bool failed) {
  using namespace owner_thread_diagnostics;
  static Snapshot baseline;
  if (mode != 0 && !failed) return;
  SavedErrors saved;
  if (mode == 0 && !failed) { baseline = Capture(); return; }
  const Snapshot current = Capture();
  std::fprintf(stderr, "TCP_PRIORITY_THREAD_DIAGNOSTIC mode=%d snapshots=post_census "
      "baseline=%u/%u baseline_error=%lu baseline_close_error=%lu "
      "current=%u/%u current_error=%lu current_close_error=%lu original_gate_unchanged=1\n",
      mode, baseline.captured, baseline.observed, baseline.error, baseline.close_error,
      current.captured, current.observed, current.error, current.close_error);
  for (unsigned i = 0; i < baseline.captured; ++i)
    std::fprintf(stderr, "TCP_PRIORITY_THREAD_BASELINE mode=%d tid=%lu numeric_observation_only=1\n",
                 mode, baseline.tids[i]);

  // Resolve the optional documented native query at runtime. A missing query,
  // vanished thread or unknown module is diagnostic data, never a reason to pass.
  using Query = NTSTATUS (NTAPI*)(HANDLE, THREADINFOCLASS, PVOID, ULONG, PULONG);
  Query query = nullptr;
  HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
  FARPROC symbol = ntdll ? GetProcAddress(ntdll, "NtQueryInformationThread") : nullptr;
  static_assert(sizeof(query) == sizeof(symbol));
  std::memcpy(&query, &symbol, sizeof(query));
  unsigned opened = 0, closed = 0, modules = 0, released = 0;
  for (unsigned i = 0; i < current.captured; ++i) {
    const DWORD tid = current.tids[i];
    bool seen = false;
    for (unsigned j = 0; j < baseline.captured; ++j) seen |= baseline.tids[j] == tid;
    HANDLE thread = OpenThread(THREAD_QUERY_INFORMATION | SYNCHRONIZE, FALSE, tid);
    if (!thread) {
      const DWORD error = GetLastError();
      std::fprintf(stderr, "TCP_PRIORITY_THREAD_CURRENT mode=%d tid=%lu seen_baseline_tid=%d open_error=%lu\n",
                   mode, tid, seen, error);
      continue;
    }
    ++opened;
    // A snapshot TID can retire and be reused. Query no thread details until its
    // newly retained handle is confirmed to belong to this process.
    const DWORD owner = GetProcessIdOfThread(thread);
    if (owner == GetCurrentProcessId()) {
      FILETIME birth{}, exit{}, kernel{}, user{};
      const BOOL times = GetThreadTimes(thread, &birth, &exit, &kernel, &user);
      const DWORD times_error = times ? 0 : GetLastError();
      const uint64_t created = (uint64_t{birth.dwHighDateTime} << 32) | birth.dwLowDateTime;
      const DWORD wait = WaitForSingleObject(thread, 0);
      void* start = nullptr;
      ULONG bytes = 0;
      const NTSTATUS status = query ? query(thread, static_cast<THREADINFOCLASS>(9),
          &start, sizeof(start), &bytes) : static_cast<NTSTATUS>(0xc0000002u);
      wchar_t path[1024]{};
      const wchar_t* name = L"unknown";
      unsigned long long rva = 0;
      HMODULE module = nullptr;
      bool mapped = false;
      if (status >= 0 && bytes == sizeof(start) && start &&
          GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
                            reinterpret_cast<LPCWSTR>(start), &module)) {
        ++modules;  // Hold this reference only while copying its name and RVA.
        const DWORD length = GetModuleFileNameW(module, path, 1024);
        const uintptr_t address = reinterpret_cast<uintptr_t>(start);
        const uintptr_t base = reinterpret_cast<uintptr_t>(module);
        if (length > 0 && length < 1024 && address >= base) {
          const wchar_t* slash = std::wcsrchr(path, L'\\');
          name = slash ? slash + 1 : path;
          rva = static_cast<unsigned long long>(address - base);
          mapped = true;
        }
        if (FreeLibrary(module)) ++released;
        else std::fprintf(stderr, "TCP_PRIORITY_THREAD_MODULE_CLOSE mode=%d tid=%lu error=%lu\n",
                          mode, tid, GetLastError());
      }
      std::fprintf(stderr, "TCP_PRIORITY_THREAD_CURRENT mode=%d tid=%lu seen_baseline_tid=%d "
          "birth=%llu times_error=%lu wait=%lu query_status=%lu query_bytes=%lu "
          "mapped=%d module=%ls start_rva=%llu\n", mode, tid, seen,
          static_cast<unsigned long long>(created), times_error, wait,
          static_cast<unsigned long>(status), bytes, mapped, name, rva);
    } else {
      std::fprintf(stderr, "TCP_PRIORITY_THREAD_CURRENT mode=%d tid=%lu skipped_owner_changed=1\n", mode, tid);
    }
    if (CloseHandle(thread)) ++closed;
    else std::fprintf(stderr, "TCP_PRIORITY_THREAD_CLOSE mode=%d tid=%lu error=%lu\n", mode, tid, GetLastError());
  }
  std::fprintf(stderr, "TCP_PRIORITY_THREAD_DIAGNOSTIC_END mode=%d opened=%u closed=%u "
      "module_refs=%u released=%u unclosed_handles=%u unreleased_modules=%u\n",
      mode, opened, closed, modules, released, opened - closed, modules - released);
  std::fflush(stderr);
}
}  // namespace gesher::test
