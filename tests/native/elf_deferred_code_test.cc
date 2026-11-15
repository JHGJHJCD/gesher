// The ELF loader's raw system calls and its rewrite of code made at run time
// (compat/elf/fs_rewrite.h, ADR 0084), on hand-assembled code in fake objects
// registered with the real rewriter: no NDK library needed.
//
//  1. A `syscall` in an object's code becomes ud2 at load and is emulated
//     through the handler SetRawSyscallHandler installs (number and the six
//     argument registers in, the result in %rax).
//  2. Code written into an object after its load and made executable with
//     ProtectLoadedMemory stays non-executable until it runs; then its raw
//     system call and its %fs:0x28 read are rewritten and it runs.
//  3. A self-unpacking object (its .eh_frame_hdr outside its file bytes): the
//     load rewrites none of its bytes (they are "compressed"); its initializer
//     runs once DeferUnpackedCode has made its pages wait, maps fresh pages
//     over its image with MapLoadedMemory (MAP_FIXED, as UPX does), writes
//     code there and makes it executable; that code is rewritten as it runs,
//     including a system call stub written at run time and entered by `ret`.
//
// Prints "elf_deferred_code_test all passed" and exits 0 on success.

#include <elf.h>
#include <sys/mman.h>
#include <windows.h>

#include <gesher/elf_loader.h>

#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "fs_rewrite.h"

namespace elf = gesher::elf;

#define SYSV __attribute__((sysv_abi))

namespace {

int failures = 0;
void Check(const char* what, bool ok) {
  std::printf("%-72s %s\n", what, ok ? "ok" : "FAIL");
  if (!ok) ++failures;
}

constexpr size_t kPage = 4096;

// The handler: number * 1000 + first argument; the six arguments recorded.
int64_t g_args[6];
int64_t RawHandler(int64_t nr, const int64_t args[6]) {
  std::memcpy(g_args, args, sizeof(g_args));
  return nr * 1000 + args[0];
}

// A minimal ELF image: one executable PT_LOAD of `file_pages` pages that
// spans `pages`, and optionally a PT_GNU_EH_FRAME at `eh_vaddr`.
struct FakeFile {
  alignas(Elf64_Ehdr) std::array<uint8_t, sizeof(Elf64_Ehdr) + 2 * sizeof(Elf64_Phdr)> bytes{};
  FakeFile(size_t file_pages, size_t pages, uint64_t eh_vaddr) {
    auto* header = reinterpret_cast<Elf64_Ehdr*>(bytes.data());
    header->e_phoff = sizeof(Elf64_Ehdr);
    header->e_phnum = eh_vaddr != 0 ? 2 : 1;
    auto* segment = reinterpret_cast<Elf64_Phdr*>(bytes.data() + header->e_phoff);
    segment[0].p_type = PT_LOAD;
    segment[0].p_flags = PF_R | PF_X;
    segment[0].p_filesz = file_pages * kPage;
    segment[0].p_memsz = pages * kPage;
    if (eh_vaddr != 0) {
      segment[1].p_type = PT_GNU_EH_FRAME;
      segment[1].p_flags = PF_R;
      segment[1].p_vaddr = eh_vaddr;
      segment[1].p_memsz = segment[1].p_filesz = 16;
    }
  }
};

// The fake object's image: gesher-base memory, so that MAP_FIXED can replace it.
uint8_t* MapImage(size_t pages) {
  void* p = mmap(nullptr, pages * kPage, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  return p == MAP_FAILED ? nullptr : static_cast<uint8_t*>(p);
}

DWORD Protection(const void* p) {
  MEMORY_BASIC_INFORMATION info{};
  if (VirtualQuery(p, &info, sizeof(info)) == 0) return 0;
  return info.Protect;
}
bool Executable(const void* p) {
  return (Protection(p) & (PAGE_EXECUTE | PAGE_EXECUTE_READ | PAGE_EXECUTE_READWRITE |
                           PAGE_EXECUTE_WRITECOPY)) != 0;
}

using Fn = int64_t (SYSV*)(int64_t);

// mov $nr,%eax; syscall; ret
std::vector<uint8_t> SyscallCode(uint8_t nr) { return {0xB8, nr, 0, 0, 0, 0x0F, 0x05, 0xC3}; }
// mov %fs:0x28,%rax; ret
const std::vector<uint8_t> kGuardCode = {0x64, 0x48, 0x8B, 0x04, 0x25, 0x28, 0, 0, 0, 0xC3};

int64_t TebSelf() { return reinterpret_cast<int64_t>(NtCurrentTeb()); }

void TestLoadTimeSyscall() {
  uint8_t* image = MapImage(1);
  if (image == nullptr) return Check("load time: image mapped", false);
  std::vector<uint8_t> code = SyscallCode(39);
  std::memcpy(image, code.data(), code.size());
  FakeFile file(1, 1, 0);
  elf::FsRewriteStats stats;
  std::string error;
  bool ok = elf::RewriteFsAccesses("deferred-test-static", file.bytes.data(), file.bytes.size(),
                                   image, image, kPage, &stats, &error);
  Check("load time: rewrite succeeds", ok);
  Check("load time: one raw syscall found", stats.syscalls == 1 && !stats.self_unpacking);
  Check("load time: syscall became ud2 (0F 0B)", image[5] == 0x0F && image[6] == 0x0B);
  if (!ok || mprotect(image, kPage, PROT_READ | PROT_EXEC) != 0) return;
  int64_t r = reinterpret_cast<Fn>(image)(5);
  Check("load time: emulated getpid(39) gets %rdi and returns %rax", r == 39005 && g_args[0] == 5);
  // The rewritten code copied elsewhere and run there (UPX's stub copies
  // itself into an anonymous mapping): the copy's ud2 is the same call.
  void* copy = VirtualAlloc(nullptr, kPage, MEM_RESERVE | MEM_COMMIT, PAGE_EXECUTE_READWRITE);
  if (copy != nullptr) std::memcpy(copy, image, 64);
  Check("copied code: the copy of a rewritten syscall is emulated too",
        copy != nullptr && reinterpret_cast<Fn>(copy)(8) == 39008);
}

void TestRunTimeCode() {
  uint8_t* image = MapImage(3);
  if (image == nullptr) return Check("run time: image mapped", false);
  std::memset(image, 0xC3, 3 * kPage);
  FakeFile file(1, 3, 0);
  elf::FsRewriteStats stats;
  std::string error;
  bool ok = elf::RewriteFsAccesses("deferred-test-runtime", file.bytes.data(), file.bytes.size(),
                                   image, image, 3 * kPage, &stats, &error);
  Check("run time: object registered", ok);
  if (!ok) return;
  // Pages 1 and 2: written now, then made executable.
  std::vector<uint8_t> code = SyscallCode(7);
  std::memcpy(image + kPage, code.data(), code.size());
  std::memcpy(image + 2 * kPage, kGuardCode.data(), kGuardCode.size());
  Check("run time: ProtectLoadedMemory(PROT_READ|PROT_EXEC)",
        elf::ProtectLoadedMemory(image + kPage, 2 * kPage, PROT_READ | PROT_EXEC) == 0);
  Check("run time: pages wait non-executable", !Executable(image + kPage) &&
                                                   !Executable(image + 2 * kPage));
  int64_t r = reinterpret_cast<Fn>(image + kPage)(11);
  Check("run time: raw syscall in code written at run time emulated", r == 7011);
  Check("run time: its page rewritten and executable now",
        Executable(image + kPage) && image[kPage + 6] == 0x0B);
  Check("run time: the other page still waits", !Executable(image + 2 * kPage));
  int64_t guard = reinterpret_cast<Fn>(image + 2 * kPage)(0);
  Check("run time: %fs:0x28 in code written at run time reads the TEB", guard == TebSelf());
  Check("run time: %fs prefix rewritten to %gs", image[2 * kPage] == 0x65);
  // Patched again and made executable again: rewritten again.
  Check("run time: made writable", elf::ProtectLoadedMemory(image + kPage, kPage,
                                                            PROT_READ | PROT_WRITE) == 0);
  code = SyscallCode(9);
  std::memcpy(image + kPage, code.data(), code.size());
  elf::ProtectLoadedMemory(image + kPage, kPage, PROT_READ | PROT_EXEC);
  r = reinterpret_cast<Fn>(image + kPage)(1);
  Check("run time: code patched again is rewritten again", r == 9001);
  // Outside any object, PROT_EXEC is as asked.
  uint8_t* other = MapImage(1);
  Check("run time: memory of no object is executable at once",
        other != nullptr && elf::ProtectLoadedMemory(other, kPage, PROT_READ | PROT_EXEC) == 0 &&
            Executable(other));
  // ...unless a raw system call asks (UPX 5 maps its decompressor from a
  // memfd): that code waits for its first execution and is rewritten then.
  void* raw = elf::MapLoadedMemory(nullptr, kPage, PROT_READ | PROT_WRITE,
                                   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0, true);
  Check("raw mmap: read-write memory is mapped as asked", raw != MAP_FAILED);
  if (raw == MAP_FAILED) return;
  code = SyscallCode(39);
  std::memcpy(raw, code.data(), code.size());
  Check("raw mprotect: executable memory outside every object waits",
        elf::ProtectLoadedMemory(raw, kPage, PROT_READ | PROT_EXEC, true) == 0 && !Executable(raw));
  Check("raw mprotect: its raw syscall is emulated when it runs",
        reinterpret_cast<Fn>(raw)(4) == 39004 && Executable(raw));
  Check("raw mmap: unmapped", elf::UnmapLoadedMemory(raw, kPage) == 0);
  void* raw_exec = elf::MapLoadedMemory(nullptr, kPage, PROT_READ | PROT_WRITE | PROT_EXEC,
                                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0, true);
  Check("raw mmap: read-write-execute memory waits", raw_exec != MAP_FAILED && !Executable(raw_exec));
  if (raw_exec != MAP_FAILED) {
    std::memcpy(raw_exec, code.data(), code.size());
    Check("raw mmap: its raw syscall is emulated when it runs",
          reinterpret_cast<Fn>(raw_exec)(6) == 39006);
    elf::UnmapLoadedMemory(raw_exec, kPage);
  }
}

void TestSelfUnpacking() {
  // Page 0: the "unpacker" (file bytes); pages 1-3: its image, not in the
  // file (p_filesz is one page), where .eh_frame_hdr points as well.
  uint8_t* image = MapImage(4);
  if (image == nullptr) return Check("unpacking: image mapped", false);
  std::memset(image, 0xCC, 4 * kPage);
  // The initializer at 0 makes a raw syscall and returns. The "compressed"
  // bytes after it look like a syscall and an %fs access: the load must leave
  // them alone.
  std::vector<uint8_t> init = SyscallCode(39);
  std::memcpy(image, init.data(), init.size());
  const uint8_t compressed[] = {0x0F, 0x05, 0x64, 0x48, 0x8B, 0x04, 0x25, 0x28, 0, 0, 0};
  std::memcpy(image + 0x100, compressed, sizeof(compressed));
  FakeFile file(1, 4, kPage + 0x40);
  elf::FsRewriteStats stats;
  std::string error;
  bool ok = elf::RewriteFsAccesses("deferred-test-unpacking", file.bytes.data(), file.bytes.size(),
                                   image, image, 4 * kPage, &stats, &error);
  Check("unpacking: detected (.eh_frame_hdr outside the file bytes)", ok && stats.self_unpacking);
  Check("unpacking: no byte rewritten at load",
        image[6] == 0x05 && std::memcmp(image + 0x100, compressed, sizeof(compressed)) == 0);
  if (!ok || mprotect(image, kPage, PROT_READ | PROT_EXEC) != 0) return;
  Check("unpacking: DeferUnpackedCode",
        elf::DeferUnpackedCode("deferred-test-unpacking", file.bytes.data(), image, &error));
  Check("unpacking: its code waits non-executable", !Executable(image));
  int64_t r = reinterpret_cast<Fn>(image)(2);
  Check("unpacking: its initializer runs, its raw syscall emulated", r == 39002);
  Check("unpacking: only what ran was rewritten",
        image[6] == 0x0B && std::memcmp(image + 0x100, compressed, sizeof(compressed)) == 0);

  // Unpacked: fresh pages over the image (MAP_FIXED), code written, made
  // executable: a stack-guard function (page 1), and a system call stub
  // (page 3) entered only by `ret` from page 2, as UPX's munmap stub is.
  void* fixed = elf::MapLoadedMemory(image + kPage, 3 * kPage, PROT_READ | PROT_WRITE,
                                     MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
  Check("unpacking: MapLoadedMemory(MAP_FIXED) over the image", fixed == image + kPage);
  if (fixed != image + kPage) return;
  std::memcpy(image + kPage, kGuardCode.data(), kGuardCode.size());
  const uint8_t via_ret[] = {0x48, 0x8D, 0x05, 0xF9, 0x0F, 0, 0,  // lea 0xff9(%rip),%rax: page 3
                             0x50,                                // push %rax
                             0xB8, 0x0B, 0, 0, 0,                 // mov $11,%eax
                             0xC3};                               // ret -> page 3
  std::memcpy(image + 2 * kPage, via_ret, sizeof(via_ret));
  const uint8_t stub[] = {0x0F, 0x05, 0xC3};  // syscall; ret
  std::memcpy(image + 3 * kPage, stub, sizeof(stub));
  Check("unpacking: unpacked pages made executable",
        elf::ProtectLoadedMemory(image + kPage, 3 * kPage, PROT_READ | PROT_EXEC) == 0 &&
            !Executable(image + kPage));
  Check("unpacking: stack guard in unpacked code reads the TEB",
        reinterpret_cast<Fn>(image + kPage)(0) == TebSelf());
  Check("unpacking: a syscall stub reached only by `ret` is emulated",
        reinterpret_cast<Fn>(image + 2 * kPage)(3) == 11003);
  Check("unpacking: UnmapLoadedMemory", elf::UnmapLoadedMemory(image + kPage, 3 * kPage) == 0);
}

}  // namespace

int main() {
  elf::SetRawSyscallHandler(RawHandler);
  TestLoadTimeSyscall();
  TestRunTimeCode();
  TestSelfUnpacking();
  if (failures == 0) {
    std::printf("\nelf_deferred_code_test all passed\n");
  } else {
    std::printf("\n%d check(s) failed\n", failures);
  }
  return failures == 0 ? 0 : 1;
}
