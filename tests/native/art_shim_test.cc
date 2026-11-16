// gesher: the stand-in libart.so (compat/bionic/art_shim.cc, ADR 0086) as a
// packer's shell finds and hooks it, with tests/native/art_hook_test.so doing
// what Ijiami's shell does.
//
//   art_shim_test <art_hook_test.so> <scratch directory>
//
// Checks that the stand-in is written once under the directory and loaded,
// that /proc/self/maps names it at its load address (readable, ELF header
// there), that its LoadClass resolves to the same address through DT_GNU_HASH,
// DT_HASH and the file's .symtab, that an inline hook over its first
// instructions takes effect, and that a call with ART's LoadClass arguments
// (as patches/art/0800 makes it) reaches the hook with DexFile::begin_ and
// ClassDef::class_data_off_ where the shell reads them, then returns through
// the hook's trampoline.

#include <gesher/art_shim.h>
#include <gesher/bionic_shim.h>
#include <gesher/elf_loader.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

#define SYSV __attribute__((sysv_abi))

namespace {

int g_failures = 0;

void Check(const char* what, bool ok, const std::string& detail = "") {
  std::printf("%s  %s%s%s\n", ok ? "PASS" : "FAIL", what, detail.empty() ? "" : ": ",
              detail.c_str());
  if (!ok) ++g_failures;
}

std::string Hex(const void* p) {
  char buf[32];
  std::snprintf(buf, sizeof(buf), "%p", p);
  return buf;
}

// The device's art::DexFile up to begin_, and a dex::ClassDef.
struct FakeDexFile {
  void* vtable;
  const uint8_t* begin;
};

}  // namespace

int main(int argc, char** argv) {
  if (argc != 3) {
    std::fprintf(stderr, "usage: %s <art_hook_test.so> <scratch directory>\n", argv[0]);
    return 2;
  }
  gesher::bionic::InstallBionicShim();

  Check("a packer's shell is recognized (libexec_x86.so, libexecmain.so)",
        gesher::bionic::IsArtHookingPacker("/data/app/x/lib/x86_64/libexec_x86.so") &&
            gesher::bionic::IsArtHookingPacker("C:\\a\\libexecmain.so") &&
            !gesher::bionic::IsArtHookingPacker("/data/app/x/lib/x86_64/libexecutor.so"));

  std::string path, error;
  void* entry = gesher::bionic::LoadArtShim(argv[2], &path, &error);
  Check("the stand-in libart.so loads", entry != nullptr, path + error);
  if (entry == nullptr) return 1;
  const size_t slash = path.find_last_of('/');
  Check("its file is named libart.so",
        slash != std::string::npos && path.substr(slash) == "/libart.so", path);
  std::string again_path, again_error;
  Check("a second load returns the same entry",
        gesher::bionic::LoadArtShim(argv[2], &again_path, &again_error) == entry);

  std::string hook_error;
  gesher::elf::Library* hook = gesher::elf::Load(argv[1], &hook_error);
  Check("art_hook_test.so loads", hook != nullptr, hook_error);
  if (hook == nullptr) return 1;
  auto install = reinterpret_cast<int(SYSV*)(void**, void**, void**)>(
      gesher::elf::Lookup(hook, "gesher_art_hook_install"));
  auto calls = reinterpret_cast<int(SYSV*)()>(gesher::elf::Lookup(hook, "gesher_art_hook_calls"));
  auto returned =
      reinterpret_cast<int(SYSV*)()>(gesher::elf::Lookup(hook, "gesher_art_hook_original_returned"));
  auto last_begin =
      reinterpret_cast<uintptr_t(SYSV*)()>(gesher::elf::Lookup(hook, "gesher_art_hook_last_begin"));
  auto last_off = reinterpret_cast<uint32_t(SYSV*)()>(
      gesher::elf::Lookup(hook, "gesher_art_hook_last_class_data_off"));
  auto last_klass =
      reinterpret_cast<uintptr_t(SYSV*)()>(gesher::elf::Lookup(hook, "gesher_art_hook_last_klass"));
  auto hooked_path =
      reinterpret_cast<const char*(SYSV*)()>(gesher::elf::Lookup(hook, "gesher_art_hook_path"));
  if (!install || !calls || !returned || !last_begin || !last_off || !last_klass || !hooked_path) {
    Check("art_hook_test.so's exports", false);
    return 1;
  }

  void* gnu = nullptr;
  void* sysv = nullptr;
  void* file = nullptr;
  const int installed = install(&gnu, &sysv, &file);
  Check("/proc/self/maps names the stand-in at its base (readable, ELF header)",
        installed != -1, hooked_path());
  Check("the maps line is the stand-in's file", path == hooked_path(), hooked_path());
  Check("LoadClass through DT_GNU_HASH", gnu == entry, Hex(gnu) + " vs " + Hex(entry));
  Check("LoadClass through DT_HASH", sysv == entry, Hex(sysv));
  Check("LoadClass through the file's .symtab", file == entry, Hex(file));
  Check("the inline hook is installed (prologue as expected, writable, restored)", installed == 0,
        std::to_string(installed));

  // ART's call (patches/art/0800): this, Thread*, &dex_file, &class_def, Handle.
  uint8_t dex_bytes[0x70] = {'d', 'e', 'x', '\n', '0', '3', '9', 0};
  FakeDexFile dex = {nullptr, dex_bytes};
  uint8_t class_def[0x20] = {};
  const uint32_t class_data_off = 0x1234;
  std::memcpy(class_def + 0x18, &class_data_off, sizeof(class_data_off));
  using LoadClass = void(SYSV*)(void*, void*, const void*, const void*, uintptr_t);
  for (uintptr_t klass = 0x100; klass < 0x103; ++klass) {
    reinterpret_cast<LoadClass>(entry)(reinterpret_cast<void*>(0x10), reinterpret_cast<void*>(0x20),
                                       &dex, class_def, klass);
  }
  Check("ART's calls reach the hook", calls() == 3, std::to_string(calls()));
  Check("the hook's original (trampoline) returns", returned() == 3, std::to_string(returned()));
  Check("the hook reads DexFile::begin_ at offset 8",
        last_begin() == reinterpret_cast<uintptr_t>(dex_bytes));
  Check("the hook reads ClassDef::class_data_off_ at 0x18", last_off() == class_data_off);
  Check("the Handle arrives by value in the fifth argument", last_klass() == 0x102);

  std::printf(g_failures == 0 ? "art_shim_test all passed\n" : "art_shim_test: %d failed\n",
              g_failures);
  return g_failures == 0 ? 0 : 1;
}
