// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
package android.net.http;

import android.net.Network;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

public abstract class UrlRequest {
    public static final int REQUEST_PRIORITY_IDLE=0, REQUEST_PRIORITY_LOWEST=1,
            REQUEST_PRIORITY_LOW=2, REQUEST_PRIORITY_MEDIUM=3, REQUEST_PRIORITY_HIGHEST=4;
    public abstract void cancel();
    public abstract void followRedirect();
    public abstract HeaderBlock getHeaders();
    public abstract String getHttpMethod();
    public abstract int getPriority();
    public abstract void getStatus(StatusListener listener);
    public abstract int getTrafficStatsTag();
    public abstract int getTrafficStatsUid();
    public abstract boolean hasTrafficStatsTag();
    public abstract boolean hasTrafficStatsUid();
    public abstract boolean isCacheDisabled();
    public abstract boolean isDirectExecutorAllowed();
    public abstract boolean isDone();
    public abstract void read(ByteBuffer buffer);
    public abstract void start();

    public abstract static class Builder {
        public abstract Builder addHeader(String name, String value);
        public abstract Builder bindToNetwork(Network network);
        public abstract UrlRequest build();
        public abstract Builder setCacheDisabled(boolean disabled);
        public abstract Builder setDirectExecutorAllowed(boolean allowed);
        public abstract Builder setHttpMethod(String method);
        public abstract Builder setPriority(int priority);
        public abstract Builder setTrafficStatsTag(int tag);
        public abstract Builder setTrafficStatsUid(int uid);
        public abstract Builder setUploadDataProvider(UploadDataProvider provider, Executor executor);
    }
    public interface Callback {
        void onCanceled(UrlRequest request, UrlResponseInfo info);
        void onFailed(UrlRequest request, UrlResponseInfo info, HttpException exception);
        void onReadCompleted(UrlRequest request, UrlResponseInfo info, ByteBuffer buffer) throws Exception;
        void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocationUrl) throws Exception;
        void onResponseStarted(UrlRequest request, UrlResponseInfo info) throws Exception;
        void onSucceeded(UrlRequest request, UrlResponseInfo info);
    }
    public interface StatusListener { void onStatus(int status); }
    public static class Status {
        public static final int INVALID=-1, IDLE=0, WAITING_FOR_STALLED_SOCKET_POOL=1,
                WAITING_FOR_AVAILABLE_SOCKET=2, WAITING_FOR_DELEGATE=3, WAITING_FOR_CACHE=4,
                DOWNLOADING_PAC_FILE=5, RESOLVING_PROXY_FOR_URL=6, RESOLVING_HOST_IN_PAC_FILE=7,
                ESTABLISHING_PROXY_TUNNEL=8, RESOLVING_HOST=9, CONNECTING=10,
                SSL_HANDSHAKE=11, SENDING_REQUEST=12, WAITING_FOR_RESPONSE=13, READING_RESPONSE=14;
    }
}
