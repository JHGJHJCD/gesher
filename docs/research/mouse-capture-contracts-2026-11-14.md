# Windows mouse capture and Android gesture cancellation

Baseline: `e0dbabf8932df2c101d8ed3d28a8c560df771dc0`.

## Failure and correction

The Windows message handler retained its button mask until button-up and did
not handle `WM_CAPTURECHANGED` or `WM_CANCELMODE`. If another window acquired
capture, or a modal operation canceled it, the button-up could go elsewhere.
The Android input translator then retained pressed buttons, and popup routing
retained the previous gesture target. Another press of the same button could
be ignored as a repeated press of the unfinished gesture.

The handler now clears active mouse buttons and queues a distinct
`kMouseCancel` event. It leaves `WM_CANCELMODE` default handling intact, so
Windows can release capture and cancel its internal menu/scroll tracking.
It never reacquires capture from `WM_CAPTURECHANGED`.

Popup routing sends cancellation to the gesture's original target, then
retires that target when no touch remains active. The translator emits
`ACTION_CANCEL` with `FLAG_CANCELED`, the original down time and the last
position, and clears mouse button/hover/movement history. Duplicate
cancellation and a later stale button-up are ignored. A subsequent press or
same-position hover can start normally.

A normal final button-up clears the Windows button mask before calling
`ReleaseCapture`, so its synchronous capture-change notification does not
generate a spurious cancellation. This preserves ordinary click behavior.

The first full Windows runs exposed a further integration error in a two-button
chord. The handler called `SetCapture(hwnd)` again on an additional button press
or nonfinal release. Windows can notify the previous owner even when the new
owner is that same HWND; treating every such notification as capture loss
prematurely canceled the gesture. The handler now ignores a
`WM_CAPTURECHANGED` whose `lParam` names itself and avoids redundant capture
calls when `GetCapture()` already names that window. Actual capture loss and
`WM_CANCELMODE` still cancel. The existing Java chord and handled-completion
assertions remain unchanged.

## Evidence and limits

`tools/test_mouse_capture_contracts.py` compiles the exact production mouse
translation methods, message-handling branches, and popup routing methods.
Win32 capture delivery, coordinate conversion, unrelated key/touch handling,
and Android event storage are explicit host adapters. The capture adapter
delivers synchronous notifications, including the reentrant notification from
normal `ReleaseCapture`, same-owner `SetCapture`, and the default processing of
`WM_CANCELMODE`. The original adapter had incorrectly suppressed same-owner
notifications; the native Windows failure prompted this correction.

The candidate passes 25 assertions. The same fixture against the baseline
fails 12 of its 21 reached assertions; four additional checks depend on an
emitted cancellation and therefore are reached only by the corrected source.
Against the earlier PR revision `c22d7c9c0a931cae0bd8a37bd56e06764a3cd993`,
the identical updated fixture fails six of 25 assertions. Independent QA
reproduced that control and the candidate's 25 successful assertions under
ASan/UBSan. All 20 prior assertions remain, with five additional chord and
same-owner checks.
The existing physical-key fixture passes its 50 assertions after adding the
new event constant to its declared event adapter.

Local commands:

```sh
ASAN_OPTIONS=detect_leaks=0 python3 tools/test_mouse_capture_contracts.py --sanitize
python3 tools/test_mouse_capture_contracts.py --source-ref e0dbabf8932df2c101d8ed3d28a8c560df771dc0
ASAN_OPTIONS=detect_leaks=0 python3 tools/test_mouse_capture_contracts.py --sanitize \
  --source-ref c22d7c9c0a931cae0bd8a37bd56e06764a3cd993
ASAN_OPTIONS=detect_leaks=0 python3 tools/test_input_key_contracts.py --sanitize
```

LeakSanitizer cannot run under this executor's ptrace environment; only local
leak detection is disabled. AddressSanitizer and UndefinedBehaviorSanitizer
remain enabled, and CI retains its default leak settings.

The existing Windows `input_probe` now adds two complementary gates: actual
Android event-container translation and hidden native Windows message/routing
execution. The latter posts a mouse press, changes the route, sends
`WM_CANCELMODE`, and verifies exactly one cancellation at the original target,
followed by a normal click at the new target. A posted keyboard event is the
ordered message-loop barrier. Four more checks exercise a two-button chord and
an explicit same-owner notification on the native window/message/routing path.
These run through the existing track 33 on both Windows runners, alongside the
unchanged Java `InputEventReceiver` chord and completion assertions. The complete
window message-handler unit also passes strict x64 and ARM64 MinGW syntax
checks. Host-adapter success
alone does not establish native Windows
execution or physical pointing-device coverage; the pull request's CI records
that separate result.

This change does not redesign simultaneous mouse/touch routing, pointer capture
requested by an Android app, hardware touch injection or stylus behavior.

## Primary references

- [WM_CAPTURECHANGED](https://learn.microsoft.com/en-us/windows/win32/inputdev/wm-capturechanged):
  capture loss notifications include a window's own `ReleaseCapture` call, and
  the handler must not reacquire capture in response. `lParam` identifies the
  new capture owner.
- [Wine's pinned Windows message-contract test](https://github.com/wine-mirror/wine/blob/9f8c4ae4d35c7ff4153267aba9618c026f5f5583/dlls/user32/tests/msg.c):
  `test_DoubleSetCapture` expects `WM_CAPTURECHANGED` after repeated capture by
  the same window.
- [WM_CANCELMODE](https://learn.microsoft.com/en-us/windows/win32/winmsg/wm-cancelmode):
  default processing releases capture and cancels internal menu/scroll modes.
- [MotionEvent.ACTION_CANCEL](https://developer.android.com/reference/android/view/MotionEvent#ACTION_CANCEL):
  cancellation terminates the gesture and carries `FLAG_CANCELED`.
