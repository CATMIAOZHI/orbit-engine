// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Focus coordination between the web view and native Android views, mirroring
 * CEF's {@code CefFocusHandler}. All methods are {@code default}. Register with
 * {@link CefriumBrowser#setFocusHandler(CefriumFocusHandler)}.
 *
 * <p>Delivered on the browser UI thread.
 *
 * <p>Experimental: kept for parity with desktop CEF; no app has been seen to
 * need it, so it may go at 1.0 unless one asks (FEATURE-092 D5).
 */
@CefriumExperimental
public interface CefriumFocusHandler {
    /**
     * The browser is giving up focus (tab-out).
     *
     * @param next True to focus the next control, false for the previous.
     */
    default void onTakeFocus(boolean next) {}

    /**
     * The browser component is requesting focus.
     *
     * @param source Chromium {@code cef_focus_source_t}.
     * @return true to cancel the focus change.
     */
    default boolean onSetFocus(int source) {
        return false;
    }

    /** The browser received focus. */
    default void onGotFocus() {}
}
