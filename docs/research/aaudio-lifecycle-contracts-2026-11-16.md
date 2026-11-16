# AAudio lifecycle and channel-layout audit

Base: `99344ef805b2503f14ba37c2db723e8a14d700dd`. Production scope:
`compat/media/ndk/aaudio.cc`. This follow-up does not duplicate open PR #51's
receiver leases, #44's capture allocations, or #50's CI work. The compatibility
oracle is the repository's `aosp.lock` tag, Android `android-16.0.0_r4`.

## Findings and changes

| Base-revision finding | Application-visible effect | Change |
| --- | --- | --- |
| Start/stop/pause could overwrite `CLOSING`; start ignored backend failure. | A released stream could appear active again, or a failed start report success. | Enforce Android state admissions, preserve state on failed start, retain `CLOSING` until close. |
| Release stopped the device without retiring its worker or releasing the backend. | A previously admitted callback could submit PCM or replace `CLOSING` with `STOPPED`. | Retire callback work, join the worker and release the device once, retaining query state until close. |
| Same-stream lifecycle calls from the data callback had no collision guard. | Callback-local close could join its own worker. | Reject callback-local lifecycle operations with Android's error precedence. |
| A callback completing after stop/restart could stop the new playback generation. | Old callback results and PCM affected the new start. | Retire admitted callback work with a generation counter. |
| Flush rejected `FLUSHED`; input flush checked state before support. | Repeated flush or unsupported input returned the wrong result. | Allow repeated output flush and check input support first. |
| Channel-mask setter saved only popcount; query always returned zero. | Positional identity was lost and some layouts used the wrong speaker order. | Preserve mask/setter order; report negotiated defaults; reject unrepresentable layouts. |
| API 36 `AAudioStream_getDeviceIds` was absent. | Apps resolving the public function could not query the device list. | Register the exact signature and scalar-equivalent virtual device list with Android capacity/null precedence. |

Primary lifecycle sources:
[`AudioStream.cpp`](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r4/media/libaaudio/src/core/AudioStream.cpp)
(`systemStart`, `systemPause`, `safeStop_l`, `safeFlush`, `safeRelease`,
`safeReleaseClose`) and
[`AAudioUtilities.cpp`](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r4/media/libaaudio/src/utility/AAudioUtilities.cpp)
(`AAudio_isFlushAllowed`). Public query and setter behavior comes from
[`AAudioAudio.cpp`](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r4/media/libaaudio/src/core/AAudioAudio.cpp).

### Lifetime and callback ordering

Release publishes `CLOSING`, sets quit, retires the callback generation and stops
the backend under the stream mutex. Joining happens outside that mutex so an
already admitted callback can return. The worker checks state and generation
after device waits and callback execution; retired work cannot write PCM or
apply its return value. A separate release mutex serializes repeated release.
Close shares the release path, preventing double release. Queries retain the
stopped backend object until close. Concurrent close and arbitrary use of a
deleted stream remain invalid.

Gesher joins its worker on release. Pinned legacy Android may still have a
callback in progress when release returns; joining is a stronger local
quiescence guarantee, not a claim about every Android backend. Input pause and
flush return `UNIMPLEMENTED` before checking state or callback collision, as the
pinned implementation does.

### Positional masks and Windows audio mapping

An explicit positive channel count produces an internal indexed layout whose
public mask query returns `AAUDIO_UNSPECIFIED`, including counts one and two.
An explicit positional mask survives open. Last count/mask setter wins; zero
restores default negotiation. Sources: `AAudioAudio.cpp`, the conversion helpers
in `AAudioUtilities.cpp`, and post-open conversion in
[`AudioStreamTrack.cpp`](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r4/media/libaaudio/src/legacy/AudioStreamTrack.cpp)
and
[`AudioStreamRecord.cpp`](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r4/media/libaaudio/src/legacy/AudioStreamRecord.cpp).

Existing Gesher defaults remain output stereo and input mono; their newly
reported masks are `0x3` and `0x1`. These are negotiated Gesher values, not a
claim that all Android devices choose those defaults.

`Track::MixInto` in `compat/media/audio/engine.cc` consumes FL FR FC LFE then
two left/right surround pairs, downmixing center/surrounds at approximately
-3 dB and omitting LFE. It carries no layout mask into the Windows sink.
`CaptureStream::Push` in `compat/media/audio/capture.cc` copies channels by index,
duplicates mono input and averages for mono capture. Acceptance follows those
actual operations rather than treating any popcount as a valid mapping.

| Direction | Supported explicit positional masks | Existing representation |
| --- | --- | --- |
| Output | MONO `0x1`, STEREO `0x3`, TRI `0x7`, 3POINT1 `0xf`, 5POINT1 `0x3f`, 5POINT1_SIDE `0x60f`, 7POINT1 `0x63f` | Mono/stereo or the FC/LFE/surround-pair order. This is stereo downmix, not physical surround output. |
| Input | MONO `0x1`, STEREO `0x3` | Existing mono/stereo capture conversion. |
| Both | Unspecified `0` | Direction-specific default, or indexed layout when supplied through a positive count. |

The following legal Android masks previously passed popcount validation within
the existing count limit. They now fail with `AAUDIO_ERROR_UNAVAILABLE` before
device creation. Every listed layout has a host assertion for the error and
absence of backend allocation.

| Direction | Newly unavailable masks | Missing backend mapping |
| --- | --- | --- |
| Output | 2POINT1 `0xb`, TRI_BACK `0x103` | Third sample would become center instead of LFE/back-center. |
| Output | QUAD `0x33`, QUAD_SIDE `0x603` | Third rear/side sample would become center; fourth would be omitted as LFE. |
| Output | SURROUND `0x107`, PENTA `0x37`, 6POINT1 `0x13f` | Back-center or an unpaired surround sample would be omitted or assigned incorrectly. |
| Output | 2POINT0POINT2 `0xc0003`, 2POINT1POINT2 `0xc000b`, 3POINT0POINT2 `0xc0007`, 3POINT1POINT2 `0xc000f` | Height channels would occupy center/LFE slots or a plain surround pair. |
| Output | 5POINT1POINT2 `0xc003f`, 5POINT1POINT4 `0x2d03f`, 7POINT1POINT2 `0xc063f`, 7POINT1POINT4 `0x2d63f` | Height channels would become a plain surround pair or be dropped beyond channel eight. |
| Input | FRONT_BACK `0x104`, 2POINT0POINT2 `0xc0003`, 2POINT1POINT2 `0xc000b`, 3POINT0POINT2 `0xc0007`, 3POINT1POINT2 `0xc000f`, 5POINT1 `0x3f` | Capture has no matching front/back, height or surround positional mapping. |

Unknown/wrong-direction masks return `ILLEGAL_ARGUMENT`. The unchanged
maximum-count check comes first, so layouts beyond twelve channels retain
`OUT_OF_RANGE`. Count-only multi-channel playback policy and backend remapping
are outside this change.

### Device array

`AAudioStream_getDeviceIds(AAudioStream*, int32_t*, int32_t*)` returns an
`aaudio_result_t`. Null count is rejected first. Insufficient capacity writes
the required count and returns `OUT_OF_RANGE` before validating the array.
Sufficient capacity with a null array returns `ILLEGAL_ARGUMENT` without
changing count. Success fills only returned entries and updates count. Tests
cover those outputs, spare entries, both directions and post-release queries.

The query reports the existing virtual output ID 2 or input ID 3, agreeing with
the scalar query. It does not imply additional physical routes or multi-device
playback. Oracle assertions use nonnegative capacities; negative capacity is
treated as insufficient locally and is outside those assertions.
The pinned implementation compares against an unsigned vector size, so negative
input can instead bypass its capacity error. That invalid-input behavior is not
part of this parity claim; callers must supply the actual nonnegative array size.

## Verification

The new runner compiles the complete production AAudio namespace and actual
export table unchanged, replacing platform includes and device objects with
deterministic observers. Gated callbacks control release/stop/restart ordering.
It is a host contract test, not Windows or ELF execution.

```sh
python3 tools/test_aaudio_lifecycle_contracts.py --sanitize
python3 tools/test_aaudio_lifecycle_contracts.py \
  --source-ref 99344ef805b2503f14ba37c2db723e8a14d700dd --sanitize
python3 tools/test_aaudio_builder_contracts.py
python3 tools/test_media_api_contracts.py --sanitize
```

Observed local results:

| Check | Result |
| --- | --- |
| Complete AAudio lifecycle/mask/device suite, ASan/UBSan | 199 checks, 0 failures |
| Same assertions against the base source, ASan/UBSan | 175 checks, 101 expected failures |
| Existing AAudio builder suite, UBSan | 103 checks, 0 failures |
| Existing media API suite, ASan/UBSan | AAudio 37, extractor 17, YUV crop 32, YUV rows 180 checks; all passed |
| Whitespace check | `git diff --check` passed |

Independent QA repeated those candidate and old-source runs, checked all six
primary-source fingerprints below, and compared the lifecycle admission tables
and channel conversions with the pinned source. It also inspected the actual
`Track::MixInto`, `CaptureStream::Push`, backend waits and release operations.
No blocking production defect was found within this scope.

The additional QA regression cases cover two simultaneous releases while a
callback is held, same-stream versus other-stream callback collision rules, and
a short backend write. The latter verifies that the public frame counter adds
only accepted PCM. With those test-only additions, the production suite passes
**221 checks, zero failures** under ASan/UBSan; the old-source control executes
197 checks and produced 106 expected failures. Existing builder and media API
suites also passed independently with the counts above. Concurrent destruction
and arbitrary use after close remain outside the valid lifetime contract.

Production-namespace SHA-256: candidate
`dfb4361db1742ab46cc2ec1898cc9fd28ce70ac2159f3b919663d06956a4e98d`;
base `f6186b22922d700a204439c58c4c77467741da1796051c217a4cc813b33ff510`.

The source-ref command is an expected-failing old-source control. It runs the
same assertions but skips callback-local close/release checks that would make
the old worker join itself. It does not use an intentional process abort.

`tests/native/aaudio_builder_contract_test.c` now uses typed `dlsym` for release
(API 30), masks (API 32), and device arrays (API 36), retaining its NDK r27c
API 29 target. Link `-laaudio -ldl`. The fixture requires runtime exports and
checks actual guest callbacks/lifecycle/queries. Additional success marker:
`AAudio lifecycle guest contracts: all passed`; the existing builder marker
remains. Existing Windows x86-64 and translated AArch64 Track 99 runs with
`GESHER_AUDIO=null` are the execution and guest ABI gates. No NDK is available
locally; those required hosted checks remain the guest compile/execution gate.

Local sanitizer runs use `ASAN_OPTIONS=detect_leaks=0` because this container's
ptrace environment prevents LeakSanitizer execution. Address/undefined-behavior
instrumentation remains active. CI need not inherit this local workaround.

## Primary-source fingerprints

SHA-256 of decoded Gitiles files at `android-16.0.0_r4`:

| Source under `frameworks/av` | SHA-256 |
| --- | --- |
| `media/libaaudio/src/core/AudioStream.cpp` | `22407fde63d844d491b3cb2440f309116a16d6b0c032d2e960268b29dea445e1` |
| `media/libaaudio/src/core/AAudioAudio.cpp` | `a983c00f5eb965671d578911e06082f3bab097dff848d34ded34ffa29ad391cb` |
| `media/libaaudio/src/utility/AAudioUtilities.cpp` | `38bb19bdbe13163523ed883d8b1fbefb79280384d377cf6e24843b5afeaa1029` |
| `media/libaaudio/include/aaudio/AAudio.h` | `3722ac5beadcf8f4fb5d32f3226b57fcb49f237f007dae6493bc38511281a1a7` |
| `media/libaaudio/src/legacy/AudioStreamTrack.cpp` | `51284e047a583027b54934ff5be7ff678f7035b40d92df210feed4b6687a0377` |
| `media/libaaudio/src/legacy/AudioStreamRecord.cpp` | `2b18af9c014981a0913bc7c5fa6810583adc41ff2f87fcb39a973a67ed6b5415` |

## Remaining scope

This does not establish complete AAudio parity. Physical routing, microphone
permissions, latency guarantees, MMAP, offload, spatialization, additional
layouts, error callbacks and asynchronous stop/drain completion remain outside
the claim. The immediate public `STOPPED` state can still precede backend drain
completion. The silent guest fixture tests exported calls and callback
ownership, not audible hardware fidelity.
