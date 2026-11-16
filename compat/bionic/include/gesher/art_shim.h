// gesher: a stand-in libart.so for apps whose packer hooks ART by symbol
// (compat/bionic/art_shim.cc, docs/decisions/0086-packer-art-hooks.md).
//
// Ijiami (爱加密) ships the app's dex files with their method bodies left out.
// Its native shell finds libart.so in /proc/self/maps, reads the symbol of
// art::ClassLinker::LoadClass from it and hooks that function: before ART
// reads a class, the hook writes the class's method bodies back into the dex
// file. Gesher's ART is part of dalvikvm.exe, not an ELF libart.so, so the
// shell finds nothing to hook, says nothing, and ART later rejects the empty
// methods (VerifyError: "Constructor returning without calling superclass
// constructor").
//
// The stand-in is a small ELF object written and loaded here: it exports that
// symbol as a System V function that does nothing. ART calls it at the start
// of ClassLinker::LoadClass with the device's arguments (patches/art/0800);
// when the packer has hooked it, the packer's code runs there, as on a device.
#pragma once

#include <string>

namespace gesher::bionic {

// The symbol the stand-in exports: art::ClassLinker::LoadClass(Thread*,
// const DexFile&, const dex::ClassDef&, Handle<mirror::Class>).
extern const char kArtLoadClassSymbol[];

// Is `path` the native shell of a packer that hooks ART by symbol (its base
// name: Ijiami's libexec.so, libexecmain.so and their _x86 variants)?
bool IsArtHookingPacker(const char* path);

// The stand-in libart.so's bytes (an ELF64 x86-64 ET_DYN object: one
// read-execute PT_LOAD from offset 0, .dynsym with DT_HASH and DT_GNU_HASH,
// and section headers with .symtab, as a parser of either finds it).
std::string ArtShimImage();

// Writes the stand-in under `dir` (a directory named after its contents, the
// file named libart.so, as /proc/self/maps then names it), loads it with the
// ELF loader and returns its LoadClass entry point; nullptr with *error set on
// failure. *path receives the file's path. Loads it once per process: later
// calls return the same entry point.
void* LoadArtShim(const std::string& dir, std::string* path, std::string* error);

}  // namespace gesher::bionic
