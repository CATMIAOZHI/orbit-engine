// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * A browser instance backed by Chromium via CEF (Chromium Embedded Framework).
 *
 * <p>Two rendering modes are available:</p>
 * <ul>
 *   <li><b>Off-screen (OSR)</b> — frames delivered as RGBA pixel buffers
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
 * <p>Input, resize and script results are public calls here; do not reach into
 * {@code org.chromium.*} for them. See
 * <a href="https://cefrium.com/guides/public-api/">the public API guide</a> and
 * <a href="https://cefrium.com/llms.txt">cefrium.com/llms.txt</a>.</p>
 *
 * @see Cefrium#initialize(android.content.Context)
 * @see CefriumRenderHandler
 */
public class CefriumBrowser {
    private long mNativePtr;  // Pointer to native CefBrowser
    private boolean mMediaPlaybackRequiresUserGesture = true;
    private CefriumRenderHandler mRenderHandler;
    private String mCurrentUrl;  // Tracked for saveState/getUrl
    private OnUrlChangedListener mUrlListener;
    private OnTitleChangedListener mTitleListener;
    private OnLoadingStateChangedListener mLoadingListener;
    private OnRenderProcessTerminatedListener mRenderProcessTerminatedListener;
    private volatile OnRequestInterceptedListener mRequestListener;
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
    // The render view's compositor exists once its first surface is created.
    private boolean mRenderViewReady;
    private boolean mTransparentBackground;
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
    // FEATURE-071: whose cookies, storage and cache this browser uses.
    private CefriumProfile mProfile = CefriumProfile.getDefault();
    // A restored tab defers its load until the surface + overscroll handler are
    // connected (connectWebContentsInternal), then triggers it once.
    private boolean mPendingRestoreLoad;
    // The ContentView added to mSurfaceContainer at connect; removed at close.
    private android.view.View mContentView;

    // Private marker constructor for factory methods — does NOT create native.
    private CefriumBrowser(boolean marker) {
        Cefrium.ensureInitialized();
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
        this(width, height, scale, CefriumProfile.getDefault());
    }

    /**
     * Like {@link #CefriumBrowser(int, int, float)}, using {@code profile}'s
     * cookies, storage and cache.
     */
    public CefriumBrowser(int width, int height, float scale, CefriumProfile profile) {
        checkThread("CefriumBrowser");
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
        Cefrium.ensureInitialized();
        mProfile = profile;
        mIncognito = profile.isIncognito();
        mOsrWidth = width;
        mOsrHeight = height;
        mOsrScale = scale;
        mNativePtr = nativeCreate(width, height, scale, profile.isIncognito(),
                profile.getName());
        // As createWithSurface does: events that do not depend on the app
        // listening to this browser (creation, proxy errors) reach it.
        if (isLive()) nativeBindJavaBrowser(mNativePtr, this);
        CefriumProfile.browserOpened(profile);
    }

    private static final String TAG = "CefriumBrowser";

    // Off-screen viewport, for a popup this browser opens.
    private int mOsrWidth;
    private int mOsrHeight;
    private float mOsrScale = 1.0f;

    // volatile: read on the main looper in connectWebContentsInternal (delayed
    // callback) while close() may set it from another thread; the write must be
    // visible to short-circuit the stale-pointer use-after-free.
    private volatile boolean mClosed;

    // FEATURE-084 contract for every public method: called on the main thread,
    // as Android WebView requires, and nothing reaches native after close().
    private void checkThread(String method) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            throw new IllegalStateException("CefriumBrowser." + method
                    + " must be called on the main thread, not on "
                    + Thread.currentThread().getName());
        }
    }

    private boolean mClosedCallLogged;

    private boolean isLive() {
        if (mClosed) {
            if (!mClosedCallLogged) {
                mClosedCallLogged = true;
                android.util.Log.w(TAG, "a method was called after close(); ignored");
            }
            return false;
        }
        return mNativePtr != 0;
    }

    private boolean checkNative(String method) {
        if (mClosed || mNativePtr == 0) {
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
     * Each frame is an RGBA (premultiplied) pixel buffer with dimensions matching the
     * viewport size set in the constructor.
     *
     * @param handler The handler to receive OnPaint callbacks, or null to stop.
     * @see CefriumRenderHandler#onPaint(int, int, java.nio.ByteBuffer)
     */
    public void setRenderHandler(CefriumRenderHandler handler) {
        checkThread("setRenderHandler");
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
        checkThread("loadUrl");
        if (!checkNative("loadUrl")) return;
        nativeLoadUrl(mNativePtr, url);
        // Surface mode connects the WebContents when the native browser exists
        // (onNativeBrowserCreated), not after this load.
    }

    /**
     * Navigate to a URL, sending {@code additionalHttpHeaders} with the page's
     * own request (not with the resources it loads), as WebView's
     * {@code loadUrl(String, Map)}. A header the engine sets itself
     * ({@code User-Agent}, {@code Cookie}) may be replaced by the engine's
     * value.
     *
     * @throws IllegalArgumentException if a name is empty or contains a colon,
     *     or a name or value contains a line break
     */
    public void loadUrl(String url, java.util.Map<String, String> additionalHttpHeaders) {
        checkThread("loadUrl");
        if (additionalHttpHeaders == null || additionalHttpHeaders.isEmpty()) {
            loadUrl(url);
            return;
        }
        String[] pairs = new String[additionalHttpHeaders.size() * 2];
        int i = 0;
        for (java.util.Map.Entry<String, String> e : additionalHttpHeaders.entrySet()) {
            String name = e.getKey();
            String value = e.getValue() == null ? "" : e.getValue();
            if (name == null || name.trim().isEmpty() || name.indexOf(':') >= 0
                    || hasLineBreak(name) || hasLineBreak(value)) {
                throw new IllegalArgumentException("bad header: " + name);
            }
            pairs[i++] = name.trim();
            pairs[i++] = value;
        }
        if (!checkNative("loadUrl")) return;
        nativeLoadUrlWithHeaders(mNativePtr, url, pairs);
    }

    private static boolean hasLineBreak(String s) {
        return s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0;
    }

    /**
     * Sets the background colour, as ARGB: what shows where the page draws no
     * background of its own, and before its first frame. The default is opaque
     * white.
     *
     * <p>Pass {@code 0x00000000} for a transparent background, for a page drawn
     * over other content (a game's UI over its engine, an overlay). Page areas
     * the page leaves transparent then show what is below:
     * <ul>
     *   <li>Surface browser ({@link #createWithSurface}): another
     *       {@link android.view.SurfaceView} below it, such as a game engine's.
     *       The browser's surface is a media overlay, above other surfaces and
     *       below the window, so views of the window under the browser do not
     *       show through.</li>
     *   <li>Off-screen browser: the pixels arrive in
     *       {@link CefriumRenderHandler#onPaint} with alpha 0.</li>
     * </ul>
     *
     * <p>A surface browser takes a colour at any time, opaque or transparent.
     * An off-screen browser takes it before its first load: the browser is
     * created then, and its background cannot change afterwards.
     *
     * @throws IllegalArgumentException if this is a surface browser and the
     *     colour is neither opaque nor fully transparent.
     * @throws IllegalStateException if this is an off-screen browser that has
     *     already loaded a page.
     */
    public void setBackgroundColor(int argb) {
        checkThread("setBackgroundColor");
        if (!checkNative("setBackgroundColor")) return;
        if (mSurfaceContainer != null) {
            int alpha = argb >>> 24;
            if (alpha != 0xFF && alpha != 0) {
                throw new IllegalArgumentException(
                        "a surface browser's background is opaque or transparent");
            }
            boolean transparent = alpha == 0;
            if (!transparent && mContentViewRenderView != null) {
                // Where no layer draws, e.g. before the page's first frame.
                mContentViewRenderView.setCompositorBackgroundColor(argb);
            }
            if (transparent != mTransparentBackground && mContentViewRenderView != null) {
                mTransparentBackground = transparent;
                // An alpha channel in the surface and the compositor, which
                // exists once the first surface does (onReadyToRender).
                if (mRenderViewReady) {
                    mContentViewRenderView.setOverlayVideoMode(transparent);
                } else {
                    mContentViewRenderView.getSurfaceView().getHolder().setFormat(transparent
                            ? android.graphics.PixelFormat.TRANSLUCENT
                            : android.graphics.PixelFormat.OPAQUE);
                }
            }
            // Until the first frame the SurfaceView shows its own background,
            // white, which ContentViewRenderView clears on the first swap. Not
            // after: it would cover the page until the next one.
            if (mContentViewRenderView != null
                    && mContentViewRenderView.getSurfaceView().getBackground() != null) {
                mContentViewRenderView.setSurfaceViewBackgroundColor(argb);
            }
            nativeSetBackgroundColor(mNativePtr, argb);
            return;
        }
        if (!nativeSetBackgroundColor(mNativePtr, argb)) {
            throw new IllegalStateException(
                    "setBackgroundColor must be called before the first load");
        }
    }

    /**
     * Navigate back in history. No-op if {@link #canGoBack()} is false.
     */
    public void goBack() {
        checkThread("goBack");
        if (checkNative("goBack")) nativeGoBack(mNativePtr);
    }

    /**
     * Navigate forward in history. No-op if there is no forward history.
     */
    public void goForward() {
        checkThread("goForward");
        if (checkNative("goForward")) nativeGoForward(mNativePtr);
    }

    /**
     * Enable pull-to-refresh: an overscroll-down gesture at the top of the page
     * reloads it (Chrome-style). Off by default. May be called, and toggled,
     * at any time.
     */
    public void setPullToRefreshEnabled(boolean enabled) {
        checkThread("setPullToRefreshEnabled");
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
     * Set this browser's preferred color scheme ({@code prefers-color-scheme}).
     * Can be called right after creation; it applies once the browser exists.
     *
     * @param dark true for dark mode, false for light mode.
     */
    public void setDarkMode(boolean dark) {
        checkThread("setDarkMode");
        if (isLive()) nativeSetDarkMode(mNativePtr, dark);
    }

    /**
     * Whether media needs a user gesture to start playing with sound, as
     * Android WebView's {@code WebSettings.setMediaPlaybackRequiresUserGesture}.
     * By default an unmuted {@code play()} or {@code autoplay} without a tap is
     * rejected with {@code NotAllowedError}. Pass {@code false} to let pages
     * start sound on their own. Can be called before the first load.
     */
    public void setMediaPlaybackRequiresUserGesture(boolean required) {
        checkThread("setMediaPlaybackRequiresUserGesture");
        mMediaPlaybackRequiresUserGesture = required;
        if (isLive()) nativeSetMediaPlaybackRequiresUserGesture(mNativePtr, required);
    }

    /** The value last passed to {@link #setMediaPlaybackRequiresUserGesture}; true by default. */
    public boolean getMediaPlaybackRequiresUserGesture() {
        checkThread("getMediaPlaybackRequiresUserGesture");
        return mMediaPlaybackRequiresUserGesture;
    }

    /** A script added with {@link #addDocumentStartJavaScript}. */
    public interface ScriptHandler {
        /** Stops the script for documents that start from now on. */
        void remove();
    }

    /**
     * Runs {@code script} in every document of this browser whose origin
     * matches {@code allowedOriginRules}, before any of the page's own
     * scripts, as Android WebView's {@code addDocumentStartJavaScript}. It runs
     * in iframes too, in the page's JavaScript world, for every navigation from
     * now on, until removed. A rule is {@code "*"} (every origin), an origin
     * such as {@code "https://example.com"} or {@code "http://localhost:8080"},
     * or a subdomain pattern such as {@code "https://*.example.com"}. Can be
     * called before the first load; added after a load starts, it runs from
     * the next document on. Call it on the main thread.
     *
     * <p>To add CSS at document start (before the page has a {@code <head>}),
     * adopt a stylesheet:
     * {@code const s = new CSSStyleSheet(); s.replaceSync(css);
     * document.adoptedStyleSheets = [...document.adoptedStyleSheets, s];}
     *
     * @throws IllegalArgumentException if a rule is not valid, or there is no
     *     rule: an empty set matches no origin.
     * @throws IllegalStateException off the main thread, or after {@link #close}.
     */
    public ScriptHandler addDocumentStartJavaScript(String script,
            java.util.Set<String> allowedOriginRules) {
        checkThread("addDocumentStartJavaScript");
        if (script == null) throw new IllegalArgumentException("script must not be null");
        if (allowedOriginRules == null || allowedOriginRules.isEmpty()) {
            throw new IllegalArgumentException("allowedOriginRules is empty, so the script"
                    + " would run nowhere. Pass \"*\" for every origin.");
        }
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "addDocumentStartJavaScript must be called on the main thread");
        }
        String[] rules = allowedOriginRules.toArray(new String[0]);
        if (mClosed || !checkNative("addDocumentStartJavaScript")) {
            throw new IllegalStateException(
                    "addDocumentStartJavaScript: the browser is closed or did not start");
        }
        int id = nativeAddDocumentStartJavaScript(mNativePtr, script, rules);
        if (id < 0) {
            throw new IllegalArgumentException("allowedOriginRules " + rules[-id - 1]
                    + " is invalid");
        }
        if (id == 0) {
            throw new IllegalStateException(
                    "addDocumentStartJavaScript: the engine did not accept the script");
        }
        return () -> {
            if (id > 0 && isLive()) nativeRemoveDocumentStartJavaScript(mNativePtr, id);
        };
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

    private volatile Callback mCallback;

    private volatile CefriumNavigationClient mNavigationClient;

    /**
     * Set the client told of this browser's navigations (see
     * {@link CefriumNavigationClient}), or null to remove it. The last one set
     * wins.
     */
    public void setNavigationClient(CefriumNavigationClient client) {
        checkThread("setNavigationClient");
        mNavigationClient = client;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }

    // Called from native CefRequestHandler::OnBeforeBrowse, on the UI thread.
    // |headers| alternates names and values.
    @SuppressWarnings("unused")
    private boolean onBeforeNavigate(String url, String method, boolean mainFrame,
            boolean redirect, boolean gesture, String[] headers) {
        CefriumNavigationClient client = mNavigationClient;
        if (client == null || mClosed) return false;
        return client.onBeforeNavigate(this, new CefriumNavigationRequest(
                url, method, mainFrame, redirect, gesture, headers));
    }

    /**
     * Set a unified callback for all browser events.
     * This is the recommended way to handle events. Individual
     * setOnXxxListener methods are also available for convenience.
     */
    public void setCallback(Callback callback) {
        checkThread("setCallback");
        mCallback = callback;
        if (isLive()) {
            nativeSetDisplayCallback(mNativePtr, this);
        }
        updateRequestObservers();
    }

    /**
     * Listener for URL changes. Called by CEF's CefDisplayHandler::OnAddressChange.
     * @deprecated Use {@link CefriumNavigationClient#onUrlChanged}.
     */
    @Deprecated
    public interface OnUrlChangedListener {
        void onUrlChanged(String url);
    }

    /**
     * Listener for title changes. Called by CEF's CefDisplayHandler::OnTitleChange.
     * @deprecated Use {@link CefriumChromeClient#onTitleChanged}.
     */
    @Deprecated
    public interface OnTitleChangedListener {
        void onTitleChanged(String title);
    }

    /**
     * Listener for network requests: one call per request this browser makes,
     * with the full URL and whether the ad blocker or a
     * {@link RequestInterceptor} blocked it. Called on the UI thread, after the
     * request has been let through or cancelled; to decide, use a
     * {@link RequestInterceptor}.
     */
    public interface OnRequestInterceptedListener {
        void onRequestIntercepted(String url, String method, boolean blocked);
    }

    /**
     * Listener for fullscreen mode changes. Called by CEF's
     * CefDisplayHandler::OnFullscreenModeChange.
     * @deprecated Use {@link CefriumChromeClient#onFullscreenChanged}.
     */
    @Deprecated
    public interface OnFullscreenChangedListener {
        void onFullscreenChanged(boolean fullscreen);
    }

    /**
     * Listener for loading state changes. Called by CEF's
     * CefLoadHandler::OnLoadingStateChange.
     * @deprecated Use {@link CefriumNavigationClient#onLoadingStateChanged}.
     */
    @Deprecated
    public interface OnLoadingStateChangedListener {
        void onLoadingStateChanged(boolean isLoading, boolean canGoBack,
                                   boolean canGoForward);
    }

    /**
     * Termination status: process exited abnormally.
     * @deprecated Use {@link CefriumRendererTerminationStatus}.
     */
    @Deprecated
    public static final int TERMINATION_ABNORMAL = 0;
    /**
     * Termination status: process was killed (e.g. by the OS low-memory killer).
     * @deprecated Use {@link CefriumRendererTerminationStatus}.
     */
    @Deprecated
    public static final int TERMINATION_KILLED = 1;
    /**
     * Termination status: process crashed.
     * @deprecated Use {@link CefriumRendererTerminationStatus}.
     */
    @Deprecated
    public static final int TERMINATION_CRASHED = 2;
    /**
     * Termination status: process ran out of memory.
     * @deprecated Use {@link CefriumRendererTerminationStatus}.
     */
    @Deprecated
    public static final int TERMINATION_OOM = 3;

    /**
     * Listener for renderer-process termination. Called by CEF's
     * CefRequestHandler::OnRenderProcessTerminated. A renderer can die without
     * killing the app process and without a logcat FATAL -- use this to show a
     * reload UI (the page is now blank/dead) and to count renderer crashes.
     * @deprecated Use {@link CefriumNavigationClient#onRenderProcessTerminated}.
     */
    @Deprecated
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
     * @deprecated Use {@link CefriumNavigationClient#onUrlChanged}.
     */
    @Deprecated
    public void setOnUrlChangedListener(OnUrlChangedListener listener) {
        checkThread("setOnUrlChangedListener");
        mUrlListener = listener;
        if (isLive()) {
            nativeSetDisplayCallback(mNativePtr, this);
        }
    }

    /**
     * Set a listener for title changes.
     * @deprecated Use {@link CefriumChromeClient#onTitleChanged}.
     */
    @Deprecated
    public void setOnTitleChangedListener(OnTitleChangedListener listener) {
        checkThread("setOnTitleChangedListener");
        mTitleListener = listener;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }

    /**
     * Set a listener for fullscreen mode changes.
     * @deprecated Use {@link CefriumChromeClient#onFullscreenChanged}.
     */
    @Deprecated
    public void setOnFullscreenChangedListener(OnFullscreenChangedListener listener) {
        checkThread("setOnFullscreenChangedListener");
        mFullscreenListener = listener;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }

    /**
     * Set a listener told of every network request (see
     * {@link OnRequestInterceptedListener}), for inspection, debugging or
     * auditing. Called on the UI thread.
     */
    public void setOnRequestInterceptedListener(OnRequestInterceptedListener listener) {
        checkThread("setOnRequestInterceptedListener");
        mRequestListener = listener;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
        updateRequestObservers();
    }

    // Tells native which per-request Java listeners exist, so a request that
    // nobody observes makes no call into Java (it runs on the network thread).
    private void updateRequestObservers() {
        if (mNativePtr == 0) return;
        int observers = 0;
        if (mRequestInterceptor != null) observers |= 1;
        if (overridesInterceptRequest(mRequestInterceptor)) observers |= 8;
        if (mRequestListener != null || overridesRequestIntercepted(mCallback)) observers |= 2;
        if (mRequestHandler != null) observers |= 4;
        nativeSetRequestObservers(mNativePtr, observers);
    }

    private static boolean overridesInterceptRequest(CefriumResourceInterceptor i) {
        if (i == null) return false;
        try {
            return i.getClass().getMethod("shouldInterceptRequest",
                    CefriumContentRequest.class).getDeclaringClass()
                    != CefriumResourceInterceptor.class;
        } catch (NoSuchMethodException e) {
            return true;
        }
    }

    private static boolean overridesRequestIntercepted(Callback callback) {
        if (callback == null) return false;
        try {
            return callback.getClass().getMethod("onRequestIntercepted",
                    String.class, String.class, boolean.class).getDeclaringClass()
                    != Callback.class;
        } catch (NoSuchMethodException e) {
            return true;
        }
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
        CefriumNavigationClient nav = mNavigationClient;
        if (nav != null) nav.onUrlChanged(this, url);
    }

    /** The current top-level URL, or {@code null} before the first navigation. */
    public String getUrl() {
        checkThread("getUrl");
        return mCurrentUrl;
    }

    // ── State preservation (Activity recreation: rotation, process death) ──

    /**
     * Captures restorable browser state (the current URL) into a Bundle. Call
     * from {@code Activity.onSaveInstanceState} and pass the result to
     * {@link #restoreState} after recreating the browser.
     */
    public android.os.Bundle saveState() {
        checkThread("saveState");
        android.os.Bundle state = new android.os.Bundle();
        if (mCurrentUrl != null) state.putString(STATE_URL, mCurrentUrl);
        return state;
    }

    /**
     * Restores state previously captured by {@link #saveState} — re-navigates to
     * the saved URL. No-op if {@code state} is null or holds no saved URL.
     */
    public void restoreState(android.os.Bundle state) {
        checkThread("restoreState");
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
        checkThread("serializeNavigationState");
        return checkNative("serializeNavigationState")
                ? nativeSerializeNavigationState(mNativePtr) : null;
    }

    /**
     * Restores a navigation stack captured by {@link #serializeNavigationState}.
     * Must be called on a fresh browser (empty history) before its first
     * navigation; no-op for null/empty input.
     */
    public void restoreNavigationState(byte[] state) {
        checkThread("restoreNavigationState");
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
     * @deprecated Use {@link CefriumNavigationClient#onLoadingStateChanged}.
     */
    @Deprecated
    public void setOnLoadingStateChangedListener(OnLoadingStateChangedListener listener) {
        checkThread("setOnLoadingStateChangedListener");
        mLoadingListener = listener;
        if (isLive()) {
            nativeSetDisplayCallback(mNativePtr, this);
        }
    }

    /**
     * Set a listener notified when a renderer process for this browser dies.
     * The native CefRequestHandler is always installed, so no extra wiring is
     * needed -- the callback fires whenever the renderer terminates.
     * @deprecated Use {@link CefriumNavigationClient#onRenderProcessTerminated}.
     */
    @Deprecated
    public void setOnRenderProcessTerminatedListener(
            OnRenderProcessTerminatedListener listener) {
        checkThread("setOnRenderProcessTerminatedListener");
        mRenderProcessTerminatedListener = listener;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }

    // Called from native CefDisplayHandler::OnTitleChange.
    @SuppressWarnings("unused")
    private String mLastTitle;

    private void onTitleChanged(String title) {
        mLastTitle = title;
        if (mCallback != null) mCallback.onTitleChanged(title);
        if (mTitleListener != null) mTitleListener.onTitleChanged(title);
        CefriumChromeClient chrome = mChromeClient;
        if (chrome != null) chrome.onTitleChanged(this, title);
    }

    // Called from native CefDisplayHandler::OnFullscreenModeChange.
    @SuppressWarnings("unused")
    private void onFullscreenChanged(boolean fullscreen) {
        if (mCallback != null) mCallback.onFullscreenChanged(fullscreen);
        if (mFullscreenListener != null) mFullscreenListener.onFullscreenChanged(fullscreen);
        CefriumChromeClient chrome = mChromeClient;
        if (chrome != null) chrome.onFullscreenChanged(this, fullscreen);
    }

    private static final android.os.Handler MAIN_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    // Called from native CefResourceRequestHandler::OnBeforeResourceLoad, on the
    // network thread. It only reports, so it is delivered on the UI thread like
    // every other Callback method, in request order.
    @SuppressWarnings("unused")
    private void onRequestIntercepted(String url, String method, boolean blocked) {
        MAIN_HANDLER.post(() -> {
            if (mCallback != null) mCallback.onRequestIntercepted(url, method, blocked);
            if (mRequestListener != null) {
                mRequestListener.onRequestIntercepted(url, method, blocked);
            }
        });
    }

    // Called from native CefLoadHandler::OnLoadingStateChange.
    @SuppressWarnings("unused")
    private void onLoadingStateChanged(boolean isLoading, boolean canGoBack,
                                       boolean canGoForward) {
        if (mCallback != null) mCallback.onLoadingStateChanged(isLoading, canGoBack, canGoForward);
        if (mLoadingListener != null) mLoadingListener.onLoadingStateChanged(isLoading, canGoBack, canGoForward);
        CefriumNavigationClient nav = mNavigationClient;
        if (nav != null) nav.onLoadingStateChanged(this, isLoading, canGoBack, canGoForward);
        if (!isLoading && !mPinchToZoomEnabled) {
            evaluateJavaScript(PINCH_ZOOM_DISABLE_JS);
        }
    }

    // Called from native CefRequestHandler::OnRenderProcessTerminated.
    @SuppressWarnings("unused")
    // |status| is a TERMINATION_ value (mapped from the engine's in native), or
    // 4 for any other reason.
    private void onRenderProcessTerminated(int status, int errorCode) {
        if (mRenderProcessTerminatedListener != null) {
            mRenderProcessTerminatedListener.onRenderProcessTerminated(status, errorCode);
        }
        CefriumNavigationClient nav = mNavigationClient;
        if (nav != null) {
            CefriumRendererTerminationStatus[] all = CefriumRendererTerminationStatus.values();
            nav.onRenderProcessTerminated(this,
                    status >= 0 && status < all.length ? all[status]
                            : CefriumRendererTerminationStatus.UNKNOWN,
                    errorCode);
        }
    }

    /**
     * Reload the current page, revalidating cached resources as a normal reload
     * does (like WebView's {@code reload()}).
     */
    public void reload() {
        checkThread("reload");
        if (checkNative("reload")) nativeReload(mNativePtr);
    }

    /**
     * Execute JavaScript in the browser's main frame (fire-and-forget).
     * Uses CefFrame::ExecuteJavaScript. To get the script's value back, use
     * {@link #evaluateJavaScript(String, android.webkit.ValueCallback)}.
     *
     * @param script JavaScript source to execute. Ignored if null.
     */
    public void evaluateJavaScript(String script) {
        checkThread("evaluateJavaScript");
        if (script != null && checkNative("evaluateJavaScript")) {
            nativeEvaluateJavaScript(mNativePtr, script);
        }
    }

    /**
     * Execute JavaScript in the frame that has focus (fire-and-forget), or in
     * the main frame when none has. Use it to act on the focused field: in a
     * field inside an iframe, the main frame's {@code document.activeElement}
     * is the iframe, not the field. Uses CefBrowser::GetFocusedFrame.
     *
     * @param script JavaScript source to execute. Ignored if null.
     */
    public void evaluateJavaScriptInFocusedFrame(String script) {
        checkThread("evaluateJavaScriptInFocusedFrame");
        if (script != null && checkNative("evaluateJavaScriptInFocusedFrame")) {
            nativeEvaluateJavaScriptInFocusedFrame(mNativePtr, script);
        }
    }

    /**
     * Evaluates JavaScript in the main frame and returns its value, like
     * {@code WebView.evaluateJavascript}. The callback gets the value as JSON
     * ({@code "2"}, {@code "\"text\""}, {@code "{\"a\":1}"}), {@code "null"}
     * for undefined or a value JSON cannot hold, and Java {@code null} when the
     * script threw or could not run. A Promise is not awaited. Runs through
     * the DevTools protocol ({@code Runtime.evaluate}), so the page's Content
     * Security Policy does not block it.
     *
     * <p>Experimental: it may still change before the 1.0 API freeze.</p>
     *
     * @param script         JavaScript source. Ignored if null.
     * @param resultCallback invoked on the UI thread; may be null.
     */
    public void evaluateJavaScript(String script,
            android.webkit.ValueCallback<String> resultCallback) {
        checkThread("evaluateJavaScript");
        if (script == null) return;
        if (resultCallback == null) {
            evaluateJavaScript(script);
            return;
        }
        String params;
        try {
            params = new org.json.JSONObject()
                    .put("expression", script)
                    .put("returnByValue", true)
                    .toString();
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
        executeDevToolsMethod("Runtime.evaluate", params,
                (success, json) -> resultCallback.onReceiveValue(
                        success ? evaluateResultValue(json) : null));
    }

    // The JSON of a Runtime.evaluate result's value; null if it threw.
    static String evaluateResultValue(String resultJson) {
        try {
            org.json.JSONObject r = new org.json.JSONObject(resultJson);
            if (r.has("exceptionDetails")) return null;
            org.json.JSONObject result = r.optJSONObject("result");
            if (result == null || !result.has("value")) return "null";
            Object value = result.get("value");
            if (value instanceof String) return org.json.JSONObject.quote((String) value);
            return value == org.json.JSONObject.NULL ? "null" : value.toString();
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    // ─────────────── DevTools protocol (FEATURE-082) ───────────────

    /** Result of {@link #executeDevToolsMethod}. Invoked on the UI thread. */
    public interface DevToolsCallback {
        /**
         * @param success    whether the method succeeded
         * @param resultJson on success the method's {@code result} object as
         *                   JSON, otherwise its {@code error} object, which has
         *                   a {@code message}
         */
        void onDevToolsResult(boolean success, String resultJson);
    }

    /** DevTools protocol events of this browser. Invoked on the UI thread. */
    public interface DevToolsEventListener {
        /**
         * @param method     the event, e.g. {@code Page.loadEventFired}
         * @param paramsJson its parameters as a JSON object
         */
        void onDevToolsEvent(String method, String paramsJson);
    }

    private final java.util.concurrent.ConcurrentHashMap<Integer, DevToolsCallback>
            mDevToolsCallbacks = new java.util.concurrent.ConcurrentHashMap<>();
    // The SDK's own internal DevTools calls use ids below 1000.
    private final java.util.concurrent.atomic.AtomicInteger mDevToolsId =
            new java.util.concurrent.atomic.AtomicInteger(1000);
    private volatile DevToolsEventListener mDevToolsEventListener;

    /**
     * Calls a Chrome DevTools Protocol method on this browser, in process: no
     * remote debugging port is opened. For example
     * {@code executeDevToolsMethod("Page.navigate", "{\"url\":\"https://example.com\"}", cb)}.
     * Domains that send events (Page, Network, Runtime, ...) report them to
     * the {@link #setDevToolsEventListener listener} once enabled with their
     * {@code enable} method. Uses CefBrowserHost::ExecuteDevToolsMethod.
     *
     * <p>Can be called right after the constructor: an off-screen browser is
     * created asynchronously, and a call made before it exists runs as soon
     * as it does. After {@link #close()} the callback gets an error.
     *
     * <p>Experimental: it may still change before the 1.0 API freeze.</p>
     *
     * @param method     e.g. {@code Runtime.evaluate}
     * @param paramsJson the parameters as a JSON object, or null for none
     * @param callback   invoked once on the UI thread; may be null
     */
    public void executeDevToolsMethod(String method, String paramsJson,
            DevToolsCallback callback) {
        if (method == null) throw new IllegalArgumentException("method is null");
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> executeDevToolsMethod(method, paramsJson, callback));
            return;
        }
        int id = mDevToolsId.getAndIncrement();
        if (callback != null) mDevToolsCallbacks.put(id, callback);
        int rc = mNativePtr == 0 ? 1
                : nativeExecuteDevToolsMethod(mNativePtr, this, id, method, paramsJson,
                        mDevToolsEventListener != null);
        if (rc != 0) {
            onDevToolsResult(id, false, devToolsError(rc == 2
                    ? "paramsJson is not a JSON object"
                    : rc == 3 ? "the method was not accepted"
                              : "the browser is closed"));
        }
    }

    /**
     * Receives every DevTools protocol event of this browser; null stops
     * them. Events flow only for domains enabled through
     * {@link #executeDevToolsMethod}.
     * <p>Experimental: it may still change before the 1.0 API freeze.</p>
     */
    public void setDevToolsEventListener(DevToolsEventListener listener) {
        checkThread("setDevToolsEventListener");
        mDevToolsEventListener = listener;
        if (isLive()) {
            nativeSetDevToolsEventsEnabled(mNativePtr, this, listener != null);
        }
    }

    private static String devToolsError(String message) {
        try {
            return new org.json.JSONObject().put("message", message).toString();
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // Called from native (UI thread).
    private void onDevToolsResult(int id, boolean success, String resultJson) {
        DevToolsCallback cb = mDevToolsCallbacks.remove(id);
        if (cb != null) cb.onDevToolsResult(success, resultJson);
    }

    // Called from native (UI thread).
    private void onDevToolsEvent(String method, String paramsJson) {
        DevToolsEventListener l = mDevToolsEventListener;
        if (l != null) l.onDevToolsEvent(method, paramsJson);
    }

    // Pending calls never get an answer once the browser is gone.
    private void failPendingDevToolsCalls() {
        for (Integer id : new java.util.ArrayList<>(mDevToolsCallbacks.keySet())) {
            onDevToolsResult(id, false, devToolsError("the browser was closed"));
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

        /**
         * The same, also saying whether the main frame called. Only a policy
         * from {@link QueryOriginPolicy#withSubframes()} lets an iframe call at
         * all; override this one to treat the two differently. The browser
         * calls it, and by default it calls the four-argument form.
         *
         * @param isMainFrame Whether the calling frame is the main frame.
         */
        default boolean onQuery(long queryId, String request, String origin,
                                boolean isMainFrame, QueryCallback callback) {
            return onQuery(queryId, request, origin, callback);
        }
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
     * {@link #anyOrigin()} itself; a custom predicate never sees one. A query
     * the browser handles after the frame has moved on to another document is
     * denied too, before the policy runs: it is judged by the document that
     * sent it, never by the one the frame holds now.
     * A custom predicate sees the raw URL: compare
     * {@link #originOf(String) originOf(url)} or the parsed host, never a
     * string prefix, because {@code https://example.com.evil.com/} starts with
     * {@code https://example.com}.</p>
     *
     * <p>Only the main frame may call by default, whatever the policy. An
     * allowed page can be framed by any site, and its iframe would otherwise
     * reach your handler from inside that site. {@link #withSubframes()} opts
     * a policy into subframes; even then a subframe is admitted only when the
     * policy allows its main frame as well.</p>
     */
    public interface QueryOriginPolicy {
        boolean allows(String frameUrl);

        /**
         * Whether calls from subframes are considered at all. {@code false}
         * unless the policy came from {@link #withSubframes()}.
         */
        default boolean allowsSubframes() {
            return false;
        }

        /**
         * This policy, also for subframes: a call from an iframe is admitted
         * when the policy allows both the iframe's URL and its main frame's.
         * An allowed page framed by a site the policy does not allow stays
         * denied.
         */
        default QueryOriginPolicy withSubframes() {
            return new SubframePolicy(this);
        }

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

    /** {@link QueryOriginPolicy#withSubframes()}: the base policy, plus subframes. */
    private static final class SubframePolicy implements QueryOriginPolicy {
        final QueryOriginPolicy base;

        SubframePolicy(QueryOriginPolicy base) {
            this.base = base instanceof SubframePolicy ? ((SubframePolicy) base).base : base;
        }

        @Override
        public boolean allows(String frameUrl) {
            return base.allows(frameUrl);
        }

        @Override
        public boolean allowsSubframes() {
            return true;
        }
    }

    /**
     * Whether a subframe's call may go on to the policy: only when the policy
     * opted into subframes and allows the caller's main frame too. Fails
     * closed on an opaque main frame (unless anyOrigin) or a throwing policy.
     */
    private static boolean subframeAdmitted(QueryOriginPolicy policy, boolean any,
            String mainFrameUrl, String mainFrameCommittedOrigin) {
        if (!policy.allowsSubframes()) return false;
        if (any) return true;
        if (mainFrameCommittedOrigin == null || "null".equals(mainFrameCommittedOrigin)) {
            return false;
        }
        try {
            return policy.allows(mainFrameUrl);
        } catch (RuntimeException e) {
            android.util.Log.w(TAG, "QueryOriginPolicy threw on the main frame; denying", e);
            return false;
        }
    }

    /** The policy without a {@link SubframePolicy} wrapper, for identity checks. */
    private static QueryOriginPolicy basePolicy(QueryOriginPolicy p) {
        return p instanceof SubframePolicy ? ((SubframePolicy) p).base : p;
    }

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
        checkThread("setQueryHandler");
        if (handler != null && policy == null) {
            throw new IllegalArgumentException(
                    "setQueryHandler needs a QueryOriginPolicy; pass "
                    + "QueryOriginPolicy.anyOrigin() to accept every origin");
        }
        if (handler != null && basePolicy(policy) == ANY_ORIGIN) {
            android.util.Log.w(TAG, "window.cefriumQuery is reachable from every "
                    + "origin (QueryOriginPolicy.anyOrigin())");
        }
        mQuery = handler == null ? null : new QueryRegistration(policy, handler);
    }

    // Called from native (browser UI thread) when JS calls window.cefriumQuery.
    // origin is the frame URL; committedOrigin is the frame's committed origin,
    // serialized, "null" when opaque; senderOrigin is the origin of the document
    // that sent the query, stamped by the renderer when it was sent.
    private void onJsQuery(final long queryId, String request, String origin,
            String committedOrigin, String senderOrigin, boolean isMainFrame,
            String mainFrameUrl, String mainFrameCommittedOrigin) {
        final QueryRegistration reg = mQuery;
        final QueryHandler handler = reg == null ? null : reg.handler;
        // The frame no longer holds the document that sent this query: it was
        // handled after the frame committed another document. Judging it by
        // the frame's current state would give the old document the new one's
        // identity, and no reply could reach the old document anyway.
        if (handler != null && committedOrigin != null
                && !committedOrigin.equals(senderOrigin)) {
            android.util.Log.w(TAG, "window.cefriumQuery denied: sent by a document "
                    + "the frame no longer holds");
            nativeQueryResult(mNativePtr, queryId, false, null,
                    QUERY_ERROR_ORIGIN_DENIED, "origin not allowed");
            return;
        }
        boolean allowed = false;
        // A sandboxed frame (sandbox attribute or CSP sandbox) has an opaque
        // origin even at an allowed URL: the content its embedder did not
        // trust. Only anyOrigin() admits it; no other policy is consulted.
        boolean opaque = committedOrigin == null || "null".equals(committedOrigin);
        boolean any = basePolicy(reg == null ? null : reg.policy) == ANY_ORIGIN;
        if (handler != null && !isMainFrame && !subframeAdmitted(reg.policy, any,
                mainFrameUrl, mainFrameCommittedOrigin)) {
            // An allowed page can be framed by any site; its iframe must not
            // reach the handler from inside that site. Log origins, not URLs.
            android.util.Log.w(TAG, "window.cefriumQuery denied for a subframe of "
                    + normalizeOrigin(mainFrameUrl)
                    + (reg.policy.allowsSubframes() ? "" : " (the policy is main frame only)"));
            nativeQueryResult(mNativePtr, queryId, false, null,
                    QUERY_ERROR_ORIGIN_DENIED, "origin not allowed");
            return;
        }
        if (handler != null && (!opaque || any)) {
            try {
                allowed = reg.policy.allows(origin);
            } catch (RuntimeException e) {
                // Fail closed: an exception here would otherwise escape into
                // the native caller and leave the query pending.
                android.util.Log.w(TAG, "QueryOriginPolicy threw; denying", e);
            }
        }
        if (handler != null && !allowed) {
            // Log the origin, never the full URL: a URL can carry tokens. A
            // sandboxed frame says so, or the log would show an origin the
            // policy allows next to the word "denied".
            String shown = normalizeOrigin(origin);
            android.util.Log.w(TAG, opaque && !"null".equals(shown)
                    ? "window.cefriumQuery denied for a sandboxed frame at " + shown
                            + " (its origin is opaque)"
                    : "window.cefriumQuery denied for origin " + shown);
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
        if (!handler.onQuery(queryId, request, origin, isMainFrame, callback)) {
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
        checkThread("setCookie");
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
        checkThread("setCookie");
        if (isLive()) {
            nativeSetCookie(mNativePtr, url, name, value, secure, httpOnly, sameSite);
        }
    }

    /** Reads cookies visible to {@code url}; the callback fires on the UI thread. */
    public void getCookies(String url, CookieCallback callback) {
        checkThread("getCookies");
        if (!isLive() || callback == null) return;
        long reqId = mCookieReqId.getAndIncrement();
        mCookieCallbacks.put(reqId, callback);
        nativeGetCookies(mNativePtr, this, url, reqId);
    }

    /** Deletes cookies for {@code url}; {@code name} null/empty = all for the URL. */
    public void deleteCookies(String url, String name) {
        checkThread("deleteCookies");
        if (isLive()) nativeDeleteCookies(mNativePtr, url, name == null ? "" : name);
    }

    /** Persists the cookie store to disk. */
    public void flushCookies() {
        checkThread("flushCookies");
        if (isLive()) nativeFlushCookies(mNativePtr);
    }

    /** Clears the browser's HTTP cache. */
    public void clearCache() {
        checkThread("clearCache");
        if (isLive()) nativeClearCache(mNativePtr);
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
        checkThread("printToPdf");
        if (!isLive() || callback == null) return;
        printToPdf(filePath, 0, 0, false, callback);
    }

    /**
     * Like {@link #printToPdf(String, PdfCallback)}, on paper of the given
     * size in inches (the portrait size; 0 for the default, Letter), in
     * landscape if asked.
     *
     * <p>Experimental: it may still change before the 1.0 API freeze.</p>
     */
    public void printToPdf(String filePath, double paperWidthInches, double paperHeightInches,
            boolean landscape, PdfCallback callback) {
        checkThread("printToPdf");
        if (!isLive() || callback == null) return;
        long id = mPdfReqId.getAndIncrement();
        mPdfCallbacks.put(id, callback);
        nativePrintToPdf(mNativePtr, this, filePath, id, paperWidthInches, paperHeightInches,
                landscape);
    }

    // ─────────────── Text selection (TE2) ───────────────

    private android.view.ActionMode.Callback mTextSelectionCallback;

    /**
     * Adds the app's own actions to the text-selection toolbar of a surface
     * browser. The toolbar keeps its own items (copy, paste, share, web
     * search, text processing); {@code callback} is called after them in
     * onCreateActionMode and onPrepareActionMode to add or change items, first
     * in onActionItemClicked (return true to take the click), and in
     * onDestroyActionMode. Read the selection with {@link #getSelectedText}.
     * Null removes it. An off-screen browser shows no toolbar; see
     * {@link CefriumRenderHandler#onQuickMenu}.
     *
     * <p>Experimental: it may still change before the 1.0 API freeze.</p>
     */
    public void setTextSelectionActionModeCallback(android.view.ActionMode.Callback callback) {
        checkThread("setTextSelectionActionModeCallback");
        mTextSelectionCallback = callback;
    }

    android.view.ActionMode.Callback textSelectionCallback() {
        return mTextSelectionCallback;
    }

    /**
     * The text selected in the page of a surface browser, or an empty string.
     *
     * <p>Experimental: it may still change before the 1.0 API freeze.</p>
     */
    public String getSelectedText() {
        checkThread("getSelectedText");
        if (!isLive() || mSurfaceContainer == null) return "";
        org.chromium.content_public.browser.WebContents wc = nativeGetWebContents(mNativePtr);
        if (wc == null) return "";
        String text = org.chromium.content_public.browser.SelectionPopupController
                .fromWebContents(wc).getSelectedText();
        return text == null ? "" : text;
    }

    // ─────────────── Printing (FEATURE-087) ───────────────

    /** Takes a page's {@code window.print()}. */
    public interface PrintHandler {
        /**
         * The page called {@code window.print()}. Return true if you print it
         * yourself, for example with {@link #createPrintDocumentAdapter};
         * return false for the default, the system print dialog on this
         * browser's activity. Called on the main thread.
         */
        boolean onPrintRequested();
    }

    private PrintHandler mPrintHandler;

    /**
     * Sets the handler for the page's {@code window.print()}. Null (the
     * default) shows the system print dialog on this browser's activity (a
     * surface browser's own, or the one given to {@link #setDialogActivity});
     * with neither, {@code window.print()} does nothing.
     *
     * <p>Experimental: it may still change before the 1.0 API freeze.</p>
     */
    public void setPrintHandler(PrintHandler handler) {
        checkThread("setPrintHandler");
        mPrintHandler = handler;
    }

    /**
     * The current page for Android's print framework ({@code PrintManager}):
     * printed by the engine, with the page's print styles, at the paper size
     * and orientation the user picks. As Android WebView's method of the same
     * name.
     *
     * <p>Experimental: it may still change before the 1.0 API freeze.</p>
     *
     * @param documentName the document name the print dialog shows.
     */
    public android.print.PrintDocumentAdapter createPrintDocumentAdapter(String documentName) {
        checkThread("createPrintDocumentAdapter");
        return new CefriumPrintAdapter(this, documentName);
    }

    /**
     * Opens the system print dialog for the current page, on this browser's
     * activity.
     *
     * <p>Experimental: it may still change before the 1.0 API freeze.</p>
     *
     * @throws IllegalStateException if the browser has no activity: a surface
     *         browser's own, or one given to {@link #setDialogActivity}.
     */
    public void print() {
        checkThread("print");
        android.app.Activity activity = dialogActivity();
        if (activity == null) {
            throw new IllegalStateException(
                    "print needs an activity: call setDialogActivity on an off-screen browser");
        }
        String name = mLastTitle == null || mLastTitle.isEmpty() ? "page" : mLastTitle;
        android.print.PrintManager manager = (android.print.PrintManager)
                activity.getSystemService(android.content.Context.PRINT_SERVICE);
        manager.print(name, createPrintDocumentAdapter(name), null);
    }

    // Called from native (UI thread) when the page calls window.print().
    // Returns false when nothing will print it.
    private boolean onScriptedPrint() {
        if (mClosed) return false;
        PrintHandler h = mPrintHandler;
        if (h != null && h.onPrintRequested()) return true;
        if (dialogActivity() == null) return false;
        // After the page's print event returns, as a browser does.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (!mClosed) print();
        });
        return true;
    }

    java.io.File cacheDirForPrinting() {
        return org.chromium.base.ContextUtils.getApplicationContext().getCacheDir();
    }

    // Called from native (UI thread) when PrintToPDF finishes.
    private void onPdfPrinted(long requestId, String filePath, boolean ok) {
        PdfCallback cb = mPdfCallbacks.remove(requestId);
        if (cb != null) cb.onPdfPrinted(filePath, ok);
    }

    // ──────────────────── Dynamic WebSettings ────────────────────

    /**
     * Overrides the User-Agent string (Emulation.setUserAgentOverride), for
     * the documents loaded from now on. Can be called right after the
     * constructor. A window the page opens keeps it from its first request.
     * It does not change User-Agent Client Hints
     * ({@code navigator.userAgentData}); for those, call
     * {@code Emulation.setUserAgentOverride} with {@code userAgentMetadata}
     * through {@link #executeDevToolsMethod}.
     */
    public void setUserAgentOverride(String userAgent) {
        checkThread("setUserAgentOverride");
        if (isLive()) nativeSetUserAgentOverride(mNativePtr, userAgent);
    }

    /**
     * Enables or disables JavaScript (Emulation.setScriptExecutionDisabled).
     * Can be called right after the constructor.
     */
    public void setJavaScriptEnabled(boolean enabled) {
        checkThread("setJavaScriptEnabled");
        if (isLive()) nativeSetJavaScriptEnabled(mNativePtr, enabled);
    }

    /** Sets the zoom level (0 = 100%; factor = 1.2^level). */
    public void setZoomLevel(double zoomLevel) {
        checkThread("setZoomLevel");
        if (isLive()) nativeSetZoomLevel(mNativePtr, zoomLevel);
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
        checkThread("setPinchToZoomEnabled");
        mPinchToZoomEnabled = enabled;
        // Apply immediately to the loaded document (no reload). Disabling injects
        // the neutralizer; enabling reverts it in-place.
        if (isLive()) {
            evaluateJavaScript(enabled ? PINCH_ZOOM_ENABLE_JS : PINCH_ZOOM_DISABLE_JS);
        }
    }

    /** @return whether pinch/page zoom is currently enabled (default true). */
    public boolean isPinchToZoomEnabled() {
        checkThread("isPinchToZoomEnabled");
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
        checkThread("loadHtml");
        if (!isLive() || html == null) return;
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
        checkThread("loadDataWithBaseUrl");
        if (!isLive() || baseUrl == null || data == null) return;
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
        checkThread("loadHtmlWithBaseUrl");
        loadDataWithBaseUrl(baseUrl, html, "text/html", "utf-8", baseUrl);
    }

    /** Loads {@code url} with an HTTP POST body. */
    public void postUrl(String url, byte[] postData) {
        checkThread("postUrl");
        if (isLive()) nativePostUrl(mNativePtr, url, postData);
    }

    /** Stops the current load. */
    public void stopLoading() {
        checkThread("stopLoading");
        if (isLive()) nativeStopLoading(mNativePtr);
    }

    // ─────────────── Request interceptor ───────────────

    /**
     * Decides whether to block outgoing requests. Invoked on a background
     * (network) thread for every request — must be fast and thread-safe, and
     * must not touch the UI. Consulted for requests a loaded ad-block list did
     * not already block (see {@link Cefrium#loadAdBlockList}).
     */
    /** @deprecated Use {@link CefriumResourceInterceptor}. */
    @Deprecated
    public interface RequestInterceptor extends CefriumResourceInterceptor {
        /** Return true to block (cancel) this request. */
        boolean shouldBlockRequest(String url, String method, boolean isMainFrame);

        /**
         * Return true to block (cancel) this request. Override this one to see
         * the resource type and the documents behind the request; the browser
         * calls it, and by default it calls the three-argument form.
         */
        @Override
        default boolean shouldBlockRequest(ResourceRequest request) {
            return shouldBlockRequest(request.url, request.method, request.isMainFrame);
        }
    }

    /** A request as {@link CefriumResourceInterceptor} sees it. */
    public static final class ResourceRequest {
        // The TYPE_ constants are the engine's numbers, deprecated with
        // resourceType: use getType() and CefriumResourceType.
        /** A page loaded into the main frame. */
        @Deprecated
        public static final int TYPE_MAIN_FRAME = 0;
        /** A page loaded into an iframe. */
        @Deprecated
        public static final int TYPE_SUB_FRAME = 1;
        @Deprecated
        public static final int TYPE_STYLESHEET = 2;
        @Deprecated
        public static final int TYPE_SCRIPT = 3;
        @Deprecated
        public static final int TYPE_IMAGE = 4;
        @Deprecated
        public static final int TYPE_FONT = 5;
        /** Anything else a page loads that has no type of its own. */
        @Deprecated
        public static final int TYPE_SUB_RESOURCE = 6;
        /** {@code <object>} and {@code <embed>}. */
        @Deprecated
        public static final int TYPE_OBJECT = 7;
        /** Audio and video. */
        @Deprecated
        public static final int TYPE_MEDIA = 8;
        @Deprecated
        public static final int TYPE_WORKER = 9;
        @Deprecated
        public static final int TYPE_SHARED_WORKER = 10;
        @Deprecated
        public static final int TYPE_PREFETCH = 11;
        @Deprecated
        public static final int TYPE_FAVICON = 12;
        /** {@code fetch()} and {@code XMLHttpRequest}. */
        @Deprecated
        public static final int TYPE_XHR = 13;
        /** {@code navigator.sendBeacon} and {@code <a ping>}. */
        @Deprecated
        public static final int TYPE_PING = 14;
        @Deprecated
        public static final int TYPE_SERVICE_WORKER = 15;
        @Deprecated
        public static final int TYPE_CSP_REPORT = 16;
        @Deprecated
        public static final int TYPE_PLUGIN_RESOURCE = 17;
        @Deprecated
        public static final int TYPE_NAVIGATION_PRELOAD_MAIN_FRAME = 19;
        @Deprecated
        public static final int TYPE_NAVIGATION_PRELOAD_SUB_FRAME = 20;

        public final String url;
        public final String method;
        /** Whether the main frame made the request (true for its own navigation). */
        public final boolean isMainFrame;
        /**
         * One of the {@code TYPE_} constants.
         * @deprecated Use {@link #getType()}: these numbers are the engine's
         *     and may change with it.
         */
        @Deprecated
        public final int resourceType;
        /**
         * The document that made the request: for a subresource the page that
         * loads it, for an iframe the page that holds it, for a main-frame
         * navigation the request's own URL. For a subresource, only the
         * page's origin ({@code https://host/}) when the page has no URL of
         * its own (an iframe {@code srcdoc}) or when the request starts before
         * the browser has seen the page commit, as a page's first requests
         * can. Empty when unknown.
         */
        public final String documentUrl;
        /**
         * The page in the main frame: the request's own URL for a main-frame
         * navigation, otherwise the main frame's URL when the request starts
         * (during a navigation, a late request of the old page can carry the
         * new page's URL). Empty when unknown.
         */
        public final String topLevelUrl;

        private final CefriumResourceType mType;

        ResourceRequest(String url, String method, boolean isMainFrame, int resourceType,
                CefriumResourceType type, String documentUrl, String topLevelUrl) {
            this.url = url;
            this.method = method;
            this.isMainFrame = isMainFrame;
            this.resourceType = resourceType;
            mType = type;
            this.documentUrl = documentUrl;
            this.topLevelUrl = topLevelUrl;
        }

        /** What the request is for. */
        public CefriumResourceType getType() {
            return mType;
        }
    }

    private volatile CefriumResourceInterceptor mRequestInterceptor;
    private static boolean sContentHostReady;

    /**
     * Sets the interceptor that sees, and may cancel or answer, every request
     * of this browser (null removes it). The last one set wins, including one
     * set with {@link #setRequestInterceptor}.
     */
    public void setResourceInterceptor(CefriumResourceInterceptor interceptor) {
        checkThread("setResourceInterceptor");
        // The engine finds ContentServerHost from here: its threads cannot
        // look up app classes.
        if (!sContentHostReady && overridesInterceptRequest(interceptor)) {
            sContentHostReady = nativeInitContentHost(ContentServerHost.class);
        }
        mRequestInterceptor = interceptor;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
        updateRequestObservers();
    }

    /**
     * Sets a programmatic request interceptor (null clears it).
     * @deprecated Use {@link #setResourceInterceptor}.
     */
    @Deprecated
    public void setRequestInterceptor(RequestInterceptor interceptor) {
        checkThread("setRequestInterceptor");
        mRequestInterceptor = interceptor;
        // Ensure native holds this Java ref so the resource handler can call
        // onShouldBlockRequest — independent of whether a display callback was set.
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
        updateRequestObservers();
    }

    // Called from native (IO thread) for every request not already ad-blocked.
    private boolean onShouldBlockRequest(String url, String method, boolean isMainFrame,
            int resourceType, int type, String documentUrl, String topLevelUrl) {
        CefriumResourceInterceptor i = mRequestInterceptor;
        return i != null && i.shouldBlockRequest(new ResourceRequest(url, method,
                isMainFrame, resourceType, CefriumResourceType.values()[type], documentUrl,
                topLevelUrl));
    }

    // Called from native (IO thread) when the interceptor overrides
    // shouldInterceptRequest; decision |id| waits for nativeOnInterceptDecision.
    // An interceptor that throws crashes the app, as WebView's does, after the
    // request is let go.
    @SuppressWarnings("unused")
    private void onInterceptRequest(long id, String method, String url, String[] headers,
            byte[] body) {
        CefriumResourceInterceptor i = mRequestInterceptor;
        ContentServerHost.execute(() -> {
            CefriumContentResponse response = null;
            try {
                if (i != null && !mClosed) {
                    response = i.shouldInterceptRequest(
                            ContentServerHost.requestOf(method, url, headers, body));
                }
            } finally {
                final CefriumContentResponse answer = response;
                nativeOnInterceptDecision(id, answer == null ? null : request -> answer);
            }
        });
    }

    // ─────────────── JS dialogs ───────────────

    /** alert(). @deprecated Use {@link CefriumJsDialogType#ALERT}. */
    @Deprecated
    public static final int JS_DIALOG_ALERT = 0;
    /** confirm(). @deprecated Use {@link CefriumJsDialogType#CONFIRM}. */
    @Deprecated
    public static final int JS_DIALOG_CONFIRM = 1;
    /** prompt(). @deprecated Use {@link CefriumJsDialogType#PROMPT}. */
    @Deprecated
    public static final int JS_DIALOG_PROMPT = 2;

    /** Resolves a JS dialog. */
    public interface JsDialogCallback {
        void continueDialog(boolean success, String userInput);
    }

    /**
     * Handles page JS dialogs (alert/confirm/prompt). Return true if handled
     * (you MUST call the callback); return false to use the default Android
     * AlertDialog. Without a handler set, the default AlertDialog is shown.
     */
    /** @deprecated Use {@link CefriumChromeClient#onJsDialog}. */
    @Deprecated
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

    /**
     * Sets a custom JS-dialog handler (null = default Android AlertDialog). A
     * {@link CefriumChromeClient} decides instead when one is set.
     * @deprecated Use {@link CefriumChromeClient#onJsDialog}.
     */
    @Deprecated
    public void setJsDialogHandler(JsDialogHandler handler) {
        checkThread("setJsDialogHandler");
        mJsDialogHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }

    // Called from native (UI thread) when the page opens alert/confirm/prompt.
    // |type| is a CefriumJsDialogType ordinal (= JS_DIALOG_), mapped in native.
    @SuppressWarnings("deprecation")
    private void onJsDialog(int type, long token, String message, String defaultPrompt) {
        JsDialogCallback cb = jsDialogAnswer(token, true);
        CefriumChromeClient chrome = mChromeClient;
        if (chrome != null) {
            if (chrome.onJsDialog(this, CefriumJsDialogType.values()[type], message,
                    defaultPrompt == null ? "" : defaultPrompt, cb)) {
                return;
            }
        } else {
            JsDialogHandler h = mJsDialogHandler;
            if (h != null && h.onJsDialog(type, message, defaultPrompt, cb)) return;
        }
        android.app.Activity activity = dialogActivity();
        if (activity == null) { cb.continueDialog(false, ""); return; }
        activity.runOnUiThread(() -> showDefaultJsDialog(activity, type, message, defaultPrompt, cb));
    }

    // ----- Pickers and default dialogs (issue #16) ---------------------------

    private CefriumPickerHandler mPickerHandler;
    private android.app.Activity mDialogActivity;

    /**
     * Sets the handler for the {@code <select>}, date/time and colour pickers
     * of an off-screen browser. See {@link CefriumPickerHandler}.
     */
    public void setPickerHandler(CefriumPickerHandler handler) {
        checkThread("setPickerHandler");
        mPickerHandler = handler;
    }

    /**
     * The activity on which an off-screen browser shows its default dialogs:
     * JS alert/confirm/prompt, the {@code <select>}, date/time and colour
     * pickers, and the file chooser. Without one, and without a handler for
     * them, they are cancelled. A surface browser uses its own activity.
     */
    public void setDialogActivity(android.app.Activity activity) {
        checkThread("setDialogActivity");
        mDialogActivity = activity;
    }

    private android.app.Activity dialogActivity() {
        return mActivity != null ? mActivity : mDialogActivity;
    }

    // Called from native (UI thread) when a <select> opens in a browser
    // without a window. Returns false to cancel.
    private boolean onShowSelect(long id, String[] labels, int[] types, boolean[] enabled,
            boolean[] checked, int selected, boolean multiple) {
        CefriumPickerHandler.SelectItem[] items = new CefriumPickerHandler.SelectItem[labels.length];
        for (int i = 0; i < labels.length; i++) {
            items[i] = new CefriumPickerHandler.SelectItem(labels[i], types[i], enabled[i], checked[i]);
        }
        final boolean[] answered = {false};
        CefriumPickerHandler.SelectCallback cb = new CefriumPickerHandler.SelectCallback() {
            @Override public void select(int[] indices) { answer(indices); }
            @Override public void cancel() { answer(null); }
            private void answer(int[] indices) {
                if (answered[0]) return;
                answered[0] = true;
                nativePickerSelectResult(id, indices);
            }
        };
        CefriumPickerHandler h = mPickerHandler;
        if (h != null && h.onShowSelect(items, selected, multiple, cb)) return true;
        android.app.Activity activity = dialogActivity();
        if (activity == null) return false;
        activity.runOnUiThread(
                () -> CefriumPickerDialogs.showSelect(activity, items, selected, multiple, cb));
        return true;
    }

    private boolean onShowDateTime(long id, int type, double value, double minimum,
            double maximum, double step) {
        final boolean[] answered = {false};
        CefriumPickerHandler.DateTimeCallback cb = new CefriumPickerHandler.DateTimeCallback() {
            @Override public void set(double v) { answer(true, v); }
            @Override public void cancel() { answer(false, 0); }
            private void answer(boolean ok, double v) {
                if (answered[0]) return;
                answered[0] = true;
                nativePickerDateTimeResult(id, ok, v);
            }
        };
        CefriumPickerHandler h = mPickerHandler;
        if (h != null && h.onShowDateTime(type, value, minimum, maximum, step, cb)) return true;
        android.app.Activity activity = dialogActivity();
        if (activity == null) return false;
        activity.runOnUiThread(() -> CefriumPickerDialogs.showDateTime(
                activity, type, value, minimum, maximum, step, cb));
        return true;
    }

    private boolean onShowColor(long id, int color, int[] suggestions) {
        final boolean[] answered = {false};
        CefriumPickerHandler.ColorCallback cb = new CefriumPickerHandler.ColorCallback() {
            @Override public void set(int c) { answer(true, c); }
            @Override public void cancel() { answer(false, 0); }
            private void answer(boolean ok, int c) {
                if (answered[0]) return;
                answered[0] = true;
                nativePickerColorResult(id, ok, c);
            }
        };
        CefriumPickerHandler h = mPickerHandler;
        if (h != null && h.onShowColor(color, suggestions, cb)) return true;
        android.app.Activity activity = dialogActivity();
        if (activity == null) return false;
        activity.runOnUiThread(
                () -> CefriumPickerDialogs.showColor(activity, color, suggestions, cb));
        return true;
    }

    // ── Permissions ──

    // The PERMISSION_ bits are Cefrium's own values, kept as published; the
    // engine's bits are mapped to them in cefrium_sdk_dialogs.h. Deprecated
    // with PermissionHandler: use CefriumPermission and CefriumChromeClient.

    /** @deprecated Use {@link CefriumPermission#GEOLOCATION}. */
    @Deprecated
    public static final int PERMISSION_GEOLOCATION = 1 << 8;
    /** @deprecated Use {@link CefriumPermission#CAMERA}. */
    @Deprecated
    public static final int PERMISSION_CAMERA = 1 << 2;
    /** @deprecated Use {@link CefriumPermission#MICROPHONE}. */
    @Deprecated
    public static final int PERMISSION_MICROPHONE = 1 << 12;
    /** @deprecated Use {@link CefriumPermission#NOTIFICATIONS}. */
    @Deprecated
    public static final int PERMISSION_NOTIFICATIONS = 1 << 15;
    /**
     * Clipboard read (navigator.clipboard.readText). Writing needs no prompt.
     * @deprecated Use {@link CefriumPermission#CLIPBOARD_READ}.
     */
    @Deprecated
    public static final int PERMISSION_CLIPBOARD = 1 << 4;
    /**
     * Web NFC (NDEFReader scan/write). Also declare {@code android.permission.NFC}
     * in your manifest and have NFC enabled on the device. Grant this in your
     * {@link PermissionHandler} (or pre-authorize via {@link #setContentSetting}
     * with {@link #SETTING_ALLOW}).
     * @deprecated Use {@link CefriumPermission#NFC}.
     */
    @Deprecated
    public static final int PERMISSION_NFC = 1 << 30;

    /**
     * Receives the app's grant/deny decision for a permission request.
     * @deprecated Use {@link CefriumPermissionRequest}.
     */
    @Deprecated
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
     *
     * @deprecated Use {@link CefriumChromeClient#onPermissionRequest}: a
     *     typed request instead of engine bits.
     */
    @Deprecated
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

    /**
     * Sets the permission handler (null = deny all web permission requests).
     * A {@link CefriumChromeClient} set with {@link #setChromeClient} decides
     * instead when there is one.
     * @deprecated Use {@link #setChromeClient}.
     */
    @Deprecated
    public void setPermissionHandler(PermissionHandler handler) {
        checkThread("setPermissionHandler");
        mPermissionHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }

    private volatile CefriumChromeClient mChromeClient;
    private final java.util.Map<Long, CefriumPermissionRequest> mPendingPermissions =
            new java.util.HashMap<>();

    /**
     * Set the client that answers what pages ask of the app (see
     * {@link CefriumChromeClient}), or null to remove it. The last one set
     * wins. It decides permission requests instead of a
     * {@link PermissionHandler}.
     */
    public void setChromeClient(CefriumChromeClient client) {
        checkThread("setChromeClient");
        mChromeClient = client;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }

    private static final int[] PERMISSION_BITS = {PERMISSION_GEOLOCATION, PERMISSION_CAMERA,
            PERMISSION_MICROPHONE, PERMISSION_NOTIFICATIONS, PERMISSION_CLIPBOARD,
            PERMISSION_NFC};
    private static final CefriumPermission[] PERMISSION_VALUES = {
            CefriumPermission.GEOLOCATION, CefriumPermission.CAMERA,
            CefriumPermission.MICROPHONE, CefriumPermission.NOTIFICATIONS,
            CefriumPermission.CLIPBOARD_READ, CefriumPermission.NFC};

    private static int permissionBit(CefriumPermission p) {
        for (int i = 0; i < PERMISSION_VALUES.length; i++) {
            if (PERMISSION_VALUES[i] == p) return PERMISSION_BITS[i];
        }
        throw new IllegalArgumentException("no setting for " + p);
    }

    // Answers a permission request on the UI thread, once.
    private void respondPermission(long promptId, boolean grant) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            MAIN_HANDLER.post(() -> respondPermission(promptId, grant));
            return;
        }
        mPendingPermissions.remove(promptId);
        if (mNativePtr != 0 && !mClosed) nativeRespondPermission(mNativePtr, promptId, grant);
    }

    // Called from native (UI thread) when a page requests a permission:
    // |permissions| are Cefrium's bits, |unmapped| the engine's bits that have
    // none (they reach a PermissionHandler as before, and a chrome client as
    // CefriumPermission.OTHER).
    @SuppressWarnings("unused")
    private void onPermissionRequest(long promptId, String origin, int permissions,
            int unmapped) {
        CefriumChromeClient client = mChromeClient;
        if (client != null) {
            java.util.EnumSet<CefriumPermission> set =
                    java.util.EnumSet.noneOf(CefriumPermission.class);
            for (int i = 0; i < PERMISSION_BITS.length; i++) {
                if ((permissions & PERMISSION_BITS[i]) != 0) set.add(PERMISSION_VALUES[i]);
            }
            if (unmapped != 0) set.add(CefriumPermission.OTHER);
            CefriumPermissionRequest request = new CefriumPermissionRequest(origin, set,
                    grant -> respondPermission(promptId, grant));
            mPendingPermissions.put(promptId, request);
            client.onPermissionRequest(this, request);
            return;
        }
        legacyPermissionRequest(promptId, origin, permissions | unmapped);
    }

    @SuppressWarnings("deprecation")
    private void legacyPermissionRequest(long promptId, String origin, int permissions) {
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

    /**
     * Content setting values for {@link #setContentSetting(String, int, int)}.
     * @deprecated Use {@link CefriumContentSetting}.
     */
    @Deprecated
    public static final int SETTING_DEFAULT = 0;
    /** @deprecated Use {@link CefriumContentSetting#ALLOW}. */
    @Deprecated
    public static final int SETTING_ALLOW = 1;
    /** @deprecated Use {@link CefriumContentSetting#BLOCK}. */
    @Deprecated
    public static final int SETTING_BLOCK = 2;
    /** @deprecated Use {@link CefriumContentSetting#ASK}. */
    @Deprecated
    public static final int SETTING_ASK = 3;

    /**
     * Stores a decision for {@code permission} on {@code origin}, so the page
     * is answered without asking: for example allow notifications for your own
     * web app. Applies to this browser's profile.
     *
     * @param origin e.g. {@code "https://example.com"}
     * @throws IllegalArgumentException for {@link CefriumPermission#OTHER}
     */
    public void setContentSetting(String origin, CefriumPermission permission,
            CefriumContentSetting value) {
        checkThread("setContentSetting");
        int bit = permissionBit(permission);
        if (isLive()) nativeSetContentSetting(mNativePtr, origin, bit, value.ordinal());
    }

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
     * @throws IllegalArgumentException for another permission or value (the
     *     clipboard is now its own setting; it used to set notifications)
     * @deprecated Use {@link #setContentSetting(String, CefriumPermission,
     *     CefriumContentSetting)}.
     */
    @Deprecated
    public void setContentSetting(String requestingUrl, int permission, int value) {
        checkThread("setContentSetting");
        boolean known = false;
        for (int bit : PERMISSION_BITS) known |= bit == permission;
        if (!known || value < SETTING_DEFAULT || value > SETTING_ASK) {
            throw new IllegalArgumentException(
                    "permission " + permission + ", value " + value);
        }
        if (isLive()) {
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
     * Sets the proxy of this browser's PROFILE: {@code getProfile().setProxy}.
     * Every browser of the profile uses it, including ones opened later, and
     * the default and named profiles keep it across restarts. It applies before
     * the browser's first navigation, so it can be called right after creating
     * the browser. See {@link CefriumProfile#setProxy(String, String)}.
     *
     * @return false if the engine rejected the setting.
     */
    public boolean setProxy(String mode, String server) {
        checkThread("setProxy");
        return mProfile.setProxy(mode, server);
    }

    /** Convenience: route all traffic through a fixed proxy {@code "host:port"}. */
    public boolean setProxy(String server) {
        checkThread("setProxy");
        return setProxy(PROXY_FIXED, server);
    }

    /** Convenience: disable proxying (direct connection). */
    public boolean clearProxy() {
        checkThread("clearProxy");
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

    // ---- FedCM account chooser (FEATURE-086) ----

    private CefriumIdentityHandler mIdentityHandler;
    private final java.util.Map<Long, IdentityRequestImpl> mIdentityRequests =
            new java.util.HashMap<>();

    /**
     * Sets the handler that shows the account chooser of FedCM sign-in
     * requests. Null (the default) shows a dialog on this browser's activity;
     * see {@link CefriumIdentityHandler}.
     *
     * <p>Experimental: it may still change before the 1.0 API freeze.</p>
     */
    public void setIdentityHandler(CefriumIdentityHandler handler) {
        checkThread("setIdentityHandler");
        mIdentityHandler = handler;
    }

    private final class IdentityRequestImpl implements CefriumIdentityHandler.Request {
        final long mId;
        final java.util.List<CefriumIdentityHandler.Account> mAccounts;
        CefriumIdentityHandler mShownBy;
        android.app.AlertDialog mDialog;
        boolean mDone;

        IdentityRequestImpl(long id, java.util.List<CefriumIdentityHandler.Account> accounts) {
            mId = id;
            mAccounts = accounts;
        }

        @Override public void select(CefriumIdentityHandler.Account account) {
            if (!mAccounts.contains(account)) {
                throw new IllegalArgumentException("not one of the accounts offered");
            }
            if (finish()) nativeIdentitySelect(mNativePtr, mId, account.id);
        }

        @Override public void cancel() {
            if (finish()) nativeIdentityCancel(mNativePtr, mId);
        }

        private boolean finish() {
            if (mDone) return false;
            mDone = true;
            mIdentityRequests.remove(mId);
            return mNativePtr != 0;
        }
    }

    // Called from native (UI thread) when a page asks to sign in with FedCM.
    // Returns false to fail the request.
    private boolean onShowAccountChooser(long id, String relyingParty, String[] ids,
            String[] names, String[] emails, String[] pictures, String[] idps,
            boolean[] returning) {
        java.util.List<CefriumIdentityHandler.Account> accounts = new java.util.ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            accounts.add(new CefriumIdentityHandler.Account(ids[i], names[i], emails[i],
                    pictures[i], idps[i], returning[i]));
        }
        accounts = java.util.Collections.unmodifiableList(accounts);
        IdentityRequestImpl request = new IdentityRequestImpl(id, accounts);
        mIdentityRequests.put(id, request);
        CefriumIdentityHandler h = mIdentityHandler;
        if (h != null && h.onShowAccountChooser(relyingParty, accounts, request)) {
            request.mShownBy = h;
            return true;
        }
        android.app.Activity activity = dialogActivity();
        if (activity == null) {
            mIdentityRequests.remove(id);
            return false;
        }
        request.mDialog = CefriumIdentityDialog.show(activity, relyingParty, accounts, request);
        return true;
    }

    // Called from native (UI thread): chooser |id| is no longer needed.
    private void onAccountChooserClosed(long id) {
        IdentityRequestImpl request = mIdentityRequests.remove(id);
        if (request == null || request.mDone) return;
        request.mDone = true;
        if (request.mShownBy != null) request.mShownBy.onAccountChooserClosed(request);
        if (request.mDialog != null) request.mDialog.dismiss();
    }

    /** Sets the device chooser handler (null = cancel all device requests). */
    public void setDeviceChooserHandler(DeviceChooserHandler handler) {
        checkThread("setDeviceChooserHandler");
        mDeviceChooserHandler = handler;
        if (isLive()) {
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
        checkThread("setSslErrorHandler");
        mSslErrorHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
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
        checkThread("setHttpAuthHandler");
        mHttpAuthHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
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

    private void showDefaultJsDialog(android.app.Activity activity, int type, String message,
                                     String defaultPrompt, JsDialogCallback cb) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(activity);
        b.setMessage(message);
        b.setCancelable(false);
        final android.widget.EditText input =
                (type == JS_DIALOG_PROMPT) ? new android.widget.EditText(activity) : null;
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
     * Handles popups (window.open / target=_blank). By default the target URL
     * is loaded in this browser, and the page loses its reference to the
     * popup. Sign-in and payment popups need that reference: implement
     * {@link #onCreateWindow} for them.
     */
    public interface PopupHandler {
        /** Return true if you handled it (e.g. opened a new tab); false to load
            the target URL in this browser. */
        boolean onOpenPopup(String targetUrl, boolean userGesture);

        /**
         * The page opened a window. {@code popup} is a new browser in this
         * browser's mode and profile that will load {@code targetUrl} as that
         * window: the page keeps {@code window.opener} and the popup can post
         * its result back, as sign-in popups do. Show it (its
         * {@link #getSurfaceContainer()}, or its frames if off-screen) and
         * return true; return false to fall back to {@link #onOpenPopup}.
         * As with Android WebView's {@code onCreateWindow}.
         *
         * <p>Experimental: it may still change before the 1.0 API freeze.</p>
         */
        default boolean onCreateWindow(CefriumBrowser popup, String targetUrl,
                boolean userGesture) {
            return false;
        }

        /**
         * A window from {@link #onCreateWindow} called {@code window.close()}:
         * remove it from the UI. It closes itself.
         *
         * <p>Experimental: it may still change before the 1.0 API freeze.</p>
         */
        default void onCloseWindow(CefriumBrowser popup) {}
    }

    // The handler that took this browser as a popup (onCloseWindow).
    private PopupHandler mPopupOwner;

    private PopupHandler mPopupHandler;

    /** Sets a popup handler (null = load popups in this browser). */
    public void setPopupHandler(PopupHandler handler) {
        checkThread("setPopupHandler");
        mPopupHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }

    // Called from native (UI thread) when the page opens window.open / target=_blank.
    private boolean onOpenPopup(String targetUrl, boolean userGesture) {
        PopupHandler h = mPopupHandler;
        return h != null && h.onOpenPopup(targetUrl, userGesture);
    }

    // Called from native (UI thread) before onOpenPopup: the native browser of
    // the popup's window, or 0 when the app does not take it.
    private long onCreateWindow(String targetUrl, boolean userGesture) {
        PopupHandler h = mPopupHandler;
        if (h == null || mClosed) return 0;
        CefriumBrowser popup = mSurfaceContainer != null
                ? createSurfaceBrowser(mActivity, mProfile, true)
                : new CefriumBrowser(Math.max(1, mOsrWidth), Math.max(1, mOsrHeight),
                        mOsrScale, mProfile);
        boolean taken = false;
        try {
            taken = h.onCreateWindow(popup, targetUrl, userGesture);
        } finally {
            if (!taken) popup.close();
        }
        if (!popup.isLive()) return 0;
        popup.mPopupOwner = h;
        popup.mDialogActivity = mDialogActivity;
        return popup.mNativePtr;
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
        checkThread("canGoBack");
        return isLive() && nativeCanGoBack(mNativePtr);
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
        checkThread("suspendMedia");
        org.chromium.content_public.browser.WebContents wc = webContents();
        if (wc != null) {
            org.chromium.content_public.browser.MediaSession ms =
                    org.chromium.content_public.browser.MediaSession.fromWebContents(wc);
            if (ms != null) ms.suspend(org.chromium.media_session.mojom.MediaSession.SuspendType.UI);
        }
    }

    /** Resumes media suspended by {@link #suspendMedia()} (resumes where it left off). */
    public void resumeMedia() {
        checkThread("resumeMedia");
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
        checkThread("setAudioMuted");
        org.chromium.content_public.browser.WebContents wc = webContents();
        if (wc != null) wc.setAudioMuted(muted);
    }

    /** Whether the page's audio is currently muted via {@link #setAudioMuted}. */
    public boolean isAudioMuted() {
        checkThread("isAudioMuted");
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
        checkThread("isPlayingAudio");
        return mMediaSession != null && mMediaSession.isPlayingAudio();
    }

    /**
     * Sets the page's visibility (compositor + Page Visibility API
     * {@code document.hidden}). HIDDEN saves GPU/battery and fires
     * {@code visibilitychange}, but does NOT by itself stop audio.
     */
    public void setPageVisible(boolean visible) {
        checkThread("setPageVisible");
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
        checkThread("stopAllMedia");
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
        checkThread("stopMediaSession");
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
        checkThread("onPause");
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
        checkThread("onResume");
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
        if (!Cefrium.isInitialized()) return;
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
        return Cefrium.isInitialized() ? nativeActiveRenderProcessCount() : 0;
    }

    /**
     * Renderer processes that currently host ZERO frames -- reuse "zombies" the
     * pool's reap targets. A high value while a pool runs means orphaned renderers
     * are lingering (reusable but not freed). Main thread only (-1 otherwise).
     * Diagnostic.
     */
    public static int zeroFrameRenderProcessCount() {
        return Cefrium.isInitialized() ? nativeZeroFrameRenderProcessCount() : 0;
    }

    /**
     * Total live RenderFrameHosts across all renderer processes -- the real
     * "working frame" count (host pages + iframes). If this grows as you open pages
     * and never drops, the closed pages' frames are NOT being destroyed. Main
     * thread only (-1 otherwise). Diagnostic.
     */
    public static int totalRenderFrameHostCount() {
        return Cefrium.isInitialized() ? nativeTotalRenderFrameHostCount() : 0;
    }

    /**
     * Cumulative number of browsers that have finished closing (their
     * {@code OnBeforeClose} fired). If this does NOT grow as a pool evicts
     * browsers, {@link #close()} is not completing -- the page leaks. Diagnostic.
     */
    public static int closedBrowserCount() {
        return Cefrium.isInitialized() ? nativeClosedBrowserCount() : 0;
    }

    /** Live (not-yet-closed) browser instances. Diagnostic. */
    public static int liveBrowserCount() {
        return Cefrium.isInitialized() ? nativeLiveBrowserCount() : 0;
    }

    /**
     * Close this browser and release all native resources.
     * After calling this method, no other methods may be called on this instance.
     * For Surface mode, also removes the rendering views.
     */
    public void close() {
        checkThread("close");
        if (mClosed) return;
        mClosed = true;
        CefriumProfile.browserClosed(mProfile);
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
        failPendingDevToolsCalls();
        // The container may stay on screen after close (an app closing the
        // visible tab). Remove both views before destroying anything, as
        // content_shell's removeShell does: a ContentViewRenderView left in the
        // layout keeps its WebContents, and the next layout calls setSize on it
        // ("Native WebContents already destroyed"), killing the app process.
        // setCurrentWebContents(null) is not an option: its native side
        // rejects null.
        if (mSurfaceContainer != null) {
            mSurfaceContainer.setWebContents(null);
            if (mContentView != null) mSurfaceContainer.removeView(mContentView);
        }
        mContentView = null;
        if (mContentViewRenderView != null) {
            if (mSurfaceContainer != null) {
                mSurfaceContainer.removeView(mContentViewRenderView);
            }
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
    private void onTouchHandleStateChanged(int id, int orientation, boolean mirrorVertical,
            boolean mirrorHorizontal, int x, int y, float alpha, boolean enabled) {
        final CefriumRenderHandler handler = mRenderHandler;
        if (handler == null) return;
        final CefriumRenderHandler.TouchHandle handle = new CefriumRenderHandler.TouchHandle(
                id, enabled, orientation, mirrorVertical, mirrorHorizontal, x, y, alpha);
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> handler.onTouchHandleChanged(handle));
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
        checkThread("captureBitmap");
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
     * Resizes an off-screen browser, for example when the view that shows its
     * frames changes size. The page lays out again at the new size, gets a
     * {@code resize} event, and the following {@code onPaint} frames have the
     * new dimensions. No effect on a surface browser, which follows its view.
     *
     * @param width  Viewport width in physical pixels, as in the constructor.
     * @param height Viewport height in physical pixels.
     */
    public void setSize(int width, int height) {
        checkThread("setSize");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        mOsrWidth = width;
        mOsrHeight = height;
        if (checkNative("setSize")) {
            nativeSetSize(mNativePtr, width, height);
        }
    }

    /**
     * Force the browser to repaint. Call this if you need
     * an updated frame (e.g. after content changes).
     */
    public void invalidate() {
        checkThread("invalidate");
        if (isLive()) {
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
        checkThread("imeSetComposition");
        if (isLive()) {
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
        checkThread("imeCommitText");
        if (isLive()) {
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
        checkThread("imeFinishComposingText");
        if (isLive()) {
            nativeImeFinishComposingText(mNativePtr, keepSelection);
        }
    }

    /**
     * Cancel the current composition, discarding the composed text.
     */
    public void imeCancelComposition() {
        checkThread("imeCancelComposition");
        if (isLive()) {
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
        checkThread("sendKeyEvent");
        if (isLive()) {
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
        checkThread("sendKeyEvent");
        if (!isLive() || event == null) return;
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
     * Forward a touch event to an off-screen browser.
     *
     * <p>Coordinates are device-independent pixels from the view's upper-left
     * corner: the view's physical pixels divided by the {@code scale} the
     * browser was created with. At scale 1 the two are the same.
     *
     * @param action 0=DOWN, 1=UP, 2=MOVE, 3=CANCEL
     * @param x X coordinate in device-independent pixels
     * @param y Y coordinate in device-independent pixels
     * @param pointerId Pointer ID for multi-touch
     */
    public void sendTouchEvent(int action, float x, float y, int pointerId) {
        checkThread("sendTouchEvent");
        if (isLive()) {
            nativeSendTouchEvent(mNativePtr, action, x, y, pointerId);
        }
    }

    // ----- Touch automation ---------------------------------------------------
    // Programmatic single-finger touch injection: tap, plus low-level down/move/up
    // for drags and swipes -- mirroring the mouse API. Coordinates start at the
    // view's upper-left corner; see sendTouchDown for the units. These drive the
    // full gesture pipeline:
    // a tap becomes a click, a down-move-up becomes a scroll/fling. In windowed
    // (createWithSurface) mode the events go through the content view's
    // EventForwarder (same path as a real finger); in windowless (OSR) mode they
    // go through the CEF OSR touch path. Call on the UI thread, after the page has
    // started loading.

    /** Press a finger down at (x, y). Pair with {@link #sendTouchUp} (with
     * {@link #sendTouchMove} in between) for drags/swipes, or use {@link #sendTap}.
     * Coordinates are the view's physical pixels for a surface browser, and
     * device-independent pixels (physical / scale) for an off-screen one. */
    public void sendTouchDown(float x, float y) {
        checkThread("sendTouchDown"); injectTouch(0, x, y); }

    /** Move the pressed finger to (x, y). */
    public void sendTouchMove(float x, float y) {
        checkThread("sendTouchMove"); injectTouch(2, x, y); }

    /** Lift the finger at (x, y). */
    public void sendTouchUp(float x, float y) {
        checkThread("sendTouchUp"); injectTouch(1, x, y); }

    /** Tap at (x, y): a finger down immediately followed by up. Synthesizes a
     * click on the element at that point (the touch-automation equivalent of
     * {@link #sendLeftClick}). */
    public void sendTap(float x, float y) {
        checkThread("sendTap");
        sendTouchDown(x, y);
        sendTouchUp(x, y);
    }

    /**
     * Swipe from (x1, y1) to (x2, y2) as a down + {@code steps} moves + up --
     * drives scrolling/flinging. {@code steps} controls smoothness (>= 1).
     */
    public void sendSwipe(float x1, float y1, float x2, float y2, int steps) {
        checkThread("sendSwipe");
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
            // A finger, so the page sees pointerType "touch" (obtain(x, y)
            // leaves the tool type unknown, which the page sees as "").
            android.view.MotionEvent.PointerProperties props =
                    new android.view.MotionEvent.PointerProperties();
            props.id = 0;
            props.toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;
            android.view.MotionEvent.PointerCoords coords =
                    new android.view.MotionEvent.PointerCoords();
            coords.x = x;
            coords.y = y;
            coords.pressure = 1f;
            coords.size = 1f;
            android.view.MotionEvent ev = android.view.MotionEvent.obtain(
                    mTouchDownTime, now, androidAction, 1,
                    new android.view.MotionEvent.PointerProperties[] {props},
                    new android.view.MotionEvent.PointerCoords[] {coords},
                    0, 0, 1f, 1f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
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
    // Exposed the same way as sendTouchEvent above. Coordinates are
    // device-independent pixels from the view's upper-left corner, for both a
    // surface and an off-screen browser. Useful for automation/testing, a
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
     * @param x X coordinate in device-independent pixels.
     * @param y Y coordinate in device-independent pixels.
     */
    public void sendMouseMove(int x, int y) {
        checkThread("sendMouseMove");
        sendMouseMove(x, y, 0);
    }

    /** Move the pointer to (x, y) with modifier keys held (see MOUSE_MODIFIER_*). */
    public void sendMouseMove(int x, int y, int modifiers) {
        checkThread("sendMouseMove");
        if (isLive()) {
            nativeSendMouseMoveEvent(mNativePtr, x, y, modifiers, false);
        }
    }

    /** Signal the pointer leaving the view at (x, y) (clears {@code :hover}). */
    public void sendMouseLeave(int x, int y) {
        checkThread("sendMouseLeave");
        if (isLive()) {
            nativeSendMouseMoveEvent(mNativePtr, x, y, 0, true);
        }
    }

    /**
     * Click at (x, y) with the given button (down + up).
     *
     * @param button one of MOUSE_BUTTON_LEFT / MOUSE_BUTTON_MIDDLE / MOUSE_BUTTON_RIGHT.
     */
    public void sendMouseClick(int x, int y, int button) {
        checkThread("sendMouseClick");
        sendMouseClick(x, y, button, 1, 0);
    }

    /**
     * Click at (x, y) with a button, click count, and modifiers (down + up).
     *
     * @param clickCount 1 = single, 2 = double, 3 = triple.
     * @param modifiers OR of MOUSE_MODIFIER_* (0 = none).
     */
    public void sendMouseClick(int x, int y, int button, int clickCount, int modifiers) {
        checkThread("sendMouseClick");
        if (!isLive()) return;
        nativeSendMouseClickEvent(mNativePtr, x, y, modifiers, button, false, clickCount);
        nativeSendMouseClickEvent(mNativePtr, x, y, modifiers, button, true, clickCount);
    }

    /** Left click at (x, y). */
    public void sendLeftClick(int x, int y) {
        checkThread("sendLeftClick");
        sendMouseClick(x, y, MOUSE_BUTTON_LEFT, 1, 0);
    }

    /**
     * Right click at (x, y). Also opens the context menu, routed through
     * {@code CefContextMenuHandler} (see setOnContextMenuListener).
     */
    public void sendRightClick(int x, int y) {
        checkThread("sendRightClick");
        sendMouseClick(x, y, MOUSE_BUTTON_RIGHT, 1, 0);
    }

    /** Double left click at (x, y). */
    public void sendDoubleClick(int x, int y) {
        checkThread("sendDoubleClick");
        sendMouseClick(x, y, MOUSE_BUTTON_LEFT, 2, 0);
    }

    /**
     * Press a mouse button at (x, y) without releasing it. Pair with
     * {@link #sendMouseUp} (with intervening {@link #sendMouseMove} calls) to
     * synthesize a drag.
     */
    public void sendMouseDown(int x, int y, int button, int modifiers) {
        checkThread("sendMouseDown");
        if (isLive()) {
            nativeSendMouseClickEvent(mNativePtr, x, y, modifiers, button, false, 1);
        }
    }

    /** Release a mouse button at (x, y). */
    public void sendMouseUp(int x, int y, int button, int modifiers) {
        checkThread("sendMouseUp");
        if (isLive()) {
            nativeSendMouseClickEvent(mNativePtr, x, y, modifiers, button, true, 1);
        }
    }

    /**
     * Scroll the mouse wheel at (x, y). {@code deltaX}/{@code deltaY} are the
     * scroll deltas in pixels (a typical wheel notch is ~120).
     */
    public void sendMouseWheel(int x, int y, int deltaX, int deltaY) {
        checkThread("sendMouseWheel");
        sendMouseWheel(x, y, deltaX, deltaY, 0);
    }

    /** Scroll the mouse wheel at (x, y) with modifier keys held. */
    public void sendMouseWheel(int x, int y, int deltaX, int deltaY, int modifiers) {
        checkThread("sendMouseWheel");
        if (isLive()) {
            nativeSendMouseWheelEvent(mNativePtr, x, y, modifiers, deltaX, deltaY);
        }
    }

    /**
     * Turns ad blocking off or back on for this browser; on by default. It
     * only blocks once a list is loaded: nothing is built in (see
     * {@link Cefrium#loadAdBlockList} and {@link Cefrium#downloadAndLoadEasyList}).
     */
    public void setAdBlockEnabled(boolean enabled) {
        checkThread("setAdBlockEnabled");
        if (isLive()) nativeSetAdBlockEnabled(mNativePtr, enabled);
    }

    /**
     * The requests this browser cancelled since it was created: those matching
     * a loaded ad-block list plus those a {@link RequestInterceptor} blocked.
     * 0 while no list is loaded and no interceptor blocks anything.
     *
     * @return Number of blocked requests, or 0 if the browser is closed.
     */
    public int getBlockedCount() {
        checkThread("getBlockedCount");
        if (isLive()) {
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
        return createWithSurface(activity,
                incognito ? CefriumProfile.incognito() : CefriumProfile.getDefault());
    }

    /**
     * Like {@link #createWithSurface(android.app.Activity)}, using
     * {@code profile}'s cookies, storage and cache.
     *
     * @param activity The Activity to render in.
     * @param profile  e.g. {@code CefriumProfile.get("work")}.
     * @return A new CefriumBrowser instance with Surface rendering.
     */
    public static CefriumBrowser createWithSurface(android.app.Activity activity,
                                                   CefriumProfile profile) {
        return createSurfaceBrowser(activity, profile, false);
    }

    // A popup's native browser is created by CEF, from its opener.
    private static CefriumBrowser createSurfaceBrowser(android.app.Activity activity,
            CefriumProfile profile, boolean forPopup) {
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
        Cefrium.ensureInitialized();

        CefriumBrowser browser = new CefriumBrowser(true);

        // Create Chromium rendering infrastructure internally.
        // Use CefWindowAndroid (an ActivityWindowAndroid) to support intents —
        // required for the file picker (<input type=file>, File System Access),
        // permission requests, and other Activity-based flows — and to provide a
        // ModalDialogManager so tab-modal dialogs (e.g. the File System Access
        // folder-permission prompt) do not crash the native CHECK.
        // Results come back through an AndroidX activity's result registry;
        // another activity forwards them (onActivityResult).
        browser.mIntentTracker = ActivityResultIntentDelegate.createTracker(activity);
        browser.mWindowAndroid = new CefWindowAndroid(
                activity, true, browser.mIntentTracker, null, false);

        browser.mContentViewRenderView =
                new org.chromium.components.embedder_support.view.ContentViewRenderView(
                        activity) {
                    @Override
                    protected void onReadyToRender() {
                        browser.mRenderViewReady = true;
                        if (browser.mTransparentBackground) setOverlayVideoMode(true);
                    }
                };
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
        browser.mProfile = profile;
        browser.mIncognito = profile.isIncognito();
        browser.mNativePtr = nativeCreateWithSurface(
                browser.mWindowAndroid, null,
                activity.getResources().getDisplayMetrics().widthPixels,
                activity.getResources().getDisplayMetrics().heightPixels,
                density, profile.isIncognito(), profile.getName(), forPopup);
        CefriumProfile.browserOpened(profile);

        browser.mActivity = activity;
        // Browser creation is synchronous on Android: OnAfterCreated already ran
        // inside nativeCreateWithSurface, before any Java reference was bound,
        // so this binding is what delivers onNativeBrowserCreated. It does not
        // depend on the embedder setting a listener.
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
        checkThread("isIncognito");
        return mIncognito;
    }

    /** @return the profile whose cookies, storage and cache this browser uses. */
    public CefriumProfile getProfile() {
        checkThread("getProfile");
        return mProfile;
    }

    /**
     * Forwards an activity result to the browser, for the intents it starts
     * (the default file chooser, camera, document pickers). Needed only from
     * an activity that is not an AndroidX {@code ComponentActivity}: those
     * deliver the results by themselves, through their result registry.
     *
     * @return true if the result was the browser's.
     */
    public boolean onActivityResult(int requestCode, int resultCode,
                                    android.content.Intent data) {
        checkThread("onActivityResult");
        if (requestCode == DefaultFileChooser.REQUEST_CODE && mPendingFileDialog >= 0) {
            long id = mPendingFileDialog;
            mPendingFileDialog = -1;
            fileDialogDone(id, DefaultFileChooser.urisOf(resultCode, data));
            return true;
        }
        if (mIntentTracker != null) {
            return mIntentTracker.onActivityResult(requestCode, resultCode, data);
        }
        return false;
    }

    /**
     * Forwards a runtime permission result to the browser, for the
     * permissions the engine asks for (the camera, before the default file
     * chooser offers a capture). Needed only from an activity that is not an
     * AndroidX {@code ComponentActivity}, as {@link #onActivityResult}.
     *
     * @return true if the request was the browser's.
     */
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions,
                                              int[] grantResults) {
        checkThread("onRequestPermissionsResult");
        return mWindowAndroid != null
                && mWindowAndroid.handlePermissionResult(requestCode, permissions, grantResults);
    }

    /**
     * Get the container View for Surface rendering.
     * Add this to your Activity's layout.
     *
     * @return FrameLayout containing the browser surface.
     */
    public android.widget.FrameLayout getSurfaceContainer() {
        checkThread("getSurfaceContainer");
        return mSurfaceContainer;
    }

    /**
     * Enables system media controls (lock-screen + notification shade) for this
     * browser's playback, driven by the page's {@code navigator.mediaSession}.
     * Requires the {@code POST_NOTIFICATIONS} runtime permission on Android
     * 13+. Safe to call before or after the page loads.
     */
    public void setMediaSessionEnabled(boolean enabled) {
        checkThread("setMediaSessionEnabled");
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
        checkThread("setMediaSessionActivity");
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

        mContentView = cv;
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
     * @deprecated Use {@link CefriumChromeClient#onFindResult}.
     */
    @Deprecated
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
     * @deprecated Use {@link CefriumChromeClient#onFindResult}.
     */
    @Deprecated
    public void setOnFindResultListener(OnFindResultListener listener) {
        checkThread("setOnFindResultListener");
        mFindListener = listener;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
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
        checkThread("find");
        if (checkNative("find")) nativeFind(mNativePtr, text, forward, matchCase, findNext);
    }

    /**
     * Stop the current find-in-page search.
     * Uses CEF API: CefBrowserHost::StopFinding.
     *
     * @param clearSelection true to clear the selection highlight.
     */
    public void stopFinding(boolean clearSelection) {
        checkThread("stopFinding");
        if (checkNative("stopFinding")) nativeStopFinding(mNativePtr, clearSelection);
    }

    // Called from native CefFindHandler::OnFindResult.
    @SuppressWarnings("unused")
    private void onFindResult(int activeMatch, int totalMatches) {
        if (mFindListener != null) {
            mFindListener.onFindResult(activeMatch, totalMatches);
        }
        if (mCallback != null) mCallback.onFindResult(activeMatch, totalMatches);
        CefriumChromeClient chrome = mChromeClient;
        if (chrome != null) chrome.onFindResult(this, activeMatch, totalMatches);
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
        checkThread("setOnDownloadListener");
        mDownloadListener = listener;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
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
         * to save it under its suggested name in the app's own Downloads
         * directory, {@code Context.getExternalFilesDir(DIRECTORY_DOWNLOADS)})
         * or {@code callback.cancel()} to block it
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
        /**
         * The download stopped on an error and is neither complete nor
         * canceled; {@link #getInterruptReason()} says why.
         */
        public boolean isInterrupted() {
            return mInterruptReason != 0 && !mComplete && !mCanceled;
        }

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
        checkThread("setDownloadHandler");
        mDownloadHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
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
    public void cancelDownload(int id) {
        checkThread("cancelDownload"); controlDownload(id, 0); }

    /** Pause an in-progress download by its id. See {@link #cancelDownload}. */
    public void pauseDownload(int id) {
        checkThread("pauseDownload"); controlDownload(id, 1); }

    /** Resume a paused download by its id. See {@link #cancelDownload}. */
    public void resumeDownload(int id) {
        checkThread("resumeDownload"); controlDownload(id, 2); }

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
    private volatile CefriumRequestHandler mRequestHandler;
    private CefriumKeyboardHandler mKeyboardHandler;
    private CefriumFocusHandler mFocusHandler;
    private CefriumDragHandler mDragHandler;
    private CefriumAudioHandler mAudioHandler;
    private CefriumFrameHandler mFrameHandler;
    private CefriumCommandHandler mCommandHandler;
    private CefriumDialogHandler mDialogHandler;

    /**
     * Sets the display handler (favicon, progress, console, status, media).
     * @deprecated Use {@link CefriumChromeClient}.
     */
    @Deprecated
    public void setDisplayHandler(CefriumDisplayHandler handler) {
        checkThread("setDisplayHandler");
        mDisplayHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }
    /**
     * Sets the per-frame load handler (load start / error).
     * @deprecated Use {@link CefriumNavigationClient}.
     */
    @Deprecated
    public void setLoadHandler(CefriumLoadHandler handler) {
        checkThread("setLoadHandler");
        mLoadHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }
    /** Sets the request handler (open-in-tab, hung renderer, client cert, resource events). */
    /**
     * Enable the opt-in response-body tap ({@link CefriumRequestHandler#onResponseData}).
     * Off by default; enabling it copies each response body chunk across JNI
     * (a hot path). Requires a request handler set to receive the data.
     */
    public void setResponseTapEnabled(boolean enabled) {
        checkThread("setResponseTapEnabled");
        if (isLive()) nativeSetResponseTapEnabled(mNativePtr, enabled);
    }

    /**
     * Enable the opt-in per-cookie access filter
     * ({@link CefriumRequestHandler#onCanSendCookie}/{@code onCanSaveCookie}).
     * Off by default; enabling it calls the handler per cookie (a hot path).
     */
    public void setCookieFilterEnabled(boolean enabled) {
        checkThread("setCookieFilterEnabled");
        if (isLive()) nativeSetCookieFilterEnabled(mNativePtr, enabled);
    }

    public void setRequestHandler(CefriumRequestHandler handler) {
        checkThread("setRequestHandler");
        mRequestHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
        updateRequestObservers();
    }
    /** Sets the keyboard handler (intercept keys before the page). */
    public void setKeyboardHandler(CefriumKeyboardHandler handler) {
        checkThread("setKeyboardHandler");
        mKeyboardHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }
    /** Sets the focus handler. */
    public void setFocusHandler(CefriumFocusHandler handler) {
        checkThread("setFocusHandler");
        mFocusHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }
    /** Sets the drag handler. */
    public void setDragHandler(CefriumDragHandler handler) {
        checkThread("setDragHandler");
        mDragHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }
    /**
     * Sets the audio handler. OPT-IN and exclusive: a non-null handler REDIRECTS
     * the page's audio to it (it stops playing through the speakers); pass null
     * to disable and restore normal playback.
     */
    public void setAudioHandler(CefriumAudioHandler handler) {
        checkThread("setAudioHandler");
        mAudioHandler = handler;
        if (isLive()) {
            nativeSetDisplayCallback(mNativePtr, this);
            nativeSetAudioCaptureEnabled(mNativePtr, handler != null);
        }
    }
    /** Sets the frame lifecycle handler. */
    public void setFrameHandler(CefriumFrameHandler handler) {
        checkThread("setFrameHandler");
        mFrameHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }
    /** Sets the Chrome command handler. */
    public void setCommandHandler(CefriumCommandHandler handler) {
        checkThread("setCommandHandler");
        mCommandHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }
    /**
     * Sets the file-chooser dialog handler ({@code <input type=file>}), for a
     * chooser of the app's own. Without one the default chooser is shown; see
     * {@link CefriumDialogHandler}. A {@link CefriumChromeClient} decides
     * instead when one is set.
     * @deprecated Use {@link CefriumChromeClient#onShowFileChooser}.
     */
    @Deprecated
    public void setDialogHandler(CefriumDialogHandler handler) {
        checkThread("setDialogHandler");
        mDialogHandler = handler;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }

    private void postMain(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    // ---- Display bridge (called from native, UI thread) ----
    @SuppressWarnings("deprecation")
    private void onFaviconUrlChanged(String[] iconUrls) {
        CefriumDisplayHandler h = mDisplayHandler;
        if (h != null) h.onFaviconUrlChanged(iconUrls);
        CefriumChromeClient chrome = mChromeClient;
        if (chrome != null) {
            chrome.onFaviconUrlsChanged(this, java.util.Collections.unmodifiableList(
                    java.util.Arrays.asList(iconUrls == null ? new String[0] : iconUrls)));
        }
    }
    @SuppressWarnings("deprecation")
    private void onLoadingProgress(double progress) {
        CefriumDisplayHandler h = mDisplayHandler;
        if (h != null) h.onLoadingProgress(progress);
        CefriumNavigationClient nav = mNavigationClient;
        if (nav != null) {
            nav.onProgressChanged(this, (int) Math.round(Math.max(0, Math.min(1, progress)) * 100));
        }
    }
    // |level| is the engine's severity (for CefriumDisplayHandler), |cefriumLevel|
    // a CefriumConsoleMessage.Level ordinal mapped from it in native.
    @SuppressWarnings("deprecation")
    private void onConsoleMessage(int level, int cefriumLevel, String message, String source,
            int line) {
        CefriumDisplayHandler h = mDisplayHandler;
        if (h != null) h.onConsoleMessage(level, message, source, line);
        CefriumChromeClient chrome = mChromeClient;
        if (chrome != null) {
            chrome.onConsoleMessage(this, new CefriumConsoleMessage(
                    CefriumConsoleMessage.Level.values()[cefriumLevel], message, source, line));
        }
    }
    @SuppressWarnings("deprecation")
    private void onStatusMessage(String value) {
        CefriumDisplayHandler h = mDisplayHandler;
        if (h != null) h.onStatusMessage(value);
        CefriumChromeClient chrome = mChromeClient;
        if (chrome != null) chrome.onStatusMessage(this, value == null ? "" : value);
    }
    @SuppressWarnings("deprecation")
    private void onMediaAccessChange(boolean hasVideo, boolean hasAudio) {
        CefriumDisplayHandler h = mDisplayHandler;
        if (h != null) h.onMediaAccessChange(hasVideo, hasAudio);
        CefriumChromeClient chrome = mChromeClient;
        if (chrome != null) chrome.onMediaAccessChanged(this, hasVideo, hasAudio);
    }

    // ---- Load bridge ----
    @SuppressWarnings("deprecation")
    private void onLoadStart(String url) {
        CefriumLoadHandler h = mLoadHandler;
        if (h != null) h.onLoadStart(url);
        CefriumNavigationClient nav = mNavigationClient;
        if (nav != null) nav.onLoadStart(this, url);
    }
    @SuppressWarnings("deprecation")
    private void onLoadError(int errorCode, String errorText, String failedUrl) {
        CefriumLoadHandler h = mLoadHandler;
        if (h != null) h.onLoadError(errorCode, errorText, failedUrl);
        CefriumNavigationClient nav = mNavigationClient;
        if (nav != null) {
            nav.onLoadError(this, new CefriumLoadError(errorCode,
                    errorText == null ? "" : errorText, failedUrl));
        }
    }

    // ---- Request bridge ----
    private void onOpenUrlFromTab(String url, int disposition, boolean userGesture) {
        CefriumRequestHandler h = mRequestHandler;
        if (h != null) h.onOpenUrlFromTab(url, disposition, userGesture);
    }
    private void onRenderUnresponsive() {
        CefriumRequestHandler h = mRequestHandler;
        if (h != null) h.onRenderUnresponsive();
        CefriumNavigationClient nav = mNavigationClient;
        if (nav != null) nav.onRenderProcessUnresponsive(this);
    }
    private void onRenderResponsive() {
        CefriumRequestHandler h = mRequestHandler;
        if (h != null) h.onRenderResponsive();
        CefriumNavigationClient nav = mNavigationClient;
        if (nav != null) nav.onRenderProcessResponsive(this);
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
    // Called by native code on the IO thread: a request failed because of the
    // proxy. Reported to the profile's listener on the main thread.
    @SuppressWarnings("unused")
    private void onProxyError(String url, int error, boolean mainFrame) {
        final CefriumProfile profile = mProfile;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> profile.dispatchProxyError(this, url, error, mainFrame));
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

    // The answer to dialog |token|: the first call counts, from any thread.
    private JsDialogCallback jsDialogAnswer(long token, boolean withText) {
        final java.util.concurrent.atomic.AtomicBoolean answered =
                new java.util.concurrent.atomic.AtomicBoolean();
        return new JsDialogCallback() {
            @Override
            public void continueDialog(boolean success, String userInput) {
                if (!answered.compareAndSet(false, true)) return;
                String text = withText && userInput != null ? userInput : "";
                Runnable r = () -> {
                    if (mNativePtr != 0 && !mClosed) {
                        nativeJsDialogResult(mNativePtr, token, success, text);
                    }
                };
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run();
                else MAIN_HANDLER.post(r);
            }
        };
    }

    // ---- beforeunload (reuses the JS-dialog resolve path) ----
    @SuppressWarnings("deprecation")
    private void onBeforeUnloadDialog(final long token, String message, boolean isReload) {
        JsDialogCallback cb = jsDialogAnswer(token, false);
        CefriumChromeClient chrome = mChromeClient;
        if (chrome != null) {
            if (chrome.onBeforeUnloadDialog(this, message, isReload, cb)) return;
        } else {
            JsDialogHandler h = mJsDialogHandler;
            if (h != null && h.onBeforeUnloadDialog(message, isReload, cb)) return;
        }
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
    @SuppressWarnings({"unused", "deprecation"})
    private void onDismissPermissionPrompt(long promptId, int result) {
        CefriumPermissionRequest request = mPendingPermissions.remove(promptId);
        CefriumChromeClient client = mChromeClient;
        if (request != null) {
            request.cancel();
            if (client != null) client.onPermissionRequestCanceled(this, request);
            return;
        }
        PermissionHandler h = mPermissionHandler;
        if (h != null) h.onDismissPermissionPrompt(promptId, result);
    }

    // ---- LifeSpan ----
    private void onDoClose() {
        // CEF continues its default close sequence.
        PopupHandler owner = mPopupOwner;
        if (owner != null) owner.onCloseWindow(this);
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
    // Returns false to leave it to the engine's own chooser.
    // |mode| is a CefriumFileChooserParams.Mode ordinal, mapped in native.
    @SuppressWarnings("deprecation")
    private boolean onFileDialog(final long id, final int mode, final String title,
                                 final String[] acceptFilters) {
        CefriumChromeClient chrome = mChromeClient;
        if (chrome != null) {
            final java.util.concurrent.atomic.AtomicBoolean answered =
                    new java.util.concurrent.atomic.AtomicBoolean();
            CefriumFileChooserCallback cb = new CefriumFileChooserCallback() {
                @Override public void select(String[] uris) {
                    answer(uris == null ? new String[0] : uris);
                }
                @Override public void cancel() { answer(new String[0]); }
                private void answer(String[] uris) {
                    if (!answered.compareAndSet(false, true)) return;
                    postMain(() -> fileDialogDone(id, uris));
                }
            };
            CefriumFileChooserParams params = new CefriumFileChooserParams(
                    CefriumFileChooserParams.Mode.values()[mode], title, acceptFilters);
            if (chrome.onShowFileChooser(this, params, cb)) return true;
        }
        final CefriumDialogHandler h = chrome != null ? null : mDialogHandler;
        if (h == null) {
            // A surface browser: the engine's chooser, as Chrome's (camera,
            // photo picker, accept types), on its window.
            if (mSurfaceContainer != null) return false;
            // Off-screen: the system picker on the dialog activity.
            android.app.Activity activity = dialogActivity();
            if (activity == null || !DefaultFileChooser.show(activity, mode, title,
                    acceptFilters, uris -> fileDialogDone(id, uris))) {
                fileDialogDone(id, new String[0]);
            } else if (!(activity instanceof androidx.activity.result.ActivityResultRegistryOwner)) {
                mPendingFileDialog = id;
            }
            return true;
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
        return true;
    }

    // A default chooser started without a result registry: its id, for
    // onActivityResult.
    private long mPendingFileDialog = -1;

    private void fileDialogDone(long id, String[] uris) {
        if (mNativePtr != 0 && !mClosed) nativeFileDialogResult(mNativePtr, id, uris);
    }

    /** Delivers a file-chooser selection (empty/null cancels). See {@link CefriumDialogHandler}. */
    public void fileDialogResult(long id, String[] paths) {
        checkThread("fileDialogResult");
        if (isLive()) {
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
        checkThread("downloadImage");
        if (!isLive() || url == null) return;
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
        checkThread("setFocus");
        if (isLive()) nativeSetFocus(mNativePtr, focus);
    }
    /** Programmatically exit HTML fullscreen (e.g. from the back button). */
    public void exitFullscreen() {
        checkThread("exitFullscreen");
        if (isLive()) nativeExitFullscreen(mNativePtr, true);
    }
    /** Force a download of {@code url}. */
    public void startDownload(String url) {
        checkThread("startDownload");
        if (isLive() && url != null) nativeStartDownload(mNativePtr, url);
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
        checkThread("getNavigationEntries");
        java.util.List<NavigationEntry> out = new java.util.ArrayList<>();
        if (!isLive()) return out;
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
    private static native long nativeCreate(int width, int height, float scale,
            boolean incognito, String profile);
    private static native long nativeCreateWithSurface(
            org.chromium.ui.base.WindowAndroid windowAndroid,
            android.view.Surface surface, int width, int height, float scale,
            boolean incognito, String profile, boolean forPopup);
    private static native void nativeSetRenderHandler(long nativePtr, CefriumBrowser browser);
    private static native void nativeBindJavaBrowser(long nativePtr, CefriumBrowser browser);
    private static native boolean nativeSetBackgroundColor(long nativePtr, int argb);
    private static native void nativeLoadUrl(long nativePtr, String url);
    private static native boolean nativeInitContentHost(Class<?> hostClass);
    private static native void nativeOnInterceptDecision(long id, CefriumContentServer server);
    private static native void nativeLoadUrlWithHeaders(long nativePtr, String url,
            String[] headers);
    private static native void nativeLoadDataWithBaseUrl(long nativePtr, String baseUrl,
            String data, String mimeType, String encoding, String historyUrl);
    private static native void nativeInvalidate(long nativePtr);
    private static native void nativeSetSize(long nativePtr, int width, int height);
    // Picker answers; null indices / ok=false cancel.
    private static native void nativePickerSelectResult(long id, int[] indices);
    private static native void nativePickerDateTimeResult(long id, boolean ok, double value);
    private static native void nativePickerColorResult(long id, boolean ok, int color);
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
    private static native void nativeSetRequestObservers(long nativePtr, int observers);
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
    private static native void nativeEvaluateJavaScriptInFocusedFrame(long nativePtr,
            String script);
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
    private static native void nativePrintToPdf(long nativePtr, CefriumBrowser browser,
            String filePath, long requestId, double paperWidth, double paperHeight,
            boolean landscape);
    private static native int nativeExecuteDevToolsMethod(long nativePtr, CefriumBrowser browser,
            int id, String method, String paramsJson, boolean events);
    private static native void nativeSetDevToolsEventsEnabled(long nativePtr,
            CefriumBrowser browser, boolean enabled);
    private static native void nativeSetUserAgentOverride(long nativePtr, String userAgent);
    private static native void nativeSetJavaScriptEnabled(long nativePtr, boolean enabled);
    private static native void nativeSetMediaPlaybackRequiresUserGesture(long nativePtr, boolean required);
    private static native int nativeAddDocumentStartJavaScript(long nativePtr, String script,
            String[] allowedOriginRules);
    private static native void nativeRemoveDocumentStartJavaScript(long nativePtr, int id);
    private static native void nativeSetZoomLevel(long nativePtr, double zoomLevel);
    private static native void nativePostUrl(long nativePtr, String url, byte[] postData);
    private static native void nativeStopLoading(long nativePtr);
    private static native void nativeJsDialogResult(long nativePtr, long token, boolean success, String userInput);
    private static native void nativeRespondPermission(long nativePtr, long promptId, boolean grant);
    private static native void nativeSetContentSetting(long nativePtr, String requestingUrl, int permission, int value);
    private static native void nativeSetDeviceChooserHandler(long nativePtr, boolean enabled);
    private static native void nativeDeviceChooserSelect(long nativePtr, long id, String deviceId);
    private static native void nativeDeviceChooserCancel(long nativePtr, long id);
    private static native void nativeIdentitySelect(long nativePtr, long id, String accountId);
    private static native void nativeIdentityCancel(long nativePtr, long id);
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
        checkThread("setOnContextMenuListener");
        mContextMenuListener = listener;
        if (isLive()) nativeSetDisplayCallback(mNativePtr, this);
    }

    /**
     * Send the user's context menu selection back to CEF.
     * Use -1 to cancel.
     *
     * @param commandId The selected command ID, or -1 to cancel.
     */
    public void contextMenuCommand(int commandId) {
        checkThread("contextMenuCommand");
        if (isLive()) nativeContextMenuCommand(mNativePtr, commandId);
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
