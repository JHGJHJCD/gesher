# Android runtime contract audit — 2026-11-16

This audit maps the repository, refreshes the public SDK and service/JNI review queues, and repairs seven groups of demonstrated runtime contract problems: socket timeouts, DNS hints, activity finishing, null service bindings, SoundPool receiver ownership, MediaRecorder receiver ownership, and Bluetooth GATT MTU reporting. Separate researchers, implementers and independent reviewers examined the changes. All work in this round is attributed to **palmoni5**.

The results establish specific improvements, not compatibility with every Android application. Static availability, correct Java descriptors, a successful build and passing focused tests answer different questions. None alone proves that a rarely used application, native library or physical device will work.

## 1. Source snapshots and complete navigation map

| Snapshot | Revision |
| --- | --- |
| Audit baseline | `a3b103790f0d856a17d644075702af702a99c86f` |
| Published implementation snapshot used by these inventories | `98f36644cc5d6d571c5d297670495329c85df31f` |
| Current main integrated during the audit | `fa55ed99fc23df3762426c63621b0aa06cfda61c` |

The [source map](android-runtime-map-2026-11-16.json) records **3,252 tracked paths**, compared with **3,211** at baseline. Every row retains its path, subsystem, Git mode, object type, blob ID and byte size. The 41-path increase includes changes integrated from main; it is not an attribution of every addition to this audit. Report files added after the implementation snapshot are intentionally not self-inventoried.

| Repository area | Paths | Responsibility |
| --- | ---: | --- |
| `app` | 362 | Application bootstrap, Java overlays, services, generated stubs and client compatibility sources |
| `compat` | 527 | Native Android/Linux-to-Windows adapters and execution backends |
| `cmake` / `patches` | 33 / 75 | Build composition and changes applied to pinned upstream projects |
| `tests` | 1,300 | Native, JVM, ART, application, integration and Windows fixtures |
| `tools` / `probes` | 406 / 83 | Builders, inventories, regression runners and diagnostic programs |
| `docs` | 364 | Architecture, decisions, contracts and prior audit evidence |
| `.github` | 89 | Build, validation and release orchestration |
| `config` / `assets` / `java` / root files | 4 / 1 / 1 / 7 | Configuration, artwork, Java support and repository metadata |

This is complete path coverage, not a claim that every file received equally deep semantic review. Investigation followed call chains around ABI conversion, lifetime, callbacks and lifecycle transitions, where a small mismatch can stall or terminate otherwise working applications.

### Execution and Windows mappings

The [architecture overview](../architecture.md) describes the broader design. The following map identifies the boundaries reviewed here and the native behavior that still needs platform validation.

| Android-facing path | Gesher implementation and Windows boundary |
| --- | --- |
| APK, resources and DEX | `app/java/com/gesher/app` and `server/ApkPackage` prepare real Android framework/ART execution, using the pinned upstream parser and runtime. |
| Native `.so` libraries | `compat/elf` loads ELF objects; `compat/bionic` exposes guest ABI adapters; `compat/arm64` translates supported ARM64 execution. Windows does not load these as ordinary PE DLLs. |
| Files, sockets, timers and threads | `compat/posix` maps descriptors and synchronization to CRT/Win32 handles, Winsock and Windows threads; guest structure layouts and error values remain an additional bionic boundary. |
| System services and activities | `Services`, `StandIn`, generated AIDL stubs and `ActivityTaskManagerService` provide in-process or device-mode service calls, with activity transactions managed by Gesher. |
| Display, input and rendering | `WindowSession`, input adapters, HWUI/Skia and ANGLE connect Android windows and rendering to Windows input and Direct3D-backed graphics. |
| Audio, recording and camera | `compat/media` uses Windows audio/capture and codec backends; camera services reach Media Foundation. Java/JNI ownership is separate from physical-device and format support. |
| Bluetooth, sensors and location | Java/services and native adapters reach WinRT capabilities, including GATT session properties; RFCOMM uses its Windows networking path. |
| Web and network configuration | WebView uses WebView2; networking uses the host socket/resolver paths and Windows proxy/trust integration. |

The survey also distinguishes actual runtime inputs from source-only helpers. In particular, `app/build.sh` excludes `app/java/com/google` client SDK stubs from the boot artifact. Their presence in the repository does not by itself show that an app's bundled Google classes are shadowed.

## 2. SDK, service and JNI inventories

### Public SDK structure

The [SDK inventory](android-runtime-inventory-2026-11-16.json) compares SDK 36 with an explicitly ordered boot path: `core-oj.jar`, `core-libart.jar`, `core-icu4j.jar`, `framework.jar`, then `gesher-app.jar`. The candidate replaces the last archive. These are selected-input comparisons; actual class-loader behavior, optional module providers, APK libraries and desugaring are not established by the scan.

| Class measure | Baseline | Integrated candidate |
| --- | ---: | ---: |
| Public SDK class definitions examined | 6,227 | 6,227 |
| Available on the selected boot path | 5,335 | **5,362** |
| Absent from that path | 892 | **865** |
| Total selected boot class definitions, including non-SDK classes | 45,549 | 45,644 |

**The 27 newly available public classes come from HttpEngine PR #49, merged independently into main during this audit.** The same upstream integration supplies 204 newly matching public/protected member declarations. This audit's behavioral repairs alone do not increase those public class/member counts. The JSON's `delta_attribution` and `candidate_class_delta` make that distinction explicit.

The member scanner counts each SDK declaration once, resolves eligible superclass/interface members, and compares full JVM descriptors, including return and field types. Constructors are not inherited. Its **90,630** public/protected declarations divide as follows:

| Candidate member status | Declarations |
| --- | ---: |
| Exact descriptor present without a scanned modifier candidate | **82,899** |
| Absent declaration on a class that is present | **428** |
| Declaring class absent from selected boot inputs | **6,800** |
| Exact descriptor present with a possible visibility/final/other flag mismatch | **503** |

There are also **nine** otherwise-present descriptors whose referenced types are absent from the selected boot path. These overlap the declaration population; they are not nine extra SDK declarations. The committed review queue contains **940 rows**: the 428 absent declarations, 503 modifier candidates and nine dependency cases. The 6,800 declarations belonging to absent classes are represented by the complete 865-class list. The full 90,630-row JSONL is reproducible; its SHA-256 is recorded in `member_summary.member_inventory`.

Absence is concentrated in module or feature families, including connectivity, Health Connect, advertising services, Bluetooth and NFC. Some entries are annotations or depend on providers outside the examined path. Conversely, an exact descriptor says nothing about method bodies, constants, exceptions, native registration, permissions or callback timing. These inventories are review queues, not counts of broken APIs, broken applications or compatibility percentages.

### Services and native registration

The [service/JNI review](android-runtime-service-review-2026-11-16.json) retains the source scanner's complete rows, hashes and limitations:

| Surface | Observed count |
| --- | ---: |
| Scanned service/listener implementation classes | **73** |
| Classes with corresponding interface available in the supplied framework input | **70** |
| Explicit source methods | **1,149** |
| Inherited default-method candidates | **2,745** |
| Direct service registration sites | **55** |
| Context-key candidates lacking a direct matching source registration | **194** |
| Upstream JNI registration rows | **174** |
| JNI stand-in method rows | **523** |

Of the 174 upstream registration rows, **120** have no textual reference on the examined local surface, **50** have a same-symbol reference, and **four** have a reviewed alternate registration name. None of these statuses proves reachability or absence at runtime. Attach-time JNI stand-ins can be replaced by later real registrations. Context managers can be local or use different Binder keys. Empty/default answers can correctly describe an unavailable capability; they require contract-specific evaluation before being labeled defects.

## 3. Seven implemented contract repairs

### Socket timeout conversion

[`socket_bionic.cc`](../../compat/bionic/socket_bionic.cc) now validates full-width guest timeout values before converting through the narrower host representation. Previously, invalid large microsecond values or wide negative seconds could change meaning during narrowing. The fix preserves descriptor/non-socket error precedence and existing option values after rejected updates.

The harness compiles production conversion and timeout branches, uses real local sockets and an explicit 32-bit intermediate structure, and compares applicable behavior with Linux. It passes **143 assertions across production and reference runs**; the old source fails 18. Existing differences remain explicit: Gesher rejects negative seconds where Linux can accept immediate-timeout behavior, and large positive durations retain the host's saturation policy.

### DNS hints and error precedence

Resolver entrypoints now validate the pinned Android hint contract before backend work or allocation, return bionic error values, and clear the output pointer on failure. The admitted flag mask is **`0x40f`**, as defined by Android 16 release 4. Output-only hint fields are rejected with `EAI_BADHINTS`; a query with both hostname and service null retains `EAI_NONAME` precedence.

Independent QA found that the initial implementation and its passing tests both accepted three historical Gesher flag extensions outside the pinned mask. QA also found incorrect combined-error ordering. The corrected suite tests all 32 flag bits, malformed metadata, combined invalid inputs, backend errors and allocation behavior through three routes. **232 assertions pass**, while **53 fail against the pre-QA candidate**. This correction supersedes the earlier research note's broader DNS whitelist. The reference is the exact pinned [bionic header](https://android.googlesource.com/platform/bionic/+/android-16.0.0_r4/libc/include/netdb.h), [libc resolver](https://android.googlesource.com/platform/bionic/+/android-16.0.0_r4/libc/dns/net/getaddrinfo.c) and [DnsResolver validation](https://android.googlesource.com/platform/packages/modules/DnsResolver/+/android-16.0.0_r4/getaddrinfo.cpp).

### Activity task, affinity and child finishing

[`ActivityClientController`](../../app/java/com/gesher/server/ActivityClientController.java) previously handled task-removal flag 2 while public `finishAndRemoveTask()` sends flag 1. The implementation now removes a task for flag 1 only when called by its root, and for flag 2 unconditionally. Ordinary finishing preserves supplied results; actual task removal cancels outstanding results and ignores supplied success data.

Previously inherited defaults for `finishAffinity` and `finishSubActivity` are now implemented. Affinity traversal stops at the first different affinity below the caller in the same task. Child cancellation matches parent record, request code and null-safe result identity. Cancellation results reach resumed recipients immediately or precede a returning recipient's resume.

All selected records are marked finishing before callbacks can reenter. Pending launch processing excludes those records, and completion continues from current lifecycle state after a reentrant acknowledgement. The **170-check** fixture covers interleaved tasks, null affinities, routing, result order, delayed acknowledgements, repeated calls and cleanup. Independent review compared the pinned [controller](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/services/core/java/com/android/server/wm/ActivityClientController.java) and [ActivityRecord](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/services/core/java/com/android/server/wm/ActivityRecord.java).

### Published null service bindings

[`ActiveServices`](../../app/java/com/gesher/server/ActiveServices.java) conflated a published null Binder with a binding still awaiting publication. Initial clients received completion, but later clients could wait indefinitely. Publication state is now retained separately from the value, so every later client receives the existing result, including null.

The **65-check** fixture uses the real `LoadedApk.ServiceDispatcher` and public `ServiceConnection.onNullBinding`. It covers waiting and late clients, auto-create flags, queued executors, reentrant unbind, cleanup, distinct intents and non-null controls. This follows the [ServiceConnection contract](https://developer.android.com/reference/android/content/ServiceConnection), which still requires clients to unbind after a null result.

### SoundPool receiver ownership

[`soundpool_jni.cc`](../../compat/media/jni/soundpool_jni.cc) previously obtained an unretained native receiver while release could delete it. JNI entrypoints now acquire a receiver lease under the same context mutex used for detach. Admitted operations retain ownership; later entry sees the detached state. Destruction and loader joining occur outside that short mutex after the last owner releases it. Mute and volume updates use the same Pool mutex. Official [AOSP SoundPool JNI](https://android.googlesource.com/platform/frameworks/base/+/master/media/jni/soundpool/android_media_SoundPool.cpp) provides the ownership comparison.

### MediaRecorder receiver ownership

[`media_recorder_jni.cc`](../../compat/media/jni/media_recorder_jni.cc) now retains receivers and serializes their mutable operations. Concurrent release detaches once and defers destruction while admitted work continues. Reentrant release remains supported; mutating reentry is rejected. Failed JNI reference setup is not published as a partial receiver.

The shared host suite executes whole production JNI units under checked JNI and UBSan, with declared backend observers. This repairs ownership and operation ordering, not the full recording state machine, encoder choice or hardware behavior. The comparison uses official [AOSP MediaRecorder JNI](https://android.googlesource.com/platform/frameworks/base/+/main/media/jni/android_media_MediaRecorder.cpp); existing extractor/retriever helper users were also regression-tested.

### Bluetooth negotiated MTU and change callbacks

[`BluetoothGatt`](../../app/java/android/bluetooth/BluetoothGatt.java) now reports the actual backend MTU instead of inventing a value limited by the request. A request of 64 on a link whose actual MTU is 247 reports 247. Native MTU-change events now post `onMtuChanged`; the event value is captured before buffer reuse, and existing generation guards suppress delivery after disconnect or close.

All six GATT cases pass. Old-source controls detect the incorrect requested-MTU result and absent event callback. References are Android's [GATT callback contract](https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback) and Windows [GattSession.MaxPduSize](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.genericattributeprofile.gattsession.maxpdusize). No physical radio was exercised locally.

## 4. Independent QA, integration and remaining execution gates

Independent native, framework and media reviewers found no remaining blocker within the reviewed changes after the DNS correction. Reviewers executed production-code fixtures separately from the implementers and inspected lifetime, ownership and callback sequencing.

| Verification | Observed result |
| --- | --- |
| Final combined app compilation and D8; complete services-test compilation and D8 | Passed against full pinned platform classes |
| Activity and null-binding suites against final combined dependencies | **170 + 65 checks passed** |
| Existing ComponentCaller regressions | **177 protected-mode + 16 bootstrap checks passed** |
| Corrected DNS / timeout suites | **232 / 143 assertions passed**, with the scopes described above |
| Media receiver lifetimes / existing media callbacks / SoundPool state | **50 / 66 / 10 checks passed** |
| Additional independent media sequencing | **10 queued-operation + nine replacement-with-in-flight-operation checks passed** |
| Bluetooth GATT | All six cases passed |
| Old-source controls | Detected the targeted activity, binding, timeout, DNS, receiver and MTU regressions |

The media host backend is an observer, not a recording device. Activity host threads acknowledge real transaction objects but do not execute application ActivityThread callbacks or render windows. Host tests do not substitute Parcel transport. The activity fixture also uses a separate classloader to expose accidental package-private access that would fail with a separate ART DEX.

Local ASan/UBSan runs passed; LeakSanitizer could not run under the executor's ptrace environment. Leak scanning was disabled only in those local invocations, not in committed runners or CI, and no leak-sanitizer success is claimed.

[`ci.yml`](../../.github/workflows/ci.yml) and [`framework.yml`](../../.github/workflows/framework.yml) retain existing HttpEngine and memory checks and require the new host regressions. Independent integration review confirmed the CI additions do not remove current-main gates. [`track 99`](../../tests/windows/tracks/99-android-windows-contracts.ps1) requires bounded ART fixtures for activity finishing, null binding and public media receiver release, checking both exit status and success markers. Native fixtures are included in the existing Windows/guest libc path, including translated ARM64 coverage where configured.

**Actual Windows, Winsock, ART/Binder and translated ARM64 success for the final revision is a separate pending CI observation at report drafting time.** Workflow configuration and local compilation are not recorded as execution success. Fresh CI must also perform its normal generated-table build. Publication does not authorize automatic merging; no auto-merge is requested.

## 5. Prioritized remaining work

1. **ELF failed-load publication and graph/TLS lifetime.** [`elf_loader.cc`](../../compat/elf/elf_loader.cc) exposes objects before dependency/relocation completion. A failed object can remain visible to retry, lookup or enumeration. Fixing only one object is insufficient when a dependency cycle has already run another object's constructors. TLS modules, rewritten executable ranges and ARM64 translation retain references without coordinated rollback. The next design needs dependency-graph staging, constructor ordering, concurrent/reentrant load rules and safe TLS/code lifetime. Blind unmapping would introduce another failure mode.

2. **Pipe flags, readiness and atomicity.** `compat/posix/src/unistd.cc`, `system.cc` and `fd.cc` still contain ignored `pipe2`/`F_SETFL` behavior and incomplete endpoint/capacity readiness. A healthy write endpoint can be mistaken for hangup. A Windows nonblocking byte-pipe mode alone does not guarantee all-or-EAGAIN writes up to `PIPE_BUF`; see Microsoft's [pipe mode semantics](https://learn.microsoft.com/en-us/windows/win32/ipc/named-pipe-type-read-and-wait-modes). Repair needs alias/inheritance ownership, child stdio, close/EOF, capacity and concurrent-writer tests.

3. **Target-thread pthread contracts.** Guest `pthread_getattr_np` can return the current thread's stack instead of the requested thread's, affecting collectors and unwinders. Attribute validation also needs work. The host CPU-clock target issue is distinct from the guest clock adapter; the two must not be conflated. Some apparent missing attribute exports were found in a secondary registration module and are not missing APIs.

4. **Full multi-intent activity stacks.** `startActivities` still creates only the final activity and omits preceding back-stack entries. TaskStackBuilder and multi-intent pending launches need a queued stack/lifecycle plan, not a loop of superficial launches.

5. **Service rebinding.** `ActiveServices.unbindFinished` still ignores `onUnbind(true)`; a service kept alive by a start can receive another onBind instead of onRebind. The null-publication repair does not resolve this separate contract.

6. **Network activity and mDNS.** Default-network activity listener methods remain empty, and NSD lacks a working discovery backend. Link availability is not equivalent to network-activity events. Both need explicit Windows event sources, executor and cancellation behavior.

7. **Media and hardware completeness.** Remaining areas include camera/sensor feature advertisement and metadata, AudioRecord device routing, recorder codec substitutions, MediaSync/DRM/Crypto and NDK/surface contracts. Physical audio, camera and Bluetooth error paths require real Windows hardware validation. Receiver safety does not prove codec or format compatibility.

Two further items are **untested follow-up candidates**, not newly demonstrated failures: the older ordinary single-activity finish path may leave a result queued for an already-resumed recipient; and the host X-button comment expects whole-task closure while invoking a public method whose non-root semantics finish one activity. Neither is claimed fixed or independently reproduced here.

## 6. Provenance and reproduction

All detailed input receipts are retained in the SDK JSON's `provenance`, `class_inventory_metadata.inputs` and `member_summary.inputs`. Full framework/core classes and boot artifacts came from verified successful CI run **43877105787**, source `c7c4a88486554029874b6f1fe80f1c110a026d63`. The relevant `aosp.lock`, patches, framework/core builders, app builder and CMake AOSP inputs were unchanged from baseline. The source pin is **`android-16.0.0_r4`**.

SDK `android.jar` SHA-256 is `d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a`. Baseline and candidate app sources were compiled freshly. Local compilation reused the **complete** CompatChangeTable recovered from PR42 CI, hash `fdbdd619eb03f77b0e952917cc8713379be8bba84c810de18ddce2826e8ff413`; it was not regenerated from a complete local AOSP checkout. These JARs are test/inventory inputs, not release artifacts. The candidate app DEX hash is `6516819a490719d4a972dc92aa643cabb54d3218f6c8c7a37eb73884d78f16b0`; class and service-test DEX hashes are also recorded in provenance.

To reproduce the structural comparison, supply the hash-matched inputs and preserve their order:

```sh
# Set AUDIT_SDK, AUDIT_BOOT and AUDIT_CANDIDATE to the recorded inputs.
inventory_args=(--sdk-jar "$AUDIT_SDK" --sdk-api-level 36
  --boot-jar "$AUDIT_BOOT/core-oj.jar"
  --boot-jar "$AUDIT_BOOT/core-libart.jar"
  --boot-jar "$AUDIT_BOOT/core-icu4j.jar"
  --boot-jar "$AUDIT_BOOT/framework.jar"
  --boot-jar "$AUDIT_BOOT/gesher-app.jar"
  --candidate-replace "gesher-app.jar=$AUDIT_CANDIDATE")
python3 tools/java_api_inventory.py "${inventory_args[@]}" --outdir out/audit/classes
python3 tools/java_member_inventory.py "${inventory_args[@]}" --outdir out/audit/members
python3 tools/audit_services_jni.py --framework-jar "$AUDIT_FRAMEWORK_CLASSES" \
  --aosp-dir "$AUDIT_AOSP" --out out/audit/service-review.json
```

The source-map path population can be checked with `git ls-tree -r --name-only 98f36644cc5d6d571c5d297670495329c85df31f`; the JSON additionally stores blob metadata. Generator hashes and scanner limitations are part of the committed receipts.

For behavioral reproduction, run `tools/test_socket_timeout_contracts.py --linux-reference --sanitize`, `tools/test_bionic_dns_contracts.py --sanitize`, `tools/test_media_receiver_lifetimes.py`, `tools/test_media_callback_contracts.py`, and `tools/test_bluetooth_gatt_contracts.py --java-home "$JAVA_HOME"`. The activity, null-binding and component-caller runners require `--framework-classes`, `--core-classes`, `--app-classes`, `--apksig` and `--java-home`; use the final freshly built app classes. Each runner documents its bounded old-source controls. Build the app with `app/build.sh`, then the complete service fixtures with `tests/dex-services/build.sh`; execute the configured Windows tracks for the platform-specific evidence that host tests cannot supply.
