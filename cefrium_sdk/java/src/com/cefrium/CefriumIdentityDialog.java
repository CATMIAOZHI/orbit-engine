// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.app.Activity;
import android.app.AlertDialog;

import java.util.List;

/** The default FedCM account chooser: a list dialog on the browser's activity. */
final class CefriumIdentityDialog {
    private CefriumIdentityDialog() {}

    // Chromium's own (translated) title. The AAR carries Chromium's resources
    // but the SDK is compiled without their R classes, so look it up by name.
    private static String title(Activity activity, String relyingParty, String idp) {
        int id = activity.getResources().getIdentifier(
                "account_selection_sheet_title_explicit_signin", "string",
                activity.getPackageName());
        return id != 0 ? activity.getString(id, relyingParty, idp)
                : "Sign in to " + relyingParty + " with " + idp;
    }

    static AlertDialog show(Activity activity, String relyingParty,
            List<CefriumIdentityHandler.Account> accounts,
            CefriumIdentityHandler.Request request) {
        CharSequence[] labels = new CharSequence[accounts.size()];
        for (int i = 0; i < labels.length; i++) {
            CefriumIdentityHandler.Account a = accounts.get(i);
            String name = a.name.isEmpty() ? a.email : a.name;
            labels[i] = a.email.isEmpty() || a.email.equals(name)
                    ? name : name + "\n" + a.email;
        }
        String idp = accounts.isEmpty() ? "" : accounts.get(0).identityProvider;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title(activity, relyingParty, idp))
                .setItems(labels, (d, which) -> request.select(accounts.get(which)))
                .setNegativeButton(android.R.string.cancel, (d, w) -> request.cancel())
                .setOnCancelListener(d -> request.cancel())
                .create();
        dialog.show();
        return dialog;
    }
}
