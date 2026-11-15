/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.gesher.server;

import com.android.apksig.ApkVerifier;
import java.io.File;
import java.util.List;

/** Full APK verification with a narrowly scoped compatibility rule for JAR-only APKs. */
final class ApkSigningVerifier {
    private ApkSigningVerifier() {}

    static ApkVerifier.Result verify(File apk) throws Exception {
        ApkVerifier.Result result = verifyAt(apk, 36);
        if (mayRetryV1(result)) {
            // API 29 predates the target-SDK minimum-scheme rule, but still checks
            // v2/v3 signatures, stripping protection and the target sandbox policy.
            ApkVerifier.Result legacy = verifyAt(apk, 29);
            if (legacy.isVerified() && isV1Only(legacy)) return legacy;
        }
        return result;
    }

    private static ApkVerifier.Result verifyAt(File apk, int platform) throws Exception {
        return new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(platform)
                .setMaxCheckedPlatformVersion(platform).build().verify();
    }

    private static boolean isV1Only(ApkVerifier.Result result) {
        return result.isVerifiedUsingV1Scheme()
                && result.getV2SchemeSigners().isEmpty()
                && result.getV3SchemeSigners().isEmpty()
                && result.getV31SchemeSigners().isEmpty();
    }

    static boolean mayRetryV1(ApkVerifier.Result result) {
        if (result.isVerified() || !isV1Only(result)) return false;
        List<ApkVerifier.IssueWithParams> errors = result.getAllErrors();
        if (errors.isEmpty()) return false;
        for (ApkVerifier.IssueWithParams error : errors) {
            if (error.getIssue() != ApkVerifier.Issue.MIN_SIG_SCHEME_FOR_TARGET_SDK_NOT_MET) {
                return false;
            }
        }
        return true;
    }
}
