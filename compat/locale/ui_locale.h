// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
#pragma once
#include <windows.h>
#include "ui_messages.h"
#include "preferred_languages.h"

// Same preference order as SystemLocale, including its explicit test/launch override.
// Read at presentation time so a later Windows language change is picked up.
static inline enum GesherUiLanguage GesherUiCurrentLanguage(void) {
  DWORD size = GetEnvironmentVariableW(L"GESHER_LOCALES", NULL, 0);
  if (size > 1) {
    wchar_t* tags = (wchar_t*)HeapAlloc(GetProcessHeap(), 0, size * sizeof(wchar_t));
    if (tags != NULL) {
      DWORD copied = GetEnvironmentVariableW(L"GESHER_LOCALES", tags, size);
      if (copied > 0 && copied < size) {
        const wchar_t* value = tags;
        while (*value == L' ' || *value == L'\t' || *value == L'\r' || *value == L'\n') ++value;
        if (*value) {
          enum GesherUiLanguage lang = GesherUiMatch(value);
          HeapFree(GetProcessHeap(), 0, tags);
          return lang;
        }
      }
      HeapFree(GetProcessHeap(), 0, tags);
    }
  }

  wchar_t* tags = GesherPreferredLanguages();
  if (tags == NULL) return GESHER_UI_EN;
  // Flatten the owned list in place for the existing comma-separated selector.
  for (wchar_t* end = tags; *end != L'\0';) {
    end += wcslen(end);
    if (end[1] != L'\0') *end++ = L',';
  }
  enum GesherUiLanguage language = GesherUiMatch(tags);
  HeapFree(GetProcessHeap(), 0, tags);
  return language;
}
