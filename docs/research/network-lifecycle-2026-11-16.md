# Network query and registration lifecycle audit — 2026-11-16

This follow-up starts at `99344ef805b2503f14ba37c2db723e8a14d700dd` and does not
repeat PR #51's socket/DNS, Activity, null-service-binding or Bluetooth repairs.
It repairs four observable defects in the existing connectivity implementation.

## Confirmed defects and changes

| Boundary | Before | Repair |
| --- | --- | --- |
| Queries without listeners | `ConnectivityService` cached the first Windows snapshot, but no watcher started until a listener or sticky-broadcast query arrived. Polling an offline-at-start app could remain offline after reconnection. | Read a fresh adapter snapshot while no watcher owns the cache. Watched queries retain the event-published cache. Query-only use creates no background thread. |
| Initial subscription | The manager read a snapshot before attaching its listener. A change between those calls could be missed indefinitely. A partly failed attachment could retain a remote listener. | Subscribe before reading the initial snapshot. Preserve newer same-process Binder publications using a generation counter. Retire failed attachments outside the registry lock and permit clean retry. |
| PendingIntent replacement | Release, callback registration and token publication were separate operations. Overlapping replacements could leave a callback with no owning token and duplicate delivery. | Replace the token and exact callback in one registry transaction; retire the old callback without churning the shared service listener. Publish ownership before scheduling initial delivery. |
| PendingIntent cancellation | A previously admitted send could fail after replacement, then cancel the new request by token alone. | Internal failed-send cleanup checks the exact callback generation. Explicit public release still removes the current token owner. |

The query correction deliberately performs one native snapshot enumeration per
query when no watcher is running. This replaces an indefinitely stale answer;
it is not claimed as a performance improvement. Once a watcher is active, the
existing cache prevents repeated enumeration.

## Pinned Android reference

The actual AOSP Connectivity repository was read at
[`android-16.0.0_r4`](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/tags/android-16.0.0_r4),
commit `f930245ec39a510f37a9a7dfaded96d287491a61`.

- [`ConnectivityManager`](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/tags/android-16.0.0_r4/framework/src/android/net/ConnectivityManager.java)
  documents the current active network, replacement of an existing PendingIntent
  request, and explicit release. Its blob is
  `1a07d33f0740bdba8b86a68f8f7ee3a0cb06b8ee`.
- [`ConnectivityService`](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/tags/android-16.0.0_r4/service/src/com/android/server/ConnectivityService.java)
  reads current service state for `getActiveNetwork`; handler messages serialize
  `handleRegisterNetworkRequestWithIntent` and
  `handleReleaseNetworkRequestWithIntent`. Its lookup assumes one request per
  pending intent. Its blob is `27f6dd357d5b6d3283ee6456d52c5dd99bbf4897`.

These references justify freshness and ownership requirements; Gesher's
Windows-snapshot transport remains its own implementation.

## Regression evidence

`tools/test_connectivity_callback_contracts.py` compiles the actual changed
manager and service against the pinned platform classes and freshly built main
app classes. Its default run passes **57 callback/registration assertions** and
**nine service-query assertions**. Existing identity, timeout, callback reuse
and unlocked teardown checks remain in the same suite.

New cases exercise attachment gaps, reentrant initial publications, failed
partial attachment and retry, same-token replacement, old-send cancellation,
two concurrent replacements, public PendingIntent extras, and query-only
offline/reconnect/default-interface/disconnect transitions.

The four old-source controls each fail at the intended behavioral assertion:

| `--case`, with `--source-ref 99344ef805b2503f14ba37c2db723e8a14d700dd` | Observed failure |
| --- | --- |
| `freshness` | Query-only reconnect still reports no active network. |
| `subscriptions` | A change before attachment never reaches the new callback. |
| `pending` | Interleaved same-token replacement retains more than one callback. |
| `pending-cancel` | Cancellation of an old in-flight send retires its replacement. |

Run with `--java-home`, `--framework-classes`, `--core-classes` and
`--app-classes`, as the existing framework CI invocation does. The default
Windows `gesher.services.ConnectivityCallbackContractTest` also includes the new
registration cases, keeps the existing success marker, and retains its real
Binder Parcel and HandlerThread checks.

The host uses controlled scheduling/service objects, an ActivityManager IPC
observer for the real `PendingIntent.send`, and a JNI adapter-snapshot boundary
for the real service. It does not claim Windows network discovery, physical
adapter changes, process-lifetime persistence or live Binder transport. Those
remain separate hosted/platform execution evidence.

## Explicit remaining gaps

- `addDefaultNetworkActiveListener` and its removal method remain empty. Network
  availability is not traffic activity; a Windows activity source and accurate
  cancellation/executor behavior are required.
- PendingIntent registrations are still process-local. Android's comparison of
  underlying intents across distinct tokens, post-delivery delayed release and
  persistence after client process exit need a server-owned request design.
  This change fixes ownership of the same token and does not claim those wider
  semantics.
- Full mDNS discovery and Wi-Fi radio management are not supplied by these
  repairs. The existing SDK/member inventories list additional missing entry
  points; API presence alone would not establish a working hardware backend.

Independent QA and the final combined PR's hosted CI are recorded by the main
audit; the local evidence above is not a substitute for those gates.
