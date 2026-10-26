// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * HTML5 drag-and-drop notifications, mirroring CEF's {@code CefDragHandler}.
 * All methods are {@code default}. Register with
 * {@link CefriumBrowser#setDragHandler(CefriumDragHandler)}.
 *
 * <p>Delivered on the browser UI thread.
 */
public interface CefriumDragHandler {
    /**
     * A drag entered the web view.
     *
     * @param mask Allowed drag operations ({@code cef_drag_operations_mask_t}).
     * @return true to cancel the drop.
     */
    default boolean onDragEnter(int mask) {
        return false;
    }

    /**
     * The page's draggable regions changed (CSS {@code -webkit-app-region}).
     *
     * @param regions Flattened as five ints per region:
     *                {@code x, y, width, height, draggable} (draggable is 1/0).
     */
    default void onDraggableRegionsChanged(int[] regions) {}
}
