# Round 2 evidence and reproduction

The parent [audit report](../android-runtime-round2-2026-11-16.md) explains the
findings. These files preserve the complete path map and actionable static
queues without treating a missing declaration or a source default as proof of
an application crash.

## Files and identities

| File | Meaning |
| --- | --- |
| `source-map.jsonl` | One record for every one of the 3,301 tracked implementation-snapshot paths, with Git mode, object type, blob, size, area and baseline blob. |
| `source-map-summary.json` | The implementation tree, baseline commit, area counts and map SHA-256. The ten report/evidence files added after this snapshot are intentionally outside their own map. |
| `java-class-availability.json` | Complete class comparison, absent-class lists, family counts, ordered input hashes and candidate delta. |
| `java-member-summary.json` | All 90,630 declaration totals, input hashes, generator identity, full generated JSONL hash and committed queue selection. |
| `java-member-review-queue.jsonl` | 940 retained rows: 423 absent members on present classes, 503 possible flag mismatches, nine descriptor-type gaps and the five resolved MediaStore rows. These selected groups are disjoint in this snapshot. |
| `services-jni-review.json` | Complete fresh scanner output: source coordinates/hashes, explicit and inherited service methods, service keys, upstream registration candidates and fallback JNI methods. |
| `input-provenance.json` | Fresh baseline/candidate application artifacts, service DEX, reused platform source/run, generated table and tool/input hashes. |
| `pr-overlap.json` | Direct metadata, complete changed-path sets and exact pairwise merge evidence for the inspected PR heads. |

The complete 90,630-row member JSONL is a reproducible intermediate. The
committed summary retains its hash; the smaller queue retains every unresolved
member on a present class, every possible flag mismatch, every absent type in a
present descriptor, and every changed row. The 6,800 declarations belonging to
absent classes are summarized by family, and all 865 absent classes are listed
in the class inventory. The inventories compare the supplied boot path, not
every optional APK, module, shared library or desugaring configuration.

The service scan is unchanged between the freshly built baseline and this
candidate except for scan metadata. This does not imply that service behavior
is unchanged: the focused `ActiveServices` state-machine repair is outside the
scanner's inherited-AIDL-default accounting. Its independent behavior tests
provide the evidence for that repair. The generated device stand-in table was
not present locally; its absence is recorded explicitly. No runtime JNI
coverage total is inferred from this source-only output.

## Regenerating the Java inventories

Prepare matching SDK 36, framework/libcore and application outputs using the
repository's normal build workflows. Build the baseline application at commit
`74af63a1fd7cb10c508ea21ebf013ec0aff38504` and the candidate application from the
reviewed change. Keep the five boot inputs in this order:

```sh
python3 tools/java_api_inventory.py \
  --sdk-jar inputs/sdk36/android.jar --sdk-api-level 36 \
  --boot-jar inputs/boot/core-oj.jar \
  --boot-jar inputs/boot/core-libart.jar \
  --boot-jar inputs/boot/core-icu4j.jar \
  --boot-jar inputs/boot/framework.jar \
  --boot-jar baseline-app/gesher-app.jar \
  --candidate-replace gesher-app.jar=candidate-app/gesher-app.jar \
  --outdir evidence/classes

python3 tools/java_member_inventory.py \
  --sdk-jar inputs/sdk36/android.jar --sdk-api-level 36 \
  --boot-jar inputs/boot/core-oj.jar \
  --boot-jar inputs/boot/core-libart.jar \
  --boot-jar inputs/boot/core-icu4j.jar \
  --boot-jar inputs/boot/framework.jar \
  --boot-jar baseline-app/gesher-app.jar \
  --candidate-replace gesher-app.jar=candidate-app/gesher-app.jar \
  --outdir evidence/members
```

The retained queue uses this predicate on each full member row:

```python
result = row["candidate"]
retain = (
    result["status"] not in ("exact_descriptor_present", "declaring_class_absent")
    or result.get("descriptor_types_absent_from_boot")
    or row["installed"] != result
)
```

For service/JNI source scanning, supply the framework class archive and the
pinned AOSP tree, including `frameworks/base/core/jni/AndroidRuntime.cpp`:

```sh
python3 tools/audit_services_jni.py \
  --framework-jar inputs/framework-classes.zip \
  --aosp-dir inputs/aosp --out evidence/services-jni-review.json
```

The committed copy normalizes the external framework archive path and replaces
the local checkout commit identity with the reviewed implementation tree.
Repository-relative source locations and source hashes are retained.

## Behavioral gates

The four detailed reports provide arguments and negative-control methodology.
The CI changes require the new host runners as well as Windows/ART or guest
completion markers. Existing exit-code checks and regression gates remain.

* `tools/test_service_rebinding.py`: real service state machine and LoadedApk;
  Windows track 99 dispatches the corresponding service fixture.
* `tools/test_media_store_access.py`: call sites compiled against only the
  public SDK, then run against the production facade and real framework
  clients. The Windows service DEX builder recompiles the same call-site source
  against framework/application classes; it is not the identical SDK-only
  compiled artifact. Track 99 runs the fixture on ART.
* `tools/test_sleep_contracts.py --sanitize`: controlled long-budget and
  signal-boundary tests plus bounded independent Linux calls. `libc_test.c`
  includes the shared public fixture for x86-64 and translated ARM64; tracks
  40 and 47 require `sleep contracts: complete`.
* `tools/test_native_window_dispatch.py --sanitize`: production entrypoints and
  recording native-window receiver. Track 96 also requires the shared marker
  from the real Windows-linked geometry executable.

Host test executions are bounded by their runners. Tracks 47, 96 and 99 retain
their existing process deadlines. The inherited track 40 invocation is bounded
by the 120-minute shard job rather than an additional per-process watchdog.
All real new sleep waits/polls are at most two seconds; very long and overflow
budgets use controlled clocks instead of actual long sleeps.

Local application and service DEX builds are fresh, but reuse the platform
compilation inputs and compatibility table identified in `input-provenance.json`.
They do not replace a current full-platform Windows CI run. Exact published-head
workflow results are recorded in the PR, where they can remain current without
creating a new code revision merely to write a CI status into the repository.
