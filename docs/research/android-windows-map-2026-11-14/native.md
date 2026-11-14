> Archived baseline mapping evidence. Findings describe pre-fix source at `e0dbabf8`. See [the integrated audit](../android-windows-compatibility-2026-11-14.md) for resolutions and current tests. Scratch paths identify investigation inputs, not published runtime evidence.

# Native Android / Windows compatibility map and audit

Baseline: `e0dbabf8932df2c101d8ed3d28a8c560df771dc0` (main), inspected 2026-11-14.
Read-only source audit. No GitHub mutation or source edit. The only executed new
probe is the small actual-source ELF lookup contract described below. No native
Windows, GPU, camera, physical audio, or arbitrary application execution is
claimed. Open PR24 reserved-name/exit diagnostic changes, PR25 process identity
and PR26 Debug memory accounting were excluded from proposed fixes.

## Coverage map

The families below cover the native runtime entrypoints and their Windows
backends. Review followed registration/build order, object ownership, error and
state contracts, test gates and prior accepted reports. It is not a claim that
every branch in every decoder or every upstream AOSP line was executed.

| Family / owned files | Implementation and boundary | Existing proof and material gaps |
| --- | --- | --- |
| `compat/framework` (22) | android_runtime installs boot JNI tables; real AOSP resources, Parcel/Binder, messages, properties, file observers, SQL, text; owned process/debug/software-memory/socket shims | Critical/native ABI thunk and real-framework fixtures exist. Process scheduling/groups/name lookups still defaults; numerous omitted platform JNI classes and generated device defaults remain. PR26 owns memory accounting. |
| `compat/libcore` (4) and `cmake/aosp/libcore_native.cmake` | Builtin ICU, javacore, OpenJDK libraries; EPoll/WatchService symbols and Windows process launcher | NativeBN is actually built: unported.cc is conditional, not evidence that BigInteger is absent. Runtime file/process behavior depends on POSIX; pending PR24 diagnostic fix excluded. |
| `compat/elf` (21) | ELF64 x64/ARM64 mapping, RELA/RELR/APS2, hashes, IFUNC, TLS, executable launch and shell | Startup, TLS, filesystem and ABI fixtures exist. Transitive handle lookup is demonstrably incomplete. Failed loading remains in shared registry. Close/unload, namespace/flags/versioning and loader concurrency are partial. |
| `compat/arm64` (15) | Dynarmic frontend, host callbacks/thunks, guest libc/va_list, JNI entry bridges, FPCR/FPSR and monotonic guest counter | Substantial x64-hosted ARM tests. Does not qualify native Windows ARM64, all instruction extensions or arbitrary ABI aggregates. Explicit unsupported-instruction failure remains; host-entry code/translated executable/TLS caches remain long lived. |
| `compat/hwui` (41), `compat/opengl` (5), `compat/angle` (2) | AOSP HWUI/Skia + ANGLE/D3D11; software ANativeWindow and texture/view surfaces; font and locale integration | Actual view/GLES/frame gates exist. SurfaceTexture has no external GL texture, only raster latest-frame composition. HardwareBuffer supports snapshots, not general allocation/import/Parcel. One-buffer queue and transform/fence/dataspace semantics remain partial. |
| `compat/input` (32) | Win32 pointer/key translation, Android input transport, TSF/IME text store, keyboard focus and editor synchronization | Current source already has left/right modifier and Num/Scroll/meta translation, so older reports calling all modifiers left-only are stale. Physical multitouch/stylus/gamepad/hotplug and cross-process channels are not broadly qualified. |
| `compat/media` (87) | WASAPI mixer/capture, AudioTrack/Record, AAudio/OpenSL, MediaPlayer, extractors, decoders, MediaCodec, muxers/recorders, SurfaceTexture bridge | Strong recent codec/audio/source tests. AudioEffect/Visualizer and several MediaSync/DRM/LinearBlock/profile classes remain generic zero stand-ins. Retriever/extractor callback sources are not wired; several receiver lifetime and no-exception allocation boundaries remain. |
| `compat/devices` (17), `compat/camera` (3) | Windows Media Foundation camera, camera metadata, ImageReader, Windows sensor/location bridge | Synthetic camera/sensor tests do not qualify real physical formats/orientation/latency. Flush ordering remains a known work item. ImageWriter/detach, GraphicBuffer import, direct sensor channels are explicitly unsupported. Native NDK Camera2 is separate from these Java implementations. |
| `compat/webview` (9), accessibility/locale/devsvc integration | WebView2 bridge, resource/message callbacks, COM ownership, Windows host integrations | Recent message-port/cleartext contract fixes exist. WebView2 is not the Android Chromium implementation; advanced transformations, capture/documents/JS transfer semantics still differ. |

A successful registration or export lookup is not a behavioral proof. Earlier
API36 export counts in `systematic-java-native-api-2026-11-05.md` are historical
runtime results, not freshly reproduced counts at this baseline. The substantial
reported missing NDK families (Binder NDK, Camera2 NDK, public NDK ICU, Vulkan,
mediandk and libc/libm subsets) remain prioritization inputs, not a number of
newly demonstrated crashes in this pass.

## Verified and source-established findings

### N1 — handle dlsym does not traverse transitive dependencies (high, bounded)

`compat/elf/elf_loader.cc:1512-1524` tests the object and its immediate
`dependencies`, then returns null. It never reaches a grandchild. Bionic's pinned
`linker.cpp` uses breadth-first `walk_dependencies_tree` through the complete
reachable graph for ordinary handle lookup. The shim's fallback to its own
platform symbols cannot supply an application-defined grandchild symbol.

A scratch harness compiles the **verbatim production Lookup function**, with
declared symbol-table/hash stand-ins and an ordinary graph root -> A -> grandchild,
root -> B. Results: root PASS, direct PASS, transitive **FAIL**, breadth-first
priority PASS, missing with cycle terminates PASS. Exit 1. This establishes a
lookup contract defect without claiming a full Windows loader run.
Files: `native-lookup-map-probe.cc` and executable beside this report.

Proposed fix: breadth-first recursive traversal, cycle/diamond de-duplication,
retain root and direct-dependency precedence, test missing and alternate branches.
Use real multi-library ELF fixtures for both native and translated guest paths in
addition to the portable selection test. Keep namespace/versioning/unload scope
explicit. Root may own this disjoint fix.

### N2 — failed ELF loading remains discoverable as a successful object (high, design required)

`LoadCommon` publishes the object in `Loaded()` at line 1341 before
`ResolveNeeded`, `Relocate` or `ProtectSegments` return. Failures at 1349/1352/1366
return null without marking/retiring the object. `FindLocked` at 399 checks only
name/soname and returns such an object. `Load`/`LoadFromMemory` then return it
immediately on a later call at 1496/1507, without relocation or constructors.
This is a source-established retry-state defect; a full live Windows retry
fixture was not executed.

Fix requires a loading/ready/failed state with reentrant dependency-cycle and
concurrent caller rules. A failed object/TLS/code range may need to remain
allocated while being absent from public lookup. Do not simply free it while
registered TLS, rewrite/translation ranges or partially loaded dependencies
retain pointers. Test a well-formed module with unavailable dependency, retry
after dependency installation, and repeated ordinary relocation failure.

### N3 — AudioEffect and Visualizer report successful setup without an engine (high, bounded)

`compat/media/jni/media_jni.cc:50-55` comments that setup fails, but registers
only `RegisterStandins`. `jni_util.cc:24-25,103-175` assigns zero to every integer
native. `native_setup` therefore returns `SUCCESS`.

Pinned AudioEffect.java:540-565 then accepts ID zero and **null Descriptor** and
sets STATE_INITIALIZED. A caller using `getDescriptor().type` has no descriptor;
parameter operations falsely report success. Pinned Visualizer.java:238-255 also
sets STATE_INITIALIZED, while static `getCaptureSizeRange()` returns null instead
of a range and enable operations succeed without capture. This is not a device
without an effect; it is fabricated successful state.

Bounded fix: replace these blanket setup/availability answers with typed
unavailable-backend implementations. Enumerate an empty typed Descriptor array
when no effect factory exists, fail setup with Android's documented unsupported
code, and preserve query signatures and cleanup. Visualizer static capture bounds
are API metadata and must use its upstream constants if exposed. Do not invent
PCM analysis/equalizer support. Test real Java constructor failure, queries,
null-safe capability probing, and cleanup after failed constructors. Source
registration tests alone cannot prove correct constructor behavior.

### N4 — MediaMetadataRetriever publishes inconsistent metadata/source and stale artwork (medium/high, bounded)

`compat/media/jni/media_jni_others.cc:49-97` sets `r->source` before
`Extractor::Open` succeeds, clears metadata only afterward, and does not clear
`album_art` before `Format::GetBuffer`. The latter leaves its output unchanged
when a key is absent. A second source without artwork can return the first
source's artwork; failed setup can leave new source bytes with old metadata.

Use a fresh temporary snapshot (`source`, metadata, art), publish only after
successful extraction and replace it as one coherent state. Validate arithmetic
used for inferred bitrate (`size * 8 * 1000000`) against overflow. Test source A
with artwork -> B without artwork, failed B and later valid C. Check pinned
retriever behavior for failure-state expectations rather than blindly retaining
all old state.

### N5 — Retriever and Extractor callback-source overloads are false-success stand-ins (high, coherent extension)

Both boot Java APIs declare `MediaDataSource` overloads, but
`media_jni_others.cc:kRetrieverMethods` has only path/FD `_setDataSource` entries,
and `media_extractor_jni.cc:kMethods` has only path/FD `setDataSource` entries.
`RegisterStandins` fills their missing void-native methods with no-op success.
Calling callback-based setDataSource therefore does not even read the supplied
bytes. Later retriever calls throw 'No retriever available' / extractor calls
throw 'no data source', despite successful setup. This is directly observable
from source registration and state, not an unregistered-method speculation.

MediaPlayer has a `JavaDataSource` adapter in `media_player_jni.cc:82-129` which
can be extracted into a reusable owned component, **after** validating JNI
allocation/errors, callback return counts and serializing the shared buffer.
Do not duplicate the unchecked existing adapter into two more entrypoints.
Tests: actual in-memory WAV callback on all three APIs, EOF/IOException, size
unknown, callback close exactly once, release/replacement, failed setup retry,
and source ownership following the pinned per-API native implementation.

### N6 — Retriever/Extractor/Recorder/SoundPool native receiver lifetime is not retained across entrypoints (high, cross-cutting)

Examples: `GetRetriever`/`MMR_release` use an unguarded raw pointer at 35/271;
`ME_release` deletes the raw context; Recorder and SoundPool also load raw Java
context pointers. Their internal mutexes only protect an object that already
exists; they do not protect acquisition against deletion. In contrast, current
MediaPlayer and MediaCodec have specific shared-receiver ownership patterns.

Pinned retriever JNI protects each operation/release with `sLock` and retains an
`sp`; pinned extractor JNI gets an `sp`. Correcting the remaining types needs
entrypoint lifetime retention and state synchronization, not just replacing
`delete` with shared_ptr in a raw holder. The recently completed Codec tests do
not cover these other types. Validate delayed operation versus release and
idempotent release with deterministic barriers, then real Windows JNI tests.

### N7 — AAudio validates too little and reports requested rather than actual state (medium/high, bounded follow-up)

`compat/media/ndk/aaudio.cc:287-312` treats every direction other than INPUT as
OUTPUT, negative rates/channels/capacity/callback sizes as default, and multiplies
`s->burst * 4` / `* 8` in signed int. `GetPerformanceMode` at 488 returns the
requested value, despite an ordinary shared mixer. `GetTimestamp` at 507 ignores
clockid entirely, so unsupported clocks report successful monotonic timestamps.
`SetBufferSizeInFrames` clamps negative values instead of rejecting them.

The pinned AAudio builder validates parameters before opening a device; Android
public APIs define the available clock IDs and actual negotiated values. Fix as
one validation/negotiation contract, with fixed-width arithmetic and consistent
failure output. Existing host AAudio harness provides a useful seam, but actual
output/input backends need CI checks. Self-close from a callback is not proposed
as a bug without checking the public callback restrictions.

### N8 — SurfaceTexture / HardwareBuffer / native window contracts remain materially partial (architecture)

`compat/media/surface/surface_jni.cc:190-220` explicitly says there is no GL
external texture: updateTexImage only latches a timestamp, releaseTexImage does
nothing, attach/detach return success, transform is constant, dataspace zero.
Thus an Android app sampling GL_TEXTURE_EXTERNAL_OES can receive no producer
image even while TextureView's raster composition works. General HardwareBuffer
allocation/GraphicBuffer import/Parcel are rejected in reader_surface.cc:38-52.
Native window surfaces accept/drop several producer contracts and expose one
CPU buffer. These require an actual shared GL texture/buffer/fence design and
rendered pixel oracles, not more no-op symbols. Existing raw-window geometry
repairs and ARM export presence do not close the gap.

### N9 — process JNI remains semantically incomplete (medium, coordinate POSIX owner)

`compat/framework/android_runtime.cc:191-216` has no-op priorities/groups/argv0,
constant thread priority and -1 for UID/GID name lookup. Even numeric ID names
that Android resolves are not handled. Coordinate with the POSIX agent before
mapping these onto working scheduling/account-name helpers. PR26 changes this
same file, so isolate any additions carefully. No broad claim about Android UID
security can follow from a name-lookup patch.

## Recommended implementation set

1. **Root/disjoint ELF**: N1 graph lookup with a portable actual-source harness
   and real x64/translated-ARM library graph fixtures. Keep N2 state-machine
   repair separately reviewed; it is more invasive than lookup.
2. **Native media unit**: N3 truthful unavailable AudioEffect/Visualizer JNI
   behavior, replacing false-success constructors and invalid query outputs.
3. **Source/ownership media unit**: N4+N5 retriever/extractor callback-source
   support using one checked reusable callback DataSource; include N6 lifetime
   repair for the two touched types. Follow pinned setup/release behavior and
   keep existing player behavior under regression.
4. N7 AAudio validation can be a separate bounded commit if schedule permits.
   It must not claim low-latency/exclusive/device-routing support that the engine
   does not supply.
5. Keep N8 and broad missing JNI/NDK families as explicit retained map items,
   not silently resolved by registration stubs.

## Primary reference sources consulted

Downloaded UTF-8 source bytes into `native-aosp-reference` from the exact
`android-16.0.0_r4` tag; they agree with the project target. No source was copied
into production during this mapping phase.

- Bionic linker, especially breadth-first dependency lookup and failed-load cleanup:
  https://android.googlesource.com/platform/bionic/+/android-16.0.0_r4/linker/linker.cpp
- AudioEffect Java constructor/state/query contract:
  https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/java/android/media/audiofx/AudioEffect.java
- Visualizer Java constructor and static capture metadata:
  https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/java/android/media/audiofx/Visualizer.java
- AudioEffect native errors/queries:
  https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/audioeffect/android_media_AudioEffect.cpp
- Visualizer native setup, capture-size/rate metadata:
  https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/audioeffect/android_media_Visualizer.cpp
- MediaMetadataRetriever native locking/lifetime/source overloads:
  https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/android_media_MediaMetadataRetriever.cpp
- MediaMetadataRetriever public contract:
  https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/java/android/media/MediaMetadataRetriever.java
- MediaExtractor native data-source/receiver contracts:
  https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/android_media_MediaExtractor.cpp
- AAudio builder validation:
  https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r4/media/libaaudio/src/core/AudioStreamBuilder.cpp

## Retained evidence limits

The ELF baseline probe is a selection-contract witness with mocked symbol
lookup, not a full loader test. The other newly reported defects are based on
verified production and pinned upstream control flow; their application-level
regressions should be implemented/run before claiming a fixed runtime. Old
repo audit counts and hardware success claims have not been silently relabeled
as current fresh results. Large missing-library families, kernel-specific
facilities and Windows device semantics remain open even after a green PR.
