/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.server;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;

/** Uses actual APKs, the production verifier and the pinned apksig implementation. */
public final class ApkSigningVerifierTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File fixtures = new File(args[0]);
        File work = new File(args[1]);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        char[] password = "fixture-only".toCharArray();
        try (FileInputStream input = new FileInputStream(new File(work, "signer.p12"))) {
            keys.load(input, password);
        }
        X509Certificate certificate = (X509Certificate) keys.getCertificate("fixture");
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("fixture",
                (PrivateKey) keys.getKey("fixture", password),
                Collections.singletonList(certificate)).build();
        File v1 = new File(work, "v1-only.apk");
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(new File(fixtures, "unsigned.apk")).setOutputApk(v1)
                .setV1SigningEnabled(true).setV2SigningEnabled(false)
                .setV3SigningEnabled(false).setV4SigningEnabled(false).build().sign();

        ApkVerifier.Result platform = new ApkVerifier.Builder(v1)
                .setMinCheckedPlatformVersion(36).setMaxCheckedPlatformVersion(36).build().verify();
        require(!platform.isVerified() && platform.isVerifiedUsingV1Scheme(),
                "fixture must have verified v1 content and fail the SDK36 scheme policy");
        require(ApkSigningVerifier.mayRetryV1(platform), "v1-only policy failure must be eligible");
        ApkVerifier.Result compatible = ApkSigningVerifier.verify(v1);
        require(compatible.isVerified() && compatible.isVerifiedUsingV1Scheme(),
                "legitimate v1-only target-SDK36 APK must verify");
        require(compatible.getSignerCertificates().size() == 1
                && certificate.equals(compatible.getSignerCertificates().get(0)),
                "compatibility must preserve the actual verified signer");
        require(!ApkSigningVerifier.mayRetryV1(compatible), "successful verification is never retried");

        ApkVerifier.Result v2 = ApkSigningVerifier.verify(new File(fixtures, "v2only.apk"));
        require(v2.isVerified() && v2.isVerifiedUsingV2Scheme(), "v2 verification is preserved");
        require(!ApkSigningVerifier.mayRetryV1(v2), "v2 APK must not enter v1 compatibility");
        ApkVerifier.Result rotated = ApkSigningVerifier.verify(new File(fixtures, "rotated.apk"));
        require(rotated.isVerified() && rotated.isVerifiedUsingV31Scheme(),
                "current-platform v3.1 verification is preserved");
        require(rotated.getSigningCertificateLineage() != null
                && rotated.getSigningCertificateLineage().getCertificatesInLineage().size() == 2,
                "verified rotation history is preserved");
        require(!ApkSigningVerifier.mayRetryV1(rotated), "rotated APK must not enter v1 compatibility");

        ApkVerifier.Result unsigned = ApkSigningVerifier.verify(new File(fixtures, "unsigned.apk"));
        require(!unsigned.isVerified() && !ApkSigningVerifier.mayRetryV1(unsigned),
                "unsigned APK remains rejected without a compatibility retry");
        byte[] complete = Files.readAllBytes(v1.toPath());
        File incomplete = new File(work, "incomplete-download.apk");
        Files.write(incomplete.toPath(), Arrays.copyOf(complete, complete.length / 2));
        boolean rejected;
        try {
            rejected = !ApkSigningVerifier.verify(incomplete).isVerified();
        } catch (java.io.IOException | com.android.apksig.apk.ApkFormatException expected) {
            rejected = true;
        }
        require(rejected, "incomplete APK download remains rejected");
        System.out.println("PASS: real v1/v2/v3.1 APK verification, signer identity, lineage, unsigned and incomplete APK rejection");
    }
}
