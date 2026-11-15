// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
#pragma once
#include <windows.h>
#include <stddef.h>
#include <string.h>
#include <wchar.h>

struct GesherLanguageBuffer {
  wchar_t* names;
  size_t chars;
};

// Reserve two additional terminators: the registry does not guarantee that a
// string value includes its terminator, even when its declared type is correct.
static inline wchar_t* GesherLanguageBufferAllocate(size_t chars) {
  if (chars > (size_t)-1 / sizeof(wchar_t) - 2) return NULL;
  return (wchar_t*)HeapAlloc(GetProcessHeap(), 0, (chars + 2) * sizeof(wchar_t));
}

static inline struct GesherLanguageBuffer GesherProfileLanguages(void) {
  struct GesherLanguageBuffer result = {NULL, 0};
  HKEY key = NULL;
  if (RegOpenKeyExW(HKEY_CURRENT_USER,
                   L"Control Panel\\International\\User Profile", 0,
                   KEY_QUERY_VALUE, &key) != ERROR_SUCCESS) return result;
  DWORD type = 0, bytes = 0;
  if (RegQueryValueExW(key, L"Languages", NULL, &type, NULL, &bytes) == ERROR_SUCCESS &&
      (type == REG_MULTI_SZ || type == REG_SZ) && bytes != 0 &&
      bytes % sizeof(wchar_t) == 0) {
    const DWORD capacity = bytes;
    wchar_t* names = GesherLanguageBufferAllocate(bytes / sizeof(wchar_t));
    if (names != NULL) {
      // The value can change between queries. Never interpret a changed type,
      // an odd byte count or a reply larger than the allocated input capacity.
      if (RegQueryValueExW(key, L"Languages", NULL, &type, (LPBYTE)names, &bytes) == ERROR_SUCCESS &&
          (type == REG_MULTI_SZ || type == REG_SZ) && bytes <= capacity &&
          bytes % sizeof(wchar_t) == 0) {
        size_t chars = bytes / sizeof(wchar_t);
        names[chars] = names[chars + 1] = L'\0';
        if (type == REG_SZ) {
          // REG_SZ contributes one name, even if stray data follows its NUL.
          size_t end = 0;
          while (end < chars && names[end] != L'\0') ++end;
          names[end + 1] = L'\0';
          chars = end;
        }
        result.names = names;
        result.chars = chars + 2;
      } else {
        HeapFree(GetProcessHeap(), 0, names);
      }
    }
  }
  RegCloseKey(key);
  return result;
}

static inline struct GesherLanguageBuffer GesherDisplayLanguages(void) {
  struct GesherLanguageBuffer result = {NULL, 0};
  ULONG count = 0, chars = 0;
  if (!GetUserPreferredUILanguages(MUI_LANGUAGE_NAME, &count, NULL, &chars) || chars == 0)
    return result;
  const ULONG capacity = chars;
  wchar_t* names = GesherLanguageBufferAllocate(chars);
  if (names == NULL) return result;
  if (GetUserPreferredUILanguages(MUI_LANGUAGE_NAME, &count, names, &chars) && chars <= capacity) {
    names[chars] = names[(size_t)chars + 1] = L'\0';
    result.names = names;
    result.chars = (size_t)chars + 2;
  } else {
    HeapFree(GetProcessHeap(), 0, names);
  }
  return result;
}

static inline int GesherLanguageSpace(wchar_t c) {
  return c == L' ' || c == L'\t' || c == L'\r' || c == L'\n';
}

// Locale names are ASCII language tags. Preserve their spelling, accepting the
// underscore separator already understood by Gesher's locale selectors.
static inline int GesherLanguageNameValid(const wchar_t* name, size_t chars) {
  if (chars == 0 || !((name[0] >= L'A' && name[0] <= L'Z') ||
                      (name[0] >= L'a' && name[0] <= L'z'))) return 0;
  int separator = 0;
  for (size_t i = 0; i < chars; ++i) {
    wchar_t c = name[i];
    if (c == L'-' || c == L'_') {
      if (separator) return 0;
      separator = 1;
    } else {
      if (!((c >= L'A' && c <= L'Z') || (c >= L'a' && c <= L'z') ||
            (c >= L'0' && c <= L'9'))) return 0;
      separator = 0;
    }
  }
  return !separator;
}

static inline int GesherLanguageNameEqual(const wchar_t* a, const wchar_t* b, size_t chars) {
  for (size_t i = 0; i < chars; ++i) {
    wchar_t ac = a[i], bc = b[i];
    if (ac >= L'A' && ac <= L'Z') ac += L'a' - L'A';
    if (bc >= L'A' && bc <= L'Z') bc += L'a' - L'A';
    if (ac != bc) return 0;
  }
  return 1;
}

static inline void GesherAppendLanguages(wchar_t* output, size_t* used,
                                         struct GesherLanguageBuffer input) {
  size_t at = 0;
  // The first empty string terminates MULTI_SZ. Trailing bytes are not names.
  while (at < input.chars && input.names[at] != L'\0') {
    size_t end = at;
    while (end < input.chars && input.names[end] != L'\0') ++end;
    if (end == input.chars) break;
    size_t start = at, finish = end;
    while (start < finish && GesherLanguageSpace(input.names[start])) ++start;
    while (finish > start && GesherLanguageSpace(input.names[finish - 1])) --finish;
    const size_t length = finish - start;
    if (GesherLanguageNameValid(input.names + start, length)) {
      int duplicate = 0;
      for (size_t previous = 0; previous < *used;) {
        size_t previous_length = wcslen(output + previous);
        if (previous_length == length &&
            GesherLanguageNameEqual(output + previous, input.names + start, length)) {
          duplicate = 1;
          break;
        }
        previous += previous_length + 1;
      }
      if (!duplicate) {
        memcpy(output + *used, input.names + start, length * sizeof(wchar_t));
        *used += length;
        output[(*used)++] = L'\0';
      }
    }
    at = end + 1;
  }
}

// Profile languages for apps/websites first, then unseen Windows UI languages.
// Returns an owned, double-NUL-terminated list or NULL. Release with HeapFree.
static inline wchar_t* GesherPreferredLanguages(void) {
  struct GesherLanguageBuffer profile = GesherProfileLanguages();
  struct GesherLanguageBuffer display = GesherDisplayLanguages();
  wchar_t* names = NULL;
  if (profile.chars <= (size_t)-1 - display.chars && profile.chars + display.chars != 0) {
    names = GesherLanguageBufferAllocate(profile.chars + display.chars);
    if (names != NULL) {
      size_t used = 0;
      GesherAppendLanguages(names, &used, profile);
      GesherAppendLanguages(names, &used, display);
      names[used] = names[used + 1] = L'\0';
      if (used == 0) {
        HeapFree(GetProcessHeap(), 0, names);
        names = NULL;
      }
    }
  }
  if (profile.names != NULL) HeapFree(GetProcessHeap(), 0, profile.names);
  if (display.names != NULL) HeapFree(GetProcessHeap(), 0, display.names);
  return names;
}
