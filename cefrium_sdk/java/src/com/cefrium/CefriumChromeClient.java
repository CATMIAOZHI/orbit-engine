// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Called on the UI thread. What a page asks of the app around it, like
 * {@code android.webkit.WebChromeClient}. Set it with
 * {@link CefriumBrowser#setChromeClient}; every method has a default, so
 * implement only the ones you need. The browser is the first parameter, so one
 * client can serve several browsers.
 */
public interface CefriumChromeClient {

    /**
     * A page asks for permissions. Answer with {@code request.grant()} or
     * {@code request.deny()}, now or later (after asking the user). The
     * default denies.
     *
     * <p>Some requests never get here because the platform refuses them
     * first: geolocation when the app does not hold the location permission
     * or location is off, clipboard reads from a page that is not focused
     * (an off-screen browser needs {@link CefriumBrowser#setFocus}), and any
     * permission of a page with an opaque origin ({@link CefriumBrowser#loadHtml}).
     */
    default void onPermissionRequest(CefriumBrowser browser, CefriumPermissionRequest request) {
        request.deny();
    }

    /**
     * A request that was not answered yet is no longer wanted (the page
     * navigated away): close any prompt shown for it. Answering it now does
     * nothing.
     */
    default void onPermissionRequestCanceled(CefriumBrowser browser,
            CefriumPermissionRequest request) {}
}
