// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import java.nio.ByteBuffer;

/**
 * Callback interface for receiving rendered frames from a {@link CefriumBrowser}.
 * Frames are delivered as BGRA pixel buffers on the UI thread.
 */
public interface CefriumRenderHandler {
    /**
     * Called when a new frame has been rendered.
     *
     * @param width  Frame width in pixels.
     * @param height Frame height in pixels.
     * @param pixels Direct ByteBuffer containing BGRA pixel data
     *               (width * height * 4 bytes). Valid only during this call.
     */
    void onPaint(int width, int height, ByteBuffer pixels);

    /**
     * Called when the focused editable node changes and the on-screen keyboard
     * should be shown or hidden (OSR mode). Mirrors CEF's
     * {@code CefRenderHandler::OnVirtualKeyboardRequested}.
     *
     * <p>Use this to drive {@link android.view.inputmethod.InputMethodManager}:
     * show the soft keyboard (and request focus on the hosting View) when an
     * editable is focused, hide it otherwise. Pair it with a custom
     * {@link android.view.inputmethod.InputConnection} that forwards composing
     * and committed text to {@link CefriumBrowser#imeSetComposition},
     * {@link CefriumBrowser#imeCommitText},
     * {@link CefriumBrowser#imeFinishComposingText} and
     * {@link CefriumBrowser#imeCancelComposition}.
     *
     * <p>Delivered on the Android main thread.
     *
     * @param inputMode The requested text input mode, matching CEF's
     *                  {@code cef_text_input_mode_t} (see the
     *                  {@code CefriumBrowser.TEXT_INPUT_MODE_*} constants). Hide
     *                  the keyboard when this equals
     *                  {@link CefriumBrowser#TEXT_INPUT_MODE_NONE} ({@code 1});
     *                  any other value (including
     *                  {@link CefriumBrowser#TEXT_INPUT_MODE_DEFAULT}, which is
     *                  {@code 0}) means an editable is focused and the keyboard
     *                  should be shown. The specific value also indicates the
     *                  preferred keyboard flavour (numeric, email, ...).
     */
    default void onVirtualKeyboardRequested(int inputMode) {}
}
