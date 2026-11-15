// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
#pragma once
#include <windows.h>
#include "ui_messages.h"

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

  wchar_t all_tags[1024];
  all_tags[0] = L'\0';
  size_t offset = 0;

  // 1. User preferred languages for apps & websites (Settings -> Language list).
  HKEY key = NULL;
  if (RegOpenKeyExW(HKEY_CURRENT_USER, L"Control Panel\\International\\User Profile", 0, KEY_QUERY_VALUE, &key) == ERROR_SUCCESS) {
    DWORD type = 0, bytes = 0;
    if (RegQueryValueExW(key, L"Languages", NULL, &type, NULL, &bytes) == ERROR_SUCCESS &&
        (type == REG_MULTI_SZ || type == REG_SZ) && bytes > sizeof(wchar_t)) {
      wchar_t* reg_tags = (wchar_t*)HeapAlloc(GetProcessHeap(), 0, bytes + 2 * sizeof(wchar_t));
      if (reg_tags != NULL) {
        if (RegQueryValueExW(key, L"Languages", NULL, &type, (LPBYTE)reg_tags, &bytes) == ERROR_SUCCESS) {
          DWORD chars = bytes / sizeof(wchar_t);
          for (DWORD i = 0; i < chars && offset + 1 < sizeof(all_tags)/sizeof(all_tags[0]); ++i) {
            if (reg_tags[i] == L'\0') {
              if (offset > 0 && all_tags[offset - 1] != L',' && i + 1 < chars && reg_tags[i + 1] != L'\0') {
                all_tags[offset++] = L',';
              }
            } else {
              all_tags[offset++] = reg_tags[i];
            }
          }
          all_tags[offset] = L'\0';
        }
        HeapFree(GetProcessHeap(), 0, reg_tags);
      }
    }
    RegCloseKey(key);
  }

  // 2. Windows UI display languages (GetUserPreferredUILanguages).
  ULONG count = 0, length = 0;
  if (GetUserPreferredUILanguages(MUI_LANGUAGE_NAME, &count, NULL, &length) && length > 1) {
    wchar_t* tags = (wchar_t*)HeapAlloc(GetProcessHeap(), 0, length * sizeof(wchar_t));
    if (tags != NULL) {
      if (GetUserPreferredUILanguages(MUI_LANGUAGE_NAME, &count, tags, &length)) {
        if (offset > 0 && offset + 1 < sizeof(all_tags)/sizeof(all_tags[0]) && all_tags[offset - 1] != L',') {
          all_tags[offset++] = L',';
        }
        for (ULONG i = 0; i < length && offset + 1 < sizeof(all_tags)/sizeof(all_tags[0]); ++i) {
          if (tags[i] == L'\0') {
            if (offset > 0 && all_tags[offset - 1] != L',' && i + 1 < length && tags[i + 1] != L'\0') {
              all_tags[offset++] = L',';
            }
          } else {
            all_tags[offset++] = tags[i];
          }
        }
        all_tags[offset] = L'\0';
      }
      HeapFree(GetProcessHeap(), 0, tags);
    }
  }

  if (all_tags[0] != L'\0') {
    return GesherUiMatch(all_tags);
  }
  return GESHER_UI_EN;
}
