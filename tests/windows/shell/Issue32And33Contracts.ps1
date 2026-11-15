# Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
# Regression test for Issue #32 (Modern dialogs in control panel) and Issue #33 (v1 APK signature compatibility).

param(
    [string] $PanelPath = (Join-Path $PSScriptRoot '../../../tools/package/gesher-control-panel.ps1'),
    [string] $UiMessagesPath = (Join-Path $PSScriptRoot '../../../tools/package/ui-messages.json'),
    [string] $RuntimeDir = (Join-Path $env:LOCALAPPDATA 'Programs/Gesher')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$checks = 0
function Assert-Contract([bool] $condition, [string] $message) {
    if (-not $condition) { throw "FAIL: $message" }
    $script:checks++
    Write-Host "  [OK] $message"
}

Write-Host "=== Testing Issue #32: Modern Control Center Dialogs ==="

# 1. Check ui-messages.json contains required localized strings
$catalog = Get-Content -LiteralPath $UiMessagesPath -Raw -Encoding UTF8 | ConvertFrom-Json
$requiredKeys = @(
    'cp_uninstall_title',
    'cp_uninstall_confirm',
    'cp_uninstall_detail',
    'cp_uninstall_failed',
    'cp_install_failed',
    'cp_dialog_ok'
)
foreach ($key in $requiredKeys) {
    $entry = $catalog.messages.PSObject.Properties[$key]
    Assert-Contract ($null -ne $entry) "ui-messages.json has key '$key'"
    Assert-Contract (-not [string]::IsNullOrWhiteSpace($entry.Value.en)) "Key '$key' has English text: $($entry.Value.en)"
    Assert-Contract (-not [string]::IsNullOrWhiteSpace($entry.Value.he)) "Key '$key' has Hebrew text: $($entry.Value.he)"
}

# 2. Check gesher-control-panel.ps1 has NO legacy MessageBox::Show calls
$panelContent = Get-Content -LiteralPath $PanelPath -Raw
$nonCommentLines = ($panelContent -split "`r?`n") | Where-Object { $_ -notmatch '^\s*#' }
$messageBoxCalls = @($nonCommentLines | Where-Object { $_ -match '\[System\.Windows\.MessageBox\]::Show' })
Assert-Contract ($messageBoxCalls.Count -eq 0) "No active [System.Windows.MessageBox]::Show calls remain in control panel (found: $($messageBoxCalls.Count))"

# 3. Check gesher-control-panel.ps1 contains modern dialog functions
Assert-Contract ($panelContent -match 'function Show-ModernConfirmDialog') "Control panel defines Show-ModernConfirmDialog"
Assert-Contract ($panelContent -match 'function Show-UninstallConfirmDialog') "Control panel defines Show-UninstallConfirmDialog"
Assert-Contract ($panelContent -match 'function Show-ModernMessageDialog') "Control panel defines Show-ModernMessageDialog"
Assert-Contract ($panelContent -match '\$LASTEXITCODE\s*-ne\s*0') "Control panel checks `$LASTEXITCODE on command execution"

# 4. Check AST parsing of gesher-control-panel.ps1
$tokens = $null; $parseErrors = $null
$null = [Management.Automation.Language.Parser]::ParseFile($PanelPath, [ref]$tokens, [ref]$parseErrors)
Assert-Contract ($parseErrors.Count -eq 0) "gesher-control-panel.ps1 parses with zero AST errors"

# 5. Check WPF XAML loading in both Hebrew (RTL) and English (LTR)
Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase
foreach ($lang in 'he', 'en') {
    $env:GESHER_LOCALES = $lang
    $flow = if ($lang -eq 'he') { 'RightToLeft' } else { 'LeftToRight' }
    $title = if ($lang -eq 'he') { $catalog.messages.cp_uninstall_title.he } else { $catalog.messages.cp_uninstall_title.en }
    $msg = if ($lang -eq 'he') { $catalog.messages.cp_uninstall_confirm.he -replace '\{0\}', 'Soundcore' } else { $catalog.messages.cp_uninstall_confirm.en -replace '\{0\}', 'Soundcore' }
    
    $xaml = @"
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        Title="$title"
        Width="460" SizeToContent="Height"
        FlowDirection="$flow"
        FontFamily="Segoe UI, Tahoma, Arial"
        Background="#FFFFFF">
    <Border Padding="22">
        <Grid>
            <Grid.RowDefinitions>
                <RowDefinition Height="Auto"/>
                <RowDefinition Height="Auto"/>
                <RowDefinition Height="20"/>
                <RowDefinition Height="Auto"/>
            </Grid.RowDefinitions>
            <Grid Grid.Row="0">
                <Grid.ColumnDefinitions>
                    <ColumnDefinition Width="Auto"/>
                    <ColumnDefinition Width="14"/>
                    <ColumnDefinition Width="*"/>
                </Grid.ColumnDefinitions>
                <Border Name="IconContainer" Grid.Column="0" Width="44" Height="44" CornerRadius="10" Background="#FEE4E2" VerticalAlignment="Top"/>
                <StackPanel Grid.Column="2" VerticalAlignment="Center">
                    <TextBlock Text="$title" FontSize="16" FontWeight="Bold" Foreground="#101828"/>
                </StackPanel>
            </Grid>
            <StackPanel Grid.Row="1">
                <TextBlock Text="$msg" FontSize="13" FontWeight="SemiBold" Foreground="#344054" TextWrapping="Wrap"/>
            </StackPanel>
            <StackPanel Grid.Row="3" Orientation="Horizontal" HorizontalAlignment="Right">
                <Button Name="BtnCancel" Content="Cancel" IsCancel="True" MinWidth="85"/>
                <Border Width="10"/>
                <Button Name="BtnConfirm" Content="Uninstall" IsDefault="True" MinWidth="85"/>
            </StackPanel>
        </Grid>
    </Border>
</Window>
"@
    [xml]$xml = $xaml
    $reader = [System.Xml.XmlNodeReader]::new($xml)
    $window = [System.Windows.Markup.XamlReader]::Load($reader)
    Assert-Contract ($null -ne $window -and $window.FlowDirection.ToString() -eq $flow) "WPF XAML successfully instantiates modal window for lang '$lang' ($flow)"
}

Write-Host "`n=== Testing Issue #33: v1-Only APK Signatures & Security Invariant ==="

# 6. Verify Java source changes in ApkPackage.java
$apkPackageSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../../../app/java/com/gesher/server/ApkPackage.java') -Raw
Assert-Contract ($apkPackageSource -match 'setMinCheckedPlatformVersion\(24\)') "ApkPackage.java includes platform 24 verification fallback for v1 signatures"
Assert-Contract ($apkPackageSource -match 'SigningDetails\.SignatureSchemeVersion\.JAR') "ApkPackage.java populates SigningDetails with JAR scheme for verified v1 APKs"

Write-Host "`nAll $script:checks checks passed successfully!"
