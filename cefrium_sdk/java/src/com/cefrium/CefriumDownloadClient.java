// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Called on the UI thread. Decides and follows the downloads of a
 * {@link CefriumBrowser}, like {@code android.webkit.DownloadListener} with
 * control. Set it with {@link CefriumBrowser#setDownloadClient}.
 *
 * <p>Without a client a download starts on its own and is saved in the app's
 * own storage; from the next release it will be refused, as in WebView.
 */
@CefriumExperimental
public interface CefriumDownloadClient {

    /**
     * A download is about to start. Call {@code callback.allow(path)} to save
     * it ({@code null}: under its suggested name in the app's Downloads
     * directory, {@code Context.getExternalFilesDir(DIRECTORY_DOWNLOADS)}) or
     * {@code callback.cancel()}; now or later (after asking the user), from
     * any thread. Nothing is written until then.
     */
    void onBeforeDownload(CefriumBrowser browser, CefriumBrowser.DownloadItem item,
            CefriumBrowser.BeforeDownloadCallback callback);

    /**
     * Progress of an allowed download; {@link CefriumBrowser.DownloadItem}
     * also cancels, pauses and resumes it.
     */
    default void onDownloadUpdated(CefriumBrowser browser, CefriumBrowser.DownloadItem item) {}
}
