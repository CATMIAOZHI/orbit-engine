// Copyright 2026 The Cefrium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import android.app.Activity;

import org.chromium.base.ContextUtils;
import org.chromium.components.external_intents.ExternalNavigationHandler;
import org.chromium.components.external_intents.InterceptNavigationDelegateClient;
import org.chromium.components.external_intents.RedirectHandler;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;

/**
 * Cefrium implementation of {@link InterceptNavigationDelegateClient}. Wires the
 * per-browser InterceptNavigationDelegateImpl (installed on the WebContents) to a
 * {@link CefExternalNavigationDelegate}, and carries the RedirectHandler that
 * ExternalNavigationHandler consults for the user-gesture / redirect-chain rules.
 * The tab-model / PWA / reparenting concerns Chrome exposes here are stubbed:
 * a Cefrium browser is a single WebContents owned by the app.
 */
class CefInterceptNavigationDelegateClient implements InterceptNavigationDelegateClient {
    private final CefriumBrowser mBrowser;
    private final WebContents mWebContents;
    private final WindowAndroid mWindowAndroid;
    private final boolean mIncognito;
    private RedirectHandler mRedirectHandler;

    CefInterceptNavigationDelegateClient(
            CefriumBrowser browser,
            WebContents webContents,
            WindowAndroid windowAndroid,
            boolean incognito) {
        mBrowser = browser;
        mWebContents = webContents;
        mWindowAndroid = windowAndroid;
        mIncognito = incognito;
    }

    @Override
    public WebContents getWebContents() {
        return mWebContents;
    }

    @Override
    public ExternalNavigationHandler createExternalNavigationHandler() {
        return new ExternalNavigationHandler(
                new CefExternalNavigationDelegate(mWebContents, mWindowAndroid, mIncognito));
    }

    @Override
    public RedirectHandler getOrCreateRedirectHandler() {
        if (mRedirectHandler == null) {
            mRedirectHandler = RedirectHandler.create();
        }
        return mRedirectHandler;
    }

    @Override
    public Activity getActivity() {
        if (mWindowAndroid == null) return null;
        return ContextUtils.activityFromContext(mWindowAndroid.getContext().get());
    }

    @Override
    public boolean isIncognito() {
        return mIncognito;
    }

    @Override
    public boolean wasTabLaunchedFromExternalApp() {
        return false;
    }

    @Override
    public boolean wasTabLaunchedFromLongPressInBackground() {
        return false;
    }

    @Override
    public void closeTab() {}

    @Override
    public void handleShouldCloseTab() {}

    @Override
    public void loadUrlIfPossible(LoadUrlParams loadUrlParams) {
        // Used for the intent:// S.browser_fallback_url path: load the fallback
        // web URL in this browser when the external app is not available.
        if (loadUrlParams != null && loadUrlParams.getUrl() != null) {
            mBrowser.loadUrl(loadUrlParams.getUrl());
        }
    }

    @Override
    public boolean isTabInPWA() {
        return false;
    }

    @Override
    public boolean isTabInBrowser() {
        return true;
    }

    @Override
    public boolean isTabInPopup() {
        // Not a stub: no Cefrium browser is ever a popup window. The SDK never
        // spawns one -- window.open / target=_blank either goes to the app's
        // PopupHandler, which opens a browser of its own, or loads in this
        // browser (see CefriumBrowser.PopupHandler).
        //
        // This answer is load-bearing. ExternalNavigationHandler suppresses the
        // hand-off to external apps when it is true, so returning true would
        // silently kill intent://, market:// and tel:// hand-off (FEATURE-059).
        // Revisit only if the SDK ever grows real popup windows.
        return false;
    }

    @Override
    public boolean isTabDetached() {
        return false;
    }

    @Override
    public boolean isInDesktopWindowingMode() {
        return false;
    }

    @Override
    public void startReparentingTask() {}

    @Override
    public void startReparentingTaskToNewWindow() {}
}
