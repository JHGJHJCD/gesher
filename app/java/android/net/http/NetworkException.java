// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
package android.net.http;

public abstract class NetworkException extends HttpException {
    public static final int ERROR_HOSTNAME_NOT_RESOLVED=1, ERROR_INTERNET_DISCONNECTED=2,
            ERROR_NETWORK_CHANGED=3, ERROR_TIMED_OUT=4, ERROR_CONNECTION_CLOSED=5,
            ERROR_CONNECTION_TIMED_OUT=6, ERROR_CONNECTION_REFUSED=7,
            ERROR_CONNECTION_RESET=8, ERROR_ADDRESS_UNREACHABLE=9,
            ERROR_QUIC_PROTOCOL_FAILED=10, ERROR_OTHER=11;
    public NetworkException(String message, Throwable cause) { super(message, cause); }
    public abstract int getErrorCode();
    public abstract boolean isImmediatelyRetryable();
}
