// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Handles Chrome command IDs, mirroring CEF's {@code CefCommandHandler}.
 * Register with {@link CefriumBrowser#setCommandHandler(CefriumCommandHandler)}.
 *
 * <p>Called synchronously on the browser UI thread.
 *
 * <p>Experimental: kept for parity with desktop CEF; no app has been seen to
 * need it, so it may go at 1.0 unless one asks (FEATURE-092 D5).
 */
@CefriumExperimental
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
