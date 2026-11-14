// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Display-related notifications (favicon, load progress, console messages,
 * status text, media-device usage). Mirrors parts of CEF's
 * {@code CefDisplayHandler} that are not already delivered via
 * {@link CefriumBrowser.Callback} / the URL/title listeners. All methods are
 * {@code default} no-ops, so implement only what you need. Register with
 * {@link CefriumBrowser#setDisplayHandler(CefriumDisplayHandler)}.
 *
 * <p>Delivered on the browser UI thread.
 *
 * @deprecated Use {@link CefriumChromeClient} and {@link CefriumNavigationClient#onProgressChanged}.
 */
@Deprecated
public interface CefriumDisplayHandler {
    /**
     * The page's favicon URLs changed (WebView {@code onReceivedIcon} /
     * faviconUrl). Fetch the bytes with
     * {@link CefriumBrowser#downloadImage(String, int, CefriumBrowser.ImageCallback)}.
     *
     * @param iconUrls Candidate favicon URLs, best-first (may be empty).
     */
    default void onFaviconUrlChanged(String[] iconUrls) {}

    /**
     * Fractional load progress (WebView {@code onProgressChanged}).
     *
     * @param progress 0.0 to 1.0.
     */
    default void onLoadingProgress(double progress) {}

    /**
     * A JavaScript console message (WebView {@code onConsoleMessage}).
     *
     * @param level   Log severity (Chromium {@code cef_log_severity_t}).
     * @param message The message text.
     * @param source  Source URL/script.
     * @param line    Line number.
     */
    default void onConsoleMessage(int level, String message, String source, int line) {}

    /** Link-hover status text (empty when the pointer leaves a link). */
    default void onStatusMessage(String value) {}

    /**
     * Camera/microphone in-use state changed (privacy indicator).
     *
     * @param hasVideoAccess The page is using the camera.
     * @param hasAudioAccess The page is using the microphone.
     */
    default void onMediaAccessChange(boolean hasVideoAccess, boolean hasAudioAccess) {}
}
