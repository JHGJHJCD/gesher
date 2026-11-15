# Bluetooth (docs/bluetooth.md, ADR 0084): the android.bluetooth classes over the host
# radio. Dot-sourced by run-tests.ps1, using Check, NoDrive, $Dir, $root and $failed from
# there and RunTimed from 50-services.ps1. The runners have no radio: one run checks
# that there is no adapter (and logs what Windows answered); the other runs every path
# above the JNI layer over GESHER_BLUETOOTH_TEST's radio (compat/devices/bluetooth_test.cc).

Copy-Item "$Dir/framework-res.apk" (Join-Path $root 'system/framework') -Force
Copy-Item "$Dir/build.prop" (Join-Path $root 'system') -Force
$bcpBt = (('core-oj', 'core-libart', 'core-icu4j', 'framework', 'gesher-app') | ForEach-Object { NoDrive "$Dir/$_.jar" }) -join ':'
$dvmBt = @("-Xbootclasspath:$bcpBt", "-Ximage:$(NoDrive "$root/system")/framework/boot.art", '-Xint', '-Xusejit:false',
  '-cp', (NoDrive "$Dir/services-tests.dex"), 'gesher.services.BluetoothHostTest')

$hadTest = Test-Path Env:GESHER_BLUETOOTH_TEST
$savedTest = $env:GESHER_BLUETOOTH_TEST
try {
  if ($hadTest) { Remove-Item Env:GESHER_BLUETOOTH_TEST }
  $noRadioOut = RunTimed "$Dir/dalvikvm.exe" ($dvmBt + '--no-radio') 120
  $noRadioCode = $LASTEXITCODE
  Check 'bluetooth: without a radio there is no adapter (Windows.Devices.Bluetooth answers no default adapter)' {
    $global:LASTEXITCODE = $noRadioCode
    $noRadioOut
  } @('BluetoothHostTest: Windows: no adapter', 'BluetoothHostTest no radio passed:')

  $env:GESHER_BLUETOOTH_TEST = '1'
  $btOut = RunTimed "$Dir/dalvikvm.exe" $dvmBt 240
  $btCode = $LASTEXITCODE
} finally {
  if ($hadTest) { $env:GESHER_BLUETOOTH_TEST = $savedTest } else { Remove-Item Env:GESHER_BLUETOOTH_TEST -ErrorAction SilentlyContinue }
}

foreach ($c in @(
  @('permissions: BLUETOOTH_CONNECT/BLUETOOTH_SCAN on API 31+, legacy BLUETOOTH below', 'BluetoothHostTest: permissions:'),
  @('adapter state, name, hidden address and the bonded headset (class, service classes, isConnected)', 'BluetoothHostTest: adapter:'),
  @('A2DP and headset profile proxies list the connected headset', 'BluetoothHostTest: profiles:'),
  @('LE scan: filters, advertisement merged with its scan response, legacy startLeScan', 'BluetoothHostTest: LE scan:'),
  @('GATT client: connect, discovery, read, CCCD notifications, write and echo, MTU, disconnect', 'BluetoothHostTest: GATT:'),
  @('RFCOMM: service UUID and channel sockets, available(), listener accept', 'BluetoothHostTest: RFCOMM:'),
  @('system broadcasts: radio off/on, ACL and A2DP state, discovery, ACTION_UUID', 'BluetoothHostTest: broadcasts:')
)) {
  Check "bluetooth (test radio): $($c[0])" {
    $global:LASTEXITCODE = $btCode
    $btOut
  } @($c[1], 'BluetoothHostTest all passed:')
}
