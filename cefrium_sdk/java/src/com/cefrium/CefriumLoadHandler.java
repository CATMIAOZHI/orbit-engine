// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

/**
 * Per-frame load notifications. Complements the binary loading state delivered
 * via {@link CefriumBrowser.OnLoadingStateChangedListener}. Mirrors CEF's
 * {@code CefLoadHandler}. Only the main frame is reported. All methods are
 * {@code default} no-ops. Register with
 * {@link CefriumBrowser#setLoadHandler(CefriumLoadHandler)}.
 *
 * <p>Delivered on the browser UI thread.
 */
public interface CefriumLoadHandler {
    /** The main frame started loading {@code url}. */
    default void onLoadStart(String url) {}

    /**
     * The main frame failed to load (WebView {@code onReceivedError}).
     *
     * @param errorCode Chromium net error code (see net_error_list.h).
     * @param errorText Human-readable error text (may be empty).
     * @param failedUrl The URL that failed to load.
     */
    default void onLoadError(int errorCode, String errorText, String failedUrl) {}
}
