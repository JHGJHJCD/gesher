# Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
# Synchronize only runtime-owned files into the Android profile. The installed
# version invalidates the cache once per upgrade, including equal-size files
# with identical timestamps. Ordinary launches do not hash large ICU/font files.

function New-GesherRuntimeSync {
    param(
        [Parameter(Mandatory = $true)][string] $InstallDirectory,
        [Parameter(Mandatory = $true)][string] $ProfileDirectory,
        [Parameter(Mandatory = $true)][ValidateSet('app', 'dex')][string] $Component
    )
    $metadata = Join-Path $InstallDirectory 'gesher-version.ini'
    $generation = $null
    if (Test-Path -LiteralPath $metadata -PathType Leaf) {
        $info = Get-Item -LiteralPath $metadata -Force
        if ($info.Length -le 4096) {
            $text = [IO.File]::ReadAllText($info.FullName)
            if ($text -match '(?m)^Version=([0-9]+\.[0-9]+\.[0-9]+)\s*$') {
                $generation = [IO.Path]::GetFullPath($InstallDirectory) + "`n" + $Matches[1]
            }
        }
    }
    $stamp = Join-Path $ProfileDirectory ("system/.gesher-runtime-{0}.version" -f $Component)
    $previous = $null
    if ($generation -and (Test-Path -LiteralPath $stamp -PathType Leaf)) {
        try {
            if ((Get-Item -LiteralPath $stamp -Force).Length -le 8192) {
                $previous = [IO.File]::ReadAllText($stamp)
            }
        } catch { } # A missing/unreadable stamp requires a complete refresh.
    }
    return [PSCustomObject]@{
        Generation = $generation
        StampPath = $stamp
        ForceRefresh = ($null -ne $generation -and $generation -cne $previous)
    }
}

function Sync-GesherRuntimeFile {
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $Destination,
        [Parameter(Mandatory = $true)] $State
    )
    $sourceInfo = Get-Item -LiteralPath $Source -ErrorAction Stop
    if ($sourceInfo.PSIsContainer) { throw "Expected a runtime file: $Source" }
    $copy = [bool]$State.ForceRefresh
    if (-not (Test-Path -LiteralPath $Destination -PathType Leaf)) {
        $copy = $true
    } else {
        $destinationInfo = Get-Item -LiteralPath $Destination -ErrorAction Stop
        if ($sourceInfo.Length -ne $destinationInfo.Length -or
            $sourceInfo.LastWriteTimeUtc -ne $destinationInfo.LastWriteTimeUtc) {
            $copy = $true
        } elseif (-not $State.Generation) {
            # Unversioned developer builds need a content check; their timestamps
            # and sizes do not constitute an immutable release identity.
            $copy = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash -cne
                    (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash
        }
    }
    if ($copy) {
        Copy-Item -LiteralPath $Source -Destination $Destination -ErrorAction Stop
    }
}

function Complete-GesherRuntimeSync {
    param([Parameter(Mandatory = $true)] $State)
    if (-not $State.Generation -or -not $State.ForceRefresh) { return }
    # Called only after every requested copy succeeds. Separate app/dex stamps
    # keep the small dex launcher from acknowledging the full framework refresh.
    $parent = Split-Path -Parent $State.StampPath
    $null = [IO.Directory]::CreateDirectory($parent)
    $temporary = Join-Path $parent ([IO.Path]::GetRandomFileName())
    try {
        [IO.File]::WriteAllText($temporary, $State.Generation, [Text.UTF8Encoding]::new($false))
        if ([IO.File]::Exists($State.StampPath)) {
            [IO.File]::Replace($temporary, $State.StampPath, [NullString]::Value)
        } else {
            try { [IO.File]::Move($temporary, $State.StampPath) } catch [IO.IOException] {
                if (-not [IO.File]::Exists($State.StampPath)) { throw }
                # Another launcher may have completed the same generation first.
                [IO.File]::Replace($temporary, $State.StampPath, [NullString]::Value)
            }
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}
