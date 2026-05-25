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
    private OnUrlChangedListener mUrlListener;
    private OnTitleChangedListener mTitleListener;
    private OnLoadingStateChangedListener mLoadingListener;
    private OnRequestInterceptedListener mRequestListener;
    private OnFullscreenChangedListener mFullscreenListener;

    // Surface rendering internals — not exposed in public API.
    private android.app.Activity mActivity;
    private org.chromium.ui.base.WindowAndroid mWindowAndroid;
    private org.chromium.components.embedder_support.view.ContentViewRenderView mContentViewRenderView;
    private android.widget.FrameLayout mSurfaceContainer;
    private boolean mWebContentsConnected;

    // Private marker constructor for factory methods — does NOT create native.
    private CefriumBrowser(boolean marker) {
        if (!Cefrium.isInitialized()) {
            throw new IllegalStateException(
                    "Cefrium.initialize() must be called before creating browsers");
        }
        // mNativePtr set by factory method after construction.
    }

    /**
     * Create a new off-screen browser with default settings.
     */
    public CefriumBrowser() {
        this(800, 600, 1.0f);
    }

    /**
     * Create a new off-screen browser with specified viewport size.
     * Device scale factor defaults to 1.0 (desktop). For mobile,
     * use {@link #CefriumBrowser(int, int, float)}.
     *
     * @param width  Viewport width in physical pixels.
     * @param height Viewport height in physical pixels.
     */
    public CefriumBrowser(int width, int height) {
        this(width, height, 1.0f);
    }

    /**
     * Create a new off-screen browser with specified viewport size
     * and device scale factor.
     *
     * <p>The scale factor controls how CSS pixels map to physical pixels.
     * For example, on a 1080px wide screen with scale 2.75, the CSS
     * viewport is ~393px — triggering mobile layouts.</p>
     *
     * @param width  Viewport width in physical pixels.
     * @param height Viewport height in physical pixels.
     * @param scale  Device scale factor (e.g., 2.75 for Xiaomi Redmi Note 13 Pro).
     */
    public CefriumBrowser(int width, int height, float scale) {
        if (!Cefrium.isInitialized()) {
            throw new IllegalStateException(
                    "Cefrium.initialize() must be called before creating browsers");
        }
        mNativePtr = nativeCreate(width, height, scale);
    }

    /**
     * Set the render handler to receive painted frames.
     */
    public void setRenderHandler(CefriumRenderHandler handler) {
        mRenderHandler = handler;
        nativeSetRenderHandler(mNativePtr, this);
    }

    /**
     * Navigate to a URL.
     */
    public void loadUrl(String url) {
        nativeLoadUrl(mNativePtr, url);
        // In Surface mode, connect WebContents after first load.
        if (mSurfaceContainer != null && !mWebContentsConnected) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(this::connectWebContentsInternal, 2000);
        }
    }

    /**
     * Navigate back in history.
     */
    public void goBack() {
        if (mNativePtr != 0) nativeGoBack(mNativePtr);
    }

    /**
     * Navigate forward in history.
     */
    public void goForward() {
        if (mNativePtr != 0) nativeGoForward(mNativePtr);
    }

    /**
     * Set dark mode via CEF API (CefRequestContext::SetChromeColorSchemeMode).
     * Call after browser is created and loaded.
     *
     * @param dark true for dark mode, false for light mode.
     */
    public void setDarkMode(boolean dark) {
        if (mNativePtr != 0) nativeSetDarkMode(mNativePtr, dark);
    }

    /**
     * Listener for URL changes. Called by CEF's CefDisplayHandler::OnAddressChange.
     */
    public interface OnUrlChangedListener {
        void onUrlChanged(String url);
    }

    /**
     * Listener for title changes. Called by CEF's CefDisplayHandler::OnTitleChange.
     */
    public interface OnTitleChangedListener {
        void onTitleChanged(String title);
    }

    /**
     * Listener for network requests. Called by CEF's
     * CefResourceRequestHandler::OnBeforeResourceLoad for every request.
     * This level of request visibility is not available in Android WebView.
     */
    public interface OnRequestInterceptedListener {
        void onRequestIntercepted(String url, String method, boolean blocked);
    }

    /**
     * Listener for fullscreen mode changes. Called by CEF's
     * CefDisplayHandler::OnFullscreenModeChange.
     */
    public interface OnFullscreenChangedListener {
        void onFullscreenChanged(boolean fullscreen);
    }

    /**
     * Listener for loading state changes. Called by CEF's
     * CefLoadHandler::OnLoadingStateChange.
     */
    public interface OnLoadingStateChangedListener {
        void onLoadingStateChanged(boolean isLoading, boolean canGoBack,
                                   boolean canGoForward);
    }

    /**
     * Set a listener for URL changes during navigation.
     */
    public void setOnUrlChangedListener(OnUrlChangedListener listener) {
        mUrlListener = listener;
        if (mNativePtr != 0) {
            nativeSetDisplayCallback(mNativePtr, this);
        }
    }

    /**
     * Set a listener for title changes.
     */
    public void setOnTitleChangedListener(OnTitleChangedListener listener) {
        mTitleListener = listener;
    }

    /**
     * Set a listener for fullscreen mode changes.
     */
    public void setOnFullscreenChangedListener(OnFullscreenChangedListener listener) {
        mFullscreenListener = listener;
    }

    /**
     * Set a listener for network request interception.
     * Called for EVERY request — use for network inspection,
     * debugging, or custom request auditing.
     */
    public void setOnRequestInterceptedListener(OnRequestInterceptedListener listener) {
        mRequestListener = listener;
    }

    // Called from native CefDisplayHandler::OnAddressChange.
    @SuppressWarnings("unused")
    private void onUrlChanged(String url) {
        if (mUrlListener != null) {
            mUrlListener.onUrlChanged(url);
        }
    }

    /**
     * Set a listener for loading state changes.
     */
    public void setOnLoadingStateChangedListener(OnLoadingStateChangedListener listener) {
        mLoadingListener = listener;
    }

    // Called from native CefDisplayHandler::OnTitleChange.
    @SuppressWarnings("unused")
    private void onTitleChanged(String title) {
        if (mTitleListener != null) {
            mTitleListener.onTitleChanged(title);
        }
    }

    // Called from native CefDisplayHandler::OnFullscreenModeChange.
    @SuppressWarnings("unused")
    private void onFullscreenChanged(boolean fullscreen) {
        if (mFullscreenListener != null) {
            mFullscreenListener.onFullscreenChanged(fullscreen);
        }
    }

    // Called from native CefResourceRequestHandler::OnBeforeResourceLoad.
    @SuppressWarnings("unused")
    private void onRequestIntercepted(String url, String method, boolean blocked) {
        if (mRequestListener != null) {
            mRequestListener.onRequestIntercepted(url, method, blocked);
        }
    }

    // Called from native CefLoadHandler::OnLoadingStateChange.
    @SuppressWarnings("unused")
    private void onLoadingStateChanged(boolean isLoading, boolean canGoBack,
                                       boolean canGoForward) {
        if (mLoadingListener != null) {
            mLoadingListener.onLoadingStateChanged(isLoading, canGoBack, canGoForward);
        }
    }

    /**
     * Reload the current page.
     */
    public void reload() {
        if (mNativePtr != 0) nativeReload(mNativePtr);
    }

    /**
     * @return true if the browser can navigate back.
     */
    public boolean canGoBack() {
        return mNativePtr != 0 && nativeCanGoBack(mNativePtr);
    }

    /**
     * Close this browser and release native resources.
     */
    public void close() {
        if (mNativePtr != 0) {
            nativeClose(mNativePtr);
            mNativePtr = 0;
        }
        if (mContentViewRenderView != null) {
            mContentViewRenderView.destroy();
            mContentViewRenderView = null;
        }
        if (mWindowAndroid != null) {
            mWindowAndroid.destroy();
            mWindowAndroid = null;
        }
    }

    // Called from native when a frame is painted.
    @SuppressWarnings("unused")
    private void onPaint(int width, int height, java.nio.ByteBuffer pixels) {
        if (mRenderHandler != null) {
            mRenderHandler.onPaint(width, height, pixels);
        }
    }

    /**
     * Force the browser to repaint. Call this if you need
     * an updated frame (e.g. after content changes).
     */
    public void invalidate() {
        if (mNativePtr != 0) {
            nativeInvalidate(mNativePtr);
        }
    }

    /**
     * Forward a touch event to the browser.
     *
     * @param action 0=DOWN, 1=UP, 2=MOVE, 3=CANCEL
     * @param x X coordinate in browser viewport pixels
     * @param y Y coordinate in browser viewport pixels
     * @param pointerId Pointer ID for multi-touch
     */
    public void sendTouchEvent(int action, float x, float y, int pointerId) {
        if (mNativePtr != 0) {
            nativeSendTouchEvent(mNativePtr, action, x, y, pointerId);
        }
    }

    /**
     * Get the number of blocked ad/tracking requests.
     */
    public int getBlockedCount() {
        if (mNativePtr != 0) {
            return nativeGetBlockedCount(mNativePtr);
        }
        return 0;
    }

    /**
     * Create a browser that renders directly to an Android Surface.
     * Enables video playback, audio, and hardware-accelerated compositing.
     *
     * <p>All Chromium rendering infrastructure (WindowAndroid,
     * ContentViewRenderView, ContentView) is created and managed
     * internally. The browser view is added to the Activity's content.</p>
     *
     * <pre>
     * CefriumBrowser browser = CefriumBrowser.createWithSurface(this);
     * browser.setOnUrlChangedListener(url -> urlBar.setText(url));
     * browser.loadUrl("https://example.com");
     * </pre>
     *
     * @param activity The Activity to render in. A FrameLayout container
     *                 is returned via {@link #getSurfaceContainer()}.
     * @return A new CefriumBrowser instance with Surface rendering.
     */
    public static CefriumBrowser createWithSurface(android.app.Activity activity) {
        if (!Cefrium.isInitialized()) {
            throw new IllegalStateException(
                    "Cefrium.initialize() must be called before creating browsers");
        }

        CefriumBrowser browser = new CefriumBrowser(true);

        // Create Chromium rendering infrastructure internally.
        browser.mWindowAndroid = new org.chromium.ui.base.WindowAndroid(
                activity, false);

        browser.mContentViewRenderView =
                new org.chromium.components.embedder_support.view.ContentViewRenderView(
                        activity);
        browser.mContentViewRenderView.onNativeLibraryLoaded(
                browser.mWindowAndroid);

        // Container that holds the rendering surface.
        browser.mSurfaceContainer = new android.widget.FrameLayout(activity);
        browser.mSurfaceContainer.addView(browser.mContentViewRenderView,
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        // Create native browser (non-windowless mode).
        float density = activity.getResources().getDisplayMetrics().density;
        browser.mNativePtr = nativeCreateWithSurface(
                browser.mWindowAndroid, null,
                activity.getResources().getDisplayMetrics().widthPixels,
                activity.getResources().getDisplayMetrics().heightPixels,
                density);

        browser.mActivity = activity;

        return browser;
    }

    /**
     * Get the container View for Surface rendering.
     * Add this to your Activity's layout.
     *
     * @return FrameLayout containing the browser surface.
     */
    public android.widget.FrameLayout getSurfaceContainer() {
        return mSurfaceContainer;
    }

    // Internal: connect WebContents to rendering pipeline.
    // Called after the browser has loaded its first page.
    void connectWebContentsInternal() {
        if (mNativePtr == 0 || mActivity == null) return;
        org.chromium.content_public.browser.WebContents wc = nativeGetWebContents(mNativePtr);
        if (wc == null) return;
        if (mWebContentsConnected) return;
        mWebContentsConnected = true;

        org.chromium.components.embedder_support.view.ContentView cv =
                org.chromium.components.embedder_support.view.ContentView
                        .createContentView(mActivity, wc);
        org.chromium.ui.base.ViewAndroidDelegate delegate =
                org.chromium.ui.base.ViewAndroidDelegate.createBasicDelegate(cv);
        wc.setDelegates("", delegate, cv, mWindowAndroid,
                org.chromium.content_public.browser.WebContents
                        .createDefaultInternalsHolder());

        mSurfaceContainer.addView(cv,
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        int w = mContentViewRenderView.getWidth();
        int h = mContentViewRenderView.getHeight();
        if (w > 0 && h > 0) wc.setSize(w, h);

        mContentViewRenderView.setCurrentWebContents(wc);
        wc.updateWebContentsVisibility(
                org.chromium.content_public.browser.Visibility.VISIBLE);
    }

    /**
     * Get the Chromium WebContents object for this browser.
     * Package-private — internal use only, not part of the public API.
     */
    org.chromium.content_public.browser.WebContents getWebContents() {
        if (mNativePtr == 0) return null;
        return nativeGetWebContents(mNativePtr);
    }

    // Native methods.
    private static native long nativeCreate(int width, int height, float scale);
    private static native long nativeCreateWithSurface(
            org.chromium.ui.base.WindowAndroid windowAndroid,
            android.view.Surface surface, int width, int height, float scale);
    private static native void nativeSetRenderHandler(long nativePtr, CefriumBrowser browser);
    private static native void nativeLoadUrl(long nativePtr, String url);
    private static native void nativeInvalidate(long nativePtr);
    private static native void nativeSendTouchEvent(long nativePtr, int action, float x, float y, int pointerId);
    private static native void nativeSetAdBlockEnabled(long nativePtr, boolean enabled);
    private static native int nativeGetBlockedCount(long nativePtr);
    private static native void nativeUpdateSurface(
            long nativePtr, android.view.Surface surface, int width, int height);
    private static native void nativeDetachSurface(long nativePtr);
    private static native org.chromium.content_public.browser.WebContents nativeGetWebContents(long nativePtr);
    private static native void nativeGoBack(long nativePtr);
    private static native void nativeGoForward(long nativePtr);
    private static native void nativeReload(long nativePtr);
    private static native boolean nativeCanGoBack(long nativePtr);
    private static native void nativeSetDisplayCallback(long nativePtr, CefriumBrowser browser);
    private static native void nativeClose(long nativePtr);
    private static native void nativeSetDarkMode(long nativePtr, boolean dark);
}
