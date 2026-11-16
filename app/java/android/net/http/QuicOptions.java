// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
package android.net.http;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public class QuicOptions {
    private final Set<String> hosts;
    private final String agent;
    private final Duration timeout;
    private final Integer size;
    private QuicOptions(Builder b) {
        hosts=Collections.unmodifiableSet(new LinkedHashSet<>(b.hosts));
        agent=b.agent; timeout=b.timeout; size=b.size;
    }
    public Set<String> getAllowedQuicHosts() { return hosts; }
    public String getHandshakeUserAgent() { return agent; }
    public Duration getIdleConnectionTimeout() { return timeout; }
    public int getInMemoryServerConfigsCacheSize() {
        if (size==null) throw new IllegalStateException("Cache size is unset");
        return size;
    }
    public boolean hasInMemoryServerConfigsCacheSize() { return size!=null; }
    public static final class Builder {
        private final Set<String> hosts=new LinkedHashSet<>();
        private String agent;
        private Duration timeout;
        private Integer size;
        public Builder addAllowedQuicHost(String host) { hosts.add(Objects.requireNonNull(host)); return this; }
        public Builder setHandshakeUserAgent(String value) { agent=Objects.requireNonNull(value); return this; }
        public Builder setIdleConnectionTimeout(Duration value) { timeout=Objects.requireNonNull(value); return this; }
        public Builder setInMemoryServerConfigsCacheSize(int value) {
            if (value<0) throw new IllegalArgumentException("Negative cache size");
            size=value; return this;
        }
        public QuicOptions build() { return new QuicOptions(this); }
    }
}
