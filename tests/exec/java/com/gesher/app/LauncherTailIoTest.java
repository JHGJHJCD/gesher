/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.app;

import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** File-open adapters count actual I/O without replacing parsing or decoding. */
public final class LauncherTailIoTest {
    private static long bytesRead;
    private static int checks;
    private static boolean appendAfterSize;
    private static boolean truncateAfterSize;

    private static void check(boolean ok, String description) {
        ++checks;
        if (!ok) throw new AssertionError(description);
    }

    static BufferedReader openReader(File file) throws IOException {
        InputStream input = new FilterInputStream(Files.newInputStream(file.toPath())) {
            @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                int read = in.read(bytes, offset, count);
                if (read > 0) bytesRead += read;
                return read;
            }
            @Override public int read() throws IOException {
                int value = in.read();
                if (value >= 0) ++bytesRead;
                return value;
            }
        };
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()));
    }

    static SeekableByteChannel openChannel(File file) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(file.toPath());
        return new SeekableByteChannel() {
            @Override public int read(ByteBuffer bytes) throws IOException {
                int read = channel.read(bytes);
                if (read > 0) bytesRead += read;
                return read;
            }
            @Override public int write(ByteBuffer bytes) { throw new UnsupportedOperationException(); }
            @Override public long position() throws IOException { return channel.position(); }
            @Override public SeekableByteChannel position(long offset) throws IOException {
                channel.position(offset); return this;
            }
            @Override public long size() throws IOException {
                long size = channel.size();
                if (appendAfterSize) {
                    appendAfterSize = false;
                    Files.write(file.toPath(), "appended\n".getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.APPEND);
                }
                if (truncateAfterSize) {
                    truncateAfterSize = false;
                    Files.write(file.toPath(), new byte[0]);
                }
                return size;
            }
            @Override public SeekableByteChannel truncate(long size) { throw new UnsupportedOperationException(); }
            @Override public boolean isOpen() { return channel.isOpen(); }
            @Override public void close() throws IOException { channel.close(); }
        };
    }

    private static void compare(Path path, String input) throws Exception {
        Files.write(path, input.getBytes(StandardCharsets.UTF_8));
        List<String> all = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int count : new int[] {0, 1, 2, 3, 40, Integer.MAX_VALUE}) {
            List<String> expected = all.subList(Math.max(0, all.size() - count), all.size());
            check(expected.equals(LogLines.readTail(path.toFile(), count)),
                    "line suffix mismatch: count=" + count + " chars=" + input.length());
        }
    }

    private static void behavior(Path path, boolean gate) throws Exception {
        for (String input : new String[] {"", "\n", "\r", "\r\n", "\n\r", "\n\n", "\r\r",
                "one", "one\n", "one\r", "one\r\n", "one\r\ntwo\n\rthree\r\n\r\n",
                "שלום🙂\r\n世界\n\r\nlast"}) compare(path, input);
        // CR/LF pairs and UTF-8 sequences straddle every reverse-block boundary.
        for (int count : new int[] {0, 1, 2, 3, 4, 8190, 8191, 8192, 8193, 16382, 16383, 16384}) {
            for (String newline : new String[] {"\n", "\r", "\r\n", "\n\r"}) {
                compare(path, "head\r\nשלום🙂" + newline + "x".repeat(count) + newline);
                compare(path, "x".repeat(count) + "\r\nשלום🙂\nlast");
            }
        }
        Random random = new Random(0x5e5e);
        String[] pieces = {"a", "שלום", "🙂", "\n", "\r", "\r\n", "", "b\n\n"};
        for (int i = 0; i < 300; ++i) {
            StringBuilder input = new StringBuilder();
            for (int n = random.nextInt(80); n > 0; --n) input.append(pieces[random.nextInt(pieces.length)]);
            compare(path, input.toString());
        }
        Files.delete(path);
        check(LogLines.readTail(path.toFile(), 0).isEmpty(), "zero lines must not open missing file");
        try { LogLines.readTail(path.toFile(), -1); throw new AssertionError("negative count accepted"); }
        catch (IllegalArgumentException expected) { ++checks; }
        try { LogLines.readTail(path.toFile(), 1); throw new AssertionError("missing file accepted"); }
        catch (IOException expected) { ++checks; }
        Files.write(path, new byte[] {'o', 'k', '\n', (byte) 0xc3, (byte) 0x28});
        try { LogLines.readTail(path.toFile(), 1); throw new AssertionError("malformed retained UTF-8 accepted"); }
        catch (CharacterCodingException expected) { ++checks; }
        if (gate) {
            Files.write(path, new byte[] {(byte) 0xc3, (byte) 0x28, '\n', 'o', 'k', '\n'});
            check(LogLines.readTail(path.toFile(), 1).equals(Arrays.asList("ok")),
                    "malformed discarded prefix must not suppress valid suffix");
            Files.write(path, "before\nlast\n".getBytes(StandardCharsets.UTF_8));
            appendAfterSize = true;
            check(LogLines.readTail(path.toFile(), 1).equals(Arrays.asList("last")),
                    "snapshot must exclude concurrently appended lines");
            truncateAfterSize = true;
            try { LogLines.readTail(path.toFile(), 1); throw new AssertionError("concurrent truncation accepted"); }
            catch (IOException expected) { ++checks; }
        }
    }

    private static void measure(Path path, boolean gate) throws Exception {
        byte[] row = ("diagnostic שלום🙂 " + "x".repeat(96) + "\r\n").getBytes(StandardCharsets.UTF_8);
        final int rows = 400000;
        try (BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(path))) {
            for (int i = 0; i < rows; ++i) output.write(row);
            output.write("end".getBytes(StandardCharsets.UTF_8));
        }
        bytesRead = 0;
        long started = System.nanoTime();
        List<String> tail = LogLines.readTail(path.toFile(), 40);
        long elapsed = System.nanoTime() - started;
        long suffixBytes = 39L * row.length + 3;
        check(tail.size() == 40 && tail.get(39).equals("end"), "large-file suffix content");
        for (int i = 0; i < 39; ++i) check(tail.get(i).equals(new String(row, StandardCharsets.UTF_8).trim()),
                "large-file line content");
        System.out.println("Tail I/O: file_bytes=" + Files.size(path) + " actual_bytes_read=" + bytesRead
                + " suffix_bytes=" + suffixBytes + " elapsed_ns=" + elapsed + " max_heap="
                + Runtime.getRuntime().maxMemory());
        if (gate) check(bytesRead <= 2 * suffixBytes + 8192,
                "tail must read only reverse blocks and retained suffix, not the discarded prefix");
        if (gate) {
            try (java.io.RandomAccessFile huge = new java.io.RandomAccessFile(path.toFile(), "rw")) {
                huge.setLength(0); huge.setLength(64L * 1024 * 1024);
            }
            bytesRead = 0;
            List<String> capped = LogLines.readTail(path.toFile(), 40);
            check(capped.size() == 1 && capped.get(0).startsWith("[gesher: earlier log content omitted] "),
                    "enormous line omitted its diagnostic truncation marker");
            check(bytesRead <= 2L * 1024 * 1024 + 4, "byte cap still scanned the complete enormous line");
            System.out.println("Tail capped I/O: file_bytes=" + Files.size(path) + " actual_bytes_read=" + bytesRead);
        }
    }

    public static void main(String[] args) throws Exception {
        boolean gate = args[0].equals("gate");
        Path path = Files.createTempFile("gesher-tail-io-", ".log");
        try { behavior(path, gate); measure(path, gate); }
        finally { Files.deleteIfExists(path); }
        System.out.println("Launcher tail I/O PASS: " + checks + " checks");
    }
}
