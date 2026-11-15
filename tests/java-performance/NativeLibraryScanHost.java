/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.performance;

import com.gesher.app.NativeLibs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class NativeLibraryScanHost {
    private static int checks;
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
        checks++;
    }
    private static void archive(Path path, String abi) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (int i = 0; i < 2000; i++) {
                out.putNextEntry(new ZipEntry("assets/entry-" + i)); out.closeEntry();
            }
            if (abi != null) {
                out.putNextEntry(new ZipEntry("lib/" + abi + "/libsample.so")); out.closeEntry();
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("gesher-abi-scan-");
        try {
            Path plain = root.resolve("plain.apk"), arm = root.resolve("arm.apk"), x64 = root.resolve("x64.apk");
            archive(plain, null); archive(arm, "arm64-v8a"); archive(x64, "x86_64");
            CountingZipFile.reset();
            check(NativeLibs.abiFor(plain.toString(), plain.toString()) == null, "Java-only selection");
            System.out.println("NativeLibs Java-only: opens=" + CountingZipFile.opens + " entries=" + CountingZipFile.entries);
            check(CountingZipFile.opens == 2 && CountingZipFile.entries == 4000,
                    "Each supplied Java-only archive is enumerated once");
            CountingZipFile.reset();
            check("arm64-v8a".equals(NativeLibs.abiFor(plain.toString(), arm.toString())), "Fallback ABI selection");
            System.out.println("NativeLibs ARM64: opens=" + CountingZipFile.opens + " entries=" + CountingZipFile.entries);
            check(CountingZipFile.opens == 2 && CountingZipFile.entries == 4001,
                    "Fallback selection does not reopen either archive");
            CountingZipFile.reset();
            check("x86_64".equals(NativeLibs.abiFor(arm.toString(), x64.toString())), "Later preferred ABI wins");
            check(CountingZipFile.opens == 2 && CountingZipFile.entries == 4002, "Preference search has one pass per archive");
            CountingZipFile.reset();
            check("x86_64".equals(NativeLibs.abiFor(x64.toString(), root.resolve("missing.apk").toString())),
                    "Preferred ABI retains early return");
            check(CountingZipFile.opens == 1 && CountingZipFile.entries == 2001, "Preferred ABI never opens later missing file");
            System.out.println("NativeLibraryScanHost all passed: " + checks + " checks");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }
}
