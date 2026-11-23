// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.content.Context;

import androidx.core.content.FileProvider;

import org.chromium.base.FileProviderUtils;

/**
 * Shares the photo a file chooser takes with the camera app. A subclass, so
 * the manifest merger keeps it apart from an app's own androidx FileProvider.
 */
public final class CefriumFileProvider extends FileProvider {
    static void register(Context context) {
        String authority = context.getPackageName() + ".cefrium.fileprovider";
        FileProviderUtils.setFileProviderUtil(
                file -> FileProvider.getUriForFile(context, authority, file));
    }
}
