// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Called on the UI thread. Navigation events of a {@link CefriumBrowser}, like
 * {@code android.webkit.WebViewClient}. Set it with
 * {@link CefriumBrowser#setNavigationClient}; every method has a default, so
 * implement only the ones you need. The browser is the first parameter, so one
 * client can serve several browsers.
 */
public interface CefriumNavigationClient {

    /**
     * A page or iframe is about to navigate. Return true to cancel it, for
     * example to hand a link to another app; false lets it go on.
     *
     * <p>Like {@code WebViewClient.shouldOverrideUrlLoading}: called before
     * the request is sent, for navigations the page starts (links, forms,
     * scripts) and for every server redirect. Not called for a load the app
     * starts ({@link CefriumBrowser#loadUrl} and the like) or for back and
     * forward. It must answer quickly: the navigation waits for it.
     */
    default boolean onBeforeNavigate(CefriumBrowser browser, CefriumNavigationRequest request) {
        return false;
    }

    /** The address of the page changed (a navigation, or the page's own history). */
    default void onUrlChanged(CefriumBrowser browser, String url) {}

    /** The page started loading {@code url} (WebView's {@code onPageStarted}). */
    default void onLoadStart(CefriumBrowser browser, String url) {}

    /**
     * The browser started or stopped loading. {@code loading == false} is
     * WebView's {@code onPageFinished}. The back and forward states are the
     * ones after the change.
     */
    default void onLoadingStateChanged(CefriumBrowser browser, boolean loading,
            boolean canGoBack, boolean canGoForward) {}

    /** How much of the page has loaded, 0 to 100 (WebView's {@code onProgressChanged}). */
    default void onProgressChanged(CefriumBrowser browser, int percent) {}

    /** The page failed to load (WebView's {@code onReceivedError}, main frame). */
    default void onLoadError(CefriumBrowser browser, CefriumLoadError error) {}

    /**
     * The page's renderer process ended; the page is gone until it is loaded
     * again ({@link CefriumBrowser#reload}).
     *
     * @param errorCode the process's exit code, 0 if none
     */
    default void onRenderProcessTerminated(CefriumBrowser browser,
            CefriumRendererTerminationStatus status, int errorCode) {}

    /** The page's renderer stopped answering (a long-running script, for example). */
    default void onRenderProcessUnresponsive(CefriumBrowser browser) {}

    /** The renderer answers again after {@link #onRenderProcessUnresponsive}. */
    default void onRenderProcessResponsive(CefriumBrowser browser) {}

    /**
     * A certificate failed validation. Call {@code callback.proceed()} to load
     * anyway (for example after checking {@code error.getCertificate()}
     * against a pinned one) or {@code cancel()}; now or later, from any
     * thread. The default cancels.
     */
    default void onSslError(CefriumBrowser browser, CefriumSslError error,
            CefriumBrowser.SslErrorCallback callback) {
        callback.cancel();
    }

    /**
     * A server or proxy asks for a user name and password (HTTP
     * authentication). Answer with {@code callback.proceed(user, password)}
     * or {@code cancel()}, from any thread. The default cancels.
     */
    default void onHttpAuthRequest(CefriumBrowser browser, String host, String realm,
            int port, boolean isProxy, CefriumBrowser.HttpAuthCallback callback) {
        callback.cancel();
    }

    /**
     * A server asks for a client certificate (mutual TLS). Pick one of
     * {@code subjects} by index, or cancel to go on without one. The default
     * cancels.
     */
    default void onSelectClientCertificate(CefriumBrowser browser, boolean isProxy,
            String host, int port, java.util.List<String> subjects,
            CefriumClientCertificateCallback callback) {
        callback.cancel();
    }
}
