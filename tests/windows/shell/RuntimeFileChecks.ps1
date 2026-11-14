param([string] $HelperPath = (Join-Path $PSScriptRoot '../../../tools/package/gesher-runtime-files.ps1'))

$ErrorActionPreference = 'Stop'
. $HelperPath
$script:Checks = 0
$script:CopyCalls = 0
# Observe real copy attempts as well as Windows' read-sharing behavior. This
# makes the no-rewrite contract visible on hosts that do not enforce NT sharing.
function Copy-Item {
    [CmdletBinding()]
    param([string] $LiteralPath, [string] $Destination)
    $script:CopyCalls++
    Microsoft.PowerShell.Management\Copy-Item @PSBoundParameters
}
function Assert-RuntimeFile([bool] $Value, [string] $Message) {
    if (-not $Value) { throw $Message }
    $script:Checks++
}
$work = Join-Path ([IO.Path]::GetTempPath()) ('gesher-runtime-files-' + [guid]::NewGuid())
try {
    $install = Join-Path $work 'install [fixture]'
    $profile = Join-Path $work 'profile'
    $null = [IO.Directory]::CreateDirectory($install)
    $null = [IO.Directory]::CreateDirectory((Join-Path $profile 'system'))
    $null = [IO.Directory]::CreateDirectory((Join-Path $profile 'data/app/fixture'))
    $source = Join-Path $install 'build.prop'
    $destination = Join-Path $profile 'system/build.prop'
    $userData = Join-Path $profile 'data/app/fixture/user.txt'
    [IO.File]::WriteAllText($userData, 'keep user data')
    $metadata = Join-Path $install 'gesher-version.ini'
    [IO.File]::WriteAllText($metadata, "[Gesher]`r`nVersion=0.1.9`r`n")
    [IO.File]::WriteAllText($source, 'old data')
    $state = New-GesherRuntimeSync $install $profile app
    Assert-RuntimeFile $state.ForceRefresh 'First versioned launch must initialize runtime files'
    Sync-GesherRuntimeFile $source $destination $state
    Complete-GesherRuntimeSync $state
    Assert-RuntimeFile ([IO.File]::ReadAllText($destination) -ceq 'old data') 'Initial copy did not populate runtime file'

    $stampTime = [IO.File]::GetLastWriteTimeUtc($state.StampPath)
    $stable = New-GesherRuntimeSync $install $profile app
    Assert-RuntimeFile (-not $stable.ForceRefresh) 'Same version invalidated the runtime cache'
    # A read-only handle on Windows proves unchanged launches do not rewrite a mapped file.
    $handle = [IO.File]::Open($destination, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try { Sync-GesherRuntimeFile $source $destination $stable } finally { $handle.Dispose() }
    Complete-GesherRuntimeSync $stable
    Assert-RuntimeFile ([IO.File]::GetLastWriteTimeUtc($state.StampPath) -eq $stampTime) 'Ordinary launch rewrote generation stamp'

    $timestamp = [IO.File]::GetLastWriteTimeUtc($source)
    [IO.File]::WriteAllText($source, 'new data')
    [IO.File]::SetLastWriteTimeUtc($source, $timestamp)
    [IO.File]::WriteAllText($metadata, "[Gesher]`nVersion=0.1.10`n")
    $updated = New-GesherRuntimeSync $install $profile app
    Assert-RuntimeFile $updated.ForceRefresh 'New version did not invalidate runtime files'
    Sync-GesherRuntimeFile $source $destination $updated
    Complete-GesherRuntimeSync $updated
    Assert-RuntimeFile ([IO.File]::ReadAllText($destination) -ceq 'new data') 'Equal-size, equal-time resource stayed stale after upgrade'
    Assert-RuntimeFile ([IO.File]::ReadAllText($userData) -ceq 'keep user data') 'Runtime upgrade changed app data'

    $dex = New-GesherRuntimeSync $install $profile dex
    Assert-RuntimeFile $dex.ForceRefresh 'App completion incorrectly acknowledged the dex component'
    $copies = $script:CopyCalls
    $handle = [IO.File]::Open($destination, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        Sync-GesherRuntimeFile $source $destination $dex
        Complete-GesherRuntimeSync $dex
    } finally { $handle.Dispose() }
    Assert-RuntimeFile ($script:CopyCalls -eq $copies) 'Other launcher rewrote an identical, possibly mapped runtime file'
    Assert-RuntimeFile (-not (New-GesherRuntimeSync $install $profile dex).ForceRefresh) 'Other launcher could not acknowledge an identical, read-shared runtime file'
    # A release may preserve ICU bytes but rebuild with different timestamps.
    # This is still not a reason to overwrite a file an existing app has mapped.
    [IO.File]::SetLastWriteTimeUtc($source, $timestamp.AddMinutes(1))
    $handle = [IO.File]::Open($destination, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try { Sync-GesherRuntimeFile $source $destination $dex } finally { $handle.Dispose() }
    Assert-RuntimeFile ($script:CopyCalls -eq $copies) 'Timestamp-only difference rewrote identical, possibly mapped runtime bytes'
    Assert-RuntimeFile ([IO.File]::ReadAllText($destination) -ceq 'new data') 'Identical resource bytes changed during timestamp reconciliation'
    [IO.File]::WriteAllText($metadata, "[Gesher]`nVersion=0.1.11`n")
    $dex = New-GesherRuntimeSync $install $profile dex
    Complete-GesherRuntimeSync $dex
    $app = New-GesherRuntimeSync $install $profile app
    Assert-RuntimeFile $app.ForceRefresh 'Dex completion incorrectly acknowledged the full framework upgrade'

    $failed = $false
    try { Sync-GesherRuntimeFile (Join-Path $install 'missing') $destination $app } catch { $failed = $true }
    Assert-RuntimeFile $failed 'Missing runtime source was treated as a successful copy'
    $retry = New-GesherRuntimeSync $install $profile app
    Assert-RuntimeFile $retry.ForceRefresh 'Failed synchronization prevented retry'

    # Corrupt version state recovers by re-copying; unversioned development builds
    # compare real content even when both length and modification time match.
    [IO.File]::WriteAllText($app.StampPath, 'corrupt')
    Assert-RuntimeFile (New-GesherRuntimeSync $install $profile app).ForceRefresh 'Corrupt cache stamp was trusted'
    [IO.File]::Delete($metadata)
    [IO.File]::WriteAllText($source, 'dev data')
    [IO.File]::SetLastWriteTimeUtc($source, [IO.File]::GetLastWriteTimeUtc($destination))
    $development = New-GesherRuntimeSync $install $profile app
    Sync-GesherRuntimeFile $source $destination $development
    Assert-RuntimeFile ([IO.File]::ReadAllText($destination) -ceq 'dev data') 'Unversioned same-size resource did not refresh'
    Assert-RuntimeFile ([IO.File]::ReadAllText($userData) -ceq 'keep user data') 'Developer synchronization changed app data'
    foreach ($invalid in @(
        "Version=0.1.10`n",
        "[Other]`nVersion=0.1.10`n",
        "[Gesher]`nVersion=0.1.10`nVersion=0.1.11`n",
        "[Gesher]`nVersion=0.1.10`n[Gesher]`nVersion=0.1.10`n",
        "[Gesher]`nVersion=0.1.65536`n",
        "[Gesher]`nVersion=00.1.10`n"
    )) {
        [IO.File]::WriteAllText($metadata, $invalid)
        Assert-RuntimeFile ($null -eq (New-GesherRuntimeSync $install $profile app).Generation) 'Invalid installed version metadata was accepted as a cache identity'
    }
    'PASS: runtime file upgrade contracts ({0} checks)' -f $script:Checks
} finally {
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}
