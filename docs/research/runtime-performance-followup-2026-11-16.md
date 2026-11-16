# Runtime performance follow-up — 2026-11-16

## Baseline and review scope

Local `main` was fast-forwarded by nine commits to upstream `b9c26031` before
creating `codex/performance-followup-20261116`. The checkout was clean. This
continues the [earlier November 16 audit](runtime-performance-audit-2026-11-16.md)
and preserves its changes. Three implementation reviewers covered Java/services,
native runtime, and media/graphics; the coordinator covered desktop UX/build
tools. A separate reviewer then examined the combined changes and ran independent
behavioral, adverse-case and measurement controls.

The baseline has 3,202 tracked paths: 341 app, 527 compat, 1 Java support,
398 tools, 33 CMake, 75 patches, 83 probes, 1,281 tests, 362 documentation,
89 CI, 4 configuration, 1 asset and 7 root files. Mapping and focused review
are distinct from reading every line. This work does not establish that every
possible optimization has been found, complete APK compatibility, or measured
whole-application startup, RSS, frame rate or hardware latency.

| Area | Review and disposition |
|---|---|
| ART/JIT, ARM64, ELF, Bionic | Startup, resolver/JNI paths, TLS/environment parking, waits and ownership. Existing code-cache retention avoids demonstrated recompilation costs; no speculative cache policy change. |
| POSIX, Binder, networking | Paths, directory enumeration, procfs, properties/logging, socket/DNS ownership, certificate freshness and polling snapshots. Streaming path classification implemented; owning descriptor snapshots and cross-process publication retained. |
| Java/services/installation | Inventory of 305 Java sources; focused launch/logging, settings/services, metadata, native-library discovery, shell/icon, WebView and camera review. Two demonstrated repeated-work paths changed. |
| Media, camera and devices | Capture/output, PCM conversion, file decoders, stream lifecycle and sensors. Three capture-copy/selection paths changed. |
| Graphics, windows, input and WebView native | Surface queues, HWUI presentation, D3D11 video staging and WebView preview/response paths reviewed for allocation/copy candidates. Further changes need hardware/workload evidence. |
| Desktop UX, build, package and CI | Real WPF row layout, routed actions, localization, live refresh, updates, source discovery, archive publication and regression gates. Virtualized rows and indexed framework globs implemented. |

## Changes and measurements

Measurements below describe the stated function/workload boundaries. Counts of
copies or allocations are not claims of end-user speedup. Baseline controls use
the exact `b9c26031` source wherever the drivers accept `--source-ref` or
`--compare-ref`.

### Streaming path components

Virtual-file classification reads components directly from an invocation-owned
string view instead of building a vector. Ordinary paths stop after their first
component. Empty/`.` components, literal `..`, Binder existence, normalized
virtual names and unchanged output on ordinary/missing paths are preserved.
Font-suffix validation uses the same iterator while retaining its original
validation and exact suffix publication rules.

Per 100,000 classifier calls, `/data/app/…/libexample.so` falls from 400,000
allocations / 24,000,000 bytes to zero; `/dev/null` from 200,000 / 4,800,000 to
zero; missing `/sys/devices/system/cpu/unknown` from 400,000 / 24,000,000 to zero.
`/proc/self/status` still owns its result, reducing 400,000 / 16,000,000 to
100,000 / 4,800,000. There is no persistent path cache.

Independent QA added the exact production font-validation method with an adapted
final host-font publication boundary. Classifier/font upstream differential
coverage passes 301,289 checks plus 60,000 concurrent samples under ASan/UBSan.
Windows uses the real UTF-8 conversion; portable tests explicitly adapt that
conversion. The final host font directory resolution is outside this fixture;
the actual Windows POSIX probe also exercises font mapping.

### Audio capture spans and selection

`CaptureStream::Read` copies an admitted block in at most two contiguous ring
spans. A rare scalar fallback preserves the original unsigned frame-counter
wrap behavior. WAV-source `Fill` copies runs ending at the source loop boundary.
Byte counts, interleaving, restart, blocking and callback rules remain unchanged.
Engine presence checks prune expired weak references without allocating an owning
snapshot; actual dispatch still creates a strong-reference snapshot before
unlocking, preserving delivery ownership and order.

| Workload | Baseline | Updated |
|---|---:|---:|
| 100 stereo 480-frame reads | 48,000 copies / 192,000 bytes | 143 copies / 192,000 bytes |
| 100 stereo 480-frame WAV fills | 48,000 copies / 384,000 bytes | 100 copies / 384,000 bytes |
| 10,000 presence checks, 64 streams | 70,000 allocations / 20,320,000 bytes | 0 / 0 |

No additional storage is retained. ASan/UBSan capture coverage passes 5,262,425
checks and an identical 14,795,322-byte PCM/state/callback transcript against
upstream. QA's seeded unsigned-wrap controls fail 2,200 byte checks when the
fallback is removed. WAV coverage passes 159,408 checks across 280 valid files;
selection passes 2,061 ownership/order/pruning/concurrency controls. Physical
microphones, WASAPI scheduling and device latency are outside these fixtures.

### WebView boundary reflection

Each fixed-delegate AndroidX boundary wrapper retains at most 64 successfully
resolved method pairs. Warm queries avoid `getMethod` and its parameter-array
allocation. Failed lookup, feature fallback, access checking and delegate
exception identity remain unchanged. No result, failed lookup or global app
class cache is introduced. The tradeoff is a map and up to 64 method entries per
wrapper; overflow queries retain ordinary resolution.

For 100,000 warm host dispatches, actual reflection queries fall from 100,000 to
zero, and host dispatch allocation from 4,000,400 to zero bytes. The independent
32-check suite includes overloads, missing methods, nulls, exception identity,
reentry, concurrent cold queries and cap saturation. These exclude AndroidX IPC
and WebView2 costs.

### Camera2 capture work

The capture loop no longer constructs an unused target list or scans target
stream IDs under a redundant lock. Opening already selected its dimensions from
all configured streams; the unused argument is removed and its comment corrected.
Actual delivery continues to resolve the current streams. For 1,000 frames with
three stream IDs, map queries fall from 6,000 to 3,000. The 1,026-check loop fixture
covers callbacks, deleted streams, delivery errors and open/read failures at
declared framework/native seams. Both complete changed Java classes compile
against the prepared Android core/framework/app dependencies.

### Control Center row virtualization

The existing `ItemsControl` now owns its scrolling template and uses a recycling
`VirtualizingStackPanel` with pixel scrolling. The dashboard has a bounded initial
height before its existing resize handler adjusts the layout, preventing an
initial unbounded measure from constructing every row. Existing row visuals,
localized launch/uninstall buttons and routed click handling are retained.

Real WPF tests with 1,000 installed entries realize 3–4 containers, including the
startup peak, instead of 1,000 on the baseline at the tested 800×600/800×820
window sizes. This bounds row-control construction; the item data and icon cache
still cover the installed app list. The 60-check independent suite exercises
English/Hebrew, initial layout, recycling, top/end scrolling, replacement, empty
lists, resize and correct routed button package IDs. Removing the initial height
is a negative control that constructs 1,000 rows and fails the startup gate.

Existing refresh (15), icon reuse (13), update/layout (102), asynchronous action
(84), and production modal/WPF interaction (251) checks also pass locally.

### Framework source lookup

Framework globs lazily build one sorted reference list per immutable project
snapshot. Binary search narrows matching to the literal directory before the
first wildcard. Regex matching and result order stay unchanged; `files()` and
`dir_files()` retain their original Git order. The tradeoff is one retained
reference list per queried project, approximately eight bytes per entry on this
64-bit Python, excluding list bookkeeping; the strings themselves are shared.

The deterministic 60,000-file fixture preserves results while regex checks fall
from 180,000 to 3,000 for three subtree queries. A local 61,144-file AOSP
`frameworks/base` snapshot produced the same 5,360 matches for six Java subtree
globs, taking 0.1121 s full-scan versus 0.0096 s including initial sorting in one
sample. These times are illustrative, not a timing gate or full-build speedup.
Three unit tests cover randomized paths, nested projects, wildcard semantics,
unchanged file order and repeated index reuse.

## Independent QA and integration

QA reviewed every production change and the new gates independently. Its concrete
findings were the unsigned-counter wrap difference, missing Windows fixture
runtime dependencies, and missing direct font/startup controls. These were fixed
and the adverse controls added before opening the PR. The changed native/Java
fixtures and WPF controls pass; the complete tools unit suite passes 95 tests
with 10 explicitly CI-only skips. New regression drivers are wired into mandatory
host or Windows CI jobs alongside the existing audio regression gates.

The local D: development build compiled/linked `dalvikvm`, Binder server,
execution host and platform/POSIX/socket/epoll/libutils probes. The execution
host's existing post-link command requires GitHub CI provenance, so it rejects
ordinary local invocation; the same native-input verifier passes separately
with its explicit `--local` mode. No CI provenance is claimed for this build.
All listed Windows probes pass with isolated writable scratch/property data.
ART reports its version and runs `gesher.Hello` successfully with prepared boot
dependencies. Those dependencies and the local compiler are distinct from
canonical CI's pinned suppliers.

Full canonical CI, arbitrary APK workloads, physical camera/audio/GPU behavior,
ARM64 JIT retention policies, owning outer poll snapshots, full-body HTTP
backpressure and native video/WebView staging remain separate validation or
investigation work. No assertion was weakened to report a pass.
