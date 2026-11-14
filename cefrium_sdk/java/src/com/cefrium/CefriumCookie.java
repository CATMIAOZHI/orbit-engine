// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** A cookie of a profile, as {@link CefriumCookieManager#getCookies} gives it. */
public final class CefriumCookie {
    /** The cookie's SameSite attribute. */
    public enum SameSite { UNSPECIFIED, NONE, LAX, STRICT }

    private final String mName;
    private final String mValue;
    private final String mDomain;
    private final String mPath;
    private final boolean mSecure;
    private final boolean mHttpOnly;
    private final long mExpiresAtMillis;
    private final SameSite mSameSite;

    CefriumCookie(String name, String value, String domain, String path, boolean secure,
            boolean httpOnly, long expiresAtMillis, SameSite sameSite) {
        mName = name;
        mValue = value;
        mDomain = domain;
        mPath = path;
        mSecure = secure;
        mHttpOnly = httpOnly;
        mExpiresAtMillis = expiresAtMillis;
        mSameSite = sameSite;
    }

    public String getName() {
        return mName;
    }

    public String getValue() {
        return mValue;
    }

    /** The domain it is sent to; a leading dot covers the subdomains. */
    public String getDomain() {
        return mDomain;
    }

    public String getPath() {
        return mPath;
    }

    public boolean isSecure() {
        return mSecure;
    }

    /** Whether scripts cannot read it ({@code document.cookie}). */
    public boolean isHttpOnly() {
        return mHttpOnly;
    }

    /** When it expires, in Unix milliseconds; 0 for a session cookie. */
    public long getExpiresAtMillis() {
        return mExpiresAtMillis;
    }

    public SameSite getSameSite() {
        return mSameSite;
    }
}
