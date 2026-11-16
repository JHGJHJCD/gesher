/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.app;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

public final class LauncherLogBoundsTest {
    private static final int TAIL_BYTES = 1024 * 1024, FRAGMENT_BYTES = 256 * 1024;
    private static final String TAIL_MARKER = "[gesher: earlier log content omitted] ";
    private static final String PREFIX = "[gesher: continued log line] ";
    private static final String END = " [gesher: log line continues]";
    private static int checks;
    private static void check(boolean ok, String description) {
        ++checks;
        if (!ok) throw new AssertionError(description);
    }
    private static void tail() throws Exception {
        Path path = Files.createTempFile("gesher-huge-log-line-", ".log");
        final long length = 64L * 1024 * 1024;
        try {
            byte[] block = new byte[16384]; Arrays.fill(block, (byte) 'x');
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(path))) {
                for (long left = length; left != 0; left -= block.length) output.write(block);
            }
            List<String> suffix = LogLines.readTail(path.toFile(), 40);
            check(suffix.size() == 1, "huge single line must remain one diagnostic line");
            check(suffix.get(0).equals(TAIL_MARKER + "x".repeat(TAIL_BYTES)), "single-line byte budget/marker mismatch");
            for (String character : new String[] {"א", "界", "🙂"}) {
                byte[] encoded = character.getBytes(StandardCharsets.UTF_8);
                for (int cut = 1; cut < encoded.length; ++cut) {
                    try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
                        file.seek(length - TAIL_BYTES - cut);
                        file.write(encoded); file.write('A');
                    }
                    String retained = LogLines.readTail(path.toFile(), 1).get(0);
                    check(retained.startsWith(TAIL_MARKER + "A"), "byte cap began inside a UTF-8 character");
                    check(retained.indexOf('\ufffd') < 0, "valid capped UTF-8 acquired replacement characters");
                    check(retained.getBytes(StandardCharsets.UTF_8).length <= TAIL_BYTES + TAIL_MARKER.length(),
                            "UTF-8 cap exceeded source-byte budget");
                    try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
                        file.seek(length - TAIL_BYTES - cut);
                        for (int i = 0; i <= encoded.length; ++i) file.write('x');
                    }
                }
            }
            // Multi-line diagnostics obey both the line count and the byte budget.
            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
                file.seek(length - 5); file.write("\nend\n".getBytes(StandardCharsets.UTF_8));
            }
            check(LogLines.readTail(path.toFile(), 1).equals(Arrays.asList("end")), "small final line was marked/truncated");
            suffix = LogLines.readTail(path.toFile(), 2);
            check(suffix.size() == 2 && suffix.get(0).startsWith(TAIL_MARKER) && suffix.get(1).equals("end"),
                    "byte-capped multi-line suffix lost line order");
            System.out.println("Bounded log tail: file_bytes=" + Files.size(path) + " source_budget=" + TAIL_BYTES
                    + " max_heap=" + Runtime.getRuntime().maxMemory());
            Arrays.fill(block, (byte) '\n');
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(path))) {
                for (int i = 0; i < 64; ++i) output.write(block);
            }
            suffix = LogLines.readTail(path.toFile(), Integer.MAX_VALUE);
            check(suffix.size() == 4096 && suffix.get(0).equals(TAIL_MARKER), "many short lines bypassed the retained-line cap");
            for (int i = 1; i < suffix.size(); ++i) check(suffix.get(i).isEmpty(), "empty retained line changed");
            check(LogLines.readTail(path.toFile(), 4096).get(0).isEmpty(), "requested exact-limit suffix acquired an omission marker");
            Files.write(path, new byte[] {'a', '\n', 'b', '\n'});
            check(LogLines.readTail(path.toFile(), Integer.MAX_VALUE).equals(Arrays.asList("a", "b")), "large request marked a fully retained small file");
        } finally { Files.deleteIfExists(path); }
    }
    private static final class Observer extends PrintStream {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long bytes;
        int fragments, continuationPrefixes, finalNewlines;
        boolean held = true;
        Observer() throws Exception { super(OutputStream.nullOutputStream(), true, "UTF-8"); }
        private static boolean equal(byte[] input, int offset, int count, String marker) {
            byte[] encoded = marker.getBytes(StandardCharsets.US_ASCII);
            return count == encoded.length && Arrays.equals(input, offset, offset + count, encoded, 0, encoded.length);
        }
        @Override public void write(byte[] input, int offset, int count) {
            held &= Thread.holdsLock(this);
            if (equal(input, offset, count, PREFIX)) { ++continuationPrefixes; return; }
            if (equal(input, offset, count, END + "\n")) { ++fragments; return; }
            check(count <= FRAGMENT_BYTES, "oversized payload copied to PrintStream before splitting");
            try { StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(input, offset, count)); }
            catch (java.nio.charset.CharacterCodingException invalid) { throw new AssertionError("split valid UTF-8 character", invalid); }
            digest.update(input, offset, count); bytes += count;
        }
        @Override public void write(int value) {
            held &= Thread.holdsLock(this);
            check(value == '\n', "unexpected individual output byte"); ++finalNewlines;
        }
    }
    private static void forward() throws Exception {
        byte[] pattern = "x🙂א".getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[16381];
        long total = 64L * 1024 * 1024 / pattern.length * pattern.length;
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        try (Observer output = new Observer()) {
            LogLines lines = new LogLines(output);
            for (long at = 0; at < total;) {
                int count = (int) Math.min(input.length, total - at);
                for (int i = 0; i < count; ++i) input[i] = pattern[(int) ((at + i) % pattern.length)];
                expected.update(input, 0, count);
                lines.write(input, 0, count);
                at += count;
            }
            lines.finish(); lines.finish();
            check(output.held, "source fragment/marker writes escaped the shared output lock");
            check(output.bytes == total && Arrays.equals(output.digest.digest(), expected.digest()), "fragmentation lost/duplicated/changed source bytes");
            check(output.fragments > 200 && output.continuationPrefixes == output.fragments, "missing explicit continuation markers");
            check(output.finalNewlines == 1, "finish added duplicate or missing final newline");
            System.out.println("Bounded log forwarding: source_bytes=" + total + " fragments=" + output.fragments
                    + " payload_budget=" + FRAGMENT_BYTES + " max_heap=" + Runtime.getRuntime().maxMemory());
        }
        // One large incoming array is also split before ByteArrayOutputStream grows.
        byte[] single = new byte[3 * FRAGMENT_BYTES + 1]; Arrays.fill(single, (byte) 'a');
        try (Observer output = new Observer()) {
            LogLines lines = new LogLines(output);
            lines.write(single, 0, single.length); lines.finish();
            check(output.bytes == single.length && output.fragments == 3, "single write bypassed pending-byte cap");
        }
        // Independent QA: every partial position of 2/3/4-byte characters at
        // the budget boundary, including a lead arriving in a separate chunk.
        for (String character : new String[] {"\u05d0", "\u754c", "\ud83d\ude42"}) {
            byte[] encoded = character.getBytes(StandardCharsets.UTF_8);
            for (int remaining = 0; remaining <= encoded.length + 1; ++remaining) {
                int prefix = FRAGMENT_BYTES - remaining;
                byte[] boundary = new byte[prefix + encoded.length + 6];
                Arrays.fill(boundary, 0, prefix, (byte) 'x');
                System.arraycopy(encoded, 0, boundary, prefix, encoded.length);
                System.arraycopy("-tail\n".getBytes(StandardCharsets.US_ASCII), 0, boundary,
                        prefix + encoded.length, 6);
                for (boolean chunked : new boolean[] {false, true}) {
                    try (Observer output = new Observer()) {
                        LogLines lines = new LogLines(output);
                        if (chunked) {
                            lines.write(boundary, 0, prefix + 1);
                            for (int at = prefix + 1; at < boundary.length; ++at) lines.write(boundary, at, 1);
                        } else lines.write(boundary, 0, boundary.length);
                        lines.finish();
                        check(output.bytes == boundary.length && Arrays.equals(output.digest.digest(),
                                MessageDigest.getInstance("SHA-256").digest(boundary)),
                                "fragment boundary changed complete source bytes");
                        check(output.held && output.fragments == 1 && output.continuationPrefixes == 1
                                && output.finalNewlines == 0, "boundary line framing/locking changed");
                    }
                }
            }
        }
        byte[] exactLine = new byte[FRAGMENT_BYTES];
        Arrays.fill(exactLine, (byte) 'x'); exactLine[exactLine.length - 1] = '\n';
        try (Observer output = new Observer()) {
            LogLines lines = new LogLines(output);
            lines.write(exactLine, 0, exactLine.length); lines.finish();
            check(output.bytes == exactLine.length && output.fragments == 0
                    && output.continuationPrefixes == 0, "exact-budget normal line was fragmented");
        }
    }
    private static void concurrency() throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(captured, true, "UTF-8");
        LogLines lines = new LogLines(output);
        String source = "child🙂א".repeat(60000);
        byte[] input = (source + "\n").getBytes(StandardCharsets.UTF_8);
        Thread parent = new Thread(() -> { for (int i = 0; i < 400; ++i) output.println("parent-" + i); });
        parent.start();
        for (int at = 0; at < input.length;) {
            int count = Math.min(4093, input.length - at);
            lines.write(input, at, count); at += count;
        }
        lines.finish(); parent.join();
        String text = new String(captured.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        StringBuilder reconstructed = new StringBuilder();
        int parentLines = 0, childLines = 0;
        for (String line : text.split("\n")) {
            if (line.matches("parent-\\d+")) { ++parentLines; continue; }
            if (childLines != 0) {
                check(line.startsWith(PREFIX), "parent/source text spliced into a continuation marker");
                line = line.substring(PREFIX.length());
            }
            if (line.endsWith(END)) line = line.substring(0, line.length() - END.length());
            check(line.startsWith("child") || line.startsWith("🙂") || line.startsWith("א") || line.startsWith("hild")
                    || line.startsWith("ild") || line.startsWith("ld") || line.startsWith("d"), "unlabelled or interleaved fragment");
            reconstructed.append(line); ++childLines;
        }
        check(parentLines == 400 && childLines > 1, "concurrent lines lost/duplicated");
        check(reconstructed.toString().equals(source), "concurrent fragments changed source UTF-8");
        captured.reset();
        lines.write(input, 0, FRAGMENT_BYTES + 5); lines.reset(); captured.reset();
        byte[] next = "new source\n".getBytes(StandardCharsets.UTF_8);
        lines.write(next, 0, next.length); lines.finish();
        check(new String(captured.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n").equals("new source\n"),
                "reset retained the oversized-source continuation label");
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "tail": tail(); break;
            case "forward": forward(); break;
            case "concurrency": concurrency(); break;
            default: throw new IllegalArgumentException(args[0]);
        }
        System.out.println("Launcher bounded log " + args[0] + " PASS: " + checks + " checks");
    }
}
