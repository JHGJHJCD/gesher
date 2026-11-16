# Native pthread attribute audit — 2026-11-16

Scope: selected bionic pthread attribute contracts and target stack reporting,
starting from Gesher `99344ef805b2503f14ba37c2db723e8a14d700dd`.
Implementation author: `palmoni5`.

This is a bounded compatibility correction. It does not establish that every
Android API, every pthread lifecycle, or every application works on Windows.
The independent Windows and actual ELF guest fixtures are required integration
gates; the Linux source harness alone does not establish Windows behavior.

## Pinned contract sources

The Android oracle is AOSP bionic tag `android-16.0.0_r4`, peeled commit
`f22516cbc67e81c13cf943ce02f01b8929a98726` (annotated tag object
`2ae67d60dd95c0f0c8fe12c30907be36f2ac17ff`). The source was inspected from a
read-only Git checkout of the official repository.

| Source | Git blob | Contract used |
| --- | --- | --- |
| [pthread_attr.cpp](https://android.googlesource.com/platform/bionic/+/android-16.0.0_r4/libc/bionic/pthread_attr.cpp) | `f6c0401bb13090ff51d4e2e263b168271d18a40a` | Target lookup, detach/inheritance/scope validation, supplied stack alignment |
| [pthread_internal.h](https://android.googlesource.com/platform/bionic/+/android-16.0.0_r4/libc/bionic/pthread_internal.h) | `b3133a92dfa7a26f9c3f5d6ec58ec2d1ea412f58` | Attribute flags and thread identity/lifetime |
| [pthread_create.cpp](https://android.googlesource.com/platform/bionic/+/android-16.0.0_r4/libc/bionic/pthread_create.cpp) | `f6d7fa8421a6af3ccf1ddc886c08a3e409678b39` | Allocation, attributes and startup sequencing |

Microsoft documents the native observation API in
[GetCurrentThreadStackLimits](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getcurrentthreadstacklimits)
and distinguishes stack reservation, commitment and the movable guard in
[Thread Stack Size](https://learn.microsoft.com/en-us/windows/win32/procthread/thread-stack-size).
These sources support reporting the observed native interval; they do not make
the existing 4096-byte compatibility guard value an observed POSIX guard region.

## Confirmed mismatches and changes

| Surface | Previous behavior | Bounded correction |
| --- | --- | --- |
| `pthread_getattr_np(target, ...)` | Ignored `target`; returned the caller's Windows stack | Captures the target's own native interval before TLS/user callbacks; queries its retained pthread identity |
| Thread created but not started | A query still described the caller | A condition-variable wait releases the registry lock until the target publishes; no record pointer survives the unlocked wait |
| External host/ART thread | Only self could accidentally return correct bounds | Its `pthread_self()` registration captures its own interval alongside its retained Windows handle |
| Detach state in runtime query | Always initialized as joinable | Reads current registry state, including a live `pthread_detach` |
| Unknown or retired identity | Silently returned caller attributes | Returns `ESRCH` without changing output; a null output returns `EINVAL` |
| `pthread_attr_setdetachstate` | Every nonzero integer meant detached | Accepts exactly joinable 0 and detached 1; invalid input preserves attributes |
| `pthread_attr_{get,set}inheritsched` | Returned 0 and discarded assignments | Implements Android's `EXPLICIT=0`, `INHERIT=1`, flags 8/4 and historical policy-dependent default |
| `pthread_attr_setscope` | All values succeeded | SYSTEM succeeds, PROCESS returns Android `ENOTSUP=95`, other values return `EINVAL` |
| `pthread_attr_setstack` | Checked only minimum size | Also checks the guest's 4096-byte base and size alignment; `setstacksize` keeps its distinct minimum-only contract |
| ARM64 translated stack | Native callback stack could be reported for guest execution | `Env::Attach` publishes actual guest allocation values; the ARM64 public resolver selects its distinct attribute view |

The pinned Android `pthread_attr_setschedpolicy` deliberately stores arbitrary
integer policy values. This change preserves that behavior.

## Identity, startup and ARM64 ownership

Native stack values are copied into the existing guest-thread record or external
retained-handle record. No Windows TID is reopened. External records are reaped
using their retained thread object, preserving the existing name/CPU-clock
ownership model. Native snapshot publication happens before `SetupThreadTls`
and the caller's start routine. No user callback runs under the registry lock.

The translator has its own allocated stack: its native callback stack cannot be
used as an ARM64 guest stack oracle. `Env::Attach` publishes `stack_base_`,
`kStackSize` and the actual no-access `kGuardSize` through private resolved
symbols. The host stores values against the current retained pthread identity;
it never stores an `Env*` or mutable translator TLS object. Every pool attachment
publishes under its new current identity. The public ARM64 `pthread_getattr_np`
override uses the translated view; a missing publication yields `EAGAIN`, not a
native-stack substitute.

The ARM64 pthread wrapper retains its startup record with two references and
waits until the child has attached its environment and copied FPCR/FPSR. It then
releases the creator reference. The child releases its reference before invoking
guest code, which may call `pthread_exit` instead of returning. `WaitOnAddress`
is always retried with an acquire check. The creation-failure path never waits
for a child that does not exist. A nothrow startup-record allocation failure
returns Android `ENOMEM` before creating a thread.

## Verification and its limits

`tools/test_pthread_attribute_contracts.py` inserts production sections verbatim
and prints SHA-256 hashes of every source input. The boundary supplies actual
Linux pthreads, independently queried native stack intervals and owned
kernel-object-like handles. Only OS calls and translator allocation are modeled.
The ARM64 tests use the actual publication snippet and startup wrapper/refcount
code, with a synthetic separate guest allocation.

Local results before independent QA:

| Gate | Result |
| --- | --- |
| Attribute source harness with ASan/UBSan | 71 checks, 0 failures |
| Same safe old-code assertions with `--source-ref 99344ef8` | 59 checks, 35 failures; confirms regression sensitivity |
| Existing `test_bionic_key_contracts.py --sanitize` | All public, mutation, exit-order, host-exit and generation-limit groups pass |
| Existing `test_pthread_clock_contracts.py --sanitize` | 216 checks across all seven groups, 0 failures |
| Public guest fixture built/run on Linux | 24 checks, 0 failures for the shared POSIX subset |
| Real Windows native binary and x86_64/ARM64 ELF guests | Required hosted CI gates; no local Windows/NDK toolchain available |

Local invocation used `ASAN_OPTIONS=detect_leaks=0` because LeakSanitizer cannot
inspect this ptrace execution environment. This is an invocation-only local
setting. The committed runner does not disable LSan; ordinary CI retains its
default behavior. ASan and UBSan remain enabled, and repeated query/retirement
tests assert handle ownership explicitly. The host containment oracle uses the
native frame address because ASan may move ordinary automatic locals to its
separate fake stack.

Run the host contracts with:

```sh
python3 tools/test_pthread_attribute_contracts.py --sanitize
python3 tools/test_pthread_attribute_contracts.py --source-ref 99344ef8
```

The second command is expected to fail on that baseline.

`tests/native/pthread_attributes_target_test.cc` must link the real
`gesher_bionic` and `gesher_elf` libraries and run on Windows. It samples each
target's independent `GetCurrentThreadStackLimits`, checks target-local
containment/caller exclusion, immediate post-create queries, detached-at-create
and live-detach behavior, native external threads, 512 stable query snapshots,
owned completion witnesses and 32 replacement identities. Its success summary
ends in `0 failures`.

`tests/native/pthread_attributes_guest.cc` must compile with the Android NDK for
both x86_64 and ARM64 and execute through the respective Windows runtime paths.
Use C++20, `-O1 -Wall -Wextra -Werror -static-libstdc++ -pthread`. It uses public
NDK types/constants and checks actual target-local containment in both guest
architectures. The required final marker is `PASS guest pthread attributes`.
Keep external process timeouts on all fixtures. The ARM64 containment assertion
is necessary and must not be dropped to obtain a passing job.

### Independent native QA

A separate reviewer inspected the production change, the three pinned bionic
blob identities above, the Windows target fixture and the translator's actual
allocation/attachment path. No blocking defect was found within the documented
live-target and attribute-value scope. The published bounds have distinct
native/translated authorities, the registry lock is released during startup
waiting, and query code re-finds a record after each wake rather than carrying
a pointer across an unlocked wait. The ARM64 startup record is released before
guest code can exit without returning through its wrapper.

Independent tests add two boundary cases to the committed host harness:

- A query starts before the target has published, and another thread detaches
  that target. Detach completes while the query is waiting; after publication,
  the query returns the target's bounds together with its new detached state.
  Owned completion then proves retirement.
- An external thread cannot retain its Windows-handle substitute. Its own
  query still observes its stack; a peer receives `EAGAIN` with untouched output
  rather than relying on an identity with no retained lifetime witness. The
  failure path allocates no handle.

The expanded source harness passes **78 checks with ASan/UBSan**. Its safe
old-source control executes **66 checks and fails 39**, including the added
pending-detach and external-peer assertions. Independent runs also pass all
**216 clock checks**, all key-contract groups and the **24 Linux public guest
checks**. These results use the same invocation-only local LSan workaround
described above. They add no Windows or translated-execution claim; the actual
Windows/NDK CI gates remain required. No production change was needed by QA.

## Explicit remaining compatibility gaps

- Custom caller-provided stacks, guard requests and POSIX scheduling policy/
  priority are still not implemented by the Windows thread creator. Attribute
  setters now have the selected Android value semantics; successful setters do
  not establish that creation honors every requested field.
- Native guard/scheduling query fields retain existing compatibility defaults.
  Only the native stack interval and current detach state are observed here.
  The ARM64 guard is its separately allocated real no-access guard.
- The translator still uses its existing fixed stack allocation rather than
  resizing its private stack from a guest `pthread_attr_setstacksize` request.
  Returned translated bounds describe the actual allocation.
- An exited-but-unjoined thread can retain snapshot values in its joinable
  record after Windows has released the native stack or the translator has
  pooled the guest stack. Exact Android retention of that allocation until
  `pthread_join` is not established. Snapshot values must not be treated as
  proof of live memory after target exit.
- Existing `Env` allocation uses unchecked `VirtualAlloc` and ordinary `new`
  under `-fno-exceptions`. Allocation failure is not a recoverable pthread
  creation path in this change; it can terminate the process. The new startup
  record/CreateThread failure cases are handled, but this does not certify
  general translator allocation-failure behavior.
- This work does not add cancellation, join/detach race redesign, full POSIX
  scheduler mapping or native host `compat/posix` pthread parity.

## Broader native mapping and separate candidates

The initial native map covered bionic adapters, POSIX host adapters, ELF loading
and ARM64 translation. Separate candidates left unchanged include `pipe2`
ignoring `O_NONBLOCK`/unknown flags in `compat/posix/src/unistd.cc`, generic
`F_SETFL` success without a mode change in `compat/posix/src/system.cc`, pipe
poll direction/error handling in `compat/posix/src/fd.cc`, the host-side
`pthread_getattr_np` `ENOTSUP` in `compat/posix/src/pthread.cc`, and failed-load
publication ownership in the ELF loader. These require their own targeted
contract evidence and integration tests.

Open-PR overlap was checked before implementation. Socket timeout/DNS, media
receiver JNI and framework service changes already under PR #51 are excluded;
the performance and CI proposals in #44/#50 are separate. This report's bounded
fix does not duplicate those implementations.
