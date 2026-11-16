param([string] $PanelPath = (Join-Path $PSScriptRoot '../../../tools/package/gesher-control-panel.ps1'))

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName WindowsBase, PresentationFramework
# An app's "App info" (ADR 0087): the system server writes a package to
# data/system/control-panel-request and opens the Control Center. Execute the production
# request functions without a WPF window; the window and the dialog are stand-ins here.
$tokens = $null; $parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    [IO.Path]::GetFullPath($PanelPath), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
foreach ($name in 'Test-PanelRequestRecent', 'Read-PanelRequest', 'Get-AppInstallTimes', 'Invoke-PanelRequest') {
    $functions = @($ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true))
    if ($functions.Count -ne 1) { throw "Expected one production function: $name" }
    . ([scriptblock]::Create($functions[0].Extent.Text))
}
# The second instance's exit is guarded by the request and the mutex, in that order.
$text = [IO.File]::ReadAllText([IO.Path]::GetFullPath($PanelPath))
if ($text -notmatch "Mutex\]::new\(\`$false, 'Local\\Gesher\.ControlPanel'") { throw 'The panel no longer opens its named mutex unowned' }
if ($text -notmatch 'PanelInstanceCreated -and \(Test-PanelRequestRecent\)') { throw 'A second panel no longer leaves a request to the first' }

$script:Checks = 0
function Assert-Request([bool] $value, [string] $message) {
    if (-not $value) { throw $message }
    $script:Checks++
}

$root = Join-Path ([IO.Path]::GetTempPath()) ("gesher-panel-request-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force (Join-Path $root 'data/system') | Out-Null
try {
    $script:PanelRequestFile = Join-Path $root 'data/system/control-panel-request'
    $script:PanelRequestTaken = "$($script:PanelRequestFile).taken"

    # No request: nothing to do, nothing recent.
    Assert-Request ($null -eq (Read-PanelRequest)) 'A missing request was read'
    Assert-Request (-not (Test-PanelRequestRecent)) 'No request counted as recent'

    # A request is read once, moved aside, and counts as recent while it is fresh.
    [IO.File]::WriteAllText($script:PanelRequestFile, "com.example.app`n")
    Assert-Request (Test-PanelRequestRecent) 'A pending request was not recent'
    Assert-Request ((Read-PanelRequest) -eq 'com.example.app') 'The request package was not read'
    Assert-Request (-not (Test-Path -LiteralPath $script:PanelRequestFile)) 'The request was not taken'
    Assert-Request ($null -eq (Read-PanelRequest)) 'A taken request was read twice'
    Assert-Request (Test-PanelRequestRecent) 'A just-taken request was not recent (a second panel would open)'
    [IO.File]::SetLastWriteTimeUtc($script:PanelRequestTaken, [DateTime]::UtcNow.AddMinutes(-5))
    Assert-Request (-not (Test-PanelRequestRecent)) 'An old taken request still blocks a new panel'

    # packages.list times (ms since 1970) become local dates; unknown packages have none.
    [IO.File]::WriteAllText((Join-Path $root 'data/system/packages.list'),
        "# package`tcode path`tfirst install`tlast update`ncom.example.app`t/data/app/x`t1700000000000`t1700000600000`n")
    $times = Get-AppInstallTimes 'com.example.app'
    Assert-Request ($times.Count -eq 2 -and $times[0] -and $times[1] -and $times[0] -ne $times[1]) 'Install times were not read'
    Assert-Request ($null -eq (Get-AppInstallTimes 'com.example.other')) 'An unknown package had times'

    # Invoke-PanelRequest: raises the window; only a package name reaches the dialog.
    $script:IsBusy = $false
    $script:ShowingAppInfo = $false
    $script:Shown = [System.Collections.Generic.List[string]]::new()
    $window = [PSCustomObject]@{ WindowState = [System.Windows.WindowState]::Minimized; Topmost = $false; Activations = 0 }
    $window | Add-Member -MemberType ScriptMethod -Name Activate -Value { $this.Activations++; $true }
    function Show-AppInfoDialog([string] $pkg) {
        $script:Shown.Add($pkg)
        # A request arriving while the dialog is open waits for it to close.
        [IO.File]::WriteAllText($script:PanelRequestFile, "com.example.nested`n")
        Invoke-PanelRequest
        Assert-Request (Test-Path -LiteralPath $script:PanelRequestFile) 'A request was taken while a dialog was open'
        Remove-Item -LiteralPath $script:PanelRequestFile
    }
    [IO.File]::WriteAllText($script:PanelRequestFile, "com.example.app`n")
    Invoke-PanelRequest
    Assert-Request ($script:Shown.Count -eq 1 -and $script:Shown[0] -eq 'com.example.app') 'The app details were not shown'
    Assert-Request ($window.WindowState -eq [System.Windows.WindowState]::Normal -and $window.Activations -eq 1) 'The panel was not brought to the front'
    Assert-Request (-not $script:ShowingAppInfo) 'The dialog guard was left set'

    foreach ($bad in '', '../../evil', 'com.example.app extra', 'single', '1com.example') {
        [IO.File]::WriteAllText($script:PanelRequestFile, "$bad`n")
        Invoke-PanelRequest
    }
    Assert-Request ($script:Shown.Count -eq 1) 'A request that is not a package name reached the dialog'
    Assert-Request ($window.Activations -eq 6) 'An empty or invalid request did not still raise the panel'

    $script:IsBusy = $true
    [IO.File]::WriteAllText($script:PanelRequestFile, "com.example.app`n")
    Invoke-PanelRequest
    Assert-Request (Test-Path -LiteralPath $script:PanelRequestFile) 'A busy panel took a request'
} finally {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
"PASS: control panel App info requests ($script:Checks checks)"
