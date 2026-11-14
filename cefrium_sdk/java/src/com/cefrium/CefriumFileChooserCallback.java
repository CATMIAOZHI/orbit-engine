// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Answers a file chooser (see {@link CefriumChromeClient#onShowFileChooser}),
 * from any thread. The first answer counts; the page waits until there is one.
 */
public interface CefriumFileChooserCallback {
    /** The chosen files: {@code content://} URIs or file paths. Empty cancels. */
    void select(String[] uris);

    /** Nothing was chosen. */
    void cancel();
}
