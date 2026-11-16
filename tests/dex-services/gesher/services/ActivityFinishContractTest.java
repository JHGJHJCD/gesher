/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

/** The finish contract fixture through the real AIDL proxy/Parcel path on ART. */
public final class ActivityFinishContractTest {
    public static void main(String[] args) throws Exception {
        System.loadLibrary("android_runtime");
        int checks = com.gesher.server.ActivityFinishFixture.run(true, "all");
        System.out.println("Activity finish Binder contract all passed: " + checks + " checks");
    }
}
