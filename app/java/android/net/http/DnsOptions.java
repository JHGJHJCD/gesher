// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
package android.net.http;

import java.time.Duration;
import java.util.Objects;

public final class DnsOptions {
    public static final int DNS_OPTION_UNSPECIFIED=0, DNS_OPTION_ENABLED=1, DNS_OPTION_DISABLED=2;
    private final int persist, preestablish, stale, resolver;
    private final Duration period;
    private final StaleDnsOptions staleOptions;
    private DnsOptions(Builder b) {
        persist=b.persist; preestablish=b.preestablish; stale=b.stale; resolver=b.resolver;
        period=b.period; staleOptions=b.staleOptions;
    }
    public int getPersistHostCache() { return persist; }
    public Duration getPersistHostCachePeriod() { return period; }
    public int getPreestablishConnectionsToStaleDnsResults() { return preestablish; }
    public int getStaleDns() { return stale; }
    public StaleDnsOptions getStaleDnsOptions() { return staleOptions; }
    public int getUseHttpStackDnsResolver() { return resolver; }
    public static final class Builder {
        private int persist, preestablish, stale, resolver;
        private Duration period;
        private StaleDnsOptions staleOptions;
        public Builder setPersistHostCache(int v) { persist=ConnectionMigrationOptions.option(v); return this; }
        public Builder setPersistHostCachePeriod(Duration v) { period=Objects.requireNonNull(v); return this; }
        public Builder setPreestablishConnectionsToStaleDnsResults(int v) { preestablish=ConnectionMigrationOptions.option(v); return this; }
        public Builder setStaleDns(int v) { stale=ConnectionMigrationOptions.option(v); return this; }
        public Builder setStaleDnsOptions(StaleDnsOptions v) { staleOptions=Objects.requireNonNull(v); return this; }
        public Builder setUseHttpStackDnsResolver(int v) { resolver=ConnectionMigrationOptions.option(v); return this; }
        public DnsOptions build() { return new DnsOptions(this); }
    }
    public static class StaleDnsOptions {
        private final int cross, useStale;
        private final Duration fresh, max;
        private StaleDnsOptions(Builder b) { cross=b.cross; useStale=b.useStale; fresh=b.fresh; max=b.max; }
        public int getAllowCrossNetworkUsage() { return cross; }
        public Duration getFreshLookupTimeout() { return fresh; }
        public Duration getMaxExpiredDelay() { return max; }
        public int getUseStaleOnNameNotResolved() { return useStale; }
        public static final class Builder {
            private int cross, useStale;
            private Duration fresh, max;
            public Builder setAllowCrossNetworkUsage(int v) { cross=ConnectionMigrationOptions.option(v); return this; }
            public Builder setFreshLookupTimeout(Duration v) { fresh=Objects.requireNonNull(v); return this; }
            public Builder setMaxExpiredDelay(Duration v) { max=Objects.requireNonNull(v); return this; }
            public Builder setUseStaleOnNameNotResolved(int v) { useStale=ConnectionMigrationOptions.option(v); return this; }
            public StaleDnsOptions build() { return new StaleDnsOptions(this); }
        }
    }
}
