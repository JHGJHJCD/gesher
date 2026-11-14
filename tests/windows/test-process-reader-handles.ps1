# Compile the actual shared helper and bounded real Win32 controls together.
# A fresh PowerShell process is required; no runtime, app or artifact is substituted.
param([string] $HelperPath = (Join-Path $PSScriptRoot 'helpers/ProcessReaderHandles.ps1'))
$ErrorActionPreference = 'Stop'
if (-not [Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
    [Runtime.InteropServices.OSPlatform]::Windows)) { throw 'real Windows handle controls require Windows' }
$HelperPath = (Resolve-Path -LiteralPath $HelperPath).Path
$fixture = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'fixtures/ProcessReaderHandleContracts.cs')).Path
$source = [IO.File]::ReadAllText($HelperPath)
$matches = [regex]::Matches($source, "(?s)Add-Type\s+-TypeDefinition\s+@'\r?\n(?<source>.*?)\r?\n'@")
if ($matches.Count -ne 1) { throw 'shared helper must expose exactly one unmodified C# body' }
Write-Host "READER_HELPER_SOURCE path=$HelperPath sha256=$((Get-FileHash -LiteralPath $HelperPath -Algorithm SHA256).Hash)"
Write-Host "READER_CONTROL_SOURCE path=$fixture sha256=$((Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash)"
$combined = $matches[0].Groups['source'].Value + [Environment]::NewLine + [IO.File]::ReadAllText($fixture)
Add-Type -TypeDefinition $combined -CompilerOptions '/warnaserror+' -ErrorAction Stop
$code = [ProcessReaderHandleContracts.Fixture]::Run()
if ($code -ne 0) { throw 'real process-reader ownership controls failed' }
