/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Production serialization and real filesystem operations; stream adapters count boundary calls. */
public final class SettingsStoreHost {
    private static int checks;
    private static final String[] TABLES = {"system", "secure", "global"};
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
        checks++;
    }
    private static Map<String, Map<String, String>> empty() {
        Map<String, Map<String, String>> tables = new LinkedHashMap<>();
        for (String table : TABLES) tables.put(table, new LinkedHashMap<>());
        return tables;
    }
    private static Map<String, Map<String, String>> defaults() {
        Map<String, Map<String, String>> result = empty();
        result.get("system").put("seed", "original");
        return result;
    }
    private static void string(ByteBuffer out, String value) {
        out.putInt(value.length());
        out.asCharBuffer().put(value);
        out.position(out.position() + value.length() * 2);
    }
    private static void row(ByteBuffer out, String name, long id, String value) {
        string(out, name); out.putLong(id); out.put((byte) (value == null ? 0 : 1));
        if (value != null) string(out, value);
    }
    private static byte[] expected() {
        ByteBuffer out = ByteBuffer.allocate(1024);
        out.putInt(0x47535431); out.putLong(4);
        out.putInt(2); row(out, "seed", 0, "original");
        row(out, "value", 1, "\u0000A\u05d0\ud800\udfff");
        out.putInt(1); row(out, "empty", 2, "");
        out.putInt(1); row(out, "nullable", 3, null);
        return Arrays.copyOf(out.array(), out.position());
    }
    private static void format(Path root) throws Exception {
        File file = root.resolve("format.bin").toFile();
        Map<String, Map<String, String>> tables = defaults();
        SettingsStore store = new SettingsStore(file, tables);
        check(store.put("system", "value", "\u0000A\u05d0\ud800\udfff", false), "Persist binary UTF-16 units");
        check(store.put("secure", "empty", "", false), "Persist empty string");
        check(store.put("global", "nullable", null, false), "Persist null row");
        check(Arrays.equals(expected(), Files.readAllBytes(file.toPath())), "Exact legacy GST1 byte layout");
        Map<String, Map<String, String>> loaded = empty();
        SettingsStore restored = new SettingsStore(file, loaded);
        check(loaded.equals(tables), "Reload legacy bytes without changing values");
        check(restored.id("system", "seed") == 0 && restored.id("system", "value") == 1
                && restored.id("secure", "empty") == 2 && restored.id("global", "nullable") == 3,
                "Row IDs survive reload");
        check(!restored.put("system", "value", "\u0000A\u05d0\ud800\udfff", false), "Identical value is not rewritten");
        check(restored.put("system", "value", "changed", true) && restored.id("system", "value") == 4,
                "Generation resumes after reload");
        check(!restored.put("system", "missing", "value", true), "Existing-only mutation remains conditional");
    }
    private static void boundaries(Path root) throws Exception {
        File file = root.resolve("boundaries.bin").toFile();
        Map<String, Map<String, String>> tables = empty();
        SettingsStore store = new SettingsStore(file, tables);
        int[] lengths = {4095, 4096, 4097, 32768};
        for (int length : lengths) {
            char[] value = new char[length];
            for (int i = 0; i < length; i++) value[i] = (char) ((i * 79) & 0xffff);
            check(store.put("secure", "length-" + length, new String(value), false), "Write across stream buffer boundary");
        }
        check(store.put("system", "text-null", "null", false), "Literal null normalization");
        check(tables.get("system").containsKey("text-null") && tables.get("system").get("text-null") == null,
                "Literal null remains nullable state");
        Map<String, Map<String, String>> loaded = empty();
        new SettingsStore(file, loaded);
        check(loaded.equals(tables), "Large binary values and unpaired surrogates round-trip exactly");
        byte[] before = Files.readAllBytes(file.toPath());
        check(!store.put("system", "bad\u0000key", "value", false), "Invalid key remains rejected");
        try { store.put("system", "too-long", "x".repeat(32769), false); throw new AssertionError("Missing value limit"); }
        catch (IllegalArgumentException expected) { checks++; }
        check(Arrays.equals(before, Files.readAllBytes(file.toPath())), "Rejected changes do not touch persisted state");
    }
    private static void invalidLoad(Path root) throws Exception {
        byte[] valid = expected();
        Path path = root.resolve("invalid.bin");
        for (int size = 0; size < valid.length; size++) {
            Files.write(path, Arrays.copyOf(valid, size));
            Map<String, Map<String, String>> tables = defaults();
            SettingsStore store = new SettingsStore(path.toFile(), tables);
            check(tables.equals(defaults()) && store.id("system", "seed") == 0,
                    "A truncated file never publishes partially decoded tables");
        }
        byte[][] bad = {valid.clone(), Arrays.copyOf(valid, valid.length + 1), valid.clone(), valid.clone(), valid.clone()};
        bad[0][0] = 0;
        Arrays.fill(bad[2], 4, 12, (byte) 0xff); // negative next ID
        Arrays.fill(bad[3], 12, 16, (byte) 0xff); // negative row count
        Arrays.fill(bad[4], 16, 20, (byte) 0xff); // negative name length
        for (byte[] bytes : bad) {
            Files.write(path, bytes);
            Map<String, Map<String, String>> tables = defaults();
            new SettingsStore(path.toFile(), tables);
            check(tables.equals(defaults()), "Malformed file leaves defaults intact");
        }
    }
    private static void publicationFailure(Path root) throws Exception {
        Path target = Files.createDirectory(root.resolve("publication-target"));
        Files.write(target.resolve("keep"), new byte[] {1});
        Map<String, Map<String, String>> tables = defaults();
        SettingsStore store = new SettingsStore(target.toFile(), tables);
        for (int operation = 0; operation < 3; operation++) {
            try {
                if (operation == 0) store.put("system", "seed", "changed", false);
                if (operation == 1) store.put("system", "fresh", "value", false);
                if (operation == 2) store.remove("system", "seed");
                throw new AssertionError("Expected publication failure");
            } catch (IllegalStateException expected) { checks++; }
            check(tables.equals(defaults()) && store.id("system", "seed") == 0, "Failed publication rolls back state and IDs");
            try (java.util.stream.Stream<Path> files = Files.list(root)) {
                check(files.noneMatch(p -> p.getFileName().toString().startsWith("settings-")), "Failed publication removes temporary file");
            }
        }
        Files.delete(target.resolve("keep")); Files.delete(target);
        check(store.put("system", "fresh", "value", false) && store.id("system", "fresh") == 1,
                "Failed writes do not consume generation IDs");
        Map<String, Map<String, String>> loaded = empty();
        new SettingsStore(target.toFile(), loaded);
        check(loaded.equals(tables), "Persistence recovers after publication failure");
    }
    private static void io(Path root) throws Exception {
        File file = root.resolve("io.bin").toFile();
        Map<String, Map<String, String>> tables = empty();
        tables.get("system").put("wide", "a\u05d0\ud800\u0000".repeat(2048));
        SettingsStore store = new SettingsStore(file, tables);
        SettingsIO.reset();
        store.put("secure", "trigger", "value", false);
        int writes = SettingsIO.writes;
        long written = SettingsIO.written;
        SettingsIO.reset();
        Map<String, Map<String, String>> loaded = empty();
        new SettingsStore(file, loaded);
        int reads = SettingsIO.reads;
        check(loaded.equals(tables), "Measured real I/O preserves full data");
        check(written == file.length(), "Measured output bytes match the complete persisted file");
        System.out.println("SettingsStore I/O: bytes=" + written + " writes=" + writes + " reads=" + reads);
        check(writes <= 4, "Settings snapshot writes are buffered, not one operation per UTF-16 unit");
        check(reads <= 5, "Settings snapshot reads are buffered, not one operation per byte");
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("gesher-settings-performance-");
        try {
            format(root); boundaries(root); invalidLoad(root); publicationFailure(root); io(root);
            System.out.println("SettingsStoreHost all passed: " + checks + " checks");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }
}

// The production stream construction sites are instrumented by the runner. These
// subclasses always delegate to real File*Stream operations and real FileDescriptor.sync.
final class SettingsIO {
    static int writes, reads;
    static long written;
    static void reset() { writes = reads = 0; written = 0; }
}
final class CountingFileOutputStream extends FileOutputStream {
    CountingFileOutputStream(File file) throws IOException { super(file); }
    public void write(int value) throws IOException { SettingsIO.writes++; SettingsIO.written++; super.write(value); }
    public void write(byte[] bytes) throws IOException { write(bytes, 0, bytes.length); }
    public void write(byte[] bytes, int offset, int length) throws IOException {
        SettingsIO.writes++; SettingsIO.written += length; super.write(bytes, offset, length);
    }
}
final class CountingFileInputStream extends FileInputStream {
    CountingFileInputStream(File file) throws IOException { super(file); }
    public int read() throws IOException { SettingsIO.reads++; return super.read(); }
    public int read(byte[] bytes) throws IOException { return read(bytes, 0, bytes.length); }
    public int read(byte[] bytes, int offset, int length) throws IOException {
        SettingsIO.reads++; return super.read(bytes, offset, length);
    }
}
final class SystemServer {
    static void log(String ignored) {} // Host test boundary: no system server is started.
}
