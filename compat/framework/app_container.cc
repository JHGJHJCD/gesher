// Gesher: experimental per-package AppContainer isolation (see app_container.h).
#include "app_container.h"

#include <aclapi.h>
#include <objbase.h>
#include <sddl.h>
#include <userenv.h>

namespace gesher {
namespace appcontainer {
namespace {

std::wstring StringSid(PSID sid) {
  LPWSTR text = nullptr;
  if (!ConvertSidToStringSidW(sid, &text)) return std::wstring();
  std::wstring result(text);
  LocalFree(text);
  return result;
}

// Whether `dacl` already has this explicit allow ACE (no propagation needed again).
bool HasExplicitAce(PACL dacl, PSID trustee, DWORD access, BYTE inherit_flags) {
  if (dacl == nullptr) return false;
  for (DWORD i = 0; i < dacl->AceCount; ++i) {
    void* raw = nullptr;
    if (!GetAce(dacl, i, &raw)) return false;
    auto* header = static_cast<ACE_HEADER*>(raw);
    if (header->AceType != ACCESS_ALLOWED_ACE_TYPE || (header->AceFlags & INHERITED_ACE)) continue;
    auto* ace = static_cast<ACCESS_ALLOWED_ACE*>(raw);
    if ((header->AceFlags & (OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE)) != inherit_flags) continue;
    if ((ace->Mask & access) != access) continue;
    if (EqualSid(reinterpret_cast<PSID>(&ace->SidStart), trustee)) return true;
  }
  return false;
}

}  // namespace

Sid::~Sid() {
  if (value == nullptr) return;
  if (local) LocalFree(value);
  else FreeSid(value);
}

bool Enabled() {
  wchar_t value[32];
  DWORD n = GetEnvironmentVariableW(L"GESHER_SANDBOX", value, 32);
  return n > 0 && n < 32 && CompareStringOrdinal(value, static_cast<int>(n), L"appcontainer", -1,
                                                 TRUE) == CSTR_EQUAL;
}

bool ValidName(const std::wstring& name) {
  if (name.empty() || name.size() > 64) return false;
  for (wchar_t c : name) {
    bool ok = (c >= L'a' && c <= L'z') || (c >= L'A' && c <= L'Z') || (c >= L'0' && c <= L'9') ||
              c == L'.' || c == L'-';
    if (!ok) return false;
  }
  return true;
}

DWORD EnsureProfile(const std::wstring& name, const std::wstring& display_name, Sid* sid) {
  if (!ValidName(name) || sid == nullptr) return ERROR_INVALID_PARAMETER;
  PSID created = nullptr;
  HRESULT hr = CreateAppContainerProfile(name.c_str(), display_name.c_str(), display_name.c_str(),
                                         nullptr, 0, &created);
  if (hr == HRESULT_FROM_WIN32(ERROR_ALREADY_EXISTS)) {
    hr = DeriveAppContainerSidFromAppContainerName(name.c_str(), &created);
  }
  if (FAILED(hr)) return HRESULT_CODE(hr) != 0 ? HRESULT_CODE(hr) : ERROR_GEN_FAILURE;
  sid->value = created;
  sid->local = false;
  return ERROR_SUCCESS;
}

DWORD ContainerSid(const std::wstring& name, Sid* sid) {
  if (!ValidName(name) || sid == nullptr) return ERROR_INVALID_PARAMETER;
  PSID derived = nullptr;
  HRESULT hr = DeriveAppContainerSidFromAppContainerName(name.c_str(), &derived);
  if (FAILED(hr)) return HRESULT_CODE(hr) != 0 ? HRESULT_CODE(hr) : ERROR_GEN_FAILURE;
  sid->value = derived;
  sid->local = false;
  return ERROR_SUCCESS;
}

DWORD DeleteContainerProfile(const std::wstring& name) {
  if (!ValidName(name)) return ERROR_INVALID_PARAMETER;
  HRESULT hr = DeleteAppContainerProfile(name.c_str());
  // S_OK also when it did not exist (documented); a not-found HRESULT is the same.
  if (SUCCEEDED(hr) || hr == HRESULT_FROM_WIN32(ERROR_NOT_FOUND) ||
      hr == HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND)) {
    return ERROR_SUCCESS;
  }
  return HRESULT_CODE(hr) != 0 ? HRESULT_CODE(hr) : ERROR_GEN_FAILURE;
}

DWORD CapabilitySid(const std::wstring& capability, Sid* sid) {
  if (capability.empty() || sid == nullptr) return ERROR_INVALID_PARAMETER;
  PSID* groups = nullptr;
  PSID* sids = nullptr;
  DWORD group_count = 0, sid_count = 0;
  if (!DeriveCapabilitySidsFromName(capability.c_str(), &groups, &group_count, &sids, &sid_count)) {
    return GetLastError();
  }
  for (DWORD i = 0; i < group_count; ++i) LocalFree(groups[i]);
  LocalFree(groups);
  if (sid_count == 0) {
    LocalFree(sids);
    return ERROR_INVALID_SID;
  }
  sid->value = sids[0];
  sid->local = true;
  for (DWORD i = 1; i < sid_count; ++i) LocalFree(sids[i]);
  LocalFree(sids);
  return ERROR_SUCCESS;
}

DWORD FolderPath(PSID container, std::wstring* path) {
  if (container == nullptr || path == nullptr) return ERROR_INVALID_PARAMETER;
  std::wstring text = StringSid(container);
  if (text.empty()) return GetLastError();
  PWSTR folder = nullptr;
  HRESULT hr = GetAppContainerFolderPath(text.c_str(), &folder);
  if (FAILED(hr)) return HRESULT_CODE(hr) != 0 ? HRESULT_CODE(hr) : ERROR_GEN_FAILURE;
  *path = folder;
  CoTaskMemFree(folder);
  return ERROR_SUCCESS;
}

DWORD Grant(const std::wstring& path, PSID trustee, DWORD access, bool inherit) {
  if (path.empty() || trustee == nullptr || access == 0) return ERROR_INVALID_PARAMETER;
  PACL dacl = nullptr;
  PSECURITY_DESCRIPTOR descriptor = nullptr;
  DWORD error = GetNamedSecurityInfoW(path.c_str(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION,
                                      nullptr, nullptr, &dacl, nullptr, &descriptor);
  if (error != ERROR_SUCCESS) return error;
  const BYTE flags = inherit ? (OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE) : 0;
  if (HasExplicitAce(dacl, trustee, access, flags)) {
    LocalFree(descriptor);
    return ERROR_SUCCESS;
  }
  EXPLICIT_ACCESS_W entry{};
  entry.grfAccessPermissions = access;
  entry.grfAccessMode = GRANT_ACCESS;
  entry.grfInheritance = inherit ? SUB_CONTAINERS_AND_OBJECTS_INHERIT : NO_INHERITANCE;
  entry.Trustee.TrusteeForm = TRUSTEE_IS_SID;
  entry.Trustee.TrusteeType = TRUSTEE_IS_UNKNOWN;
  entry.Trustee.ptstrName = static_cast<LPWSTR>(trustee);
  PACL updated = nullptr;
  error = SetEntriesInAclW(1, &entry, dacl, &updated);
  if (error == ERROR_SUCCESS) {
    // A copy: SetNamedSecurityInfoW takes a non-const path.
    std::wstring target = path;
    error = SetNamedSecurityInfoW(target.data(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION,
                                  nullptr, nullptr, updated, nullptr);
    LocalFree(updated);
  }
  LocalFree(descriptor);
  return error;
}

DWORD BinderPipeSecurity(PSECURITY_DESCRIPTOR* descriptor) {
  if (descriptor == nullptr) return ERROR_INVALID_PARAMETER;
  HANDLE token = nullptr;
  if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return GetLastError();
  alignas(TOKEN_USER) BYTE buffer[sizeof(TOKEN_USER) + SECURITY_MAX_SID_SIZE];
  DWORD size = 0;
  BOOL ok = GetTokenInformation(token, TokenUser, buffer, sizeof(buffer), &size);
  DWORD error = ok ? ERROR_SUCCESS : GetLastError();
  CloseHandle(token);
  if (!ok) return error;
  std::wstring user = StringSid(reinterpret_cast<TOKEN_USER*>(buffer)->User.Sid);
  Sid capability;
  error = CapabilitySid(kBinderClientCapability, &capability);
  if (error != ERROR_SUCCESS) return error;
  std::wstring client = StringSid(capability.value);
  if (user.empty() || client.empty()) return ERROR_INVALID_SID;
  // Protected DACL: nothing inherited, no Everyone/anonymous read of the default one.
  std::wstring sddl = L"D:P(A;;GA;;;SY)(A;;GA;;;" + user + L")(A;;GRGW;;;" + client +
                      L")S:(ML;;NW;;;LW)";
  if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1,
                                                            descriptor, nullptr)) {
    return GetLastError();
  }
  return ERROR_SUCCESS;
}

}  // namespace appcontainer
}  // namespace gesher
