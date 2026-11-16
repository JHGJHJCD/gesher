# Runtime performance and responsiveness audit — 2026-11-16

## Scope and baseline

The baseline is upstream `980d56c4` (3,155 tracked files). Local `main` was
fast-forwarded by 39 commits before this work. The audit uses an isolated
`codex/performance-ux-audit-20261116` worktree; the original checkout's unrelated
uncommitted changes were preserved.

This continues the [November 14 audit](runtime-performance-audit-2026-11-14.md).
Several implementation rounds followed the first independent QA pass. Separate
reviewers investigated Java/tools/CI and native/media ownership, adverse cases
and measurements. Their additional fixtures cover UTF-8 fragment boundaries,
ZIP64, resize during presentation, extreme insets, poll reentry and TLS cleanup.

Subsystem mapping and focused source review do not establish line-by-line
verification of every file, complete Android compatibility or the absence of
further opportunities. Counts below describe specific operations at declared
boundaries. They are not application-wide RSS, startup, frame-rate or
physical-device latency claims. Local build dependencies are the prepared D:
development sources; canonical CI independently uses its pinned source/toolchain
and supplier workflows.

## Execution paths examined

| Area | Review and evidence |
|---|---|
| ART/JIT, ARM64 and ELF | Runtime startup/linking; symbol precedence and owned keys; environment lifetime/cache retention; translation smoke and thread churn; ELF hash/dependency traversal remains a follow-up |
| Bionic, POSIX and Binder | Symbol dispatch; task membership/liveness/DACLs; wildcard matching; native polling scratch/reentry/retirement; message framing and partial writes |
| Network | Real sockets, guarded UDP receives/faults, event-loop probes and native polling; TCP supplier/inline limitations are recorded below |
| Graphics, windows and input | Cropped pixel ownership and row stride; resize/padding; layered GDI lifetime; input geometry handoff and device/resize failure paths |
| Camera/media/devices | Capture publication, conversion, callback admission, restart and close ownership; mixer/video staging and device scheduling candidates |
| Java services and installation | Metadata equality; existing durable settings, reflected dispatch and native-library discovery regression suites; package/shell publication |
| Launcher and desktop UX | Reverse log scans, bounded tail/forwarding, concurrent output; panel icon reuse and live refresh; long dashboard operations |
| WebView, HTTP and shell | Body/preview ownership and whole-body buffering candidates; APK icon index publication and Explorer background-work bounds |
| Build, package and CI | Repeated source walks, sparse membership construction, deterministic source archives, standalone font proof and mandatory host/Windows gates |

## Implemented and measured changes

### Allocation-free symbol queries

The bionic registry, EGL thunk names and ARM64 guest overrides share an owned-key
table with transparent `string_view` hashing. Registration still owns its names;
lookups preserve the actual resolver's hit, miss, replacement and pointer rules.
For **each** of the three resolvers, 400,000 long-name hit/miss queries fall from
400,000 temporary allocations / 19,200,000 bytes to zero. Each resolver passes
4,106 checks. This does not eliminate registration or unrelated JNI allocations.

### Targeted task membership

Individual procfs task queries use a thread handle's process identity and
signalled state; the current thread needs no handle. A retained dead handle,
including exit code `STILL_ACTIVE`, cannot make an exited thread visible.
Access-denied custom DACLs retain the prior snapshot fallback. In the ordinary
512-query workload, machine-wide snapshots fall from 512 to zero, with 384
targeted opens and no handle growth. Real Windows coverage comprises 546 checks.
Listing the entire task directory still enumerates threads.

### Iterative wildcard matching

The matcher retries the most recent star without recursion, removing its
64-star correctness limit and exponential suffix tree. A valid 256-star path
now matches. The hostile 25-byte nonmatch falls from 3,615,561 matcher steps to
51. The fixture passes 592,031 checks against an independent state-exploration
oracle and the prior matcher. The bracket parser is unchanged and shared with
the oracle; this is not independent verification of locale/bracket semantics or
a claim that every pattern is linear time.

### Bounded native polling scratch

At most 256 socket entries use per-thread POD/bit/index scratch, roughly 11 KiB
per warmed polling thread. Larger and reentrant calls use invocation-owned
fallback storage. State is reset each call; no descriptor ownership or readiness
is cached. UDP recursive resampling releases its scratch lease first. Invalid
entry removal compacts stably in place.

Across 100 warmed samples at 1/8/64/256 sockets, 600/1,200/1,800/2,200 allocations
become zero. Seventy-two real Windows controls exercise observer reentry before
and after polling, concurrent threads, wake sockets, invalid endpoints, pending
state, delayed UDP readiness, large-call release and TLS destruction. ConnectEx
is a declared completion boundary in this fixture; existing full transport gates
remain necessary. Owning snapshots in the outer fd/epoll layers are unchanged.
Independent cold-growth-with-wake measurements retain 10,504 bytes including
the TLS object on the local compiler (allocator metadata excluded), below the
fixture's conservative 12 KiB gate. Thread exit releases all tracked storage.

### Binder framing without another payload buffer

Complete replies reserve their length prefix in the original output vector.
Nested command streams retain their original offsets and replacement behavior.
Sending fills the prefix and uses the same `WriteExact` path; it does not add a
second pipe write. The final send allocation/copy falls from payload-size-plus-4
bytes to zero for 11 payload sizes through 4 MiB. The 171 Windows checks include
real pipes, partial/failed/zero writes, resend and nested-command replacement.
Reply construction itself can still allocate.

### Camera conversion and capture buffers

NV12-to-NV21 conversion writes interleaved chroma directly and copies unchanged
luma rows directly. For 100 output frames at 1280×720, native conversion storage
falls from 300 allocations / 184,320,000 bytes to 100 / 138,240,000 bytes. Output
pixels remain equal to the scaling oracle. Dimension/array-size validation and
JNI pin failure are checked before publication. There are 2,322,078 sanitizer
checks; Java result-array allocations are outside this native count.

Each open capture also reuses one scratch frame under its read mutex. For 100
warmed reads, native scratch allocation falls from 138,240,000 bytes at 720p or
311,040,000 bytes at 1080p to zero. Sixty-three sanitizer controls include failed
partial captures, output preservation, six concurrent callers, isolation and
destruction. The tradeoff is one retained native frame per open capture.

### Camera callback admission and lifetime

Legacy preview admits a manual callback buffer before conversion. Starved
callbacks continue surface delivery while skipping callback conversion. Across
200 frames, NV21 allocation falls from about 92.63 MB to 0.46 MB and YV12 from
184.79 MB to 0.46 MB. Admitted YV12 writes directly into the caller's buffer,
reducing approximately 184.79 MB to 92.62 MB while retaining padding, suffix,
identity and FIFO rules. Admitted NV21 still requires its native conversion
array; that allocation has not been eliminated.

Capture close retires the handle and rejects new reads immediately. The last
admitted read releases native state exactly once, including exceptions. This
repairs a read/close use-after-free window without holding a Java monitor across
blocking `ReadSample`. The 1,544-check lifetime fixture rejects the unsafe
baseline before executing an actual freed-pointer access. Close does not cancel
a blocked physical read; native release is deferred until that read returns.
Restart guards prevent detached preview loops from consuming replacement-session
callback buffers or enqueuing callbacks once replacement is current. Ongoing
native surface delivery and already queued Handler messages cannot be retracted.
The final preview fixture passes 1,254 behavioral and 12 measurement controls.

### Pixel views and layered GDI resources

Inset popup presentation passes a cropped source view and row stride into the
existing synchronous window-owned copy, removing the intermediate cropped
frame. For 200 inset 1280×720 frames, 200 allocations / 737,280,000 bytes become
zero. The 38,028 sanitizer checks cover actual presentation methods with a
declared back-buffer boundary, including resize at lock, padded destinations,
source ownership and extreme insets. Existing surface geometry controls pass.

A layered window reuses one owner-thread memory DC and DIB. Replacement failure
retains the previous valid bitmap; GDI is flushed before reuse/deletion. Cache
resources are released on translucency disable, display change and owner-thread
exit. For 200 fixed-size frames, 200 DC plus 200 DIB creations become one each.
The 1,187 Windows checks use real GDI handles/pixels, failure injection and resize
cycles with no net handle growth. The desktop compositor is an adapter; physical
monitor/DPI/device-loss behavior is not established by this fixture. One DIB
remains allocated per active layered window.

### Bounded launcher diagnostics

The launcher scans backward in 8 KiB blocks and decodes only the selected suffix.
Last-40-line I/O on a 48,800,003-byte file falls from the complete file to 12,953
bytes. Tail retention is capped at 1 MiB and 4,096 lines; an explicit omission
label accompanies truncation. A 64 MiB unterminated line reads 2,097,156 bytes
(bounded scan plus decode), rather than scanning the complete file. Invalid
discarded UTF-8 no longer suppresses valid retained diagnostics; retained invalid
UTF-8 still fails. Append/truncate and CR/LF/CRLF boundaries are covered.

Forwarding limits pending source bytes to 256 KiB, emitting visibly labelled
UTF-8-safe fragments for extreme lines. Each decorated fragment is published in
one synchronous bulk write, so a PrintStream with its own internal lock cannot
interleave a parent line between the label, payload and ending. A reusable
framing transfer buffer raises the total retained byte-buffer bound to about
512 KiB plus 59 marker bytes; it adds a payload copy only on the decorated path.
Smaller completed lines retain their existing single-buffer write. A 64 MiB
Unicode stream without newlines passes
under a 24 MiB host JVM heap, preserving every source byte after removing the
explicit labels. Independent QA added every multibyte split position around the
fragment boundary and a deterministic parent writer with an independent lock.
Actual ART execution also passes Unicode/concurrent
forwarding and the Android file-channel/tail regression in Windows track 94.

### Streamed installation metadata

Metadata equality uses two buffers capped at 64 KiB and stops at the first
different block. Equal 4 MiB files allocate 132,520 host-JVM bytes instead of
8,391,616; a first-byte mismatch reads 131,072 bytes instead of 8,388,608.
Two 48 MiB files compare under a 24 MiB heap where the baseline runs out of
memory. Filename sets, exact bytes, errors and ownership remain covered by
209 behavioral, five measurement and two constrained-heap checks. The existing
installation workflow owns these files; this does not add filesystem snapshots.

### Desktop icons and build work

Panel icons are frozen and reused by path/stamp, with removed entries pruned.
Changed files bypass WPF's URI cache; default icon changes are watched too.
A 120-app label update reuses 120 existing images instead of zero, and polling
stats fall from 340 to 241. Real WPF checks verify changed pixels, file-lock
release and pruning. Invalidation retains the existing size/write-time contract.

Long dashboard mutations use one serialized background owner, while the WPF
dispatcher owns dialogs, button state and completion refresh. Device, microG,
package batch, store and uninstall actions no longer synchronously perform their
work in click handlers. Setup installation and competing mutations remain
excluded. Download staging avoids publishing a partial store APK; transport
waits are bounded. A noncancellable commit defers closing with a visible message
instead of terminating installation. One background owner waits for a hidden
PowerShell ConsoleHost using structured request/result files and only whitelisted
NUL standard handles. This preserves real native stderr/exit behavior without
reader threads, accumulated logs or output buffers. Native declarations are
emitted in memory, avoiding the runtime compiler's ANSI-dependent temporary
paths. Small managed wrappers preserve the native error immediately in a
thread-local field before PowerShell binding can overwrite it. Child helpers
reuse the selected display language without changing Android application locales.
The 84-check Windows fixture passes on x64 and x86 and uses actual WPF
dispatchers/processes, Unicode/literal arguments and temporary paths outside the
ANSI code page, failed process start, abrupt exit, 64 MiB discarded output and
exact warmed handle-count equality. A deliberately retained NUL handle proves
the census detects a leak before explicit cleanup restores its baseline.
A 450 ms helper leaves the dispatcher running (55+ observed ticks including
host startup), while action submission takes about 20 ms on the local machine.
These timings describe the fixture; they are not an installation speedup. A
permanently blocked noncancellable helper can still defer closing indefinitely.

Host source globs share one directory traversal. Actual `libstats_proto_host`
expansion preserves all 287 output paths while walks fall from six to one,
directory visits from 1,776 to 296 and file visits from 2,550 to 425. Sparse
checkout computes its existing-pattern membership set once; a 5,001-path
fixture preserves the exact Git mutations while set construction falls from
5,002 to one.

Source ZIP creation streams 64 KiB input chunks with known-size ZIP64 selection.
For a 35,651,584-byte generated source, local Python traced peak storage falls
from 36,446,730 to 745,837 bytes. Entry contents, metadata and deterministic new
output are verified. Raw compressed archive bytes can differ for large files;
global byte identity to the old compressor feed pattern is not claimed.
The formerly ignored `-write_if_changed` flag now compares the staged archive
in bounded chunks and atomically publishes only changed bytes. Unchanged output
keeps its timestamp for dependent compilation, and failed generation retains the
prior valid archive. Tests cover timestamp preservation, changed contents,
failure cleanup and the original rewrite behavior without that flag.

Native Windows standalone font verification now obtains response-file-expanded
link arguments from Ninja's actual target rule when needed. It retains strict
compiler, target, directory, archive collision, object/map owner and no-VM input
checks, as well as the original raw-command receipt hash. This repairs local
post-link verification without asserting CI provenance for a local build.

### Parked ARM64 TLS

The environment pool was already bounded (default eight). Thread-churn and
unique-code measurements show that discarding all parked JIT state trades
substantial recompilation time for memory, so this audit preserves code caches.
Detached TLS is different: its 256 KiB allocation is removed from publication
and reconstructed by the next Attach. Park now decommits those dead TLS pages;
Unpark requires both stack and TLS recommit before publication, and destroys an
environment if either allocation fails. Guest addresses and translated code
remain stable. The lifecycle fixture checks dirty TCB/module BSS, newly published
modules, other active environments, failure cleanup and actual DLL guest/nested
callbacks, including errno. This reduces parked TLS commit, independently of
the code-cache retention tradeoff. The final Windows fixture passes 15,930
checks against the actual translator DLL and extracted production lifecycle.
Eight parked environments reduce committed TLS from 2,097,152 bytes to zero;
the same eight active allocations remain committed. No total RSS reduction is
inferred from this isolated page measurement.

## Integration status and remaining investigation

Local full runtime, Binder server, execution host and standalone ARM64 DLL were
compiled/linked; ART starts, portable log regressions run on actual ART, and the
ARM64 smoke suite passes including callbacks, concurrent LL/SC and sleeping WFE.
Core platform/socket/epoll/libutils/procfs and guarded UDP/fault probes pass.
Changed Java classes compile against the prepared Android core/framework APIs.
Physical camera/GPU/audio behavior and the complete canonical CI remain distinct.

Two raw local TCP scopes remain red on both the current and exact upstream
Winsock implementation, linked against the same regular local compiler:

- Default inline-OOB fails urgent readiness. A direct native Windows control
  exposes no urgent indication after `SO_OOBINLINE` merges the byte; this requires
  broader logical-inline transport semantics or reviewed platform evidence.
- `--noninline` reaches a strict ownership census with one extra handle and an
  unchanged thread count. The local publisher compiler is distinct from CI's
  selected-supplier receiver path. Exact-source baseline controls reproduce it.

No assertion was relaxed and neither scope is reported as passing. The new
allocation fixtures complement the existing supplier/lifecycle gates.

Further work needing separate workloads or hardware evidence includes ARM64 JIT
retention/recompilation, ELF graph lookup, owning outer poll/epoll snapshots,
mapping rollback copies, video staging, full-body HTTP fallback/backpressure,
WebView preview marshaling, camera hot-plug inventory and WASAPI device-period
scheduling. These candidates do not justify speculative caching or coalescing
that could change callback order, ownership or host networking behavior.
