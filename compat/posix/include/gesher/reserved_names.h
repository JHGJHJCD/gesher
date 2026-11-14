// gesher-base: Linux file names that Windows cannot store as they are
// (docs/decisions/0083-reserved-file-name-characters.md).
//
// Linux allows any byte but '/' and NUL in a name; Windows reserves
// < > : " | ? * and the control characters 1-31. Apps use some of them: the
// Firebase SDK keeps "frc_<n>:<project>:android:<id>_..._settings.xml" in
// shared_prefs, and Play Store a directory per process ("...:background").
// On NTFS a ':' names an alternate data stream, so such a name fails or, with
// one colon, silently writes a hidden stream of another file.
//
// As Cygwin does, each reserved character c is stored as the private-use
// character U+F000 + c, and turned back when a name leaves the disk (readdir,
// getcwd, readlink, inotify). The Windows root of a path (a drive "C:", the
// "\\?\" and "\\.\" prefixes, a UNC server and share) keeps its characters.
// Header-only, so the bionic shim and the exec tools share it with
// gesher-base, and a portable test (tests/paths/reserved_names_test.cc) runs
// it anywhere.
#ifndef GESHER_RESERVED_NAMES_H_
#define GESHER_RESERVED_NAMES_H_

#include <cstddef>
#include <string>
#include <string_view>

namespace gesher::reserved_names {

constexpr wchar_t kBase = 0xF000;

// A character Windows does not allow in a file name.
inline bool Reserved(wchar_t c) {
  return (c >= 1 && c < 0x20) || c == L':' || c == L'*' || c == L'?' || c == L'"' ||
         c == L'<' || c == L'>' || c == L'|';
}

// An encoded reserved character.
inline bool Encoded(wchar_t c) {
  return c > kBase && c < kBase + 0x80 && Reserved(static_cast<wchar_t>(c - kBase));
}

inline bool Separator(wchar_t c) { return c == L'\\' || c == L'/'; }

inline bool DriveLetter(wchar_t c) {
  return (c >= L'A' && c <= L'Z') || (c >= L'a' && c <= L'z');
}

// A drive root: "C:" alone or before a separator. "x:cfg" is a Linux name
// with a colon, not a path relative to drive X's current directory.
template <typename Char>
inline bool DriveRoot(const Char* p) {
  return DriveLetter(static_cast<wchar_t>(p[0])) && p[1] == ':' &&
         (p[2] == 0 || Separator(static_cast<wchar_t>(p[2])));
}

// How much of `path` is its Windows root, which keeps its ':' and '?':
// "C:", "\\?\C:", "\\?\UNC\server\share", "\\.\name", "\\server\share".
inline size_t RootLength(std::wstring_view path) {
  size_t p = 0;
  if (path.size() >= 2 && Separator(path[0]) && Separator(path[1])) {
    if (path.size() >= 4 && (path[2] == L'?' || path[2] == L'.') && Separator(path[3])) {
      p = 4;
      if (path[2] == L'.') {
        // A device: its name is not a file's.
        size_t end = p;
        while (end < path.size() && !Separator(path[end])) ++end;
        return end;
      }
      if (path.size() >= p + 4 && (path[p] | 0x20) == L'u' && (path[p + 1] | 0x20) == L'n' &&
          (path[p + 2] | 0x20) == L'c' && Separator(path[p + 3])) {
        p += 4;
      } else {
        if (path.size() >= p + 2 && DriveLetter(path[p]) && path[p + 1] == L':') p += 2;
        return p;
      }
    } else {
      p = 2;
    }
    // A UNC server and share.
    for (int part = 0; part < 2 && p < path.size(); ++part) {
      while (p < path.size() && !Separator(path[p])) ++p;
      if (part == 0 && p < path.size()) ++p;
    }
    return p;
  }
  if (path.size() >= 2 && DriveLetter(path[0]) && path[1] == L':' &&
      (path.size() == 2 || Separator(path[2]))) {
    return 2;
  }
  return 0;
}

// Encodes the reserved characters of `path` from `from` on.
inline void EncodeFrom(std::wstring* path, size_t from) {
  for (size_t i = from; i < path->size(); ++i) {
    wchar_t& c = (*path)[i];
    if (Reserved(c)) c = static_cast<wchar_t>(kBase + c);
  }
}

// A Windows path with the names under its root encoded.
inline void Encode(std::wstring* path) { EncodeFrom(path, RootLength(*path)); }

// One name (no root): every reserved character is encoded.
inline void EncodeName(std::wstring* name) { EncodeFrom(name, 0); }

// The names as Linux sees them again.
inline void Decode(std::wstring* path) {
  for (wchar_t& c : *path) {
    if (Encoded(c)) c = static_cast<wchar_t>(c - kBase);
  }
}

inline std::wstring Decoded(std::wstring_view path) {
  std::wstring out(path);
  Decode(&out);
  return out;
}

}  // namespace gesher::reserved_names

#endif  // GESHER_RESERVED_NAMES_H_
