# Runtime performance audit — 2026-11-14

## Scope and evidence

This round maps Gesher's execution paths and implements focused improvements in allocation, I/O, startup and frame lifetime. The baseline is `e0dbabf8932df2c101d8ed3d28a8c560df771dc0`. Three mapping agents investigated native execution, media/graphics and Java/application services; three separate implementation agents followed, with independent QA of the changes.

Native/JNI, Java, launcher-log and media QA have approved the changes, including the additional audio rate-change repair described below. The published revision must also complete full CI; the PR checks record its observed platform status. Host measurements establish specific operation counts and contracts, not application startup gains, frame-rate gains, total RSS reductions or correctness of every repository line. Windows/ART/Skia execution and physical WASAPI/device validation remain distinct from host fixtures.

The baseline has 2,972 tracked files: tests 1,173, compat 513, tools 340, docs 339, app 320, `.github` 83, probes 83, patches 75, cmake 33, config 4, assets 1, java 1, and 7 root files. This inventory describes coverage areas, not equal-depth inspection of every file.

Open PRs were checked before choosing changes and again before publication. This work does not duplicate [PR24](https://github.com/gesher-runtime/gesher/pull/24)'s reserved filenames and exec diagnostics, [PR25](https://github.com/gesher-runtime/gesher/pull/25)'s exiting-process Binder identity, [PR26](https://github.com/gesher-runtime/gesher/pull/26)'s Debug memory natives, or [PR27](https://github.com/gesher-runtime/gesher/pull/27)'s Android callback, clock, NSD and input contracts. Shared documentation and CI files receive separate additions. Publication for this round uses **palmoni5**.

## Repository and execution map

| Path | Responsibility and performance questions |
|---|---|
| `app/java/com/gesher/app`, `app/java/com/gesher/server` | APK launch/install, parsing, services, settings, Binder dispatch and lifecycle; repeated scans, reflection and small I/O |
| `compat/art`, `patches`, `cmake/aosp` | ART, JIT and boot/framework integration; optimization must preserve platform and ABI contracts |
| `compat/bionic`, `compat/elf` | Native adapters, JNI, ELF mappings/relocations, TLS and exec; allocations at crossings and symbol resolution |
| `compat/arm64` | Translation, code caches, guest/host calls and pooled environments; throughput versus retained memory |
| `compat/posix`, `compat/binder` | Descriptors, waits, sockets, mappings, procfs, properties and Binder transport; scratch allocations, revalidation and payload copies |
| `compat/hwui`, `compat/angle`, `compat/opengl`, `compat/windows` | Rendering, composition and windows; uploads, device resources and message-loop latency |
| `compat/media` | WASAPI mixing, codecs, playback, capture and surfaces; periodic allocations, frame ownership and conversions |
| `compat/input`, `compat/accessibility`, `compat/devices`, `compat/camera` | Input/IME and device callbacks; response latency, admission and queue lifetime |
| `compat/net`, application networking | TLS, Windows trust, networks and proxies; preserve certificate/filter behavior |
| `compat/webview`, application WebView code | WebView2 bridge, requests and callbacks; body buffering and worker lifetime |
| `compat/shell`, `compat/install`, application shell code | Explorer, icons and packaging; index parsing and startup I/O |
| `tests`, `probes`, `tools`, workflows | Host contracts, native/Android fixtures and Windows end-to-end gates |

Existing optimizations remain: mapped ELF input, GNU/SysV symbol hashes, per-thread TLS and guest-region fast paths, ARM64 pooling with stack decommit, directory large-fetch mode, and unchanged-generation property reads. Socket wait slices also enforce descriptor lifetime contracts; eliminating checks requires more than a throughput comparison.

## Implemented changes

### 1. Inline JNI arguments

[`jni_bridge.cc`](../../compat/bionic/jni_bridge.cc)'s `Args` previously allocated even for a callback with no arguments. It now stores up to 16 arguments plus the existing spare slot inline, retaining the vector fallback for larger signatures. Primitive promotion, GP/FP decoding, zeroed union bytes and the spare slot are unchanged. Per-invocation ownership protects nested callbacks without a persistent thread-local buffer.

The actual-source harness passed 4,666 checks. The baseline passed 3,818 behavioral checks but failed 424 allocation-gate checks. Across 200,000 calls at each of 0, 1, 10 and 16 arguments, argument-storage allocations fall from 200,000 to zero. At 17 arguments, the 28.8 MB allocation total is unchanged. This isolates argument storage, not all JNI allocations. The tradeoff is 136 bytes of inline storage per `Args`. Existing x86-64 and ARM64 JNI fixtures also exercise the change.

### 2. Reusable audio mixing scratch

[`engine.cc`](../../compat/media/audio/engine.cc) retains frame and refill vectors in the track's resampler under the existing track mutex. Resets retain capacity; destruction releases it. Sample calculations and queue semantics are unchanged by scratch reuse.

The mixer harness covered 60 configurations and 480 checks. After warm-up, 100 mixes per configuration perform zero allocations instead of 200. The sample-and-position comparison covered 27,126,900 bit-identical bytes; this is output size, not allocated memory. The tested 256-frame refill needs approximately 2,056 retained bytes per stereo track and 24,672 per 24-channel track. These are scratch examples, not total track-memory bounds. The same fixed-configuration allocation and bit-exact checks passed again after the separate rate-change repair below.

### 3. Frame identity and detach-safe image caching

[`surface_jni.cc`](../../compat/media/surface/surface_jni.cc) previously identified cached images by a number unique only within one surface queue. A replacement could reuse the number and display old pixels. The cache now uses immutable frame identity; its SkData retains that frame, preventing address reuse while cached.

Null input, detach and replacement clear the image. The layer mutex serializes publication with detach, preventing an older in-flight read from repopulating a cleared cache. Already returned images retain their own ownership. The change repairs stale frames and retained pixel storage without introducing pooling. The original host fixture passed 38 checks and the baseline failed 11; independent QA added ten cases, bringing coverage to 48. Real Skia/framework behavior is additionally tested in Windows track 52.

### 4. Buffered durable settings I/O

[`SettingsStore`](../../app/java/com/gesher/server/SettingsStore.java) places buffered streams beneath its data streams. The binary format, generation/row IDs, temporary replacement and explicit flush-before-file-descriptor-sync remain intact.

The host fixture passed 197 checks. A 16,474-byte settings file requires 3 underlying writes instead of 8,221 and 4 reads instead of 8,222. Counting streams still delegate to real file I/O. This establishes batching, not a disk-latency multiplier. Failure and corrupt-input cases retain the existing publication contract.

### 5. Cached service method resolution

[`StandIn`](../../app/java/com/gesher/server/StandIn.java) caches resolved methods per wrapper instead of repeating reflection and parameter-array retrieval on every invocation. Cold lookup is serialized, while warm dispatch reads a concurrent map. Results, strict-mode decisions and exception handling remain uncached.

For 250,000 host dispatch calls, measured allocation decreases from 40,000,160 bytes to 160 bytes. HotSpot's absolute numbers do not predict ART's allocation profile. The tradeoff is one retained entry per exercised method per wrapper, without a global class-retaining cache. Independent Java QA added 13 dispatch checks; the behavioral fixture also runs in mandatory Windows track 94.

### 6. One native-library scan per APK

[`NativeLibs`](../../app/java/com/gesher/app/NativeLibs.java) tracks the best supported ABI during one scan per archive; later splits can still supply a preferred ABI. Matches remain direct `.so` children of the appropriate ABI directory. The installer reuses the result for native-library location and metadata. Java-only packages retain their default directory and null primary ABI.

The measured repeated-scan case falls from 4 archive opens/8,000 visited entries to 2 opens/4,000 entries. Preferred-ABI early exits may already be cheap. Path coverage increased from 96 to 108 checks, preserving originals. Independent QA also compared 5,860 ABI cases, compiled 12 class files against the actual Android core/framework APIs and converted them to a 29,448-byte DEX. Compilation is distinct from runtime execution.

### 7. Bounded retained launcher-log suffix

[`Launcher`](../../app/java/com/gesher/app/Launcher.java) now uses [`LogLines.readTail`](../../app/java/com/gesher/app/LogLines.java): a buffered UTF-8 reader and bounded deque replace whole-file `readAllLines` retention.

A 49,088,890-byte log completes under a 24 MiB Java heap where the baseline runs out of memory. Fixtures check suffix order, line endings and decoding errors. The implementation still scans the complete file, and a single extremely long line remains unbounded. It bounds retained line count, not arbitrary input bytes or read time.

### 8. Forward log lines without copying each payload

`LogLines` writes directly from its owned byte buffer to the synchronous PrintStream under the existing output lock, then flushes and resets. Chunk assembly and atomic line output remain intact. The fixture observes one reused transfer array for 10,000 lines instead of 10,000 separate arrays. Verification comprises 30 behavioral checks, three buffer-identity checks and one constrained-heap case. Unterminated pending lines remain unbounded, and flushing is preserved.

### 9. Audio rate-change history repair

Independent QA confirmed a pre-existing resampler history-bound defect when a changed rate requires a wider filter and unavailable left-hand history. The repair prepends only missing zero history and shifts relative sample position and start index equally, preserving absolute coordinates and every retained real sample. This prefix insertion can allocate during a parameter change; the warmed fixed-configuration guarantee remains unchanged.

The transition fixture passed 32,720 checks across 360 scenarios under ASan/UBSan, covering PCM formats, channel layouts, speed/source/output-rate changes, startup and compacted history, static and streaming playback, drain completion, tail resume and post-stop priming. An absolute-coordinate oracle checks sample output and source positions using the production kernel; it does not independently assess filter frequency response. The baseline fails the first-tap invariant in all 360 scenarios; each case stops at that guard before executing a positive-frame mix, so this negative control does not perform an invalid history read. Existing audio-state and SoundPool contracts also passed after the repair. Fixed-configuration sample-and-position output remains bit-identical as described above.

## Verification and integration

Six new unconditional preflight scripts exercise production code:

- `tools/test_jni_argument_storage.py`
- `tools/test_audio_mixer_allocations.py`
- `tools/test_audio_rate_history.py`
- `tools/test_surface_image_cache.py`
- `tools/test_java_performance_contracts.py`
- `tools/test_launcher_log_contracts.py`

Native fixtures use strict warnings, disabled C++ exceptions and ASan/UBSan where applicable. Baseline/negative-control runs separate preserved behavior from the allocation or lifetime regressions. Java fixtures retain real Proxy/reflection and file/ZIP I/O while declaring Android boundary adapters. Existing assertions and deadlines remain enforced. Windows tracks 52 and 94 and native x86-64/ARM64 JNI fixtures supply runtime coverage. Host fixtures, Android compilation and D8 success do not replace the full Windows CI gates.

Local sanitizer execution disables only LeakSanitizer process inspection because
the executor runs under ptrace. ASan and UBSan remain active, and CI retains normal
sanitizer defaults. Elapsed timings are informational, not CI pass/fail thresholds.

## Deferred priorities

| Candidate | Observed work or question | Required evidence |
|---|---|---|
| WASAPI scheduling | Fixed 10 ms cadence versus device period | Actual periods, underruns, latency and CPU across devices |
| Poll scratch | Multiple vectors rebuilt on bounded samples | Allocation counts; close/reuse, ConnectEx, UDP and mixed-event latency contracts |
| Procfs threads | Individual task lookups scan/sort a whole-system snapshot | Live/dead/foreign threads, retained handles and snapshot counts |
| Surface pooling | Sizeable pixel buffers retained by consumers | Explicit ownership, outstanding-image bounds and memory/latency profiling |
| Binder prefix | Outbound payload copied into a length-prefixed vector | Identical framing, partial writes and small/large transaction throughput |
| Symbol lookup | C-string lookup creates temporary string keys | Long-name allocation counts and unchanged resolution precedence |
| GDI reuse | Repeated composition resources | Handle lifetime, resize/DPI, device loss and frame profiles |
| Video copies | Multiple format/representation boundaries | Bandwidth per stage, stride/format correctness and ownership |
| HTTP buffering | Some paths retain complete response bodies | Body-size distribution, backpressure, cancellation and ordering |
| APK icon index | Repeated parsing during shell work | Update/delete invalidation and Explorer workload measurements |
| `fnmatch` | Recursive suffix retries and depth limit | Supported-semantics oracle and bounded iterative matching |
| Callback workers | Queue and executor growth | Measured backlog, cancellation/teardown and thread counts |

These are follow-up investigations, not claimed speedups. Buffer pooling, mutable-state caching and callback coalescing can change observable lifetime or ordering. Their benefit must be established alongside preserved Android/Windows behavior.
