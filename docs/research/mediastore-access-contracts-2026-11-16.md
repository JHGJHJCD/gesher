# MediaStore access and URI conversion contracts

## Scope and source pin

This change adds five public SDK 36 method descriptors to the existing
`android.provider.MediaStore` facade. An app invoking any of them on baseline
`74af63a1fd7cb10c508ea21ebf013ec0aff38504` encounters `NoSuchMethodError`.
The implementation follows MediaProvider's Android 16 framework contract and
uses Gesher's existing framework clients and services.

The reference is AOSP MediaProvider tag `android-16.0.0_r4`, peeled commit
`217515852d78543d1d7da39bd69d4e03957ee118`,
[`apex/framework/java/android/provider/MediaStore.java`](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/217515852d78543d1d7da39bd69d4e03957ee118/apex/framework/java/android/provider/MediaStore.java),
Git blob `76f76cd27a89b1b9207c400e4769072a0a8ae36f`.
The existing facade retains the AOSP Apache-2.0 notice.

| Added API | Preserved contract |
| --- | --- |
| `canManageMedia(Context)` | Queries the `MANAGE_MEDIA` app-op with the context's UID and operation package; only `MODE_DEFAULT` falls back to `checkPermission` with the current PID and that UID. `MODE_ALLOWED` permits, ignored/errored/unknown modes deny. |
| `getDocumentUri(Context, Uri)` | Acquires a stable `media` provider client and calls `get_document_uri` with the input `uri` and an independent `uriPermissions` list containing the caller's existing persisted grants. |
| `getMediaUri(Context, Uri)` | Uses the same grant and client protocol with `get_media_uri`. The provider determines the equivalent URI and any associated access. |
| `getRedactedUri(ContentResolver, Uri)` | Selects the provider using the original authority, strips a user qualifier only inside the request URI, calls `get_redacted_media_uri`, and restores the original user qualifier on the reply. |
| `getRedactedUri(ContentResolver, List<Uri>)` | Verifies a single user identity, selects the first URI's authority, calls `get_redacted_media_uri_list` with `uri_list`, and preserves provider reply order and duplicates while restoring the first URI's user qualifier. |

The API additions do not implement a media database, EXIF transformation,
photo-picker UI, or a new permission service. Actual conversion and redaction
depend on an installed provider implementing these calls. In particular,
Gesher's existing `DeviceServices.AppOpsService` has a broad allow policy;
`canManageMedia` faithfully consumes that service's answer. This change does
not establish an Android-equivalent per-application app-op policy.

## Failure and ownership behavior

Provider replies are returned as supplied. Security and argument exceptions
propagate. A `RemoteException` becomes a runtime exception retaining its cause.
Every acquired stable client closes on both success and failure. No new grants
are synthesized by the facade.

The implementation also preserves edge behavior of the pinned source:

- An unavailable provider leaves a null client and fails when the operation is
  invoked; no provider result or resource release is invented.
- A null result bundle fails. A missing or null URI value can remain null for
  an unqualified URI. A qualified redaction reply containing null fails in
  `ContentProvider.createContentUriForUser`; a null qualified batch reply also
  fails. These pinned-source edge cases are covered explicitly.
- URI conversion forwards a null input URI to the provider. Redaction of a
  null URI fails before provider acquisition.
- Empty batches fail at the first-element access. Mixed user IDs are rejected
  before acquiring a provider; implicit and explicit current-user IDs are the
  same identity. Input lists and URIs are not mutated.

These details are compatibility decisions, not guarantees that an absent or
nonconforming MediaProvider can satisfy the API.

## Verification

`tools/test_media_store_access.py` compiles `MediaStoreAccessSdkCalls.java`
against **only the public SDK 36 jar**. It then compiles the production facade
and `MediaStoreAccessContractTest.java` against the runtime inputs and executes
the public SDK call sites. Thus the test checks the actual method descriptors
used by apps as well as behavior.

The fixture uses the real framework `AppOpsManager`, `ContentResolver`,
`ContentProviderClient`, `Uri`, `UriPermission`, `Bundle`, and permission-to-op
mapping. A controlled `IAppOpsService` supplies policy decisions and an
`IContentProvider` proxy supplies provider replies. API 36's shared app-op
service is replaced only for the fixture and restored afterward; distinct
request identities avoid stale cached answers. The framework's cache logic
and manager are unchanged.

Coverage includes all app-op modes and permission fallback, caller identity,
persisted-grant snapshots, exact call envelopes, user routing, reply nulls,
denial and remote errors, pre-provider validation, and stable-client release
counts. The test main also loads the real `android_runtime` library when run
as a Windows ART dex fixture.

Host execution uses the existing `tests/location/framework_host.c` adapter for
native initialization and identity, and loads the actual Android core Cleaner
classes through the host JDK's `jdk.unsupported` module. Host execution does
not test remote Binder, Parcel wire encoding, real media contents, Windows
filesystem permissions, or a running MediaProvider. A passing Windows fixture
adds ART/native-initialization coverage but still uses controlled service and
provider boundaries.

Example invocation with prepared, matching runtime artifacts:

```sh
python3 tools/test_media_store_access.py \
  --java-home "$JAVA_HOME" \
  --sdk-jar work/sdk-36/android.jar \
  --framework-classes out/framework/classes \
  --core-classes out/coremod \
  --app-classes work/app/gesher-app-classes.jar
```

`--case management|document|media|redacted|redacted-list` selects one API.
`--source-ref <commit>` compiles that revision's production facade while
preserving the SDK-compiled invocation, providing a linkage negative control.
The runner prints the exact production source SHA-256.

Local results for this change:

- The current implementation passed **202 checks** against real framework
  classes and the freshly built baseline app jar.
- Each of the five baseline cases failed with `NoSuchMethodError` at its exact
  SDK-compiled call site, rather than at fixture setup or compilation.
- The existing `tools/test_media_store_requests.py` regression passed
  **208 checks** with the modified facade.

## Remaining three open helpers

The SDK 36 `openFileDescriptor`, `openAssetFileDescriptor`, and
`openTypedAssetFileDescriptor` descriptors remain explicitly open. The pinned
source uses ordinary `ContentResolver` opens for ordinary and local-picker
URIs, but routes cloud-picker URIs through a separate asynchronous provider
protocol. Delegating all URIs to the resolver would misrepresent that contract.

A complete follow-up must bring over and verify at least these six support
types from the same pinned project:

- `OpenFileRequest` and `OpenAssetFileRequest`, with exact Parcelable layouts.
- `android.provider.ParcelableException`, distinct from `android.os.ParcelableException`.
- `IOpenFileCallback`, `IOpenAssetFileCallback`, and `IMPCancellationSignal`,
  with their exact Binder descriptors, transactions, and one-way semantics.

It must also implement authority/scheme validation, picker URI routing,
read-only enforcement, asynchronous success/error delivery, typed exception
rethrow, remote cancellation creation/forwarding/listener removal, client
closure, and descriptor ownership. The current application build compiles
Java sources, so the AIDL-derived interfaces require a reproducible generation
step or verified generated sources. Parcel round trips and remote callback,
cancellation, failure, and descriptor-lifetime tests are required before that
protocol can be claimed complete.

## Open-PR overlap

At review time the repository's open PRs were #52 at
`4928f662bac4da5deadf5caec22bcb73f1896338` (connectivity ownership, AAudio lifetime,
pthread/guest stacks, and broad audit evidence) and #53 at
`c4a9e846d67a4cb034322cf9338a513eee882c4c` (CI approval/merge policy).
Neither changes MediaStore or these new fixture paths. Shared CI and inventory
integration is coordinated separately to avoid duplicating their scope.
