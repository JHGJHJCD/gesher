# Native media compatibility corrections (palmoni5)

Baseline: `e0dbabf8932df2c101d8ed3d28a8c560df771dc0`.
The reference contracts below use AOSP `android-16.0.0_r4`.

## Audio effects without a native engine

The WASAPI mixer has no Android effect factory. Previously reflective native
stand-ins returned success from AudioEffect and Visualizer setup. Real framework
constructors accepted that answer, even though no effect ID/descriptor or capture
engine had been created. Setup now returns `ERROR_INVALID_OPERATION` (-5), which
those constructors translate into `UnsupportedOperationException`. Cleanup is
idempotent; operations on an absent native engine answer `ERROR_NO_INIT`, false,
or the corresponding native getter sentinel.

`AudioEffect.queryEffects()` returns an empty **Descriptor[]**, preserving the
Java cast and making availability checks safe. No default preprocessing is
attached to a session, so `queryPreProcessings()` returns null, matching its AOSP
native no-effects branch. Visualizer's static capture bounds (128..1024) and
maximum rate (20000 millihertz) describe the API; they do not advertise an engine.

Sources:

- [AudioEffect.java](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/java/android/media/audiofx/AudioEffect.java)
- [Visualizer.java](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/java/android/media/audiofx/Visualizer.java)
- [AudioEffect JNI](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/audioeffect/android_media_AudioEffect.cpp)
- [Visualizer JNI](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/audioeffect/android_media_Visualizer.cpp)
- [Visualizer constants](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/media/jni/audioeffect/Visualizer.h)
- [Capture-size constants](https://android.googlesource.com/platform/system/media/+/android-16.0.0_r4/audio/include/system/audio_effects/effect_visualizer.h)

### Validation and integration

Run `tools/test_audio_effect_contracts.py --framework-classes <framework-classes.zip>`
with a JDK (`JAVA_HOME`). It compiles the **verbatim production JNI functions and
tables**, then runs the unmodified built framework AudioEffect and Visualizer classes with `-Xcheck:jni`. The declared host replacements are only
attribution/Parcel and logging, because Android Binder is unavailable in the host
JVM. JNI registration is deferred to each class's native initializer to avoid the
host JVM's recursive library-initialization restriction.

The suite checks typed enumeration, false availability, static metadata, actual
public constructor errors, and finalizers after failed construction (24 checks).
`--negative-control` restores the old zero-success setup answer in a disposable
build: the unchanged constructor assertions must fail. No source is rewritten.

`gesher.services.AudioEffectAvailabilityTest` is a separate public API fixture
for the real framework/ART/Windows runtime, built by the normal dex-services
build. A host pass does not establish Windows effect processing, which remains
unsupported, or qualify every Android audio effect implementation.
