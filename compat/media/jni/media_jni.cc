// Gesher: the media JNI libraries the framework loads by name, as builtin
// libraries (docs/media.md): libmedia_jni.so (MediaPlayer and the rest of
// android.media's player, codec and metadata classes), libsoundpool.so
// (SoundPool) and libaudioeffect_jni.so (AudioEffect, Visualizer: unavailable).
// Each JNI_OnLoad registers stand-ins for every native of its classes, then
// Gesher's.

#include <jni.h>

#include <gesher/builtin_library.h>

#include "../audio/log.h"
#include "jni_util.h"
#include "media_jni.h"

namespace gesher::media {
namespace {

JNIEnv* EnvOf(JavaVM* vm) {
  JNIEnv* env = nullptr;
  return vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK ? env : nullptr;
}

// --- Unavailable audio effects ------------------------------------------------------------
// There is no effect factory in the WASAPI mixer. A successful setup would
// publish an initialized Java object with no native engine or Descriptor.
// These are the Android native contracts for an unavailable effect library,
// including static metadata that applications may query before construction.
constexpr jint kEffectNoInit = -3;
constexpr jint kEffectUnsupported = -5;

void JNICALL EffectNoop(JNIEnv*, jobject) {}
jint JNICALL EffectSetup(JNIEnv*, jobject, jobject, jstring, jstring, jint, jint,
                         jint, jstring, jintArray, jobjectArray, jobject, jboolean) {
  return kEffectUnsupported;
}
jint JNICALL VisualizerSetup(JNIEnv*, jobject, jobject, jint, jintArray, jobject) {
  return kEffectUnsupported;
}
jboolean JNICALL EffectFalse(JNIEnv*, jobject) { return JNI_FALSE; }
jint JNICALL EffectSetEnabled(JNIEnv*, jobject, jboolean) { return kEffectNoInit; }
jint JNICALL EffectParameter(JNIEnv*, jobject, jint, jbyteArray, jint, jbyteArray) {
  return kEffectNoInit;
}
jint JNICALL EffectCommand(JNIEnv*, jobject, jint, jint, jbyteArray, jint, jbyteArray) {
  return kEffectNoInit;
}
jobjectArray JNICALL EffectQuery(JNIEnv* env, jclass) {
  jclass descriptor = env->FindClass("android/media/audiofx/AudioEffect$Descriptor");
  if (descriptor == nullptr) return nullptr;
  jobjectArray result = env->NewObjectArray(0, descriptor, nullptr);
  env->DeleteLocalRef(descriptor);
  return result;
}
jobjectArray JNICALL EffectQueryPreprocessing(JNIEnv*, jclass, jint) {
  // AOSP returns null when no default preprocessing is attached to a session.
  return nullptr;
}
jintArray JNICALL VisualizerCaptureRange(JNIEnv* env, jclass) {
  // Visualizer.h, android-16.0.0_r4: metadata, not a promise of a capture engine.
  constexpr jint range[] = {128, 1024};
  jintArray result = env->NewIntArray(2);
  if (result != nullptr) env->SetIntArrayRegion(result, 0, 2, range);
  return result;
}
jint JNICALL VisualizerCaptureRate(JNIEnv*, jclass) { return 20000; }  // millihertz
jint JNICALL VisualizerSetInt(JNIEnv*, jobject, jint) { return kEffectNoInit; }
jint JNICALL VisualizerGetInt(JNIEnv*, jobject) { return -1; }
jint JNICALL VisualizerMeasurementMode(JNIEnv*, jobject) { return 0; }
jint JNICALL VisualizerGetBytes(JNIEnv*, jobject, jbyteArray) { return kEffectNoInit; }
jint JNICALL VisualizerPeak(JNIEnv*, jobject, jobject) { return kEffectNoInit; }
jint JNICALL VisualizerPeriodic(JNIEnv*, jobject, jint, jboolean, jboolean) {
  return kEffectNoInit;
}

const JNINativeMethod kUnavailableEffectMethods[] = {
    {"native_init", "()V", GESHER_NATIVE(EffectNoop)},
    {"native_setup", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;IIILjava/lang/String;[I[Ljava/lang/Object;Landroid/os/Parcel;Z)I", GESHER_NATIVE(EffectSetup)},
    {"native_finalize", "()V", GESHER_NATIVE(EffectNoop)},
    {"native_release", "()V", GESHER_NATIVE(EffectNoop)},
    {"native_setEnabled", "(Z)I", GESHER_NATIVE(EffectSetEnabled)},
    {"native_getEnabled", "()Z", GESHER_NATIVE(EffectFalse)},
    {"native_hasControl", "()Z", GESHER_NATIVE(EffectFalse)},
    {"native_setParameter", "(I[BI[B)I", GESHER_NATIVE(EffectParameter)},
    {"native_getParameter", "(I[BI[B)I", GESHER_NATIVE(EffectParameter)},
    {"native_command", "(II[BI[B)I", GESHER_NATIVE(EffectCommand)},
    {"native_query_effects", "()[Ljava/lang/Object;", GESHER_NATIVE(EffectQuery)},
    {"native_query_pre_processing", "(I)[Ljava/lang/Object;", GESHER_NATIVE(EffectQueryPreprocessing)},
};

const JNINativeMethod kUnavailableVisualizerMethods[] = {
    {"native_init", "()V", GESHER_NATIVE(EffectNoop)},
    {"native_setup", "(Ljava/lang/Object;I[ILandroid/os/Parcel;)I", GESHER_NATIVE(VisualizerSetup)},
    {"native_finalize", "()V", GESHER_NATIVE(EffectNoop)},
    {"native_release", "()V", GESHER_NATIVE(EffectNoop)},
    {"native_setEnabled", "(Z)I", GESHER_NATIVE(EffectSetEnabled)},
    {"native_getEnabled", "()Z", GESHER_NATIVE(EffectFalse)},
    {"getCaptureSizeRange", "()[I", GESHER_NATIVE(VisualizerCaptureRange)},
    {"getMaxCaptureRate", "()I", GESHER_NATIVE(VisualizerCaptureRate)},
    {"native_setCaptureSize", "(I)I", GESHER_NATIVE(VisualizerSetInt)},
    {"native_getCaptureSize", "()I", GESHER_NATIVE(VisualizerGetInt)},
    {"native_setScalingMode", "(I)I", GESHER_NATIVE(VisualizerSetInt)},
    {"native_getScalingMode", "()I", GESHER_NATIVE(VisualizerGetInt)},
    {"native_setMeasurementMode", "(I)I", GESHER_NATIVE(VisualizerSetInt)},
    {"native_getMeasurementMode", "()I", GESHER_NATIVE(VisualizerMeasurementMode)},
    {"native_getSamplingRate", "()I", GESHER_NATIVE(VisualizerGetInt)},
    {"native_getWaveForm", "([B)I", GESHER_NATIVE(VisualizerGetBytes)},
    {"native_getFft", "([B)I", GESHER_NATIVE(VisualizerGetBytes)},
    {"native_getPeakRms", "(Landroid/media/audiofx/Visualizer$MeasurementPeakRms;)I", GESHER_NATIVE(VisualizerPeak)},
    {"native_setPeriodicCapture", "(IZZ)I", GESHER_NATIVE(VisualizerPeriodic)},
};
// --- End unavailable audio effects --------------------------------------------------------

}  // namespace
}  // namespace gesher::media

extern "C" jint JNI_OnLoad_media_jni(JavaVM* vm, void*) {
  using namespace gesher::media;
  JNIEnv* env = EnvOf(vm);
  if (env == nullptr) return JNI_ERR;
  SetVm(env);
  RegisterMediaPlayer(env);
  RegisterMediaJniOthers(env);
  return JNI_VERSION_1_6;
}

extern "C" jint JNI_OnLoad_soundpool(JavaVM* vm, void*) {
  using namespace gesher::media;
  JNIEnv* env = EnvOf(vm);
  if (env == nullptr) return JNI_ERR;
  SetVm(env);
  RegisterSoundPool(env);
  return JNI_VERSION_1_6;
}

extern "C" jint JNI_OnLoad_audioeffect_jni(JavaVM* vm, void*) {
  using namespace gesher::media;
  JNIEnv* env = EnvOf(vm);
  if (env == nullptr) return JNI_ERR;
  SetVm(env);
  // No audio effects: AudioEffect's and Visualizer's natives answer as a
  // device without the effect would (setup fails, so the effect is not
  // created).
  RegisterStandins(env, "android/media/audiofx/AudioEffect");
  RegisterMethods(env, "android/media/audiofx/AudioEffect", kUnavailableEffectMethods);
  RegisterStandins(env, "android/media/audiofx/Visualizer");
  RegisterMethods(env, "android/media/audiofx/Visualizer", kUnavailableVisualizerMethods);
  return JNI_VERSION_1_6;
}

namespace {

const gesher_builtin_symbol kMediaJni[] = {
    {"JNI_OnLoad", reinterpret_cast<void*>(&JNI_OnLoad_media_jni)},
};
const gesher_builtin_symbol kSoundPool[] = {
    {"JNI_OnLoad", reinterpret_cast<void*>(&JNI_OnLoad_soundpool)},
};
const gesher_builtin_symbol kAudioEffect[] = {
    {"JNI_OnLoad", reinterpret_cast<void*>(&JNI_OnLoad_audioeffect_jni)},
};

[[maybe_unused]] const bool registered = [] {
  gesher_register_builtin_library("libmedia_jni.so", kMediaJni, 1);
  gesher_register_builtin_library("libsoundpool.so", kSoundPool, 1);
  gesher_register_builtin_library("libaudioeffect_jni.so", kAudioEffect, 1);
  return true;
}();

}  // namespace
