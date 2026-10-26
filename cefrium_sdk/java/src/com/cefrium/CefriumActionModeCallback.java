// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import org.chromium.base.ContextUtils;
import org.chromium.base.PackageManagerUtils;
import org.chromium.content_public.browser.ActionModeCallback;
import org.chromium.content_public.browser.ActionModeCallbackHelper;
import org.chromium.content_public.browser.SelectionMenuItem;
import org.chromium.content_public.browser.SelectionPopupController;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.ui.base.WindowAndroid.IntentCallback;

/**
 * FEATURE-065: text-selection floating toolbar (Select All / Copy / Paste /
 * Share / Web Search / process-text) for the windowed Cefrium browser.
 *
 * <p>Content lazily creates the Java SelectionPopupControllerImpl, but its
 * floating toolbar is gated on an installed ActionMode callback
 * (isActionModeSupported() == mCallback != EMPTY_CALLBACK). Without one, the
 * toolbar never shows. This installs one, mirroring how Chrome
 * (ChromeActionModeHandler) and WebView (AwActionModeCallback) wire it; content
 * only provides the reusable ActionModeCallbackHelper.
 */
class CefriumActionModeCallback extends ActionModeCallback implements IntentCallback {
    private final WebContents mWebContents;
    private final SelectionPopupController mSelectionPopupController;
    private final ActionModeCallbackHelper mHelper;
    private int mAllowedMenuItems;

    CefriumActionModeCallback(CefriumBrowser browser, WebContents webContents) {
        mWebContents = webContents;
        mSelectionPopupController = SelectionPopupController.fromWebContents(webContents);
        mHelper = mSelectionPopupController.getActionModeCallbackHelper();
        // Copy/Cut/Paste/Select-All are always handled by the helper; these are
        // the optional items. Recomputed in onCreateActionMode.
        mHelper.setAllowedMenuItems(0);
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        int allowedItems =
                ActionModeCallbackHelper.MENU_ITEM_SHARE
                        | (isWebSearchAvailable()
                                ? ActionModeCallbackHelper.MENU_ITEM_WEB_SEARCH : 0)
                        | ActionModeCallbackHelper.MENU_ITEM_PROCESS_TEXT;
        if (allowedItems != mAllowedMenuItems) {
            mHelper.setAllowedMenuItems(allowedItems);
            mAllowedMenuItems = allowedItems;
        }
        mHelper.onCreateActionMode(mode, menu);
        return true;
    }

    private boolean isWebSearchAvailable() {
        Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);
        intent.putExtra(android.app.SearchManager.EXTRA_NEW_SEARCH, true);
        return !PackageManagerUtils.queryIntentActivities(
                        intent, PackageManager.MATCH_DEFAULT_ONLY)
                .isEmpty();
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return mHelper.onPrepareActionMode(mode, menu);
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        if (!mHelper.isActionModeValid()) return true;
        if (isProcessTextMenuItem(item.getGroupId())) {
            processText(item.getIntent());
            // Do not dismiss (matches TextView behavior).
            return true;
        }
        return mHelper.onActionItemClicked(mode, item);
    }

    @Override
    public boolean onDropdownItemClicked(SelectionMenuItem item, boolean closeMenu) {
        if (isProcessTextMenuItem(item.groupId)) {
            if (item.intent != null) processText(item.intent);
            return true;
        }
        return mHelper.onDropdownItemClicked(item, closeMenu);
    }

    private boolean isProcessTextMenuItem(final int groupId) {
        return groupId == org.chromium.content.R.id.select_action_menu_text_processing_items;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        mHelper.onDestroyActionMode();
    }

    @Override
    public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
        mHelper.onGetContentRect(mode, view, outRect);
    }

    private void processText(Intent intent) {
        String query =
                ActionModeCallbackHelper.sanitizeQuery(
                        mHelper.getSelectedText(),
                        ActionModeCallbackHelper.MAX_SEARCH_QUERY_LENGTH);
        if (TextUtils.isEmpty(query)) return;
        intent.putExtra(Intent.EXTRA_PROCESS_TEXT, query);

        WindowAndroid window = mWebContents.getTopLevelNativeWindow();
        // Prefer an activity-for-result launch so the replacement comes back;
        // fall back to a plain launch if there is no activity window.
        if (window == null || !window.showIntent(intent, this, null)) {
            Context context = ContextUtils.getApplicationContext();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
            } catch (ActivityNotFoundException ex) {
                // No app handles it -- ignore.
            }
        }
    }

    // WindowAndroid.IntentCallback -- process-text replacement result.
    @Override
    public void onIntentCompleted(int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            CharSequence value = data.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            String result = (value == null) ? null : value.toString();
            mSelectionPopupController.handleTextReplacementAction(result);
        }
    }
}
