/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
/* Recording native process boundary only: never invoke OS process control. */
#include "../pending-intent/framework_host.c"
static jclass fixture(JNIEnv* env) {
    return (*env)->FindClass(env, "com/gesher/server/PackageEnabledLifecycleFixture");
}
JNIEXPORT jlong JNICALL Java_com_gesher_server_Zygote_nativeChildStartTime(JNIEnv* env, jclass type, jint pid) {
    jclass target = fixture(env);
    return (*env)->CallStaticLongMethod(env, target,
            (*env)->GetStaticMethodID(env, target, "childStartTime", "(I)J"), pid);
}
JNIEXPORT jboolean JNICALL Java_com_gesher_server_Zygote_nativeKillChild(JNIEnv* env, jclass type, jint pid, jlong time) {
    jclass target = fixture(env);
    return (*env)->CallStaticBooleanMethod(env, target,
            (*env)->GetStaticMethodID(env, target, "killChild", "(IJ)Z"), pid, time);
}
JNIEXPORT jint JNICALL Java_com_gesher_server_Zygote_nativeExitCode(JNIEnv* env, jclass type, jint pid) {
    jclass target = fixture(env);
    return (*env)->CallStaticIntMethod(env, target,
            (*env)->GetStaticMethodID(env, target, "exitCode", "(I)I"), pid);
}
JNIEXPORT jboolean JNICALL Java_com_gesher_server_Zygote_nativeKill(JNIEnv* env, jclass type, jint pid) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/AssertionError"), "Lifecycle attempted bare PID termination");
    return JNI_FALSE;
}
