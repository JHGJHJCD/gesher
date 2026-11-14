/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.gesher.app;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Frames a single reader's byte chunks into atomic stdout lines. */
public final class LogLines {
    private final PrintStream output;
    private final LineBuffer pending = new LineBuffer();

    /** The reader owns this buffer until a synchronous write finishes. */
    private static final class LineBuffer extends ByteArrayOutputStream {
        void writeTo(PrintStream output) { output.write(buf, 0, count); }
    }

    public LogLines(PrintStream output) { this.output = output; }

    /** Reads a UTF-8 suffix without retaining every preceding line of a large log. */
    public static List<String> readTail(File file, int lines) throws IOException {
        if (lines < 0) throw new IllegalArgumentException("Negative line count");
        if (lines == 0) return Collections.emptyList();
        ArrayDeque<String> tail = new ArrayDeque<>();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (tail.size() == lines) tail.removeFirst();
                tail.addLast(line);
            }
        }
        return new ArrayList<>(tail);
    }
    public void write(byte[] bytes, int offset, int count) {
        int end = offset + count;
        int start = offset;
        for (int at = offset; at < end; ++at) {
            if (bytes[at] == '\n') {
                pending.write(bytes, start, at + 1 - start);
                emit();
                start = at + 1;
            }
        }
        pending.write(bytes, start, end - start);
    }
    public void reset() { pending.reset(); }
    public void finish() {
        if (pending.size() != 0) {
            // Separate an unterminated child fragment from the next parent line.
            pending.write('\n');
            emit();
        }
    }
    private void emit() {
        synchronized (output) {
            pending.writeTo(output);
            output.flush();
        }
        pending.reset();
    }
}
