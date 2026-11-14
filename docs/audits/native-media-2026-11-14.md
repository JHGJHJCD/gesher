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

Retriever and Extractor Java context fields now own intrusive references. Each
entrypoint retains a reference under the class context lock before acquiring the
receiver's operation lock. Release detaches the field under the context lock and
drops ownership outside it. A parser already inside a callback may finish, and a
callback may release its own receiver, without destroying that active receiver
or source. Independent receiver operations are serialized; reentrant extractor
setup is rejected while an open is already in progress. New receiver allocation
uses `new (std::nothrow)` and reports OutOfMemoryError. This does not claim that
all pre-existing STL allocations in media codecs have recoverable OOM handling.

Retriever builds a fresh source/metadata/artwork snapshot. Successful replacement
cannot carry artwork from a previous source. A failed replacement clears the
whole old snapshot, following Stagefright's `clearMetadata()`/source reset
contract. Inferred bitrate is omitted when its integer numerator cannot be
represented. Extractor retains its one-successful-source contract, permits retry
after an initial failure, and closes a rejected callback candidate while keeping
the original selected source.

Pinned references:

- [MediaDataSource native callback adapter](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/android_media_MediaDataSource.cpp)
- [MediaExtractor callback overload and error cleanup](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/android_media_MediaExtractor.cpp)
- [Retriever callback overload and retained receivers](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/android_media_MediaMetadataRetriever.cpp)
- [Stagefright metadata replacement and clearMetadata](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r4/media/libmediaplayerservice/StagefrightMetadataRetriever.cpp)

### Callback validation and integration

`JAVA_HOME=<jdk> python3 tools/test_media_callback_contracts.py` runs 43 checks
against the production callback header, receiver lease header and verbatim JNI
entrypoints using a real JVM with `-Xcheck:jni`, undefined-behavior sanitization,
and C++ exceptions disabled. The parser boundary is a deterministic observer;
it supplies a source with artwork, a source without artwork and an unreadable
source. It is not an AOSP parser emulation or a Windows runtime qualification.
Tests include partial reads through multiple chunks, EOF, size caching, negative
and overflowing offsets, invalid callback counts, IOException handling, a
throwing close while an exception is pending, concurrent readers, snapshot
replacement, failed setup/retry, and release while a callback is blocked on an
explicit barrier. Both receivers keep the source alive until the operation ends.

The unchanged assertions fail under `--negative-control stale-art` (reuse the
previous artwork) and `--negative-control unavailable-callback` (restore the old
extractor callback no-op). Negative controls operate in disposable generated
files and do not edit production source.

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
