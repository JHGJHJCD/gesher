// Data-only memfd final-page contracts, shared by Linux, Android and Gesher.
// MAP_FIXED destinations are whole pages of this fixture's private BSS arena:
// the loaded-object mapping path is exercised without touching code, generating
// instructions, or executing mapped data. mmap rounds a short final page up;
// Linux memfd/tmpfs retains shared writes beyond EOF in that page's cache.
// Those bytes must not extend st_size or become readable through pread(EOF).
// Define GESHER_MEMFD_TAIL_STANDALONE for a Linux reference executable.
// GESHER_MEMFD_TAIL_CASE selects one named case; the default runs all cases.
#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

enum { kMaximumPage = 65536, kArenaPages = 4 };
static unsigned char arena[2][kArenaPages * kMaximumPage]
    __attribute__((aligned(kMaximumPage)));
static size_t page_size;
static const char* current_case;
static int failures, assertions;

static int check(const char* name, int okay) {
  ++assertions;
  if (!okay) ++failures;
  printf("memfd_tail: %s: %s %s", current_case, name, okay ? "PASS" : "FAIL");
  if (!okay) printf(" (errno=%d)", errno);
  putchar('\n');
  fflush(stdout);
  return okay;
}

static int create_file(size_t logical) {
  int fd = (int)syscall(SYS_memfd_create, "gesher-tail-contract", MFD_CLOEXEC);
  if (!check("create memfd", fd >= 0)) return -1;
  if (!check("set logical size", ftruncate(fd, (off_t)logical) == 0)) {
    close(fd);
    return -1;
  }
  return fd;
}

static unsigned char* map_file(int fd, int slot, size_t length, int prot, int flags) {
  void* address = slot < 0 ? NULL : arena[slot];
  void* result = mmap(address, length, prot, flags | (slot < 0 ? 0 : MAP_FIXED), fd, 0);
  if (!check(slot < 0 ? "map at a fresh address" : "map at owned BSS destination",
             result != MAP_FAILED && (slot < 0 || result == address))) {
    if (result != MAP_FAILED) munmap(result, length);
    return NULL;
  }
  return (unsigned char*)result;
}

static int unmap_file(unsigned char* mapping, size_t length) {
  return check("unmap requested range", munmap(mapping, length) == 0);
}

static void close_file(int fd) {
  if (fd >= 0) check("close descriptor", close(fd) == 0);
}

static void check_file_shape(int fd, size_t logical) {
  struct stat info;
  check("logical file size is unchanged", fstat(fd, &info) == 0 &&
        info.st_size == (off_t)logical);
  unsigned char sentinel = 0xa7;
  check("pread at logical EOF returns zero without touching output",
        pread(fd, &sentinel, 1, (off_t)logical) == 0 && sentinel == 0xa7);
}

static unsigned char pattern(size_t offset, unsigned seed) {
  return (unsigned char)(1 + (offset * 17 + seed) % 251);
}

static void fill_tail(unsigned char* mapping, size_t logical, size_t rounded,
                       unsigned seed) {
  for (size_t at = logical; at < rounded; ++at) mapping[at] = pattern(at, seed);
}

static int tail_matches(const unsigned char* mapping, size_t logical,
                         size_t rounded, unsigned seed) {
  for (size_t at = logical; at < rounded; ++at) {
    if (mapping[at] != pattern(at, seed)) {
      printf("memfd_tail: %s: first mismatch at file offset %zu: got=%u expected=%u\n",
             current_case, at, (unsigned)mapping[at], (unsigned)pattern(at, seed));
      return 0;
    }
  }
  return 1;
}

static int is_zero(const unsigned char* bytes, size_t length) {
  for (size_t at = 0; at < length; ++at) if (bytes[at] != 0) return 0;
  return 1;
}

static void round_trip(int rounded_request, int slot) {
  const size_t logical = 2 * page_size + 173, rounded = 3 * page_size;
  const size_t request = rounded_request ? rounded : logical;
  const unsigned seed = rounded_request ? 43 : 19;
  int fd = create_file(logical);
  if (fd < 0) return;
  unsigned char* p = map_file(fd, slot, request, PROT_READ | PROT_WRITE, MAP_SHARED);
  if (p == NULL) { close_file(fd); return; }
  check("new final-page tail is zero", is_zero(p + logical, rounded - logical));
  p[logical - 1] = 0x6d;
  fill_tail(p, logical, rounded, seed);
  check("msync accepts the non-page-multiple logical length", msync(p, logical, MS_SYNC) == 0);
  check_file_shape(fd, logical);
  unsigned char last = 0;
  check("shared bytes before EOF reach the file",
        pread(fd, &last, 1, (off_t)logical - 1) == 1 && last == 0x6d);
  unmap_file(p, request);
  check_file_shape(fd, logical);

  p = map_file(fd, slot, request, PROT_READ | PROT_WRITE, MAP_PRIVATE);
  if (p == NULL) { close_file(fd); return; }
  check("private remap retains the entire shared final-page tail",
        tail_matches(p, logical, rounded, seed));
  p[logical] ^= 0xff;
  p[logical - 1] = 0x29;
  check("private mapping msync succeeds", msync(p, logical, MS_SYNC) == 0);
  unmap_file(p, request);

  p = map_file(fd, slot, request, PROT_READ, MAP_PRIVATE);
  if (p != NULL) {
    check("private changes stay private after another remap",
          p[logical - 1] == 0x6d && tail_matches(p, logical, rounded, seed));
    unmap_file(p, request);
  }
  check_file_shape(fd, logical);
  close_file(fd);
}

static void aliases_and_reuse(void) {
  const size_t logical = 2 * page_size + 211, rounded = 3 * page_size;
  int original = create_file(logical);
  if (original < 0) return;
  int alias = dup(original);
  if (!check("duplicate descriptor", alias >= 0)) { close_file(original); return; }
  unsigned char* first = map_file(original, 0, logical, PROT_READ | PROT_WRITE, MAP_SHARED);
  if (first == NULL) { close_file(alias); close_file(original); return; }
  fill_tail(first, logical, rounded, 29);
  check("sync original through its live mapping", msync(first, logical, MS_SYNC) == 0);
  check_file_shape(alias, logical);
  close_file(original);

  // Same diagnostic name and descriptor number, but a distinct underlying file.
  int other = create_file(logical);
  if (other >= 0 && other != original) {
    int reused = dup2(other, original);
    close_file(other);
    other = reused;
  }
  if (!check("reuse the closed integer for a different memfd", other == original)) {
    unmap_file(first, logical); close_file(alias); close_file(other); return;
  }
  unsigned char* second = map_file(other, 1, logical, PROT_READ | PROT_WRITE, MAP_SHARED);
  if (second == NULL) {
    unmap_file(first, logical); close_file(alias); close_file(other); return;
  }
  check("same name and reused fd do not inherit another file's tail",
        is_zero(second + logical, rounded - logical));
  fill_tail(second, logical, rounded, 97);
  check("sync replacement file", msync(second, logical, MS_SYNC) == 0);
  unmap_file(second, logical);
  unmap_file(first, logical);

  first = map_file(alias, 0, logical, PROT_READ, MAP_PRIVATE);
  if (first != NULL) {
    check("dup retains original file identity after fd reuse", tail_matches(first, logical, rounded, 29));
    close_file(alias); alias = -1;
    check("mapping lives after its final descriptor closes", tail_matches(first, logical, rounded, 29));
    unmap_file(first, logical);
  }
  second = map_file(other, 1, logical, PROT_READ, MAP_PRIVATE);
  if (second != NULL) {
    check("replacement file keeps its distinct tail", tail_matches(second, logical, rounded, 97));
    unmap_file(second, logical);
  }
  check_file_shape(other, logical);
  close_file(alias);
  close_file(other);
}

static void truncate_cache(void) {
  const size_t logical = 2 * page_size + 257, rounded = 3 * page_size;
  int fd = create_file(logical);
  if (fd < 0) return;
  unsigned char* p = map_file(fd, 0, logical, PROT_READ | PROT_WRITE, MAP_SHARED);
  if (p == NULL) { close_file(fd); return; }
  p[0] = 0x63;
  fill_tail(p, logical, rounded, 61);
  unmap_file(p, logical);  // Writeback is required even without msync.
  check_file_shape(fd, logical);
  // Linux truncation clears a partial page's bytes beyond the requested size,
  // including a request that leaves the logical size unchanged.
  check("same-size ftruncate succeeds", ftruncate(fd, (off_t)logical) == 0);
  p = map_file(fd, 0, logical, PROT_READ, MAP_PRIVATE);
  if (p != NULL) {
    check("same-size ftruncate clears cached tail but retains file data",
          p[0] == 0x63 && is_zero(p + logical, rounded - logical));
    unmap_file(p, logical);
  }
  p = map_file(fd, 0, logical, PROT_READ | PROT_WRITE, MAP_SHARED);
  if (p == NULL) { close_file(fd); return; }
  memset(p + logical - 37, 0x4b, 37);
  fill_tail(p, logical, rounded, 83);
  unmap_file(p, logical);
  check("shrink within final page succeeds", ftruncate(fd, (off_t)logical - 37) == 0);
  check_file_shape(fd, logical - 37);
  check("regrow after shrink succeeds", ftruncate(fd, (off_t)logical) == 0);
  p = map_file(fd, 0, logical, PROT_READ, MAP_PRIVATE);
  if (p != NULL) {
    check("shrink clears removed bytes and cached tail before regrowth",
          p[0] == 0x63 && is_zero(p + logical - 37, rounded - logical + 37));
    unmap_file(p, logical);
  }
  check_file_shape(fd, logical);
  close_file(fd);
}

static void noaccess_mapping(void) {
  const size_t logical = 2 * page_size + 191, rounded = 3 * page_size;
  int fd = create_file(logical);
  if (fd < 0) return;
  unsigned char* p = map_file(fd, 0, logical, PROT_NONE, MAP_SHARED);
  if (p == NULL) { close_file(fd); return; }
  check("initial PROT_NONE mapping can sync", msync(p, logical, MS_SYNC) == 0);
  unmap_file(p, logical);
  check_file_shape(fd, logical);

  p = map_file(fd, 0, logical, PROT_READ | PROT_WRITE, MAP_SHARED);
  if (p == NULL) { close_file(fd); return; }
  p[0] = 0x35;
  p[logical - 1] = 0x67;
  fill_tail(p, logical, rounded, 101);
  if (!check("written mapping can become PROT_NONE", mprotect(p, logical, PROT_NONE) == 0)) {
    unmap_file(p, logical); close_file(fd); return;
  }
  // No fixture byte is read or written while this mapping is inaccessible.
  check("written PROT_NONE mapping can sync", msync(p, logical, MS_SYNC) == 0);
  unmap_file(p, logical);
  check_file_shape(fd, logical);
  p = map_file(fd, 0, logical, PROT_READ, MAP_PRIVATE);
  if (p == NULL) { close_file(fd); return; }
  check("writes before PROT_NONE retain file bytes and cached tail",
        p[0] == 0x35 && p[logical - 1] == 0x67 && tail_matches(p, logical, rounded, 101));
  unmap_file(p, logical);

  p = map_file(fd, 0, logical, PROT_NONE, MAP_SHARED);
  if (p == NULL) { close_file(fd); return; }
  if (!check("initial PROT_NONE mapping can become writable",
             mprotect(p, logical, PROT_READ | PROT_WRITE) == 0)) {
    unmap_file(p, logical); close_file(fd); return;
  }
  p[0] = 0x49;
  p[logical - 1] = 0x7b;
  fill_tail(p, logical, rounded, 139);
  if (!check("rewritten mapping can become PROT_NONE again", mprotect(p, logical, PROT_NONE) == 0)) {
    unmap_file(p, logical); close_file(fd); return;
  }
  check("rewritten PROT_NONE mapping can sync", msync(p, logical, MS_SYNC) == 0);
  unmap_file(p, logical);
  check_file_shape(fd, logical);
  p = map_file(fd, 0, logical, PROT_READ, MAP_PRIVATE);
  if (p != NULL) {
    check("writes after re-enabling access replace prior file bytes and tail",
          p[0] == 0x49 && p[logical - 1] == 0x7b && tail_matches(p, logical, rounded, 139));
    unmap_file(p, logical);
  }
  close_file(fd);
}

static void partial_unmap(void) {
  const size_t logical = 2 * page_size + 307, rounded = 3 * page_size;
  int fd = create_file(logical);
  if (fd < 0) return;
  unsigned char* p = map_file(fd, 0, logical, PROT_READ | PROT_WRITE, MAP_SHARED);
  if (p == NULL) { close_file(fd); return; }
  fill_tail(p, logical, rounded, 73);
  if (!unmap_file(p, page_size)) { unmap_file(p, logical); close_file(fd); return; }
  check("unmapping prefix leaves the final page live", tail_matches(p, logical, rounded, 73));
  fill_tail(p, logical, rounded, 113);
  check("sync surviving suffix", msync(p + page_size, logical - page_size, MS_SYNC) == 0);
  unmap_file(p + page_size, logical - page_size);
  check_file_shape(fd, logical);
  // A fresh private address also checks the route outside a loaded object.
  // No access to the unmapped prefix/suffix occurs in this fixture.
  p = map_file(fd, -1, logical, PROT_READ, MAP_PRIVATE);
  if (p != NULL) {
    check("split mapping retains offsets and final tail on a fresh remap",
          tail_matches(p, logical, rounded, 113));
    unmap_file(p, logical);
  }
  close_file(fd);
}

static void logical_length(void) { round_trip(0, 0); }
static void rounded_length(void) { round_trip(1, 0); }
static void nonfixed(void) { round_trip(0, -1); }

__attribute__((visibility("default"))) int gesher_selftest(void) {
  struct Case { const char* name; void (*run)(void); };
  static const struct Case cases[] = {
      {"logical-length", logical_length}, {"rounded-length", rounded_length},
      {"nonfixed", nonfixed}, {"aliases-reused-fd", aliases_and_reuse},
      {"truncate", truncate_cache}, {"noaccess", noaccess_mapping},
      {"partial-unmap", partial_unmap}};
  failures = assertions = 0;
  current_case = "setup";
  long page = sysconf(_SC_PAGESIZE);
  if (!check("page size fits dedicated BSS arena", page >= 1024 && page <= kMaximumPage &&
             (page & (page - 1)) == 0)) return failures;
  page_size = (size_t)page;
  const char* selected = getenv("GESHER_MEMFD_TAIL_CASE");
  int ran = 0;
  for (size_t i = 0; i < sizeof(cases) / sizeof(cases[0]); ++i) {
    if (selected != NULL && strcmp(selected, cases[i].name) != 0) continue;
    current_case = cases[i].name;
    cases[i].run();
    ++ran;
  }
  current_case = "summary";
  check("selected at least one known case", ran > 0);
  printf("memfd_tail: %d assertions, %d failures\n", assertions, failures);
  if (failures == 0) printf("memfd_tail: all passed\n");
  fflush(stdout);
  return failures;
}

#ifdef GESHER_MEMFD_TAIL_STANDALONE
int main(void) { return gesher_selftest() == 0 ? 0 : 1; }
#endif
