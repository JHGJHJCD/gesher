// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
package android.net.http;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class UploadDataProvider implements Closeable {
    public abstract long getLength() throws IOException;
    public abstract void read(UploadDataSink sink, ByteBuffer buffer) throws IOException;
    public abstract void rewind(UploadDataSink sink) throws IOException;
    public void close() throws IOException {}
}
