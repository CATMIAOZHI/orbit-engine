// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

/**
 * A browser instance backed by Chromium via CEF (Chromium Embedded Framework).
 *
 * <p>Two rendering modes are available:</p>
 * <ul>
 *   <li><b>Off-screen (OSR)</b> — frames delivered as BGRA pixel buffers
 *       via {@link CefriumRenderHandler#onPaint}. Use for headless rendering,
 *       screenshots, or custom compositing. Create with {@link #CefriumBrowser(int, int, float)}.</li>
 *   <li><b>Surface</b> — hardware-accelerated rendering to an Android Surface
 *       via ContentViewRenderView. Supports video, audio, touch, DRM.
 *       Create with {@link #createWithSurface(android.app.Activity)}.</li>
 * </ul>
 *
 * <p><b>Lifecycle:</b> Call {@link #onPause()} / {@link #onResume()} from your
 * Activity lifecycle. Call {@link #close()} when done — this releases native
 * resources. Do not call any method after {@link #close()}.</p>
 *
 * <p><b>Threading:</b> All methods must be called on the UI thread.</p>
 *
 * <pre>
 * // Surface mode (recommended for most apps)
 * CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
 * layout.addView(browser.getSurfaceContainer());
 * browser.loadUrl("https://example.com");
 *
 * // OSR mode (headless / screenshots)
 * CefriumBrowser browser = new CefriumBrowser(1080, 1920, 2.75f);
 * browser.setRenderHandler((w, h, pixels) -&gt; { });
 * browser.loadUrl("https://example.com");
 * </pre>
 *
 * @see Cefrium#initialize(android.content.Context)
 * @see CefriumRenderHandler
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

    private boolean mClosed;

    private boolean checkNative(String method) {
        if (mNativePtr == 0) {
            if (mClosed) {
                android.util.Log.e(TAG, method + "(): browser already closed. "
                        + "Do not call methods after close().");
            } else {
                android.util.Log.e(TAG, method + "(): native browser not yet created. "
                        + "Wait for createWithSurface() callback or constructor to complete.");
            }
            return false;
        }
        return true;
    }

    /**
     * Set the render handler to receive painted frames (OSR mode only).
     * Each frame is a BGRA pixel buffer with dimensions matching the
     * viewport size set in the constructor.
     *
     * @param handler The handler to receive OnPaint callbacks, or null to stop.
     * @see CefriumRenderHandler#onPaint(int, int, java.nio.ByteBuffer)
     */
    public void setRenderHandler(CefriumRenderHandler handler) {
        mRenderHandler = handler;
        if (checkNative("setRenderHandler")) {
            nativeSetRenderHandler(mNativePtr, this);
        }
    }

    /**
     * Navigate to a URL. Supports http://, https://, file://, and data: URLs.
     * If the scheme is omitted, https:// is NOT added automatically — pass
     * the full URL.
     *
     * @param url The URL to load.
     */
    public void loadUrl(String url) {
        if (!checkNative("loadUrl")) return;
        nativeLoadUrl(mNativePtr, url);
        // In Surface mode, connect WebContents after first load.
        if (mSurfaceContainer != null && !mWebContentsConnected) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(this::connectWebContentsInternal, 2000);
        }
    }

    /**
     * Navigate back in history. No-op if {@link #canGoBack()} is false.
     */
    public void goBack() {
        if (checkNative("goBack")) nativeGoBack(mNativePtr);
    }

    /**
     * Navigate forward in history. No-op if there is no forward history.
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
     * Reload the current page. Uses CefBrowserHost::Reload (ignores cache).
     */
    public void reload() {
        if (checkNative("reload")) nativeReload(mNativePtr);
    }

    /**
     * Execute JavaScript in the browser's main frame (fire-and-forget).
     * Uses CefFrame::ExecuteJavaScript. The script's return value is not
     * delivered back to the caller; a result-returning JS bridge is a
     * separate feature built on CefMessageRouter.
     *
     * @param script JavaScript source to execute. Ignored if null.
     */
    public void evaluateJavaScript(String script) {
        if (script != null && checkNative("evaluateJavaScript")) {
            nativeEvaluateJavaScript(mNativePtr, script);
        }
    }

    /**
     * Handles JavaScript {@code window.cefriumQuery({request, onSuccess,
     * onFailure})} calls from page content (FEATURE-023 JS bridge).
     *
     * <p>Step 4 supports synchronous responses: call {@code callback.success()}
     * or {@code callback.failure()} from within {@link #onQuery}. Returning
     * false (or never responding) fails the query with code -1.
     */
    public interface QueryHandler {
        /**
         * @param queryId Opaque id (also valid for async responses).
         * @param request The JS-provided request payload.
         * @param origin URL of the frame that called window.cefriumQuery. Gate
         *        on this to reject untrusted pages/iframes (security).
         * @param callback Resolve the query via success/failure.
         * @return true if this handler took the query.
         */
        boolean onQuery(long queryId, String request, String origin,
                        QueryCallback callback);
    }

    /** Resolves a {@link QueryHandler} query back to JavaScript. */
    public interface QueryCallback {
        void success(String response);
        void failure(int errorCode, String errorMessage);
    }

    private QueryHandler mQueryHandler;

    /** Registers the handler for {@code window.cefriumQuery} calls. */
    public void setQueryHandler(QueryHandler handler) {
        mQueryHandler = handler;
    }

    // Called from native (browser UI thread) when JS calls window.cefriumQuery.
    private void onJsQuery(final long queryId, String request, String origin) {
        final QueryHandler handler = mQueryHandler;
        if (handler == null) {
            nativeQueryResult(mNativePtr, queryId, false, null, -1,
                    "No query handler registered");
            return;
        }
        QueryCallback callback = new QueryCallback() {
            @Override
            public void success(String response) {
                if (mNativePtr != 0) {
                    nativeQueryResult(mNativePtr, queryId, true, response, 0, null);
                }
            }

            @Override
            public void failure(int errorCode, String errorMessage) {
                if (mNativePtr != 0) {
                    nativeQueryResult(mNativePtr, queryId, false, null,
                            errorCode, errorMessage);
                }
            }
        };
        if (!handler.onQuery(queryId, request, origin, callback)) {
            nativeQueryResult(mNativePtr, queryId, false, null, -1,
                    "Query not handled");
        }
    }

    // ───────────────────────── Cookies (FEATURE-024) ─────────────────────────

    /** An HTTP cookie read from the browser's cookie store. */
    public static final class Cookie {
        public final String name, value, domain, path;
        public final boolean secure, httpOnly;
        public Cookie(String name, String value, String domain, String path,
                      boolean secure, boolean httpOnly) {
            this.name = name; this.value = value; this.domain = domain;
            this.path = path; this.secure = secure; this.httpOnly = httpOnly;
        }
    }

    /** Receives the result of {@link #getCookies}. Invoked on the UI thread. */
    public interface CookieCallback {
        void onCookies(java.util.List<Cookie> cookies);
    }

    private final java.util.concurrent.ConcurrentHashMap<Long, CookieCallback>
            mCookieCallbacks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong mCookieReqId =
            new java.util.concurrent.atomic.AtomicLong(1);

    /** Sets a cookie for {@code url} (domain/path inferred from the URL). */
    public void setCookie(String url, String name, String value) {
        if (mNativePtr != 0) nativeSetCookie(mNativePtr, url, name, value);
    }

    /** Reads cookies visible to {@code url}; the callback fires on the UI thread. */
    public void getCookies(String url, CookieCallback callback) {
        if (mNativePtr == 0 || callback == null) return;
        long reqId = mCookieReqId.getAndIncrement();
        mCookieCallbacks.put(reqId, callback);
        nativeGetCookies(mNativePtr, this, url, reqId);
    }

    /** Deletes cookies for {@code url}; {@code name} null/empty = all for the URL. */
    public void deleteCookies(String url, String name) {
        if (mNativePtr != 0) nativeDeleteCookies(mNativePtr, url, name == null ? "" : name);
    }

    /** Persists the cookie store to disk. */
    public void flushCookies() {
        if (mNativePtr != 0) nativeFlushCookies(mNativePtr);
    }

    /** Clears the browser's HTTP cache (FEATURE-030). */
    public void clearCache() {
        if (mNativePtr != 0) nativeClearCache(mNativePtr);
    }

    // ─────────────── Print to PDF (FEATURE-031) ───────────────

    /** Result of {@link #printToPdf}. Invoked on the UI thread. */
    public interface PdfCallback {
        void onPdfPrinted(String filePath, boolean success);
    }

    private final java.util.concurrent.ConcurrentHashMap<Long, PdfCallback>
            mPdfCallbacks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong mPdfReqId =
            new java.util.concurrent.atomic.AtomicLong(1);

    /** Renders the current page to a PDF at {@code filePath} (async). */
    public void printToPdf(String filePath, PdfCallback callback) {
        if (mNativePtr == 0 || callback == null) return;
        long id = mPdfReqId.getAndIncrement();
        mPdfCallbacks.put(id, callback);
        nativePrintToPdf(mNativePtr, this, filePath, id);
    }

    // Called from native (UI thread) when PrintToPDF finishes.
    private void onPdfPrinted(long requestId, String filePath, boolean ok) {
        PdfCallback cb = mPdfCallbacks.remove(requestId);
        if (cb != null) cb.onPdfPrinted(filePath, ok);
    }

    // ──────────────────── Dynamic WebSettings (FEATURE-025) ────────────────────

    /** Overrides the User-Agent at runtime (Emulation.setUserAgentOverride). */
    public void setUserAgentOverride(String userAgent) {
        if (mNativePtr != 0) nativeSetUserAgentOverride(mNativePtr, userAgent);
    }

    /** Enables/disables JavaScript at runtime (Emulation.setScriptExecutionDisabled). */
    public void setJavaScriptEnabled(boolean enabled) {
        if (mNativePtr != 0) nativeSetJavaScriptEnabled(mNativePtr, enabled);
    }

    /** Sets the zoom level (0 = 100%; factor = 1.2^level). */
    public void setZoomLevel(double zoomLevel) {
        if (mNativePtr != 0) nativeSetZoomLevel(mNativePtr, zoomLevel);
    }

    // ─────────────── Navigation extras (FEATURE-026) ───────────────

    /** Loads an HTML string directly (served to the engine as a data: URL). */
    public void loadHtml(String html) {
        if (mNativePtr == 0 || html == null) return;
        String dataUrl = "data:text/html;charset=utf-8;base64," + android.util.Base64.encodeToString(
                html.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        nativeLoadUrl(mNativePtr, dataUrl);
    }

    /** Loads {@code url} with an HTTP POST body. */
    public void postUrl(String url, byte[] postData) {
        if (mNativePtr != 0) nativePostUrl(mNativePtr, url, postData);
    }

    /** Stops the current load. */
    public void stopLoading() {
        if (mNativePtr != 0) nativeStopLoading(mNativePtr);
    }

    // ─────────────── Request interceptor (FEATURE-027) ───────────────

    /**
     * Decides whether to block outgoing requests. Invoked on a background
     * (network) thread for every request — must be fast and thread-safe, and
     * must not touch the UI. Complements the built-in EasyList ad blocker.
     */
    public interface RequestInterceptor {
        /** Return true to block (cancel) this request. */
        boolean shouldBlockRequest(String url, String method, boolean isMainFrame);
    }

    private volatile RequestInterceptor mRequestInterceptor;

    /** Sets a programmatic request interceptor (null clears it). */
    public void setRequestInterceptor(RequestInterceptor interceptor) {
        mRequestInterceptor = interceptor;
        // Ensure native holds this Java ref so the resource handler can call
        // onShouldBlockRequest — independent of whether a display callback was set.
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    // Called from native (IO thread) for every request not already ad-blocked.
    private boolean onShouldBlockRequest(String url, String method, boolean isMainFrame) {
        RequestInterceptor i = mRequestInterceptor;
        return i != null && i.shouldBlockRequest(url, method, isMainFrame);
    }

    // ─────────────── JS dialogs (FEATURE-028) ───────────────

    /** alert() */   public static final int JS_DIALOG_ALERT = 0;
    /** confirm() */ public static final int JS_DIALOG_CONFIRM = 1;
    /** prompt() */  public static final int JS_DIALOG_PROMPT = 2;

    /** Resolves a JS dialog. */
    public interface JsDialogCallback {
        void continueDialog(boolean success, String userInput);
    }

    /**
     * Handles page JS dialogs (alert/confirm/prompt). Return true if handled
     * (you MUST call the callback); return false to use the default Android
     * AlertDialog. Without a handler set, the default AlertDialog is shown.
     */
    public interface JsDialogHandler {
        boolean onJsDialog(int type, String message, String defaultPromptText,
                           JsDialogCallback callback);
    }

    private JsDialogHandler mJsDialogHandler;

    /** Sets a custom JS-dialog handler (null = default Android AlertDialog). */
    public void setJsDialogHandler(JsDialogHandler handler) {
        mJsDialogHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    // Called from native (UI thread) when the page opens alert/confirm/prompt.
    private void onJsDialog(int type, long token, String message, String defaultPrompt) {
        JsDialogCallback cb = (success, userInput) -> {
            if (mNativePtr != 0) {
                nativeJsDialogResult(mNativePtr, token, success,
                        userInput == null ? "" : userInput);
            }
        };
        JsDialogHandler h = mJsDialogHandler;
        if (h != null && h.onJsDialog(type, message, defaultPrompt, cb)) return;
        if (mActivity == null) { cb.continueDialog(false, ""); return; }
        mActivity.runOnUiThread(() -> showDefaultJsDialog(type, message, defaultPrompt, cb));
    }

    private void showDefaultJsDialog(int type, String message, String defaultPrompt,
                                     JsDialogCallback cb) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(mActivity);
        b.setMessage(message);
        b.setCancelable(false);
        final android.widget.EditText input =
                (type == JS_DIALOG_PROMPT) ? new android.widget.EditText(mActivity) : null;
        if (input != null) {
            input.setText(defaultPrompt == null ? "" : defaultPrompt);
            b.setView(input);
        }
        b.setPositiveButton(android.R.string.ok, (d, w) ->
                cb.continueDialog(true, input != null ? input.getText().toString() : ""));
        if (type == JS_DIALOG_CONFIRM || type == JS_DIALOG_PROMPT) {
            b.setNegativeButton(android.R.string.cancel, (d, w) -> cb.continueDialog(false, ""));
        }
        b.show();
    }

    // ─────────────── Popups / window.open (FEATURE-029) ───────────────

    /**
     * Handles popups (window.open / target=_blank). This SDK never spawns a
     * separate window; by default the target URL is loaded in this browser.
     */
    public interface PopupHandler {
        /** Return true if you handled it (e.g. opened a new tab); false to load
            the target URL in this browser. */
        boolean onOpenPopup(String targetUrl, boolean userGesture);
    }

    private PopupHandler mPopupHandler;

    /** Sets a popup handler (null = load popups in this browser). */
    public void setPopupHandler(PopupHandler handler) {
        mPopupHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    // Called from native (UI thread) when the page opens window.open / target=_blank.
    private boolean onOpenPopup(String targetUrl, boolean userGesture) {
        PopupHandler h = mPopupHandler;
        return h != null && h.onOpenPopup(targetUrl, userGesture);
    }

    // Called from native (UI thread) with the visited cookies for a getCookies request.
    private void onCookiesVisited(long requestId, String[] names, String[] values,
            String[] domains, String[] paths, boolean[] secure, boolean[] httpOnly) {
        CookieCallback cb = mCookieCallbacks.remove(requestId);
        if (cb == null) return;
        java.util.ArrayList<Cookie> list = new java.util.ArrayList<>(names.length);
        for (int i = 0; i < names.length; i++) {
            list.add(new Cookie(names[i], values[i], domains[i], paths[i],
                    secure[i], httpOnly[i]));
        }
        cb.onCookies(list);
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
     * Close this browser and release all native resources.
     * After calling this method, no other methods may be called on this instance.
     * For Surface mode, also removes the rendering views.
     */
    public void close() {
        mClosed = true;
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
     * Get the number of blocked ad/tracking requests since the browser was created.
     * Cefrium includes a built-in network-level ad blocker using EasyList rules.
     *
     * @return Number of blocked requests, or 0 if the browser is closed.
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

            // Set WebAuthn mode per-WebContents to CHROME_3PP_ENABLED.
            // This enables CredMan-only path (no Play Services Fido2 privileged
            // API). Security checks pass with this mode (unlike BROWSER mode).
            org.chromium.components.webauthn.WebauthnModeProvider.getInstance()
                    .setWebauthnModeForWebContents(wc,
                            org.chromium.components.webauthn.WebauthnMode.CHROME_3PP_ENABLED);
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
    private static native void nativeEvaluateJavaScript(long nativePtr, String script);
    private static native void nativeQueryResult(long nativePtr, long queryId,
            boolean success, String response, int errorCode, String errorMessage);
    private static native void nativeSetCookie(long nativePtr, String url, String name, String value);
    private static native void nativeGetCookies(long nativePtr, CefriumBrowser browser, String url, long requestId);
    private static native void nativeDeleteCookies(long nativePtr, String url, String name);
    private static native void nativeFlushCookies(long nativePtr);
    private static native void nativeClearCache(long nativePtr);
    private static native void nativePrintToPdf(long nativePtr, CefriumBrowser browser, String filePath, long requestId);
    private static native void nativeSetUserAgentOverride(long nativePtr, String userAgent);
    private static native void nativeSetJavaScriptEnabled(long nativePtr, boolean enabled);
    private static native void nativeSetZoomLevel(long nativePtr, double zoomLevel);
    private static native void nativePostUrl(long nativePtr, String url, byte[] postData);
    private static native void nativeStopLoading(long nativePtr);
    private static native void nativeJsDialogResult(long nativePtr, long token, boolean success, String userInput);
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
