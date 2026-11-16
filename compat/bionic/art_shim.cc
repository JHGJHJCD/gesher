// gesher: the stand-in libart.so for packers that hook ART by symbol
// (gesher/art_shim.h, docs/decisions/0086-packer-art-hooks.md).

#include <gesher/art_shim.h>

#include <gesher/elf_loader.h>

#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace gesher::bionic {

const char kArtLoadClassSymbol[] =
    "_ZN3art11ClassLinker9LoadClassEPNS_6ThreadERKNS_7DexFileERKNS_3dex8ClassDefENS_"
    "6HandleINS_6mirror5ClassEEE";

namespace {

// The hookable function: an ordinary System V prologue and epilogue around
// two long NOPs, and nothing else. An inline hook replaces its first bytes
// with a jump (5 or 14 bytes) and runs the instructions it displaced from a
// trampoline before jumping back; every instruction here is position
// independent, so displaced ones run anywhere, and the prologue ends on an
// instruction boundary at 13 bytes, the NOPs past it at 21 and 29.
constexpr uint8_t kLoadClassCode[] = {
    0x55,                                            // push rbp
    0x48, 0x89, 0xe5,                                // mov rbp, rsp
    0x53,                                            // push rbx
    0x41, 0x54,                                      // push r12
    0x41, 0x55,                                      // push r13
    0x41, 0x56,                                      // push r14
    0x41, 0x57,                                      // push r15
    0x0f, 0x1f, 0x84, 0x00, 0x00, 0x00, 0x00, 0x00,  // nop dword [rax+rax*1+0]
    0x0f, 0x1f, 0x84, 0x00, 0x00, 0x00, 0x00, 0x00,  // nop dword [rax+rax*1+0]
    0x41, 0x5f,                                      // pop r15
    0x41, 0x5e,                                      // pop r14
    0x41, 0x5d,                                      // pop r13
    0x41, 0x5c,                                      // pop r12
    0x5b,                                            // pop rbx
    0x5d,                                            // pop rbp
    0xc3,                                            // ret
};

constexpr char kSoname[] = "libart.so";
constexpr size_t kPage = 4096;

uint32_t GnuHash(const char* name) {
  uint32_t h = 5381;
  for (const auto* p = reinterpret_cast<const uint8_t*>(name); *p != 0; ++p) h += (h << 5) + *p;
  return h;
}

// Appends `size` bytes at an `align`ed offset of `out`; returns the offset.
size_t Append(std::vector<uint8_t>* out, const void* data, size_t size, size_t align) {
  while (out->size() % align != 0) out->push_back(0);
  const size_t offset = out->size();
  const auto* bytes = static_cast<const uint8_t*>(data);
  out->insert(out->end(), bytes, bytes + size);
  return offset;
}

template <typename T>
void Put(std::vector<uint8_t>* out, size_t offset, const T& value) {
  std::memcpy(out->data() + offset, &value, sizeof(T));
}

std::string Base(const char* path) {
  std::string s(path);
  const size_t slash = s.find_last_of("/\\");
  return slash == std::string::npos ? s : s.substr(slash + 1);
}

bool ReadFile(const std::string& path, std::string* data) {
  const int fd = open(path.c_str(), O_RDONLY);
  if (fd < 0) return false;
  data->clear();
  char buf[4096];
  for (;;) {
    const int n = static_cast<int>(read(fd, buf, sizeof(buf)));
    if (n < 0) {
      close(fd);
      return false;
    }
    if (n == 0) break;
    data->append(buf, static_cast<size_t>(n));
  }
  close(fd);
  return true;
}

// mkdir -p.
void MakeDirectories(const std::string& dir) {
  for (size_t i = 1; i <= dir.size(); ++i) {
    if (i == dir.size() || dir[i] == '/' || dir[i] == '\\') {
      const std::string part = dir.substr(0, i);
      if (!part.empty() && part.back() != ':') mkdir(part.c_str(), 0755);
    }
  }
}

// Makes `path` hold exactly `image`: an existing file with these bytes is used
// as it is (another process may have it mapped); otherwise the bytes go to a
// file of this process and are renamed into place.
bool WriteOnce(const std::string& path, const std::string& image, std::string* error) {
  std::string existing;
  if (ReadFile(path, &existing) && existing == image) return true;
  const std::string temp = path + "." + std::to_string(getpid()) + ".tmp";
  const int fd = open(temp.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
  if (fd < 0) {
    *error = "cannot create " + temp + ": " + std::strerror(errno);
    return false;
  }
  const bool written = write(fd, image.data(), static_cast<unsigned>(image.size())) ==
                       static_cast<int>(image.size());
  close(fd);
  if (!written) {
    unlink(temp.c_str());
    *error = "cannot write " + temp;
    return false;
  }
  if (rename(temp.c_str(), path.c_str()) != 0) {
    // Another process put it there first (or holds an old one mapped): use it
    // if it is this image.
    unlink(temp.c_str());
    if (ReadFile(path, &existing) && existing == image) return true;
    *error = "cannot create " + path + ": " + std::strerror(errno);
    return false;
  }
  return true;
}

}  // namespace

bool IsArtHookingPacker(const char* path) {
  if (path == nullptr) return false;
  const std::string base = Base(path);
  return base == "libexec.so" || base == "libexecmain.so" || base == "libexec_x86.so" ||
         base == "libexecmain_x86.so";
}

std::string ArtShimImage() {
  // Layout, in one read-execute PT_LOAD from file offset 0 at address 0, so
  // that each file offset is also the address relative to the load base:
  // headers, .text, .dynsym, .dynstr, .hash, .gnu.hash, .dynamic, .symtab,
  // .strtab, .shstrtab, then the section headers.
  constexpr int kPhnum = 3;
  std::vector<uint8_t> out(sizeof(Elf64_Ehdr) + kPhnum * sizeof(Elf64_Phdr), 0);

  const size_t text = Append(&out, kLoadClassCode, sizeof(kLoadClassCode), 64);

  // String table: "", soname, symbol.
  std::string strtab(1, '\0');
  const auto soname_name = static_cast<uint32_t>(strtab.size());
  strtab += kSoname;
  strtab += '\0';
  const auto symbol_name = static_cast<uint32_t>(strtab.size());
  strtab += kArtLoadClassSymbol;
  strtab += '\0';

  Elf64_Sym syms[2] = {};
  syms[1].st_name = symbol_name;
  syms[1].st_info = ELF64_ST_INFO(STB_GLOBAL, STT_FUNC);
  syms[1].st_other = STV_DEFAULT;
  syms[1].st_shndx = 1;  // .text
  syms[1].st_value = text;
  syms[1].st_size = sizeof(kLoadClassCode);
  const size_t dynsym = Append(&out, syms, sizeof(syms), 8);
  const size_t dynstr = Append(&out, strtab.data(), strtab.size(), 1);

  // DT_HASH: one bucket (every hash modulo 1), holding the symbol; no chain.
  const uint32_t hash[] = {1, 2, 1, 0, 0};  // nbucket, nchain, bucket[0], chain[0..1]
  const size_t sysv_hash = Append(&out, hash, sizeof(hash), 8);

  // DT_GNU_HASH: one bucket, symbols from index 1, a one-word bloom filter.
  const uint32_t h = GnuHash(kArtLoadClassSymbol);
  constexpr uint32_t kBloomShift = 6;
  struct {
    uint32_t nbuckets = 1, symoffset = 1, bloom_size = 1, bloom_shift = kBloomShift;
    uint64_t bloom;
    uint32_t bucket = 1;
    uint32_t chain;
  } gnu;
  gnu.bloom = (uint64_t{1} << (h % 64)) | (uint64_t{1} << ((h >> kBloomShift) % 64));
  gnu.chain = h | 1;  // the last (only) symbol of its chain
  const size_t gnu_hash = Append(&out, &gnu, sizeof(gnu), 8);

  const Elf64_Dyn dyn[] = {
      {DT_HASH, {sysv_hash}},     {DT_GNU_HASH, {gnu_hash}},
      {DT_STRTAB, {dynstr}},      {DT_SYMTAB, {dynsym}},
      {DT_STRSZ, {strtab.size()}}, {DT_SYMENT, {sizeof(Elf64_Sym)}},
      {DT_SONAME, {soname_name}}, {DT_NULL, {0}},
  };
  const size_t dynamic = Append(&out, dyn, sizeof(dyn), 8);

  // .symtab/.strtab: the same symbol, for parsers that read the file's
  // section headers instead of the dynamic segment.
  const size_t symtab = Append(&out, syms, sizeof(syms), 8);
  const size_t symstr = Append(&out, strtab.data(), strtab.size(), 1);

  const char shstr[] =
      "\0.text\0.dynsym\0.dynstr\0.hash\0.gnu.hash\0.dynamic\0.symtab\0.strtab\0.shstrtab";
  const size_t shstrtab = Append(&out, shstr, sizeof(shstr), 1);
  auto name_of = [&](const char* section) {
    for (size_t i = 0; i < sizeof(shstr); ++i) {
      if (std::strcmp(shstr + i, section) == 0) return static_cast<uint32_t>(i);
    }
    return 0u;
  };

  Elf64_Shdr sh[10] = {};
  auto section = [&](int i, const char* name, uint32_t type, uint64_t flags, size_t offset,
                     size_t size, uint32_t link, uint32_t info, uint64_t align, uint64_t entsize) {
    sh[i].sh_name = name_of(name);
    sh[i].sh_type = type;
    sh[i].sh_flags = flags;
    sh[i].sh_addr = (flags & SHF_ALLOC) != 0 ? offset : 0;
    sh[i].sh_offset = offset;
    sh[i].sh_size = size;
    sh[i].sh_link = link;
    sh[i].sh_info = info;
    sh[i].sh_addralign = align;
    sh[i].sh_entsize = entsize;
  };
  section(1, ".text", SHT_PROGBITS, SHF_ALLOC | SHF_EXECINSTR, text, sizeof(kLoadClassCode), 0, 0,
          64, 0);
  section(2, ".dynsym", SHT_DYNSYM, SHF_ALLOC, dynsym, sizeof(syms), 3, 1, 8, sizeof(Elf64_Sym));
  section(3, ".dynstr", SHT_STRTAB, SHF_ALLOC, dynstr, strtab.size(), 0, 0, 1, 0);
  section(4, ".hash", SHT_HASH, SHF_ALLOC, sysv_hash, sizeof(hash), 2, 0, 8, 4);
  section(5, ".gnu.hash", SHT_GNU_HASH, SHF_ALLOC, gnu_hash, sizeof(gnu), 2, 0, 8, 0);
  section(6, ".dynamic", SHT_DYNAMIC, SHF_ALLOC, dynamic, sizeof(dyn), 3, 0, 8,
          sizeof(Elf64_Dyn));
  section(7, ".symtab", SHT_SYMTAB, 0, symtab, sizeof(syms), 8, 1, 8, sizeof(Elf64_Sym));
  section(8, ".strtab", SHT_STRTAB, 0, symstr, strtab.size(), 0, 0, 1, 0);
  section(9, ".shstrtab", SHT_STRTAB, 0, shstrtab, sizeof(shstr), 0, 0, 1, 0);
  const size_t shoff = Append(&out, sh, sizeof(sh), 8);
  // Whole pages: gesher-base maps whole pages of a file as a view and copies a
  // final partial page into anonymous memory, which /proc/self/maps cannot name
  // (the packer looks for the line naming libart.so).
  out.resize((out.size() + kPage - 1) / kPage * kPage, 0);

  Elf64_Ehdr eh = {};
  eh.e_ident[EI_MAG0] = ELFMAG0;
  eh.e_ident[EI_MAG1] = ELFMAG1;
  eh.e_ident[EI_MAG2] = ELFMAG2;
  eh.e_ident[EI_MAG3] = ELFMAG3;
  eh.e_ident[EI_CLASS] = ELFCLASS64;
  eh.e_ident[EI_DATA] = ELFDATA2LSB;
  eh.e_ident[EI_VERSION] = EV_CURRENT;
  eh.e_ident[EI_OSABI] = ELFOSABI_NONE;
  eh.e_type = ET_DYN;
  eh.e_machine = EM_X86_64;
  eh.e_version = EV_CURRENT;
  eh.e_phoff = sizeof(Elf64_Ehdr);
  eh.e_shoff = shoff;
  eh.e_ehsize = sizeof(Elf64_Ehdr);
  eh.e_phentsize = sizeof(Elf64_Phdr);
  eh.e_phnum = kPhnum;
  eh.e_shentsize = sizeof(Elf64_Shdr);
  eh.e_shnum = 10;
  eh.e_shstrndx = 9;
  Put(&out, 0, eh);

  Elf64_Phdr ph[kPhnum] = {};
  ph[0].p_type = PT_LOAD;
  ph[0].p_flags = PF_R | PF_X;
  ph[0].p_offset = 0;
  ph[0].p_vaddr = ph[0].p_paddr = 0;
  ph[0].p_filesz = ph[0].p_memsz = out.size();
  ph[0].p_align = kPage;
  ph[1].p_type = PT_DYNAMIC;
  ph[1].p_flags = PF_R;
  ph[1].p_offset = ph[1].p_vaddr = ph[1].p_paddr = dynamic;
  ph[1].p_filesz = ph[1].p_memsz = sizeof(dyn);
  ph[1].p_align = 8;
  ph[2].p_type = PT_GNU_STACK;
  ph[2].p_flags = PF_R | PF_W;
  for (int i = 0; i < kPhnum; ++i) {
    Put(&out, sizeof(Elf64_Ehdr) + i * sizeof(Elf64_Phdr), ph[i]);
  }
  return std::string(out.begin(), out.end());
}

void* LoadArtShim(const std::string& dir, std::string* path, std::string* error) {
  static std::mutex mutex;
  static void* entry = nullptr;
  static std::string loaded_path;
  std::lock_guard<std::mutex> lock(mutex);
  if (entry != nullptr) {
    *path = loaded_path;
    return entry;
  }
  const std::string image = ArtShimImage();
  // A directory per image: a process that has an older stand-in mapped keeps
  // it, and the file is never rewritten while mapped.
  uint32_t fnv = 2166136261u;
  for (unsigned char c : image) fnv = (fnv ^ c) * 16777619u;
  char name[16];
  std::snprintf(name, sizeof(name), "%08x", fnv);
  const std::string sub = dir + "/" + name;
  MakeDirectories(sub);
  *path = sub + "/" + kSoname;
  if (!WriteOnce(*path, image, error)) return nullptr;
  elf::Library* lib = elf::Load(path->c_str(), error);
  if (lib == nullptr) return nullptr;
  void* fn = elf::Lookup(lib, kArtLoadClassSymbol);
  if (fn == nullptr) {
    *error = *path + " has no " + kArtLoadClassSymbol;
    return nullptr;
  }
  entry = fn;
  loaded_path = *path;
  return entry;
}

}  // namespace gesher::bionic
