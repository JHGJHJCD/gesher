// Copyright (C) 2026 The Gesher Authors
// SPDX-License-Identifier: Apache-2.0
// Exercise the real Bionic exports on Windows. The same data-only contract can
// also run directly against Linux libc, without a substitute resize backend.
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#ifdef _WIN32
#include <gesher/bionic_shim.h>
#endif

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#if defined(_WIN32) && defined(__x86_64__)
#define SYSV __attribute__((sysv_abi))
#else
#define SYSV
#endif

namespace {
using Ftruncate = int(SYSV*)(int, int64_t);
using Truncate = int(SYSV*)(const char*, int64_t);
using Error = int*(SYSV*)();
struct Api {
  Ftruncate truncate_fd;
  Ftruncate truncate_fd64;
  Truncate truncate_path;
  Error error;
};
int assertions, failures;

bool Check(const char* name, bool success) {
  ++assertions;
  if (!success) ++failures;
  std::printf("bionic_ftruncate: %s %s\n", name, success ? "PASS" : "FAIL");
  return success;
}

#ifndef _WIN32
int* NativeError() { return &errno; }
#endif

Api Resolve() {
#ifdef _WIN32
  gesher::bionic::InstallBionicShim();
  auto lookup = gesher::bionic::LookupBionicSymbol;
  std::puts("bionic_ftruncate: actual Bionic export route");
  return {reinterpret_cast<Ftruncate>(lookup("ftruncate")),
          reinterpret_cast<Ftruncate>(lookup("ftruncate64")),
          reinterpret_cast<Truncate>(lookup("truncate")),
          reinterpret_cast<Error>(lookup("__errno"))};
#else
  static_assert(sizeof(off_t) == sizeof(int64_t), "64-bit reference offsets");
  std::puts("bionic_ftruncate: Linux libc reference route");
  return {::ftruncate, ::ftruncate64, ::truncate, NativeError};
#endif
}

struct Fixture {
  char path[64] = "gesher-bionic-ftruncate-XXXXXX";
  int rw = -1, alias = -1, wo = -1, ro = -1;
  bool created = false;
  ~Fixture() {
    if (ro >= 0) Check("close read-only witness", close(ro) == 0);
    if (wo >= 0) Check("close write-only witness", close(wo) == 0);
    if (alias >= 0) Check("close duplicate witness", close(alias) == 0);
    if (rw >= 0) Check("close owned file", close(rw) == 0);
    if (created) Check("remove owned file", unlink(path) == 0);
  }
};

// These offsets stay beyond every later EOF. Neither a successful resize nor
// a rejected one may change them, including the description shared by dup().
constexpr off_t kRwCursor = 1024;
constexpr off_t kWoCursor = 999;
constexpr off_t kRoCursor = 888;

void State(const Fixture& file, const std::vector<unsigned char>& expected) {
  struct stat info {};
  Check("exact logical size", fstat(file.rw, &info) == 0 &&
        info.st_size == static_cast<int64_t>(expected.size()));
  std::vector<unsigned char> actual(expected.size(), 0xee);
  Check("retained bytes and zero-filled growth", pread(file.rw, actual.data(),
        actual.size(), 0) == static_cast<ssize_t>(actual.size()) && actual == expected);
  unsigned char byte = 0xee;
  Check("pread at logical EOF returns zero", pread(file.rw, &byte, 1,
        static_cast<off_t>(expected.size())) == 0);
  Check("read/write and dup cursors unchanged",
        lseek(file.rw, 0, SEEK_CUR) == kRwCursor &&
        lseek(file.alias, 0, SEEK_CUR) == kRwCursor);
  Check("independent access-witness cursors unchanged",
        lseek(file.wo, 0, SEEK_CUR) == kWoCursor &&
        lseek(file.ro, 0, SEEK_CUR) == kRoCursor);
}

struct Result { int value, error; };
Result Resize(const Api& api, Ftruncate call, int fd, int64_t length) {
  *api.error() = 0;
  int value = call(fd, length);
  return {value, *api.error()};
}
Result ResizePath(const Api& api, const char* path, int64_t length) {
  *api.error() = 0;
  int value = api.truncate_path(path, length);
  return {value, *api.error()};
}
void Outcome(const char* name, Result result, int expected,
             int error = 0, int alternate_error = -1) {
  Check(name, result.value == expected && (expected == 0 ||
        result.error == error || result.error == alternate_error));
  // This errno belongs to the immediately preceding call, not to a later
  // inspection. A successful call is allowed to leave errno unspecified.
  std::printf("bionic_ftruncate: observed return=%d errno=%d\n",
              result.value, result.error);
}

void Contracts(const Api& api) {
  Fixture file;
  file.rw = mkstemp(file.path);
  file.created = file.rw >= 0;
  if (!Check("create owned relative file", file.created)) return;
  std::vector<unsigned char> expected(333);
  for (size_t i = 0; i < expected.size(); ++i)
    expected[i] = static_cast<unsigned char>(1 + i % 251);
  if (!Check("seed owned file", write(file.rw, expected.data(), expected.size()) ==
             static_cast<ssize_t>(expected.size()))) return;
  file.alias = dup(file.rw);
  file.wo = open(file.path, O_WRONLY);
  file.ro = open(file.path, O_RDONLY);
  if (!Check("open duplicate and access witnesses",
             file.alias >= 0 && file.wo >= 0 && file.ro >= 0)) return;
  if (!Check("position independent and shared cursors",
             lseek(file.rw, kRwCursor, SEEK_SET) == kRwCursor &&
             lseek(file.wo, kWoCursor, SEEK_SET) == kWoCursor &&
             lseek(file.ro, kRoCursor, SEEK_SET) == kRoCursor)) return;
  State(file, expected);

  Outcome("ftruncate read/write same size", Resize(api, api.truncate_fd, file.rw, 333), 0);
  State(file, expected);
  Outcome("ftruncate64 duplicate shrinks", Resize(api, api.truncate_fd64, file.alias, 173), 0);
  expected.resize(173);
  State(file, expected);
  Outcome("ftruncate read/write grows", Resize(api, api.truncate_fd, file.rw, 777), 0);
  expected.resize(777, 0);
  State(file, expected);

  Outcome("ftruncate write-only same size", Resize(api, api.truncate_fd, file.wo, 777), 0);
  State(file, expected);
  Outcome("ftruncate64 write-only shrinks", Resize(api, api.truncate_fd64, file.wo, 111), 0);
  expected.resize(111);
  State(file, expected);
  Outcome("ftruncate write-only grows", Resize(api, api.truncate_fd, file.wo, 389), 0);
  expected.resize(389, 0);
  State(file, expected);

  // POSIX permits EBADF or EINVAL for a descriptor not open for writing.
  // Check unchanged size, bytes and all cursors after each rejected call.
  Outcome("ftruncate read-only same size rejects", Resize(api, api.truncate_fd, file.ro, 389),
          -1, EBADF, EINVAL);
  State(file, expected);
  Outcome("ftruncate64 read-only resize rejects", Resize(api, api.truncate_fd64, file.ro, 200),
          -1, EBADF, EINVAL);
  State(file, expected);
  Outcome("ftruncate negative length rejects", Resize(api, api.truncate_fd, file.rw, -1),
          -1, EINVAL);
  State(file, expected);
  Outcome("ftruncate64 negative length rejects", Resize(api, api.truncate_fd64, file.alias, -1),
          -1, EINVAL);
  State(file, expected);
  Outcome("ftruncate invalid descriptor rejects", Resize(api, api.truncate_fd, -1, 333),
          -1, EBADF);
  State(file, expected);
  Outcome("ftruncate64 invalid descriptor rejects", Resize(api, api.truncate_fd64, -1, 333),
          -1, EBADF);
  State(file, expected);

  // The Bionic path wrapper opens O_WRONLY internally. Keep held descriptors
  // as independent observers so that its cursor cannot mask changes to ours.
  Outcome("truncate path same size", ResizePath(api, file.path, 389), 0);
  State(file, expected);
  Outcome("truncate path shrinks", ResizePath(api, file.path, 71), 0);
  expected.resize(71);
  State(file, expected);
  Outcome("truncate path grows", ResizePath(api, file.path, 293), 0);
  expected.resize(293, 0);
  State(file, expected);
  Outcome("truncate path negative length rejects", ResizePath(api, file.path, -1), -1, EINVAL);
  State(file, expected);
}
}  // namespace

int main() {
  setvbuf(stdout, nullptr, _IONBF, 0);
  Api api = Resolve();
  if (Check("resolve all requested exports", api.truncate_fd && api.truncate_fd64 &&
            api.truncate_path && api.error)) Contracts(api);
  std::printf("bionic_ftruncate: %d assertions, %d failures\n", assertions, failures);
  if (!failures) std::puts("bionic_ftruncate_test all passed");
  return failures ? 1 : 0;
}
