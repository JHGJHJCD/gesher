// gesher: what Ijiami's native shell does with libart.so (ADR 0086), against
// Gesher's stand-in (compat/bionic/art_shim.cc), built with the NDK.
//
// gesher_art_hook_install() finds a mapping of a file named libart.so in
// /proc/self/maps (readable, its ELF header at the start, as the shell checks),
// resolves art::ClassLinker::LoadClass through the loaded object's dynamic
// segment (DT_GNU_HASH and DT_HASH) and through the file's .symtab, and hooks
// it inline: a 14-byte absolute jump over its first whole instructions, which
// a trampoline runs before jumping back. The hook reads the arguments as the
// shell's does: DexFile::begin_ at offset 8 of the DexFile, and
// ClassDef::class_data_off_ at offset 0x18 of the ClassDef, before calling the
// original. Exported for tests/native/art_shim_test.cc.

#include <elf.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define LOAD_CLASS                                                                  \
  "_ZN3art11ClassLinker9LoadClassEPNS_6ThreadERKNS_7DexFileERKNS_3dex8ClassDefENS_" \
  "6HandleINS_6mirror5ClassEEE"

// Whole instructions the jump displaces: the stand-in's prologue (13 bytes)
// and its first 8-byte NOP. Checked against the bytes before hooking.
#define DISPLACED 21
static const uint8_t kExpectedStart[DISPLACED] = {
    0x55, 0x48, 0x89, 0xe5, 0x53, 0x41, 0x54, 0x41, 0x55, 0x41, 0x56,
    0x41, 0x57, 0x0f, 0x1f, 0x84, 0x00, 0x00, 0x00, 0x00, 0x00};

typedef void (*LoadClassFn)(void* linker, void* self, const void* dex_file,
                            const void* class_def, uintptr_t klass);

static LoadClassFn g_original;
static int g_calls;
static int g_original_returned;
static uintptr_t g_last_begin;
static uint32_t g_last_class_data_off;
static uintptr_t g_last_klass;
static char g_path[512];

static uint32_t GnuHash(const char* name) {
  uint32_t h = 5381;
  for (const unsigned char* p = (const unsigned char*)name; *p; ++p) h += (h << 5) + *p;
  return h;
}

static uint32_t SysvHash(const char* name) {
  uint32_t h = 0, g;
  for (const unsigned char* p = (const unsigned char*)name; *p; ++p) {
    h = (h << 4) + *p;
    g = h & 0xf0000000u;
    if (g) h ^= g >> 24;
    h &= ~g;
  }
  return h;
}

// The stand-in's base from /proc/self/maps: the line naming .../libart.so at
// offset 0, readable, with an ELF header there.
static uint8_t* FindLibart(void) {
  FILE* f = fopen("/proc/self/maps", "r");
  if (!f) return NULL;
  char line[1024];
  uint8_t* base = NULL;
  while (fgets(line, sizeof line, f)) {
    if (!strstr(line, "/libart.so")) continue;
    unsigned long start, end, offset;
    char perms[8], path[512];
    if (sscanf(line, "%lx-%lx %7s %lx %*s %*s %511s", &start, &end, perms, &offset, path) != 5) {
      continue;
    }
    if (perms[0] != 'r' || offset != 0) continue;
    if (memcmp((const void*)start, ELFMAG, SELFMAG) != 0) continue;
    base = (uint8_t*)start;
    snprintf(g_path, sizeof g_path, "%s", path);
    break;
  }
  fclose(f);
  return base;
}

// LOAD_CLASS through the dynamic segment of the object at `base`; `gnu`
// chooses DT_GNU_HASH, else DT_HASH.
static void* ResolveInMemory(uint8_t* base, int gnu) {
  const Elf64_Ehdr* eh = (const Elf64_Ehdr*)base;
  const Elf64_Phdr* ph = (const Elf64_Phdr*)(base + eh->e_phoff);
  uintptr_t bias = (uintptr_t)-1;
  const Elf64_Dyn* dyn = NULL;
  for (int i = 0; i < eh->e_phnum; ++i) {
    if (ph[i].p_type == PT_LOAD && bias == (uintptr_t)-1) {
      bias = (uintptr_t)base - (ph[i].p_vaddr - ph[i].p_offset);
    }
  }
  for (int i = 0; i < eh->e_phnum; ++i) {
    if (ph[i].p_type == PT_DYNAMIC) dyn = (const Elf64_Dyn*)(bias + ph[i].p_vaddr);
  }
  if (!dyn) return NULL;
  const Elf64_Sym* symtab = NULL;
  const char* strtab = NULL;
  const uint32_t* hash = NULL;
  const uint32_t* gnu_hash = NULL;
  for (; dyn->d_tag != DT_NULL; ++dyn) {
    switch (dyn->d_tag) {
      case DT_SYMTAB: symtab = (const Elf64_Sym*)(bias + dyn->d_un.d_ptr); break;
      case DT_STRTAB: strtab = (const char*)(bias + dyn->d_un.d_ptr); break;
      case DT_HASH: hash = (const uint32_t*)(bias + dyn->d_un.d_ptr); break;
      case DT_GNU_HASH: gnu_hash = (const uint32_t*)(bias + dyn->d_un.d_ptr); break;
    }
  }
  if (!symtab || !strtab) return NULL;
  if (gnu) {
    if (!gnu_hash) return NULL;
    uint32_t nbuckets = gnu_hash[0], symoffset = gnu_hash[1];
    uint32_t bloom_size = gnu_hash[2], bloom_shift = gnu_hash[3];
    const uint64_t* bloom = (const uint64_t*)(gnu_hash + 4);
    const uint32_t* buckets = (const uint32_t*)(bloom + bloom_size);
    const uint32_t* chain = buckets + nbuckets;
    uint32_t h = GnuHash(LOAD_CLASS);
    uint64_t word = bloom[(h / 64) % bloom_size];
    if (!((word >> (h % 64)) & (word >> ((h >> bloom_shift) % 64)) & 1)) return NULL;
    uint32_t i = buckets[h % nbuckets];
    if (i < symoffset) return NULL;
    for (;; ++i) {
      uint32_t ch = chain[i - symoffset];
      if ((ch | 1) == (h | 1) && strcmp(strtab + symtab[i].st_name, LOAD_CLASS) == 0) {
        return (void*)(bias + symtab[i].st_value);
      }
      if (ch & 1) return NULL;
    }
  }
  if (!hash) return NULL;
  uint32_t nbucket = hash[0];
  const uint32_t* bucket = hash + 2;
  const uint32_t* chain = bucket + nbucket;
  for (uint32_t i = bucket[SysvHash(LOAD_CLASS) % nbucket]; i != 0; i = chain[i]) {
    if (strcmp(strtab + symtab[i].st_name, LOAD_CLASS) == 0) {
      return (void*)(bias + symtab[i].st_value);
    }
  }
  return NULL;
}

// LOAD_CLASS from the file's .symtab, relative to `base`.
static void* ResolveFromFile(uint8_t* base) {
  int fd = open(g_path, O_RDONLY);
  if (fd < 0) return NULL;
  struct stat st;
  void* result = NULL;
  if (fstat(fd, &st) == 0 && st.st_size > 0) {
    uint8_t* data = (uint8_t*)malloc((size_t)st.st_size);
    if (data && read(fd, data, (size_t)st.st_size) == st.st_size) {
      const Elf64_Ehdr* eh = (const Elf64_Ehdr*)data;
      const Elf64_Shdr* sh = (const Elf64_Shdr*)(data + eh->e_shoff);
      for (int i = 0; i < eh->e_shnum && !result; ++i) {
        if (sh[i].sh_type != SHT_SYMTAB) continue;
        const Elf64_Sym* sym = (const Elf64_Sym*)(data + sh[i].sh_offset);
        const char* str = (const char*)(data + sh[sh[i].sh_link].sh_offset);
        for (size_t j = 0; j < sh[i].sh_size / sizeof(Elf64_Sym); ++j) {
          if (strcmp(str + sym[j].st_name, LOAD_CLASS) == 0) {
            result = base + sym[j].st_value;
            break;
          }
        }
      }
    }
    free(data);
  }
  close(fd);
  return result;
}

static void Hook(void* linker, void* self, const void* dex_file, const void* class_def,
                 uintptr_t klass) {
  g_last_begin = ((const uintptr_t*)dex_file)[1];                      // DexFile::begin_
  memcpy(&g_last_class_data_off, (const uint8_t*)class_def + 0x18, 4);  // class_data_off_
  g_last_klass = klass;
  ++g_calls;
  g_original(linker, self, dex_file, class_def, klass);
  ++g_original_returned;
}

// Returns 0 when hooked; a negative step number otherwise. *memory_gnu,
// *memory_sysv and *file receive the three resolutions.
int gesher_art_hook_install(void** memory_gnu, void** memory_sysv, void** file) {
  uint8_t* base = FindLibart();
  if (!base) return -1;
  void* target = ResolveInMemory(base, 1);
  *memory_gnu = target;
  *memory_sysv = ResolveInMemory(base, 0);
  *file = ResolveFromFile(base);
  if (!target) return -2;
  if (memcmp(target, kExpectedStart, DISPLACED) != 0) return -3;

  // The trampoline: the displaced instructions, then a jump back past them.
  uint8_t* tramp = (uint8_t*)mmap(NULL, 4096, PROT_READ | PROT_WRITE | PROT_EXEC,
                                  MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (tramp == MAP_FAILED) return -4;
  memcpy(tramp, target, DISPLACED);
  uint8_t* back = tramp + DISPLACED;
  back[0] = 0xff;
  back[1] = 0x25;  // jmp qword [rip+0]
  memset(back + 2, 0, 4);
  uintptr_t resume = (uintptr_t)target + DISPLACED;
  memcpy(back + 6, &resume, 8);
  g_original = (LoadClassFn)(void*)tramp;

  // The jump over the target's first bytes.
  uintptr_t page = (uintptr_t)target & ~(uintptr_t)4095;
  size_t len = ((uintptr_t)target + 14 - page + 4095) & ~(size_t)4095;
  if (mprotect((void*)page, len, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) return -5;
  uint8_t jump[14] = {0xff, 0x25, 0, 0, 0, 0};
  uintptr_t hook = (uintptr_t)&Hook;
  memcpy(jump + 6, &hook, 8);
  memcpy(target, jump, sizeof jump);
  if (mprotect((void*)page, len, PROT_READ | PROT_EXEC) != 0) return -6;
  __builtin___clear_cache((char*)target, (char*)target + 14);
  return 0;
}

int gesher_art_hook_calls(void) { return g_calls; }
int gesher_art_hook_original_returned(void) { return g_original_returned; }
uintptr_t gesher_art_hook_last_begin(void) { return g_last_begin; }
uint32_t gesher_art_hook_last_class_data_off(void) { return g_last_class_data_off; }
uintptr_t gesher_art_hook_last_klass(void) { return g_last_klass; }
const char* gesher_art_hook_path(void) { return g_path; }
