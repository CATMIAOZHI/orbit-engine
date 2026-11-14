// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A page asking for permissions, as
 * {@link CefriumChromeClient#onPermissionRequest} sees it. Like
 * {@code android.webkit.PermissionRequest}: answer with {@link #grant} or
 * {@link #deny}, now or later, from any thread. The first answer counts; the
 * page waits until there is one.
 */
public final class CefriumPermissionRequest {

    /** How the browser delivers an answer. */
    interface Responder {
        void respond(boolean grant);
    }

    private final String mOrigin;
    private final Set<CefriumPermission> mPermissions;
    private final Responder mResponder;
    private boolean mAnswered;

    CefriumPermissionRequest(String origin, Set<CefriumPermission> permissions,
            Responder responder) {
        mOrigin = origin;
        mPermissions = Collections.unmodifiableSet(permissions.isEmpty()
                ? EnumSet.noneOf(CefriumPermission.class) : EnumSet.copyOf(permissions));
        mResponder = responder;
    }

    /** The origin asking, e.g. {@code https://example.com}. */
    public String getOrigin() {
        return mOrigin;
    }

    /** What it asks for. Unmodifiable. */
    public Set<CefriumPermission> getPermissions() {
        return mPermissions;
    }

    /** Grants every permission of the request. */
    public void grant() {
        answer(true);
    }

    /** Denies the request. */
    public void deny() {
        answer(false);
    }

    /** The page no longer waits: later answers are ignored. */
    void cancel() {
        synchronized (this) {
            mAnswered = true;
        }
    }

    private void answer(boolean grant) {
        synchronized (this) {
            if (mAnswered) return;
            mAnswered = true;
        }
        mResponder.respond(grant);
    }
}
