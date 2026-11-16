// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
package android.net.http;

public abstract class CallbackException extends HttpException {
    protected CallbackException(String message, Throwable cause) { super(message, cause); }
}
