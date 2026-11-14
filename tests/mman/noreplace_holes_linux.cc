// Linux reference for the exact public-API cases used by mman_probe on Windows.
// c++ -std=c++17 -Wall -Wextra -Werror tests/mman/noreplace_holes_linux.cc -o /tmp/noreplace-holes
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <sys/mman.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <vector>

namespace {
int checks = 0, failures = 0;
size_t kPage = 0;
void Check(const char* what, bool ok) {
  ++checks;
  if (!ok) { ++failures; printf("FAIL %s\n", what); }
}
#include "noreplace_holes.inc"
}  // namespace

int main() {
  const long page = sysconf(_SC_PAGESIZE);
  if (page <= 0) return 1;
  kPage = static_cast<size_t>(page);
  char name[] = "/tmp/gesher-noreplace-XXXXXX";
  const int fd = mkstemp(name);
  if (fd < 0) return 1;
  if (unlink(name) != 0) { close(fd); return 1; }
  std::vector<uint8_t> bytes(3 * kPage);
  for (size_t i = 0; i < bytes.size(); ++i) bytes[i] = static_cast<uint8_t>(i / kPage + 1);
  size_t done = 0;
  while (done < bytes.size()) {
    const ssize_t count = write(fd, bytes.data() + done, bytes.size() - done);
    if (count < 0 && errno == EINTR) continue;
    if (count <= 0) { close(fd); return 1; }
    done += static_cast<size_t>(count);
  }
  NoreplaceHoleContracts(fd);
  Check("noreplace: reference file cleanup", close(fd) == 0);
  printf("mmap noreplace reference: %d checks, %d failed\n", checks, failures);
  return failures == 0 ? 0 : 1;
}
