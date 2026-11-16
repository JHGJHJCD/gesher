// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
package android.net.http;

public final class InlineExecutionProhibitedException extends java.util.concurrent.RejectedExecutionException {
    public InlineExecutionProhibitedException() { super("Inline execution is prohibited for this request"); }
}
