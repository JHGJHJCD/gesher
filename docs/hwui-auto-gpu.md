# Automatic D3D11 hardware GPU rendering for Windows apps

This change makes windowed Android applications prefer the **existing**
SkiaGL → ANGLE → Direct3D 11 HWUI pipeline on supported hardware, including
Intel UHD/Iris Xe/Arc graphics. It does **not** add a new renderer, reimplement
Android graphics, or copy closed-source Microsoft WSA code.

## Renderer selection

- Default, graphical process (GESHER_APP_WINDOW=window or hidden): probe ANGLE.
  Use SkiaGL only if an EGL context and Skia GL interface initialize and
  GL_RENDERER identifies supported D3D11 hardware.
- Default, headless/CLI/system service: stay on SkiaCpu without ANGLE probing.
- GESHER_HWUI_RENDERER=skiacpu: always request the CPU pipeline.
- GESHER_HWUI_RENDERER=skiagl: explicitly request GL, including WARP for tests.
- GESHER_HWUI_RENDERER=auto: probe hardware, falling back to SkiaCpu.
- debug.hwui.renderer has the same options unless overridden by the environment.
- GESHER_EGL_DEVICE=warp forces ANGLE's software renderer; auto selection
  uses SkiaCpu instead of treating WARP as a hardware GPU.

A successful GPU probe may still be backed by Microsoft Basic Render Driver,
so auto mode rejects that and other known software backends using the actual
GL_RENDERER. A conservative vendor check recognizes Intel, AMD/ATI, NVIDIA,
Qualcomm, ARM and Imagination hardware; other devices can opt in using skiagl.
The current ANGLE build targets Direct3D 11, not Vulkan or DirectX 12.

The renderer is selected once per process, before framework graphics natives
initialize. Existing JNI, Skia, Window/Surface and EGL interfaces are unchanged.

## Validation and caveats

The host test runs with python3 tools/test_hwui_renderer_policy.py and compiles
both the policy and the *real* pipeline_select.cc with small test stubs.
The Windows graphics track additionally checks that auto mode on WARP stays
on SkiaCpu. Neither test substitutes for a physical Intel GPU benchmark.

Run Windows CI and test at least Intel integrated graphics, discrete GPUs, and
WARP/basic-driver machines. Compare first frame, scroll frame-time p50/p95,
frame drops, GPU/CPU usage, driver resets, power use and peak memory with
GESHER_HWUI_RENDERER=skiacpu and automatic/explicit skiagl.

Known limitations:
- SkiaGL has less coverage than SkiaCpu, including some TextureView/
  HardwareBitmap paths; broad application compatibility is not yet proven.
- Translucent popups and child EGL surfaces may still use glReadPixels to
  transfer frames to CPU memory; this change does not remove those copies.
- Switching rendering pipelines dynamically after device loss is unsupported.
- GPU use does not prove a speedup on all integrated drivers or apps.
- Testing on a machine with a real Intel GPU is still required before merge.

## WSA comparison

WSA had high-performance, power-saving and specific-GPU preferences, as
documented by Microsoft, but its GitHub WSA repository does not provide an
open-source graphics runtime. Gesher already uses open-source ANGLE and supports
hardware or WARP D3D11 devices. Implementing DXGI adapter selection and per-GPU
power preferences should be a separate change once device tests justify it.
