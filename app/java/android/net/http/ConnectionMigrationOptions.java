// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
package android.net.http;

/** Immutable QUIC preferences. They do not enable QUIC on the URLConnection transport. */
public class ConnectionMigrationOptions {
    public static final int MIGRATION_OPTION_UNSPECIFIED=0, MIGRATION_OPTION_ENABLED=1, MIGRATION_OPTION_DISABLED=2;
    private final int nonDefault, defaultNetwork, degradation;
    private ConnectionMigrationOptions(Builder b) {
        nonDefault=b.nonDefault; defaultNetwork=b.defaultNetwork; degradation=b.degradation;
    }
    public int getAllowNonDefaultNetworkUsage() { return nonDefault; }
    public int getDefaultNetworkMigration() { return defaultNetwork; }
    public int getPathDegradationMigration() { return degradation; }
    public static final class Builder {
        private int nonDefault, defaultNetwork, degradation;
        public Builder setAllowNonDefaultNetworkUsage(int value) { nonDefault=option(value); return this; }
        public Builder setDefaultNetworkMigration(int value) { defaultNetwork=option(value); return this; }
        public Builder setPathDegradationMigration(int value) { degradation=option(value); return this; }
        public ConnectionMigrationOptions build() { return new ConnectionMigrationOptions(this); }
    }
    static int option(int value) {
        if (value<0 || value>2) throw new IllegalArgumentException("Invalid option: " + value);
        return value;
    }
}
