// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

/**
 * A browser instance that renders web content off-screen.
 * Frames are delivered via {@link CefriumRenderHandler#onPaint}.
 *
 * <pre>
 * CefriumBrowser browser = new CefriumBrowser();
 * browser.setRenderHandler(new CefriumRenderHandler() {
 *     public void onPaint(int width, int height, java.nio.ByteBuffer pixels) {
 *         // BGRA pixel buffer, width * height * 4 bytes
 *     }
 * });
 * browser.loadUrl("https://example.com");
 * </pre>
 */
public class CefriumBrowser {
    private long mNativePtr;  // Pointer to native CefBrowser
    private CefriumRenderHandler mRenderHandler;

    /**
     * Create a new off-screen browser with default settings.
     */
    public CefriumBrowser() {
        this(800, 600);
    }

    /**
     * Create a new off-screen browser with specified viewport size.
     *
     * @param width  Viewport width in pixels.
     * @param height Viewport height in pixels.
     */
    public CefriumBrowser(int width, int height) {
        if (!Cefrium.isInitialized()) {
            throw new IllegalStateException(
                    "Cefrium.initialize() must be called before creating browsers");
        }
        mNativePtr = nativeCreate(width, height);
    }

    /**
     * Set the render handler to receive painted frames.
     */
    public void setRenderHandler(CefriumRenderHandler handler) {
        mRenderHandler = handler;
        nativeSetRenderHandler(mNativePtr);
    }

    /**
     * Navigate to a URL.
     */
    public void loadUrl(String url) {
        nativeLoadUrl(mNativePtr, url);
    }

    /**
     * Close this browser and release native resources.
     */
    public void close() {
        if (mNativePtr != 0) {
            nativeClose(mNativePtr);
            mNativePtr = 0;
        }
    }

    // Called from native when a frame is painted.
    @SuppressWarnings("unused")
    private void onPaint(int width, int height, java.nio.ByteBuffer pixels) {
        if (mRenderHandler != null) {
            mRenderHandler.onPaint(width, height, pixels);
        }
    }

    // Native methods.
    private static native long nativeCreate(int width, int height);
    private static native void nativeSetRenderHandler(long nativePtr);
    private static native void nativeLoadUrl(long nativePtr, String url);
    private static native void nativeClose(long nativePtr);
}
