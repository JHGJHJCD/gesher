param([string] $CompilePath = (Join-Path $PSScriptRoot '../../../tools/boot-image/compile.ps1'))
$ErrorActionPreference = 'Stop'
$CompilePath = [IO.Path]::GetFullPath($CompilePath)
$originalDirectory = Get-Location
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('gesher-image-format-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testRoot
$dist = Join-Path $testRoot 'dist'
$null = New-Item -ItemType Directory -Path $dist
$global:BootImageFormatInvocations = @()
function Start-Process {
    param($FilePath, $ArgumentList, [switch]$NoNewWindow, [switch]$PassThru,
        $RedirectStandardOutput, $RedirectStandardError)
    $global:BootImageFormatInvocations += ,@($ArgumentList)
    [IO.File]::WriteAllText($RedirectStandardOutput, '')
    [IO.File]::WriteAllText($RedirectStandardError, '')
    $process = [pscustomobject]@{ Id = 1; Handle = 1; ExitCode = 0 }
    $process | Add-Member ScriptMethod WaitForExit { param($Milliseconds) if ($args.Count -eq 0 -and $null -eq $Milliseconds) { return }; return $true }
    return $process
}
try {
    foreach ($format in @('default', 'lz4', 'lz4hc', 'uncompressed', 'LZ4')) {
        $global:BootImageFormatInvocations = @()
        $parameters = @{ Dist = $dist; Out = (Join-Path $testRoot $format); Filter = 'speed'; ExtensionFilter = 'speed'; AppFilter = 'verify' }
        if ($format -ne 'default') { $parameters.ImageFormat = $format }
        $null = & $CompilePath @parameters 6>$null
        if ($global:BootImageFormatInvocations.Count -ne 3) { throw "Expected all three compiler stages for $format" }
        $expected = if ($format -eq 'default') { 'lz4' } else { $format.ToLowerInvariant() }
        for ($stage = 0; $stage -lt 3; $stage++) {
            $arguments = $global:BootImageFormatInvocations[$stage]
            if (@($arguments | Where-Object { $_ -like '--image-format=*' }).Count -ne 1 -or
                    $arguments -notcontains "--image-format=$expected") {
                throw "Image format missing or duplicated for $format stage $stage"
            }
            $filter = if ($stage -eq 2) { 'verify' } else { 'speed' }
            if ($arguments -notcontains "--compiler-filter=$filter") { throw 'Image format changed the compiler filter' }
        }
    }
    $global:BootImageFormatInvocations = @()
    $rejected = $false
    try { & $CompilePath -Dist $dist -Out (Join-Path $testRoot 'invalid') -ImageFormat invalid } catch {
        if ($_.FullyQualifiedErrorId -notlike 'ParameterArgumentValidationError*') { throw }
        $rejected = $true
    }
    if (-not $rejected -or $global:BootImageFormatInvocations.Count -ne 0) { throw 'Unsupported format reached the compiler' }
    Write-Host 'PASS boot image format: defaults, accepted formats, case normalization, all stages, compiler filters and invalid values'
} finally {
    Remove-Variable BootImageFormatInvocations -Scope Global -ErrorAction SilentlyContinue
    Set-Location $originalDirectory
    $resolvedRoot = [IO.Path]::GetFullPath($testRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolvedRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected test cleanup path' }
    Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
}

