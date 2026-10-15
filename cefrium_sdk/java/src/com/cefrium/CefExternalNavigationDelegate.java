// Copyright 2026 The Cefrium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import org.chromium.base.ApplicationState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.Callback;
import org.chromium.base.ContextUtils;
import org.chromium.components.external_intents.ExternalNavigationDelegate;
import org.chromium.components.external_intents.ExternalNavigationHelper;
import org.chromium.components.external_intents.ExternalNavigationParams;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.url.GURL;

import java.util.List;
import java.util.function.Supplier;

/**
 * Cefrium implementation of {@link ExternalNavigationDelegate}. Supplies the
 * embedder side of Chromium's external_intents machinery so unknown/app schemes
 * (baiduboxapp://, weixin://, intent://, ...) are handed off to Android instead
 * of being loaded as web pages. All the sanitization + gesture/redirect guards
 * live in ExternalNavigationHandler; this class only provides context, the
 * WebContents/WindowAndroid, and policy answers. Chrome-specific concerns
 * (tab model, incognito dialogs, CCT, WebAPK, reparenting) are stubbed off.
 */
class CefExternalNavigationDelegate implements ExternalNavigationDelegate {
    private final WebContents mWebContents;
    private final WindowAndroid mWindowAndroid;
    private final boolean mIncognito;

    CefExternalNavigationDelegate(
            WebContents webContents, WindowAndroid windowAndroid, boolean incognito) {
        mWebContents = webContents;
        mWindowAndroid = windowAndroid;
        mIncognito = incognito;
    }

    @Override
    public Context getContext() {
        if (mWindowAndroid == null) return null;
        return mWindowAndroid.getContext().get();
    }

    @Override
    public boolean willAppHandleIntent(Intent intent) {
        // True if THIS app would handle the intent (=> keep it internal, do not
        // hand off). Resolve against the package manager and compare to self.
        try {
            Context ctx = ContextUtils.getApplicationContext();
            ResolveInfo info = ctx.getPackageManager().resolveActivity(intent, 0);
            return info != null
                    && info.activityInfo != null
                    && ctx.getPackageName().equals(info.activityInfo.packageName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean shouldDisableExternalIntentRequestsForUrl(
            ExternalNavigationParams params, Intent intent) {
        return false;
    }

    @Override
    public void maybeSetWindowId(Intent intent) {}

    @Override
    public void maybeSetPendingReferrer(Intent intent, GURL referrerUrl) {}

    @Override
    public void maybeSetRequestMetadata(
            Intent intent, boolean hasUserGesture, boolean isRendererInitiated) {}

    @Override
    public void maybeSetPendingIncognitoUrl(Intent intent) {}

    @Override
    public boolean isApplicationInForeground() {
        return ApplicationStatus.getStateForApplication()
                == ApplicationState.HAS_RUNNING_ACTIVITIES;
    }

    @Override
    public WindowAndroid getWindowAndroid() {
        return mWindowAndroid;
    }

    @Override
    public WebContents getWebContents() {
        return mWebContents;
    }

    @Override
    public boolean hasValidTab() {
        return mWebContents != null && !mWebContents.isDestroyed();
    }

    @Override
    public boolean canCloseTabOnIntentLaunch() {
        // The SDK/app owns tab lifecycle; never auto-close on launch.
        return false;
    }

    @Override
    public void closeTab() {}

    @Override
    public boolean isIncognito() {
        return mIncognito;
    }

    @Override
    public boolean hasCustomLeavingIncognitoDialog() {
        return false;
    }

    @Override
    public void presentLeavingIncognitoModalDialog(Callback<Boolean> onUserDecision) {
        // Never called: hasCustomLeavingIncognitoDialog() returns false.
        onUserDecision.onResult(true);
    }

    @Override
    public boolean isForTrustedCallingApp(Supplier<List<ResolveInfo>> resolveInfoSupplier) {
        return false;
    }

    @Override
    public boolean shouldLaunchWebApksOnInitialIntent() {
        return false;
    }

    @Override
    public void setPackageForTrustedCallingApp(Intent intent) {}

    @Override
    public boolean shouldAvoidDisambiguationDialog(GURL intentDataUrl) {
        return false;
    }

    @Override
    public String getSelfScheme() {
        return null;
    }

    @Override
    public boolean shouldDisableAllExternalIntents() {
        return false;
    }

    @Override
    public boolean shouldReturnAsActivityResult(GURL url) {
        return false;
    }

    @Override
    public void returnAsActivityResult(GURL url) {
        throw new UnsupportedOperationException(
                "Returning as activity result is not supported.");
    }

    @Override
    public void notifyCctPasswordSavingRecorderOfExternalNavigation() {}

    @Override
    public Intent createIntentToPreventIncognitoAccess(GURL url) {
        return null;
    }

    @Override
    public boolean wasTabLaunchedFromLinkCreatingNewForegroundTab() {
        return false;
    }

    @Override
    public boolean wasTabLaunchedFromLinkCreatingNewWindow() {
        return false;
    }

    @Override
    public boolean shouldLaunchNewWindow(ExternalNavigationParams params) {
        return false;
    }

    @Override
    public boolean shouldSelfNavigationLaunchAsMultipleTask(ExternalNavigationParams params) {
        return false;
    }

    @Override
    public void setExternalNavigationHelper(ExternalNavigationHelper helper) {}

    @Override
    public boolean allowExternalNavigationForHttpProtocols(GURL url) {
        // Do NOT hand http/https navigations off to other apps -- they stay in
        // the browser. Only genuinely external schemes are handed off.
        return false;
    }
}
