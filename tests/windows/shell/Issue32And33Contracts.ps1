# Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
# Exercise production dialog wrappers and, on Windows, the actual WPF windows.
# APK verification has Java behavior tests; source matches cannot verify signatures.
param(
    [string] $PanelPath = (Join-Path $PSScriptRoot '../../../tools/package/gesher-control-panel.ps1'),
    [string] $UiMessagesPath = (Join-Path $PSScriptRoot '../../../tools/package/ui-messages.json')
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
$script:DialogChecks = 0
function Assert-Dialog([bool] $condition, [string] $message) {
    if (-not $condition) { throw "FAIL: $message" }
    $script:DialogChecks++
}

$tokens = $null; $parseErrors = $null
$panelAst = [Management.Automation.Language.Parser]::ParseFile($PanelPath, [ref]$tokens, [ref]$parseErrors)
Assert-Dialog ($parseErrors.Count -eq 0) 'Control panel parses without errors'
$uiAst = [Management.Automation.Language.Parser]::ParseFile(
    (Join-Path (Split-Path -Parent $PanelPath) 'gesher-ui.ps1'), [ref]$tokens, [ref]$parseErrors)
Assert-Dialog ($parseErrors.Count -eq 0) 'UI helper parses without errors'
$textFunction = $uiAst.Find({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-GesherText'
}, $false)
. ([scriptblock]::Create($textFunction.Extent.Text))
$script:GesherUiCatalog = Get-Content -LiteralPath $UiMessagesPath -Raw -Encoding UTF8 | ConvertFrom-Json
$script:DialogFunctions = @{}
foreach ($name in 'Show-UninstallConfirmDialog', 'Confirm-PanelUpdateInstallation', 'Show-ModernConfirmDialog', 'Show-ModernMessageDialog') {
    $definition = $panelAst.Find({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $false)
    Assert-Dialog ($null -ne $definition) "Production function exists: $name"
    $script:DialogFunctions[$name] = $definition.Extent.Text
    . ([scriptblock]::Create($definition.Extent.Text))
}

# Execute the real wrappers; replace only their modal boundary. This detects
# invalid argument expressions, lost results and incorrect parameter forwarding.
function Show-ModernConfirmDialog {
    param([string] $Title, [string] $Message, [string] $Detail,
        [string] $ConfirmText, [string] $CancelText, [switch] $IsDanger,
        [switch] $IsWarning, $IconImage, [string] $SubTitle)
    $script:ForwardedDialog = @{} + $PSBoundParameters
    return $script:ApproveDialog
}
$literalLabel = 'Probe & <tag> "{1}"'
$iconMarker = [object]::new()
foreach ($language in 'en', 'he') {
    $script:GesherUiLanguage = $language
    foreach ($approved in $true, $false) {
        $script:ApproveDialog = $approved
        foreach ($label in $literalLabel, 'gesher.test.dialog') {
            $result = @(Show-UninstallConfirmDialog -pkg 'gesher.test.dialog' -label $label -iconImage $iconMarker)
            Assert-Dialog ($result.Count -eq 1 -and $result[0] -is [bool] -and $result[0] -eq $approved) "Uninstall returns only the modal decision ($language/$approved)"
            $forwarded = $script:ForwardedDialog
            Assert-Dialog ($forwarded.Title -eq (Get-GesherText 'cp_uninstall_title')) 'Localized uninstall title'
            Assert-Dialog ($forwarded.Message -eq (Get-GesherText 'cp_uninstall_confirm' @($label))) 'App label and placeholder-like text remain literal'
            Assert-Dialog ($forwarded.Detail -eq (Get-GesherText 'cp_uninstall_detail')) 'Localized data-removal explanation'
            Assert-Dialog ($forwarded.ConfirmText -eq (Get-GesherText 'cp_btn_uninstall') -and $forwarded.CancelText -eq (Get-GesherText 'cp_update_cancel')) 'Localized action buttons'
            Assert-Dialog ([bool]$forwarded.IsDanger -and [object]::ReferenceEquals($forwarded.IconImage, $iconMarker)) 'Danger styling and original app icon are forwarded'
            $expectedSubtitle = if ($label -eq 'gesher.test.dialog') { '' } else { 'gesher.test.dialog' }
            Assert-Dialog ([string]$forwarded.SubTitle -eq $expectedSubtitle) 'Subtitle appears only when different from label'
        }
        $script:UpdateState = @{ Release = @{ Version = '1.2.3' } }
        $result = @(Confirm-PanelUpdateInstallation)
        Assert-Dialog ($result.Count -eq 1 -and $result[0] -is [bool] -and $result[0] -eq $approved) "Update returns only the modal decision ($language/$approved)"
        Assert-Dialog ($script:ForwardedDialog.Message -eq (Get-GesherText 'cp_update_confirm' @('1.2.3'))) 'Update version reaches the confirmation message'
        Assert-Dialog ([bool]$script:ForwardedDialog.IsWarning) 'Update retains warning styling'
    }
}
Write-Host "PASS: $script:DialogChecks production dialog wrapper checks"

if ($env:OS -ne 'Windows_NT') {
    Write-Host 'SKIP: actual WPF interactions require Windows; the Windows CI gates run them.'
    return
}
Assert-Dialog ([Threading.Thread]::CurrentThread.ApartmentState -eq 'STA') 'WPF checks run in STA'
Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase, UIAutomationProvider, UIAutomationTypes
. ([scriptblock]::Create($script:DialogFunctions['Show-ModernConfirmDialog']))

function Get-DialogText($node) {
    if ($node -is [System.Windows.Controls.TextBlock]) { $node.Text }
    if ($node -is [System.Windows.DependencyObject]) {
        foreach ($child in [System.Windows.LogicalTreeHelper]::GetChildren($node)) { Get-DialogText $child }
    }
}
function Invoke-WpfDialogCase {
    param([scriptblock] $Show, [ValidateSet('Confirm', 'Cancel', 'Close', 'Ok')] [string] $Action,
        [string] $ExpectedTitle, [string] $ExpectedText, [switch] $MessageDialog)
    $script:DialogFixture = @{
        Action = $Action; Title = $ExpectedTitle; Text = $ExpectedText
        MessageDialog = [bool]$MessageDialog; Observed = $false; Failure = $null
        Deadline = [DateTime]::UtcNow.AddSeconds(10)
    }
    $timer = [System.Windows.Threading.DispatcherTimer]::new()
    $timer.Interval = [TimeSpan]::FromMilliseconds(20)
    $timer.Add_Tick({
        param($sender, $eventArgs)
        try {
            $case = $script:DialogFixture
            if ([DateTime]::UtcNow -gt $case.Deadline) { throw 'Production modal did not close within 10 seconds' }
            if ($case.Observed) { return }
            $dialogs = @($script:window.OwnedWindows | Where-Object { $_.IsVisible })
            if ($dialogs.Count -eq 0) { return }
            Assert-Dialog ($dialogs.Count -eq 1) 'Exactly one production modal opens'
            $modal = $dialogs[0]
            $case.Observed = $true
            Assert-Dialog ([object]::ReferenceEquals($modal.Owner, $script:window)) 'Modal belongs to the control panel'
            Assert-Dialog ($modal.FlowDirection.ToString() -eq $script:flowDirection) 'Production reading direction matches selected language'
            Assert-Dialog ($modal.Title -eq $case.Title) 'Literal title survives production XAML'
            Assert-Dialog ((@(Get-DialogText $modal)) -contains $case.Text) 'Literal message and XML characters survive production XAML'
            if ($case.MessageDialog) {
                $button = $modal.FindName('BtnOk')
                Assert-Dialog ($button.Content -eq (Get-GesherText 'cp_dialog_ok') -and $button.IsCancel) 'Message has a localized dismiss button'
            } else {
                Assert-Dialog ($modal.FindName('BtnCancel').IsCancel) 'Cancel remains the WPF cancel action'
                Assert-Dialog ($modal.FindName('BtnCancel').Content -eq (Get-GesherText 'cp_update_cancel')) 'Cancel label is localized'
                $button = $modal.FindName('BtnConfirm')
            }
            if ($case.Action -eq 'Close') {
                $modal.Close()
            } elseif ($case.Action -eq 'Cancel') {
                # The automation provider invokes Button.OnClick, including
                # IsCancel handling. Raising Click alone omits that behavior.
                $peer = [System.Windows.Automation.Peers.ButtonAutomationPeer]::new($modal.FindName('BtnCancel'))
                $provider = $peer.GetPattern([System.Windows.Automation.Peers.PatternInterface]::Invoke)
                [System.Windows.Automation.Provider.IInvokeProvider].GetMethod('Invoke').Invoke($provider, $null)
            } else {
                $button.RaiseEvent([System.Windows.RoutedEventArgs]::new([System.Windows.Controls.Button]::ClickEvent))
            }
        } catch {
            $script:DialogFixture.Failure = $_
            $sender.Stop()
            # Closing the owner also ends a broken child's nested modal loop.
            $script:window.Close()
        }
    })
    try {
        $timer.Start()
        $result = @(& $Show)
        if ($script:DialogFixture.Failure) { throw $script:DialogFixture.Failure }
        Assert-Dialog $script:DialogFixture.Observed 'Production function displayed a modal'
        if ($MessageDialog) {
            Assert-Dialog ($result.Count -eq 0) 'Message dialog does not leak return values'
        } else {
            Assert-Dialog ($result.Count -eq 1 -and $result[0] -is [bool]) 'Confirmation produces exactly one boolean'
            Assert-Dialog ($result[0] -eq ($Action -eq 'Confirm')) 'Only explicit confirmation approves the action'
        }
    } finally {
        $timer.Stop()
        foreach ($owned in @($script:window.OwnedWindows)) { $owned.Close() }
    }
}

$script:window = [System.Windows.Window]::new()
$window.ShowInTaskbar = $false
$window.ShowActivated = $false
$window.Opacity = 0
$window.Width = 80
$window.Height = 80
try {
    $window.Show()
    foreach ($language in 'en', 'he') {
        $script:GesherUiLanguage = $language
        $script:flowDirection = if ($language -eq 'he') { 'RightToLeft' } else { 'LeftToRight' }
        foreach ($action in 'Confirm', 'Cancel', 'Close') {
            $fixture = @{ Action = $action; ExpectedTitle = (Get-GesherText 'cp_uninstall_title'); ExpectedText = (Get-GesherText 'cp_uninstall_confirm' @($literalLabel)) }
            Invoke-WpfDialogCase @fixture -Show { Show-UninstallConfirmDialog -pkg 'gesher.test.dialog' -label $literalLabel }
        }
        foreach ($action in 'Confirm', 'Cancel') {
            $fixture = @{ Action = $action; ExpectedTitle = (Get-GesherText 'cp_update_title'); ExpectedText = (Get-GesherText 'cp_update_confirm' @('1.2.3')) }
            Invoke-WpfDialogCase @fixture -Show { Confirm-PanelUpdateInstallation }
        }
        foreach ($isError in $true, $false) {
            Invoke-WpfDialogCase -Action Ok -MessageDialog -ExpectedTitle $literalLabel -ExpectedText $literalLabel -Show { Show-ModernMessageDialog -Title $literalLabel -Message $literalLabel -IsError $isError }
        }
    }
} finally {
    $window.Close()
}
Write-Host "PASS: $script:DialogChecks production wrapper and WPF interaction checks"
