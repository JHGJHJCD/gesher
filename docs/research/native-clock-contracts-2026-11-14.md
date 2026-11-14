# Guest pthread clock and ownership contracts — 2026-11-14

This repair compares Gesher baseline
`e0dbabf8932df2c101d8ed3d28a8c560df771dc0` with the pinned Android
`android-16.0.0_r4` bionic implementation. It covers guest pthread deadlines,
explicit clock selection, and rwlock writer ownership. It does not establish
complete pthread, Android, or Windows scheduling compatibility.

## Findings and repairs

| Contract | Baseline behavior | Repaired behavior |
| --- | --- | --- |
| `pthread_cond_clockwait` with a monotonic condvar and explicit realtime clock | Forwarded to ordinary timedwait, which selected the condvar attribute and compared epoch seconds with uptime | Uses the explicit clock for this call, without changing the condvar attribute; ordinary timedwait still uses the attribute |
| Explicit mutex, condvar, rwlock, and semaphore clocks | Treated every nonzero clock ID as monotonic, sometimes acquiring or consuming a resource | Accepts only `CLOCK_REALTIME` and `CLOCK_MONOTONIC`; rejects other IDs before touching the resource |
| `sem_clockwait` invalid clock | Used the ordinary semaphore wait path | Matches Android's particular positive `EINVAL` return with unchanged `errno`; valid-clock semaphore errors retain their existing `-1`/`errno` convention |
| Nullable contended mutex timeout | The standard timed mutex route dereferenced the null deadline | All timed/clock mutex routes share a helper that treats null as an unbounded wait, as Android permits |
| Invalid pthread deadline | Missing validation in the core paths and validation before immediate acquisition in the extra wrappers | Mutex/rwlock immediate acquisition and recursive/errorcheck ownership take precedence; blocking waits then check nanoseconds before negative seconds; condvars validate before releasing their mutex |
| Finite Windows wait interval | A condition wait could report timeout when its capped Windows slice ended before the absolute deadline | Rechecks the selected clock after a finite slice; realtime slices are at most one second to observe wall-clock adjustments |
| Owning writer reacquires a rwlock | Blocking read/write waited for the caller itself; timed operations returned timeout | Blocking and timed read/write return `EDEADLK`; try-read and try-write retain `EBUSY` |
| Another thread unlocks a writer | Released the write lock | Returns `EPERM` and retains ownership |
| Reader count exhaustion / held rwlock destruction | Reader count addition had no Android capacity check; destroy always succeeded | Returns `EAGAIN` at Android's reader limit without changing the count; destroy returns `EBUSY` while held |

The clock wrappers now live beside the mutex, condition, and rwlock
implementations in `compat/bionic/pthread_bionic.cc`. The old generic
trylock/polling wrappers in `extra_sys_bionic.cc` are removed. The public
symbol names already existed; this changes their semantics and their
authoritative registration, without adding a new ABI layout or requiring a
new ARM64 dispatch signature.

The mutex, condition, and rwlock guest object sizes remain unchanged.
Rwlock word 0 continues to contain the reader count or writer flag; a
reserved word records the writer's Windows thread ID. The writer publishes
that ID after acquisition, clears it before releasing the state, and checks
it on unlock. Address waits remain process-local Win32 `WaitOnAddress`
operations.

## Android source authority

These contracts were read from the actual release sources, including the
validation and dispatch code, rather than inferred from symbol existence or
from a Linux host libc:

- [pthread condition implementation](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r4/libc/bionic/pthread_cond.cpp): explicit clocks, attribute clocks, and validation before mutex release.
- [pthread mutex implementation](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r4/libc/bionic/pthread_mutex.cpp): immediate acquisition, nullable deadlines, and recursive/errorcheck owner precedence.
- [pthread rwlock implementation](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r4/libc/bionic/pthread_rwlock.cpp): writer ownership, timed/try distinctions, acquisition-before-validation, reader limit, and destruction.
- [semaphore implementation](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r4/libc/bionic/semaphore.cpp): positive `EINVAL` for an unsupported explicit clock.
- [time conversion helpers](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r4/libc/private/bionic_time_conversions.h): null allowance, nanosecond validation, and negative-second error order.

SHA-256 of the downloaded source files:

| File | SHA-256 |
| --- | --- |
| `pthread_cond.cpp` | `300329f53d09cd57d2dbd2197b93659cce39eb8ea61b97b429e9e911c724a30d` |
| `pthread_mutex.cpp` | `606975c58c720f80e2615c00eec2508aab43ba3f1413b4f64e62abf41f5a7a59` |
| `pthread_rwlock.cpp` | `1335c2868720a24b376ff5f559161af45f0a8b45cbd1e89bb6e5d03031e7f2c0` |
| `semaphore.cpp` | `1be11a02351be9cd9b3fdced5e010b05610bb707502f4d3bc6df677d32962b44` |
| `bionic_time_conversions.h` | `b0f68d6cff0f11de97b41c67214d0ea00ec7364beb46fa8a1c0b6b729315ad04` |

## Test design

`tools/test_pthread_clock_contracts.py` extracts the actual production
helpers, API implementations, and symbol registrations verbatim. It prints
their source hashes and compiles them with strict warnings. The Linux host
envelope substitutes only the Win32 wait/wake/thread-ID and clock boundaries.
Real host threads and Linux futexes exercise contention; deterministic clock
and wait hooks cover the attribute matrix and long Windows wait slices.

The shared `tests/native/pthread_clock_contract_test.inc` resolves public
exports using `dlsym`. `libc_test.c` includes it for the existing x86-64 and
translated ARM64 Windows guest suites. Its actual-clock matrix uses an
expired valid deadline plus a delayed signalling thread that holds the same
mutex. That control bounds the old incorrect clock selection without a
long sleep or an unbounded wait. Null-timeout checks release a held mutex
from the owner and require the waiting thread to acquire it successfully.
The host-specific null test additionally observes the actual production
contended state before releasing the owner.

Private-state tests are declared as such: one sets the mutex's existing
waiter state to prove that invalid condition deadlines never unlock it;
another sets the rwlock reader count at its limit. Neither substitutes the
production operation under test. The ownership test uses a real second
thread. A concurrent reader/writer test transfers ownership through 4,000
protected writes.

`tests/native/pthread_wait_clock_linux_reference.c` is a separate native
Linux control for the common POSIX/glibc behavior. It deliberately does not
use glibc as the authority for Android null deadlines, positive semaphore
clock errors, nonowner writer unlock, or free-rwlock invalid-deadline
precedence. Those differ or need Android-specific source authority. For
example, its free-rwlock acquisition check uses a valid expired deadline;
the Android test separately checks invalid nanoseconds.

## Local validation

The candidate source-host suite passed **216 checks** with AddressSanitizer
and UndefinedBehaviorSanitizer enabled:

| Group | Checks | Failures |
| --- | ---: | ---: |
| Public exports and invalid clocks | 66 | 0 |
| Clock selection | 36 | 0 |
| Deadline/owner validation precedence | 62 | 0 |
| Writer ownership | 17 | 0 |
| Finite Windows slices | 2 | 0 |
| Nullable mutex deadlines | 28 | 0 |
| Reader bounds and contention | 5 | 0 |

The same public, clock, validation, ownership, and slice groups against the
original baseline completed safely with **104 failing assertions across
183 checks**. Every selected group failed. The original-source control does
not execute the known null dereference or untimed self-owner deadlock;
those candidate paths are covered without executing the unsafe original
behavior. Each source-host group has a 30-second external timeout.

The independent Linux reference passed **20 checks**. Existing
`test_bionic_key_contracts.py`, `test_bionic_semaphore_contracts.py`,
`test_bionic_wait_contracts.py`, `test_bionic_system_contracts.py`, and
`test_futex_contracts.py` also passed with ASan/UBSan. The futex suite reports
80 public, 164 guest, and 152 concurrency checks.

Reproduction commands, from the repository root:

```sh
python3 tools/test_pthread_clock_contracts.py --sanitize
python3 tools/test_pthread_clock_contracts.py --linux-reference
python3 tools/test_pthread_clock_contracts.py --sanitize \
  --source-ref e0dbabf8932df2c101d8ed3d28a8c560df771dc0 \
  --group public --group clocks --group validation --group ownership --group slices
```

The final command is an expected failing negative control. In the local
managed environment only, `ASAN_OPTIONS=detect_leaks=0` was used because
LeakSanitizer cannot run under ptrace. Address and undefined-behavior
sanitizers remained enabled; CI leak settings were not changed.

Supplemental compiler checks used LLVM-MinGW `20261006` and Android NDK
`27c`:

- Both complete changed production translation units compiled for x86-64
  and ARM64 Windows with the repository compile definitions and
  `-std=gnu++20 -Wall -Wextra -Werror`.
- The new shared C fixture compiled for Android API 26 x86-64 and ARM64 with
  `-std=c17 -Wall -Wextra -Werror`.
- The complete `libc_test.c`, including the new fixture, compiled for both
  Android ABIs using its existing CI warning policy. Adding `-Werror` to
  that whole pre-existing suite exposes unrelated null-argument,
  intentional truncation, and string-escape warnings. The new fixture was
  also compiled separately under strict warnings to keep that distinction
  visible.

These object-compilation checks are supplemental local evidence. They do
not prove Win32 execution, guest translation, full linking, or the selected
CI artifact supplier. The existing native-libraries and ARM64 Windows
guest gates execute the shared fixture; their run results belong to the PR
CI record.

## Boundaries and remaining native gaps

Windows relative waits cannot exactly reproduce every Linux absolute-clock
and suspend/resume behavior. Realtime deadline changes are rechecked at
most one second after the currently scheduled slice; no suspend/resume or
Windows scheduler guarantee is claimed by the host tests. The existing
millisecond wait granularity remains.

This repair does not implement process-shared synchronization or rwlock
writer-preference attributes. It does not change semaphore null-timeout
semantics. The wider audit also identified work requiring separate designs:
target-thread stack metadata in `pthread_getattr_np`, irreversible
protection reduction in `ASharedMemory_setProt`, broader local-time ranges,
and atomic signal-mask behavior in polling APIs. Those remain outside this
patch. Open PRs #24–26 owned unrelated path/exit logging, Binder lifecycle,
and `android.os.Debug` work and were excluded from this native repair.
