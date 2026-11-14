# Android contract audit: clocks, callbacks, values and Windows input

Baseline: `e0dbabf8932df2c101d8ed3d28a8c560df771dc0` (`main`).
Reference: Android 16 release 4 (`android-16.0.0_r4`) and official SDK36.
GitHub publication account: `palmoni5`.

This pass maps the repository, refreshes actual boot-class/member and source
service/JNI inventories, and repairs five contract families with separate
implementation and QA reviews. Every baseline tracked path is mapped. The
behavioral investigation is risk-directed: neither this audit nor green CI
establishes that every possible APK or every Android API works.

The [machine-readable inventory](android-contract-audit-2026-11-14.json) contains
the complete 2,972-path map, input/source hashes, all 909 absent SDK boot classes,
all 548 absent declarations on present classes, 502 possible modifier mismatches,
nine descriptor-dependency rows and the open-PR comparison. The
[service/JNI queue](android-service-review-2026-11-14.json) preserves current
source-scanner rows, signatures and locations. Historical manually maintained
gap labels were removed from that snapshot to avoid presenting already-repaired
features as new findings.

## 1. Open PR coordination

| PR | Reviewed head | Work left to that PR |
| --- | --- | --- |
| [24](https://github.com/gesher-runtime/gesher/pull/24) | `4eb1817bd8ad529b23f2b1268fc70d903fb21a74` | Reserved filenames/colon and UNC mapping, Java paths, child-exit diagnostic integrity. |
| [25](https://github.com/gesher-runtime/gesher/pull/25) | `df27469d3b7610a67835c6e3257a3f14b1d5459c` | Process/Binder identity through exit and PID supersession; service/activity recovery. |
| [26](https://github.com/gesher-runtime/gesher/pull/26) | `eff0b7b1047fd706961645a9b58ebc884dc5631c` | `android.os.Debug` memory JNI/accounting contracts. |

PR25 includes an earlier PR24 revision, and PR26 includes PR25. Current PR24 has
an additional Java-path follow-up, so these heads are no longer one unchanged
linear stack. This pass changes none of their production files. Shared
`ci.yml` and track94 additions are independent gates. Integration QA read the
exact remote files and verified their blob hashes: all four shared-file
three-way comparisons against the baseline merge cleanly, preserving both sets
of assertions. The JSON records the reviewed changed-path lists.

## 2. Repository and Windows translation map

The baseline has 2,972 tracked files: tests 1,173; compat 513; tools 340; docs
339; app 320; probes 83; GitHub configuration 83; patches 75; cmake 33; config 4;
assets 1; java 1; root files 7. These are navigation counts, not dynamic coverage.
`app/java` has 281 Java sources, of which 235 enter the production driver build.
The 46 `com/google` sources are excluded test-client stubs, not boot classes
replacing an ordinary APK's GMS/Firebase client SDK.

| Boundary | Main paths | Existing bridge and review implication |
| --- | --- | --- |
| Platform/build | `aosp.lock`, `patches`, `cmake/aosp`, `tools/framework`, `app/build.sh` | Pinned AOSP and generated Binder defaults become boot JARs/native libraries. Source presence does not prove a runtime contract. |
| APK/package/process entry | `app/java/android/app`, `com/gesher/app`, package/installer/shared-library services | APK resources, class loading, manifests, identity, Windows launcher/process startup. Missing-module assessment must include shared-library and APK providers. |
| Binder/lifecycle | `ActivityTaskManagerService`, `ActivityManagerService`, `ActiveServices`, `BroadcastController`, `compat/binder` | Android lifetimes over Windows processes/IPC. Current exit/identity work remains owned by PR25. |
| Providers/settings/sync | `ContentProviders`, `ContentService`, `SettingsProvider`, `SettingsStore`, `SyncManager`, `UriGrants` | References, durable values, observer delivery and sync admission/retry. Main already has substantial repairs; historical TODOs are not current evidence. |
| Jobs/power/location | scheduler/alarm/power services, `LocationManagerService`, `WindowsLocation` | Windows timers/power requests and WinRT position feed Android callbacks. This pass repairs terminal ownership and expiry; hardware/GNSS/batching remain separate. |
| Network/module values | `app/java/android/net`, `com/gesher/net`, POSIX socket/Winsock files | Windows interfaces/DNS/sockets plus replacement modular classes. NSD value repair does not implement mDNS discovery. |
| Guest libc/POSIX | `compat/bionic` (41 baseline files), `compat/posix` (158) | LP64 guest values and Linux semantics map to LLP64 UCRT/Win32 descriptors and handles. Fork/namespaces/signals and protection/accounting remain partial. |
| Synchronization | `pthread_bionic.cc`, `extra_sys_bionic.cc` | Guest storage/identity over Windows address waits. Explicit clocks, deadline precedence and writer ownership are repaired without changing object ABI. |
| ELF/TLS/ARM64 | `compat/elf` (21), `compat/arm64` (15) | Relocation, TLS and guest/host dispatch. ARM64-to-x64 execution differs from a native ARM64 Windows host; symbols do not prove linker namespace/unload parity. |
| Framework JNI | `compat/framework` (22), generated native tables | Explicit registrations and fallback stand-ins. A loadable class can still have a zero/null native fallback; real registrations can replace earlier stand-ins. |
| Graphics/surfaces | `compat/hwui` (41), ANGLE/Skia/AOSP build wiring | HWUI/GL over Direct3D with HWND/native surfaces. SurfaceControl/BLAST, secure composition and HDR require broader evidence. |
| Input/IME | `compat/input` (32), `compat/hwui/window` | Win32 messages and TSF become Android events/editor operations. Capture loss/modal cancellation is repaired; physical pen/touch fidelity remains separate. |
| Media/audio/codecs | `compat/media` (87) | WASAPI, Media Foundation, AOSP container readers and software codecs. Callback setters/ownership are repaired; DRM, routing and device-dependent policy remain partial. |
| Browser | `compat/webview` (9), Java WebView provider | WebView2 COM STA navigation/requests/cookies/permissions. Documented subframe/script/geolocation, proxy, client-certificate and geometry gaps remain. |
| Devices/accessibility | `compat/devices` (17), `compat/camera` (3), `compat/accessibility` (5) | WinRT, Media Foundation and UI Automation. Capability reporting, hotplug and actual assistive/device behavior need dedicated tests. |
| Verification/distribution | `tests`, `probes`, `.github/workflows`, `tools/ci`, launcher/installer | Host fixtures, Linux references, SDK callers, actual Windows ART/JNI/ELF, guest ABIs, E2E and artifact identity establish distinct evidence scopes. |

## 3. Fresh SDK and service inventories

Baseline and candidate app sources were compiled separately with JDK21/D8
against identical hash-verified platform inputs. The complete candidate service
test DEX also builds. The offline inventory compares official
`platform-36_r02/android.jar` with ordered `core-oj.jar`, `core-libart.jar`,
`core-icu4j.jar`, `framework.jar` and each freshly built `gesher-app.jar`.

Reused platform artifacts come from
[successful main run 43622672102](https://github.com/gesher-runtime/gesher/actions/runs/43622672102)
at `66f2077a25fc0066ba279dd8e99e5a7de39d7e50`; hashes are in the JSON. The app
baseline is the stated later `e0dbabf` source, not a downloaded app of unknown
revision. Local app generation uses pinned `frameworks/base/core/java` coverage
for 136 compatibility-change IDs. These compilation inputs are not a
distributable replacement for CI's complete source-generated table. Fresh full
framework/native builds and Windows execution are separate PR gates.

| Structural measurement | Baseline | Candidate |
| --- | ---: | ---: |
| SDK class definitions examined | 6,227 | 6,227 |
| SDK classes present in ordered boot definitions | 5,318 | 5,318 |
| SDK classes absent from boot | 909 | 909 |
| Public/protected declarations examined | 90,630 | 90,630 |
| Exact descriptor present without flagged modifier mismatch | 82,264 | 82,264 |
| Absent declaration on a present class | 548 | 548 |
| Declaration whose entire class is absent | 7,316 | 7,316 |
| Exact descriptor with possible modifier mismatch | 502 | 502 |
| Present descriptor referring to an absent boot type | 9 | 9 |

The nine dependency rows are an additional flag, not another exclusive status.
The parser resolves inherited declarations and boot provider order: 35 duplicate
class definitions choose the earlier provider; 773 exact-descriptor rows are
inherited. Descriptor presence does not establish JNI, ABI or behavior.

The unchanged SDK counts are expected. The two media setters already had Java
declarations but lacked real JNI implementations. The full NSD import adds
hidden metadata operations without restoring an absent public SDK declaration.
The main defects repaired here would all be missed by merely counting APIs.

| SDK family | Absent boot classes | Absent declarations on present classes |
| --- | ---: | ---: |
| Connectivity modules | 263 | 281 |
| Other Android framework | 212 | 16 |
| Health Connect | 207 | 24 |
| AdServices | 154 | 0 |
| Bluetooth | 41 | 142 |
| NFC | 21 | 59 |
| SDK sandbox | 9 | 26 |
| AndroidX annotation classes | 2 | 0 |
| Core Java, Javax, Dalvik and remaining SDK | 0 | 0 |

Absent classes may have module/shared-library/APK providers; annotations have
different implications from instantiated runtime classes. These are review
targets, not 909 demonstrated crashes. The other-framework present-class gaps
include eight `NetworkStatsManager` and eight `MediaStore` declarations, with
file-descriptor and document/media URI APIs. Exact descriptors are listed;
correct implementation requires their backing accounting/provider behavior.

The source scanner finds 70 service-class candidates, 67 interfaces in the
supplied compile JAR, 1,059 explicit methods, 2,767 inherited defaults, 51 direct
service registrations and 197 context-key candidates. Explicit methods comprise
877 implemented-but-unverified, 80 default answers, 76 no-ops, 12 empty collections
and 14 unsupported paths. Its override comparison uses method names only;
signatures and arities are recorded without overload/type resolution. This is
a review queue, not a reachable call graph or a count of broken service calls.

The JNI comparison records 174 upstream rows: 120 without matching local
registration-symbol text, 50 with matching symbols and four with alternate
symbols. It also records 523 stand-in method rows. Aliases, generated dispatch
and later replacements require manual review. The missing media callback
registrations in this patch were separately confirmed against exact upstream
descriptors and actual local tables.

The older native export lookup audit remains historical. It was not rerun in
this Linux executor, and its numbers are not presented as candidate measurements.
Selected synchronization exports are checked through actual registration,
compile boundaries and mandatory Windows guest fixtures.

## 4. Repairs and verification

Each focused report records pinned primary sources, behavior, test seams and
limits. Old-source controls use bounded functional checks; they do not execute
the known old null dereference, unbounded owner wait or overflowing expression.

| Family | Original problem | Result and focused evidence |
| --- | --- | --- |
| [Synchronization](native-clock-contracts-2026-11-14.md) | Explicit clocks could follow a condvar attribute; invalid clocks acquired resources; null timed deadlines failed; retry wrappers lost validation/owner precedence; rwlocks lacked writer ownership. | Core clock/deadline paths and bounded Win32 wait slices; owner checks without ABI changes. 216 sanitized assertions; 104 intended baseline failures across 183 safe checks; 20 Linux reference checks. |
| [Location](location-terminal-contracts-2026-11-14.md) | Terminal reentry/concurrency exceeded limits; a late fix lost current completion; finite listeners retained resources; current callbacks lacked death links; duration overflowed. | Locked terminal claims before callbacks; record-owned expiry independent of polling; identity-safe death/inactivation. 144 host assertions and seven baseline case failures. |
| [NSD values](nsd-value-contracts-2026-11-14.md) | TXT bounds differed; Parcel dropped IPv6 scope/type and used a divergent schema. | Complete pinned value implementation with license and build-annotation adaptations only. 41 SDK-bound host checks and four baseline case failures; native Parcel is a separate Windows gate. |
| [Media callbacks/state](media-callback-contracts-2026-11-14.md) | Extractor/retriever setters silently used stand-ins; player callbacks mishandled status/size/shared buffers; ownership/reentry/replacement retained invalid context/state or stale art. | Shared serialized JNI adapter, actual setters, retained receivers, close/error ownership, coherent replacement and reset outside the lock. 42 checked-JNI/UBSan assertions, eight baseline case failures; 35 sanitized state checks with six safe baseline failures among the original 27. |
| [Mouse capture](mouse-capture-contracts-2026-11-14.md) | Capture loss/modal cancellation left buttons and old gesture routing active. | One canceled gesture at its original target; Windows default cancellation preserved; next gesture starts cleanly. 20 sanitized checks; 12 baseline failures among 16 reached checks; existing 50 key checks pass. |

Pinned Android details are deliberate: invalid-clock `sem_clockwait` returns
positive `EINVAL` with unchanged errno; condvar deadline validation precedes
unlock, while mutex/rwlock acquisition and owner errors have different precedence.
NSD retains the release's TXT replacement accounting and nullable hosts. Ordinary
media release exposes close exceptions, including retriever `IOException`, while
cleanup preserves an already pending failure.

## 5. Independent QA and mandatory gates

Separate reviewers repeated source comparisons and positive/negative controls
for Java/input, native synchronization and media. QA found and resolved:

1. Duplicate initial disabled notifications to ordinary location registrations.
   Per-record state claims now suppress repetition, while callbacks remain
   outside the lock. First observations do not broadcast a fictitious global
   transition. Thirty-six new assertions cover listeners/PendingIntents,
   initial states, real transitions, removal and lock boundaries.
2. Signed intermediate overflow in bitrate for large virtual sources. The
   calculation follows Stagefright's floating average with conversion strictly
   below `2^63`; an unrepresentable result remains unavailable. Eight
   candidate-only assertions cover fractional, large and boundary values.
3. A possible legitimate signal/timeout race in the independent Linux reference.
   It now checks timeout without a competing signaler, preserving its external
   deadline and 20 assertions. Shared old/new guest controls remain unchanged.

Relevant existing location/input, semaphore/wait/futex, media API, retriever,
source, HTTP, surface and player-priming suites passed. Complete changed pthread
units compile under MinGW for x64/ARM64; the shared C fixture compiles with
NDK27c for both guest ABIs. Full window/popup and four full media units pass
supplemental MinGW syntax checks. These do not replace pinned-AOSP CMake linking.

Local ASan disables only LeakSanitizer because the executor is ptraced. ASan and
UBSan remain active; CI sanitizer defaults, assertions, timeouts and artifact
identity controls are unchanged. Both YAML workflows parse; all five changed
Bash step bodies pass `bash -n`. PowerShell additions received static review;
actual parsing/execution is a Windows gate.

| Mandatory gate | Evidence supplied |
| --- | --- |
| Linux artifact preflight | Sanitized pthread/mouse/state contracts; separate Linux clock reference; checked-JNI media after JDK setup. |
| Framework SDK/linkage | Production Location service and SDK-bound NSD caller against real built inputs. |
| Windows track33 | Real Android event containers and hidden Win32 window/message/routing cancellation. |
| Windows tracks40/47 | Shared `libc_test` through x86-64 ELF and translated ARM64; existing completion markers retained. |
| Windows track94 | Public media callbacks with actual readers; real NSD Parcel requires both class success marker and `parcel=true`. |
| Windows track95 | Generated Location AIDL and public `LocationManager` terminal-delivery paths. |
| Full CI | Native linking, framework/ART build, Windows2022/2025 suites, existing E2E and source/artifact identity. Exact-head results are recorded on the PR. |

The tests are unconditional additions. This report records reproduced local
evidence; PR checks supply separate native Windows results. Physical devices,
an Android-device differential run, every APK and native ARM64 Windows are not
inferred from Linux fixtures or x64-hosted ARM64 translation.

## 6. Remaining priorities

| Priority/area | Current finding or uncertainty | Required follow-up |
| --- | --- | --- |
| High: module/member coverage | The complete 909-class/548-member queues remain; present descriptors can refer to absent types. | Resolve provider/loading scope, then implement coherent value/service families with SDK callers and runtime tests. |
| High: target-thread metadata | `pthread_getattr_np` still ignores its target and reports the caller's stack; stack/guard attributes are partly honored. | Retained target identity/stack metadata with lifetime/reuse and guest-ABI tests. |
| High: shared-memory protection | `ASharedMemory_setProt` reports success without enforcing reduced protection. | Alias-aware object protection integrated with subsequent mappings and monotonic permission reduction. |
| High: service/JNI defaults | Thousands of inherited defaults and hundreds of stand-in rows need reachability/contract classification. | Prioritize actual public paths and diagnostics; inspect later registrations and implement concrete completion/error semantics. |
| Medium: time/signals | UCRT limits local dates; polling signal masks and process-shared pthread semantics remain incomplete. | Windows timezone/DST and pre-1970 date design, atomic signal masks and interprocess synchronization. |
| Medium: networking/filesystem | Ancillary data, some timeout widths, path-based directory lifetime and extended namespace/metadata semantics remain partial. | Shared-description/lifetime models and Linux/Android differential checks, preserving PR24's separate path work. |
| Medium: location/NSD | GNSS/geofences/batching and provider accuracy remain partial; cached IP fixes refresh timestamps without establishing a fresh sample; no mDNS backend. | Real provider/device evidence, freshness semantics and discovery/advertisement implementation. |
| Medium: media/graphics | Physical route setters, AAudio error callbacks, secure/DRM, device codecs, compositor/HDR and arbitrary concurrent track/setter mutation remain incomplete. | Public completion/capability tests on real backends and coherent supported state serialization. |
| Platform: browser/input/accessibility | Desktop bridges do not prove Android subframes, devices, geometry or assistive-tool fidelity. | Dedicated application/browser and physical/assistive-device matrices. |
| Platform: process/linker/architecture | Fork, mounts/namespaces, complete linker namespaces/unloading and native ARM64 Windows are separate work. | Explicit designs and host/guest gates, beyond symbol presence. |

Defects in excluded `com/google` test-client stubs are not production boot-linkage
findings. Earlier provider/sync/settings TODOs and older 911-class/584-member
counts are not reused as current measurements. The exact queues and source-backed
priorities above provide concrete inputs for subsequent contract families.

## 7. Reproduction and references

```sh
python3 tools/java_api_inventory.py --sdk-jar /path/to/android.jar \
  --boot-jar /path/to/core-oj.jar --boot-jar /path/to/core-libart.jar \
  --boot-jar /path/to/core-icu4j.jar --boot-jar /path/to/framework.jar \
  --boot-jar /path/to/baseline/gesher-app.jar \
  --candidate-replace gesher-app.jar=/path/to/candidate/gesher-app.jar \
  --outdir out/audit/classes
python3 tools/java_member_inventory.py --sdk-jar /path/to/android.jar \
  --boot-jar /path/to/core-oj.jar --boot-jar /path/to/core-libart.jar \
  --boot-jar /path/to/core-icu4j.jar --boot-jar /path/to/framework.jar \
  --boot-jar /path/to/baseline/gesher-app.jar \
  --candidate-replace gesher-app.jar=/path/to/candidate/gesher-app.jar \
  --outdir out/audit/members
python3 tools/audit_services_jni.py --framework-jar /path/to/framework-classes.zip \
  --aosp-dir /path/to/pinned/aosp --out out/audit/services-jni.json
```

The JSON lists every missing class and present-class/modifier/dependency review
row. The 7,316 declarations on absent classes are represented by the full absent
class list and totals; all 90,630 declaration rows can be regenerated without
committing a 42-MB duplicate table. Input/output and generator hashes identify
the snapshot. Service JSON is a normalized source inventory, not a runtime log.

Pinned primary references and decoded-source hashes are linked in the five
focused reports: AOSP bionic synchronization, LocationProviderManager and
LocationRequest, Connectivity NsdServiceInfo, JMediaDataSource/CallbackDataSource,
MetadataRetrieverClient and Stagefright, plus Microsoft's capture/cancel messages
and Android's MotionEvent cancellation contract.
