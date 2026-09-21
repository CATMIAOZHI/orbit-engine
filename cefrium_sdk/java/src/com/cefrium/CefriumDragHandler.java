// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

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
