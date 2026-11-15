# Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
# Dot-sourced by the packaged entry points. Explicit UTF-8 also works in Windows PowerShell 5.
$uiDir = if ($PSScriptRoot) { $PSScriptRoot } elseif ($MyInvocation.MyCommand.Path) { Split-Path -Parent $MyInvocation.MyCommand.Path } else { '.' }
$script:GesherUiCatalog = Get-Content -LiteralPath (Join-Path $uiDir 'ui-messages.json') -Raw -Encoding UTF8 | ConvertFrom-Json

function Get-GesherUiLanguage([string] $Tags) {
  foreach ($tag in ($Tags -split ',')) {
    $language = ($tag.Trim().ToLowerInvariant() -split '[-_]', 2)[0]
    if ($language -eq 'iw') { $language = 'he' }
    if ($script:GesherUiCatalog.languages.PSObject.Properties.Name -contains $language) { return $language }
  }
  return 'en'
}

if (-not ('Gesher.UiLanguages' -as [type])) {
  Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using Microsoft.Win32;
namespace Gesher {
  public static class UiLanguages {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern bool GetUserPreferredUILanguages(uint flags, out uint count, IntPtr buffer, ref uint size);
    public static string Read() {
      var list = new List<string>();
      try {
        using (var key = Registry.CurrentUser.OpenSubKey(@"Control Panel\International\User Profile")) {
          if (key != null) {
            string[] langs = key.GetValue("Languages") as string[];
            if (langs != null) {
              foreach (var l in langs) {
                if (!string.IsNullOrWhiteSpace(l) && !list.Contains(l)) list.Add(l);
              }
            }
          }
        }
      } catch {}
      uint count, size = 0;
      if (GetUserPreferredUILanguages(8, out count, IntPtr.Zero, ref size) && size >= 2) {
        IntPtr buffer = Marshal.AllocHGlobal(checked((int)size * 2));
        try {
          if (GetUserPreferredUILanguages(8, out count, buffer, ref size)) {
            string ui = Marshal.PtrToStringUni(buffer, (int)size);
            if (!string.IsNullOrEmpty(ui)) {
              foreach (var l in ui.Split(new char[] {'\0'}, StringSplitOptions.RemoveEmptyEntries)) {
                if (!string.IsNullOrWhiteSpace(l) && !list.Contains(l)) list.Add(l);
              }
            }
          }
        } finally { Marshal.FreeHGlobal(buffer); }
      }
      return list.Count > 0 ? string.Join(",", list) : "en";
    }
  }
}
'@
}
$script:GesherUiTags = if ([string]::IsNullOrWhiteSpace($env:GESHER_LOCALES)) { [Gesher.UiLanguages]::Read() } else { $env:GESHER_LOCALES }
$script:GesherUiLanguage = Get-GesherUiLanguage $script:GesherUiTags

function Get-GesherText([string] $Key, [object[]] $Values = @()) {
  $entry = $script:GesherUiCatalog.messages.PSObject.Properties[$Key]
  if ($null -eq $entry) { throw "Unknown Gesher UI text: $Key" }
  $template = $entry.Value.PSObject.Properties[$script:GesherUiLanguage].Value
  # One pass: a path or app name containing {1} stays literal.
  $result = [regex]::Replace($template, '\{(\d+)\}', [System.Text.RegularExpressions.MatchEvaluator] {
    param($match)
    $index = [int]$match.Groups[1].Value
    if ($index -ge $Values.Count) { throw "Missing Gesher UI argument: $Key" }
    return [string]$Values[$index]
  })
  return $result
}
