// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Frame lifecycle notifications, mirroring CEF's {@code CefFrameHandler}. All
 * methods are {@code default}. Register with
 * {@link CefriumBrowser#setFrameHandler(CefriumFrameHandler)}.
 *
 * <p>Delivered on the browser UI thread. {@code frameId} is the CEF frame
 * identifier; {@code isMain} marks the main frame.
 *
 * <p>Experimental: kept for parity with desktop CEF; no app has been seen to
 * need it, so it may go at 1.0 unless one asks (FEATURE-092 D5).
 */
@CefriumExperimental
public interface CefriumFrameHandler {
    /** A new frame was created. */
    default void onFrameCreated(String frameId, boolean isMain) {}

    /** A frame was destroyed (last notification for that frame). */
    default void onFrameDestroyed(String frameId, boolean isMain) {}

    /** A frame can route commands to/from its renderer. */
    default void onFrameAttached(String frameId, boolean isMain) {}

    /** A frame lost its renderer connection. */
    default void onFrameDetached(String frameId, boolean isMain) {}

    /** The main frame changed (creation, navigation, or destruction). */
    default void onMainFrameChanged(String frameId, boolean isMain) {}
}
