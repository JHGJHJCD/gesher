# Native media compatibility corrections (palmoni5)

Baseline: `e0dbabf8932df2c101d8ed3d28a8c560df771dc0`.
The reference contracts below use AOSP `android-16.0.0_r4`.

## Audio effects without a native engine

The WASAPI mixer has no Android effect factory. Previously reflective native
stand-ins returned success from AudioEffect and Visualizer setup. Real framework
constructors accepted that answer, even though no effect ID/descriptor or capture
engine had been created. Setup now returns `ERROR_INVALID_OPERATION` (-5), which
those constructors translate into `UnsupportedOperationException`. Cleanup is
idempotent; operations on an absent native engine answer `ERROR_NO_INIT`, false,
or the corresponding native getter sentinel.

`AudioEffect.queryEffects()` returns an empty **Descriptor[]**, preserving the
Java cast and making availability checks safe. No default preprocessing is
attached to a session, so `queryPreProcessings()` returns null, matching its AOSP
native no-effects branch. Visualizer's static capture bounds (128..1024) and
maximum rate (20000 millihertz) describe the API; they do not advertise an engine.

Sources:

- [AudioEffect.java](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/java/android/media/audiofx/AudioEffect.java)
- [Visualizer.java](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/java/android/media/audiofx/Visualizer.java)
- [AudioEffect JNI](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/audioeffect/android_media_AudioEffect.cpp)
- [Visualizer JNI](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/audioeffect/android_media_Visualizer.cpp)
- [Visualizer constants](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/audioeffect/Visualizer.h)
- [Capture-size constants](https://android.googlesource.com/platform/system/media/+/android-16.0.0_r4/audio/include/system/audio_effects/effect_visualizer.h)

### Validation and integration

Run `tools/test_audio_effect_contracts.py --framework-classes <framework-classes.zip>`
with a JDK (`JAVA_HOME`). It compiles the **verbatim production JNI functions and
tables**, then runs the unmodified built framework AudioEffect and Visualizer classes with `-Xcheck:jni`. The declared host replacements are only
attribution/Parcel and logging, because Android Binder is unavailable in the host
JVM. JNI registration is deferred to each class's native initializer to avoid the
host JVM's recursive library-initialization restriction.

The suite checks typed enumeration, false availability, static metadata, actual
public constructor errors, and finalizers after failed construction (24 checks).
`--negative-control` restores the old zero-success setup answer in a disposable
build: the unchanged constructor assertions must fail. No source is rewritten.

`gesher.services.AudioEffectAvailabilityTest` is a separate public API fixture
for the real framework/ART/Windows runtime, built by the normal dex-services
build. A host pass does not establish Windows effect processing, which remains
unsupported, or qualify every Android audio effect implementation.

## Callback media sources and receiver ownership

MediaPlayer, MediaExtractor and MediaMetadataRetriever now share the same
`JavaDataSource` adapter. The latter two now register their actual MediaDataSource
native overloads; their former void-success stand-ins never consumed callbacks.
The adapter checks Java allocations/method lookups, bounds each callback to a
64 KiB buffer, serializes callbacks sharing that buffer, converts Java EOF to
native EOF, checks callback return counts and offset arithmetic, and latches
callback errors. Size (including unknown size) is cached as in AOSP. The native
owner closes the accepted callback once when its last source owner disappears.
An exception from `close()` cannot replace a pending entrypoint exception.
An ordinary `close()` exception remains pending and propagates from public
retriever release, as its Java contract requires. Failed setup consumes cleanup
exceptions before publishing the appropriate setup error.

Retriever and Extractor Java context fields now own intrusive references. Each
entrypoint retains a reference under the class context lock before acquiring the
receiver's operation lock. Release detaches the field under the context lock and
drops ownership outside it. A parser already inside a callback may finish, and a
callback may release its own receiver, without destroying that active receiver
or source. Independent receiver operations are serialized; an admission guard rejects
callback reentry into receiver operations with IllegalStateException, before
selected readers or a snapshot can be touched. Release remains allowed. New receiver allocation
uses `new (std::nothrow)` and reports OutOfMemoryError. This does not claim that
all pre-existing STL allocations in media codecs have recoverable OOM handling.

Retriever builds a fresh source/metadata/artwork snapshot. Successful replacement
cannot carry artwork from a previous source. Failed replacement preserves the
accepted snapshot and its source: `MetadataRetrieverClient` constructs a fresh
Stagefright candidate and publishes it only after successful setup. Stagefright's
internal clearing does not discard the previously published client retriever.
The previous snapshot is retired into a local before the new snapshot
is installed; its Java close runs only after complete publication under the
admission guard. Inferred bitrate uses Stagefright's floating-point average,
omitting only an out-of-range final result. Extractor retains its one-successful-source contract, permits retry
after an initial failure, and closes a rejected callback candidate while keeping
the original selected source.

Pinned references:

- [MediaDataSource native callback adapter](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/android_media_MediaDataSource.cpp)
- [MediaExtractor callback overload and error cleanup](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/android_media_MediaExtractor.cpp)
- [Retriever callback overload and retained receivers](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/android_media_MediaMetadataRetriever.cpp)
- [Retriever client success-only publication](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r4/media/libmediaplayerservice/MetadataRetrieverClient.cpp)
- [Public release and close exception contract](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/java/android/media/MediaMetadataRetriever.java)
- [Stagefright metadata replacement and clearMetadata](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r4/media/libmediaplayerservice/StagefrightMetadataRetriever.cpp)

### Callback validation and integration

`JAVA_HOME=<jdk> python3 tools/test_media_callback_contracts.py` runs 66 checks
against the production callback header, receiver lease header and verbatim JNI
entrypoints using a real JVM with `-Xcheck:jni`, undefined-behavior sanitization,
and C++ exceptions disabled. The parser boundary is a deterministic observer;
it supplies a source with artwork, a source without artwork and an unreadable
source. It is not an AOSP parser emulation or a Windows runtime qualification.
Tests include partial reads through multiple chunks, EOF, size caching, negative
and overflowing offsets, invalid callback counts, IOException handling, a
throwing close with and without an earlier exception, concurrent readers, snapshot
replacement, retention after failed setup, retry, representable large-source
bitrate, and release while a callback is blocked on an
explicit barrier. Both receivers keep the source alive until the operation ends.

The unchanged assertions fail under `--negative-control stale-art` (reuse the
previous artwork) and `--negative-control unavailable-callback` (restore the old
extractor callback no-op). `--negative-control reentrant-admission` removes the admission check in a
disposable header and must fail the same-track selection assertion (a safe
no-op without the guard), before testing any destructive mutation. Additional
real-JNI checks reject callback select/unselect/seek/repeated-source operations
and close-time metadata access while allowing close-time release.

Negative controls operate in disposable generated
files and do not edit production source.

Independent PR review corrected the failed-replacement expectation after checking
the enclosing `MetadataRetrieverClient` publication logic. Restoring the original
PR retriever source fails the prior-artwork retention assertion; restoring its
callback header fails ordinary-close/release exception assertions; restoring its
extractor cleanup produces checked-JNI pending-exception failures. These controls
use the corrected assertions unchanged, in disposable source copies.

The existing `tools/test_media_api_contracts.py --sanitize` setup assertions are
preserved. Only its source-section boundary and setup-only ownership seam were
adapted to the retained receiver type. Its AAudio I/O, extractor setup and YUV
fixtures still pass all 266 existing checks. In a ptrace-managed environment,
`ASAN_OPTIONS=detect_leaks=0` is needed because LeakSanitizer cannot inspect threads;
ASan/UBSan remain enabled. No leak-sanitizer success is claimed in that environment.

`gesher.services.MediaCallbackContractTest` compiles against the real built
framework and runs public callback WAV sources through MediaPlayer, Retriever
and Extractor on ART/Windows. It checks actual PCM bytes, duration/sample-rate,
known/unknown size, EOF, source replacement/rejection, callback close counts,
released-object errors, and concurrent/reentrant release. Add this class to the
mandatory Windows dex-services smoke gate, with success marker
`MediaCallbackContractTest all passed`. The deterministic host artwork assertion
is distinct from this real-container test: the public WAV source has no artwork.

Remaining scope: Recorder and SoundPool receiver retention, other media
stand-ins (DRM, MediaSync, LinearBlock, etc.), unimplemented retriever image/frame
variants, arbitrary callback cancellation, real devices and unsupported codecs
are not qualified by these changes. Existing decoder/graphics methods are not
replaced by fabricated successful backends.

## AAudio builder validation and actual stream values

AAudio now validates stored direction, sharing, PCM format, sample rate, channel
count, capacity, callback frame count, usage/content/input-preset and performance
values before requesting an audio backend. The pinned Android preflight permits
unspecified values, 1..12 channels (`FCC_LIMIT`), 8000..1600000 Hz and up to
1048576 frames per callback. Invalid values return the corresponding Android
error, with a null output stream. An unavailable device or offloaded hardware
path returns unavailable. The capture engine's narrower supported rate/channel
range is reported as a failure before allocation. Null builder/output and
builder/stream allocation failures also have explicit results.

Capacity calculations use wide integers and check the complete frame size. Rings
above **64 MiB per stream** fail with `AAUDIO_ERROR_NO_MEMORY`; this is an explicit
host resource ceiling for the no-exceptions backend, not an Android hardware
capability. It prevents multi-gigabyte requests from reaching vector allocation.
Smaller allocations can still fail under system memory pressure; this change does
not make every pre-existing backend/STL allocation recoverable.

A successfully opened stream reports the actual shared mixer and performance
NONE, plus the capacity returned by its Track/CaptureStream. The requested
callback block size is separate from the actual hardware/mixer burst. Callback
loops deliver the negotiated callback size, including a resolved default size.
An allocated output session ID reaches the Track. Negative buffer-size changes
are rejected without changing the old size.

Timestamp requests use guest Linux clock IDs, validate the domain, and publish
both outputs only after a successful backend timestamp. Unsupported clocks leave
the caller's outputs unchanged. **CLOCK_BOOTTIME is explicitly unimplemented**:
Gesher's current bionic clock adapter aliases it with QPC-based monotonic clocks,
and no independently qualified suspend-aware translation exists. AAudio now
returns `AAUDIO_ERROR_UNIMPLEMENTED` for it instead of silently returning the
engine's host-monotonic timestamp. Host monotonic operation does not establish
Android suspend behavior, audio hardware latency accuracy, or full clock parity.

Pinned sources:

- [AAudioStreamParameters validation](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r4/media/libaaudio/src/core/AAudioStreamParameters.cpp)
- [AudioStreamBuilder callback limits and output initialization](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r4/media/libaaudio/src/core/AudioStreamBuilder.cpp)
- [Android audio channel/rate limits](https://android.googlesource.com/platform/system/media/+/android-16.0.0_r4/audio/include/system/audio.h)
- [Public AAudio errors, negotiation and timestamp contract](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r4/media/libaaudio/include/aaudio/AAudio.h)

### AAudio validation and integration

`python3 tools/test_aaudio_builder_contracts.py` compiles verbatim production
builder, stream, callback-loop and getter bodies with C++ exceptions disabled and
UBSan. Its declared device seam observes parameters, returns deliberately rounded
capacities, and supplies timestamps. All **103 checks pass**, including both
production input/output callback loops. The unchanged assertions fail 49 checks
under `--negative-control`, which bypasses only builder validation in a disposable
source copy. No hardware/device execution is implied by this portable test.

The complete production `media_jni.cc` and `aaudio.cc` translation units also
compile as x86-64 Windows objects with the verified llvm-mingw toolchain,
`-std=c++23 -fno-exceptions -DGESHER=1 -Wall -Wextra -Werror`, exact patched
libnativehelper JNI headers and `GESHER_MEDIA_STANDALONE` logging. This checks the
Windows target and JNI calling convention; the final linked runtime remains a CI
gate. The existing media API/AAudio I/O regression suite remains unchanged in
behavior and preserves all 266 assertions.

Build `tests/native/aaudio_builder_contract_test.c` with the real NDK for each
Windows guest ABI. The exercised declarations are available at API29 in the
CI-pinned NDK r27c; the behavioral reference remains Android16r4:

```sh
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android29-clang" \
  -std=c11 -O2 -Wall -Wextra -Werror \
  tests/native/aaudio_builder_contract_test.c -laaudio -o aaudio-builder-x64
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang" \
  -std=c11 -O2 -Wall -Wextra -Werror \
  tests/native/aaudio_builder_contract_test.c -laaudio -o aaudio-builder-arm64
```

Run each ELF through the corresponding `gesher-exec` Windows track with
`GESHER_AUDIO=null`; require exit zero and
`AAudio builder guest contracts: <count> checks, 0 failures`. The fixture exercises
public NDK invalid-input errors, output clearing, actual negotiation, resizing,
real output timestamps, and actual callback block sizes. A local NDK/Windows
runtime was unavailable during implementation, so this compilation/execution is
explicitly left to the required x64/ARM64 CI gates. Existing `recordapp`/media3
fixtures cover actual input capture and callbacks with RECORD_AUDIO permission.

Remaining AAudio gaps include channel-mask/layout fidelity, ignored newer
policy/spatialization/capture settings, compressed/offloaded paths, full
suspend-aware clock translation, callback self-close, and comprehensive
allocation/stream-lifetime handling under arbitrary concurrent caller use.
