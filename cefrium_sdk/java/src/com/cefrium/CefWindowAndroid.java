// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.app.Activity;
import android.content.Context;

import org.chromium.base.ContextUtils;
import org.chromium.ui.base.ActivityWindowAndroid;
import org.chromium.ui.base.IntentRequestTracker;
import org.chromium.ui.insets.InsetObserver;
import org.chromium.ui.modaldialog.ModalDialogManager;
import org.chromium.ui.modaldialog.ModalDialogManager.ModalDialogType;

/**
 * Cefrium's {@link ActivityWindowAndroid}, adding a {@link ModalDialogManager}.
 *
 * <p>The base WindowAndroid returns no production ModalDialogManager (Chrome supplies one from its
 * Activity). Some Chromium features show tab-modal dialogs through it - notably the File System
 * Access folder-permission prompt - and the native {@code ModalDialogWrapper::ShowTabModal} does a
 * hard {@code CHECK(dialog_manager)}, so without one the process crashes. We back it with
 * {@link CefModalDialogPresenter} (a plain AlertDialog), registered for both TAB and APP dialog
 * types.
 */
public class CefWindowAndroid extends ActivityWindowAndroid {
    private final Context mContext;
    private ModalDialogManager mModalDialogManager;

    public CefWindowAndroid(
            Context context,
            boolean listenToActivityState,
            IntentRequestTracker intentRequestTracker,
            InsetObserver insetObserver,
            boolean occlusionTrackingAllowed) {
        super(
                context,
                listenToActivityState,
                intentRequestTracker,
                insetObserver,
                occlusionTrackingAllowed);
        mContext = context;
    }

    @Override
    public ModalDialogManager getModalDialogManager() {
        if (mModalDialogManager == null) {
            Activity activity = ContextUtils.activityFromContext(mContext);
            if (activity == null) return null;
            CefModalDialogPresenter presenter = new CefModalDialogPresenter(activity);
            mModalDialogManager = new ModalDialogManager(presenter, ModalDialogType.TAB);
            mModalDialogManager.registerPresenter(presenter, ModalDialogType.APP);
        }
        return mModalDialogManager;
    }

    @Override
    public void destroy() {
        if (mModalDialogManager != null) {
            mModalDialogManager.destroy();
            mModalDialogManager = null;
        }
        super.destroy();
    }
}
