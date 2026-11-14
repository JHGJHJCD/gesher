# Location terminal results and finite registration lifetime

Baseline: `e0dbabf8932df2c101d8ed3d28a8c560df771dc0`.
Reference: AOSP `android-16.0.0_r4`.

## Repaired behavior

`LocationManagerService` previously invoked a callback before counting and
retiring its final delivery. A reentrant location report could exceed
`maxUpdates=1`; concurrent reports or a timeout could deliver a current-location
result more than once. A different ordering lost the result entirely: a fix
arriving after expiry retired the current request silently, then its timeout
found no request to complete with null.

Delivery acceptance, update count, last accepted fix and terminal retirement now
share the registration lock. A final delivery leaves the registry before client
code runs. Callbacks, Binder death-link operations and cancellation of timer
handles remain outside that lock. Current success, expiry, provider inactivation,
cancellation and client death compete for the same record identity; stale work
cannot retire a replacement registration.

Ordinary listeners and PendingIntents now receive record-owned expiration tasks.
The expiry executor is separate from potentially blocking Windows/native polling,
and removes canceled tasks from its delay queue. An early scheduler wakeup checks
the elapsed-realtime deadline and rearms. Expiration uses the framework's
`LocationRequest.getExpirationRealtimeMs`, preserving saturated arithmetic and
legacy absolute deadlines. Unlimited requests do not allocate timers.

Current callbacks now link Binder death before publication and unlink on every
terminal path. Failed links and death during publication do not leak a
registration. Provider-disabled completion also applies to the first observed
disabled state, before the asynchronous initial provider poll has established a
previous state. The original caller's system classification is retained for
later permission evaluation; callbacks running on a worker thread do not become
the permission identity.

Independent QA caught a duplicate initial disabled notification: registration
could deliver it before the asynchronous first provider check delivered it again.
Each ordinary registration now claims provider state changes under the registry
lock before invoking the listener or PendingIntent. An initial observation also
does not broadcast a provider transition to unrelated receivers. Current requests
still complete with null when the first observed state is disabled.

## Verification

`tools/test_location_registrations.py` compiles the actual production service and
fixture against real core/framework/app classes. Native Binder allocation,
clock, logging and initialization are explicit host boundaries. Controlled
Binder tokens and a scheduler admit callbacks deterministically; expiration
fields are moved into the past to represent elapsed deadlines without sleeps.
No production Java method is replaced.

The candidate passes **144 host assertions**. Seven independent runs with
`--source-ref e0dbabf8932df2c101d8ed3d28a8c560df771dc0` fail as follows:

| Case | Baseline failure |
| --- | --- |
| `terminal` | Reentrant report exceeds `maxUpdates=1`. |
| `concurrent` | A blocked current callback admits another fix/timeout result. |
| `expiry` | Finite listener has no expiry timer without a fix. |
| `current-deaths` | Current callback has no Binder death link. |
| `disabled` | Provider disabling does not complete the pending current request. |
| `late` | Late fix and delayed timeout produce no null completion. |
| `duration` | Near-maximum duration overflows into immediate expiration. |

The suite also covers timer cancellation and stale execution after replacement,
cancellation and success; legacy absolute expiration; PendingIntent expiration;
current link failure and inline death; callback reentry/replacement/failure; and
existing listener/provider identity and permission checks. The concurrent probe
uses bounded joins and releases its callback latch even on assertion failure.
The additional `initial-disabled` case passes 36 assertions for listeners and
PendingIntents: initial enabled/disabled state, repeated observations, genuine
later transitions, removal and callbacks outside the lock. The same case fails
against the reviewed pre-correction source, establishing the QA regression guard.

The existing Windows `LocationRegistrationContractTest` gate additionally
requires real generated AIDL Parcel transport and the public `LocationManager`
callback path. It now exercises final-update reentry, current success and expired
current null completion there. This report records local results only; actual
Windows results belong to the pull request's CI run. Host success does not claim
physical Windows location hardware, permission changes without another event,
GNSS, geofencing, batching or arbitrary APK compatibility. A client callback that
blocks its delivery thread can still delay other callbacks on that thread.

## Reproduce

```sh
python3 tools/test_location_registrations.py \
  --java-home /path/to/jdk21 \
  --framework-classes /path/to/framework-classes.zip \
  --core-classes /path/to/coremod \
  --app-classes /path/to/gesher-app-classes.jar \
  --apksig-jar /path/to/apksig.jar
```

Append `--source-ref <baseline> --case <case>` for each old-source control.
Compilation inputs used locally came from the hash-verified
[successful main run 43622672102](https://github.com/gesher-runtime/gesher/actions/runs/43622672102),
with a freshly built baseline app compile JAR. The parent audit records input
hashes and the whole-runtime inventory separately.

## Primary references

- [LocationProviderManager, Android 16 r4](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/core/java/com/android/server/location/provider/LocationProviderManager.java): ordinary/current expiration alarms, Binder death and inactive current-result completion. Decoded source SHA-256 `f092f99cc8bff445ecffe4e673c5b7308a82973033b1dd63565b2b72ba74b52c`.
- [LocationRequest, Android 16 r4](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/location/java/android/location/LocationRequest.java): `getExpirationRealtimeMs` and request limits. Decoded source SHA-256 `b201fc7cb0f3c352443a023743e08014c9e151fb44bf405faedc1f3cd8568700`.
- [LocationManager reference](https://developer.android.com/reference/android/location/LocationManager): current-location completion and cancellation contract.
