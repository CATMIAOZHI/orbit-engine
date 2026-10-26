// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Request/navigation and render-process notifications from CEF's
 * {@code CefRequestHandler} that are not already delivered by the SSL, HTTP-auth
 * or request-interception paths. All methods are {@code default}, so implement
 * only what you need. Register with
 * {@link CefriumBrowser#setRequestHandler(CefriumRequestHandler)}.
 *
 * <p>Delivered on the browser UI thread.
 */
public interface CefriumRequestHandler {
    /** Supplies the chosen client certificate (or none) for an mTLS challenge. */
    interface ClientCertificateCallback {
        /** Select the certificate at {@code index} in the offered list. */
        void select(int index);
        /** Proceed with no client certificate. */
        void cancel();
    }

    /**
     * A link/JS requested opening a URL in a new tab (window.open,
     * target=_blank, ctrl-click). The page's default popup path still runs.
     *
     * @param targetUrl   The URL requested.
     * @param disposition Chromium {@code cef_window_open_disposition_t}.
     * @param userGesture Whether it originated from a user gesture.
     */
    default void onOpenUrlFromTab(String targetUrl, int disposition, boolean userGesture) {}

    /** The render process became unresponsive (WebView {@code onRenderProcessUnresponsive}). */
    default void onRenderUnresponsive() {}

    /** A previously unresponsive render process became responsive again. */
    default void onRenderResponsive() {}

    /** The render view is ready. */
    default void onRenderViewReady() {}

    /** The document is available in the main frame (DOM ready). */
    default void onDocumentAvailable() {}

    /**
     * The server requested a client certificate (mTLS). Pick one via
     * {@code callback}, or cancel to proceed without one. Without a handler set,
     * the default behaviour (no client certificate) is used.
     *
     * @param isProxy      True if the request is from a proxy.
     * @param host         The requesting host.
     * @param port         The requesting port.
     * @param subjectNames Display names of the offered certificates, by index.
     * @param callback     Selection callback.
     */
    default void onSelectClientCertificate(boolean isProxy, String host, int port,
                                           String[] subjectNames,
                                           ClientCertificateCallback callback) {
        callback.cancel();
    }

    /** A resource load was redirected. */
    default void onResourceRedirect(String oldUrl, String newUrl) {}

    /** A resource response was received (inspection only). */
    default void onResourceResponse(String url, int statusCode) {}

    /** A resource finished loading. */
    default void onResourceLoadComplete(String url, int statusCode, long receivedBytes) {}

    /**
     * A chunk of a response body (opt-in; enable with
     * {@link CefriumBrowser#setResponseTapEnabled(boolean)}). The data passes
     * through unmodified; this is a read-only tap. {@code chunk} is a direct
     * buffer valid only during this call &mdash; copy what you need. Delivered on
     * an IO thread, once per chunk (a hot path; enable only when needed).
     */
    default void onResponseData(String url, java.nio.ByteBuffer chunk) {}

    /**
     * Whether a cookie may be SENT with a request (opt-in; enable with
     * {@link CefriumBrowser#setCookieFilterEnabled(boolean)}). Return false to
     * block it. Called per cookie on the network path (a hot path).
     */
    default boolean onCanSendCookie(String url, String name, String value) {
        return true;
    }

    /**
     * Whether a cookie may be SAVED from a response (opt-in; see
     * {@link CefriumBrowser#setCookieFilterEnabled(boolean)}). Return false to
     * block it.
     */
    default boolean onCanSaveCookie(String url, String name, String value) {
        return true;
    }
}
