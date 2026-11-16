# The production export registry is compared with a separate Win32 stack oracle.
# The public NDK fixture also runs through native and translated Android ABIs.
# RunTimed, Check and $Dir come from the existing framework-contract tracks.
$pthreadAttributesOut = RunTimed "$Dir/pthread_attributes_target_test.exe" @() 60
$pthreadAttributesCode = $LASTEXITCODE
Check 'native: pthread attributes describe the requested live Windows thread' {
  $global:LASTEXITCODE = $pthreadAttributesCode
  $pthreadAttributesOut
} @('pthread attributes:', ' checks, 0 failures')

foreach ($pthreadAttributesGuest in @('pthread_attributes_x64', 'pthread_attributes_arm64')) {
  $pthreadGuestOut = RunTimed "$Dir/gesher-exec.exe" @(
    '--elf', (Resolve-Path "$Dir/$pthreadAttributesGuest").Path, $pthreadAttributesGuest
  ) 60
  $pthreadGuestCode = $LASTEXITCODE
  Check "native: $pthreadAttributesGuest public pthread target-stack and attribute contracts" {
    $global:LASTEXITCODE = $pthreadGuestCode
    $pthreadGuestOut
  } @('PASS guest pthread attributes')
}
