/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
/* Existing clock/logging/Binder-holder initialization boundaries; no Parcel substitute. */
#include "../location/framework_host.c"

/* The production service reads this declared network snapshot boundary. The
 * deterministic host fixture supplies successive adapter snapshots; no Windows
 * discovery, change notification, or interface implementation is substituted. */
JNIEXPORT jstring JNICALL Java_com_gesher_net_WindowsNetworks_nativeSnapshot(JNIEnv* env, jclass type) {
    jclass fixture = (*env)->FindClass(env, "ConnectivityServiceSnapshotHost");
    if (!fixture) return NULL;
    jmethodID read = (*env)->GetStaticMethodID(env, fixture, "readSnapshot", "()Ljava/lang/String;");
    if (!read) return NULL;
    return (jstring)(*env)->CallStaticObjectMethod(env, fixture, read);
}
