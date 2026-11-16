// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
package android.net.http;

public abstract class UploadDataSink {
    public abstract void onReadError(Exception exception);
    public abstract void onReadSucceeded(boolean finalChunk);
    public abstract void onRewindError(Exception exception);
    public abstract void onRewindSucceeded();
}
