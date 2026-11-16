# Native-window dispatch contracts — 2026-11-16

Baseline: `74af63a1fd7cb10c508ea21ebf013ec0aff38504`. This scope does not
overlap the production files in open PRs #52 or #53. Attribution: **palmoni5**.

## Confirmed differences and repairs

The pinned [Android 16 r4 NDK implementation](https://android.googlesource.com/platform/frameworks/native/+/android-16.0.0_r4/libs/nativewindow/ANativeWindow.cpp)
distinguishes a producer's current dataspace from the consumer's default. Gesher
previously made `ANativeWindow_getBuffersDefaultDataSpace` an alias for the
current-dataspace query. It now dispatches `NATIVE_WINDOW_DEFAULT_DATASPACE`.

The same source checks null/invalid windows before transform, dataspace and
frame-rate calls. Those checks were absent in Gesher. They are now applied to
the matching entrypoints, retaining the older geometry/lock contracts unchanged.
Transform admission permits all combinations of horizontal/vertical mirror,
90-degree rotation and the camera's inverse-display bit; unknown bits return
`-EINVAL` without reaching the receiver. Query and receiver errors propagate.
The simple frame-rate API delegates with the pinned seamless-change strategy.

The public [NDK contract](https://developer.android.com/ndk/reference/group/a-native-window)
also documents the consumer default, valid transform flags and typed error
results. The pinned source, rather than a newer SDK implementation, determines
this change's behavior. In particular, it does not add a new policy for a
negative `NATIVE_WINDOW_IS_VALID` query result.

## Evidence and execution gates

`tools/test_native_window_dispatch.py --sanitize` compiles the production NDK
entrypoint section with declared native-header/dispatch substitutes. A recording
receiver exposes separate producer/consumer values, rejected mutations and
backend errors. **65 assertions pass.** The same assertions against the baseline
with `--source-ref 74af63a1fd7cb10c508ea21ebf013ec0aff38504 --skip-null` produce
**17 expected failures among 59 assertions**. The old-source run omits null calls
to isolate contract failures without terminating at the old null dereference.

The shared assertion header is also part of
`media_surface_geometry_contract_test`, linked against the actual Windows/AOSP
implementation. Windows track 96 requires its success marker and exit status.
The Linux sanitizer check is mandatory in `ci.yml`. Local leak detection was
disabled only in the local ASan invocation because of the executor's ptrace
limitation; the committed CI runner retains its normal sanitizer settings.

### Independent header and compiler review

The shared fixture initially passed literal null pointers to `_Nonnull`
parameters in the pinned
[NDK header](https://android.googlesource.com/platform/frameworks/native/+/android-16.0.0_r4/libs/nativewindow/include/android/native_window.h).
This caused six `-Wnonnull` errors under the Windows target's `-Werror` policy,
which the simplified Linux header boundary did not expose. The null checks now
read a volatile pointer containing null, preserving the runtime negative cases
and all compiler warnings.

Independent QA compiled the production wrapper section and shared assertions
with the unmodified Android 16 r4 native-window, native-base, rectangle, cutils
and system graphics headers, Gesher's existing cdefs adapter, and llvm-mingw
20261006 targeting x86-64 Windows. With the target's C++23 warning settings, the
original fixture produced the six errors and the corrected fixture passed.
This is a header/target syntax check; Windows execution remains the separate
track 96 gate. The Linux sanitizer suite still passes all 65 checks.

## Limits

This repairs the public dispatch boundary. The receiver fixture is not a display
or color converter. `QueueSurface` and `Win32Surface` still have incomplete
dataspace/transform/frame-pacing behavior; forwarding a valid request does not
prove those backend capabilities. The geometry fixture exercises actual Windows
surfaces, but the new dispatch assertions use a recording native-window receiver
even in the Windows build. Neither test establishes physical color accuracy,
rotation fidelity or refresh-rate selection.
