# MediaDataSource callbacks, ownership and retriever replacement

Baseline: `e0dbabf8932df2c101d8ed3d28a8c560df771dc0`.
Reference: AOSP `android-16.0.0_r4`, as selected in `aosp.lock`.

## Repaired contracts

`MediaExtractor.setDataSource(MediaDataSource)` and
`MediaMetadataRetriever.setDataSource(MediaDataSource)` had no implementation in
their JNI method tables. Generic stand-ins returned normally without attaching
the source. Both public APIs now reach a shared Java callback adapter and the
existing AOSP container readers. MediaPlayer also uses this adapter, replacing
its private implementation.

The adapter serializes Java reads and size queries over its bounded shared byte
array. It accumulates valid short reads, as AOSP's `CallbackDataSource` does;
maps Java `-1` to native EOF; and latches other negative statuses, callback
exceptions and invalid returned counts as permanent source failure. It checks a
returned count before copying and checks the copy's JNI result. It normalizes
negative lengths to unknown size and caches the first successful size result.
Zero-byte native reads do not invoke Java. Method lookup, allocation and global
reference failures preserve their original pending Java exception.

An initialized source owns its callback until the last native owner retires.
Cleanup invokes `close` once. An ordinary close preserves a callback exception
for its JNI caller; this includes the IOException contract documented by
`MediaMetadataRetriever.release`. A preexisting exception is saved and restored
across cleanup instead. Rejected or failed setup retains its setup error across
source cleanup: an earlier close failure is consumed before error translation,
or the already-published setup exception is restored after close. This prevents
a cleanup failure from replacing an earlier failure, without silently swallowing
ordinary release errors.

Extractor and retriever native contexts now use retained references acquired under
a short context lock. Release publishes the empty Java context before dropping
its reference, and admitted JNI methods keep their receiver alive. Java callbacks
can therefore release a receiver during an admitted read, or reenter release
from close, without invalidating that operation's native context. The context
lock is never held while closing a source. Integration with the independent
[Android/Windows audit](../audits/native-media-2026-11-14.md) also retains its
`ReceiverLease`: operations on one receiver serialize, and callback reentry into
an active receiver operation is rejected before it can change selected readers
or a metadata snapshot. Release remains allowed during an admitted callback.

`Player::Reset` detaches both the playback object and source under its state
lock, publishes idle state, and then shuts down and destroys those owners
outside the lock. An unprepared callback source can close and query the player
without blocking on a lock still held by reset.

Retriever replacement builds a fresh metadata/art/source snapshot and publishes
it after a successful open. The old source closes after the new state is
visible. A source with no embedded picture clears the previous picture, and a
failed replacement leaves matching old metadata and old source in place. This
follows `MetadataRetrieverClient`'s successful-candidate publication. Retriever
FD input also rejects null descriptors, negative offset/length, and invalid
descriptor values at the JNI boundary.

Independent QA also found that average bitrate multiplied source size in signed
integer arithmetic before division. A large valid virtual source could overflow
that intermediate even when the final bitrate fit. The calculation now uses
Stagefright's floating-point average and converts only results below `2^63`.
An unrepresentable average remains unavailable instead of performing an invalid
integer conversion. The existing positive size/duration conditions remain.

## Evidence and validation boundaries

`tools/test_media_callback_adapter.py` compiles the complete production
`java_data_source.cc` into a JNI library and calls it from a real host JVM with
`-Xcheck:jni` and UBSan. VM attachment/logging, JNI exception reporting and the
abstract Android callback type are host seams. The candidate passes **42 assertions in 10
groups**, with no checked-JNI or UBSan diagnostic. Cases cover short reads across
the buffer boundary, zero length, EOF, sticky read/size errors, bounded count
validation, cached known/unknown size, concurrent callback serialization,
ordinary close exceptions, original-exception preservation and lookup failure.

Eight independent old-source controls fail on the baseline: `negative`,
`exception`, `overcount`, `known-size`, `unknown-size`, `size-error`, `serialized`
and `close-error`. These are functional assertion failures. The count control
uses a valid extra destination suffix, so the old implementation's extra copied
byte remains inside an allocated object. The control runner excludes `pending`
against the old implementation, which would make an invalid JNI call during
cleanup.

`tools/test_media_callback_state.py` compiles the exact production `Fill`,
`SetFromSource` and `Player::Reset` bodies, with declared container,
receiver-acquisition, JNI-exception and playback-worker seams. Its Format seam
retains the real missing-key behavior. The candidate passes **35
assertions with ASan/UBSan**. The baseline fails six of the original 27 assertions: stale art,
replacement publication and retirement, failed-candidate state, and player
reset's lock/state publication. A separate observer thread uses a bounded
mutex check; the old-source control does not attempt a recursive blocking lock.
Eight candidate-only bitrate checks cover fractional truncation, a two-terabyte
source with a representable average, the exact `2^63` conversion boundary and
an unrepresentable average. Old-source mode excludes this group rather than
executing its original signed-overflow expression.

`tools/test_media_callback_contracts.py` retains the independent receiver suite
from the Android/Windows audit. It compiles the same adapter translation unit
alongside the actual receiver lease and extracted JNI entrypoints, passing
**66 checked-JNI/UBSan assertions**. The adapter suite above has a separate name
so both original suites remain mandatory in CI. Their assertions, concurrency
barriers and deadlines are preserved. Disabling receiver reentry admission in
the disposable negative control still fails the intended assertion; the old
publication/reset source still fails six of the original 27 state assertions.

The SDK36-only `MediaDataSourceContractTest` compiles successfully. Its Windows
track exercises the public APIs on ART with actual AOSP readers: generated WAV
sample bytes and metadata, ID3 embedded art followed by a source without art,
source ownership on failure/replacement/release, released/null/FD boundaries,
callback release during an admitted read, ordinary close IOException, and
MediaPlayer reset/release reentry. Callback latches and worker joins are bounded.
It requires the marker `MediaDataSourceContractTest all passed:`. The synthetic
MP3 fixture tests container/ID3 parsing only; it makes no audio-decoding claim.

Adjacent host checks also pass:

| Runner | Passing checks |
| --- | --- |
| `test_media_api_contracts.py` | AAudio 37, extractor setup 17, YUV crop 32, YUV rows 180 |
| `test_retriever_frame_contracts.py` | 133 |
| `test_media_source_contracts.py` | 29 source checks and 16 byte-buffer checks |
| `test_media_http_contracts.py` | 539 checks across 192 controlled requests |
| `test_media_surface_contracts.py` | 20 timestamps and 31 native-window ownership checks |
| `test_media_player_priming.py` | 158 priming checks and PCM probe analysis |

The entire new adapter, retriever JNI, player JNI and player implementation also
pass supplemental LLVM-MinGW syntax checks. Those checks use the existing
standalone logging switch and JDK JNI headers; the JDK's mutable native-method
name fields require suppressing its preexisting writable-string diagnostics.
They are not the pinned-AOSP CMake build. This report records local results;
actual Windows runtime and full build results belong to the pull request's CI
run. Local ASan runs use `detect_leaks=0` because LeakSanitizer cannot run under
the executor's ptrace environment; CI keeps its sanitizer defaults.

## Reproduce

```sh
python3 tools/test_media_callback_adapter.py --java-home /path/to/jdk21
python3 tools/test_media_callback_state.py --sanitize
```

For functional old-source controls, append `--source-ref <baseline>` and select
the relevant `--group` values. The state runner has `metadata`, `reset` and
candidate-only `bitrate` groups. Both runners print the production source fingerprint.

These results do not establish every Android media API or arbitrary APK
compatibility. In particular, concurrent context release is covered, while
concurrent setter/track mutations, physical playback devices, secure/DRM media,
unsupported codec families and unrelated remaining stand-ins require separate
work. No physical audio device is needed by the new public callback fixture.

## Primary references

- [JMediaDataSource, pinned framework revision](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/media/jni/android_media_MediaDataSource.cpp): callback serialization, status/size semantics and close exception behavior. Decoded source SHA-256 `cb6c1ed7269a16375176d6350c592b3b0d116823919bc45f0de04ce67c578564`.
- [CallbackDataSource, Android 16 r4](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-16.0.0_r4/media/libstagefright/CallbackDataSource.cpp): bounded callback aggregation and one-time close. Decoded source SHA-256 `e19dc701c75eea70158ea134e965bdb9c5dbca642cb906b1ed0c0073b82c45bd`.
- [MediaExtractor JNI, pinned framework revision](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/media/jni/android_media_MediaExtractor.cpp): callback registration, input errors and failed-source disposal before throwing. Decoded source SHA-256 `057b9a4962026c4c59c77f57473b0882fb0c3a4c528f1912540fc7bd44ac3d34`.
- [MediaMetadataRetriever JNI, pinned framework revision](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/media/jni/android_media_MediaMetadataRetriever.cpp): callback registration and native input/error mapping. Decoded source SHA-256 `660a094f63d0596f4cf6e16eb1c947d45cf3718c2c68131e8c29a69a8d17cc60`.
- [MetadataRetrieverClient, Android 16 r4](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-16.0.0_r4/media/libmediaplayerservice/MetadataRetrieverClient.cpp): a successful candidate replaces the current retriever. Decoded source SHA-256 `3dce4143ed7cf464d763cce8c769a2040b1eace6fd99bc183b11eb53a98ab331`.
- [StagefrightMetadataRetriever, Android 16 r4](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-16.0.0_r4/media/libstagefright/StagefrightMetadataRetriever.cpp): floating-point average bitrate calculation. Decoded source SHA-256 `2324dc434e3903f887265708551fe5c02e30b25f05f4295c7ea4903fb7d9ae14`.
- [MediaMetadataRetriever Java API, pinned framework revision](https://android.googlesource.com/platform/frameworks/base/+/45034f0663f960d9ee5fb0a101a4732b71f6e2f4/media/java/android/media/MediaMetadataRetriever.java): public callback setter and release's declared IOException contract. Decoded source SHA-256 `7289617e075d9e75cc691ca4772fb691196e61377f3c29107fbeaed9d27e03dd`.
