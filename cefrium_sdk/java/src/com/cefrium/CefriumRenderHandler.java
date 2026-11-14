// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import java.nio.ByteBuffer;

/**
 * Callback interface for receiving rendered frames from a {@link CefriumBrowser}.
 * Frames are delivered as RGBA pixel buffers on the UI thread: bytes R, G, B, A,
 * premultiplied alpha, the order an ARGB_8888 Bitmap and GL_RGBA use, so they go
 * into either unchanged. (Desktop CEF documents BGRA; on Android the frames are
 * RGBA.)
 */
public interface CefriumRenderHandler {
    /**
     * Called when a new frame has been rendered.
     *
     * @param width  Frame width in pixels.
     * @param height Frame height in pixels.
     * @param pixels Direct ByteBuffer containing RGBA pixel data
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
     * @deprecated Use {@link #onVirtualKeyboardRequested(CefriumTextInputMode)}.
     */
    @Deprecated
    default void onVirtualKeyboardRequested(int inputMode) {}

    /**
     * Called (OSR) when the page wants the soft keyboard shown or hidden: hide
     * it for {@link CefriumTextInputMode#NONE}, otherwise show the keyboard
     * the mode asks for (numeric, email, ...). Delivered on the Android main
     * thread.
     */
    @SuppressWarnings("deprecation")
    default void onVirtualKeyboardRequested(CefriumTextInputMode mode) {
        onVirtualKeyboardRequested(mode.engine);
    }

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
     * @param type        The input {@code type} attribute in lower case (e.g.
     *                    "text", "password", "email"; "text" when absent), or
     *                    the lower-case tag name of any other editable (e.g.
     *                    "textarea"), or "".
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
     * @deprecated Use {@link #onQuickMenu(int, int, int, int,
     *     CefriumBrowser.QuickMenuCallback)}.
     */
    @Deprecated
    default void onQuickMenu(int x, int y, int width, int height, int editFlags,
                             CefriumBrowser.QuickMenuCallback callback) {
        // Default: dismiss (no menu) so CEF does not wait on a response.
        callback.cancel();
    }

    /**
     * Called (OSR) when the text-selection quick menu should be shown, e.g.
     * after long-pressing a word: draw a menu at ({@code x},{@code y}), in view
     * pixels, with the items {@code menu} says are available
     * ({@code canCut()}, {@code canCopy()}, {@code canPaste()}), then call
     * exactly one of its {@code cut()}, {@code copy()}, {@code paste()} or
     * {@code cancel()}. The default dismisses it. Delivered on the Android
     * main thread.
     */
    @SuppressWarnings("deprecation")
    default void onQuickMenu(int x, int y, int width, int height,
                             CefriumBrowser.QuickMenuCallback menu) {
        onQuickMenu(x, y, width, height, menu.editFlags(), menu);
    }

    /**
     * A text-selection handle of an off-screen browser, as
     * {@link #onTouchHandleChanged} reports it. A selection has a start and an
     * end handle; a caret in an editable field has one centered handle.
     */
    final class TouchHandle {
        /** Orientation: the handle hangs to the left of its point (selection start). */
        public static final int LEFT = 0;
        /** Orientation: a caret handle, centered under its point. */
        public static final int CENTER = 1;
        /** Orientation: the handle hangs to the right of its point (selection end). */
        public static final int RIGHT = 2;
        /**
         * Width and height of the area a touch must start in to drag the handle,
         * in the coordinates {@link CefriumBrowser#sendTouchEvent} takes. Draw
         * the handle to fill it.
         */
        public static final int SIZE = 40;

        /** Identifies the handle across reports; each new handle gets a new one. */
        public final int id;
        /** Whether to draw it. */
        public final boolean enabled;
        /** {@link #LEFT}, {@link #CENTER} or {@link #RIGHT}. */
        public final int orientation;
        /** Draw the handle flipped top to bottom (the selection end is near the bottom edge). */
        public final boolean mirrorVertical;
        /** Draw the handle flipped left to right. */
        public final boolean mirrorHorizontal;
        /**
         * Top-left corner of the handle, in the coordinates
         * {@link CefriumBrowser#sendTouchEvent} takes. The handle spans
         * {@link #SIZE} from here; a touch that starts inside it drags the handle.
         */
        public final int x;
        public final int y;
        /** Opacity, 0 to 1: the engine fades handles in and out. */
        public final float alpha;

        TouchHandle(int id, boolean enabled, int orientation, boolean mirrorVertical,
                boolean mirrorHorizontal, int x, int y, float alpha) {
            this.id = id;
            this.enabled = enabled;
            this.orientation = orientation;
            this.mirrorVertical = mirrorVertical;
            this.mirrorHorizontal = mirrorHorizontal;
            this.x = x;
            this.y = y;
            this.alpha = alpha;
        }
    }

    /**
     * Called (OSR) when a text-selection handle appears, moves, fades or hides.
     * Draw each enabled handle over your view and forward touches with
     * {@link CefriumBrowser#sendTouchEvent}: a drag that starts on a handle moves
     * that end of the selection. The quick menu ({@link #onQuickMenu}) works
     * without drawing handles.
     */
    default void onTouchHandleChanged(TouchHandle handle) {
        onTouchHandleStateChanged(handle.orientation, handle.x, handle.y, handle.enabled);
    }

    /**
     * Called (OSR) when a text-selection handle's state changes.
     *
     * @param orientation Handle orientation (cef_horizontal_alignment_t).
     * @param x           Handle origin x.
     * @param y           Handle origin y.
     * @param enabled     Whether the handle is enabled/visible.
     * @deprecated Does not say which handle changed; use
     *             {@link #onTouchHandleChanged}.
     */
    @Deprecated
    default void onTouchHandleStateChanged(int orientation, int x, int y,
                                           boolean enabled) {}

    /**
     * Paints an OSR popup widget (e.g. an HTML {@code <select>} dropdown). Draw
     * these pixels over the main view at ({@code x}, {@code y}); without this,
     * dropdowns are invisible in off-screen mode. The buffer is RGBA, valid only
     * during this call.
     *
     * @param x      Popup left, in view pixels.
     * @param y      Popup top, in view pixels.
     * @param width  Popup width in pixels.
     * @param height Popup height in pixels.
     * @param pixels RGBA pixel data ({@code width * height * 4} bytes).
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
