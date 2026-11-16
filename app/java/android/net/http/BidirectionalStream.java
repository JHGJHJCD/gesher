// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
package android.net.http;

import java.nio.ByteBuffer;

/** Android's asynchronous bidirectional HTTP/2 stream API. */
public abstract class BidirectionalStream {
    public static final int STREAM_PRIORITY_IDLE=0, STREAM_PRIORITY_LOWEST=1,
            STREAM_PRIORITY_LOW=2, STREAM_PRIORITY_MEDIUM=3, STREAM_PRIORITY_HIGHEST=4;
    public abstract void cancel();
    public abstract void flush();
    public abstract HeaderBlock getHeaders();
    public abstract String getHttpMethod();
    public abstract int getPriority();
    public abstract int getTrafficStatsTag();
    public abstract int getTrafficStatsUid();
    public abstract boolean hasTrafficStatsTag();
    public abstract boolean hasTrafficStatsUid();
    public abstract boolean isDelayRequestHeadersUntilFirstFlushEnabled();
    public abstract boolean isDone();
    public abstract void read(ByteBuffer buffer);
    public abstract void start();
    public abstract void write(ByteBuffer buffer, boolean endOfStream);
    public abstract static class Builder {
        public abstract Builder addHeader(String name, String value);
        public abstract BidirectionalStream build();
        public abstract Builder setDelayRequestHeadersUntilFirstFlushEnabled(boolean delay);
        public abstract Builder setHttpMethod(String method);
        public abstract Builder setPriority(int priority);
        public abstract Builder setTrafficStatsTag(int tag);
        public abstract Builder setTrafficStatsUid(int uid);
    }
    public interface Callback {
        void onCanceled(BidirectionalStream stream, UrlResponseInfo info);
        void onFailed(BidirectionalStream stream, UrlResponseInfo info, HttpException exception);
        void onReadCompleted(BidirectionalStream stream, UrlResponseInfo info, ByteBuffer buffer, boolean endOfStream);
        void onResponseHeadersReceived(BidirectionalStream stream, UrlResponseInfo info);
        void onResponseTrailersReceived(BidirectionalStream stream, UrlResponseInfo info, HeaderBlock trailers);
        void onStreamReady(BidirectionalStream stream);
        void onSucceeded(BidirectionalStream stream, UrlResponseInfo info);
        void onWriteCompleted(BidirectionalStream stream, UrlResponseInfo info, ByteBuffer buffer, boolean endOfStream);
    }
}
