/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.performance;

import java.io.IOException;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Counts the real ZIP constructor/enumeration boundary; it does not substitute archive contents. */
public final class CountingZipFile extends ZipFile {
    public static int opens, entries;
    public CountingZipFile(String path) throws IOException { super(path); opens++; }
    public static void reset() { opens = entries = 0; }
    @Override public Enumeration<? extends ZipEntry> entries() {
        Enumeration<? extends ZipEntry> source = super.entries();
        return new Enumeration<ZipEntry>() {
            public boolean hasMoreElements() { return source.hasMoreElements(); }
            public ZipEntry nextElement() { entries++; return source.nextElement(); }
        };
    }
}
