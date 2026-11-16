# N53: Android uptime and elapsed-realtime domains

This change addresses the clock-domain part of issue #56. It does not claim
that the whole issue, wakeup alarms, or physical suspend qualification is complete.

## Contract and implementation

Android distinguishes [uptime and elapsed realtime](https://developer.android.com/reference/android/os/SystemClock):
uptime excludes deep sleep, whereas elapsed realtime includes it. A common
QPC clock for both cannot represent those contracts across Windows suspend.

The shared implementation in `compat/posix/include/gesher/clocks.h` uses
[QueryUnbiasedInterruptTimePrecise](https://learn.microsoft.com/en-us/windows/win32/api/realtimeapiset/nf-realtimeapiset-queryunbiasedinterrupttimeprecise)
for awake time and
[QueryInterruptTimePrecise](https://learn.microsoft.com/en-us/windows/win32/api/realtimeapiset/nf-realtimeapiset-queryinterrupttimeprecise)
for elapsed time including sleep/hibernate. Both report 100 ns units; conversion
to signed nanoseconds saturates instead of overflowing. Windows 10 is already
the repository's minimum target. Their imports require `mincore`, now a public
dependency of `gesher_posix`.

| Consumer | Selected domain |
| --- | --- |
| Guest MONOTONIC, MONOTONIC_RAW and MONOTONIC_COARSE | Unbiased interrupt time; RAW/COARSE retain their existing alias behavior |
| Guest BOOTTIME | Biased interrupt time |
| ART MilliTime, MicroTime, NanoTime; libutils uptime; System.nanoTime | The same awake domain |
| libutils SystemClock.elapsedRealtime[Nano] | The same BOOTTIME domain, including its Windows-specific dispatch |
| Guest pthread/semaphore/futex monotonic deadlines | The same awake epoch as their Android clock reader |
| Relative REALTIME sleeps and timerfds | Awake duration, unaffected by wall-clock changes |
| BOOTTIME sleeps and timerfds | Elapsed duration including sleep |
| Absolute REALTIME sleeps and timerfds | Wall time |
| Android audio timestamps, synthetic/file capture pacing, file/null sink pacing | Awake domain |
| MediaCodec rendered-frame system-nano and missing input timestamps; video-only MediaPlayer pacing | The same awake domain as Java System.nanoTime and audio pacing |
| Host winpthreads clock_gettime and host pthread condition variables | Unchanged host QPC epoch |

`AndroidClockGettime` is an explicit adapter, not a global override of the host
clock. ART's existing GESHER patch enables `ART_USE_FUTEXES`; ART absolute
monotonic dates therefore use the same adapter as Gesher's futex backend.
Replacing the host pthread clock globally would instead break its own absolute
wait implementation. Wall/CPU clocks continue to use their existing backend.
Architectural counters in ARM translation and internal protocol timestamps are
not assigned Android uptime semantics by this change.

### Remaining relative timeout work

This change does not convert every use of `steady_clock` in the runtime.
`signal_bionic.cc::Sigtimedwait`, the native `sigtimedwait`, and socket
`Recvmmsg`/`PollTimeout` still use host steady-clock relative accounting. Their
existing suspend behavior needs a separate timeout-transport change and tests;
they do not receive the newly changed Android absolute clock values. The
signal sleep transport also retains host condition-variable deadlines. The
outer Bionic nanosleep/clock_nanosleep loop now checks its remaining Android
duration after every transport return, including a premature host timeout on
resume. This avoids mixing an Android absolute date into a host condition
variable while retaining the signal transport's established wakeup protocol.

Internal prepare/performance tracing, probe deadlines and ARM's emulated
architectural counter are intentionally left on their host clocks. Device and
guest architectural-counter qualification is outside this Android API clock
patch.

`ClockNanosleep` observes interruption time in the selected domain before
running a signal handler, so an interrupted relative BOOTTIME sleep reports
the remaining BOOTTIME duration, not uptime duration.

## Resume delivery limit

Windows [relative threadpool timers exclude time spent asleep](https://learn.microsoft.com/en-us/windows/win32/api/threadpoolapiset/nf-threadpoolapiset-setthreadpooltimer).
BOOTTIME timerfds therefore arm at most one second of relative time before
checking their deadline again. After resume, an expired BOOTTIME timer becomes
ready on that recheck, at most one second of additional awake time plus
callback scheduling delay. The callback accounts for all elapsed periods and
one-shot disarm remains unchanged. BOOTTIME sleeps use the existing one-second
recheck limit with the corrected clock.

This does not wake a sleeping machine or promise instantaneous delivery on
resume. It trades a periodic callback for correctness without coupling a
BOOTTIME deadline to wall-clock adjustments. A future power-resume notification
path could remove that delivery delay. AAudio BOOTTIME timestamps remain
unsupported by the existing API adapter.

## Evidence recorded on Linux

The new runner compiles exact production bodies and the actual patches applied
to pinned AOSP source; see [fixture provenance](../../tests/clock-domains/README.md).
It drives separate awake, suspend, wall and QPC epochs and checks domain
agreement, wall jumps, sleep interruption/remainders, absolute deadlines,
conversion overflow, and audio sample production through suspend/resume.

| Command (`python3 tools/…`) | Result |
| --- | --- |
| `test_clock_domains.py --sanitize` | 71 assertions pass |
| Same command with `--source-ref 6f5e23a598c10906e4518045bc8d73f5c0cf3a9f` | Compiles; 30 semantic assertion failures, expected negative control |
| `test_timerfd_contracts.py --sanitize` | 86 host assertions and 68 Linux reference assertions pass |
| Timerfd `--case suspend --skip-linux-reference --sanitize --source-ref 6f5e23a598c10906e4518045bc8d73f5c0cf3a9f` | Compiles; six of nine assertions fail, expected negative control |
| `test_sleep_contracts.py --sanitize` | All seven host groups and Linux reference pass |
| `test_futex_contracts.py --sanitize` | 80 public, 164 guest and 152 concurrency assertions pass |
| `test_ordinary_os_contracts.py --sanitize` | All host groups and Linux reference pass |
| `test_pthread_clock_contracts.py --sanitize` | All seven groups pass |
| `test_bionic_semaphore_contracts.py --sanitize` | All groups pass |
| `test_aaudio_lifecycle_contracts.py --sanitize` | 221 assertions pass |
| `test_audio_file_source.py --sanitize` | 280 valid WAV inputs and 159408 assertions pass |
| `test_audio_state_contracts.py --sanitize` | 162 audio and 10 SoundPool checks pass |
| `test_media_player_priming.py --sanitize` | 158 checks and unchanged captured-tone tolerances pass |
| `test_codec_lifecycle_contracts.py --sanitize` | 16 receiver and 36 wait checks pass |

The development sandbox is traced, which prevents LeakSanitizer from running.
These local executions used `ASAN_OPTIONS=detect_leaks=0`; AddressSanitizer and
UndefinedBehaviorSanitizer remained enabled. CI does not disable leak checks.

The repository-pinned LLVM-MinGW compiler also compiled and linked the real
Windows clock probe against Windows headers and import libraries:

```sh
x86_64-w64-mingw32-clang++ -std=c++20 -Wall -Wextra -Werror \
  -D_WIN32_WINNT=0x0A00 -I compat/posix/include \
  tests/native/clock_domains_windows_test.cc -pthread -lmincore \
  -o clock_domains_windows_test.exe
```

This is compile/link evidence, not an execution result.

## Windows and Android acceptance gates

The Windows time track now runs `clock_domains_windows_test.exe`, which
brackets the actual readers independently and verifies that the host pthread
clock still uses QPC. The existing `native3_cpu_clock_test.exe` brackets actual
patched ART clocks using unbiased interrupt time. The dex-native fixture also
brackets `System.nanoTime` with `SystemClock.uptimeNanos`. These Windows/ART/JVM
executions require CI or a configured Windows build; they were not run in the
Linux development environment.

For physical evidence, run:

```powershell
.\out\clock_domains_windows_test.exe --after-suspend
```

Suspend or hibernate the machine for at least five seconds, resume, then press
Enter. Record the Windows build, machine/power mode, suspend type and printed
excluded nanoseconds. Repeat separately for suspend and hibernate where the
machine supports them. The probe requires BOOTTIME to advance at least one
second more than uptime, without assuming a zero clock origin (checked Windows
builds can deliberately bias interrupt clocks).

A full N53 acceptance run still needs real Windows suspend/resume while guest
MONOTONIC and BOOTTIME sleeps/timerfds are outstanding, verifying the remaining
awake timeout versus expired BOOTTIME and periodic expiration counts, together
with a pinned Android oracle. Device audio timestamps and media behavior across
physical resume also remain runtime qualification gates. Simulated suspension
proves routing/arithmetic and regression sensitivity; it cannot establish
Windows power-management or device behavior.
