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
    // When false, a small script is injected on each load-finish to neutralize
    // page/pinch zoom for arbitrary content. See setPinchToZoomEnabled(boolean).
    private boolean mPinchToZoomEnabled = true;

    // Forces a non-user-scalable viewport and blocks the multi-touch pinch
    // gesture. Re-applied on every load-finish so it survives navigation. Blink
    // re-evaluates the viewport when the meta's content attribute changes, so
    // setting it post-load re-clamps the page scale; the capturing touchmove
    // listener blocks the pinch gesture itself regardless of the page's viewport.
    // The handlers are stored on window and the original viewport is saved so
    // PINCH_ZOOM_ENABLE_JS can fully revert without a page reload.
    private static final String PINCH_ZOOM_DISABLE_JS =
            "(function(){"
            + "var m=document.querySelector('meta[name=\"viewport\"]');"
            + "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');"
            + "(document.head||document.documentElement).appendChild(m);}"
            + "if(window.__cefriumVp===undefined){"
            + "window.__cefriumVp=m.getAttribute('content')||'';}"
            + "m.setAttribute('content','width=device-width, initial-scale=1, "
            + "minimum-scale=1, maximum-scale=1, user-scalable=no');"
            + "if(!window.__cefriumNoPinchTM){"
            + "window.__cefriumNoPinchTM=function(e){"
            + "if(e.touches&&e.touches.length>1)e.preventDefault();};"
            + "window.__cefriumNoPinchGS=function(e){e.preventDefault();};"
            + "document.addEventListener('touchmove',window.__cefriumNoPinchTM,"
            + "{passive:false,capture:true});"
            + "document.addEventListener('gesturestart',window.__cefriumNoPinchGS,"
            + "{passive:false,capture:true});}"
            + "})();";

    // Reverts PINCH_ZOOM_DISABLE_JS in-place (no reload): restores the original
    // viewport meta and removes the gesture-blocking listeners.
    private static final String PINCH_ZOOM_ENABLE_JS =
            "(function(){"
            + "var m=document.querySelector('meta[name=\"viewport\"]');"
            + "if(m){m.setAttribute('content',"
            + "(window.__cefriumVp!==undefined&&window.__cefriumVp)?"
            + "window.__cefriumVp:'width=device-width, initial-scale=1');}"
            + "if(window.__cefriumNoPinchTM){"
            + "document.removeEventListener('touchmove',window.__cefriumNoPinchTM,"
            + "{capture:true});"
            + "document.removeEventListener('gesturestart',window.__cefriumNoPinchGS,"
            + "{capture:true});"
            + "window.__cefriumNoPinchTM=null;window.__cefriumNoPinchGS=null;}"
            + "})();";

    // Surface rendering internals — not exposed in public API.
    private android.app.Activity mActivity;
    private org.chromium.ui.base.WindowAndroid mWindowAndroid;
    private org.chromium.ui.base.IntentRequestTracker mIntentTracker;
    private org.chromium.components.embedder_support.view.ContentViewRenderView mContentViewRenderView;
    private org.chromium.components.autofill.AutofillProvider mAutofillProvider;
    private CefriumSurfaceContainer mSurfaceContainer;
    // Down-time of the current synthetic touch stream (for MotionEvent gesture
    // recognition in windowed mode); set on sendTouchDown.
    private long mTouchDownTime;
    private CefriumMediaSession mMediaSession;
    private boolean mMediaSessionEnabled;
    private android.app.PendingIntent mMediaSessionActivity;
    private boolean mWebContentsConnected;
    // FEATURE-059: external URL scheme hand-off. Holds the per-browser
    // InterceptNavigationDelegate (installed on the WebContents in
    // connectWebContentsInternal) that routes unknown/app schemes to Android.
    private org.chromium.components.external_intents.InterceptNavigationDelegateImpl mNavDelegate;
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
        // Surface mode connects the WebContents when the native browser exists
        // (onNativeBrowserCreated), not after this load.
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
     * reloads it (Chrome-style). Off by default. May be called, and toggled,
     * at any time.
     */
    public void setPullToRefreshEnabled(boolean enabled) {
        mPullToRefresh = enabled;
    }

    // Bound exactly once, when the WebContents connects, whatever the flag:
    // re-setting it on a live WebContents leaves the native
    // OverscrollControllerAndroid holding a freed handler (getRef() assert).
    // start() reads the flag, so enabling later still works.
    private void bindOverscrollRefreshHandler(
            org.chromium.content_public.browser.WebContents wc) {
        wc.setOverscrollRefreshHandler(new org.chromium.ui.OverscrollRefreshHandler() {
            @Override
            public boolean start(int type, int initiatingEdge) {
                return mPullToRefresh
                        && type == org.chromium.ui.OverscrollAction.PULL_TO_REFRESH;
            }
            @Override public void pull(float xDelta, float yDelta) {}
            @Override public void release(int status) {
                // Chromium 151: release(boolean allowRefresh) -> release(int status),
                // an @OverscrollActivationStatus. Refresh on ALLOW/FORCE activation.
                if (status == org.chromium.ui.OverscrollActivationStatus.ALLOW_ACTIVATION
                        || status == org.chromium.ui.OverscrollActivationStatus.FORCE_ACTIVATION) {
                    reload();
                }
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
        // FEATURE-064: register the WebAuthn Authenticator factory (same rationale
        // and timing as payments) so passkeys reach the Android Credential Manager
        // (Proton Pass etc.). Cefrium stubs Chrome's registrar, so nothing binds
        // blink.mojom.Authenticator without this.
        try {
            com.cefrium.webauthn.CefAuthenticatorInterfaceRegistrar.register();
        } catch (Throwable t) {
            android.util.Log.w("Cefrium", "WebAuthn registrar skipped: " + t);
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
            // connectWebContentsInternal triggers the deferred restore load once
            // the view + overscroll handler are set up -- loading earlier crashes
            // the overscroll controller on the RFH swap.
            mPendingRestoreLoad = true;
            // A browser that already connected triggers the restored load now;
            // otherwise onNativeBrowserCreated connects and triggers it.
            if (mWebContentsConnected && mSurfaceContainer != null) {
                mPendingRestoreLoad = false;
                nativeLoadRestoredIfNecessary(mNativePtr);
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
        if (!isLoading && !mPinchToZoomEnabled) {
            evaluateJavaScript(PINCH_ZOOM_DISABLE_JS);
        }
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
         * @param origin URL of the frame that called window.cefriumQuery. The
         *        {@link QueryOriginPolicy} has already allowed it; it is here
         *        for per-request decisions and logging.
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

    /**
     * Error code a page's {@code onFailure} receives when the calling frame's
     * origin is not allowed by the {@link QueryOriginPolicy} the handler was
     * registered with. The handler is not invoked.
     */
    public static final int QUERY_ERROR_ORIGIN_DENIED = -403;

    /**
     * Decides which origins may reach a {@link QueryHandler}. Required at
     * registration: the bridge is the one path from page content into your
     * code, so "which pages may call it" has to be a decision, not a default.
     *
     * <p>{@link #allows} receives the calling frame's URL. {@link #exactly}
     * and {@link #loopback} compare origins, {@code scheme://host[:port]} with
     * the default port dropped; {@link #bundledAssets} compares a path prefix.
     * {@code data:}, {@code about:} (including {@code about:blank} and
     * {@code srcdoc} iframes a page of yours creates) and unparsable frames are
     * opaque. So is a frame whose committed origin is opaque at an allowed URL:
     * an {@code <iframe sandbox>} or a page served with
     * {@code Content-Security-Policy: sandbox}. The bridge denies every opaque
     * caller before consulting the policy, unless the policy is
     * {@link #anyOrigin()} itself; a custom predicate never sees one.
     * A custom predicate sees the raw URL: compare
     * {@link #originOf(String) originOf(url)} or the parsed host, never a
     * string prefix, because {@code https://example.com.evil.com/} starts with
     * {@code https://example.com}.</p>
     */
    public interface QueryOriginPolicy {
        boolean allows(String frameUrl);

        /**
         * The origin of a frame URL, {@code scheme://host[:port]} with the
         * default port dropped, or {@code "null"} for an opaque origin. The
         * same normalization the built-in policies use.
         */
        static String originOf(String frameUrl) {
            return normalizeOrigin(frameUrl);
        }

        /** Only these origins, e.g. {@code exactly("https://example.com")}. */
        static QueryOriginPolicy exactly(String... origins) {
            final java.util.Set<String> allowed = new java.util.HashSet<>();
            for (String o : origins) {
                String n = normalizeOrigin(o);
                if ("null".equals(n)) {
                    throw new IllegalArgumentException("QueryOriginPolicy.exactly: \""
                            + o + "\" is not an http(s) origin such as "
                            + "https://example.com; it could never match");
                }
                allowed.add(n);
            }
            return url -> {
                String o = normalizeOrigin(url);
                return !"null".equals(o) && allowed.contains(o);
            };
        }

        /**
         * Pages your app ships in its own assets,
         * {@code file:///android_asset/...}. Only those: other {@code file:}
         * URLs are not accepted.
         */
        static QueryOriginPolicy bundledAssets() {
            return url -> url != null && url.startsWith("file:///android_asset/");
        }

        /**
         * http and https on 127.0.0.1 or localhost, any port: the pattern for
         * content served by a local server inside your app. Any port includes
         * other apps' local servers; when your server's port is known, prefer
         * {@code exactly("http://127.0.0.1:" + port)}.
         */
        static QueryOriginPolicy loopback() {
            return url -> {
                if (url == null) return false;
                android.net.Uri u = android.net.Uri.parse(url);
                String scheme = u.getScheme();
                String host = u.getHost();
                return ("http".equals(scheme) || "https".equals(scheme))
                        && ("127.0.0.1".equals(host) || "localhost".equals(host));
            };
        }

        /**
         * Every origin, including opaque ones. A named choice: registering
         * with it logs a warning, because any page this browser loads can then
         * call your handler.
         */
        static QueryOriginPolicy anyOrigin() {
            return ANY_ORIGIN;
        }
    }

    private static final QueryOriginPolicy ANY_ORIGIN = url -> true;

    /**
     * Normalizes a frame URL to {@code scheme://host[:port]}, dropping the
     * default port, or returns {@code "null"} for an opaque origin.
     */
    static String normalizeOrigin(String url) {
        if (url == null) return "null";
        android.net.Uri u = android.net.Uri.parse(url);
        String scheme = u.getScheme();
        String host = u.getHost();
        if (scheme == null || host == null || host.isEmpty()) return "null";
        scheme = scheme.toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) return "null";
        int port = u.getPort();
        boolean defaultPort = port == -1
                || ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return defaultPort ? scheme + "://" + h : scheme + "://" + h + ":" + port;
    }

    /**
     * Policy and handler as one value, so a query can never observe a new
     * handler paired with an old or missing policy while a registration from
     * another thread is half written.
     */
    private static final class QueryRegistration {
        final QueryOriginPolicy policy;
        final QueryHandler handler;
        QueryRegistration(QueryOriginPolicy policy, QueryHandler handler) {
            this.policy = policy;
            this.handler = handler;
        }
    }

    private volatile QueryRegistration mQuery;

    /**
     * Registers the handler for {@code window.cefriumQuery} calls, and the
     * policy that decides which origins may reach it.
     *
     * <p>A call from a frame whose origin the policy does not allow fails on
     * the page with {@link #QUERY_ERROR_ORIGIN_DENIED} and never reaches the
     * handler. Without this, any page the browser loads, including an iframe
     * from a third party, gets a channel into your code.</p>
     *
     * @param policy required when handler is non-null; use
     *        {@link QueryOriginPolicy#anyOrigin()} to accept every origin
     *        on purpose.
     * @param handler the handler, or null to unregister.
     */
    public void setQueryHandler(QueryOriginPolicy policy, QueryHandler handler) {
        if (handler != null && policy == null) {
            throw new IllegalArgumentException(
                    "setQueryHandler needs a QueryOriginPolicy; pass "
                    + "QueryOriginPolicy.anyOrigin() to accept every origin");
        }
        if (handler != null && policy == ANY_ORIGIN) {
            android.util.Log.w(TAG, "window.cefriumQuery is reachable from every "
                    + "origin (QueryOriginPolicy.anyOrigin())");
        }
        mQuery = handler == null ? null : new QueryRegistration(policy, handler);
    }

    // Called from native (browser UI thread) when JS calls window.cefriumQuery.
    // origin is the frame URL; committedOrigin is the frame's committed origin,
    // serialized, "null" when opaque.
    private void onJsQuery(final long queryId, String request, String origin,
            String committedOrigin) {
        final QueryRegistration reg = mQuery;
        final QueryHandler handler = reg == null ? null : reg.handler;
        boolean allowed = false;
        // A sandboxed frame (sandbox attribute or CSP sandbox) has an opaque
        // origin even at an allowed URL: the content its embedder did not
        // trust. Only anyOrigin() admits it; no other policy is consulted.
        boolean opaque = committedOrigin == null || "null".equals(committedOrigin);
        if (handler != null && (!opaque || reg.policy == ANY_ORIGIN)) {
            try {
                allowed = reg.policy.allows(origin);
            } catch (RuntimeException e) {
                // Fail closed: an exception here would otherwise escape into
                // the native caller and leave the query pending.
                android.util.Log.w(TAG, "QueryOriginPolicy threw; denying", e);
            }
        }
        if (handler != null && !allowed) {
            // Log the origin, never the full URL: a URL can carry tokens.
            android.util.Log.w(TAG, "window.cefriumQuery denied for origin "
                    + normalizeOrigin(origin));
            nativeQueryResult(mNativePtr, queryId, false, null,
                    QUERY_ERROR_ORIGIN_DENIED, "origin not allowed");
            return;
        }
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

    /**
     * Enables or disables pinch-to-zoom (and page zoom) for arbitrary page
     * content. Enabled by default, matching a normal browser.
     *
     * <p>When disabled, a small script is injected on every load-finish that
     * forces a non-user-scalable viewport
     * ({@code user-scalable=no, maximum-scale=1}) and blocks the multi-touch
     * pinch gesture. This is applied on top of whatever viewport the page
     * declares, so it works for pages you do not control. If you DO control the
     * HTML, declaring that viewport meta yourself is equivalent and takes effect
     * from first paint.
     *
     * <p>Because the neutralizer runs at load-finish (not document-start), a
     * page could in principle be pinched during the brief pre-load window; for a
     * hard guarantee from first paint, ship the viewport meta in the page.
     *
     * @param enabled true to allow zoom (default), false to disable it.
     */
    public void setPinchToZoomEnabled(boolean enabled) {
        mPinchToZoomEnabled = enabled;
        // Apply immediately to the loaded document (no reload). Disabling injects
        // the neutralizer; enabling reverts it in-place.
        if (mNativePtr != 0) {
            evaluateJavaScript(enabled ? PINCH_ZOOM_ENABLE_JS : PINCH_ZOOM_DISABLE_JS);
        }
    }

    /** @return whether pinch/page zoom is currently enabled (default true). */
    public boolean isPinchToZoomEnabled() {
        return mPinchToZoomEnabled;
    }

    // ─────────────── Navigation extras ───────────────

    /**
     * Loads an HTML string directly (served to the engine as a data: URL). The
     * document's origin is opaque, so only {@link QueryOriginPolicy#anyOrigin()}
     * lets it reach a {@link QueryHandler}; HTML that needs the bridge should
     * use {@link #loadHtmlWithBaseUrl} with your own origin and
     * {@link QueryOriginPolicy#exactly} on it.
     */
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

        /**
         * A {@code beforeunload} "Leave site?" prompt for a dirty form. Return
         * true if handled (you MUST call the callback: success = leave). Default
         * returns false (the load proceeds). {@code userInput} is unused.
         */
        default boolean onBeforeUnloadDialog(String message, boolean isReload,
                                             JsDialogCallback callback) {
            return false;
        }

        /** The dialog state was reset (e.g. before a new navigation). */
        default void onDialogResetState() {}

        /** A JS dialog was closed. */
        default void onDialogClosed() {}
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
    // Bit 30, matching CEF_PERMISSION_TYPE_NFC. It was 29 until CEF 8078 took
    // that bit for AMBIENT_LOGIN; these two values must stay in lockstep or NFC
    // permission requests are routed as something else.
    public static final int PERMISSION_NFC = 1 << 30;

    /** Receives the app's grant/deny decision for a permission request. */
    public interface PermissionCallback {
        void respond(boolean grant);
    }

    /**
     * Decides web permission requests (geolocation, camera, mic, ...). Without a
     * handler set, all requests are denied -- the default Android permission
     * dialog is not available in this engine.
     *
     * <p><b>Some requests are rejected further down the stack, before a web
     * permission request exists at all, so this handler is never consulted.
     * None of these is a handler bug.</b> Each states what makes it reach the
     * handler instead:
     *
     * <ul>
     * <li><b>Geolocation</b> is intercepted by Android's own permission context
     *     before the web layer. It requires the app to declare AND hold
     *     {@code ACCESS_FINE_LOCATION} (or {@code ACCESS_COARSE_LOCATION}) and
     *     the device's system location setting to be ON. If either is missing,
     *     the request is denied immediately and this handler is never called --
     *     the page sees {@code PERMISSION_DENIED} (code 1). The SDK deliberately
     *     does not declare the location permission for you; a library must not
     *     force it on every consumer.
     *     <p>Once the app DOES hold it and location is on, geolocation reaches
     *     this handler normally, with {@link #PERMISSION_GEOLOCATION}. Measured
     *     on device, same OSR browser, only the Android permission changed:
     *     revoked gives {@code handler=no, page=err1}; granted gives
     *     {@code handler=yes(256), page=ok}.
     *     <p>In OSR specifically, "the app can be prompted for it" is not an
     *     option: that needs a native window and an off-screen browser has
     *     none, so request the Android permission from your Activity before the
     *     page asks.</li>
     * <li><b>Clipboard read</b> requires the document to be FOCUSED, which Blink
     *     checks before any permission check. An off-screen (OSR) browser has no
     *     view and is never focused unless you call {@link #setFocus(boolean)},
     *     so {@code navigator.clipboard.readText()} rejects with
     *     {@code NotAllowedError: Document is not focused.} with this handler
     *     never consulted. Call {@code setFocus(true)} first.</li>
     * <li><b>Any permission, if the page was loaded with {@link #loadHtml}.</b>
     *     That produces a {@code data:} URL, whose document has an OPAQUE
     *     origin, and the web platform denies every permission on an opaque
     *     origin without reaching any permission context -- so nothing is
     *     prompted and this handler is never called. It is not specific to
     *     this engine; the same page in any browser behaves the same way. Load
     *     from a real origin instead: a normal URL, or
     *     {@link #loadHtmlWithBaseUrl(String, String)}, which gives the
     *     document the base URL's origin.</li>
     * </ul>
     */
    public interface PermissionHandler {
        void onPermissionRequest(String origin, int permissions, PermissionCallback callback);

        /**
         * An in-flight permission prompt was auto-dismissed (e.g. the page
         * navigated away). Tear down any prompt UI you showed. {@code result} is
         * Chromium's {@code cef_permission_request_result_t}.
         */
        default void onDismissPermissionPrompt(long promptId, int result) {}
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
            if (ms != null) ms.suspend(org.chromium.media_session.mojom.MediaSession.SuspendType.UI);
        }
    }

    /** Resumes media suspended by {@link #suspendMedia()} (resumes where it left off). */
    public void resumeMedia() {
        org.chromium.content_public.browser.WebContents wc = webContents();
        if (wc != null) {
            org.chromium.content_public.browser.MediaSession ms =
                    org.chromium.content_public.browser.MediaSession.fromWebContents(wc);
            if (ms != null) ms.resume(org.chromium.media_session.mojom.MediaSession.SuspendType.UI);
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

    // FEATURE-062 (issue #9): OSR virtual keyboard. Called from native
    // (SdkRenderHandler::OnVirtualKeyboardRequested) when the focused editable
    // node changes. Re-posted to the main thread and forwarded to the render
    // handler so the hosting View can drive InputMethodManager.
    private void onVirtualKeyboardRequested(int inputMode) {
        final CefriumRenderHandler handler = mRenderHandler;
        if (handler == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> handler.onVirtualKeyboardRequested(inputMode));
    }

    // FEATURE-062 follow-up (issue #9): focused editable node's value +
    // placeholder + type, from the renderer. Delivered on the main thread.
    private void onFocusedNodeChanged(boolean editable, String value,
                                      String placeholder, String type) {
        final CefriumRenderHandler handler = mRenderHandler;
        if (handler == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> handler.onFocusedNodeChanged(
                        editable, value, placeholder, type));
    }

    // ── OSR text-selection quick menu (FEATURE-066) ──

    /** Quick-menu edit-state flag: Cut is available (matches CEF QM_EDITFLAG_CAN_CUT). */
    public static final int QM_CAN_CUT = 1 << 1;
    /** Quick-menu edit-state flag: Copy is available. */
    public static final int QM_CAN_COPY = 1 << 2;
    /** Quick-menu edit-state flag: Paste is available. */
    public static final int QM_CAN_PASTE = 1 << 3;

    /**
     * Executes the OSR text-selection quick-menu choice. CEF performs the actual
     * cut/copy/paste; call exactly one of these once, from
     * {@link CefriumRenderHandler#onQuickMenu}.
     */
    public final class QuickMenuCallback {
        private boolean mDone;
        private void send(int command) {
            if (mDone) return;
            mDone = true;
            if (mNativePtr != 0) nativeQuickMenuCommand(mNativePtr, command);
        }
        public void cut() { send(QM_CAN_CUT); }
        public void copy() { send(QM_CAN_COPY); }
        public void paste() { send(QM_CAN_PASTE); }
        /** Dismiss the menu without acting. */
        public void cancel() { send(0); }
    }

    // Called from native (SdkContextMenuHandler::RunQuickMenu) when the OSR
    // touch-selection quick menu should be shown. Delivered on the main thread.
    private void onQuickMenu(int x, int y, int width, int height, int editFlags) {
        final CefriumRenderHandler handler = mRenderHandler;
        final QuickMenuCallback cb = new QuickMenuCallback();
        if (handler == null) { cb.cancel(); return; }
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> handler.onQuickMenu(x, y, width, height, editFlags, cb));
    }

    // Called from native (SdkRenderHandler::OnTouchHandleStateChanged, OSR).
    private void onTouchHandleStateChanged(int orientation, int x, int y,
                                           boolean enabled) {
        final CefriumRenderHandler handler = mRenderHandler;
        if (handler == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> handler.onTouchHandleStateChanged(
                        orientation, x, y, enabled));
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

    // ----- OSR IME (FEATURE-062, issue #9) ------------------------------------
    // Feed an Android InputConnection's composing/committed text into CEF's
    // off-screen IME path. Mirrors CefBrowserHost::Ime*. Use from a custom
    // InputConnection on the View that hosts the OSR frames; show/hide the soft
    // keyboard from CefriumRenderHandler#onVirtualKeyboardRequested.

    /**
     * Set (or update) the current IME composition. Maps to an
     * {@code InputConnection.setComposingText} call.
     *
     * @param text     The full composing text (underlined/marked in the page).
     * @param selStart Selection/caret start within {@code text}.
     * @param selEnd   Selection/caret end within {@code text}.
     */
    public void imeSetComposition(String text, int selStart, int selEnd) {
        if (mNativePtr != 0) {
            nativeImeSetComposition(mNativePtr, text == null ? "" : text,
                    selStart, selEnd);
        }
    }

    /**
     * Commit text, ending any active composition. Maps to an
     * {@code InputConnection.commitText} call.
     *
     * @param text              The text to commit.
     * @param relativeCursorPos New caret position relative to the inserted
     *                          text ({@code 1} places the caret right after it).
     */
    public void imeCommitText(String text, int relativeCursorPos) {
        if (mNativePtr != 0) {
            nativeImeCommitText(mNativePtr, text == null ? "" : text,
                    relativeCursorPos);
        }
    }

    /**
     * Complete the current composition, keeping the composed text. Maps to
     * {@code InputConnection.finishComposingText}.
     *
     * @param keepSelection Whether to keep the current selection after
     *                      finishing.
     */
    public void imeFinishComposingText(boolean keepSelection) {
        if (mNativePtr != 0) {
            nativeImeFinishComposingText(mNativePtr, keepSelection);
        }
    }

    /**
     * Cancel the current composition, discarding the composed text.
     */
    public void imeCancelComposition() {
        if (mNativePtr != 0) {
            nativeImeCancelComposition(mNativePtr);
        }
    }

    // Text input modes reported by {@link CefriumRenderHandler#onVirtualKeyboardRequested}.
    // Values match CEF's cef_text_input_mode_t (and HTML inputmode). NOTE: the
    // default text field is DEFAULT (0), NOT NONE -- hide the keyboard only for
    // TEXT_INPUT_MODE_NONE (1); every other value means a field is focused and
    // the keyboard should be shown (the value also picks the keyboard flavour).
    /** Default input mode (a normal focused field): show the keyboard. */
    public static final int TEXT_INPUT_MODE_DEFAULT = 0;
    /** No virtual keyboard (nothing focused, or inputmode="none"): hide it. */
    public static final int TEXT_INPUT_MODE_NONE = 1;
    /** Plain text keyboard. */
    public static final int TEXT_INPUT_MODE_TEXT = 2;
    /** Telephone keypad. */
    public static final int TEXT_INPUT_MODE_TEL = 3;
    /** URL keyboard. */
    public static final int TEXT_INPUT_MODE_URL = 4;
    /** Email keyboard. */
    public static final int TEXT_INPUT_MODE_EMAIL = 5;
    /** Numeric keyboard. */
    public static final int TEXT_INPUT_MODE_NUMERIC = 6;
    /** Decimal keyboard. */
    public static final int TEXT_INPUT_MODE_DECIMAL = 7;
    /** Search keyboard. */
    public static final int TEXT_INPUT_MODE_SEARCH = 8;

    /** Key event type: raw key down (matches CEF {@code KEYEVENT_RAWKEYDOWN}). */
    public static final int KEY_RAW_DOWN = 0;
    /** Key event type: key down (matches CEF {@code KEYEVENT_KEYDOWN}). */
    public static final int KEY_DOWN = 1;
    /** Key event type: key up (matches CEF {@code KEYEVENT_KEYUP}). */
    public static final int KEY_UP = 2;
    /** Key event type: character (matches CEF {@code KEYEVENT_CHAR}). */
    public static final int KEY_CHAR = 3;

    /**
     * Send a key event to the browser (OSR). Needed for backspace, enter,
     * arrows and any keystroke not produced through composition. Mirrors
     * {@code CefBrowserHost::SendKeyEvent}.
     *
     * <p>A typical character keystroke is three events: {@link #KEY_RAW_DOWN},
     * {@link #KEY_CHAR} (with {@code charCode} set), then {@link #KEY_UP}.
     *
     * @param type           One of {@link #KEY_RAW_DOWN}, {@link #KEY_DOWN},
     *                       {@link #KEY_UP}, {@link #KEY_CHAR}.
     * @param modifiers      CEF event flags (modifier bits), or 0.
     * @param windowsKeyCode Windows virtual-key code (what Blink expects).
     * @param nativeKeyCode  Raw Android key code, or 0.
     * @param character      Produced character (for {@link #KEY_CHAR}), or 0.
     */
    public void sendKeyEvent(int type, int modifiers, int windowsKeyCode,
                             int nativeKeyCode, char character) {
        if (mNativePtr != 0) {
            nativeSendKeyEvent(mNativePtr, type, modifiers, windowsKeyCode,
                    nativeKeyCode, character);
        }
    }

    /**
     * Sends an Android {@link android.view.KeyEvent} to the browser (OSR).
     *
     * <p><b>Prefer this over the five-argument {@code sendKeyEvent}.</b> That one
     * takes a <i>Windows</i> virtual-key code, because that is what Blink derives
     * DOM {@code key} and {@code code} from -- so passing an Android key code, or
     * 0, produces {@code keydown}/{@code keyup} events whose {@code key} and
     * {@code code} are empty strings and whose {@code keyCode} is 0. This overload
     * does the Android-to-Windows mapping with Chromium's own conversion table,
     * so it cannot drift from the platform.
     *
     * <p>It also emits the right CEF sequence for you. CEF expects a printable
     * keystroke as {@code RAWKEYDOWN} then {@code CHAR}, and a release as
     * {@code KEYUP}; sending only down/up gives a page that sees the event but
     * receives no text.
     *
     * <pre>{@code
     * // In your View, or wherever you receive key input:
     * @Override public boolean onKeyDown(int code, KeyEvent ev) {
     *     browser.sendKeyEvent(ev);   // RAWKEYDOWN (+ CHAR if printable)
     *     return true;
     * }
     * @Override public boolean onKeyUp(int code, KeyEvent ev) {
     *     browser.sendKeyEvent(ev);   // KEYUP
     *     return true;
     * }
     * }</pre>
     *
     * <p>Note this is for OSR input injection. An on-screen browser receives key
     * input through its own view, and composition (soft-keyboard) text should go
     * through the IME methods instead -- see {@link #imeCommitText(String, int)}.
     *
     * @param event the Android key event; ignored if {@code null}.
     */
    public void sendKeyEvent(android.view.KeyEvent event) {
        if (mNativePtr == 0 || event == null) return;
        final int keyCode = event.getKeyCode();
        final int meta = event.getMetaState();
        switch (event.getAction()) {
            case android.view.KeyEvent.ACTION_DOWN: {
                // getUnicodeChar() applies the meta state, so shifted and dead-key
                // combinations come out right. It goes on the RAWKEYDOWN too, not
                // only the CHAR: Blink derives the DOM key from it, and without it
                // the page sees key "Unidentified".
                final int ch = event.getUnicodeChar(meta);
                nativeSendKeyEventAndroid(mNativePtr, KEY_RAW_DOWN, keyCode, meta, ch);
                // Only a printable key produces text.
                if (ch != 0) {
                    nativeSendKeyEventAndroid(mNativePtr, KEY_CHAR, keyCode, meta, ch);
                }
                break;
            }
            case android.view.KeyEvent.ACTION_UP:
                nativeSendKeyEventAndroid(mNativePtr, KEY_UP, keyCode, meta,
                        event.getUnicodeChar(meta));
                break;
            case android.view.KeyEvent.ACTION_MULTIPLE: {
                // A batch of characters with no single key behind them (e.g. text
                // pasted by an input method). There is no key to press, so send
                // CHAR only.
                final String chars = event.getCharacters();
                if (chars != null) {
                    for (int i = 0; i < chars.length(); i++) {
                        nativeSendKeyEventAndroid(mNativePtr, KEY_CHAR, 0, meta,
                                chars.charAt(i));
                    }
                }
                break;
            }
            default:
                break;
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

    // ----- Touch automation ---------------------------------------------------
    // Programmatic single-finger touch injection: tap, plus low-level down/move/up
    // for drags and swipes -- mirroring the mouse API. Coordinates are browser-
    // viewport pixels (upper-left origin). These drive the full gesture pipeline:
    // a tap becomes a click, a down-move-up becomes a scroll/fling. In windowed
    // (createWithSurface) mode the events go through the content view's
    // EventForwarder (same path as a real finger); in windowless (OSR) mode they
    // go through the CEF OSR touch path. Call on the UI thread, after the page has
    // started loading.

    /** Press a finger down at (x, y). Pair with {@link #sendTouchUp} (with
     * {@link #sendTouchMove} in between) for drags/swipes, or use {@link #sendTap}. */
    public void sendTouchDown(float x, float y) { injectTouch(0, x, y); }

    /** Move the pressed finger to (x, y). */
    public void sendTouchMove(float x, float y) { injectTouch(2, x, y); }

    /** Lift the finger at (x, y). */
    public void sendTouchUp(float x, float y) { injectTouch(1, x, y); }

    /** Tap at (x, y): a finger down immediately followed by up. Synthesizes a
     * click on the element at that point (the touch-automation equivalent of
     * {@link #sendLeftClick}). */
    public void sendTap(float x, float y) {
        sendTouchDown(x, y);
        sendTouchUp(x, y);
    }

    /**
     * Swipe from (x1, y1) to (x2, y2) as a down + {@code steps} moves + up --
     * drives scrolling/flinging. {@code steps} controls smoothness (>= 1).
     */
    public void sendSwipe(float x1, float y1, float x2, float y2, int steps) {
        if (steps < 1) steps = 1;
        sendTouchDown(x1, y1);
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            sendTouchMove(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t);
        }
        sendTouchUp(x2, y2);
    }

    // action: 0=down, 1=up, 2=move. Windowed -> synthetic MotionEvent through the
    // content EventForwarder (full gesture pipeline); OSR -> native CEF touch path.
    private void injectTouch(int action, float x, float y) {
        if (mSurfaceContainer != null) {
            org.chromium.content_public.browser.WebContents wc = webContents();
            if (wc == null) return;
            org.chromium.ui.base.EventForwarder ef = wc.getEventForwarder();
            if (ef == null) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (action == 0) mTouchDownTime = now;
            int androidAction = (action == 0) ? android.view.MotionEvent.ACTION_DOWN
                    : (action == 1) ? android.view.MotionEvent.ACTION_UP
                    : android.view.MotionEvent.ACTION_MOVE;
            android.view.MotionEvent ev = android.view.MotionEvent.obtain(
                    mTouchDownTime, now, androidAction, x, y, 0);
            ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try {
                ef.onTouchEvent(ev);
            } finally {
                ev.recycle();
            }
        } else if (mNativePtr != 0) {
            nativeSendTouchEvent(mNativePtr, action, x, y, 0);
        }
    }

    // ----- Mouse events -------------------------------------------------------
    // Programmatic mouse injection: position the pointer at coordinates, click,
    // right-click, plus the rest of the desktop CefBrowserHost mouse surface
    // (hover/leave, press/release, wheel, buttons, modifiers, multi-click).
    // Exposed the same way as sendTouchEvent above. Coordinates are browser-
    // viewport pixels (upper-left origin). Useful for automation/testing, a
    // connected pointer, and hover (which touch cannot express). Call on the UI
    // thread, after the page has started loading.

    /** Mouse button for the click APIs (matches CEF's MBT_LEFT/MIDDLE/RIGHT). */
    public static final int MOUSE_BUTTON_LEFT = 0;
    public static final int MOUSE_BUTTON_MIDDLE = 1;
    public static final int MOUSE_BUTTON_RIGHT = 2;

    /** Modifier bit flags for mouse events; OR them together (0 = none). */
    public static final int MOUSE_MODIFIER_SHIFT = 1 << 1;
    public static final int MOUSE_MODIFIER_CTRL = 1 << 2;
    public static final int MOUSE_MODIFIER_ALT = 1 << 3;

    /**
     * Move the mouse pointer to (x, y). Drives {@code mousemove} and {@code :hover}
     * -- state that touch input cannot express.
     *
     * @param x X coordinate in browser-viewport pixels.
     * @param y Y coordinate in browser-viewport pixels.
     */
    public void sendMouseMove(int x, int y) {
        sendMouseMove(x, y, 0);
    }

    /** Move the pointer to (x, y) with modifier keys held (see MOUSE_MODIFIER_*). */
    public void sendMouseMove(int x, int y, int modifiers) {
        if (mNativePtr != 0) {
            nativeSendMouseMoveEvent(mNativePtr, x, y, modifiers, false);
        }
    }

    /** Signal the pointer leaving the view at (x, y) (clears {@code :hover}). */
    public void sendMouseLeave(int x, int y) {
        if (mNativePtr != 0) {
            nativeSendMouseMoveEvent(mNativePtr, x, y, 0, true);
        }
    }

    /**
     * Click at (x, y) with the given button (down + up).
     *
     * @param button one of MOUSE_BUTTON_LEFT / MOUSE_BUTTON_MIDDLE / MOUSE_BUTTON_RIGHT.
     */
    public void sendMouseClick(int x, int y, int button) {
        sendMouseClick(x, y, button, 1, 0);
    }

    /**
     * Click at (x, y) with a button, click count, and modifiers (down + up).
     *
     * @param clickCount 1 = single, 2 = double, 3 = triple.
     * @param modifiers OR of MOUSE_MODIFIER_* (0 = none).
     */
    public void sendMouseClick(int x, int y, int button, int clickCount, int modifiers) {
        if (mNativePtr == 0) return;
        nativeSendMouseClickEvent(mNativePtr, x, y, modifiers, button, false, clickCount);
        nativeSendMouseClickEvent(mNativePtr, x, y, modifiers, button, true, clickCount);
    }

    /** Left click at (x, y). */
    public void sendLeftClick(int x, int y) {
        sendMouseClick(x, y, MOUSE_BUTTON_LEFT, 1, 0);
    }

    /**
     * Right click at (x, y). Also opens the context menu, routed through
     * {@code CefContextMenuHandler} (see setOnContextMenuListener).
     */
    public void sendRightClick(int x, int y) {
        sendMouseClick(x, y, MOUSE_BUTTON_RIGHT, 1, 0);
    }

    /** Double left click at (x, y). */
    public void sendDoubleClick(int x, int y) {
        sendMouseClick(x, y, MOUSE_BUTTON_LEFT, 2, 0);
    }

    /**
     * Press a mouse button at (x, y) without releasing it. Pair with
     * {@link #sendMouseUp} (with intervening {@link #sendMouseMove} calls) to
     * synthesize a drag.
     */
    public void sendMouseDown(int x, int y, int button, int modifiers) {
        if (mNativePtr != 0) {
            nativeSendMouseClickEvent(mNativePtr, x, y, modifiers, button, false, 1);
        }
    }

    /** Release a mouse button at (x, y). */
    public void sendMouseUp(int x, int y, int button, int modifiers) {
        if (mNativePtr != 0) {
            nativeSendMouseClickEvent(mNativePtr, x, y, modifiers, button, true, 1);
        }
    }

    /**
     * Scroll the mouse wheel at (x, y). {@code deltaX}/{@code deltaY} are the
     * scroll deltas in pixels (a typical wheel notch is ~120).
     */
    public void sendMouseWheel(int x, int y, int deltaX, int deltaY) {
        sendMouseWheel(x, y, deltaX, deltaY, 0);
    }

    /** Scroll the mouse wheel at (x, y) with modifier keys held. */
    public void sendMouseWheel(int x, int y, int deltaX, int deltaY, int modifiers) {
        if (mNativePtr != 0) {
            nativeSendMouseWheelEvent(mNativePtr, x, y, modifiers, deltaX, deltaY);
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
        // Before the native OnAfterCreated can run (it is posted to this same
        // looper), so the WebContents connects without a listener being set.
        nativeBindJavaBrowser(browser.mNativePtr, browser);

        // Issue #10: forward window focus to CefBrowserHost::SetFocus. Without
        // this nothing ever focused the browser and the page's document stayed
        // unfocused for its whole life, which silently broke every API Blink
        // gates on document focus (the async clipboard rejects with "Document is
        // not focused." before any permission check even runs). Wired here,
        // after mNativePtr exists, and seeded from the current window state
        // because the view is attached before the native browser is created.
        browser.mSurfaceContainer.setFocusSink(browser::setFocus);

        // Install the native display callback unconditionally.
        //
        // onUrlChanged() is not merely a convenience listener: it is where the
        // PaymentRequest and WebAuthn interface factories are registered, and the
        // timing matters (they must be added after Chrome's registrars so that
        // last-writer-wins puts ours on top). Until now the native callback was
        // installed only as a side effect of the app setting SOME handler, so an
        // app that set none -- a bare CefriumWebView(url = ...), for instance --
        // silently lost both features: PaymentRequest.show() rejected with
        // "Unable to find Chrome activity" and navigator.credentials never bound.
        // Found on device 2026-10-05 with the kiosk checkout sample.
        //
        // Registration must not depend on an unrelated callback a consumer may
        // never pass.
        if (browser.mNativePtr != 0) {
            nativeSetDisplayCallback(browser.mNativePtr, browser);
        }

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

    /**
     * The native browser and its WebContents exist. Surface mode connects the
     * WebContents to its WindowAndroid here.
     *
     * This used to happen 2000 ms after the first loadUrl, a fixed delay. Until
     * the connection no renderer of this browser receives BeginFrames, so a page
     * could not paint, could not leave paint holding, and Blink suppressed every
     * non-move input event: injected clicks and taps in that window were lost.
     * Measured on a W90 with the app zygote, where renderers start fast enough
     * to wait out most of the delay. Called by native via JNI -- keep it.
     */
    private void onNativeBrowserCreated() {
        if (mSurfaceContainer == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(this::connectWebContentsInternal);
    }

    // Internal: connect WebContents to rendering pipeline.
    // Called once the native browser exists (onNativeBrowserCreated).
    // See getWebContents(): external adopters reach this via same-package access;
    // changing the signature needs a CHANGELOG-CEFRIUM.md note.
    @androidx.annotation.RestrictTo(androidx.annotation.RestrictTo.Scope.LIBRARY)
    void connectWebContentsInternal() {
        // This runs from a main-looper post (see onNativeBrowserCreated). If the
        // browser was closed in the meantime, mNativePtr still points at the native
        // SdkBrowserData until the async OnBeforeClose lands -- dereferencing it
        // here is a use-after-free. mClosed is set synchronously in close() on
        // this same (main) looper, so it reliably short-circuits the stale callback.
        if (mClosed || mNativePtr == 0 || mActivity == null) return;
        org.chromium.content_public.browser.WebContents wc = nativeGetWebContents(mNativePtr);
        if (wc == null) return;
        if (mWebContentsConnected) return;
        mWebContentsConnected = true;

        // Pull-to-refresh: the overscroll handler is bound here, exactly once,
        // and reads the flag on every gesture (see bindOverscrollRefreshHandler).
        // The connect now runs before the first navigation, so binding only when
        // the flag was already set would lose a later setPullToRefreshEnabled.
        bindOverscrollRefreshHandler(wc);

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

        // FEATURE-065: install the text-selection floating toolbar (Select All /
        // Copy / Paste / Share / Web Search). Content gates the toolbar on an
        // installed ActionMode callback (isActionModeSupported); without one it
        // never shows. Chrome/WebView both install one -- content only provides
        // the reusable helper.
        try {
            org.chromium.content_public.browser.SelectionPopupController
                    .fromWebContents(wc)
                    .setActionModeCallback(new CefriumActionModeCallback(this, wc));
            // Smart Text selection: use the platform TextClassifier so the
            // selection toolbar offers entity actions (phone->call, address->map,
            // URL->open), matching WebView. getTextClassifier() falls back to the
            // system one, but set it explicitly like WebView does.
            if (mActivity != null
                    && android.os.Build.VERSION.SDK_INT
                            >= android.os.Build.VERSION_CODES.O) {
                android.view.textclassifier.TextClassificationManager tcm =
                        (android.view.textclassifier.TextClassificationManager)
                                mActivity.getSystemService(
                                        android.content.Context.TEXT_CLASSIFICATION_SERVICE);
                if (tcm != null) {
                    org.chromium.content_public.browser.SelectionPopupController
                            .fromWebContents(wc)
                            .setTextClassifier(tcm.getTextClassifier());
                }
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "setActionModeCallback failed: " + t);
        }

        // FEATURE-059: install Chromium's external_intents InterceptNavigationDelegate
        // so unknown/app URL schemes (baiduboxapp://, weixin://, intent://, ...) are
        // handed off to Android via an Intent instead of loaded as web pages. The
        // NavigationThrottle is added by the inherited chrome base once a delegate is
        // associated with the WebContents; the ctor self-associates via native JNI.
        if (mNavDelegate == null) {
            mNavDelegate = new org.chromium.components.external_intents
                    .InterceptNavigationDelegateImpl(
                    new CefInterceptNavigationDelegateClient(
                            this, wc, mWindowAndroid, mIncognito));
        }

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
        } catch (Throwable e) {
            // Autofill not available — non-critical.
        }

        // Set WebAuthn mode per-WebContents to CHROME_3PP_ENABLED. This enables
        // the CredMan-only path (no Play Services Fido2 privileged API) so
        // third-party passkey providers (Proton Pass, 1Password...) are offered,
        // and its security checks pass (unlike BROWSER mode). MUST be independent
        // of the Autofill try/catch above: AutofillProvider construction throws on
        // some OEM ROMs (MIUI/Xiaomi, some tablets), and sharing a catch silently
        // left WebAuthn in the global BROWSER mode -> CredMan never invoked.
        try {
            org.chromium.components.webauthn.WebauthnModeProvider.getInstance()
                    .setWebauthnModeForWebContents(wc,
                            org.chromium.components.webauthn.WebauthnMode.CHROME_3PP_ENABLED);
        } catch (Throwable e) {
            // WebAuthn classes unavailable (pre-Android-14 build) — non-critical.
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
     * Package-private -- internal use only, not part of the public API.
     *
     * NOTE: an external adopter (TE2, termux-extensions-2) reaches this and
     * connectWebContentsInternal() via a same-package accessor to install a
     * custom text-selection ActionMode. There is no supported public API for
     * that yet. If you change either signature, add a CHANGELOG-CEFRIUM.md note
     * -- external code depends on the current shape with no deprecation window.
     */
    @androidx.annotation.RestrictTo(androidx.annotation.RestrictTo.Scope.LIBRARY)
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

    // ---- FEATURE-060: controllable download API (mirrors CefDownloadHandler) ----

    /**
     * Richer download handler that can allow/deny a download before it starts
     * and cancel/pause/resume one in progress. Prefer this over
     * {@link OnDownloadListener} (which is detect-only and kept for
     * compatibility). Mirrors CEF's {@code CefDownloadHandler}.
     *
     * <p>Callbacks arrive on the browser's UI thread.</p>
     */
    public interface CefriumDownloadHandler {
        /**
         * A download is about to start. Decide asynchronously whether to allow
         * it -- call {@code callback.allow(path)} to download (pass {@code null}
         * to use the suggested path) or {@code callback.cancel()} to block it
         * (e.g. after an accidental click). The download does not begin until
         * one of the two is called; you may show a dialog first.
         */
        void onBeforeDownload(DownloadItem item, BeforeDownloadCallback callback);

        /**
         * Progress update for an in-flight download. Use {@link DownloadItem}'s
         * {@code cancel()} / {@code pause()} / {@code resume()} to control it.
         */
        void onDownloadUpdated(DownloadItem item);
    }

    /** Async decision for {@link CefriumDownloadHandler#onBeforeDownload}. */
    public interface BeforeDownloadCallback {
        /** Start the download. {@code targetPath} null -> the suggested path. */
        void allow(String targetPath);
        /** Block the download (nothing is written). */
        void cancel();
    }

    /**
     * Snapshot of a download plus in-progress controls. Mirrors the getters of
     * CEF's {@code CefDownloadItem}. The control methods are no-ops once the
     * download has completed or been canceled.
     */
    public static final class DownloadItem {
        private final CefriumBrowser mBrowser;
        private final int mId;
        private final String mUrl, mSuggestedName, mMimeType, mFullPath;
        private final long mTotalBytes, mReceivedBytes, mCurrentSpeed;
        private final int mPercent, mInterruptReason;
        private final boolean mComplete, mCanceled, mPaused;

        DownloadItem(CefriumBrowser browser, int id, String url,
                     String suggestedName, String mimeType, String fullPath,
                     long totalBytes, long receivedBytes, long currentSpeed,
                     int percent, int interruptReason, boolean complete,
                     boolean canceled, boolean paused) {
            mBrowser = browser; mId = id; mUrl = url;
            mSuggestedName = suggestedName; mMimeType = mimeType;
            mFullPath = fullPath; mTotalBytes = totalBytes;
            mReceivedBytes = receivedBytes; mCurrentSpeed = currentSpeed;
            mPercent = percent; mInterruptReason = interruptReason;
            mComplete = complete; mCanceled = canceled; mPaused = paused;
        }

        /** Unique id of this download (stable for its lifetime). */
        public int getId() { return mId; }
        public String getUrl() { return mUrl; }
        public String getSuggestedName() { return mSuggestedName; }
        public String getMimeType() { return mMimeType; }
        /** Target path on disk (may be empty before the download starts). */
        public String getFullPath() { return mFullPath; }
        public long getTotalBytes() { return mTotalBytes; }
        public long getReceivedBytes() { return mReceivedBytes; }
        /** Current speed in bytes/second. */
        public long getCurrentSpeed() { return mCurrentSpeed; }
        public int getPercentComplete() { return mPercent; }
        public boolean isComplete() { return mComplete; }
        public boolean isCanceled() { return mCanceled; }
        public boolean isPaused() { return mPaused; }
        /** CEF {@code cef_download_interrupt_reason_t} (0 = none). */
        public int getInterruptReason() { return mInterruptReason; }

        /** Cancel this download. */
        public void cancel() { mBrowser.controlDownload(mId, 0); }
        /** Pause this download. */
        public void pause() { mBrowser.controlDownload(mId, 1); }
        /** Resume a paused download. */
        public void resume() { mBrowser.controlDownload(mId, 2); }
    }

    private CefriumDownloadHandler mDownloadHandler;

    /**
     * Set the controllable download handler (allow/deny + cancel/pause/resume).
     * Pass {@code null} to remove it (downloads then auto-start, as with the
     * legacy {@link OnDownloadListener}).
     */
    public void setDownloadHandler(CefriumDownloadHandler handler) {
        mDownloadHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    void controlDownload(int id, int action) {
        if (mNativePtr != 0) nativeDownloadControl(mNativePtr, id, action);
    }

    /**
     * Cancel an in-progress download by its {@link DownloadItem#getId() id}.
     * Safe to call from any thread; a no-op if the download is already finished.
     * Useful to wire a "Cancel" action on a download notification without holding
     * the {@link DownloadItem}.
     */
    public void cancelDownload(int id) { controlDownload(id, 0); }

    /** Pause an in-progress download by its id. See {@link #cancelDownload}. */
    public void pauseDownload(int id) { controlDownload(id, 1); }

    /** Resume a paused download by its id. See {@link #cancelDownload}. */
    public void resumeDownload(int id) { controlDownload(id, 2); }

    // Called from native SdkDownloadHandler::OnBeforeDownload (UI thread).
    @SuppressWarnings("unused")
    private void onBeforeDownload(int id, String url, String suggestedName,
                                  String mimeType, long totalBytes,
                                  String defaultPath) {
        if (mDownloadHandler != null) {
            DownloadItem item = new DownloadItem(this, id, url, suggestedName,
                    mimeType, defaultPath, totalBytes, 0, 0, 0, 0, false, false,
                    false);
            mDownloadHandler.onBeforeDownload(item, new BeforeDownloadCallback() {
                @Override public void allow(String targetPath) {
                    if (mNativePtr != 0) {
                        nativeRespondBeforeDownload(mNativePtr, id, true,
                                targetPath != null ? targetPath : defaultPath);
                    }
                }
                @Override public void cancel() {
                    if (mNativePtr != 0) {
                        nativeRespondBeforeDownload(mNativePtr, id, false, null);
                    }
                }
            });
        } else {
            // Legacy listener (or none): auto-start to the suggested path.
            if (mNativePtr != 0) {
                nativeRespondBeforeDownload(mNativePtr, id, true, defaultPath);
            }
        }
    }

    // Called from native SdkDownloadHandler::OnDownloadUpdated (UI thread).
    @SuppressWarnings("unused")
    private void onDownloadUpdatedRich(int id, String url, String suggestedName,
                                       String mimeType, String fullPath,
                                       long totalBytes, long receivedBytes,
                                       int percent, long currentSpeed,
                                       boolean complete, boolean canceled,
                                       boolean paused, int interruptReason) {
        if (mDownloadHandler != null) {
            mDownloadHandler.onDownloadUpdated(new DownloadItem(this, id, url,
                    suggestedName, mimeType, fullPath, totalBytes, receivedBytes,
                    currentSpeed, percent, interruptReason, complete, canceled,
                    paused));
        }
    }

    // ===== FEATURE-063: full CEF-API parity handlers =====

    private CefriumDisplayHandler mDisplayHandler;
    private CefriumLoadHandler mLoadHandler;
    private CefriumRequestHandler mRequestHandler;
    private CefriumKeyboardHandler mKeyboardHandler;
    private CefriumFocusHandler mFocusHandler;
    private CefriumDragHandler mDragHandler;
    private CefriumAudioHandler mAudioHandler;
    private CefriumFrameHandler mFrameHandler;
    private CefriumCommandHandler mCommandHandler;
    private CefriumDialogHandler mDialogHandler;

    /** Sets the display handler (favicon, progress, console, status, media). */
    public void setDisplayHandler(CefriumDisplayHandler handler) {
        mDisplayHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }
    /** Sets the per-frame load handler (load start / error). */
    public void setLoadHandler(CefriumLoadHandler handler) {
        mLoadHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }
    /** Sets the request handler (open-in-tab, hung renderer, client cert, resource events). */
    /**
     * Enable the opt-in response-body tap ({@link CefriumRequestHandler#onResponseData}).
     * Off by default; enabling it copies each response body chunk across JNI
     * (a hot path). Requires a request handler set to receive the data.
     */
    public void setResponseTapEnabled(boolean enabled) {
        if (mNativePtr != 0) nativeSetResponseTapEnabled(mNativePtr, enabled);
    }

    /**
     * Enable the opt-in per-cookie access filter
     * ({@link CefriumRequestHandler#onCanSendCookie}/{@code onCanSaveCookie}).
     * Off by default; enabling it calls the handler per cookie (a hot path).
     */
    public void setCookieFilterEnabled(boolean enabled) {
        if (mNativePtr != 0) nativeSetCookieFilterEnabled(mNativePtr, enabled);
    }

    public void setRequestHandler(CefriumRequestHandler handler) {
        mRequestHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }
    /** Sets the keyboard handler (intercept keys before the page). */
    public void setKeyboardHandler(CefriumKeyboardHandler handler) {
        mKeyboardHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }
    /** Sets the focus handler. */
    public void setFocusHandler(CefriumFocusHandler handler) {
        mFocusHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }
    /** Sets the drag handler. */
    public void setDragHandler(CefriumDragHandler handler) {
        mDragHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }
    /**
     * Sets the audio handler. OPT-IN and exclusive: a non-null handler REDIRECTS
     * the page's audio to it (it stops playing through the speakers); pass null
     * to disable and restore normal playback.
     */
    public void setAudioHandler(CefriumAudioHandler handler) {
        mAudioHandler = handler;
        if (mNativePtr != 0) {
            nativeSetDisplayCallback(mNativePtr, this);
            nativeSetAudioCaptureEnabled(mNativePtr, handler != null);
        }
    }
    /** Sets the frame lifecycle handler. */
    public void setFrameHandler(CefriumFrameHandler handler) {
        mFrameHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }
    /** Sets the Chrome command handler. */
    public void setCommandHandler(CefriumCommandHandler handler) {
        mCommandHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }
    /** Sets the file-chooser dialog handler ({@code <input type=file>}). */
    public void setDialogHandler(CefriumDialogHandler handler) {
        mDialogHandler = handler;
        if (mNativePtr != 0) nativeSetDisplayCallback(mNativePtr, this);
    }

    private void postMain(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    // ---- Display bridge (called from native, UI thread) ----
    private void onFaviconUrlChanged(String[] iconUrls) {
        CefriumDisplayHandler h = mDisplayHandler;
        if (h != null) h.onFaviconUrlChanged(iconUrls);
    }
    private void onLoadingProgress(double progress) {
        CefriumDisplayHandler h = mDisplayHandler;
        if (h != null) h.onLoadingProgress(progress);
    }
    private void onConsoleMessage(int level, String message, String source, int line) {
        CefriumDisplayHandler h = mDisplayHandler;
        if (h != null) h.onConsoleMessage(level, message, source, line);
    }
    private void onStatusMessage(String value) {
        CefriumDisplayHandler h = mDisplayHandler;
        if (h != null) h.onStatusMessage(value);
    }
    private void onMediaAccessChange(boolean hasVideo, boolean hasAudio) {
        CefriumDisplayHandler h = mDisplayHandler;
        if (h != null) h.onMediaAccessChange(hasVideo, hasAudio);
    }

    // ---- Load bridge ----
    private void onLoadStart(String url) {
        CefriumLoadHandler h = mLoadHandler;
        if (h != null) h.onLoadStart(url);
    }
    private void onLoadError(int errorCode, String errorText, String failedUrl) {
        CefriumLoadHandler h = mLoadHandler;
        if (h != null) h.onLoadError(errorCode, errorText, failedUrl);
    }

    // ---- Request bridge ----
    private void onOpenUrlFromTab(String url, int disposition, boolean userGesture) {
        CefriumRequestHandler h = mRequestHandler;
        if (h != null) h.onOpenUrlFromTab(url, disposition, userGesture);
    }
    private void onRenderUnresponsive() {
        CefriumRequestHandler h = mRequestHandler;
        if (h != null) h.onRenderUnresponsive();
    }
    private void onRenderResponsive() {
        CefriumRequestHandler h = mRequestHandler;
        if (h != null) h.onRenderResponsive();
    }
    private void onRenderViewReady() {
        CefriumRequestHandler h = mRequestHandler;
        if (h != null) h.onRenderViewReady();
    }
    private void onDocumentAvailable() {
        CefriumRequestHandler h = mRequestHandler;
        if (h != null) h.onDocumentAvailable();
    }
    private void onResourceRedirect(String oldUrl, String newUrl) {
        CefriumRequestHandler h = mRequestHandler;
        if (h != null) h.onResourceRedirect(oldUrl, newUrl);
    }
    private void onResourceResponse(String url, int statusCode) {
        CefriumRequestHandler h = mRequestHandler;
        if (h != null) h.onResourceResponse(url, statusCode);
    }
    private void onResourceLoadComplete(String url, int statusCode, long receivedBytes) {
        CefriumRequestHandler h = mRequestHandler;
        if (h != null) h.onResourceLoadComplete(url, statusCode, receivedBytes);
    }
    private void onResponseData(String url, java.nio.ByteBuffer chunk) {
        CefriumRequestHandler h = mRequestHandler;
        if (h != null) h.onResponseData(url, chunk);
    }
    private boolean onCanSendCookie(String url, String name, String value) {
        CefriumRequestHandler h = mRequestHandler;
        return h == null || h.onCanSendCookie(url, name, value);
    }
    private boolean onCanSaveCookie(String url, String name, String value) {
        CefriumRequestHandler h = mRequestHandler;
        return h == null || h.onCanSaveCookie(url, name, value);
    }
    private void onSelectClientCertificate(final long id, boolean isProxy, String host,
                                           int port, String[] subjectNames) {
        CefriumRequestHandler h = mRequestHandler;
        if (h == null) {
            if (mNativePtr != 0) nativeSelectClientCertificate(mNativePtr, id, -1);
            return;
        }
        final boolean[] answered = {false};
        CefriumRequestHandler.ClientCertificateCallback cb =
                new CefriumRequestHandler.ClientCertificateCallback() {
            @Override public void select(int index) { respond(index); }
            @Override public void cancel() { respond(-1); }
            private void respond(int index) {
                if (answered[0]) return;
                answered[0] = true;
                if (mNativePtr != 0) nativeSelectClientCertificate(mNativePtr, id, index);
            }
        };
        h.onSelectClientCertificate(isProxy, host, port, subjectNames, cb);
    }

    // ---- beforeunload (reuses the JS-dialog resolve path) ----
    private void onBeforeUnloadDialog(final long token, String message, boolean isReload) {
        JsDialogCallback cb = (success, userInput) -> {
            if (mNativePtr != 0) nativeJsDialogResult(mNativePtr, token, success, "");
        };
        JsDialogHandler h = mJsDialogHandler;
        if (h != null && h.onBeforeUnloadDialog(message, isReload, cb)) return;
        // No handler: allow the navigation (do not block).
        cb.continueDialog(true, "");
    }
    private void onDialogResetState() {
        JsDialogHandler h = mJsDialogHandler;
        if (h != null) h.onDialogResetState();
    }
    private void onDialogClosed() {
        JsDialogHandler h = mJsDialogHandler;
        if (h != null) h.onDialogClosed();
    }

    // ---- Permission auto-dismiss ----
    private void onDismissPermissionPrompt(long promptId, int result) {
        PermissionHandler h = mPermissionHandler;
        if (h != null) h.onDismissPermissionPrompt(promptId, result);
    }

    // ---- LifeSpan ----
    private void onDoClose() {
        // Notify via the render-process / listener path if the app cares; no-op
        // by default (CEF continues its default close sequence).
    }

    // ---- Keyboard (synchronous, returns handled) ----
    private boolean onPreKeyEvent(int type, int windowsKeyCode, int modifiers) {
        CefriumKeyboardHandler h = mKeyboardHandler;
        return h != null && h.onPreKeyEvent(type, windowsKeyCode, modifiers);
    }
    private boolean onKeyEvent(int type, int windowsKeyCode, int modifiers) {
        CefriumKeyboardHandler h = mKeyboardHandler;
        return h != null && h.onKeyEvent(type, windowsKeyCode, modifiers);
    }

    // ---- Focus ----
    private void onTakeFocus(boolean next) {
        CefriumFocusHandler h = mFocusHandler;
        if (h != null) h.onTakeFocus(next);
    }
    private boolean onSetFocus(int source) {
        CefriumFocusHandler h = mFocusHandler;
        return h != null && h.onSetFocus(source);
    }
    private void onGotFocus() {
        CefriumFocusHandler h = mFocusHandler;
        if (h != null) h.onGotFocus();
    }

    // ---- Drag ----
    private boolean onDragEnter(int mask) {
        CefriumDragHandler h = mDragHandler;
        return h != null && h.onDragEnter(mask);
    }
    private void onDraggableRegionsChanged(int[] regions) {
        CefriumDragHandler h = mDragHandler;
        if (h != null) h.onDraggableRegionsChanged(regions);
    }

    // ---- Audio ----
    private void onAudioStreamStarted(int channels, int sampleRate) {
        CefriumAudioHandler h = mAudioHandler;
        if (h != null) h.onAudioStreamStarted(channels, sampleRate);
    }
    private void onAudioStreamPacket(java.nio.ByteBuffer data, int frames, long pts) {
        CefriumAudioHandler h = mAudioHandler;
        if (h != null) h.onAudioStreamPacket(data, frames, pts);
    }
    private void onAudioStreamStopped() {
        CefriumAudioHandler h = mAudioHandler;
        if (h != null) h.onAudioStreamStopped();
    }
    private void onAudioStreamError(String message) {
        CefriumAudioHandler h = mAudioHandler;
        if (h != null) h.onAudioStreamError(message);
    }

    // ---- Frame ----
    private void onFrameCreated(String frameId, boolean isMain) {
        CefriumFrameHandler h = mFrameHandler;
        if (h != null) h.onFrameCreated(frameId, isMain);
    }
    private void onFrameDestroyed(String frameId, boolean isMain) {
        CefriumFrameHandler h = mFrameHandler;
        if (h != null) h.onFrameDestroyed(frameId, isMain);
    }
    private void onFrameAttached(String frameId, boolean isMain) {
        CefriumFrameHandler h = mFrameHandler;
        if (h != null) h.onFrameAttached(frameId, isMain);
    }
    private void onFrameDetached(String frameId, boolean isMain) {
        CefriumFrameHandler h = mFrameHandler;
        if (h != null) h.onFrameDetached(frameId, isMain);
    }
    private void onMainFrameChanged(String frameId, boolean isMain) {
        CefriumFrameHandler h = mFrameHandler;
        if (h != null) h.onMainFrameChanged(frameId, isMain);
    }

    // ---- Command (synchronous) ----
    private boolean onChromeCommand(int commandId, int disposition) {
        CefriumCommandHandler h = mCommandHandler;
        return h != null && h.onChromeCommand(commandId, disposition);
    }

    // ---- Context-menu extras ----
    private void onContextMenuDismissed() {}
    private void onQuickMenuCommand(int commandId) {}
    private void onQuickMenuDismissed() {}

    // ---- File chooser (async by id; delivered on main so it can start an Intent) ----
    private void onFileDialog(final long id, final int mode, final String title,
                              final String[] acceptFilters) {
        final CefriumDialogHandler h = mDialogHandler;
        if (h == null) {
            if (mNativePtr != 0) nativeFileDialogResult(mNativePtr, id, new String[0]);
            return;
        }
        final boolean[] answered = {false};
        final CefriumDialogHandler.FileDialogCallback cb =
                new CefriumDialogHandler.FileDialogCallback() {
            @Override public void select(String[] paths) {
                respond(paths == null ? new String[0] : paths);
            }
            @Override public void cancel() { respond(new String[0]); }
            private void respond(String[] paths) {
                if (answered[0]) return;
                answered[0] = true;
                if (mNativePtr != 0) nativeFileDialogResult(mNativePtr, id, paths);
            }
        };
        postMain(() -> h.onFileDialog(mode, title, acceptFilters, cb));
    }

    /** Delivers a file-chooser selection (empty/null cancels). See {@link CefriumDialogHandler}. */
    public void fileDialogResult(long id, String[] paths) {
        if (mNativePtr != 0) {
            nativeFileDialogResult(mNativePtr, id, paths == null ? new String[0] : paths);
        }
    }

    // ---- OSR render extras: forward to the render handler ----
    private void onPopupPaint(int x, int y, int width, int height,
                              java.nio.ByteBuffer pixels) {
        if (mRenderHandler != null) mRenderHandler.onPopupPaint(x, y, width, height, pixels);
    }
    private void onPopupShow(boolean show) {
        if (mRenderHandler != null) mRenderHandler.onPopupShow(show);
    }
    private void onPopupSize(int x, int y, int width, int height) {
        if (mRenderHandler != null) mRenderHandler.onPopupSize(x, y, width, height);
    }
    private void onImeCompositionRangeChanged(int x, int y, int width, int height) {
        if (mRenderHandler != null) mRenderHandler.onImeCompositionRangeChanged(x, y, width, height);
    }
    private void onTextSelectionChanged(String selectedText) {
        if (mRenderHandler != null) mRenderHandler.onTextSelectionChanged(selectedText);
    }
    private void onScrollOffsetChanged(double x, double y) {
        if (mRenderHandler != null) mRenderHandler.onScrollOffsetChanged(x, y);
    }

    // ---- Image download ----
    /** Receives an image fetched via {@link #downloadImage(String, int, ImageCallback)}. */
    public interface ImageCallback {
        /** @param bitmap the decoded image, or null on failure. */
        void onImage(String url, android.graphics.Bitmap bitmap);
    }
    private final java.util.Map<String, ImageCallback> mPendingImages =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Fetches an image (e.g. a favicon URL from
     * {@link CefriumDisplayHandler#onFaviconUrlChanged}) to a Bitmap.
     *
     * @param url      Image URL.
     * @param maxSize  Max edge in DIP (0 = no limit).
     * @param callback Delivered on the main thread; bitmap is null on failure.
     */
    public void downloadImage(String url, int maxSize, ImageCallback callback) {
        if (mNativePtr == 0 || url == null) return;
        if (callback != null) mPendingImages.put(url, callback);
        nativeDownloadImage(mNativePtr, url, this, maxSize);
    }
    private void onImageDownloaded(String url, byte[] pngBytes) {
        ImageCallback cb = mPendingImages.remove(url);
        if (cb == null) return;
        android.graphics.Bitmap bmp = null;
        if (pngBytes != null && pngBytes.length > 0) {
            bmp = android.graphics.BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.length);
        }
        final android.graphics.Bitmap fbmp = bmp;
        postMain(() -> cb.onImage(url, fbmp));
    }

    // ---- Misc BrowserHost parity ----
    /**
     * Gives or takes keyboard focus for this browser.
     *
     * <p>On-screen browsers receive focus from their Android {@code View}, so you
     * rarely need this. <b>Off-screen (OSR) browsers have no view</b>, so nothing
     * ever focuses them unless you call this, and their document stays unfocused
     * for its whole lifetime.
     *
     * <p>That matters beyond typing, because Blink gates some APIs on document
     * focus <i>before</i> any permission check, which makes them look like
     * permission failures. The clearest case is the async clipboard:
     *
     * <pre>{@code
     * // Without focus this rejects with
     * //   NotAllowedError: Document is not focused.
     * // and no CefriumBrowser.PermissionHandler is ever consulted.
     * browser.setFocus(true);
     * browser.evaluateJavaScript("navigator.clipboard.readText()", null);
     * }</pre>
     *
     * <p>Focus is independent of visibility: see {@link #setPageVisible(boolean)}
     * for the "is the page being shown" signal. A browser can be visible and
     * unfocused, or focused and hidden.
     *
     * @param focus {@code true} to focus this browser, {@code false} to blur it.
     */
    public void setFocus(boolean focus) {
        if (mNativePtr != 0) nativeSetFocus(mNativePtr, focus);
    }
    /** Programmatically exit HTML fullscreen (e.g. from the back button). */
    public void exitFullscreen() {
        if (mNativePtr != 0) nativeExitFullscreen(mNativePtr, true);
    }
    /** Force a download of {@code url}. */
    public void startDownload(String url) {
        if (mNativePtr != 0 && url != null) nativeStartDownload(mNativePtr, url);
    }

    /** One entry in the navigation history. See {@link #getNavigationEntries()}. */
    public static class NavigationEntry {
        /** True if this is the currently visible entry. */
        public final boolean current;
        /** Entry URL. */
        public final String url;
        /** Entry display title. */
        public final String title;
        public NavigationEntry(boolean current, String url, String title) {
            this.current = current;
            this.url = url;
            this.title = title;
        }
    }

    /**
     * Returns the back/forward history with titles, oldest-first. Empty if the
     * browser is not ready.
     */
    public java.util.List<NavigationEntry> getNavigationEntries() {
        java.util.List<NavigationEntry> out = new java.util.ArrayList<>();
        if (mNativePtr == 0) return out;
        String[] rows = nativeGetNavigationEntries(mNativePtr);
        if (rows == null) return out;
        for (String row : rows) {
            String[] parts = row.split("\t", 3);
            if (parts.length >= 2) {
                out.add(new NavigationEntry("1".equals(parts[0]), parts[1],
                        parts.length >= 3 ? parts[2] : ""));
            }
        }
        return out;
    }

    // Native methods.
    private static native long nativeCreate(int width, int height, float scale);
    private static native long nativeCreateWithSurface(
            org.chromium.ui.base.WindowAndroid windowAndroid,
            android.view.Surface surface, int width, int height, float scale,
            boolean incognito);
    private static native void nativeSetRenderHandler(long nativePtr, CefriumBrowser browser);
    private static native void nativeBindJavaBrowser(long nativePtr, CefriumBrowser browser);
    private static native void nativeLoadUrl(long nativePtr, String url);
    private static native void nativeLoadDataWithBaseUrl(long nativePtr, String baseUrl,
            String data, String mimeType, String encoding, String historyUrl);
    private static native void nativeInvalidate(long nativePtr);
    private static native void nativeImeSetComposition(long nativePtr, String text, int selStart, int selEnd);
    private static native void nativeImeCommitText(long nativePtr, String text, int relativeCursorPos);
    private static native void nativeImeFinishComposingText(long nativePtr, boolean keepSelection);
    private static native void nativeImeCancelComposition(long nativePtr);
    // FEATURE-063 parity native methods.
    private static native void nativeFileDialogResult(long nativePtr, long id, String[] paths);
    private static native void nativeSetAudioCaptureEnabled(long nativePtr, boolean enabled);
    private static native void nativeSetResponseTapEnabled(long nativePtr, boolean enabled);
    private static native void nativeSetCookieFilterEnabled(long nativePtr, boolean enabled);
    // Issue #10: CefBrowserHost::SetFocus -- the only way an OSR browser can be focused.
    private static native void nativeSetFocus(long nativePtr, boolean focus);
    // Issue #11: Android-shaped key events; maps the keycode with Chromium's
    // own ui::KeyboardCodeFromAndroidKeyCode so DOM key/code are populated.
    private static native void nativeSendKeyEventAndroid(
            long nativePtr, int type, int androidKeyCode, int metaState, int unicodeChar);
    private static native void nativeExitFullscreen(long nativePtr, boolean willCauseResize);
    private static native void nativeStartDownload(long nativePtr, String url);
    private static native String[] nativeGetNavigationEntries(long nativePtr);
    private static native void nativeDownloadImage(long nativePtr, String url, CefriumBrowser browser, int maxSize);
    private static native void nativeSelectClientCertificate(long nativePtr, long id, int index);
    private static native void nativeSendKeyEvent(long nativePtr, int type, int modifiers, int windowsKeyCode, int nativeKeyCode, int charCode);
    private static native void nativeQuickMenuCommand(long nativePtr, int command);
    private static native void nativeSendTouchEvent(long nativePtr, int action, float x, float y, int pointerId);
    private static native void nativeSendMouseMoveEvent(long nativePtr, int x, int y, int modifiers, boolean mouseLeave);
    private static native void nativeSendMouseClickEvent(long nativePtr, int x, int y, int modifiers, int button, boolean mouseUp, int clickCount);
    private static native void nativeSendMouseWheelEvent(long nativePtr, int x, int y, int modifiers, int deltaX, int deltaY);
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
    private static native void nativeRespondBeforeDownload(long nativePtr, int downloadId, boolean allow, String path);
    private static native void nativeDownloadControl(long nativePtr, int downloadId, int action);
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
