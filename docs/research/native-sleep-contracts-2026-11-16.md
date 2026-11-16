# Native sleep and clock contracts

Baseline: `74af63a1fd7cb10c508ea21ebf013ec0aff38504` (`main`).
Publication account: `palmoni5`.

## Findings and changes

The ordinary `nanosleep`, `usleep` and `sleep` exports in
`compat/bionic/shim.cc` did not use the already improved explicit-clock sleep
implementation. Signed seconds-to-milliseconds multiplication could overflow;
a long request completed after one capped Windows wait; `usleep(UINT32_MAX)`
wrapped its rounding addition; and `sleep` truncated its input and rounded its
remaining whole seconds differently from pinned Android LP64. A successful
`nanosleep` also overwrote the caller's remainder.

The new common ordinary-sleep path uses an elapsed-time deadline, wide
arithmetic and bounded Windows wait slices. It rechecks the clock after each
slice or early wake, saturates at the Linux signed-nanosecond deadline boundary,
preserves successful output/errno, snapshots aliased input, and returns a
normalized nanosecond remainder on interruption. `usleep` decomposes its full
unsigned input into seconds/nanoseconds. `sleep` returns the pinned Android
whole-second remainder without rounding up.

`signal_bionic.cc` adds an internal observation entry that records the wake
instant before running signal handlers, without holding the signal-state lock.
Already-pending deliverable signals interrupt this entry; ignored and
default-ignored instances are discarded; blocked instances remain pending.
The waking signal and its payload are claimed under the signal lock so another
thread cannot consume a process-directed signal before its handler runs.
Existing two-argument wait callers keep their previous path. Successful sleep
completion also delivers pending signals before restoring errno, including zero
durations, already-expired deadlines and a signal queued at the timeout boundary.
This is not a replacement for the runtime's complete signal implementation.

Both `nanosleep` and `clock_nanosleep` use that observation: a long handler does
not consume the remainder returned to the caller. The explicit-clock path also
saturates its deadline at signed nanosecond `KTIME_MAX`; its old extreme tests
incorrectly required `INT64_MAX` **seconds** in the remainder. These expectations
were corrected from primary kernel code and an independent bounded Linux
measurement. Its positive-error/unchanged-errno convention and the untouched
absolute-wait remainder are retained.

`clock_getres` now validates positive unsupported IDs as well as retained
pthread clock IDs, even when the output is omitted. Supported `clock_gettime`
calls with a null timestamp fail with `EFAULT` instead of dereferencing it;
invalid-clock validation still takes precedence. Clock source selection and
the existing reported resolution are unchanged.

## References

- Pinned AOSP bionic `android-16.0.0_r4`, peeled commit
  `f22516cbc67e81c13cf943ce02f01b8929a98726`: [sleep.cpp](https://android.googlesource.com/platform/bionic/+/f22516cbc67e81c13cf943ce02f01b8929a98726/libc/bionic/sleep.cpp),
  [usleep.cpp](https://android.googlesource.com/platform/bionic/+/f22516cbc67e81c13cf943ce02f01b8929a98726/libc/bionic/usleep.cpp),
  and [time_test.cpp](https://android.googlesource.com/platform/bionic/+/f22516cbc67e81c13cf943ce02f01b8929a98726/tests/time_test.cpp).
  The exact Git objects were read locally; the annotated tag resolves to this
  commit. The LP64 sleep wrapper returns the seconds member directly.
- Android common kernel [hrtimer.c](https://android.googlesource.com/kernel/common/+/e8c75a30a23c/kernel/time/hrtimer.c):
  request validation, monotonic relative waits, `ktime_add_safe` and remaining
  interval capture. This is a kernel reference, not the application's AOSP pin.
- [Linux nanosleep interface](https://man7.org/linux/man-pages/man2/nanosleep.2.html).
  A separate Linux probe with a 10 ms signal and an external three-second
  timeout confirmed signed-nanosecond saturation for extreme LP64 seconds.
  That probe measures Linux; corresponding Android behavior is inferred from
  the common-kernel implementation, not from an Android-device run.
- Microsoft's [QPC documentation](https://learn.microsoft.com/en-us/windows/win32/sysinfo/acquiring-high-resolution-time-stamps)
  confirms that QPC is monotonic and independent of wall-clock adjustments, but
  includes standby/hibernate time. The retained common QPC basis does not prove
  Linux MONOTONIC versus BOOTTIME suspend parity.

## Evidence and required runtime coverage

`tools/test_sleep_contracts.py --sanitize` compiles verbatim production
functions and their actual symbol registrations. Only Windows clocks, waiting,
and the signal-queue boundary are supplied by the host envelope. The signal
adapter itself is also compiled verbatim, independently of the clock envelope.
A combined group then joins the production ordinary wrappers to that production
adapter, with controlled queue insertion and handler execution.

| Verification | Result |
| --- | --- |
| Candidate host contracts under ASan/UBSan | 107 assertions pass across seven groups. |
| Independent Linux public calls | 30 assertions pass, including real timing, signals, aliased remainder and four zero/expired polling modes. |
| Original-main ordinary-sleep control | 26 expected failures across six applicable groups. Unsafe original signed-overflow/null-dereference cases are skipped; candidate checks them. |
| Ordinary OS / explicit-clock contracts | All groups pass: 231 successful assertions including the independent Linux reference, handler-latency assertions, corrected saturation checks and six completion-delivery checks. Original-main explicit-clock control fails 11 checks at the intended boundaries. |
| Adjacent pthread clock/ownership contracts | All 216 assertions pass. |
| Complete changed production translation units | `shim.cc`, `signal_bionic.cc` and `extra_sys_bionic.cc` pass x64 and ARM64 LLVM-MinGW syntax checks with warnings as errors. |

The shared `tests/native/sleep_contract_test.inc` is intended for the existing
NDK `libc_test.c` x86-64 and translated ARM64 Windows gates. Its real waits are
bounded to at most two seconds each; long-budget, handler-latency and overflow
cases use controlled clocks and a 20-second process timeout. The host tests
and syntax checks do not establish Windows scheduling, suspend/resume behavior,
native ARM64 Windows execution or full APK compatibility. Hosted integration
and exact-head CI results are recorded by the parent audit/PR.

LeakSanitizer alone is disabled locally because the executor is traced;
AddressSanitizer and UndefinedBehaviorSanitizer remain enabled. No assertions,
existing gates or production functionality are disabled.

## Independent QA correction

The independent reviewer found a regression that the separately mocked clock
and signal tests did not expose: a zero ordinary sleep returned before entering
any pending-signal delivery point. In this runtime, a different thread's
`pthread_kill` queues the handler until the target reaches such a point, so
repeated `usleep(0)` could strand that signal. A signal queued at a completed
wait's timeout boundary had the same problem. The explicit-clock path already
had the zero/expired omission in the original main baseline.

The correction calls `DeliverPendingSignals` on successful completion, before
restoring the saved errno. It keeps zero/expired requests successful, leaves the
remainder untouched, and does not add a host wait. Interrupted positive waits
still take their timestamp before running the handler.

The combined fixture against the pre-QA candidate (`shim.cc` Git blob
`35428fcfad8c3afad05013d088d3daaff0514bdf`) fails exactly four intended assertions:
pending delivery for zero `nanosleep`, zero `usleep`, zero `sleep`, and delivery
at the completed timeout boundary. All ten combined assertions pass after the
correction, including masked-signal retention, long-handler remainder timing
and distinct thread/process payloads for the same signal. The explicit-clock
pre-QA control fails its six new completion-delivery checks. The shared guest
fixture separately polls all four zero/expired public entry modes with a real
sender thread, capturing delivery before `pthread_join` can deliver a signal.
Each polling interval is bounded to two seconds.

The final production blobs reviewed and tested are:

| File | Git blob |
| --- | --- |
| `compat/bionic/shim.cc` | `2f285f665446b29289877512d2f2b0fb35d1822c` |
| `compat/bionic/extra_sys_bionic.cc` | `c59d15a09a6169f458ef9c8571d817ca7bae3a9a` |
| `compat/bionic/signal_bionic.cc` | `6f167ba17e5033698cd1d8a01dc1c18e562e4069` |

All three full translation units pass x64 and ARM64 LLVM-MinGW syntax checks
using the repository's CMake definitions, including `_FILE_OFFSET_BITS=64`,
with `-Wall -Wextra -Werror`. The existing Windows tracks 40 and 47 both require
the shared fixture's `sleep contracts: complete` marker. Their hosted runtime
results remain the parent audit/PR's evidence, not a local Windows claim.

Signal disposition races remain a separate pre-existing limitation: `Run`
copies the current action under a separate lock after dequeue. A concurrent
`sigaction` can therefore change the action of an already-claimed instance.
The sleep change secures pending-bit/payload ownership; it does not make that
broader signal-dispatch sequence atomic.

## Open-PR coordination

PR52 at `4928f662bac4da5deadf5caec22bcb73f1896338` changes only pthread
attribute helpers in `extra_sys_bionic.cc`; its `ClockNanosleep` function is
byte-identical to this baseline. The changes here are confined to that separate
function and its tests. No PR52 pthread-attribute, translated-stack, AAudio or
connectivity implementation is copied. A three-way merge of the shared file
against this baseline succeeds and retains both implementations. PR53 owns
automerge policy.

## Native map and remaining work

The baseline native boundary contains 44 tracked bionic paths, 160 POSIX paths,
21 ELF paths and 16 ARM64 paths. These are navigation counts, not executed API
coverage. Review followed exports into their host adapters and checked existing
regression seams.

| Boundary inspected | Confirmed remaining gap / next requirement |
| --- | --- |
| Files and descriptors: `posix/src/{path,fd,system,unistd}.cc`, bionic POSIX adapter | Generic `F_GETFL` always reports `O_RDWR`; generic `F_SETFL` succeeds without mutation. A complete correction needs shared open-file-description state, append behavior, descriptor aliases and reopen/close integration. |
| Pipes and readiness: `pipe2`, `read/write`, `poll` | `pipe2` honors only close-on-exec through `_pipe`; nonblocking/invalid flags are not implemented. Correctness needs bounded I/O, `PIPE_BUF`, shared alias state, readiness and child-stdio tests. Reporting flags alone would be misleading. |
| ELF mapping, dependency lookup, TLS and close | A library is globally registered before dependency/relocation/protection success; later failures leave it discoverable. Repair requires loading/ready/failed graph ownership, cycles, reentrancy and TLS lifetime, rather than blindly unmapping a node. Closed-handle reopening/reference counts also need an explicit policy. |
| Shared memory: `libandroid.cc`, memfd/mapping/seal layers | `ASharedMemory_setProt` unconditionally succeeds; generic memfd seals are advisory. Monotonic permissions must be enforced across aliases and later mappings before claiming Android protection semantics. |
| Signals and process control | Synchronous fault signals, alternate stacks and alarm timers remain partial as documented in `signal_bionic.cc`. Ordinary fixes do not make arbitrary Windows waits asynchronously interruptible. |
| Host clocks | QPC, Windows process/thread accounting and realtime are distinct backends. Existing clock resolutions and suspend accounting are unchanged and need separate device/OS evidence. |
| Process and filesystem features | POSIX fork/namespaces, FIFO creation, several extended attributes and blocking record-lock waits remain unavailable or partial. Each needs a real backing behavior and lifecycle/error contract, not another success-returning stub. |

No missing-symbol total or universal crash count is inferred from this review.
