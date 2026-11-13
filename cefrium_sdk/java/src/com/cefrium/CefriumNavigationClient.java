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
}
