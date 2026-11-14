// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Sees every request a {@link CefriumBrowser} makes, and may cancel it or
 * answer it instead of the network. Set it with
 * {@link CefriumBrowser#setResourceInterceptor}. Unlike the navigation
 * client, it is NOT called on the UI thread: each method says which thread,
 * and an implementation must be thread-safe.
 */
@CefriumExperimental
public interface CefriumResourceInterceptor {

    /**
     * Network thread, synchronous: return true to cancel the request. Called
     * for every request, so it must be fast; do not block here.
     */
    default boolean shouldBlockRequest(CefriumBrowser.ResourceRequest request) {
        return false;
    }

    /**
     * Background thread, may block (read a file, a database): return a
     * response to answer the request yourself, or null to let it go to the
     * network. Like WebView's {@code shouldInterceptRequest}, for any URL,
     * with the request's method, headers and body. Range requests and
     * {@code HEAD} are handled for you, as for a {@link CefriumContentServer}.
     * Called after {@link #shouldBlockRequest} let the request through; the
     * request waits for the answer.
     */
    default CefriumContentResponse shouldInterceptRequest(CefriumContentRequest request) {
        return null;
    }
}
