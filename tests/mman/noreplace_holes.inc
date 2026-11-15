// Shared public-API contracts for the Windows probe and Linux reference.
// The caller supplies Check, kPage, mmap/mremap/munmap, and a file whose first
// three pages contain bytes 1, 2 and 3 respectively. No registry internals are
// observed: a retained Windows placeholder must behave like a Linux VMA hole.

bool NoreplaceBytes(const uint8_t* bytes, size_t length, uint8_t value) {
  for (size_t i = 0; i < length; ++i) if (bytes[i] != value) return false;
  return true;
}

void NoreplaceArtMove() {
  auto* source = static_cast<uint8_t*>(mmap(nullptr, 8 * kPage, PROT_READ | PROT_WRITE,
                                           MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
  auto* destination = static_cast<uint8_t*>(mmap(nullptr, 2 * kPage, PROT_NONE,
                                                MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
  Check("noreplace: ART source and destination reservations", source != MAP_FAILED && destination != MAP_FAILED);
  if (source == MAP_FAILED || destination == MAP_FAILED) {
    if (source != MAP_FAILED) munmap(source, 8 * kPage);
    if (destination != MAP_FAILED) munmap(destination, 2 * kPage);
    return;
  }
  memset(source, 0x37, 2 * kPage);
  memset(source + 2 * kPage, 0x5a, 6 * kPage);
  // ART's fallback moves only its used prefix, without MREMAP_DONTUNMAP,
  // then recreates that prefix without permission to replace any live VMA.
  void* moved = mremap(source, 2 * kPage, 2 * kPage, MREMAP_MAYMOVE | MREMAP_FIXED, destination);
  Check("noreplace: ART partial move retains destination bytes", moved == destination &&
        NoreplaceBytes(destination, 2 * kPage, 0x37));
  Check("noreplace: ART partial move retains the unmoved tail",
        NoreplaceBytes(source + 2 * kPage, 6 * kPage, 0x5a));
  if (moved == destination) {
    void* recreated = mmap(source, 2 * kPage, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
    Check("noreplace: ART recreates its unmapped prefix at the exact address", recreated == source);
    if (recreated == source) {
      Check("noreplace: ART recreated prefix is fully zero-filled", NoreplaceBytes(source, 2 * kPage, 0));
    }
    for (int cycle = 0; cycle < 3; ++cycle) {
      Check("noreplace: repeated prefix unmap", munmap(source, 2 * kPage) == 0);
      void* none = mmap(source, 2 * kPage, PROT_NONE,
                        MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
      Check("noreplace: PROT_NONE fills a logical hole", none == source);
      if (none != source) continue;
      errno = 0;
      void* collision = mmap(source, kPage, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
      Check("noreplace: a live PROT_NONE mapping still collides", collision == MAP_FAILED && errno == EEXIST);
      const bool writable = mprotect(source, 2 * kPage, PROT_READ | PROT_WRITE) == 0;
      Check("noreplace: reused PROT_NONE pages become fresh writable zero pages",
            writable && NoreplaceBytes(source, 2 * kPage, 0));
      if (writable) memset(source, 0x69, 2 * kPage);
    }
    Check("noreplace: repeated prefix reuse preserves the unmoved tail",
          NoreplaceBytes(source + 2 * kPage, 6 * kPage, 0x5a));
  }
  Check("noreplace: ART source cleanup", munmap(source, 8 * kPage) == 0);
  Check("noreplace: ART destination cleanup", munmap(destination, 2 * kPage) == 0);
}

void NoreplaceAdjacentHoles() {
  auto* p = static_cast<uint8_t*>(mmap(nullptr, 8 * kPage, PROT_READ | PROT_WRITE,
                                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
  Check("noreplace: adjacent-hole reservation", p != MAP_FAILED);
  if (p == MAP_FAILED) return;
  memset(p, 0x4d, 8 * kPage);
  Check("noreplace: first separate hole", munmap(p + 2 * kPage, kPage) == 0);
  Check("noreplace: second adjacent hole", munmap(p + 3 * kPage, 2 * kPage) == 0);
  void* joined = mmap(p + 2 * kPage, 3 * kPage, PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
  Check("noreplace: one mapping fills several adjacent holes", joined == p + 2 * kPage);
  if (joined == p + 2 * kPage) {
    Check("noreplace: joined holes are fully zero-filled", NoreplaceBytes(p + 2 * kPage, 3 * kPage, 0));
  }
  Check("noreplace: joining holes retains both live neighbours",
        NoreplaceBytes(p, 2 * kPage, 0x4d) && NoreplaceBytes(p + 5 * kPage, 3 * kPage, 0x4d));
  Check("noreplace: adjacent-hole cleanup", munmap(p, 8 * kPage) == 0);
}

void NoreplaceAtomicCollisions(int fd) {
  // Test both anonymous/file dispatch and flag precedence. NOREPLACE must
  // reject the entire hole-plus-live range even when MAP_FIXED is also set.
  for (int file = 0; file != 2; ++file) {
    for (int fixed = 0; fixed != 2; ++fixed) {
      auto* p = static_cast<uint8_t*>(mmap(nullptr, 5 * kPage, PROT_READ | PROT_WRITE,
                                          MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
      Check("noreplace: collision reservation", p != MAP_FAILED);
      if (p == MAP_FAILED) continue;
      memset(p, 0x64, 5 * kPage);
      Check("noreplace: collision leaves a hole beside a live page", munmap(p + kPage, kPage) == 0);
      const int flags = MAP_PRIVATE | MAP_FIXED_NOREPLACE | (fixed ? MAP_FIXED : 0) |
                        (file ? 0 : MAP_ANONYMOUS);
      errno = 0;
      void* collision = mmap(p + kPage, 2 * kPage, PROT_READ | PROT_WRITE, flags, file ? fd : -1, 0);
      Check(file ? "noreplace: file hole-plus-live collision is EEXIST"
                 : "noreplace: anonymous hole-plus-live collision is EEXIST",
            collision == MAP_FAILED && errno == EEXIST);
      Check("noreplace: rejected collision does not mutate live bytes",
            NoreplaceBytes(p, kPage, 0x64) && NoreplaceBytes(p + 2 * kPage, 3 * kPage, 0x64));
      if (collision == MAP_FAILED) {
        void* hole = mmap(p + kPage, kPage, PROT_READ | PROT_WRITE,
                          MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
        Check("noreplace: rejected collision leaves the hole available", hole == p + kPage);
        if (hole == p + kPage) {
          Check("noreplace: the unaffected hole still supplies zero pages", NoreplaceBytes(p + kPage, kPage, 0));
        }
      }
      Check("noreplace: collision cleanup", munmap(p, 5 * kPage) == 0);
    }
  }
}

void NoreplacePrivateFile(int fd) {
  auto* p = static_cast<uint8_t*>(mmap(nullptr, 8 * kPage, PROT_READ | PROT_WRITE,
                                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
  Check("noreplace: private-file reservation", p != MAP_FAILED);
  if (p == MAP_FAILED) return;
  memset(p, 0x72, 8 * kPage);
  Check("noreplace: private-file first hole", munmap(p + kPage, kPage) == 0);
  Check("noreplace: private-file adjacent hole", munmap(p + 2 * kPage, 2 * kPage) == 0);
  void* mapped = mmap(p + kPage, 3 * kPage, PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_FIXED_NOREPLACE, fd, 0);
  Check("noreplace: private file fills several logical holes", mapped == p + kPage);
  if (mapped == p + kPage) {
    Check("noreplace: private file supplies the requested bytes",
          NoreplaceBytes(p + kPage, kPage, 1) && NoreplaceBytes(p + 2 * kPage, kPage, 2) &&
          NoreplaceBytes(p + 3 * kPage, kPage, 3));
    p[kPage] = 99;
    auto* original = static_cast<uint8_t*>(mmap(nullptr, kPage, PROT_READ, MAP_SHARED, fd, 0));
    Check("noreplace: private-file writes preserve the backing file", original != MAP_FAILED && original[0] == 1);
    if (original != MAP_FAILED) Check("noreplace: backing-file witness cleanup", munmap(original, kPage) == 0);
    Check("noreplace: private-file unmap restores logical holes", munmap(p + kPage, 3 * kPage) == 0);
    void* anonymous = mmap(p + kPage, 3 * kPage, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
    Check("noreplace: anonymous mapping reuses former file holes", anonymous == p + kPage);
    if (anonymous == p + kPage) {
      Check("noreplace: former file holes supply fresh zeros", NoreplaceBytes(p + kPage, 3 * kPage, 0));
    }
  }
  Check("noreplace: private-file mapping preserves its neighbours",
        NoreplaceBytes(p, kPage, 0x72) && NoreplaceBytes(p + 4 * kPage, 4 * kPage, 0x72));
  Check("noreplace: private-file cleanup", munmap(p, 8 * kPage) == 0);
}

void NoreplaceHoleContracts(int fd) {
  NoreplaceArtMove();
  NoreplaceAdjacentHoles();
  NoreplaceAtomicCollisions(fd);
  NoreplacePrivateFile(fd);
}
