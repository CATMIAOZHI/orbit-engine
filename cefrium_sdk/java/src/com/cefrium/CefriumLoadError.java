// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** Why a page failed to load, like androidx {@code WebResourceErrorCompat}. */
public final class CefriumLoadError {
    private final int mErrorCode;
    private final String mDescription;
    private final String mFailedUrl;

    CefriumLoadError(int errorCode, String description, String failedUrl) {
        mErrorCode = errorCode;
        mDescription = description;
        mFailedUrl = failedUrl;
    }

    /**
     * Chromium's net error code, negative: -105 name not resolved, -106
     * internet disconnected, -102 connection refused, ... These values are
     * stable across engine versions (net_error_list.h).
     */
    public int getErrorCode() {
        return mErrorCode;
    }

    /** The engine's text for the error, e.g. {@code net::ERR_NAME_NOT_RESOLVED}; may be empty. */
    public String getDescription() {
        return mDescription;
    }

    /** The URL that failed. */
    public String getFailedUrl() {
        return mFailedUrl;
    }
}
