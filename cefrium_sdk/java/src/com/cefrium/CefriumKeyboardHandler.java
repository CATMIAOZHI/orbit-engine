// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Intercepts keyboard events before/while the page handles them, mirroring
 * CEF's {@code CefKeyboardHandler}. Return {@code true} to mark the event
 * handled (block it from the page). Both methods are {@code default} and return
 * {@code false}. Register with
 * {@link CefriumBrowser#setKeyboardHandler(CefriumKeyboardHandler)}.
 *
 * <p>Called synchronously on the browser UI thread; the return value must be
 * decided synchronously.
 */
public interface CefriumKeyboardHandler {
    /**
     * Called before the event is sent to the renderer.
     *
     * @param type           Key event type ({@link CefriumBrowser#KEY_RAW_DOWN}
     *                       etc.).
     * @param windowsKeyCode Windows virtual-key code.
     * @param modifiers      CEF event flags (modifier bits).
     * @return true to consume the event.
     */
    default boolean onPreKeyEvent(int type, int windowsKeyCode, int modifiers) {
        return false;
    }

    /**
     * Called after the renderer declined to handle the event.
     *
     * @return true to consume the event.
     */
    default boolean onKeyEvent(int type, int windowsKeyCode, int modifiers) {
        return false;
    }
}
