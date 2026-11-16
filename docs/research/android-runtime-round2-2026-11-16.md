# Android/Windows contract audit, round 2 — 2026-11-16

Attribution: **palmoni5**. This round mapped the repository, refreshed API and service review queues, and repaired four demonstrated contract failures. Independent reviewers challenged implementations, added regression controls, and found additional lifecycle and test-build defects. The result is a bounded compatibility improvement; substantial API and Windows-backend work remains.

## Architecture and mapping

The [source map](round2/source-map.jsonl) covers all **3,301 paths in the reviewed implementation snapshot**, with baseline blob identities and counts in the [map summary](round2/source-map-summary.json). The baseline contains **3,285 tracked paths**. It includes 362 application paths, 531 compatibility paths, 1,313 test paths and 413 tool paths. Complete path coverage provides navigation and reproducible ownership boundaries; it does not mean every line or possible application behavior was executed.

| Boundary | Principal paths and responsibilities |
| --- | --- |
| Application and framework | `app/java`: APK bootstrap, Android module facades, service implementations, lifecycle and package behavior. Framework/libcore code is supplied through the pinned AOSP build. |
| Native compatibility | `compat/bionic`, `compat/posix`, `compat/elf`, `compat/arm64`: Android libc exports, Windows-backed OS operations, ELF loading and translated guest execution. |
| Presentation and devices | `compat/hwui`, `compat/media` and related adapters: native windows, graphics, audio, media and desktop integration. Correct public dispatch still depends on backend behavior. |
| Build and evidence | `cmake`, `patches`, `tools`, `tests`, `.github`: AOSP integration, source selection, inventories, contract fixtures and execution gates. |

Review followed calls across these boundaries: an available Java descriptor can still depend on a missing provider, inherited Binder default, native registration or incomplete Windows operation. Classes under `app/java/com/google` that are excluded from the runtime boot JAR were not classified as shipped runtime defects merely because source exists.

## Measured API and service queues

The [class inventory](round2/java-class-availability.json) compares the selected boot path with **6,227 SDK 36 class definitions**. It finds 5,362 available and **865 absent**, unchanged by these repairs. Absences concentrate in connectivity, other framework classes, Health Connect and AdServices. Module, APK and optional-library providers outside the selected inputs were not established by this comparison.

The [member summary](round2/java-member-summary.json) and [review queue](round2/java-member-review-queue.jsonl) account for **90,630 public/protected declarations**. The candidate contains 82,904 exact descriptors, **423 absent members in present classes**, 6,800 declarations whose class is absent, and 503 possible access/flag mismatches. Baseline missing members in present classes numbered 428. All five improved rows are the MediaStore additions below; the remaining 423 are independently counted from the generated rows. Inherited member lookup is included. Descriptor presence establishes neither implementation correctness nor provider availability, and these categories are not crash totals.

The [service/JNI review](round2/services-jni-review.json) records 73 service classes, 1,150 explicit methods, 2,744 inherited defaults, 55 direct registrations and 194 context-key candidates. It also identifies 174 upstream JNI registration rows and 523 stand-in methods. These are review queues: the inherited-default comparison uses names rather than full overload resolution, registration aliases need inspection, and later native registration can replace a stand-in. An unreferenced textual registration is not automatically a missing required API.

## Four repaired areas

### 1. Retained service bindings and callback order

`ActiveServices` discarded a retained service's published Binder after its last client left and did not honor `onUnbind(true)` with `onRebind`. The repair preserves publication, including a published null Binder, separately from binding lifecycle. A per-service queue orders bind/unbind submissions, tracks generations and rejects retired work.

Independent QA also found teardown overtaking an in-flight bind and a local scheduling exception stranding reentrant unbind work. The corrected implementation drains ordinary lifecycle submissions before destruction, rechecks replacement ownership, handles force-stop cancellation and preserves the original exception. See [service rebinding contracts](android-service-rebinding-2026-11-16.md).

### 2. Five MediaStore API contracts

The facade now supplies `canManageMedia`, `getDocumentUri`, `getMediaUri` and both `getRedactedUri` overloads. Each previously failed at a public-SDK invocation with `NoSuchMethodError`.

The implementation follows pinned MediaProvider behavior: app-op checks use the context UID and operation package; only the default mode consults the permission. URI calls preserve persisted-grant snapshots, stable-client ownership, user-qualified provider selection, provider errors and documented request envelopes. Batch order, duplicates and pinned null/error cases are covered. An installed provider must still perform conversion or redaction. See [MediaStore access contracts](mediastore-access-contracts-2026-11-16.md).

### 3. Native sleep and clock translation

Ordinary sleeps could overflow or truncate duration conversion, finish after one capped Windows wait, mishandle large `usleep` inputs and overwrite successful remainder outputs. The shared implementation preserves elapsed-time budgets across bounded waits, snapshots aliased inputs, normalizes interruption remainders and follows pinned Android LP64 `sleep` behavior.

Signal observation captures interruption time before handlers run, preventing handler latency from consuming the returned remainder. Explicit-clock saturation and clock-validation behavior are also corrected. Independent QA additionally covered pending signals at zero-length and already-expired completion boundaries. That final signal-delivery correction is integrated and covered by combined production-wrapper/adapter regression tests. See [native sleep contracts](native-sleep-contracts-2026-11-16.md).

### 4. Native-window dispatch

The default-dataspace getter now queries the consumer default independently of producer dataspace. Transform admission matches the pinned four-bit mask; affected transform, dataspace and frame-rate APIs reject null/invalid windows and propagate receiver errors. Legacy geometry/lock behavior remains outside this repair.

Independent QA found six Windows compilation errors because null-negative tests passed literal null to `_Nonnull` NDK declarations under `-Werror`. Reading a volatile null pointer preserves those runtime tests and compiler warnings. Compilation with the actual pinned headers and LLVM-MinGW confirms the correction. See [native-window dispatch contracts](native-window-dispatch-2026-11-16.md).

## Independent verification and provenance

| Verification | Local evidence |
| --- | --- |
| Service rebinding | 431 checks; 65 adjacent null-publication checks; targeted old-source and pre-QA controls fail at intended assertions. |
| MediaStore | 202 access checks and 208 adjacent request checks; all five SDK-only call sites fail against baseline with their expected missing descriptor. Android-core compilation and DEX conversion pass. |
| Sleep/clocks | 107 host checks under ASan/UBSan, 30 independent Linux checks, 231 ordinary/explicit-clock checks and 216 adjacent pthread checks. Changed translation units pass x64/ARM64 Windows-target syntax checks. |
| Native window | 65 sanitizer checks; baseline control produces 17 expected failures among 59 non-null checks. Actual pinned-header Windows syntax checks pass after the fixture correction. |

Suite counts overlap and are not a compatibility percentage. Controlled providers, clocks and receivers isolate contracts; they do not establish physical hardware behavior. Local leak detection alone was disabled because the executor is traced; address and undefined-behavior sanitizers remained enabled.

[Input provenance](round2/input-provenance.json) records hashes and source pins. Fresh application compilation reused verified platform artifacts and a complete generated compatibility table from an earlier build. Native ART patches and the framework source-tree materializer have since changed. Consequently, local compilation is not a fresh full candidate AOSP build. Windows/ART execution, native guest gates and regeneration of build inputs remain required publication evidence; the pull request records the hosted run result for its exact published head. See [evidence reproduction and scope](round2/README.md).

## Remaining work and parallel PRs

The following are concrete follow-up boundaries, not additional repairs claimed by this PR. The 423 member candidates and 865 class absences remain a separate, complete static queue. Priorities reflect potential reach and the dependencies needed for a correct repair; they are not measured crash frequencies.

| Follow-up | Evidence and required behavior |
| --- | --- |
| File status and pipes | Generic `F_GETFL` reports `O_RDWR`, generic `F_SETFL` succeeds without changing state, and `pipe2` only maps close-on-exec. Socket/eventfd-specific implementations already differ. A general repair needs shared open-file-description state, alias lifetime, nonblocking reads/writes, invalid-flag handling, `PIPE_BUF`, readiness and child-stdio integration. |
| ELF load failure | The loader publishes a library before all dependency, relocation and protection stages succeed. Correct failed/ready ownership must cover dependency cycles, reentrant lookup and TLS lifetime; removing a single failed mapping is insufficient. |
| Service/task semantics | Multi-activity launch currently selects the last intent instead of constructing the full requested back stack. Foreground service policy and broader lifecycle behavior need independent contracts beyond the rebinding repair. |
| MediaProvider and descriptor helpers | Three public MediaStore open helpers still require cloud-picker request parcelables, three Binder interfaces, the provider-specific exception wrapper, cancellation and descriptor ownership. The five facade additions do not create a media database or perform redaction. |
| AppOps and capability policy | The existing AppOps service broadly allows operations. Consuming its answer correctly does not establish Android-equivalent per-application policy. Unsupported hardware/features must be reported consistently with actual backend support. |
| Shared-memory protection | `ASharedMemory_setProt` returns success without enforcement and generic seals remain advisory. A full contract requires monotonic permissions across aliases and later mappings. |
| Signals and host clocks | Signal disposition is still read separately after dequeue; a concurrent `sigaction` can change the action of a claimed instance. QPC-based clocks retain their existing suspend accounting. The sleep fixes do not establish Linux MONOTONIC/BOOTTIME parity or arbitrary asynchronous interruption of Windows waits. |
| Graphics and physical devices | Native-window dispatch still relies on incomplete backend transforms, color-space handling and frame pacing. Hardware audio/routing, camera and Bluetooth behavior require physical-device evidence in addition to controlled contract tests. |

The [native report](native-sleep-contracts-2026-11-16.md), [service report](android-service-rebinding-2026-11-16.md), [MediaStore report](mediastore-access-contracts-2026-11-16.md) and [window report](native-window-dispatch-2026-11-16.md) link source contracts and narrow the evidence for each boundary. Adding a success-returning stub would not close these gaps.

The initial [PR overlap evidence](round2/pr-overlap.json) records the pre-publication state: #52 and draft #55 were open, #53 had merged, and #54 was closed and unmerged. PR #52's shared native file changes pthread attributes, while this round changes separate sleep logic. PR #53 supplied the merged CI approval-policy changes; #55 owns ANGLE activation. Shared CI/test files were inspected, and pairwise textual merges were clean at the recorded heads. Pairwise merge success does not establish combined runtime compatibility. The following update supersedes those PR states.

## Integration and parallel work discovered during publication

After PR #58 opened at `55209626556c0a712534b8cf157a46ba7160325b`, main advanced to
`7a5e49359e04272bac5e9595b8209e5239053ff7` by merging #52. That main is integrated
without conflicts. The native sleep functions remain identical to their reviewed
implementation, while the upstream pthread helpers remain identical to merged
main. Both sets of CI and Windows fixture gates are retained. The
[integration receipt](round2/integration-update.json) records the exact merged
tree and its path delta from the initial map; together they describe all 3,328
paths in that merged snapshot, before this receipt is added. The original
inventories and build hashes continue to describe their explicitly pinned local
inputs. Hosted CI must qualify the new integration head.

Draft [#57](https://github.com/gesher-runtime/gesher/pull/57), created at 20:12:14
UTC, and this PR, created at 20:16:10 UTC, independently developed overlapping
service fixes. At inspected head `f7504837c2e4825b841beb21ae055f8d67e28908`, its
retained true-rebind flow passes the existing 38-check positive control. Five
other existing scenarios fail against its actual `ActiveServices` source:
false-result Binder retention, immediate cached delivery during pending unbind,
submission ordering, destruction during an in-flight bind, and last external
client death. These are assertion failures after successful compilation and
fixture setup. The first two expectations were checked against pinned AOSP;
the integration receipt preserves the control results, source links and log
hashes. The complete branches conflict in `ActiveServices.java`.

This PR retains its validated service implementation and 431-check suite.
Avoid combining the two competing service implementations. #57's task-stack
implementation, test and Track 99 invocation are a separable three-path change;
that extracted scope applies cleanly here but still requires its own behavioral
QA. No task-stack code from #57 is included. Its unique true-acknowledgement
token is compatible with ActivityThread's echo path and may be evaluated as a
separate improvement; it does not justify changing retained-publication
semantics. The false-result callback still lacks a binding/cycle token.

PR #54 has reopened at its unchanged `07bcd38f` head and remains outside this
implementation scope. Draft #55's renderer-selection work also remains separate.
These observations are timestamped evidence, not a claim that other branches
cannot subsequently advance.
