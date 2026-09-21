// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

/**
 * Frame lifecycle notifications, mirroring CEF's {@code CefFrameHandler}. All
 * methods are {@code default}. Register with
 * {@link CefriumBrowser#setFrameHandler(CefriumFrameHandler)}.
 *
 * <p>Delivered on the browser UI thread. {@code frameId} is the CEF frame
 * identifier; {@code isMain} marks the main frame.
 */
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
