// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

/**
 * Focus coordination between the web view and native Android views, mirroring
 * CEF's {@code CefFocusHandler}. All methods are {@code default}. Register with
 * {@link CefriumBrowser#setFocusHandler(CefriumFocusHandler)}.
 *
 * <p>Delivered on the browser UI thread.
 */
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
