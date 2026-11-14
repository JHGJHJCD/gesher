# Runtime contracts added by the November 14 Android/Windows audit.
# Track 50 supplies bounded RunTimed; track 94 supplies the framework boot path.
foreach ($lookupCase in @(
  @{ Exe = 'elf_load_test.exe'; Library = 'lookup_root.so'; Label = 'x86_64'; End = 'elf_load_test all passed' },
  @{ Exe = 'arm64_elf_test.exe'; Library = 'lookup_root_arm64.so'; Label = 'translated ARM64'; End = 'arm64_elf_test all passed' }
)) {
  $lookupOut = RunTimed "$Dir/$($lookupCase.Exe)" @((NoDrive "$Dir/$($lookupCase.Library)"), (NoDrive "$Dir")) 90
  $lookupCode = $LASTEXITCODE
  Check "native: $($lookupCase.Label) transitive dlsym and breadth-first precedence" {
    $global:LASTEXITCODE = $lookupCode
    $lookupOut
  } @('elf_lookup_graph all passed', $lookupCase.End)
}
