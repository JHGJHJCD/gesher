/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
package com.gesher.server;

import android.os.Bundle;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.SpellCheckerSubtype;

import com.android.internal.textservice.ISpellCheckerSessionListener;
import com.android.internal.textservice.ITextServicesManager;
import com.android.internal.textservice.ITextServicesSessionListener;

/**
 * A present text-services manager with no installed spell-checker backend.
 * Android's manager uses these unavailable values to return a null session normally;
 * omitting the Binder service instead prevents the public manager from constructing.
 */
public final class TextServicesManagerService extends ITextServicesManager.Default {
    private static void user(int userId) {
        if (userId != 0) throw new SecurityException("Only text-services user 0 is supported");
    }

    @Override public SpellCheckerInfo getCurrentSpellChecker(int userId, String locale) {
        user(userId);
        return null;
    }

    @Override public SpellCheckerSubtype getCurrentSpellCheckerSubtype(int userId,
            boolean allowImplicitlySelectedSubtype) {
        user(userId);
        return null;
    }

    @Override public boolean isSpellCheckerEnabled(int userId) {
        user(userId);
        return false;
    }

    @Override public SpellCheckerInfo[] getEnabledSpellCheckers(int userId) {
        user(userId);
        // Android 16's service returns null when its filtered checker list is empty.
        // TextServicesManager.getEnabledSpellCheckerInfos normalizes it to an empty list.
        return null;
    }

    @Override public void getSpellCheckerService(int userId, String checkerId, String locale,
            ITextServicesSessionListener sessionListener, ISpellCheckerSessionListener listener,
            Bundle extras, int supportedAttributes) {
        user(userId);
        // As in Android's unknown-checker branch, no binding or callback is created.
        // The public manager tests availability before requesting a session.
    }

    @Override public void finishSpellCheckerService(int userId,
            ISpellCheckerSessionListener listener) {
        user(userId);
        // No sessions exist, so closing an absent session is idempotent.
    }
}
