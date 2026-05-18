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
}
