// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A navigation a page is about to make, as
 * {@link CefriumNavigationClient#onBeforeNavigate} sees it. Like
 * {@code android.webkit.WebResourceRequest}.
 */
public final class CefriumNavigationRequest {
    private final String mUrl;
    private final String mMethod;
    private final boolean mMainFrame;
    private final boolean mRedirect;
    private final boolean mGesture;
    private final Map<String, String> mHeaders;

    CefriumNavigationRequest(String url, String method, boolean mainFrame,
            boolean redirect, boolean gesture, String[] headers) {
        mUrl = url;
        mMethod = method;
        mMainFrame = mainFrame;
        mRedirect = redirect;
        mGesture = gesture;
        TreeMap<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            for (int i = 0; i + 1 < headers.length; i += 2) h.put(headers[i], headers[i + 1]);
        }
        mHeaders = Collections.unmodifiableMap(h);
    }

    /** The URL the navigation goes to. */
    public String getUrl() {
        return mUrl;
    }

    /** The method, upper case: {@code GET} or {@code POST}. */
    public String getMethod() {
        return mMethod;
    }

    /** Whether the navigation is of the page itself, not of an iframe. */
    public boolean isForMainFrame() {
        return mMainFrame;
    }

    /** Whether a server redirect started this navigation. */
    public boolean isRedirect() {
        return mRedirect;
    }

    /** Whether the user caused it (a tapped link), not a script on its own. */
    public boolean hasGesture() {
        return mGesture;
    }

    /** The request headers, case-insensitive names. Unmodifiable. */
    public Map<String, String> getRequestHeaders() {
        return mHeaders;
    }
}
