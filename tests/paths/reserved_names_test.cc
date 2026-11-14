// Encoding of the file-name characters Windows reserves (gesher/reserved_names.h).
#include "../../compat/posix/include/gesher/reserved_names.h"
#include "../../compat/posix/include/gesher/unc_paths.h"
#include <cstdio>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
int checks;
void Check(bool value, const char* reason) {
  ++checks;
  if (!value) throw std::runtime_error(reason);
}

std::wstring Encoded(std::wstring path) {
  gesher::reserved_names::Encode(&path);
  return path;
}

std::wstring EncodedName(std::wstring name) {
  gesher::reserved_names::EncodeName(&name);
  return name;
}
}  // namespace

int main() {
  using gesher::reserved_names::Decoded;
  using gesher::reserved_names::RootLength;
  try {
    // Each reserved character, and only those, moves to U+F000 + c.
    for (wchar_t c = 1; c < 0x80; ++c) {
      std::wstring name(1, c);
      bool reserved = c < 0x20 || std::wstring(L":*?\"<>|").find(c) != std::wstring::npos;
      std::wstring encoded = EncodedName(name);
      Check(encoded == (reserved ? std::wstring(1, static_cast<wchar_t>(0xF000 + c)) : name),
            "a reserved character is encoded, any other kept");
      Check(Decoded(encoded) == name, "decoding gives the name back");
    }
    // '/' and '\\' separate names; they are never encoded.
    Check(EncodedName(L"a/b\\c") == L"a/b\\c", "separators kept");

    // Firebase Remote Config's shared_prefs file, as Waze writes it.
    std::wstring firebase =
        L"/data/data/com.waze/shared_prefs/frc_1:4:android:fb5_fireperf_settings.xml";
    std::wstring stored = Encoded(firebase);
    Check(stored.find(L':') == std::wstring::npos, "no colon reaches NTFS");
    Check(stored == L"/data/data/com.waze/shared_prefs/frc_1\xF03A" L"4\xF03A" L"android\xF03A"
                    L"fb5_fireperf_settings.xml",
          "colons stored as U+F03A");
    Check(Decoded(stored) == firebase, "the name comes back");

    // The Windows root keeps its characters; the names under it do not.
    std::vector<std::pair<std::wstring, std::wstring>> roots = {
        {L"C:\\data\\a:b", L"C:\\data\\a\xF03A" L"b"},
        {L"c:/data/a:b", L"c:/data/a\xF03A" L"b"},
        // A letter and a colon before a name is a Linux name, not drive-relative.
        {L"x:cfg", L"x\xF03A" L"cfg"},
        {L"C:relative:name", L"C\xF03A" L"relative\xF03A" L"name"},
        {L"C:", L"C:"},
        {L"\\\\?\\C:\\data\\a?b", L"\\\\?\\C:\\data\\a\xF03F" L"b"},
        {L"//?/C:/data/a|b", L"//?/C:/data/a\xF07C" L"b"},
        {L"\\\\?\\UNC\\server\\share\\a:b", L"\\\\?\\UNC\\server\\share\\a\xF03A" L"b"},
        {L"\\\\?\\unc\\server\\share", L"\\\\?\\unc\\server\\share"},
        {L"\\\\server\\share\\x\\a*b", L"\\\\server\\share\\x\\a\xF02A" L"b"},
        {L"//server/share/a<b>", L"//server/share/a\xF03C" L"b\xF03E"},
        {L"\\\\.\\pipe\\x", L"\\\\.\\pipe\\x"},
        {L"\\\\?\\Volume{0}\\a:b", L"\\\\?\\Volume{0}\\a\xF03A" L"b"},
        {L"relative/a\"b", L"relative/a\xF022" L"b"},
        {L"/x:", L"/x\xF03A"},
        {L"", L""},
    };
    for (const auto& [path, want] : roots) {
      Check(Encoded(path) == want, "root kept, names encoded");
      Check(Decoded(Encoded(path)) == path, "round trip");
      Check(Encoded(Encoded(path)) == want, "encoding twice changes nothing");
    }
    Check(RootLength(L"C:") == 2, "a bare drive");
    Check(gesher::reserved_names::DriveRoot("C:") && gesher::reserved_names::DriveRoot("c:/x") &&
              gesher::reserved_names::DriveRoot(L"D:\\x") &&
              !gesher::reserved_names::DriveRoot("x:cfg") && !gesher::reserved_names::DriveRoot("ab") &&
              !gesher::reserved_names::DriveRoot(":"),
          "a drive root is a letter, a colon, and a separator or the end");
    Check(RootLength(L"\\\\?\\") == 4, "a bare long-path prefix");
    Check(RootLength(L"\\\\server") == 8, "a bare UNC server");

    // A private-use character that is not an encoding is left alone.
    Check(Decoded(L"a\xF000" L"b\xF020\xF061\xF0FF") == L"a\xF000" L"b\xF020\xF061\xF0FF",
          "only encoded reserved characters decode");

    // Native UNC roots may themselves contain private-use characters. They
    // stay literal while a name below the share is decoded and encoded again.
    const std::wstring native_unc =
        L"\\\\server\xF03F" L"root\\share\xF03A" L"root\\dir\xF02A" L"name\\file\xF03A" L"name";
    const std::wstring guest_unc =
        L"/mnt/unc/server\xF03F" L"root/share\xF03A" L"root/dir*name/file:name";
    const std::vector<std::wstring> native_roots = {
        native_unc,
        L"\\\\?\\UNC\\" + native_unc.substr(2),
        L"\\\\?\\unc\\" + native_unc.substr(2),
    };
    for (const std::wstring& native : native_roots) {
      std::wstring decoded = native;
      gesher::reserved_names::DecodePath(&decoded);
      Check(decoded.substr(0, RootLength(native)) == native.substr(0, RootLength(native)),
            "private-use characters in a native UNC root stay literal");
      std::wstring guest;
      Check(gesher::unc_paths::FromWindows(decoded, &guest),
            "decoded native UNC path remains valid");
      Check(guest == guest_unc, "UNC root kept while names below it decode");
      std::wstring restored;
      Check(gesher::unc_paths::ToWindows(guest, &restored) == gesher::unc_paths::Match::kMapped,
            "decoded UNC guest path is accepted on return");
      gesher::reserved_names::Encode(&restored);
      Check(restored == native_unc, "UNC round trip preserves root and stored names");
    }
  } catch (const std::exception& e) {
    std::fprintf(stderr, "reserved names: FAILED after %d checks: %s\n", checks, e.what());
    return 1;
  }
  std::printf("reserved names: %d checks passed\n", checks);
  return 0;
}
