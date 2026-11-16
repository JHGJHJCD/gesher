// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
package android.net.http;

import java.util.List;

public abstract class UrlResponseInfo {
    public abstract HeaderBlock getHeaders();
    public abstract int getHttpStatusCode();
    public abstract String getHttpStatusText();
    public abstract String getNegotiatedProtocol();
    public abstract long getReceivedByteCount();
    public abstract String getUrl();
    public abstract List<String> getUrlChain();
    public abstract boolean wasCached();
}
