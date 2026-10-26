// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Handles Chrome command IDs, mirroring CEF's {@code CefCommandHandler}.
 * Register with {@link CefriumBrowser#setCommandHandler(CefriumCommandHandler)}.
 *
 * <p>Called synchronously on the browser UI thread.
 */
public interface CefriumCommandHandler {
    /**
     * Execute (or veto) a Chrome command.
     *
     * @param commandId   Chromium command ID.
     * @param disposition Chromium {@code cef_window_open_disposition_t}.
     * @return true if handled (suppresses the default behaviour).
     */
    default boolean onChromeCommand(int commandId, int disposition) {
        return false;
    }
}
