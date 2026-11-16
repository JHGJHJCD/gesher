# Android service rebinding — 2026-11-16

Baseline: `74af63a1fd7cb10c508ea21ebf013ec0aff38504`. This scope repairs the
shipped `ActiveServices` implementation. It does not duplicate open PR #52's
connectivity/audio/native-thread changes or PR #53's CI controller changes.

## Demonstrated defect and Android contract

A service retained by `startService`, or by another intent's auto-create binding,
lost its cached Binder when its last client for one intent disconnected. Its
`unbindFinished` callback was empty, and every scheduled bind used `rebind=false`.
Returning `true` from `Service.onUnbind` therefore caused another `onBind` instead
of `onRebind`, while returning `false` also discarded the original interface.
Clients could miss service resume/reconnection work or receive a new interface
where the service expected its original Binder to remain in use.

The implementation follows the project's pinned AOSP `android-16.0.0_r4`:

- [ActiveServices](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/core/java/com/android/server/am/ActiveServices.java):
  `bindServiceLocked`, `unbindFinishedLocked`, `requestServiceBindingLocked`,
  connection removal, and process-death cleanup.
- [ActivityThread](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/core/java/android/app/ActivityThread.java):
  `handleBindService` calls `onRebind` for a true rebind and does not publish a new
  Binder. `handleUnbindService` reports true via `unbindFinished(service, binding)`;
  false is reported as `SERVICE_DONE_EXECUTING_UNBIND` with only the service token.
- [Service API](https://developer.android.com/reference/android/app/Service#onRebind(android.content.Intent)):
  `onRebind` is requested by a previous true `onUnbind` result.

The pinned implementation, including its cache behavior after `onUnbind(false)`,
is the source of the detailed expectations below. No new `onBind` is needed while
the same service incarnation retains its published interface.

| Transition | Result |
| --- | --- |
| First client for an intent | One `onBind`; publication may contain a non-null or null Binder |
| Additional client | Deliver the cached publication without another `onBind` |
| Last client leaves | One `onUnbind`; preserve the publication while the service survives |
| `onUnbind` returns false | Later clients reuse the cache; no `onRebind` |
| `onUnbind` returns true with no current clients | Remember a rebind for the next client |
| Client arrives before true acknowledgement | Deliver cache immediately; schedule `onRebind` when acknowledgement arrives |
| Service destroyed or process dies | Invalidate queued operations, cache and rebinding state; a new incarnation starts with `onBind` |

## Implementation boundaries

Publication (`requested`, `received`, Binder value) and lifecycle (`hasBound`,
`doRebind`) have separate state. A published null remains distinct from a bind
which has not returned yet, preserving the earlier `onNullBinding` repair.

A per-service queue serializes bind and unbind submissions without holding the
service monitor during application calls. This prevents a concurrent unbind, or
a synchronous local callback, from overtaking its corresponding bind submission.
Each queued call records its application thread and service generation; process
retirement discards old work. Dispatch clears and restores the calling identity.
Ordinary destruction waits for the active dispatcher, then rechecks whether a
new owner has arrived. Explicit force-stop invalidates queued callbacks while
the in-flight submission finishes. A local scheduling exception is propagated
after reentrantly queued work has drained, so it cannot strand a final unbind.

True acknowledgements identify their binding. False acknowledgements use the
pending-unbind dispatch order because AOSP ActivityThread processes these
callbacks on one main Looper and its false-result API contains no binding token.
True acknowledgements for a retired record, or with no unbind currently pending,
do not create another rebind. The AIDL callback contains no cycle sequence: an
artificially repeated old acknowledgement cannot be distinguished from the
current one once another unbind for the same binding is pending. Normal pinned
ActivityThread does not produce that sequence. A client which unbinds
synchronously from its connection callback does not receive a spurious rebind
request after it has left.

When a client process disappears, its last connection also requests an unbind of
a surviving service. Service-process death, explicit stop and cached retirement
clear the new queue/cache state alongside the existing service lifetime cleanup.

## Regression evidence

`tools/test_service_rebinding.py` compiles the current production
`ActiveServices.java` and runs `ServiceRebindContractTest` against real framework
classes and LoadedApk's `ServiceConnection` dispatcher. The host supplies only
native initialization/identity boundaries and constructed package/process
metadata; it does not substitute the service state machine.

The initial implementation passed **234 checks**. Independent QA expanded the
suite to **431 passing checks**, covering retained and null Binders,
true/false/delayed acknowledgements, multiple clients and intent bindings,
reentrant callbacks, controlled concurrent dispatch, service destruction,
replacement ownership, external client death, force-stop, cached-process
retirement and process restart. The adjacent null-publication suite passes
**65 checks** against the corrected production source.

The reviewer reproduced two defects missed by the original suite. A final
auto-create client leaving while a bind submission was in flight allowed stop
and exit to overtake that submission and discard its queued unbind. A local
scheduling exception could also leave reentrant unbind work queued without a
dispatcher. The corrected implementation drains ordinary lifecycle work before
teardown, retains replacement owners, cancels force-stopped queued calls, and
preserves the original exception after draining. Both new controls fail against
the pre-QA local control snapshot at their intended assertions:
`ordered-destroyed` and `failed-local`.

The same new runner with `--source-ref 74af63a1` fails independently at the intended
assertions in these modes:

| Mode | Old-source failure |
| --- | --- |
| `--scenario retained` | True unbind result does not request `onRebind` |
| `--scenario declined` | False unbind result loses the original cached Binder |
| `--scenario ordered` | Concurrent unbind overtakes an in-flight bind submission |

The DEX fixture's entry point is
`gesher.services.ServiceRebindContractTest`; its success marker is
`Service rebinding contracts passed:`. Its normal entry point uses actual Binder
Parcel dispatch through the generated `IActivityManager` stub and real LoadedApk
callbacks. A required Windows/ART execution of that fixture complements the host
test. The host result alone is not an ART, cross-process, physical-device or
universal-APK compatibility claim.

## Mapping notes and remaining work

The Java review followed app bootstrap and class-path packaging, Android module
overlays, service admission/publication, process retirement, and existing SDK and
service inventories. The `app/java/com/google` classes contain client-SDK fixture
stubs and are explicitly excluded from the runtime boot JAR; defects in their
Tasks aggregation are therefore not counted as shipped runtime defects here.

The broader SDK missing-class/member queues, multi-activity back stacks, service
foreground-policy coverage, and other backend contracts remain separate work.
The new focused tests establish these service transitions, not complete semantic
coverage of every Java source or every Android application.
