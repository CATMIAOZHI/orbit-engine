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

    // Notifications, on the network thread. A method that is not overridden
    // costs nothing: the engine only calls the ones an interceptor implements.

    /**
     * Every request, after the block decision: {@code blocked} is true when
     * the built-in ad filter or {@link #shouldBlockRequest} refused it.
     * Network thread.
     */
    default void onRequestFiltered(String url, String method, boolean blocked) {}

    /** A request was redirected to {@code newUrl}. Network thread. */
    default void onResourceRedirect(String oldUrl, String newUrl) {}

    /** A response's headers arrived, with this status code. Network thread. */
    default void onResourceResponse(String url, int statusCode) {}

    /** A request finished: its status code and the bytes it received. Network thread. */
    default void onResourceLoadComplete(String url, int statusCode, long receivedBytes) {}

    /**
     * A chunk of a response body, as it arrives (read only; the page gets it
     * unchanged). Network thread. Overriding it copies every body across, so
     * only override it when needed.
     */
    default void onResponseData(String url, java.nio.ByteBuffer chunk) {}

    /** Whether a cookie may be sent with a request. Network thread. */
    default boolean onCanSendCookie(String url, String name, String value) {
        return true;
    }

    /** Whether a cookie a response sets may be stored. Network thread. */
    default boolean onCanSaveCookie(String url, String name, String value) {
        return true;
    }
}
