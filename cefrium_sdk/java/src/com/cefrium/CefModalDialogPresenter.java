// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be found
// in the LICENSE file.

package com.cefrium;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import androidx.activity.ComponentDialog;

import org.chromium.base.Callback;
import org.chromium.ui.modaldialog.DialogDismissalCause;
import org.chromium.ui.modaldialog.ModalDialogManager;
import org.chromium.ui.modaldialog.ModalDialogProperties;
import org.chromium.ui.modelutil.PropertyModel;

import java.util.ArrayList;

/**
 * A {@link ModalDialogManager.Presenter} that renders Chromium dialog models with a plain Android
 * {@link AlertDialog}.
 *
 * <p>Chrome's {@code AppModalPresenter} inflates a Chromium dialog layout
 * (ButtonCompat + ThemeOverlay.BrowserUI.ModalDialog styles + ColorStateLists) whose resource graph
 * does not resolve cleanly inside an embedding AAR (the same coupling that broke the contacts
 * picker). This draws the dialog with the platform AlertDialog instead - same approach as Cefrium's
 * device chooser and contact picker. It is enough for the dialogs an embedding hits today, the most
 * important being the File System Access "let this site view files in this folder?" permission
 * prompt (a tab-modal dialog). Without any presenter the native
 * {@code ModalDialogWrapper::ShowTabModal} CHECK(dialog_manager) crashes the process.
 */
public class CefModalDialogPresenter extends ModalDialogManager.Presenter {
    private final Context mContext;
    private AlertDialog mDialog;

    public CefModalDialogPresenter(Context context) {
        mContext = context;
    }

    @Override
    protected void addDialogView(
            PropertyModel model,
            Callback<ComponentDialog> onDialogCreatedCallback,
            Callback<View> onDialogShownCallback) {
        CharSequence title = model.get(ModalDialogProperties.TITLE);
        CharSequence message = model.get(ModalDialogProperties.MESSAGE_PARAGRAPH_1);
        if (TextUtils.isEmpty(message)) {
            ArrayList<CharSequence> paragraphs = model.get(ModalDialogProperties.MESSAGE_PARAGRAPHS);
            if (paragraphs != null && !paragraphs.isEmpty()) {
                message = TextUtils.join("\n\n", paragraphs);
            }
        }
        String positive = model.get(ModalDialogProperties.POSITIVE_BUTTON_TEXT);
        String negative = model.get(ModalDialogProperties.NEGATIVE_BUTTON_TEXT);
        ModalDialogProperties.Controller controller =
                model.get(ModalDialogProperties.CONTROLLER);

        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        if (!TextUtils.isEmpty(title)) builder.setTitle(title);
        if (!TextUtils.isEmpty(message)) builder.setMessage(message);
        if (!TextUtils.isEmpty(positive)) {
            builder.setPositiveButton(
                    positive,
                    (d, w) -> {
                        if (controller != null) {
                            controller.onClick(model, ModalDialogProperties.ButtonType.POSITIVE);
                        }
                    });
        }
        if (!TextUtils.isEmpty(negative)) {
            builder.setNegativeButton(
                    negative,
                    (d, w) -> {
                        if (controller != null) {
                            controller.onClick(model, ModalDialogProperties.ButtonType.NEGATIVE);
                        }
                    });
        }
        // Back press / touch outside -> let the manager run the dialog's dismiss handler.
        builder.setOnCancelListener(
                d -> dismissCurrentDialog(
                        DialogDismissalCause.NAVIGATE_BACK_OR_TOUCH_OUTSIDE));

        mDialog = builder.create();
        mDialog.setCanceledOnTouchOutside(
                model.get(ModalDialogProperties.CANCEL_ON_TOUCH_OUTSIDE));
        mDialog.show();

        if (onDialogShownCallback != null) {
            View decor = mDialog.getWindow() != null ? mDialog.getWindow().getDecorView() : null;
            if (decor != null) onDialogShownCallback.onResult(decor);
        }
    }

    @Override
    protected void removeDialogView(PropertyModel model) {
        if (mDialog != null) {
            mDialog.setOnCancelListener(null);
            mDialog.dismiss();
            mDialog = null;
        }
    }
}
