# Android and Windows compatibility audit — 2026-11-14

Repository baseline: `e0dbabf8932df2c101d8ed3d28a8c560df771dc0`.
Publication identity: **palmoni5**. Reference: **android-16.0.0_r4**, including
frameworks/base `45034f0663f960d9ee5fb0a101a4732b71f6e2f4`.

Three mapping investigations were followed by separate implementation and
independent QA. This round repairs concrete failures even where an API class or
symbol already existed, and preserves a specific unresolved queue. Neither this
audit nor green CI certifies every Android application or unusual call ordering.

## Complete mapping and structural evidence

- [All 2,972 baseline files](android-windows-repository-map-2026-11-14.json), with
  path, Git blob identity, mode, byte size and source family.
- [Java services/application map](android-windows-map-2026-11-14/services.md):
  310 Java/generator entries, service construction, callbacks and module scope.
- [Native/runtime/graphics/media map](android-windows-map-2026-11-14/native.md).
- [POSIX/bionic/Windows map](android-windows-map-2026-11-14/posix.md): 52 primary
  implementation files plus principal guest adapters, 30,393 selected lines.
- [Complete SDK36 gap candidates](android-windows-api-gaps-2026-11-14.json),
  including every absent public class and non-exact member comparison.

The three mapping reports retain their **pre-fix** findings; the resolution
table below supersedes their proposed handoffs. Enumeration does not mean every
source line was executed. Real AOSP classes come from the upstream build, so
absence from `app/java` alone does not prove a missing class. Conversely, a
present class can fail through an absent service or a native stand-in.

## Resolved contracts and retained limits

| Mapping item | Change | Meaningful regression / boundary |
| --- | --- | --- |
| N1: ELF lookup | Search the entire dependency graph breadth first, with root/direct precedence and cycle/diamond de-duplication. | Actual production-function checks plus a real five-library DT_NEEDED graph on Linux and x64/ARM64 guest Windows paths. Old source fails seven unchanged assertions. Does not solve namespaces, unload or failed-load publication. |
| J4: WebStorage | Validate and canonicalize HTTP(S) origins before native queueing; use Chromium's validated `Storage.clearDataForStorageKey` for first-party file systems and IndexedDB only. Preserve admitted asynchronous operation lifetime. | Actual Java forwarding, real Windows URLMON with recording COM, and a private real WebView2 two-origin profile. Malformed/opaque/file/custom origins are rejected. Other-origin data, localStorage, cookies and caches are preserved; associated third-party partitions, quotas and usage remain incomplete. |
| POSIX advice | Track logical page origin across splits/fixed replacement/copied tails; validate complete ranges before destructive advice; preserve correct anonymous zeroing. | Production seam and Linux reference, Windows host/x64/ARM64 guest in both mapping modes. File/shared DONTNEED and shared REMOVE remain explicit EOPNOTSUPP without data mutation. |
| J3: tasks | Limit new task IDs while continuing older records of selected tasks. | Interleaved tasks, maxNum=1/2, destroyed records, display filtering, base/top/count and copied intents. Old source fails the base-activity assertion. |
| J2: text services | Register the generated Binder service with pinned no-checker behavior. | Actual public manager construction and unavailable sessions; raw null checker array, no invented unknown-checker callback, ordinary user checks and remote Binder. |
| J1: media routing | Supply unavailable WifiDisplayStatus and coherent default route/session, initial callbacks, shared audio volume, registration ownership/death/unregister. | Both public media routers, actual framework value classes and separate-process Binder. Remote casting, arbitrary hardware identity and privileged manager backends are not fabricated. |
| N3: audio effects | Replace false-success setup with typed capabilities and supported unavailable errors. | Unmodified framework AudioEffect/Visualizer constructors and queries through real JNI, plus ART fixture. No equalizer/preprocessing/capture engine is claimed. |
| N4/N5: callback sources | Register Retriever/Extractor MediaDataSource overloads and share the checked adapter with Player; publish complete metadata/art snapshots with bounded arithmetic. | Real callback WAV bytes, duration/sample rate, known/unknown size, EOF, retry, replacement and owner close. Deterministic artwork tests are a declared parser seam, separate from actual containers. |
| N6: media lifetime | Retain acquired receivers before locking; release detaches ownership safely. Reject nested callback state access and publish complete snapshots before retiring old sources. | Explicit barriers and real JNI exercise concurrent/reentrant release, select/unselect/seek/setup and close-time callbacks. Recorder/SoundPool remain separate follow-up work. |
| N7: AAudio | Validate builder ranges/enums and full-width allocation arithmetic; report actual shared/non-low-latency properties, ring capacity and callback frames; preserve timestamp outputs on errors. | 103 production-body checks, a validation-disabled control with 49 failures, and standalone NDK guests through the real engine. The 64 MiB ring limit is explicit; BOOTTIME is unavailable pending qualified suspend-aware clock translation. |

The [memory policy](madvise-contracts-2026-11-14.md) explains every mapping/advice
combination, exact errors, locking, provenance overhead and remaining rollback
limits. ADR0080 already documented file zeroing; this round extends its evidence
and repairs it. Private-file discard must restore a section/COW relationship,
and REMOVE must change backing allocation. Writing zeros is not equivalent.

The [media contract report](../audits/native-media-2026-11-14.md) records pinned
sources and ownership details. Root review found two additional callback edges:
a recursive mutex allowed mutation of a reader still used by an outer call,
and old-source close could reenter a partially assigned snapshot. Both received
separate corrections and unchanged safe negative-control assertions. A guard
disabled in a disposable test fails before any freed-state access is needed.

Service tests distinguish same-process generated Proxy/Parcel coverage from
actual BinderProxy transport. The remote fixture enrolls an ordinary client's
real PID through private fixture setup, then uses production CallerIdentity,
registries, service code, callbacks and Binder death. It is not a substitute for
a complete application-launch test. Direct Windows mixer/hotplug events and
general task visibility/recents filtering remain outside these service fixes.

## Fresh SDK36 inventory

The baseline app was rebuilt with JDK21/R8 9.5.26 against actual framework
classes and the complete pinned Java sources, producing **300 compatibility
changes**. An initial partial sparse-source build was excluded from this report.

| Structural comparison | Count | Interpretation |
| --- | ---: | --- |
| SDK class definitions | 6,227 | Exact archive hash recorded. |
| SDK classes on selected boot path | 5,318 | Definition presence, not behavior. |
| SDK classes absent from boot | 909 | Module/APK/uses-library alternatives were not assessed. |
| Public/protected member declarations | 90,630 | Exact constructor/field/method descriptors. |
| Exact descriptor present | 82,264 | Does not prove values, native behavior, permissions or callbacks. |
| Absent member in present class | 548 | 2 constructors, 263 fields and 283 methods. |
| Declaring class absent | 7,316 | Overlaps the 909 classes; not independent crash observations. |
| Possible modifier mismatch | 502 | Mostly final restrictions; requires call-site analysis. |

All 8,366 non-exact member rows are preserved. The main families include
connectivity, Bluetooth, NFC, Health Connect, AdServices, SDK sandbox and other
framework modules. Most contracts repaired above were already structurally
present, illustrating why counts alone cannot measure compatibility.

A fresh integrated app build and candidate comparison left all SDK class and
member statuses unchanged, with no removed boot classes. Fifteen implementation
and generated service classes were added. This is expected: these repairs supply
behavior behind public framework declarations that were already present.
Candidate archive identity and exact deltas are preserved in the JSON report.

The baseline service/JNI scan found 70 service classes (67 resolved interfaces),
1,059 explicit methods and 2,767 inherited name/arity candidates. Of 174 upstream
JNI registration entries, 50 had direct references, four had reviewed alternate
registrations and 120 had no reference in the limited scan. It enumerated 523
stand-in methods. These are source classifications, not proven missing APIs.
Aliases, overloads and valid unavailable answers require individual review.

Two false leads were rejected: `app/java/com/google/**` holds client SDK fixtures
excluded from the boot JAR, and NativeBN is built by the current graph despite
an older conditional fallback file. Neither establishes a shipped-runtime gap.

### Exact local input provenance

Core/framework artifacts came from successful [run 43616267018](https://github.com/gesher-runtime/gesher/actions/runs/43616267018).
Its source-identity job records checkout
`90032eae6116b1e41e9014ca07a8d0f5886d0c51`. That checkout and this baseline have
identical tree **488acd844b6e24dfba9dc4ba20fa4ef5d22059a2**, verified with Git.
The run's head SHA was not mistaken for its actual PR merge checkout.

| Artifact | ID | Verified ZIP SHA-256 |
| --- | --- | --- |
| Framework compile classes | 14121899531 | `43daf8411931c1d427ff89147eea27f36de24ca0e94f2bd2e9cd2aea7f0b9362` |
| Framework runtime | 14121784804 | `f6de2d7f3c8fa9634d2f9b557ddc666f2646cc32df9a8346bded22d7641c3728` |
| Framework assets | 14122144200 | `ee041a8e11bd5c44a25471ba44e38e418b63607a53947835bfdb9998e065c7e5` |

SDK36 android.jar SHA-256:
`d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a`.
Fresh complete baseline gesher-app.jar SHA-256:
`3c2f88a7e09215d5daa10d33c08f41f690ad70093e49862cc2ece87b8a7fdd01`.
All boot JAR and parser hashes appear in the API report. The older green run
establishes input provenance, **not validation of these new changes**.

## Independent review and runtime gates

Independent POSIX QA reran the exact-source seam under ASan/UBSan: 30 checks
passed; the old source failed 15 intended assertions. The actual Linux oracle
passed 147 checks. Full production mman and the C fixture cross-compiled with
strict warnings for x64 and ARM64 Windows. These are not Windows execution.

Independent service QA reproduced task25 and media-routing21 host passes and
the old task-source failure, inspected pinned framework semantics, reviewed
identity/death/callback locking, and verified public and remote test wiring.
Independent media QA reproduced 24 effect, 57 callback, 103 AAudio and all 266
existing media contract checks. Local LeakSanitizer detection alone is disabled
where ptrace prevents thread inspection; ASan/UBSan remain enabled.

ELF/WebView QA independently checked source controls and strict Windows bridge
compilation. It found a fixture-origin navigation error, corrected separately,
and identified the opaque-origin deletion risk described in the final storage
correction. No invalid origin is allowed to become a global clearing request.

The final storage method uses two independent parsing boundaries: native URLMON
admission and Chromium storage-key deserialization. The recording fixture cannot
create a browser, so disabling admission remains a safe negative control. The
real profile fixture checks successful first-party deletion and preservation.
Because the first reviewer authored this correction, a separate final reviewer
re-fetched the pinned Chromium sources, inspected the complete correction and
CI wiring, reproduced Java16, and cross-linked both native variants. That review
found no blocking source defect; it did not count cross-compilation as execution.
Immediate public-view destruction during a pending CDP operation remains an
unconfirmed cancellation/coverage question; this audit does not claim to prove
that timing case or associated third-party partition deletion.

The mandatory Windows track runs both guest ABIs and host memory advice with
placeholder mappings enabled/disabled, public framework media/task/text APIs,
separate-process Binder, silent paced AAudio callbacks, and private-profile
WebView storage. Existing full/frame/E2E gates remain required on Windows 2022
and 2025. Standalone ARM64 Windows CI checks builds/PE architecture, not native
ARM64 Windows execution. All new processes have finite deadlines and owned
temporary resources.

Final PR status records its exact published head, fresh complete CI, and the
actual merge-checkout tree. A green older revision, single component, or
Linux-only fixture does not satisfy this gate.

## Unresolved work, ordered by concrete risk

The mapping appendices preserve source locations and evidence. These items are
not represented as fixed by this PR. A source-level candidate still needs an
unchanged failing contract and a defined Windows behavior before implementation.

| Priority | Area | Remaining question or failure mode | Required next evidence |
| --- | --- | --- | --- |
| High | ELF load publication | Failed loads may leave partially registered images and dependencies. | Failure at each relocation/dependency stage, registry rollback and retry without breaking valid cycles. |
| High | Pipe readiness | NONBLOCK/F_SETFL and readiness can diverge from Android pipe semantics. | Full/empty pipe, partial writes, duplicated descriptors, poll/epoll and concurrent close against Linux. |
| High | File/shared memory advice | Explicit unsupported responses preserve data but do not supply Android discard/deallocation behavior. Shared anonymous mappings also retain an existing private backend limitation. | Section/COW restoration, backing-file visibility, alias consistency, genuine shared backing and failure rollback on Windows. |
| High | SurfaceTexture/HardwareBuffer | External texture, cross-process buffer and fence behavior are broader than current software-backed paths. | Producer/consumer ownership, acquire/release synchronization and real EGL/Vulkan consumers. |
| High | Remaining media receiver lifetimes | Recorder/SoundPool and other paths do not inherit the Retriever/Extractor proof. | Callback reentry and concurrent close with production parser/engine ownership. |
| Medium | Guest filesystem calls | Some *at flags, empty paths, dirfd and symlink combinations require exact review. | Per-call Linux matrix, identical errno and no fallback to the wrong path. |
| Medium | Clocks and sleep | Host/guest CPU-clock IDs and suspend-aware sleep/timestamp domains require separate translations. | Cross-thread/process clocks, suspend/resume and output preservation for unsupported clocks. |
| Medium | Socket option ABI | timeval narrowing and short getsockopt buffers may not match guest ABI expectations. | Both guest layouts, boundary values, returned length and unchanged bytes outside the allowed buffer. |
| Medium | eventfd/inotify | Some state transitions precede successful copy-out; rename/watch retirement needs fuller ordering tests. | Fault/error return without unintended consumption, rename pairs and watch deletion under load. |
| Medium | Service payloads | AppTask thumbnails, ActivityManager memory payloads, DNS/network parsing and broader visibility rules need case-by-case work. | Public framework consumer plus real remote Binder, stable empty/unavailable values and ordinary identity. |
| Broad follow-up | API/provider families | NDK Binder, Camera2, ICU/Vulkan coverage, DRM/MediaSync and modular Java families remain incomplete. | Resolve each inventory candidate against actual provider, loader registration, feature declaration and public caller. |
| Broad follow-up | Process model | Shared synchronization, signals/fork, seals, rlimits and procfs cannot be inferred from symbol presence. | Document supported process model and execute interprocess contracts on Windows. |

No single Windows function should be substituted merely because its name is
similar. The repaired memory and audio contracts demonstrate the distinction:
unsupported behavior must retain state and return a meaningful error, while a
supported operation must preserve Android ownership, ordering and observable
values. This still leaves documented unsupported features.

## Primary references and reproducibility

The mapping reports and focused [memory](madvise-contracts-2026-11-14.md) and
[media](../audits/native-media-2026-11-14.md) reports link the exact Android and
Windows source contracts used. Key public service references are pinned to
the same framework revision:

- [ActivityManager task metadata](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/core/java/android/app/ActivityManager.java).
- [TextServicesManagerService](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/services/core/java/com/android/server/textservices/TextServicesManagerService.java).
- [SystemMediaRoute2Provider](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/services/core/java/com/android/server/media/SystemMediaRoute2Provider.java).
- [WebStorage public contract](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/core/java/android/webkit/WebStorage.java).
- [Chromium 143 Android quota removal mask](https://chromium.googlesource.com/chromium/src/+/143.0.7485.2/android_webview/browser/aw_quota_manager_bridge.cc).
- [Chromium 143 CDP storage endpoints](https://chromium.googlesource.com/chromium/src/+/143.0.7485.2/content/browser/devtools/protocol/storage_handler.cc) and [storage-key deserialization](https://chromium.googlesource.com/chromium/src/+/143.0.7485.2/third_party/blink/common/storage_key/storage_key.cc).
- [Windows CreateUri](https://learn.microsoft.com/en-us/previous-versions/windows/internet-explorer/ie-developer/platform-apis/ms775098(v=vs.85)).

The repository's inventory tools accept the exact SDK/boot archives and optional
candidate replacement described in their command-line help. Regenerate class
and member reports with `tools/java_api_inventory.py` and
`tools/java_member_inventory.py`; use `tools/audit_services_jni.py` for source
classification. The JSON inputs record hashes so a different SDK or generated
app JAR cannot silently be treated as the same measurement. Runtime assertions
are committed with their production changes and invoked by mandatory CI gates.
