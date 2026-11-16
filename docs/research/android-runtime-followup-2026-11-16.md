# Android / Windows compatibility follow-up — 2026-11-16

Base: `99344ef805b2503f14ba37c2db723e8a14d700dd`. Work and commits use
`palmoni5`. This round repairs network registrations, AAudio lifetime/layout
queries, and native/translated pthread metadata.

The deliverables distinguish a complete **path inventory**, an **API/source
review queue**, and **behavioral evidence for selected contracts**. They do not
establish that every Android application runs, that every method was
semantically reviewed, or that symbol presence proves a working Windows backend.

## Duplicate avoidance

Open PRs were read before implementation and refreshed during integration.
Their production repairs were excluded from this follow-up.

| Open PR | Observed head | Existing work kept separate |
| --- | --- | --- |
| [#51](https://github.com/gesher-runtime/gesher/pull/51) | `40f5255920db9aac932010ab68ace5bf16b7de00` | Activity lifecycle, null service binding, Bluetooth, socket/DNS, media JNI receiver leases and its broader runtime inventory. |
| [#44](https://github.com/gesher-runtime/gesher/pull/44) | `00d63094793ab7ead55004001eeb97493df90258` | Capture/path/dispatch/camera/desktop performance. |
| [#50](https://github.com/gesher-runtime/gesher/pull/50) | `d955363c8b5e73c3187d70ad06aca2f9e949185c` | CI contributor policy, workflow source/actor policies and automation. |

Workflow, CMake and documentation files are shared integration surfaces. This
PR adds its own gates without removing existing ones or merging other PRs.

## Repository map

The [path map](android-runtime-followup-map-2026-11-16.json) records all **3,253
tracked baseline files**, exact Git blob IDs and sizes. The fixed baseline
avoids a generated map claiming to hash itself.

| Area | Files | Responsibility |
| --- | ---: | --- |
| `compat/` | 531 | POSIX/Win32, bionic, ELF, ARM64, Binder, graphics, media and devices. |
| `app/` | 362 | Bootstrap, framework replacements, services, providers and app resources. |
| `cmake/` + `patches/` | 110 | Windows build integration and pinned AOSP modifications. |
| `tests/` + `probes/` | 1,380 | Host, guest, ART, Windows, app and platform regressions. |
| `.github/` + `tools/` | 492 | Source acquisition, builds, inventories, tests and packaging. |
| `docs/` | 365 | Architecture, decisions and prior evidence. |
| Root, `assets/`, `config/`, `java/` | 13 | Entry points, configuration and supporting assets. |

APK preparation leads to the Windows process host, ART and boot JARs. Java
framework replacements dispatch to local/remote Binder service implementations.
Native x86-64 code passes through the ELF loader and bionic/Win32 compatibility
layer. ARM64 code additionally executes through the translator and its private
guest stack. Audio reaches the existing mixer/capture and Windows device
backends; graphics/input/WebView reach their Windows integrations.

This round adds behavioral evidence at network registration, AAudio and pthread
boundaries. UI/graphics/WebView and physical hardware are mapped but are not
claimed as newly verified comprehensive backends.

## Refreshed API inventory

The [inventory receipt](android-runtime-followup-inventory-2026-11-16.json)
records exact input hashes and the full absent-class/missing-member queues.
Reference: SDK 36 revision 2. Boot order: core-oj, core-libart, core-icu4j,
framework, then freshly compiled gesher-app.

| Structural observation | Count |
| --- | ---: |
| SDK classes inspected | 6,227 |
| Present on selected boot path | 5,362 |
| Absent on selected boot path | 865 |
| Public/protected member declarations | 90,630 |
| Exact descriptor present without flagged modifier mismatch | 82,899 |
| Missing member on present class | 428 |
| Member's declaring class absent | 6,800 |
| Possible access/final modifier difference | 503 |
| Present descriptors referencing absent types | 9 |
| Duplicate class definitions, first provider wins | 35 |

The 428 missing members comprise 230 methods, 196 fields and two constructors.
The 503 modifier candidates comprise 498 method-final restrictions, four
class-final restrictions and one public-visibility candidate. The latter is
BluetoothA2dp.finalize resolving to Object.finalize; source review found no
owned resource an empty override would repair, so no counter-only stub was added.

These are structural review candidates, not defect counts. Modules, shared
libraries and APK providers can change availability. Presence does not establish
behavior. Counts remain unchanged by these fixes and agree with PR #51's broad
inventory; they are not presented as new discoveries or newly repaired APIs.

The service/JNI scan sees 73 service classes, 70 matching interfaces, 1,148
explicit methods, 2,746 inherited-default candidates, 55 direct registrations,
194 Context-key candidates, 174 upstream JNI registrations and 523 stand-ins.
Explicit methods: 949 implemented-but-unverified, 15 empty collections,
86 default answers, 83 no-ops, 15 unsupported paths. JNI entries: 120 without a
direct local reference, 50 same-symbol references, four alternate references.

This scan is heuristic: inherited matching is name based, generated/indirect
registration may be absent, and an empty answer can be correct for unavailable
hardware. The receipt omits the generator's historical hard-coded
reviewed_known_gap_families instead of treating old prose as new findings.

## Repairs and independent QA

| Family | Observable repairs | Independent local evidence |
| --- | --- | --- |
| [Network](network-lifecycle-2026-11-16.md) | Refresh query-only snapshots; subscribe before snapshot; preserve reentrant updates; clean failed attachment; atomic same-token replacement; old send cannot cancel new owner. | 73 callback/registration + nine service-query assertions pass. Four baseline controls fail at the repaired behaviors. |
| [AAudio](aaudio-lifecycle-contracts-2026-11-16.md) | Keep released streams CLOSING, retire worker/backend once, prevent old callbacks affecting new start, reject self-joining lifecycle calls, preserve masks/setter order, export API36 device-list query. | 221 sanitized assertions pass; extended baseline has 106 expected failures. Builder103 and existing media37/17/32/180 all pass. |
| [pthread](../audits/native-pthread-attributes-2026-11-16.md) | Query requested retained thread; publish native bounds before user code; reflect live detach; validate pinned Android attribute values; publish separate ARM64 guest stack and wait for attachment. | 78 sanitized assertions pass; 39 expected baseline failures. Existing clocks216, key groups and Linux public subset24 pass. Actual Win32 and both Android ABI executions remain mandatory gates. |

The reports pin Android16r4 sources and explain test boundaries. QA used fresh
agents and isolated worktrees. Network QA added failed-cleanup/concurrent-retry
and token-alias cases. Audio QA added simultaneous release, cross-stream
callback isolation and partial-write accounting. Host observers do not claim
real Windows hardware or Binder execution.

AAudio now rejects legal layouts its mixer cannot map with UNAVAILABLE before
device creation. The report lists every affected layout; this is a behavior
change, not physical surround support. API29 guests use typed dlsym for newer
exports without raising their minimum API. Network freshness costs a native
enumeration per unwatched query. Native guard/scheduling defaults are not
measured Windows properties; exited-but-unjoined snapshots do not prove live
stack memory.

## Build provenance and hosted gates

Fresh application and service-test DEX compilation used a complete JDK and
verified platform inputs from [run 43877105787](https://github.com/gesher-runtime/gesher/actions/runs/43877105787),
source c7c4a88486554029874b6f1fe80f1c110a026d63. Archive/JAR hashes are in the
receipt. Current main adds two native ART patches since that artifact; Java
framework recipes are unchanged. The local fixture build reuses the pinned
generated compatibility table because the available AOSP checkout is sparse.
It is not a fresh full-platform build or distributable runtime. Hosted CI still
fetches pinned sources, regenerates the table and builds the current runtime.

Mandatory additions cover sanitized host contracts, independently observed
Win32 target stacks, and NDK API29 fixtures on x86-64 and translated ARM64.
Existing Binder/Parcel/HandlerThread network tests remain enabled. AAudio guests
use the explicit null audio device for export/lifecycle checks; audible fidelity
and physical routing require separate device evidence. No ARM64 containment
assertion or failing platform gate is removed to obtain a passing result.

The final PR head's hosted checks are the Windows/build execution receipt.
Local results do not substitute for queued, skipped or failing hosted jobs.

The integration wiring was independently checked: actionlint, PowerShell
parsing, all four shard plans, invalid-plan rejection and failure propagation
passed. Track99 runs once in the services shard after its timeout provider;
missing binaries or success markers fail the suite. Native and guest fixtures
each have a 60-second process bound. Both new host runners are in required
artifact-preflight. Integrated local reruns passed network73+9, AAudio221 and
pthread78 assertions, and the final service DEX rebuilt successfully.

No fresh exhaustive NDK export inventory is claimed. Native evidence here is
the selected production-export tests and required guest ABI executions; Java
SDK counts cannot be interpreted as native-library coverage.

## Concrete remaining work

| Finding | Source evidence | Next required boundary |
| --- | --- | --- |
| Pipe nonblocking/flags | POSIX unistd.cc pipe2 uses only CLOEXEC; system.cc generic F_GETFL returns RDWR and F_SETFL ignores mutation. | Shared open-description state, aliasing, bounded I/O, readiness, PIPE_BUF, child stdio; a flag-only patch would misreport behavior. |
| Failed ELF graph publication | compat/elf/elf_loader.cc; [earlier native audit](android-windows-map-2026-11-14/native.md). | Loading/ready/failed graph ownership, dependency cycles, TLS, reentrancy and safe rollback; blind unmapping can invalidate references. |
| Service rebind | ActiveServices.unbindFinished remains empty. | Rebind state and lifecycle ordering, coordinated with PR #51. |
| Multi-intent back stack | ActivityTaskManagerService.startActivities starts only the final intent. | Full logical stack with correct lazy lifecycle; repeated ordinary starts are not equivalent. Coordinate with PR #51. |
| Network activity and mDNS | Active-listener methods remain empty; full discovery backend absent. | Real Windows traffic/discovery sources and cancellation/executor lifecycle; availability is not traffic. |
| PendingIntent beyond one token | Requests remain process local. | Server-owned persistence, cross-token filter matching, one-shot and delayed release, cross-process tests. |
| pthread creation/lifetime | Custom stacks, requested guards/scheduling and post-exit stack retention remain partial. | Platform-backed allocation/ownership and independent tests; existing Env allocation failure can terminate the process. |
| AAudio backend parity | Physical routing, permissions, MMAP, offload, spatialization, extra layouts, asynchronous drain/error behavior. | Backend implementation and real-device tests; exports/silent output are insufficient. |
| SDK/service/JNI queues | Exact inventory above. | Prioritize actual APK dependencies and observed failures; verify descriptors, errors, ownership, callbacks, threading and Windows effects together. |
| Camera/Bluetooth/DRM/WebView and other hardware features | Mapped implementations and capability boundaries. | Capability-accurate responses and representative device/app evidence; no universal compatibility claim. |

These remain engineering tasks and are not replaced by successful no-op APIs
to make inventory totals appear complete.
