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
    private String mCurrentUrl;  // Tracked for saveState/getUrl
    private OnUrlChangedListener mUrlListener;
    private OnTitleChangedListener mTitleListener;
    private OnLoadingStateChangedListener mLoadingListener;
    private OnRenderProcessTerminatedListener mRenderProcessTerminatedListener;
    private OnRequestInterceptedListener mRequestListener;
    private OnFullscreenChangedListener mFullscreenListener;

    // Surface rendering internals — not exposed in public API.
    private android.app.Activity mActivity;
    private org.chromium.ui.base.WindowAndroid mWindowAndroid;
    private org.chromium.ui.base.IntentRequestTracker mIntentTracker;
    private org.chromium.components.embedder_support.view.ContentViewRenderView mContentViewRenderView;
    private org.chromium.components.autofill.AutofillProvider mAutofillProvider;
    private CefriumSurfaceContainer mSurfaceContainer;
    private CefriumMediaSession mMediaSession;
    private boolean mMediaSessionEnabled;
    private android.app.PendingIntent mMediaSessionActivity;
    private boolean mWebContentsConnected;
    private boolean mPullToRefresh;
    // FEATURE-057: true if this is a private (incognito) browser.
    private boolean mIncognito;
    // A restored tab defers its load until the surface + overscroll handler are
    // connected (connectWebContentsInternal), then triggers it once.
    private boolean mPendingRestoreLoad;

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
        // Lazy init: the SDK's CefriumInitProvider already ran the per-process
        // setup at app launch, so initialize() can use the captured app context.
        if (!Cefrium.isInitialized()) {
            Cefrium.initialize();
        }
        mNativePtr = nativeCreate(width, height, scale);
    }

    private static final String TAG = "CefriumBrowser";

    // volatile: read on the main looper in connectWebContentsInternal (delayed
    // callback) while close() may set it from another thread; the write must be
    // visible to short-circuit the stale-pointer use-after-free.
    private volatile boolean mClosed;

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
     * Enable pull-to-refresh: an overscroll-down gesture at the top of the page
     * reloads it (Chrome-style). Off by default. Call this BEFORE the page loads
     * (the handler is bound once, when the WebContents connects); it must not be
     * toggled on a live WebContents.
     */
    public void setPullToRefreshEnabled(boolean enabled) {
        mPullToRefresh = enabled;
    }

    private void applyPullToRefresh(org.chromium.content_public.browser.WebContents wc) {
        wc.setOverscrollRefreshHandler(new org.chromium.ui.OverscrollRefreshHandler() {
            @Override
            public boolean start(int type, int initiatingEdge) {
                return type == org.chromium.ui.OverscrollAction.PULL_TO_REFRESH;
            }
            @Override public void pull(float xDelta, float yDelta) {}
            @Override public void release(boolean allowRefresh) {
                if (allowRefresh) reload();
            }
            @Override public void reset() {}
            @Override public void setEnabled(boolean enabled) {}
        });
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

    /** Termination status: process exited abnormally. */
    public static final int TERMINATION_ABNORMAL = 0;
    /** Termination status: process was killed (e.g. by the OS low-memory killer). */
    public static final int TERMINATION_KILLED = 1;
    /** Termination status: process crashed. */
    public static final int TERMINATION_CRASHED = 2;
    /** Termination status: process ran out of memory. */
    public static final int TERMINATION_OOM = 3;

    /**
     * Listener for renderer-process termination. Called by CEF's
     * CefRequestHandler::OnRenderProcessTerminated. A renderer can die without
     * killing the app process and without a logcat FATAL -- use this to show a
     * reload UI (the page is now blank/dead) and to count renderer crashes.
     */
    public interface OnRenderProcessTerminatedListener {
        /**
         * @param status one of TERMINATION_ABNORMAL, TERMINATION_KILLED,
         *               TERMINATION_CRASHED, TERMINATION_OOM
         * @param errorCode the platform error code (0 if none)
         */
        void onRenderProcessTerminated(int status, int errorCode);
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
        // Register the no-UI PaymentRequest factory here (idempotent): this
        // navigation callback runs after the browser process is fully started
        // (so after Chrome's ChromeInterfaceRegistrar has added its payment
        // factory) and before the user can invoke payment (the frame's Java
        // InterfaceProvider map is built lazily on the first PaymentRequest).
        // Registering last makes our factory win the last-writer-wins registry.
        try {
            com.cefrium.payments.CefPaymentInterfaceRegistrar.register();
        } catch (Throwable t) {
            android.util.Log.w("Cefrium", "PaymentRequest registrar skipped: " + t);
        }
        mCurrentUrl = url;
        if (mCallback != null) mCallback.onUrlChanged(url);
        if (mUrlListener != null) mUrlListener.onUrlChanged(url);
    }

    /** The current top-level URL, or {@code null} before the first navigation. */
    public String getUrl() {
        return mCurrentUrl;
    }

    // ── State preservation (Activity recreation: rotation, process death) ──

    /**
     * Captures restorable browser state (the current URL) into a Bundle. Call
     * from {@code Activity.onSaveInstanceState} and pass the result to
     * {@link #restoreState} after recreating the browser.
     */
    public android.os.Bundle saveState() {
        android.os.Bundle state = new android.os.Bundle();
        if (mCurrentUrl != null) state.putString(STATE_URL, mCurrentUrl);
        return state;
    }

    /**
     * Restores state previously captured by {@link #saveState} — re-navigates to
     * the saved URL. No-op if {@code state} is null or holds no saved URL.
     */
    public void restoreState(android.os.Bundle state) {
        if (state == null) return;
        String url = state.getString(STATE_URL);
        if (url != null && !url.isEmpty()) loadUrl(url);
    }

    private static final String STATE_URL = "com.cefrium.browser.url";

    /**
     * Serializes this tab's full back/forward navigation stack (each entry's URL
     * and blink PageState, plus the current index) for session restore. Returns
     * {@code null} if there is nothing to save. Chrome-style tab restore: unlike
     * {@link #saveState} (current URL only), the restored tab can navigate back.
     * Restore with {@link #restoreNavigationState} on a FRESH browser, before its
     * first navigation.
     */
    public byte[] serializeNavigationState() {
        return checkNative("serializeNavigationState")
                ? nativeSerializeNavigationState(mNativePtr) : null;
    }

    /**
     * Restores a navigation stack captured by {@link #serializeNavigationState}.
     * Must be called on a fresh browser (empty history) before its first
     * navigation; no-op for null/empty input.
     */
    public void restoreNavigationState(byte[] state) {
        if (state != null && state.length > 0
                && checkNative("restoreNavigationState")) {
            nativeRestoreNavigationState(mNativePtr, state);
            // Restored tabs don't call loadUrl, so schedule the surface connect
            // here (same as loadUrl). connectWebContentsInternal triggers the
            // deferred restore load once the view + overscroll handler are set up
            // -- loading earlier crashes the overscroll controller on the RFH swap.
            mPendingRestoreLoad = true;
            if (mSurfaceContainer != null && !mWebContentsConnected) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(this::connectWebContentsInternal, 2000);
            }
        }
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

    /**
     * Set a listener notified when a renderer process for this browser dies.
     * The native CefRequestHandler is always installed, so no extra wiring is
     * needed -- the callback fires whenever the renderer terminates.
     */
    public void setOnRenderProcessTerminatedListener(
            OnRenderProcessTerminatedListener listener) {
        mRenderProcessTerminatedListener = listener;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
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

    // Called from native CefRequestHandler::OnRenderProcessTerminated.
    @SuppressWarnings("unused")
    private void onRenderProcessTerminated(int status, int errorCode) {
        if (mRenderProcessTerminatedListener != null) {
            mRenderProcessTerminatedListener.onRenderProcessTerminated(status, errorCode);
        }
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
     * onFailure})} calls from page content (the JS &#8596; native bridge).
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

    // ───────────────────────── Cookies ─────────────────────────

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

    // SameSite values for {@link #setCookie}; match Chromium's cookie SameSite.
    public static final int SAME_SITE_UNSPECIFIED = 0;
    public static final int SAME_SITE_NONE = 1;   // cross-site; REQUIRES secure=true
    public static final int SAME_SITE_LAX = 2;
    public static final int SAME_SITE_STRICT = 3;

    /** Sets a session cookie for {@code url} (domain/path inferred from the URL). */
    public void setCookie(String url, String name, String value) {
        setCookie(url, name, value, false, false, SAME_SITE_UNSPECIFIED);
    }

    /**
     * Sets a session cookie for {@code url} with explicit attributes. Use this for
     * cookies a third-party/cross-site context must send (e.g. a consent cookie an
     * embedded iframe needs): pass {@code secure=true} and
     * {@code sameSite=SAME_SITE_NONE} -- Chromium rejects {@code SameSite=None}
     * without {@code Secure}. domain/path are inferred from the URL.
     */
    public void setCookie(String url, String name, String value,
                          boolean secure, boolean httpOnly, int sameSite) {
        if (mNativePtr != 0) {
            nativeSetCookie(mNativePtr, url, name, value, secure, httpOnly, sameSite);
        }
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

    /** Clears the browser's HTTP cache. */
    public void clearCache() {
        if (mNativePtr != 0) nativeClearCache(mNativePtr);
    }

    // ─────────────── Print to PDF ───────────────

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

    // ──────────────────── Dynamic WebSettings ────────────────────

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

    // ─────────────── Navigation extras ───────────────

    /** Loads an HTML string directly (served to the engine as a data: URL). */
    public void loadHtml(String html) {
        if (mNativePtr == 0 || html == null) return;
        String dataUrl = "data:text/html;charset=utf-8;base64," + android.util.Base64.encodeToString(
                html.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        nativeLoadUrl(mNativePtr, dataUrl);
    }

    /**
     * Loads a data string with an explicit base URL, mirroring Android WebView's
     * {@link android.webkit.WebView#loadDataWithBaseURL}. Unlike WebView, Cefrium
     * delivers the content as a GENUINE network response at {@code baseUrl} (not a
     * {@code data:} document), so the loaded document has a real, network-served
     * origin == {@code baseUrl}. This is what lets inline embedded players that
     * require a network-served parent -- notably the YouTube IFrame player -- play;
     * a {@code data:} document is rejected by such players (Error 152), which is why
     * the same content fails under WebView's {@code loadDataWithBaseURL}.
     *
     * <p><b>Origin guidance:</b> pass <i>your own app origin</i> as {@code baseUrl}
     * (as any website embedding YouTube does), e.g. {@code "https://app.example.com"}
     * -- NOT {@code "https://www.youtube.com"}. YouTube rejects same-origin
     * self-embedding with Error 152.
     *
     * @param baseUrl    network origin for the document, e.g. {@code "https://app.example.com"}
     * @param data       the HTML (or other) content to load
     * @param mimeType   content type, e.g. {@code "text/html"} (defaults to text/html if null/empty)
     * @param encoding   charset, e.g. {@code "utf-8"} (defaults to utf-8 if null/empty)
     * @param historyUrl URL used for the document's virtual/history URL (defaults to baseUrl if null/empty)
     */
    public void loadDataWithBaseUrl(String baseUrl, String data, String mimeType,
                                    String encoding, String historyUrl) {
        if (mNativePtr == 0 || baseUrl == null || data == null) return;
        nativeLoadDataWithBaseUrl(mNativePtr, baseUrl, data, mimeType, encoding, historyUrl);
    }

    /**
     * Convenience: load an HTML string with the given base URL (text/html, utf-8,
     * history = baseUrl). The document is served from a real network origin ==
     * {@code baseUrl}, so an inline YouTube IFrame embedded in {@code html} plays.
     * Pass YOUR OWN app origin as {@code baseUrl}, e.g.
     * {@code loadHtmlWithBaseUrl("<iframe src='https://www.youtube.com/embed/ID'></iframe>", "https://app.example.com")}
     * -- not {@code "https://www.youtube.com"} (YouTube rejects same-origin
     * self-embedding). See {@link #loadDataWithBaseUrl}.
     */
    public void loadHtmlWithBaseUrl(String html, String baseUrl) {
        loadDataWithBaseUrl(baseUrl, html, "text/html", "utf-8", baseUrl);
    }

    /** Loads {@code url} with an HTTP POST body. */
    public void postUrl(String url, byte[] postData) {
        if (mNativePtr != 0) nativePostUrl(mNativePtr, url, postData);
    }

    /** Stops the current load. */
    public void stopLoading() {
        if (mNativePtr != 0) nativeStopLoading(mNativePtr);
    }

    // ─────────────── Request interceptor ───────────────

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

    // ─────────────── JS dialogs ───────────────

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

    // ── Permissions ──

    /** Permission types (bitmask, from cef_permission_request_types_t). */
    public static final int PERMISSION_GEOLOCATION = 1 << 8;
    public static final int PERMISSION_CAMERA = 1 << 2;
    public static final int PERMISSION_MICROPHONE = 1 << 12;
    public static final int PERMISSION_NOTIFICATIONS = 1 << 15;
    /** Clipboard read (navigator.clipboard.readText). Writing needs no prompt. */
    public static final int PERMISSION_CLIPBOARD = 1 << 4;
    /**
     * Web NFC (NDEFReader scan/write). Also declare {@code android.permission.NFC}
     * in your manifest and have NFC enabled on the device. Grant this in your
     * {@link PermissionHandler} (or pre-authorize via {@link #setContentSetting}
     * with {@link #SETTING_ALLOW}).
     */
    public static final int PERMISSION_NFC = 1 << 29;

    /** Receives the app's grant/deny decision for a permission request. */
    public interface PermissionCallback {
        void respond(boolean grant);
    }

    /**
     * Decides web permission requests (geolocation, camera, mic, ...). Without a
     * handler set, all requests are denied — the default Android permission
     * dialog is not available in this engine. To actually deliver geolocation,
     * the app must also hold the ACCESS_FINE_LOCATION runtime permission.
     */
    public interface PermissionHandler {
        void onPermissionRequest(String origin, int permissions, PermissionCallback callback);
    }

    private PermissionHandler mPermissionHandler;

    /** Sets the permission handler (null = deny all web permission requests). */
    public void setPermissionHandler(PermissionHandler handler) {
        mPermissionHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    // Called from native (UI thread) when a page requests a permission.
    private void onPermissionRequest(long promptId, String origin, int permissions) {
        PermissionHandler h = mPermissionHandler;
        if (h == null) {
            if (mNativePtr != 0) nativeRespondPermission(mNativePtr, promptId, false);
            return;
        }
        final boolean[] answered = {false};
        PermissionCallback cb = (grant) -> {
            if (answered[0]) return;
            answered[0] = true;
            if (mNativePtr != 0) nativeRespondPermission(mNativePtr, promptId, grant);
        };
        h.onPermissionRequest(origin, permissions, cb);
    }

    // ── Web Share (navigator.share) ──

    // Called from native (UI thread) when a page calls navigator.share(). Opens
    // the Android share sheet from the hosting Activity. Returns true if a share
    // chooser was started (the page's share() promise then resolves).
    private boolean onWebShare(String title, String text, String url) {
        if (mActivity == null) {
            return false;
        }
        StringBuilder body = new StringBuilder();
        if (text != null && !text.isEmpty()) {
            body.append(text);
        }
        if (url != null && !url.isEmpty()) {
            if (body.length() > 0) body.append('\n');
            body.append(url);
        }
        android.content.Intent send =
                new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        if (title != null && !title.isEmpty()) {
            send.putExtra(android.content.Intent.EXTRA_SUBJECT, title);
        }
        send.putExtra(android.content.Intent.EXTRA_TEXT, body.toString());
        try {
            mActivity.startActivity(
                    android.content.Intent.createChooser(send,
                            (title != null && !title.isEmpty()) ? title : null));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Per-origin content settings ──

    /** Content setting values for {@link #setContentSetting}. */
    public static final int SETTING_DEFAULT = 0;
    public static final int SETTING_ALLOW = 1;
    public static final int SETTING_BLOCK = 2;
    public static final int SETTING_ASK = 3;

    /**
     * Sets a per-origin web-permission content setting without showing a prompt.
     * Lets an app pre-grant (or block) a permission for an origin it trusts —
     * e.g. allow Web Notifications for your own web app so {@code new
     * Notification(...)} works without a runtime prompt.
     *
     * @param requestingUrl the origin URL, e.g. {@code "https://example.com"}
     * @param permission one of the {@code PERMISSION_*} constants
     *     ({@link #PERMISSION_NOTIFICATIONS}, {@link #PERMISSION_GEOLOCATION},
     *     {@link #PERMISSION_CAMERA}, {@link #PERMISSION_MICROPHONE},
     *     {@link #PERMISSION_NFC})
     * @param value one of the {@code SETTING_*} constants
     *     ({@link #SETTING_ALLOW}, {@link #SETTING_BLOCK}, {@link #SETTING_ASK},
     *     {@link #SETTING_DEFAULT})
     */
    public void setContentSetting(String requestingUrl, int permission, int value) {
        if (mNativePtr != 0) {
            nativeSetContentSetting(mNativePtr, requestingUrl, permission, value);
        }
    }

    // ── Proxy ──

    /** Proxy modes for {@link #setProxy(String, String)} (Chromium proxy modes). */
    public static final String PROXY_DIRECT = "direct";
    public static final String PROXY_SYSTEM = "system";
    public static final String PROXY_AUTO_DETECT = "auto_detect";
    public static final String PROXY_FIXED = "fixed_servers";
    public static final String PROXY_PAC = "pac_script";

    /**
     * Configures the proxy for this browser's request context. [mode] is one of
     * the {@code PROXY_*} constants; [server] is {@code "host:port"} (or
     * {@code "scheme://host:port"}) for {@link #PROXY_FIXED}, the PAC URL for
     * {@link #PROXY_PAC}, and ignored otherwise. Returns false if the preference
     * could not be applied. Useful for kiosk/enterprise deployments.
     */
    public boolean setProxy(String mode, String server) {
        return mNativePtr != 0 && nativeSetProxy(mNativePtr, mode, server);
    }

    /** Convenience: route all traffic through a fixed proxy {@code "host:port"}. */
    public boolean setProxy(String server) {
        return setProxy(PROXY_FIXED, server);
    }

    /** Convenience: disable proxying (direct connection). */
    public boolean clearProxy() {
        return setProxy(PROXY_DIRECT, null);
    }

    // ── Device choosers (Web Bluetooth / WebUSB / Web Serial) ──

    /** Device chooser kinds, passed to {@link DeviceChooserHandler}. */
    public static final int DEVICE_BLUETOOTH = 0;
    public static final int DEVICE_USB = 1;
    public static final int DEVICE_SERIAL = 2;

    /** A candidate device offered to a {@link DeviceChooserSession}. */
    public static final class ChooserDevice {
        /** Opaque id to pass to {@link DeviceChooserSession#select}. */
        public final String id;
        /** Human-readable label for the picker (may be empty). */
        public final String name;

        ChooserDevice(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * A live device chooser. When a page calls {@code
     * navigator.bluetooth.requestDevice()}, {@code navigator.usb.requestDevice()}
     * or {@code navigator.serial.requestPort()} under a user gesture, the handler
     * receives a session; candidate devices then stream in via {@link
     * DeviceChooserHandler#onDeviceAdded}. The app presents its own picker and
     * calls {@link #select} or {@link #cancel}. Exactly one of those should be
     * called; further calls are ignored.
     */
    public interface DeviceChooserSession {
        /** One of {@link #DEVICE_BLUETOOTH}, {@link #DEVICE_USB}, {@link #DEVICE_SERIAL}. */
        int getType();

        /** The origin (e.g. {@code "https://example.com"}) that requested a device. */
        String getOrigin();

        /** Continue the request with the chosen device (an id from {@link ChooserDevice}). */
        void select(String deviceId);

        /** Dismiss the chooser; the page's request promise rejects. */
        void cancel();
    }

    /**
     * Presents device chooser UI for the frontier web device APIs. Without a
     * handler set, such requests are cancelled (the page promise rejects) &mdash;
     * the engine has no built-in picker in this embedding. All methods are called
     * on the UI thread.
     */
    public interface DeviceChooserHandler {
        /** A chooser opened; present UI and keep {@code session} to resolve it. */
        void onShowDeviceChooser(DeviceChooserSession session);

        /**
         * A candidate device became available (called repeatedly while a Bluetooth
         * scan runs; once per present device for USB/Serial). May repeat for the
         * same id to update its label.
         */
        void onDeviceAdded(DeviceChooserSession session, ChooserDevice device);

        /**
         * A device previously reported to {@link #onDeviceAdded} is no longer
         * available (e.g. a USB/serial device was unplugged mid-chooser). Remove
         * it from the picker. Not called for Bluetooth scans (which only add).
         * Optional; the default does nothing.
         */
        default void onDeviceRemoved(DeviceChooserSession session, String deviceId) {}

        /** The engine closed the chooser (e.g. navigation); dismiss the picker. */
        void onDeviceChooserClosed(DeviceChooserSession session);
    }

    private DeviceChooserHandler mDeviceChooserHandler;
    private final java.util.concurrent.ConcurrentHashMap<Long, DeviceChooserSessionImpl>
            mDeviceChooserSessions = new java.util.concurrent.ConcurrentHashMap<>();

    /** Sets the device chooser handler (null = cancel all device requests). */
    public void setDeviceChooserHandler(DeviceChooserHandler handler) {
        mDeviceChooserHandler = handler;
        if (mNativePtr != 0) {
            nativeSetDisplayCallback(mNativePtr, this);
            nativeSetDeviceChooserHandler(mNativePtr, handler != null);
        }
    }

    private final class DeviceChooserSessionImpl implements DeviceChooserSession {
        private final long mId;
        private final int mType;
        private final String mOrigin;
        private boolean mResolved;

        DeviceChooserSessionImpl(long id, int type, String origin) {
            mId = id;
            mType = type;
            mOrigin = origin;
        }

        @Override public int getType() { return mType; }
        @Override public String getOrigin() { return mOrigin; }

        @Override public void select(String deviceId) {
            if (mResolved) return;
            mResolved = true;
            if (mNativePtr != 0) nativeDeviceChooserSelect(mNativePtr, mId, deviceId);
        }

        @Override public void cancel() {
            if (mResolved) return;
            mResolved = true;
            if (mNativePtr != 0) nativeDeviceChooserCancel(mNativePtr, mId);
        }
    }

    // Called from native (UI thread) when a page requests a device.
    private void onDeviceChooserShow(long id, int type, String origin) {
        DeviceChooserHandler h = mDeviceChooserHandler;
        if (h == null) {
            if (mNativePtr != 0) nativeDeviceChooserCancel(mNativePtr, id);
            return;
        }
        DeviceChooserSessionImpl session = new DeviceChooserSessionImpl(id, type, origin);
        mDeviceChooserSessions.put(id, session);
        h.onShowDeviceChooser(session);
    }

    // Called from native (UI thread) as a candidate device becomes available.
    private void onDeviceChooserDeviceAdded(long id, String deviceId, String deviceName) {
        DeviceChooserHandler h = mDeviceChooserHandler;
        DeviceChooserSessionImpl session = mDeviceChooserSessions.get(id);
        if (h != null && session != null) {
            h.onDeviceAdded(session, new ChooserDevice(deviceId, deviceName));
        }
    }

    // Called from native (UI thread) when a candidate device goes away.
    private void onDeviceChooserDeviceRemoved(long id, String deviceId) {
        DeviceChooserHandler h = mDeviceChooserHandler;
        DeviceChooserSessionImpl session = mDeviceChooserSessions.get(id);
        if (h != null && session != null) {
            h.onDeviceRemoved(session, deviceId);
        }
    }

    // Called from native (UI thread) when the engine closes the chooser.
    private void onDeviceChooserClosed(long id) {
        DeviceChooserHandler h = mDeviceChooserHandler;
        DeviceChooserSessionImpl session = mDeviceChooserSessions.remove(id);
        if (h != null && session != null) {
            h.onDeviceChooserClosed(session);
        }
    }

    // ── SSL / certificate errors ──

    /** Receives the app's proceed/cancel decision for an invalid certificate. */
    public interface SslErrorCallback {
        void proceed();
        void cancel();
    }

    /**
     * Decides whether to continue loading a URL whose TLS certificate failed
     * validation (the WebView {@code onReceivedSslError} equivalent). Without a
     * handler set, the load is cancelled &mdash; the secure default.
     */
    public interface SslErrorHandler {
        void onSslError(String url, int certError, SslErrorCallback callback);
    }

    private SslErrorHandler mSslErrorHandler;

    /** Sets the SSL/certificate error handler (null = cancel on every cert error). */
    public void setSslErrorHandler(SslErrorHandler handler) {
        mSslErrorHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    // Called from native (UI thread) when a certificate fails validation.
    private void onSslError(long id, String url, int certError) {
        SslErrorHandler h = mSslErrorHandler;
        if (h == null) {
            if (mNativePtr != 0) nativeRespondSslError(mNativePtr, id, false);
            return;
        }
        final boolean[] answered = {false};
        SslErrorCallback cb = new SslErrorCallback() {
            @Override public void proceed() { respond(true); }
            @Override public void cancel() { respond(false); }
            private void respond(boolean proceed) {
                if (answered[0]) return;
                answered[0] = true;
                if (mNativePtr != 0) nativeRespondSslError(mNativePtr, id, proceed);
            }
        };
        h.onSslError(url, certError, cb);
    }

    // ── HTTP authentication ──

    /** Receives the app's credentials (or cancel) for an HTTP auth challenge. */
    public interface HttpAuthCallback {
        void proceed(String username, String password);
        void cancel();
    }

    /**
     * Supplies credentials for an HTTP or proxy authentication challenge (the
     * WebView {@code onReceivedHttpAuthRequest} equivalent). Without a handler
     * set, the challenge is cancelled (no credentials supplied).
     */
    public interface HttpAuthHandler {
        void onHttpAuthRequest(String host, String realm, int port,
                               boolean isProxy, HttpAuthCallback callback);
    }

    private HttpAuthHandler mHttpAuthHandler;

    /** Sets the HTTP auth handler (null = cancel every auth challenge). */
    public void setHttpAuthHandler(HttpAuthHandler handler) {
        mHttpAuthHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    // Called from native (UI thread) on an HTTP/proxy auth challenge.
    private void onHttpAuthRequest(long id, String host, String realm, int port,
                                   boolean isProxy) {
        HttpAuthHandler h = mHttpAuthHandler;
        if (h == null) {
            if (mNativePtr != 0) nativeRespondHttpAuth(mNativePtr, id, null, null);
            return;
        }
        final boolean[] answered = {false};
        HttpAuthCallback cb = new HttpAuthCallback() {
            @Override public void proceed(String u, String p) { respond(u, p); }
            @Override public void cancel() { respond(null, null); }
            private void respond(String u, String p) {
                if (answered[0]) return;
                answered[0] = true;
                if (mNativePtr != 0) nativeRespondHttpAuth(mNativePtr, id, u, p);
            }
        };
        h.onHttpAuthRequest(host, realm, port, isProxy, cb);
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

    // ─────────────── Popups / window.open ───────────────

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

    // ---- Media / page lifecycle controls ----------------------------------
    //
    // Granular levers so a host can compose ANY background behavior. The Compose
    // CefriumView wires onPause()/onResume() to the host lifecycle by default
    // (see CefriumView's backgroundMediaPolicy); these are the building blocks
    // for custom policies (mute-only, keep-playing background audio, etc.).

    private org.chromium.content_public.browser.WebContents webContents() {
        return mNativePtr != 0 ? nativeGetWebContents(mNativePtr) : null;
    }

    /**
     * Suspends active media (audio + video) at the browser-process level
     * (MediaSession Suspend == the audio-focus-loss path). Position is preserved;
     * {@link #resumeMedia()} resumes. Works even after the renderer JS is
     * suspended -- unlike {@code evaluateJavaScript("...pause()")}, which is
     * queued and never runs once backgrounded.
     */
    public void suspendMedia() {
        org.chromium.content_public.browser.WebContents wc = webContents();
        if (wc != null) {
            org.chromium.content_public.browser.MediaSession ms =
                    org.chromium.content_public.browser.MediaSession.fromWebContents(wc);
            if (ms != null) ms.suspend();
        }
    }

    /** Resumes media suspended by {@link #suspendMedia()} (resumes where it left off). */
    public void resumeMedia() {
        org.chromium.content_public.browser.WebContents wc = webContents();
        if (wc != null) {
            org.chromium.content_public.browser.MediaSession ms =
                    org.chromium.content_public.browser.MediaSession.fromWebContents(wc);
            if (ms != null) ms.resume();
        }
    }

    /**
     * Mutes/unmutes ALL audio output of the page (keeps media decoding/playing,
     * just silenced). Use this for a "go quiet but keep position/time advancing"
     * policy; use {@link #suspendMedia()} to actually pause.
     */
    public void setAudioMuted(boolean muted) {
        org.chromium.content_public.browser.WebContents wc = webContents();
        if (wc != null) wc.setAudioMuted(muted);
    }

    /** Whether the page's audio is currently muted via {@link #setAudioMuted}. */
    public boolean isAudioMuted() {
        org.chromium.content_public.browser.WebContents wc = webContents();
        return wc != null && wc.isAudioMuted();
    }

    /**
     * Whether this browser currently has audible media playing -- at least one
     * audio media session that is not paused. Backed by the media-session
     * playback observer ({@link #setMediaSessionEnabled}). Lets a multi-tab host
     * avoid discarding/closing a tab that is actively playing sound (background
     * music or video). Returns {@code false} when media session is disabled.
     */
    public boolean isPlayingAudio() {
        return mMediaSession != null && mMediaSession.isPlayingAudio();
    }

    /**
     * Sets the page's visibility (compositor + Page Visibility API
     * {@code document.hidden}). HIDDEN saves GPU/battery and fires
     * {@code visibilitychange}, but does NOT by itself stop audio.
     */
    public void setPageVisible(boolean visible) {
        org.chromium.content_public.browser.WebContents wc = webContents();
        if (wc != null) {
            wc.updateWebContentsVisibility(visible
                    ? org.chromium.content_public.browser.Visibility.VISIBLE
                    : org.chromium.content_public.browser.Visibility.HIDDEN);
        }
    }

    /**
     * Hard-stops all media players (not resumable like {@link #suspendMedia()})
     * and mutes audio. For "fully release audio on background" when you do not
     * need to resume in place.
     */
    public void stopAllMedia() {
        org.chromium.content_public.browser.WebContents wc = webContents();
        if (wc != null) {
            wc.suspendAllMediaPlayers();
            wc.setAudioMuted(true);
        }
    }

    /**
     * Stops the page's media session and dismisses its media notification. Unlike
     * {@link #stopAllMedia()} (which pauses + mutes, leaving a paused notification),
     * this clears the session -- for a host navigating away from a video screen that
     * wants the tray notification gone. Operates at the WebContents level, so it also
     * stops media in cross-origin iframes (e.g. a YouTube embed) that page JS cannot reach.
     */
    public void stopMediaSession() {
        org.chromium.content_public.browser.WebContents wc = webContents();
        if (wc != null) {
            org.chromium.content_public.browser.MediaSession ms =
                    org.chromium.content_public.browser.MediaSession.fromWebContents(wc);
            if (ms != null) ms.stop();
        }
        if (mMediaSession != null) mMediaSession.hideNotification();
    }

    /**
     * Convenience for "host is backgrounding": hide the page, suspend media and
     * mute audio so inline audio/video actually stops (visibility HIDDEN alone
     * keeps background audio playing). Resumable via {@link #onResume()}. Call
     * from Activity.onPause()/onStop(); the Compose {@code CefriumView} wires this
     * automatically (see its {@code backgroundMediaPolicy}).
     */
    public void onPause() {
        setPageVisible(false);
        suspendMedia();
        setAudioMuted(true);
    }

    /**
     * Convenience for "host is foregrounding": unmute, make the page visible and
     * resume media suspended by {@link #onPause()}. Call from
     * Activity.onResume()/onStart(); wired automatically by {@code CefriumView}.
     */
    public void onResume() {
        setAudioMuted(false);
        setPageVisible(true);
        resumeMedia();
    }

    /**
     * Shuts down render child-processes left orphaned after browsers were closed
     * (e.g. LRU eviction in a browser pool). Closing a browser destroys its
     * WebContents but Chromium may keep the child process around; on
     * process-per-browser devices the sandboxed process count then grows. This
     * reaps processes with zero frames only (never a live renderer), after a short
     * delay to let the just-closed browser tear down. Memory-neutral but keeps the
     * process count bounded. Safe no-op on shared-renderer devices.
     */
    public static void reapUnusedRenderers() {
        nativeReapUnusedRenderers();
    }

    /**
     * Exact number of live renderer processes the engine currently has -- counts
     * {@code RenderProcessHost} only, so utility/network/audio/storage and GPU child
     * processes are NOT included (this is the clean renderer count, unlike
     * {@code ps | grep sandboxed_process} which mixes them all). Excludes the spare
     * renderer. After a browser pool settles this is approximately its
     * {@code maxAlive}. Diagnostic; call on the main thread (returns -1 otherwise).
     */
    public static int activeRenderProcessCount() {
        return nativeActiveRenderProcessCount();
    }

    /**
     * Renderer processes that currently host ZERO frames -- reuse "zombies" the
     * pool's reap targets. A high value while a pool runs means orphaned renderers
     * are lingering (reusable but not freed). Main thread only (-1 otherwise).
     * Diagnostic.
     */
    public static int zeroFrameRenderProcessCount() {
        return nativeZeroFrameRenderProcessCount();
    }

    /**
     * Total live RenderFrameHosts across all renderer processes -- the real
     * "working frame" count (host pages + iframes). If this grows as you open pages
     * and never drops, the closed pages' frames are NOT being destroyed. Main
     * thread only (-1 otherwise). Diagnostic.
     */
    public static int totalRenderFrameHostCount() {
        return nativeTotalRenderFrameHostCount();
    }

    /**
     * Cumulative number of browsers that have finished closing (their
     * {@code OnBeforeClose} fired). If this does NOT grow as a pool evicts
     * browsers, {@link #close()} is not completing -- the page leaks. Diagnostic.
     */
    public static int closedBrowserCount() {
        return nativeClosedBrowserCount();
    }

    /** Live (not-yet-closed) browser instances. Diagnostic. */
    public static int liveBrowserCount() {
        return nativeLiveBrowserCount();
    }

    /**
     * Close this browser and release all native resources.
     * After calling this method, no other methods may be called on this instance.
     * For Surface mode, also removes the rendering views.
     */
    public void close() {
        if (mClosed) return;
        mClosed = true;
        if (mMediaSession != null) {
            mMediaSession.destroy();
            mMediaSession = null;
        }
        // Only INITIATE the close. The ContentViewRenderView + WindowAndroid are NOT
        // destroyed here: tearing down the window before the async close completed
        // orphaned the WebContents, so the browser never actually closed and its
        // renderers (host page + cross-origin iframes) leaked. onNativeBrowserClosed()
        // -- invoked from native OnBeforeClose -- destroys them once the close lands.
        if (mNativePtr != 0) {
            nativeClose(mNativePtr);
        }
    }

    /**
     * Invoked from native {@code OnBeforeClose} when the browser has actually
     * finished closing -- now it is safe to tear down the Java rendering views.
     * (Doing this synchronously in {@link #close()} aborted the async close.)
     * Called by native via JNI (like onOpenPopup/onShouldBlockRequest) -- keep it.
     */
    private void onNativeBrowserClosed() {
        mNativePtr = 0;
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
        BitmapCallback cap = mPendingCapture;
        if (cap != null) {
            mPendingCapture = null;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    width, height, android.graphics.Bitmap.Config.ARGB_8888);
            pixels.rewind();
            bmp.copyPixelsFromBuffer(pixels);
            cap.onBitmap(bmp);
        }
    }

    // ── Snapshot / capture ──

    /** Receives the captured page bitmap (null on failure). */
    public interface BitmapCallback {
        void onBitmap(android.graphics.Bitmap bitmap);
    }

    private BitmapCallback mPendingCapture;

    /**
     * Captures the current page to a {@link android.graphics.Bitmap}, delivered
     * asynchronously on the main thread. In off-screen mode the next painted
     * frame is returned; in Surface mode the visible surface is copied via
     * {@code PixelCopy}. The callback receives null if capture is unavailable.
     */
    public void captureBitmap(BitmapCallback callback) {
        if (callback == null) {
            return;
        }
        if (mContentViewRenderView != null) {
            // Surface mode: PixelCopy the rendering SurfaceView.
            android.view.SurfaceView sv = findSurfaceView(mContentViewRenderView);
            if (sv != null && sv.getWidth() > 0 && sv.getHeight() > 0
                    && android.os.Build.VERSION.SDK_INT
                            >= android.os.Build.VERSION_CODES.N) {
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        sv.getWidth(), sv.getHeight(),
                        android.graphics.Bitmap.Config.ARGB_8888);
                try {
                    android.view.PixelCopy.request(sv, bmp,
                            (result) -> callback.onBitmap(
                                    result == android.view.PixelCopy.SUCCESS ? bmp : null),
                            new android.os.Handler(android.os.Looper.getMainLooper()));
                } catch (IllegalArgumentException e) {
                    callback.onBitmap(null);
                }
                return;
            }
            callback.onBitmap(null);
            return;
        }
        // Off-screen mode: capture the next painted frame.
        mPendingCapture = callback;
        invalidate();
    }

    private static android.view.SurfaceView findSurfaceView(android.view.View v) {
        if (v instanceof android.view.SurfaceView) {
            return (android.view.SurfaceView) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.SurfaceView r = findSurfaceView(g.getChildAt(i));
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
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
        return createWithSurface(activity, false);
    }

    /**
     * Like {@link #createWithSurface(android.app.Activity)} but for a private
     * (incognito) tab. Incognito browsers share one off-the-record session that
     * is isolated from normal tabs; their cookies, history and cache live only
     * in memory and are cleared when the last incognito browser is closed.
     *
     * @param activity  The Activity to render in.
     * @param incognito true for a private / off-the-record browser.
     * @return A new CefriumBrowser instance with Surface rendering.
     */
    public static CefriumBrowser createWithSurface(android.app.Activity activity,
                                                   boolean incognito) {
        // Lazy init: no Application boilerplate or explicit initialize() needed.
        // CefriumInitProvider ran the per-process setup at app launch; initialize()
        // is idempotent and self-sufficient.
        if (!Cefrium.isInitialized()) {
            Cefrium.initialize(activity);
        }

        CefriumBrowser browser = new CefriumBrowser(true);

        // Create Chromium rendering infrastructure internally.
        // Use CefWindowAndroid (an ActivityWindowAndroid) to support intents —
        // required for the file picker (<input type=file>, File System Access),
        // permission requests, and other Activity-based flows — and to provide a
        // ModalDialogManager so tab-modal dialogs (e.g. the File System Access
        // folder-permission prompt) do not crash the native CHECK.
        browser.mIntentTracker =
                org.chromium.ui.base.IntentRequestTracker.createFromActivity(activity);
        browser.mWindowAndroid = new CefWindowAndroid(
                activity, true, browser.mIntentTracker, null, false);

        browser.mContentViewRenderView =
                new org.chromium.components.embedder_support.view.ContentViewRenderView(
                        activity);
        browser.mContentViewRenderView.onNativeLibraryLoaded(
                browser.mWindowAndroid);

        // Container that holds the rendering surface. CefriumSurfaceContainer
        // adds Android nested-scrolling so the browser can live inside a
        // scrollable parent.
        browser.mSurfaceContainer = new CefriumSurfaceContainer(activity);
        browser.mSurfaceContainer.addView(browser.mContentViewRenderView,
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        // Create native browser (non-windowless mode).
        float density = activity.getResources().getDisplayMetrics().density;
        browser.mIncognito = incognito;
        browser.mNativePtr = nativeCreateWithSurface(
                browser.mWindowAndroid, null,
                activity.getResources().getDisplayMetrics().widthPixels,
                activity.getResources().getDisplayMetrics().heightPixels,
                density, incognito);

        browser.mActivity = activity;

        return browser;
    }

    /** @return true if this is a private (incognito) browser. */
    public boolean isIncognito() {
        return mIncognito;
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

    /**
     * Enables system media controls (lock-screen + notification shade) for this
     * browser's playback, driven by the page's {@code navigator.mediaSession}
     *. Requires the {@code POST_NOTIFICATIONS} runtime permission
     * on Android 13+. Safe to call before or after the page loads.
     */
    public void setMediaSessionEnabled(boolean enabled) {
        mMediaSessionEnabled = enabled;
        if (enabled) {
            maybeCreateMediaSession();
        } else if (mMediaSession != null) {
            mMediaSession.destroy();
            mMediaSession = null;
        }
    }

    /**
     * Sets the PendingIntent fired when the user taps this browser's media control
     * (notification / lock-screen player). Lets a multi-tab host route the tap to the
     * exact tab that is playing. Pass {@code null} to revert to the app launcher.
     * Safe to call before or after the media session is created.
     */
    public void setMediaSessionActivity(android.app.PendingIntent activity) {
        mMediaSessionActivity = activity;
        if (mMediaSession != null) mMediaSession.setActivity(activity);
    }

    private void maybeCreateMediaSession() {
        if (!mMediaSessionEnabled || mMediaSession != null
                || mNativePtr == 0 || mActivity == null) {
            return;
        }
        org.chromium.content_public.browser.WebContents wc =
                nativeGetWebContents(mNativePtr);
        if (wc != null) {
            mMediaSession = new CefriumMediaSession(mActivity, wc);
            mMediaSession.setActivity(mMediaSessionActivity);
        }
    }

    // Internal: connect WebContents to rendering pipeline.
    // Called after the browser has loaded its first page.
    void connectWebContentsInternal() {
        // This runs from a 2000ms postDelayed (see loadUrl). If the browser was
        // closed in the meantime, mNativePtr still points at the native
        // SdkBrowserData until the async OnBeforeClose lands -- dereferencing it
        // here is a use-after-free. mClosed is set synchronously in close() on
        // this same (main) looper, so it reliably short-circuits the stale callback.
        if (mClosed || mNativePtr == 0 || mActivity == null) return;
        org.chromium.content_public.browser.WebContents wc = nativeGetWebContents(mNativePtr);
        if (wc == null) return;
        if (mWebContentsConnected) return;
        mWebContentsConnected = true;

        // Pull-to-refresh: set the overscroll handler EXACTLY ONCE here. Re-setting
        // it on a live WebContents leaves the native OverscrollControllerAndroid
        // holding a freed handler pointer -> getRef() assert crash. So
        // setPullToRefreshEnabled only flips the flag; the handler is bound here.
        if (mPullToRefresh) applyPullToRefresh(wc);

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
        // Enable nested-scroll boundary handoff against this WebContents.
        mSurfaceContainer.setWebContents(wc);

        int w = mContentViewRenderView.getWidth();
        int h = mContentViewRenderView.getHeight();
        if (w > 0 && h > 0) wc.setSize(w, h);

        mContentViewRenderView.setCurrentWebContents(wc);
        wc.updateWebContentsVisibility(
                org.chromium.content_public.browser.Visibility.VISIBLE);

        // Wire up system media controls if the app enabled them before connect.
        maybeCreateMediaSession();

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

        // A restored tab deferred its load until now (surface connected +
        // overscroll handler set). Trigger it once.
        if (mPendingRestoreLoad) {
            mPendingRestoreLoad = false;
            nativeLoadRestoredIfNecessary(mNativePtr);
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
            android.view.Surface surface, int width, int height, float scale,
            boolean incognito);
    private static native void nativeSetRenderHandler(long nativePtr, CefriumBrowser browser);
    private static native void nativeLoadUrl(long nativePtr, String url);
    private static native void nativeLoadDataWithBaseUrl(long nativePtr, String baseUrl,
            String data, String mimeType, String encoding, String historyUrl);
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
    private static native byte[] nativeSerializeNavigationState(long nativePtr);
    private static native void nativeRestoreNavigationState(long nativePtr, byte[] state);
    private static native void nativeLoadRestoredIfNecessary(long nativePtr);
    private static native void nativeEvaluateJavaScript(long nativePtr, String script);
    private static native void nativeQueryResult(long nativePtr, long queryId,
            boolean success, String response, int errorCode, String errorMessage);
    private static native void nativeSetCookie(long nativePtr, String url, String name,
            String value, boolean secure, boolean httpOnly, int sameSite);
    private static native void nativeReapUnusedRenderers();
    private static native int nativeActiveRenderProcessCount();
    private static native int nativeZeroFrameRenderProcessCount();
    private static native int nativeTotalRenderFrameHostCount();
    private static native int nativeClosedBrowserCount();
    private static native int nativeLiveBrowserCount();
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
    private static native void nativeRespondPermission(long nativePtr, long promptId, boolean grant);
    private static native void nativeSetContentSetting(long nativePtr, String requestingUrl, int permission, int value);
    private static native boolean nativeSetProxy(long nativePtr, String mode, String server);
    private static native void nativeSetDeviceChooserHandler(long nativePtr, boolean enabled);
    private static native void nativeDeviceChooserSelect(long nativePtr, long id, String deviceId);
    private static native void nativeDeviceChooserCancel(long nativePtr, long id);
    private static native void nativeRespondSslError(long nativePtr, long id, boolean proceed);
    private static native void nativeRespondHttpAuth(long nativePtr, long id, String username, String password);
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
