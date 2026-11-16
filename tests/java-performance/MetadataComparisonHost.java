/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.server;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

final class CountingFileInputStream extends FileInputStream {
    CountingFileInputStream(File file) throws IOException { super(file); }
    @Override public int read(byte[] bytes, int offset, int count) throws IOException {
        MetadataComparisonHost.maxRequest = Math.max(MetadataComparisonHost.maxRequest, count);
        int read = super.read(bytes, offset, count);
        if (read > 0) MetadataComparisonHost.bytesRead += read;
        return read;
    }
}

public final class MetadataComparisonHost {
    static long bytesRead;
    static int maxRequest;
    private static int checks;
    static byte[] readAllBytes(Path path) throws IOException {
        byte[] result = Files.readAllBytes(path);
        bytesRead += result.length;
        maxRequest = Math.max(maxRequest, result.length);
        return result;
    }
    private static void check(boolean ok, String description) {
        ++checks;
        if (!ok) throw new AssertionError(description);
    }
    private static void write(Path path, long length) throws IOException {
        byte[] block = new byte[32768];
        new Random(0x631e).nextBytes(block);
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(path))) {
            while (length != 0) {
                int count = (int) Math.min(length, block.length);
                output.write(block, 0, count);
                length -= count;
            }
        }
    }
    private static void changed(Path path, long at) throws IOException {
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(path.toFile(), "rw")) {
            file.seek(at); int value = file.read(); file.seek(at); file.write(value ^ 0xff);
        }
    }
    private static void behavior(Path source, Path installed) throws Exception {
        check(new InstallSessionMetadata(source.toFile()).matches(installed.toFile()), "empty sets differ");
        // .apk, .idsig and unrelated files must not enter the metadata equality set.
        Files.write(source.resolve("base.apk"), new byte[] {1});
        Files.write(installed.resolve("other.apk"), new byte[] {2});
        Files.write(source.resolve("base.idsig"), new byte[] {3});
        check(new InstallSessionMetadata(source.toFile()).matches(installed.toFile()), "unrelated files were compared");
        for (String name : new String[] {"app.metadata", "base.dm", "base.digests", "base.digests.signature"}) {
            Path left = source.resolve(name), right = installed.resolve(name);
            for (int length : new int[] {0, 1, 7, 4095, 65535, 65536, 65537, 131071, 131073}) {
                write(left, length); write(right, length);
                InstallSessionMetadata metadata = new InstallSessionMetadata(source.toFile());
                check(metadata.matches(installed.toFile()), "equal " + name + " size=" + length);
                if (length != 0) {
                    for (long at : new long[] {0, length / 2, length - 1}) {
                        changed(right, at);
                        check(!metadata.matches(installed.toFile()), "missed differing byte at " + at);
                        changed(right, at);
                    }
                }
                write(right, length + 1);
                check(!metadata.matches(installed.toFile()), "length difference ignored");
                Files.delete(right);
                check(!metadata.matches(installed.toFile()), "missing installed metadata accepted");
                write(right, length);
            }
            Files.delete(left); Files.delete(right);
        }
        Files.write(source.resolve("base.dm"), new byte[0]);
        Files.write(installed.resolve("other.dm"), new byte[0]);
        check(!new InstallSessionMetadata(source.toFile()).matches(installed.toFile()), "equal count hid renamed metadata");
        Files.delete(installed.resolve("other.dm"));
        Files.write(installed.resolve("base.dm"), new byte[0]);
        Files.write(installed.resolve("extra.dm"), new byte[0]);
        check(!new InstallSessionMetadata(source.toFile()).matches(installed.toFile()), "extra metadata ignored");
        Files.delete(installed.resolve("extra.dm"));
        InstallSessionMetadata missingSource = new InstallSessionMetadata(source.toFile());
        Files.delete(source.resolve("base.dm"));
        try { missingSource.matches(installed.toFile()); throw new AssertionError("missing source did not fail"); }
        catch (IOException expected) { ++checks; }
    }
    private static void measurement(Path source, Path installed, boolean gate) throws Exception {
        Path left = source.resolve("base.dm"), right = installed.resolve("base.dm");
        final int length = 4 * 1024 * 1024;
        write(left, length); write(right, length);
        InstallSessionMetadata metadata = new InstallSessionMetadata(source.toFile());
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        metadata.matches(installed.toFile()); // warm the JDK/file boundary
        bytesRead = 0; maxRequest = 0;
        long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        boolean equal = metadata.matches(installed.toFile());
        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
        check(equal, "equal measured metadata differs");
        System.out.println("Metadata equal: file_bytes=" + length + " bytes_read=" + bytesRead
                + " max_read_request=" + maxRequest + " allocated_bytes=" + allocated);
        if (gate) {
            check(maxRequest <= 65536, "metadata comparison requested a complete large file");
            check(allocated < 192 * 1024, "metadata comparison allocated whole-file storage");
        }
        changed(right, 0);
        bytesRead = 0; maxRequest = 0;
        check(!metadata.matches(installed.toFile()), "first-byte mismatch ignored");
        System.out.println("Metadata first-byte mismatch: bytes_read=" + bytesRead);
        if (gate) check(bytesRead <= 128 * 1024, "first-byte mismatch read both complete files");
    }
    private static void large(Path source, Path installed) throws Exception {
        final long length = 48L * 1024 * 1024 + 17;
        write(source.resolve("base.dm"), length); write(installed.resolve("base.dm"), length);
        InstallSessionMetadata metadata = new InstallSessionMetadata(source.toFile());
        check(metadata.matches(installed.toFile()), "larger-than-heap equal metadata differs");
        changed(installed.resolve("base.dm"), length - 1);
        check(!metadata.matches(installed.toFile()), "larger-than-heap final mismatch ignored");
        System.out.println("Metadata large-file comparison: bytes_per_file=" + length
                + " max_heap=" + Runtime.getRuntime().maxMemory());
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("gesher-metadata-");
        Path source = Files.createDirectory(root.resolve("source"));
        Path installed = Files.createDirectory(root.resolve("installed"));
        try {
            switch (args[0]) {
                case "behavior": behavior(source, installed); break;
                case "measurement": measurement(source, installed, args[1].equals("gate")); break;
                case "large": large(source, installed); break;
                default: throw new IllegalArgumentException(args[0]);
            }
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path path : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
        System.out.println("Metadata comparison " + args[0] + " PASS: " + checks + " checks");
    }
}
