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

    /**
     * Called (OSR) when the focused editable node changes, carrying the field's
     * current value, placeholder and type read from the DOM in the renderer.
     * Pairs with {@link #onVirtualKeyboardRequested}: use this to populate a
     * native input overlay that mirrors the focused web field.
     *
     * <p>Delivered on the Android main thread.
     *
     * @param editable    True if an editable field is focused; false on blur (in
     *                    which case value/placeholder/type are empty).
     * @param value       The field's current value (already-typed text).
     * @param placeholder The field's {@code placeholder} attribute, or "".
     * @param type        The input {@code type} attribute (e.g. "text",
     *                    "password", "email"), or the tag name for non-input
     *                    editables (e.g. "textarea"), or "".
     */
    default void onFocusedNodeChanged(boolean editable, String value,
                                      String placeholder, String type) {}

    /**
     * Called (OSR) when the text-selection quick menu (Cut / Copy / Paste)
     * should be shown -- e.g. after long-pressing a word. Draw a small menu over
     * your OSR view at ({@code x},{@code y}) offering the items enabled in
     * {@code editFlags} (bitmask of {@link CefriumBrowser#QM_CAN_CUT},
     * {@link CefriumBrowser#QM_CAN_COPY}, {@link CefriumBrowser#QM_CAN_PASTE}),
     * then call exactly one of {@code callback.cut()/copy()/paste()}, or
     * {@code callback.cancel()} to dismiss. CEF performs the edit itself.
     *
     * <p>Without handling this, OSR text selection has no copy/paste menu.
     * Delivered on the Android main thread.
     *
     * @param x         Menu anchor x, in view pixels.
     * @param y         Menu anchor y, in view pixels.
     * @param width     Selection/anchor width.
     * @param height    Selection/anchor height.
     * @param editFlags Bitmask of available items (QM_CAN_*).
     * @param callback  Result callback (call exactly one method once).
     */
    default void onQuickMenu(int x, int y, int width, int height, int editFlags,
                             CefriumBrowser.QuickMenuCallback callback) {
        // Default: dismiss (no menu) so CEF does not wait on a response.
        callback.cancel();
    }

    /**
     * Called (OSR) when a text-selection handle's state changes. Optional: draw
     * draggable selection handles over your OSR view at ({@code x},{@code y}).
     * The quick menu (onQuickMenu) works without drawing these.
     *
     * @param orientation Handle orientation (cef_horizontal_alignment_t).
     * @param x           Handle origin x, view pixels.
     * @param y           Handle origin y, view pixels.
     * @param enabled     Whether the handle is enabled/visible.
     */
    default void onTouchHandleStateChanged(int orientation, int x, int y,
                                           boolean enabled) {}

    /**
     * Paints an OSR popup widget (e.g. an HTML {@code <select>} dropdown). Draw
     * these pixels over the main view at ({@code x}, {@code y}); without this,
     * dropdowns are invisible in off-screen mode. The buffer is BGRA, valid only
     * during this call.
     *
     * @param x      Popup left, in view pixels.
     * @param y      Popup top, in view pixels.
     * @param width  Popup width in pixels.
     * @param height Popup height in pixels.
     * @param pixels BGRA pixel data ({@code width * height * 4} bytes).
     */
    default void onPopupPaint(int x, int y, int width, int height, ByteBuffer pixels) {}

    /** An OSR popup widget was shown ({@code true}) or hidden ({@code false}). */
    default void onPopupShow(boolean show) {}

    /** The OSR popup widget's geometry (view pixels). */
    default void onPopupSize(int x, int y, int width, int height) {}

    /**
     * The IME composition rectangle (bounds of the first composed character, in
     * view pixels) changed &mdash; use it to position an IME overlay. Pairs with
     * the OSR IME input path.
     */
    default void onImeCompositionRangeChanged(int x, int y, int width, int height) {}

    /** The selected text changed (empty when the selection is cleared). */
    default void onTextSelectionChanged(String selectedText) {}

    /** The page scroll offset changed (CSS pixels). */
    default void onScrollOffsetChanged(double x, double y) {}
}
