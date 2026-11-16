/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.gesher.app;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Frames normal source lines atomically. Oversized lines use labelled, UTF-8-safe
 * fragments so an unterminated child log cannot exhaust the launcher's heap.
 */
public final class LogLines {
    private static final int MAX_PENDING_BYTES = 256 * 1024;
    private static final int MAX_TAIL_BYTES = 1024 * 1024;
    private static final int MAX_TAIL_LINES = 4096;
    private static final String TAIL_TRUNCATED = "[gesher: earlier log content omitted] ";
    private static final byte[] CONTINUED = "[gesher: continued log line] ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONTINUES = " [gesher: log line continues]\n".getBytes(StandardCharsets.US_ASCII);
    private final PrintStream output;
    private final LineBuffer pending = new LineBuffer();
    private boolean continuation;

    /** The reader owns this buffer until a synchronous write finishes. */
    private static final class LineBuffer extends ByteArrayOutputStream {
        void writeTo(PrintStream output) { output.write(buf, 0, count); }
        void writeTo(PrintStream output, int length) { output.write(buf, 0, length); }
        void removePrefix(int length) {
            System.arraycopy(buf, length, buf, 0, count - length);
            count -= length;
        }
        int fragmentLength() {
            int lead = count - 1;
            while (lead >= count - 4 && (buf[lead] & 0xc0) == 0x80) --lead;
            if (lead < count - 4) return count;  // malformed input; preserve its bytes
            int value = buf[lead] & 0xff;
            int width = value >= 0xc2 && value <= 0xdf ? 2
                    : value >= 0xe0 && value <= 0xef ? 3
                    : value >= 0xf0 && value <= 0xf4 ? 4 : 1;
            return count - lead < width ? lead : count;
        }
    }

    public LogLines(PrintStream output) { this.output = output; }

    /**
     * Reads only the requested UTF-8 suffix. Invalid UTF-8 in an older, discarded
     * prefix does not hide the diagnostic suffix; invalid retained bytes still fail.
     * At most 1 MiB of source bytes and 4096 lines are retained. If a limit cuts the requested
     * lines, the first returned line visibly labels the omitted content.
     */
    public static List<String> readTail(File file, int lines) throws IOException {
        if (lines < 0) throw new IllegalArgumentException("Negative line count");
        if (lines == 0) return Collections.emptyList();
        try (SeekableByteChannel input = Files.newByteChannel(file.toPath())) {
            // Keep one snapshot boundary: a continuously appended log must not make
            // a failure diagnostic scan forever or return more than requested.
            long end = input.size();
            long earliest = Math.max(0, end - MAX_TAIL_BYTES);
            long start = tailStart(input, end, Math.min(lines, MAX_TAIL_LINES), earliest);
            boolean byteTruncated = earliest != 0 && start == earliest;
            boolean truncated = byteTruncated || lines > MAX_TAIL_LINES && start != 0;
            if (byteTruncated) start = utf8Start(input, start, end);
            input.position(start);
            final long remainingBytes = end - start;
            InputStream suffix = new InputStream() {
                private long remaining = remainingBytes;
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
                }
                @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                    if (count == 0) return 0;
                    if (remaining == 0) return -1;
                    int read = input.read(ByteBuffer.wrap(bytes, offset,
                            (int) Math.min(remaining, count)));
                    if (read < 0) throw new IOException("Log truncated while reading suffix");
                    remaining -= read;
                    return read;
                }
            };
            List<String> tail = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(suffix,
                    StandardCharsets.UTF_8.newDecoder()))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    tail.add(line);
                }
            }
            if (truncated && !tail.isEmpty()) tail.set(0, TAIL_TRUNCATED + tail.get(0));
            return tail;
        }
    }

    /** ASCII CR/LF cannot occur inside a UTF-8 multibyte sequence. */
    private static long tailStart(SeekableByteChannel input, long end, int lines, long earliest)
            throws IOException {
        ByteBuffer block = ByteBuffer.allocate(8192);
        boolean afterLf = false;
        boolean atEnd = true;
        int separators = 0;
        while (end != earliest) {
            int count = (int) Math.min(end - earliest, block.capacity());
            end -= count;
            input.position(end);
            block.clear();
            block.limit(count);
            while (block.hasRemaining()) {
                if (input.read(block) < 0) throw new IOException("Log truncated while finding suffix");
            }
            for (int at = count - 1; at >= 0; --at) {
                byte value = block.get(at);
                if (value == '\r' && afterLf) {
                    afterLf = false;
                    continue;
                }
                afterLf = value == '\n';
                if (value == '\r' || value == '\n') {
                    // readLine has no phantom empty line after a final terminator.
                    if (!atEnd && ++separators == lines) return end + at + 1;
                }
                atEnd = false;
            }
        }
        return earliest;
    }

    /** A byte-budget cut may enter the final 1-3 bytes of a valid UTF-8 character. */
    private static long utf8Start(SeekableByteChannel input, long start, long end)
            throws IOException {
        ByteBuffer first = ByteBuffer.allocate((int) Math.min(4, end - start));
        input.position(start);
        while (first.hasRemaining()) {
            if (input.read(first) < 0) throw new IOException("Log truncated while finding UTF-8 suffix");
        }
        int skipped = 0;
        while (skipped < Math.min(3, first.limit()) && (first.get(skipped) & 0xc0) == 0x80) ++skipped;
        return start + skipped;
    }
    public void write(byte[] bytes, int offset, int count) {
        int end = offset + count;
        int start = offset;
        for (int at = offset; at < end; ++at) {
            if (bytes[at] == '\n') {
                append(bytes, start, at + 1 - start);
                emit();
                start = at + 1;
            }
        }
        append(bytes, start, end - start);
    }
    private void append(byte[] bytes, int offset, int count) {
        while (count != 0) {
            if (pending.size() == MAX_PENDING_BYTES) emitFragment();
            int copied = Math.min(count, MAX_PENDING_BYTES - pending.size());
            pending.write(bytes, offset, copied);
            offset += copied;
            count -= copied;
        }
    }
    public void reset() { pending.reset(); continuation = false; }
    public void finish() {
        if (pending.size() != 0) {
            // Separate an unterminated child fragment from the next parent line.
            synchronized (output) {
                if (continuation) output.write(CONTINUED, 0, CONTINUED.length);
                pending.writeTo(output);
                output.write('\n');
                output.flush();
            }
            reset();
        }
    }
    private void emit() {
        synchronized (output) {
            if (continuation) output.write(CONTINUED, 0, CONTINUED.length);
            pending.writeTo(output);
            output.flush();
        }
        reset();
    }
    private void emitFragment() {
        int length = pending.fragmentLength();
        synchronized (output) {
            if (continuation) output.write(CONTINUED, 0, CONTINUED.length);
            pending.writeTo(output, length);
            output.write(CONTINUES, 0, CONTINUES.length);
            output.flush();
        }
        pending.removePrefix(length);
        continuation = true;
    }
}
