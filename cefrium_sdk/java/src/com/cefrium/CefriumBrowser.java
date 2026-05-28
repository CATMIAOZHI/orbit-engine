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
    private org.chromium.ui.base.IntentRequestTracker mIntentTracker;
    private org.chromium.components.embedder_support.view.ContentViewRenderView mContentViewRenderView;
    private org.chromium.components.autofill.AutofillProvider mAutofillProvider;
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

    private static final String TAG = "CefriumBrowser";

    private boolean checkNative(String method) {
        if (mNativePtr == 0) {
            android.util.Log.w(TAG, method + " called after close()");
            return false;
        }
        return true;
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
        if (checkNative("goBack")) nativeGoBack(mNativePtr);
    }

    /**
     * Navigate forward in history.
     */
    public void goForward() {
        if (checkNative("goForward")) nativeGoForward(mNativePtr);
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
     * Unified callback interface for browser events.
     * Implement only the methods you need — all have empty defaults.
     *
     * <pre>
     * browser.setCallback(new CefriumBrowser.Callback() {
     *     public void onUrlChanged(String url) { urlBar.setText(url); }
     *     public void onTitleChanged(String title) { setTitle(title); }
     *     public void onLoadingStateChanged(boolean loading, boolean back, boolean fwd) {
     *         progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
     *     }
     * });
     * </pre>
     */
    public static class Callback {
        public void onUrlChanged(String url) {}
        public void onTitleChanged(String title) {}
        public void onLoadingStateChanged(boolean isLoading, boolean canGoBack,
                                          boolean canGoForward) {}
        public void onFullscreenChanged(boolean fullscreen) {}
        public void onRequestIntercepted(String url, String method, boolean blocked) {}
        public void onFindResult(int activeMatch, int totalMatches) {}
        public void onDownloadUpdated(String name, String path,
                                      int percent, boolean complete, boolean canceled) {}
        public void onContextMenu(String menuItems, String url) {}
    }

    private Callback mCallback;

    /**
     * Set a unified callback for all browser events.
     * This is the recommended way to handle events. Individual
     * setOnXxxListener methods are also available for convenience.
     */
    public void setCallback(Callback callback) {
        mCallback = callback;
        if (mNativePtr != 0) {
            nativeSetDisplayCallback(mNativePtr, this);
        }
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
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    /**
     * Set a listener for fullscreen mode changes.
     */
    public void setOnFullscreenChangedListener(OnFullscreenChangedListener listener) {
        mFullscreenListener = listener;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    /**
     * Set a listener for network request interception.
     * Called for EVERY request — use for network inspection,
     * debugging, or custom request auditing.
     */
    public void setOnRequestInterceptedListener(OnRequestInterceptedListener listener) {
        mRequestListener = listener;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    // Called from native CefDisplayHandler::OnAddressChange.
    @SuppressWarnings("unused")
    private void onUrlChanged(String url) {
        if (mCallback != null) mCallback.onUrlChanged(url);
        if (mUrlListener != null) mUrlListener.onUrlChanged(url);
    }

    /**
     * Set a listener for loading state changes.
     */
    public void setOnLoadingStateChangedListener(OnLoadingStateChangedListener listener) {
        mLoadingListener = listener;
        if (mNativePtr != 0) {
            nativeSetDisplayCallback(mNativePtr, this);
        }
    }

    // Called from native CefDisplayHandler::OnTitleChange.
    @SuppressWarnings("unused")
    private void onTitleChanged(String title) {
        if (mCallback != null) mCallback.onTitleChanged(title);
        if (mTitleListener != null) mTitleListener.onTitleChanged(title);
    }

    // Called from native CefDisplayHandler::OnFullscreenModeChange.
    @SuppressWarnings("unused")
    private void onFullscreenChanged(boolean fullscreen) {
        if (mCallback != null) mCallback.onFullscreenChanged(fullscreen);
        if (mFullscreenListener != null) mFullscreenListener.onFullscreenChanged(fullscreen);
    }

    // Called from native CefResourceRequestHandler::OnBeforeResourceLoad.
    @SuppressWarnings("unused")
    private void onRequestIntercepted(String url, String method, boolean blocked) {
        if (mCallback != null) mCallback.onRequestIntercepted(url, method, blocked);
        if (mRequestListener != null) mRequestListener.onRequestIntercepted(url, method, blocked);
    }

    // Called from native CefLoadHandler::OnLoadingStateChange.
    @SuppressWarnings("unused")
    private void onLoadingStateChanged(boolean isLoading, boolean canGoBack,
                                       boolean canGoForward) {
        if (mCallback != null) mCallback.onLoadingStateChanged(isLoading, canGoBack, canGoForward);
        if (mLoadingListener != null) mLoadingListener.onLoadingStateChanged(isLoading, canGoBack, canGoForward);
    }

    /**
     * Reload the current page.
     */
    public void reload() {
        if (checkNative("reload")) nativeReload(mNativePtr);
    }

    /**
     * @return true if the browser can navigate back.
     */
    public boolean canGoBack() {
        return mNativePtr != 0 && nativeCanGoBack(mNativePtr);
    }

    /**
     * Notify the browser that the Activity is pausing.
     * Suspends the compositor to save battery and GPU resources.
     * Call from Activity.onPause() or Activity.onStop().
     */
    public void onPause() {
        if (mNativePtr != 0) {
            org.chromium.content_public.browser.WebContents wc =
                    nativeGetWebContents(mNativePtr);
            if (wc != null) {
                wc.updateWebContentsVisibility(
                        org.chromium.content_public.browser.Visibility.HIDDEN);
            }
        }
    }

    /**
     * Notify the browser that the Activity is resuming.
     * Resumes the compositor and rendering.
     * Call from Activity.onResume() or Activity.onStart().
     */
    public void onResume() {
        if (mNativePtr != 0) {
            org.chromium.content_public.browser.WebContents wc =
                    nativeGetWebContents(mNativePtr);
            if (wc != null) {
                wc.updateWebContentsVisibility(
                        org.chromium.content_public.browser.Visibility.VISIBLE);
            }
        }
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
        // Use ActivityWindowAndroid (not plain WindowAndroid) to support
        // intents — required for file picker (<input type=file>),
        // permission requests, and other Activity-based flows.
        browser.mIntentTracker =
                org.chromium.ui.base.IntentRequestTracker.createFromActivity(activity);
        browser.mWindowAndroid = new org.chromium.ui.base.ActivityWindowAndroid(
                activity, true, browser.mIntentTracker, null, false);

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
     * Forward Activity result to the browser for intent handling
     * (file picker, permissions, etc.).
     * Call this from your Activity's onActivityResult.
     *
     * @return true if the result was handled by the browser.
     */
    public boolean onActivityResult(int requestCode, int resultCode,
                                    android.content.Intent data) {
        if (mIntentTracker != null) {
            return mIntentTracker.onActivityResult(requestCode, resultCode, data);
        }
        return false;
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
        // Create a ViewAndroidDelegate that supports autofill.
        // The default createBasicDelegate returns false from
        // providesAutofillStructure(), preventing the Android framework
        // from requesting the virtual view structure needed for autofill.
        final CefriumBrowser browser = this;
        org.chromium.ui.base.ViewAndroidDelegate delegate =
                new org.chromium.ui.base.ViewAndroidDelegate(cv) {
                    @Override
                    public boolean providesAutofillStructure() {
                        return browser.mAutofillProvider != null;
                    }
                    @Override
                    public void onProvideAutofillVirtualStructure(
                            android.view.ViewStructure structure, int flags) {
                        if (browser.mAutofillProvider != null) {
                            browser.mAutofillProvider
                                    .onProvideAutoFillVirtualStructure(structure, flags);
                        }
                    }
                    @Override
                    public void autofill(
                            android.util.SparseArray<android.view.autofill.AutofillValue> values) {
                        if (browser.mAutofillProvider != null) {
                            browser.mAutofillProvider.autofill(values);
                        }
                    }
                };
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

        // Initialize Android Autofill for form auto-completion.
        // CefriumAutofillClient (ContentAutofillClient that creates
        // AndroidAutofillManager) is installed at browser creation time in
        // browser_platform_delegate_alloy.cc, replacing ChromeAutofillClient.
        // Here we just create the Java-side AutofillProvider that bridges
        // to Android's AutofillManager framework.
        try {
            mAutofillProvider = new org.chromium.components.autofill.AutofillProvider(
                    new java.lang.ref.WeakReference<>(mActivity),
                    cv, wc, "Cefrium");
            cv.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_YES);
        } catch (Throwable e) {
            // Autofill not available — non-critical.
        }
    }

    /**
     * Get the Chromium WebContents object for this browser.
     * Package-private — internal use only, not part of the public API.
     */
    org.chromium.content_public.browser.WebContents getWebContents() {
        if (mNativePtr == 0) return null;
        return nativeGetWebContents(mNativePtr);
    }

    /**
     * Listener for find-in-page results.
     */
    public interface OnFindResultListener {
        /**
         * @param activeMatch Current match ordinal (1-based).
         * @param totalMatches Total number of matches found.
         */
        void onFindResult(int activeMatch, int totalMatches);
    }

    private OnFindResultListener mFindListener;

    /**
     * Set the listener for find-in-page results.
     */
    public void setOnFindResultListener(OnFindResultListener listener) {
        mFindListener = listener;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    /**
     * Search for text in the current page.
     * Uses CEF API: CefBrowserHost::Find.
     *
     * @param text Text to search for. Empty string stops the search.
     * @param forward true to search forward, false to search backward.
     * @param matchCase true for case-sensitive search.
     * @param findNext true if continuing a previous search (next/prev).
     */
    public void find(String text, boolean forward, boolean matchCase, boolean findNext) {
        if (checkNative("find")) nativeFind(mNativePtr, text, forward, matchCase, findNext);
    }

    /**
     * Stop the current find-in-page search.
     * Uses CEF API: CefBrowserHost::StopFinding.
     *
     * @param clearSelection true to clear the selection highlight.
     */
    public void stopFinding(boolean clearSelection) {
        if (checkNative("stopFinding")) nativeStopFinding(mNativePtr, clearSelection);
    }

    // Called from native CefFindHandler::OnFindResult.
    @SuppressWarnings("unused")
    private void onFindResult(int activeMatch, int totalMatches) {
        if (mFindListener != null) {
            mFindListener.onFindResult(activeMatch, totalMatches);
        }
        if (mCallback != null) mCallback.onFindResult(activeMatch, totalMatches);
    }

    /**
     * Listener for download progress updates.
     * Uses CEF API: CefDownloadHandler.
     */
    public interface OnDownloadListener {
        /**
         * @param name File name being downloaded.
         * @param path Full path on device.
         * @param percent Download progress (0-100).
         * @param complete true when download is finished.
         * @param canceled true if download was canceled.
         */
        void onDownloadUpdated(String name, String path,
                               int percent, boolean complete, boolean canceled);
    }

    private OnDownloadListener mDownloadListener;

    /**
     * Set the listener for download progress updates.
     */
    public void setOnDownloadListener(OnDownloadListener listener) {
        mDownloadListener = listener;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    // Called from native CefDownloadHandler::OnDownloadUpdated.
    @SuppressWarnings("unused")
    private void onDownloadUpdated(String name, String path,
                                   int percent, boolean complete, boolean canceled) {
        if (mDownloadListener != null) {
            mDownloadListener.onDownloadUpdated(name, path, percent, complete, canceled);
        }
        if (mCallback != null) mCallback.onDownloadUpdated(name, path, percent, complete, canceled);
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
    private static native void nativeFind(long nativePtr, String text,
            boolean forward, boolean matchCase, boolean findNext);
    private static native void nativeStopFinding(long nativePtr, boolean clearSelection);
    private static native void nativeContextMenuCommand(long nativePtr, int commandId);
    private static native void nativeInitAutofill(long nativePtr);

    /**
     * Listener for context menu events (long press on links/images).
     */
    public interface OnContextMenuListener {
        /**
         * @param menuItems Menu items as "id|label" lines separated by newlines.
         * @param url The link or image URL.
         */
        void onContextMenu(String menuItems, String url);
    }

    private OnContextMenuListener mContextMenuListener;

    /**
     * Set the listener for context menu events.
     */
    public void setOnContextMenuListener(OnContextMenuListener listener) {
        mContextMenuListener = listener;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    /**
     * Send the user's context menu selection back to CEF.
     * Use -1 to cancel.
     *
     * @param commandId The selected command ID, or -1 to cancel.
     */
    public void contextMenuCommand(int commandId) {
        if (mNativePtr != 0) nativeContextMenuCommand(mNativePtr, commandId);
    }

    // Called from native to copy text to Android clipboard.
    @SuppressWarnings("unused")
    private void copyToClipboard(String text) {
        if (mActivity != null) {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager)
                    mActivity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("Cefrium", text));
            }
        }
    }

    // Called from native CefContextMenuHandler::RunContextMenu.
    @SuppressWarnings("unused")
    private void onContextMenu(String menuItems, String url) {
        if (mCallback != null) mCallback.onContextMenu(menuItems, url);
        if (mContextMenuListener != null) mContextMenuListener.onContextMenu(menuItems, url);
    }
}
