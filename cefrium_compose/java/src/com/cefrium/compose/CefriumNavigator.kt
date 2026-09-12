package com.cefrium.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * External navigation control for a [CefriumView], created with
 * [rememberCefriumNavigator] and passed to the composable. It operates on the
 * [CefriumBrowser] owned by the bound [CefriumViewState].
 *
 * Covers load / loadHtml / postUrl / back / forward / reload / stopLoading /
 * evaluateJavaScript (fire-and-forget), plus cookies, HTTP cache, print-to-PDF,
 * dynamic web settings, a request interceptor and web permissions. Only a
 * result-returning JS bridge is deferred to a later release.
 */
@Stable
class CefriumNavigator {

    // Bound by CefriumView() to the active state.
    internal var state: CefriumViewState? = null

    /** Whether the browser can navigate back. */
    val canGoBack: Boolean
        get() = state?.canGoBack ?: false

    /** Whether the browser can navigate forward. */
    val canGoForward: Boolean
        get() = state?.canGoForward ?: false

    /** Navigates to [url]. No-op before the browser is ready. */
    fun loadUrl(url: String) {
        state?.browser?.loadUrl(url)
    }

    /** Loads an HTML string directly. */
    fun loadHtml(html: String) {
        state?.browser?.loadHtml(html)
    }

    /**
     * Loads an HTML string with an explicit base-URL origin, mirroring Android
     * WebView's loadDataWithBaseURL. Unlike WebView, Cefrium serves the content as
     * a GENUINE network response at [baseUrl] (not a `data:` document), so the
     * document has a real network origin and inline embedded players that require a
     * network-served parent -- e.g. the YouTube IFrame player -- PLAY (a `data:`
     * document is rejected with Error 152, which is why WebView's
     * loadDataWithBaseURL fails here).
     *
     * Pass YOUR OWN app origin as [baseUrl], e.g.
     * `loadHtmlWithBaseUrl("<iframe src='https://www.youtube.com/embed/ID'></iframe>", "https://app.example.com")`
     * -- NOT `"https://www.youtube.com"` (YouTube rejects same-origin self-embedding).
     */
    fun loadHtmlWithBaseUrl(html: String, baseUrl: String) {
        state?.browser?.loadHtmlWithBaseUrl(html, baseUrl)
    }

    /**
     * Loads a data string with an explicit base URL (full control over mime type,
     * encoding and history URL). See [loadHtmlWithBaseUrl] for the common case.
     */
    fun loadDataWithBaseUrl(
        baseUrl: String,
        data: String,
        mimeType: String = "text/html",
        encoding: String = "utf-8",
        historyUrl: String = baseUrl,
    ) {
        state?.browser?.loadDataWithBaseUrl(baseUrl, data, mimeType, encoding, historyUrl)
    }

    /** Loads [url] with an HTTP POST body. */
    fun postUrl(url: String, postData: ByteArray) {
        state?.browser?.postUrl(url, postData)
    }

    /** Stops the current load. */
    fun stopLoading() {
        state?.browser?.stopLoading()
    }

    /** Reloads the current page. */
    fun reload() {
        state?.browser?.reload()
    }

    /** Navigates back if [canGoBack]. */
    fun navigateBack() {
        state?.browser?.goBack()
    }

    /** Navigates forward if [canGoForward]. */
    fun navigateForward() {
        state?.browser?.goForward()
    }

    /**
     * Serializes this tab's back/forward navigation stack for session restore
     * (see [CefriumBrowser.serializeNavigationState]); null if nothing to save.
     * Restore by constructing a CefriumViewState with the bytes as navigationState.
     */
    fun serializeNavigationState(): ByteArray? =
        state?.browser?.serializeNavigationState()

    /**
     * Enable pull-to-refresh (an overscroll-down at the page top reloads). Call
     * before the page loads; the handler binds once when the WebContents connects.
     */
    fun setPullToRefreshEnabled(enabled: Boolean) {
        state?.browser?.setPullToRefreshEnabled(enabled)
    }

    /**
     * Executes [script] in the main frame (fire-and-forget). The return value
     * is not delivered back; a result-returning bridge is planned for a later release.
     */
    fun evaluateJavaScript(script: String) {
        state?.browser?.evaluateJavaScript(script)
    }

    // ── Cookies ──

    /** Sets a session cookie for [url] (domain/path inferred from the URL). */
    fun setCookie(url: String, name: String, value: String) {
        state?.browser?.setCookie(url, name, value)
    }

    /**
     * Sets a session cookie for [url] with explicit attributes. For a cookie a
     * cross-site/embedded iframe must send (e.g. a consent cookie), pass
     * [secure] = true and [sameSite] = [com.cefrium.CefriumBrowser.SAME_SITE_NONE]
     * (Chromium rejects SameSite=None without Secure).
     */
    fun setCookie(
        url: String,
        name: String,
        value: String,
        secure: Boolean,
        httpOnly: Boolean = false,
        sameSite: Int = com.cefrium.CefriumBrowser.SAME_SITE_UNSPECIFIED,
    ) {
        state?.browser?.setCookie(url, name, value, secure, httpOnly, sameSite)
    }

    /** Reads cookies visible to [url]; [callback] fires on the UI thread. */
    fun getCookies(url: String, callback: com.cefrium.CefriumBrowser.CookieCallback) {
        state?.browser?.getCookies(url, callback)
    }

    /** Deletes cookies for [url]; [name] null = all cookies for the URL. */
    fun deleteCookies(url: String, name: String? = null) {
        state?.browser?.deleteCookies(url, name)
    }

    /** Persists the cookie store to disk. */
    fun flushCookies() {
        state?.browser?.flushCookies()
    }

    /** Clears the browser's HTTP cache. */
    fun clearCache() {
        state?.browser?.clearCache()
    }

    /** Renders the current page to a PDF at [filePath] (async). */
    fun printToPdf(filePath: String, callback: com.cefrium.CefriumBrowser.PdfCallback) {
        state?.browser?.printToPdf(filePath, callback)
    }

    // ── Dynamic WebSettings ──

    /** Overrides the User-Agent at runtime. */
    fun setUserAgentOverride(userAgent: String) {
        state?.browser?.setUserAgentOverride(userAgent)
    }

    /** Enables/disables JavaScript at runtime. */
    fun setJavaScriptEnabled(enabled: Boolean) {
        state?.browser?.setJavaScriptEnabled(enabled)
    }

    /** Sets the zoom level (0 = 100%; factor = 1.2^level). */
    fun setZoomLevel(zoomLevel: Double) {
        state?.browser?.setZoomLevel(zoomLevel)
    }

    /**
     * Enables or disables pinch-to-zoom (and page zoom) for arbitrary page
     * content. Enabled by default. When disabled, a script injected on every
     * load-finish forces a non-user-scalable viewport and blocks the multi-touch
     * pinch gesture. See [com.cefrium.CefriumBrowser.setPinchToZoomEnabled].
     */
    fun setPinchToZoomEnabled(enabled: Boolean) {
        state?.browser?.setPinchToZoomEnabled(enabled)
    }

    // ----- Mouse events -------------------------------------------------------
    // Programmatic mouse injection (position/hover, click, right-click, wheel).
    // Coordinates are browser-viewport pixels. See com.cefrium.CefriumBrowser.

    /** Move the mouse pointer to (x, y). Drives mousemove / :hover. */
    fun sendMouseMove(x: Int, y: Int, modifiers: Int = 0) {
        state?.browser?.sendMouseMove(x, y, modifiers)
    }

    /** Signal the pointer leaving the view at (x, y) (clears :hover). */
    fun sendMouseLeave(x: Int, y: Int) {
        state?.browser?.sendMouseLeave(x, y)
    }

    /** Click at (x, y) with [button] (down + up). Defaults to a single left click. */
    fun sendMouseClick(
        x: Int, y: Int, button: Int = MOUSE_BUTTON_LEFT, clickCount: Int = 1, modifiers: Int = 0
    ) {
        state?.browser?.sendMouseClick(x, y, button, clickCount, modifiers)
    }

    /** Left click at (x, y). */
    fun sendLeftClick(x: Int, y: Int) {
        state?.browser?.sendLeftClick(x, y)
    }

    /** Right click at (x, y) -- also opens the context menu. */
    fun sendRightClick(x: Int, y: Int) {
        state?.browser?.sendRightClick(x, y)
    }

    /** Double left click at (x, y). */
    fun sendDoubleClick(x: Int, y: Int) {
        state?.browser?.sendDoubleClick(x, y)
    }

    /** Press a mouse button at (x, y) without releasing (pair with [sendMouseUp] for drags). */
    fun sendMouseDown(x: Int, y: Int, button: Int = MOUSE_BUTTON_LEFT, modifiers: Int = 0) {
        state?.browser?.sendMouseDown(x, y, button, modifiers)
    }

    /** Release a mouse button at (x, y). */
    fun sendMouseUp(x: Int, y: Int, button: Int = MOUSE_BUTTON_LEFT, modifiers: Int = 0) {
        state?.browser?.sendMouseUp(x, y, button, modifiers)
    }

    /** Scroll the mouse wheel at (x, y). deltaX/deltaY in pixels (~120 per notch). */
    fun sendMouseWheel(x: Int, y: Int, deltaX: Int, deltaY: Int, modifiers: Int = 0) {
        state?.browser?.sendMouseWheel(x, y, deltaX, deltaY, modifiers)
    }

    // ----- Touch automation ---------------------------------------------------
    // Single-finger touch injection. Coordinates are browser-viewport pixels.
    // A tap synthesizes a click; down/move/up drive drags and scroll/fling.

    /** Press a finger down at (x, y). */
    fun sendTouchDown(x: Float, y: Float) { state?.browser?.sendTouchDown(x, y) }

    /** Move the pressed finger to (x, y). */
    fun sendTouchMove(x: Float, y: Float) { state?.browser?.sendTouchMove(x, y) }

    /** Lift the finger at (x, y). */
    fun sendTouchUp(x: Float, y: Float) { state?.browser?.sendTouchUp(x, y) }

    /** Tap at (x, y) -- synthesizes a click on the element there. */
    fun sendTap(x: Float, y: Float) { state?.browser?.sendTap(x, y) }

    /** Swipe from (x1,y1) to (x2,y2) over [steps] moves (scroll/fling). */
    fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, steps: Int = 10) {
        state?.browser?.sendSwipe(x1, y1, x2, y2, steps)
    }

    companion object {
        // Mirror com.cefrium.CefriumBrowser mouse constants for Compose callers
        // (stable CEF values: MBT_LEFT/MIDDLE/RIGHT and shift/ctrl/alt flags).
        const val MOUSE_BUTTON_LEFT = 0
        const val MOUSE_BUTTON_MIDDLE = 1
        const val MOUSE_BUTTON_RIGHT = 2
        const val MOUSE_MODIFIER_SHIFT = 1 shl 1
        const val MOUSE_MODIFIER_CTRL = 1 shl 2
        const val MOUSE_MODIFIER_ALT = 1 shl 3
    }

    // ── Request interceptor ──

    /** Sets a programmatic request interceptor (block decisions; null clears). */
    fun setRequestInterceptor(interceptor: com.cefrium.CefriumBrowser.RequestInterceptor?) {
        state?.browser?.setRequestInterceptor(interceptor)
    }

    // ── Permissions ──

    /** Sets the web permission handler (geolocation, camera, mic; null = deny all). */
    fun setPermissionHandler(handler: com.cefrium.CefriumBrowser.PermissionHandler?) {
        state?.browser?.setPermissionHandler(handler)
    }

    /**
     * Pre-grants (or blocks) a per-origin web permission without a prompt — e.g.
     * allow Web Notifications for your own origin so service-worker
     * `showNotification()` works without asking. [permission] is one of
     * `CefriumBrowser.PERMISSION_*`; [value] one of `CefriumBrowser.SETTING_*`.
     */
    fun setContentSetting(requestingUrl: String, permission: Int, value: Int) {
        state?.browser?.setContentSetting(requestingUrl, permission, value)
    }

    /** Sets the SSL/certificate error handler (proceed/cancel; null = cancel all). */
    fun setSslErrorHandler(handler: com.cefrium.CefriumBrowser.SslErrorHandler?) {
        state?.browser?.setSslErrorHandler(handler)
    }

    /** Sets the HTTP auth handler (supply credentials/cancel; null = cancel all). */
    fun setHttpAuthHandler(handler: com.cefrium.CefriumBrowser.HttpAuthHandler?) {
        state?.browser?.setHttpAuthHandler(handler)
    }

    /**
     * Sets the device chooser handler for the frontier device APIs (Web
     * Bluetooth, WebUSB, Web Serial). When a page calls `requestDevice()` /
     * `requestPort()` the handler presents a picker and resolves it via the
     * [com.cefrium.CefriumBrowser.DeviceChooserSession]. Null = cancel all
     * device requests (the page promise rejects).
     */
    fun setDeviceChooserHandler(handler: com.cefrium.CefriumBrowser.DeviceChooserHandler?) {
        state?.browser?.setDeviceChooserHandler(handler)
    }

    /**
     * Configures the proxy for this browser's request context. [mode] is one of
     * `CefriumBrowser.PROXY_*`; [server] is `"host:port"` for fixed servers, the
     * PAC URL for `PROXY_PAC`, ignored otherwise. Returns false if not applied.
     */
    fun setProxy(mode: String, server: String?): Boolean =
        state?.browser?.setProxy(mode, server) ?: false

    /** Convenience: route through a fixed proxy `"host:port"`. */
    fun setProxy(server: String): Boolean = state?.browser?.setProxy(server) ?: false

    /** Convenience: disable proxying (direct connection). */
    fun clearProxy(): Boolean = state?.browser?.clearProxy() ?: false

    /**
     * Captures the current page to a Bitmap, delivered on the main thread
     * ([onBitmap] receives null on failure). Off-screen mode returns the next
     * painted frame; Surface mode copies the visible surface.
     */
    fun captureBitmap(onBitmap: (android.graphics.Bitmap?) -> Unit) {
        val b = state?.browser
        if (b == null) { onBitmap(null); return }
        b.captureBitmap { bmp -> onBitmap(bmp) }
    }

    // ── Find in page ──

    /**
     * Starts/continues a find-in-page for [text]. Results arrive on
     * [CefriumViewState.findActiveMatch] / [CefriumViewState.findMatchCount].
     * Set [findNext] true to step to the next match of the same query.
     */
    fun find(
        text: String,
        forward: Boolean = true,
        matchCase: Boolean = false,
        findNext: Boolean = false,
    ) {
        state?.browser?.find(text, forward, matchCase, findNext)
    }

    /** Ends the active find session ([clearSelection] clears the highlight). */
    fun stopFinding(clearSelection: Boolean = true) {
        state?.browser?.stopFinding(clearSelection)
    }

    // ── Context menu ──

    /**
     * Invokes a context-menu command id (from [CefriumViewState.contextMenu]'s
     * serialized items) after the user picks one.
     */
    fun contextMenuCommand(commandId: Int) {
        state?.browser?.contextMenuCommand(commandId)
    }

    // ── Display / media ──

    /** Forces dark mode for page rendering (prefers-color-scheme). */
    fun setDarkMode(dark: Boolean) {
        state?.browser?.setDarkMode(dark)
    }

    /** Enables the lock-screen / shade media-session controls for this browser. */
    fun setMediaSessionEnabled(enabled: Boolean) {
        state?.browser?.setMediaSessionEnabled(enabled)
    }

    /**
     * Sets the PendingIntent fired when the user taps this tab's media control -- use it
     * to open the exact tab that is playing. Null reverts to the host app launcher.
     */
    fun setMediaSessionActivity(activity: android.app.PendingIntent?) {
        state?.browser?.setMediaSessionActivity(activity)
    }

    /**
     * Whether this tab currently has audible media playing (not paused). Use it
     * to avoid discarding/closing a tab that is actively playing sound.
     */
    fun isPlayingAudio(): Boolean = state?.browser?.isPlayingAudio() ?: false

    // ── Media / page lifecycle ──
    //
    // CefriumView wires onHostPause()/onHostResume() to the host lifecycle by
    // default (its backgroundMediaPolicy). These granular controls let a host
    // build any custom policy (mute-only, keep-playing background audio, etc.).

    /**
     * "Host is backgrounding": hide the page, suspend media and mute audio so
     * inline audio/video actually stops. Resumable via [onHostResume]. Usually
     * automatic via [CefriumView]'s `backgroundMediaPolicy`; call manually only
     * for a custom lifecycle setup.
     */
    fun onHostPause() {
        state?.browser?.onPause()
    }

    /** "Host is foregrounding": unmute, show the page and resume suspended media. */
    fun onHostResume() {
        state?.browser?.onResume()
    }

    /** Suspends active media (pause; position preserved). See [resumeMedia]. */
    fun suspendMedia() {
        state?.browser?.suspendMedia()
    }

    /** Resumes media suspended by [suspendMedia] / [onHostPause]. */
    fun resumeMedia() {
        state?.browser?.resumeMedia()
    }

    /** Mutes/unmutes all page audio (media keeps playing, just silenced). */
    fun setAudioMuted(muted: Boolean) {
        state?.browser?.setAudioMuted(muted)
    }

    /** Whether page audio is muted via [setAudioMuted]. */
    val isAudioMuted: Boolean
        get() = state?.browser?.isAudioMuted ?: false

    /** Sets page visibility (compositor + `document.hidden`); does not stop audio. */
    fun setPageVisible(visible: Boolean) {
        state?.browser?.setPageVisible(visible)
    }

    /** Hard-stops all media (not resumable) and mutes; for "release audio on background". */
    fun stopAllMedia() {
        state?.browser?.stopAllMedia()
    }

    /**
     * Stops the page's media session and dismisses its media notification (also stops
     * media in cross-origin iframes, e.g. a YouTube embed). Use on navigate-away when
     * [stopAllMedia] (pause + mute) isn't enough because you want the tray notification gone.
     */
    fun stopMediaSession() {
        state?.browser?.stopMediaSession()
    }
}

/** Remembers a [CefriumNavigator] across recompositions. */
@Composable
fun rememberCefriumNavigator(): CefriumNavigator =
    remember { CefriumNavigator() }
