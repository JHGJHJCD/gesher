> Archived baseline mapping evidence. Findings describe pre-fix source at `e0dbabf8`. See [the integrated audit](../android-windows-compatibility-2026-11-14.md) for resolutions and current tests. Scratch paths identify investigation inputs, not published runtime evidence.

# Java framework and service compatibility audit

Date: 2026-11-14. Baseline: `e0dbabf8932df2c101d8ed3d28a8c560df771dc0` in `gesher-runtime/gesher`.

This is the read-only mapping/research handoff for the Java framework, service and application-host portion of the repository. No repository source was changed during this phase. The companion `map-services-files.json` enumerates every Java source in `app/java`, `app/compat-java` and `app/gen`, with SHA-256, line count, role and override count. Enumeration is evidence of source coverage, not a claim that every method has been semantically verified. Other agents own the native/runtime/graphics layers and fresh repository-wide descriptor inventories.

## 1. Findings to implement first

| ID | Finding | Evidence and effect | Proposed owner |
|---|---|---|---|
| J1 | Legacy MediaRouter and MediaRouter2 cannot construct | `IDisplayManager.getWifiDisplayStatus()` falls back to null; `media_router` is absent; MediaRouter2 additionally requires a nonempty system-route list and nonnull routing session | Java service implementer |
| J2 | TextServicesManager cannot construct despite an ordinary supported desktop text-input environment | `textservices` is absent while the pinned framework uses `getServiceOrThrow` | Java service implementer |
| J3 | `getTasks(maxNum=1)` reports a multi-activity task as one activity with the top activity as its base | Source-extracted exact production loop reproduced this deterministically | Java service implementer |
| J4 | `WebStorage.deleteOrigin(origin)` deletes all origins and additional storage types | Java delegates directly to global profile clear; Chromium143 clears only target-origin file systems and IndexedDB | Root/native-WebView implementation |

J1 and J2 must be tested through real public Android managers and remote Binder proxies as well as service methods. Registering a service whose default methods still return null does not resolve the public contract.

## 2. Reachability and architecture

The runtime uses the upstream Android framework compiled against compatibility types, adds Gesher Java framework/module substitutes, and delegates Android system-server interfaces to Java services. It is therefore incorrect to treat only classes in `app/java/android` as the Android API surface. Public framework callers can exist and link successfully but subsequently dereference an absent service or unsupported AIDL default result.

The startup chain is `Main` / `Launcher` / `Device` / `Zygote` / Android app-driver and app-process classes. `SystemServer` registers `Services.create(...)` results for ordinary Binder operation; `Services.installInProcess(...)` installs a ServiceManager cache for the single-process mode. `StandIn` dynamically delegates to a service extending an AIDL `Default` class and uses `app/gen/StubGen.java` generated concrete Binder stubs when available. If the generated stub is missing, it falls back to an attached local Binder (`StandIn.java:54–69,88–103`). That fallback is not proof of remote transaction support. New services need both registry entries and additions to the StubGen list in `app/build.sh:90–118`.

Default-return behavior matters. `StandIn` recognizes unimplemented methods and can enforce strict mode; it also supplies empty collections for certain collection return types. It does not synthesize every required object, callback, or successful state transition. Both a missing descriptor and an inherited AIDL default must be investigated at the public caller. An empty list is sometimes correct absence behavior, while a null object or success-shaped primitive can violate the contract.

`app/build.sh:128–137` deliberately excludes `app/java/com/google/**` from `gesher-app.jar`: these are client SDK fixture classes compiled into `tests/apps/gmsapp` rather than framework implementation. Apparent Google Tasks/Firebase defects there are not shipped boot-class-path compatibility defects. This corrected an early tentative finding and excludes that work from the recommended implementation.

### Source-family inventory

| Source family | Java files | Lines | Role |
|---|---:|---:|---|
| `app/java/android` | 89 | 20,908 | App driver; missing Android modules and public values/managers; network, Bluetooth, NFC, Wi-Fi, MediaStore and graphics compatibility |
| `app/java/com/android` | 2 | 261 | Internal framework compatibility classes |
| `app/java/com/gesher/server` | 87 | 33,646 | Service registry, lifecycle, packages, identity, providers, jobs, accounts, device services |
| `app/java/com/gesher/app` | 52 | 18,664 | App bootstrap, windows/input, shell, WebView2, accessibility, graphics/fonts, locale |
| `app/java/com/gesher/net` | 4 | 881 | Windows adapters, proxy and network-host bridge |
| `app/java/org` | 1 | 17 | Compatibility class |
| `app/java/com/google` | 46 | 2,782 | Excluded client SDK fixtures |
| `app/compat-java` | 27 | 696 | Framework compile support and service initializers; some joined into runtime according to build phases |
| `app/gen` | 2 | 434 | Stub and compatibility-change generation |

The separate `app/gesher-camera` companion contains two Java files, a manifest and localized resources; it is not part of the 310-file inventory above. The file inventory totals 281 `app/java` sources plus 27 compatibility sources and two generators.

## 3. Subsystem map and limits

This table records families read or traced in this audit, including relationships that prevent misclassifying current implementations as missing. Large subsystems were inspected at their public boundaries and lifecycle transitions; the strongest findings below include precise upstream call paths. Existing documentation is a navigation aid, not evidence that a path still works at this baseline.

| Subsystem | Main repository families | Current boundary / audit interpretation |
|---|---|---|
| Bootstrap and process execution | `Device`, `DeviceWork`, `RuntimeCommands`, `Zygote`, `SystemServer`, `Installer`, `Dexopt`; app `Main`, `Launcher`, `VmCommandLine`, `BuildProps`, `NativeLibs`; Android `GesherAppDriver`, `GesherAppProcess` | Native process and resource setup cross into ART; another audit owns native correctness. In-process and remote-service modes both require compatibility tests. |
| Activities/tasks/windows | `ActivityTaskManagerService`, `ActivityManagerService`, `ActivityClientController`, `ActivityCallerState`, `ActivityOptionsResolver`, `ActivityTheme`; window `ActivityClientHook`, `WindowSession`, `TaskConfiguration`, `ApplicationDisplay`, `NativeWindow`, `WindowMode`; Android activity-window/transaction helpers | Substantial task and transaction handling is present. J3 is an additive aggregation defect. Open PR25 owns other process-identity/generation behavior. `getAppTasks` explicitly returns an empty list, so a claim that it is currently null would be wrong. AppTask metadata remains incomplete. |
| Services and broadcasts | `ActiveServices`, `BroadcastController`, `StickyBroadcasts`, `PendingIntents`, `CallerIdentity` | Explicit service/broadcast/pending-intent lifetime and caller infrastructure exists. Existing lifecycle audit fixes must be retained; absence of an override alone is not a permission/lifecycle finding. |
| Packages, install and resources | `ApkPackage`, `PackageManagerService`, `PackageInstallerService`, `InstallSessionMetadata`, `InstallFileBridge`, `InstallPipeTransport`, `PackageRemovalStorage`, `PackageUids`, `PackageVisibility`, `SharedLibraryGraph`, `SharedLibraryRegistry`, `CompatChanges` | Manifest/package visibility, shared libraries, installer sessions and cleanup are distinct paths. Resource loading uses the upstream framework plus native resource integration; cannot be verified by Java signature inventory alone. |
| Permissions and app attribution | `PermissionCatalog`, `PermissionPolicy`, `PermissionRequests`, `CallerIdentity`, `UriGrants`, `UriGrantsManagerService` | Keep normal permission denial distinct from missing service. New media-router administrative APIs must not bypass package or user restrictions. No offensive/reproduction workflows were performed. |
| Content and settings | `ContentProviders`, `ContentService`, `SettingsProvider`, `SettingsStore`, `SyncManager`, Android `MediaStore` | App providers, observers, settings and sync scheduling exist. Need path- and app-lifecycle-aware tests for future changes; inherited collection defaults do not establish full provider compatibility. |
| Storage and data lifecycle | `Services.StorageService`, `BlobStoreService`, `RollbackManagerService`, `ApplicationRestrictionsService`, `PackageRemovalStorage` | One emulated shared view and private host volume; mounted/read-only/absent state, disk/volume enumeration and allocation handling are already implemented. Do not reintroduce older missing-storage findings as new. |
| Alarms/jobs/power | `AlarmManagerService`, `JobSchedulerService`, `PowerManagerService`, `DeviceIdleController`, `BatteryService`, `DeviceServices.ThermalService`, `DeviceWork` | Windows-backed battery and scheduling constraints exist with Android service lifetimes. A desktop without Android doze/hardware is an architectural difference; callbacks and cancellation still need contract fidelity. |
| Accounts and integration | `AccountManagerService`, `AccountSessions`, `AccountSecretStore`, `AccountChooser`, `GmsService`, `GmsPackageSupport`, `GmsSignatureSpoofing`, `MicrogAddons`, `BrowserPackageSupport` | Runtime GMS integration is distinct from excluded Google SDK fixtures. Authenticator/session implementations cannot be substituted by fake successful results. |
| Notifications and clipboard | `NotificationManagerService`, `NotificationStore`, `NotificationIcons`, `ClipboardService`, `ToastHost` | Host shell notifications and local channel/state management exist. Native presentation and platform capability limitations are separate from Binder/query correctness. |
| Audio and media | `AudioService`, `MediaSessionService`, app/native media hooks | Audio focus, volume and sessions are present, but `media_router` is wholly absent (J1). A desktop default audio route is a reasonable real capability; remote cast routes or speaker/Bluetooth hardware identity must not be fabricated. |
| Display/input/text | `Services.DisplayManager`, `WindowManagerService`, `StandInWindowSession`, `InputMethodManagerService`, `VirtualKeyboard`, `WindowInput`, `WindowsInputMethod`, `CursorAnchorGeometry`, `ApplicationDisplay` | Live display callbacks and host text input already exist. Missing Wi-Fi display status still breaks legacy media routing. No registered text-services manager (J2). |
| Location/camera/vibration | `LocationManagerService`, `WindowsLocation`, `CameraService`, `CameraApp`, `CameraCallbacks`, `CameraDeviceUser`, `CameraFrames`, `LegacyCamera`, `WindowsCamera`, `VibratorManagerService` | Windows location/camera integrations have dedicated classes. Honest absence on unsupported hardware is valid, but manager construction and failure callbacks must remain Android-compatible. |
| Desktop policy and usage | `UnmanagedDevicePolicyService`, `DesktopShortcutService`, `DesktopUsageStatsService`, `DeviceServices`, `LocaleManagerService`, restrictions/rollback services | Desktop-aware unavailable/policy implementations already replace many false defaults. DeviceServices contains multiple service families and must be assessed per interface, not as one generic stub. |
| Networking | Android `ConnectivityManager`, `Network`, `NetworkInfo`, `NetworkCapabilities`, `NetworkRequest`, `LinkProperties`, `LinkAddress`, `IpPrefix`, `RouteInfo`, `StaticIpConfiguration`, `ProxyInfo`, `TrafficStats`, `DnsResolver`; `ConnectivityService`, `WindowsNetworks`, `WindowsProxy` | Real host network snapshots/resolution and callbacks exist; missing module/value methods still need exact SDK descriptors. Raw DNS has restricted support; malformed-snapshot robustness is a separate lower-priority issue. |
| Wi-Fi/Bluetooth/NFC/NSD | Android package substitutes, including scan/advertising/filter/parcelable values and managers | Some values are needed even when radios are unavailable because libraries link and deserialize them. No radio is not permission to omit common descriptors or claim successful discovery. Full RF/transport support is architectural work, not a small API patch. |
| Module and extension compatibility | `app/compat-java` initializers; Android health, SDK sandbox, extension APIs, statistics classes | Definitions are often needed at framework initialization time even when the subsystem is unavailable. Build inclusion determines runtime reachability. Public SDK missing counts are starting points, not behavior proof. |
| WebView and web storage | `GesherWebView`, `GesherWebViewProvider`, `GesherWebViewFactory`, `GesherWebSettings`, `GesherCookieManager`, `GesherWebStorage`, message ports/payloads, JS bridge, geolocation, support lib, file URLs; `WebView2Host` plus `compat/webview` | Substantial Windows WebView2 bridge. J4 is a verified data-isolation/semantic defect. Callback timing and exact cookie and quota contracts deserve dedicated integration tests. |
| Accessibility/fonts/locale/shell | `AccessibilityService`, `UiaBridge`, `UiaClient`, `WindowsFonts`, `SystemFontMap`, `SystemLocale`, UI text/messages, `Shell`, `AppShell`, icon classes | Host accessibility, Windows fonts, locale propagation and shell mapping are independent Windows bridges. Correct Java shape does not establish native screen-reader/font/window behavior. |

## 4. J1 — media routing construction failure

### Verified call paths

Repository `Services.java:90–272` does not register `media_router`. Its `DisplayManager` implementation (`Services.java:460–499`) omits `getWifiDisplayStatus`, so the AIDL default object is null. `app/build.sh:90–118` does not generate an `IMediaRouterServiceStub`.

Pinned AOSP `MediaRouter.java:179` calls `updateWifiDisplayStatus(mDisplayService.getWifiDisplayStatus())` during construction/monitoring. `updateWifiDisplayStatus` (`:1484`) dereferences the status. This is the first null dereference. Fixing only that exposes the next one: `rebindAsUser` (`:446–469`) calls `mMediaRouterService.registerClientAsUser(...)` even though `ServiceManager.getService(Context.MEDIA_ROUTER_SERVICE)` returned null. This path matters to legacy and AndroidX media routing consumers even on a device with no wireless display.

Pinned `MediaRouter2.java:625–679` loads system routes immediately and explicitly rejects an absent/empty route list and a null system session. It cannot be repaired by merely registering a service that inherits `IMediaRouterService.Default`. Router registration subsequently requires `notifyRouterRegistered(routes, systemSession)` so the public controller receives initial state (`IMediaRouter2.aidl`; `MediaRouter2.java:2824` and router registration paths near `:4517`).

### Coherent desktop contract

1. `DisplayManager.getWifiDisplayStatus()` should return the normal `new WifiDisplayStatus()` unavailable value: feature unavailable, scanning off, no active display. The runtime has no Wi-Fi Display implementation; a null status is not the framework's unavailable representation.
2. Register a real `IMediaRouterService` generated Binder stub in both service modes. Expose the actual Windows default audio output as one abstract system route. A generic default-output description avoids incorrectly advertising fixed speaker/Bluetooth identity or remote-cast capability.
3. Keep route IDs, provider IDs and selected-route IDs internally consistent. Follow the pinned `SystemMediaRoute2Provider`: `ROUTE_ID_DEFAULT`, a system routing session, provider-qualified route/session IDs where required, and selected route matching the returned route. The public local router rewrites the system session's client package when necessary (`MediaRouter2.java:4178–4187`); follow that contract rather than exposing another app's package.
4. Supply legacy registration, discovery, selected-route and state behavior sufficient for the default route. Empty remote route state is an honest outcome. For MediaRouter2, register/unregister and initial state notification must work; unsupported session requests need the prescribed failure completion rather than silently leaving clients waiting.
5. Implement only supported volume control, ideally through existing AudioService stream state. Fixed volume is safer than claiming independently adjustable route volume that does nothing. Match pinned AOSP default-route semantics and distinguish route/session volume where applicable.
6. Handle dead client binders and idempotent unregister, validate supplied client/package/user identity, and dispatch client callbacks outside shared locks. Administrative manager/proxy methods require separate permission treatment; ordinary registration must not accidentally expose cross-package controls.

### Regression design

Instantiate both public MediaRouter and MediaRouter2 in the actual Android runtime. Verify construction, a nonempty and consistent default route/controller, callback registration/unregistration and supported volume behavior. Exercise remote Binder transport, because a plain local attached Binder can mask a missing StubGen entry. Assert Wi-Fi display unavailability and lack of fabricated remote routes. Include negative-control evidence that the original implementation fails at the expected null status, absent service and/or empty-session boundary. A model of the service class alone cannot demonstrate that the public manager constructor now works.

Primary references: [MediaRouter](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/media/java/android/media/MediaRouter.java), [MediaRouter2](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/media/java/android/media/MediaRouter2.java), [IMediaRouterService](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/media/java/android/media/IMediaRouterService.aidl), [IMediaRouter2 callbacks](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/media/java/android/media/IMediaRouter2.aidl), [system route provider](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/services/core/java/com/android/server/media/SystemMediaRoute2Provider.java), [WifiDisplayStatus](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/core/java/android/hardware/display/WifiDisplayStatus.java).

## 5. J2 — unavailable spell checking needs a present manager

`Services.java:90–272` has no `textservices` registry entry, and `app/build.sh:90–118` has no `ITextServicesManager` generator entry. Pinned `TextServicesManager.java:105` fetches the service with `ServiceManager.getServiceOrThrow(...)`; `SystemServiceRegistry.java:628–640` uses this constructor for normal `Context.getSystemService`. The normal manager cannot be fetched even though unavailable spell checking has a supported Android representation. The deprecated static accessor additionally wraps service-not-found in `IllegalStateException`.

The interface has six methods: current checker, current subtype, checker session request, finish session, enabled flag, enabled checker array. A desktop with no checker backend can implement a coherent disabled state without fabricating spell-check results:

- `isSpellCheckerEnabled(userId)` returns false for the supported user.
- Current checker and subtype return null.
- **The raw Binder `getEnabledSpellCheckers` returns null for no checkers**, matching pinned server `TextServicesManagerService.java:657–685`. The newer public manager's list accessor normalizes this to an empty list. Do not change the server shape simply because a collection heuristic says all arrays should be nonnull.
- `getSpellCheckerService` for an unavailable/unknown checker returns without a session, matching the server's early returns at `:569–590`. **Do not invent a null-session callback** as a universal missing-backend convention. The public manager checks disabled/current-checker state before making this request.
- `finishSpellCheckerService` is a safe no-op for absent sessions; user validation still applies.

Keep public parameter validation in the framework: modern `newSpellCheckerSession` validates executor/listener and required locale, then returns null when settings disable spell checking or no current checker exists (`TextServicesManager.java:216–245`). Registering this service should make manager lookup work and permit that ordinary unavailable result. It should not claim an installed spell checker or promise a session that never arrives.

Regression tests should use `Context.getSystemService(TextServicesManager.class)` and the string-name lookup, check both old and new checker accessors, request both session overload styles, check null/locale validation and user rejection, and exercise generated Binder dispatch. Test that no unavailable checker is enumerated and no fake callback result is created.

Primary references: [TextServicesManager](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/core/java/android/view/textservice/TextServicesManager.java), [ITextServicesManager](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/core/java/com/android/internal/textservice/ITextServicesManager.aidl), [TextServicesManagerService](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/services/core/java/com/android/server/textservices/TextServicesManagerService.java), [SystemServiceRegistry](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/core/java/android/app/SystemServiceRegistry.java).

## 6. J3 — task cap stops metadata aggregation

`ActivityTaskManagerService.java:766–802` scans activities in reverse order. The loop condition at `:771` is `i >= 0 && tasks.size() < maxNum`. On seeing the first record of a task it adds the task to the result and sets its top/base activity and activity count to that single record. Older records of the same task correct the base and increment the count only if the loop continues. Once the number of selected tasks reaches `maxNum`, it stops scanning even though selected tasks still need aggregation.

With a two-activity task `[base, top]`, asking for one task yields `baseActivity=top`, `topActivity=top`, `numActivities=1`. Asking for two tasks with the exact same records yields `baseActivity=base`, `topActivity=top`, `numActivities=2`. Result-size limits must not change metadata of a task that is returned in either case.

The scratch probe at `/tmp/gesher-task-map/TaskMapProbe.java` extracts the exact production method into minimal model types and was compiled/executed with Java 21. Output:

```text
Production maxNum=1 actual base=top, top=top, count=1
CONFIRMED wrong base/count when result cap reached
Same production records maxNum=2 base=base, top=top, count=2
```

This is deterministic algorithm evidence, not an Android runtime test. The implementation should continue scanning records for already-selected task IDs while limiting the admission of new IDs. Preserve reverse recency/top ordering, count only the intended live records, and keep base-intent extra stripping. A map for selected tasks makes the distinction explicit and avoids repeated scans of the result list. `displayId` is currently ignored; a foreign concrete display should not produce default-display tasks, while the framework's all-display sentinel must still work. Verify the exact sentinel before changing that branch.

Meaningful regression cases: `maxNum` 0/1/2; a three-activity task; interleaved records from two tasks; destroyed records; base/top correctness independent of limit; retained/removed intent extras; and valid/default/all/foreign display filters. Keep edits confined to this method and its tests to avoid overlap with open PR25's identity/generation changes.

Primary reference: [AOSP ActivityManager task API and task metadata](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/core/java/android/app/ActivityManager.java). Pinned AOSP is useful for the API contract; the concrete aggregation failure follows directly from the repository loop and executed model.

## 7. J4 — origin deletion clears unrelated origin data

`app/java/com/gesher/app/webview/GesherWebStorage.java:35–42` ignores the `origin` argument and calls `deleteAllData()`. That calls `WebView2Host.clearBrowsingData(DOM_DATA, null)` with a mask including DOM storage, file systems, IndexedDB, local storage, Web SQL and cache storage (`:15–17`). The native chain ends in WebView2 profile-wide `ICoreWebView2Profile2::ClearBrowsingData`. The operation is therefore broader than requested in both origin and storage type. Invalid, null or unknown origin input currently also falls through to global clearing.

The root agent verified the current Chromium143 implementation, `AwQuotaManagerBridge::DeleteOriginFramework`, in `/workspace/scratch/abaaec21b0b7/deps/aw_quota_manager_bridge_143.cc:295–307`: it uses `ClearDataForOrigin` and the precise mask `REMOVE_DATA_MASK_FILE_SYSTEMS | REMOVE_DATA_MASK_INDEXEDDB`. It does **not** clear local storage, cookies or cache storage. Pinned framework Java documentation still describes the historical Web SQL contract; this is a case where docs lag the current Android WebView implementation. The runtime source is the appropriate exact-behavior reference for this operation.

Recommended Windows mapping: origin-scoped DevTools storage clearing with the same narrow file-systems/IndexedDB mask, routed through the existing profile/global WebView infrastructure. Keep unrelated origins and unrelated storage types untouched. Existing asynchronous native work/completion leases must outlive the operation; merely submitting a command and closing its temporary host is insufficient. Root owns this implementation and the Chromium/CDP reference validation.

Regression must seed two distinct origins in a real private WebView2 profile, clear one, and assert that only its supported quota storage disappears. Assert other-origin IndexedDB/file-system state survives and same-origin local storage/cookies remain. Check invalid/unknown-origin behavior does not trigger global clearing. Separately exercise explicit `deleteAllData`. A Java mock that only observes the passed string or a host model does not prove real profile isolation.

Primary sources: [framework WebStorage](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/core/java/android/webkit/WebStorage.java); Chromium143 `android_webview/browser/aw_quota_manager_bridge.cc`, pinned/downloaded by the root agent in the dependency path above. Root should retain the exact Chromium revision and CDP source links in the final change documentation.

## 8. Additional unresolved findings and research queue

These are separate from the implementation shortlist; they must not disappear into a claim that all Android functionality is now supported.

| Area | Evidence | Classification / next step |
|---|---|---|
| AppTask thumbnail query | ATMS has no `getAppTaskThumbnailSize` override. Pinned `ActivityManager.java:3026–3039` obtains a `Point` and immediately uses `x/y`; inherited null therefore reaches an ordinary public API null dereference. `addAppTask` is also inherited and returns success-shaped integer zero without creating a task. | Strong follow-up construction/query defect. Design thumbnail metadata and unsupported/add-task behavior coherently; a random nonnull Point alone is incomplete. `getAppTasks` itself already returns an empty list and must not be reported as absent. |
| ActivityManager memory queries | No service override of `getMemoryInfo` or `getProcessMemoryInfo`; AOSP wrapper delegates at `ActivityManager.java:3718–3730,5038–5040`. Out-parameter defaults/nullable array are not measured host state. | Missing service behavior. Open PR26 implements separate Debug native queries, so coordinate instead of duplicating or assuming it covers Binder APIs. Use host counters, documented units and caller restrictions. |
| Raw DNS coverage | `android/net/DnsResolver` handles a limited set of record/query forms (not a full DNS packet resolver). | Architectural scope plus missing edge contracts; compare record types, malformed packet handling, cancellation and callback completion to exact AOSP API before implementing. Do not equate successful normal hostname resolution with complete raw DNS compatibility. |
| Host network snapshot parser | `WindowsNetworks.java:227–231` appends an address before parsing/appending its prefix length. Malformed prefix input can leave address/prefix arrays different lengths. | Verified local robustness issue; no demonstrated native-produced malformed snapshot. Parse the whole record before committing if selected later, and test the actual downstream consumer. Lower priority than reproducible public-manager defects. |
| WebStorage query values/timing | `getOrigins` always returns an empty map and quota/usage always zero, with inline callbacks (`GesherWebStorage.java:19–32`). | Explicitly incomplete data reporting. Compare modern Chromium behavior and UI-thread callback requirements. A storage clear fix alone does not implement quota reporting. |
| CookieManager semantics | Path/default-path, accept flags, third-party cookie policy and SameSite behavior cross Java and native cookie mapping. | Follow-up research, not a claimed verified new bug in this pass. Need real page/profile tests and exact WebView contract before changing behavior. |
| Excluded Google Tasks fixtures | Aggregation helpers can treat cancellation incorrectly or strand aggregation after retrieving a canceled result. | Test-fixture quality only: `app/build.sh` excludes all these classes. Do not spend shipped runtime compatibility budget here or advertise them as boot-class-path crash fixes. |
| Missing module descriptors | Wi-Fi/Bluetooth/NFC/NSD/network values and module initializers have varying depth of substitutes. | Fresh SDK and built-JAR descriptor inventory needed. Signature absence can be proven statically; functional parity and host translation cannot. Root is generating the fresh inventory. |

Prior reports under `docs/research` include `android-compatibility-deep-audit-2026-11-13`, `android-compatibility-followup-2026-11-13`, `android-system-contracts-2026-11-13`, and `android-lifecycle-contracts-2026-11-13`. Their missing-type/method/default counters are historical snapshots. Do not reuse those numbers as this baseline's current count or infer that every inherited method is incorrect. Some inherited returns are valid absence; others require stable objects, rejection or callbacks. The caller and backend contract decide.

## 9. Implementation and QA handoff

Implement J1/J2/J3 as distinct coherent commits with public-boundary regression coverage; root implements J4 and owns integration/CI changes. Keep generated Binder registration in the same commit as its service to avoid an in-process-only fix. Do not extend the scope to remote cast devices, an invented checker backend, RF hardware, complete AppTask support or full DNS merely to inflate coverage. Preserve tested unavailability behavior when host functionality does not exist.

Use the verified JDK/framework/coremod inputs under `/workspace/scratch/abaaec21b0b7/deps`, and test against the pinned framework rather than a guessed Android SDK release. For regressions, demonstrate the previous source fails the same meaningful assertion where feasible. Independent QA should check that the public constructor and callback/session lifecycle is complete, verify Binder transport, and review data-type/permission/unavailability semantics against the primary sources. Windows device/window/WebView execution is necessary for host behavior; Linux Java compilation is valuable but not a substitute.

The audit establishes four high-value additive fixes plus a concrete follow-up queue. It cannot justify a promise that every existing or future Android application, every rare API combination, or every hardware-specific feature works. A green CI run proves the tests that were executed; the final report should state their actual coverage and preserve known unsupported areas.
