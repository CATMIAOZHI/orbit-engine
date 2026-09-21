// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

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
