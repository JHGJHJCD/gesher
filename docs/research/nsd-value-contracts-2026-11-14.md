# NSD value contracts and Android 16 Parcel layout

Baseline: `e0dbabf8932df2c101d8ed3d28a8c560df771dc0`.

The previous local `NsdServiceInfo` accepted TXT records beyond Android's
per-entry and aggregate bounds. Its Parcel writer emitted only address bytes;
its reader reconstructed those with `InetAddress.getByAddress`, losing scoped
IPv6 interface IDs and converting IPv4-mapped IPv6 addresses to IPv4 objects.
It also used a local field order different from the pinned Android class.
A missing interface scope can make a valid link-local service address unusable.

## Implementation decision

Import the complete `NsdServiceInfo` value implementation from
[AOSP Android 16 r4](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/tags/android-16.0.0_r4/framework-t/src/android/net/nsd/NsdServiceInfo.java),
retaining its Apache 2.0 license. Decoded upstream SHA-256:
`8464dac07403b2f5d41b57366246e97eaacc0bc149935abbe2f4b27dee5bfff7`.

The only adaptations remove the `Flags` and `FlaggedApi` imports and three
build-time `@FlaggedApi(Flags...)` annotations, and add the explanatory Gesher
comment. No runtime branch or value/Parcel operation is altered. Imported source
SHA-256: `7f1dc9d508dd189211ec0a3cd36d1de07ff9ce7712ed3b155a13782c2170b529`.

Both the compiled framework inputs and its actual DEX contain
`com.android.net.module.util.HexDump`, so the original formatter is retained.
The checked framework JAR SHA-256 is
`ac3cdc18d41e03ae5835f841ac08b63ebc8e06828877a5201b920b3efea0fa9f`, from
[main CI run 43622672102](https://github.com/gesher-runtime/gesher/actions/runs/43622672102).
The framework DEX also contains `InetAddressUtils` and precedes the local app
copy on the boot classpath. That framework provider supplies the pinned IPv6
scope and explicit mapped-address reconstruction; the local same-name shim is
not the selected runtime provider for this build.

This keeps the coherent data family together: TXT validation/encoding/decoding,
legacy nullable host semantics, detached address lists, interface metadata,
expiration precision, public-key value ownership and the complete Parcel schema.
The Parcel layout now follows the pinned class, including port position, nullable
TXT value encoding, network/interface position, IPv6 scope marker, hostname,
expiration and public-key fields. It intentionally replaces the older local
layout; it is not a persistent data migration format or mixed-version runtime
IPC protocol. No arbitrary Android-version wire-compatibility claim is made.

Pinned details are preserved rather than silently changed: nine-character TXT
keys are only recommended, exact aggregate size 1300 is accepted, and replacement
validation includes the old record in the prospective size calculation. Hidden
metadata members introduced by the complete import are not counted as newly
restored public SDK declarations. The NSD discovery/advertisement backend remains
unchanged; this repair does not add mDNS networking.

## Verification and boundaries

`tools/test_nsd_value_contracts.py` compiles `NsdValueContractTest` directly against
the official SDK36 JAR. Public call sites therefore bind to SDK descriptors;
hidden metadata probes explicitly use reflection. Production `NsdServiceInfo`
then runs against real framework/core/app classes. Only the existing native
initialization/logging adapter is provided on Linux; there is no fake Parcel or
value-class substitute.

The candidate passes **41 host checks**. Separate baseline controls report:

| Case | Baseline result |
| --- | --- |
| `entries` | Accepts a 255-byte key-plus-value entry that Android rejects. |
| `total` | Accepts total TXT size beyond 1300 bytes. |
| `values` | Loses the legacy `setHost(null)` nullable address entry. |
| `complete` | Lacks hidden `setTxtRecords(byte[])` metadata operation; this is not a public-SDK linkage claim. |

Additional assertions cover UTF-8 byte limits, unchanged previous values after a
rejected replacement, key character rules, nullable versus empty TXT values,
address snapshots, raw TXT encoding/decoding, binary formatting, public-key
copies, expiration precision and interface metadata. The fixture is compiled in
full locally, including its Windows-only native Parcel path.

On Windows the same fixture requires `android_runtime` and actual Parcel
operations. It checks scoped IPv6, IPv4-mapped IPv6 type, nullable hosts, UTF-8
TXT metadata and complete consumption through a following sentinel. A separately
written field-order reader checks the production writer against the pinned
schema, and a separately encoded object checks the production reader. These
complement round trips so matching writer/reader mistakes cannot suffice.
No native Parcel execution or Android-device differential run is claimed for the
local Linux results; the PR's actual Windows CI run provides that evidence.

## Reproduce

```sh
python3 tools/test_nsd_value_contracts.py \
  --java-home /path/to/jdk21 --sdk-jar /path/to/android.jar \
  --framework-classes /path/to/framework-classes.zip \
  --core-classes /path/to/coremod \
  --app-classes /path/to/gesher-app-classes.jar
```

For each baseline control add `--source-ref e0dbabf8932df2c101d8ed3d28a8c560df771dc0`
and `--case entries`, `total`, `values` or `complete`. The Windows test class is
`gesher.services.NsdValueContractTest` with no arguments; its success marker must
include `parcel=true`.
