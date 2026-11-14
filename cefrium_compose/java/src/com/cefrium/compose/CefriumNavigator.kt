// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

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
 *
 * Call it on the main thread; on another it throws IllegalStateException.
 * Calls made before CefriumView has a browser run once it does, except the
 * input calls (send*), which need a page and are dropped, and the calls that
 * return a value, which return their default.
 */
@Stable
class CefriumNavigator {

    // Bound by CefriumView() to the active state.
    internal var state: CefriumViewState? = null

    // Calls made before CefriumView bound a browser; they used to be dropped.
    private val pending = mutableListOf<(com.cefrium.CefriumBrowser) -> Unit>()

    // Runs |action| on the browser now, or once CefriumView binds one.
    private fun run(name: String, action: (com.cefrium.CefriumBrowser) -> Unit) {
        checkMainThread(name)
        val b = state?.browser
        if (b != null) action(b) else pending += action
    }

    // As CefriumBrowser: the main thread only.
    private fun checkMainThread(name: String) {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "CefriumNavigator.$name must be called on the main thread, not on " +
                Thread.currentThread().name
        }
    }

    internal fun bind(state: CefriumViewState) {
        this.state = state
        val b = state.browser ?: return
        val actions = pending.toList()
        pending.clear()
        actions.forEach { it(b) }
    }

    /** Whether the browser can navigate back. */
    val canGoBack: Boolean
        get() = state?.canGoBack ?: false

    /** Whether the browser can navigate forward. */
    val canGoForward: Boolean
        get() = state?.canGoForward ?: false

    /** Navigates to [url]. Before the browser is ready, it loads once it is. */
    fun loadUrl(url: String) {
        run("loadUrl") { it.loadUrl(url) }
    }

    /**
     * Navigates to [url], sending [additionalHttpHeaders] with the page's own
     * request. See `CefriumBrowser.loadUrl(String, Map)`.
     */
    fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        run("loadUrl") { it.loadUrl(url, additionalHttpHeaders) }
    }

    /** Loads an HTML string directly. */
    fun loadHtml(html: String) {
        run("loadHtml") { it.loadHtml(html) }
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
        run("loadHtmlWithBaseUrl") { it.loadHtmlWithBaseUrl(html, baseUrl) }
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
        run("loadDataWithBaseUrl") { it.loadDataWithBaseUrl(baseUrl, data, mimeType, encoding, historyUrl) }
    }

    /** Loads [url] with an HTTP POST body. */
    fun postUrl(url: String, postData: ByteArray) {
        run("postUrl") { it.postUrl(url, postData) }
    }

    /** Stops the current load. */
    fun stopLoading() {
        run("stopLoading") { it.stopLoading() }
    }

    /** Reloads the current page. */
    fun reload() {
        run("reload") { it.reload() }
    }

    /** Navigates back if [canGoBack]. */
    fun navigateBack() {
        run("navigateBack") { it.goBack() }
    }

    /** Navigates forward if [canGoForward]. */
    fun navigateForward() {
        run("navigateForward") { it.goForward() }
    }

    /**
     * Serializes this tab's back/forward navigation stack for session restore
     * (see [CefriumBrowser.serializeNavigationState]); null if nothing to save.
     * Restore by constructing a CefriumViewState with the bytes as navigationState.
     */
    fun serializeNavigationState(): ByteArray? {
        checkMainThread("serializeNavigationState")
        return state?.browser?.serializeNavigationState()
    }

    /**
     * Enable pull-to-refresh (an overscroll-down at the page top reloads). May
     * be called, and toggled, at any time.
     */
    fun setPullToRefreshEnabled(enabled: Boolean) {
        run("setPullToRefreshEnabled") { it.setPullToRefreshEnabled(enabled) }
    }

    /**
     * Executes [script] in the main frame (fire-and-forget). The return value
     * is not delivered back; a result-returning bridge is planned for a later release.
     */
    fun evaluateJavaScript(script: String) {
        run("evaluateJavaScript") { it.evaluateJavaScript(script) }
    }

    // ── Cookies ──

    /** Sets a session cookie for [url] (domain/path inferred from the URL). */
    @Deprecated("Use CefriumProfile.getCookieManager(): per profile, with expiry")
    @Suppress("DEPRECATION")
    fun setCookie(url: String, name: String, value: String) {
        run("setCookie") { it.setCookie(url, name, value) }
    }

    /**
     * Sets a session cookie for [url] with explicit attributes. For a cookie a
     * cross-site/embedded iframe must send (e.g. a consent cookie), pass
     * [secure] = true and [sameSite] = [com.cefrium.CefriumBrowser.SAME_SITE_NONE]
     * (Chromium rejects SameSite=None without Secure).
     */
    @Deprecated("Use CefriumProfile.getCookieManager(): per profile, with expiry")
    @Suppress("DEPRECATION")
    fun setCookie(
        url: String,
        name: String,
        value: String,
        secure: Boolean,
        httpOnly: Boolean = false,
        sameSite: Int = com.cefrium.CefriumBrowser.SAME_SITE_UNSPECIFIED,
    ) {
        run("setCookie") { it.setCookie(url, name, value, secure, httpOnly, sameSite) }
    }

    /** Reads cookies visible to [url]; [callback] fires on the UI thread. */
    @Deprecated("Use CefriumProfile.getCookieManager(): per profile, with expiry")
    @Suppress("DEPRECATION")
    fun getCookies(url: String, callback: com.cefrium.CefriumBrowser.CookieCallback) {
        run("getCookies") { it.getCookies(url, callback) }
    }

    /** Deletes cookies for [url]; [name] null = all cookies for the URL. */
    @Deprecated("Use CefriumProfile.getCookieManager(): per profile, with expiry")
    @Suppress("DEPRECATION")
    fun deleteCookies(url: String, name: String? = null) {
        run("deleteCookies") { it.deleteCookies(url, name) }
    }

    /** Persists the cookie store to disk. */
    @Deprecated("Use CefriumProfile.getCookieManager(): per profile, with expiry")
    @Suppress("DEPRECATION")
    fun flushCookies() {
        run("flushCookies") { it.flushCookies() }
    }

    /** Clears the browser's HTTP cache. */
    fun clearCache() {
        run("clearCache") { it.clearCache() }
    }

    /** Renders the current page to a PDF at [filePath] (async). */
    fun printToPdf(filePath: String, callback: com.cefrium.CefriumBrowser.PdfCallback) {
        run("printToPdf") { it.printToPdf(filePath, callback) }
    }

    // ── Dynamic WebSettings ──

    /** Overrides the User-Agent at runtime. */
    fun setUserAgentOverride(userAgent: String) {
        run("setUserAgentOverride") { it.setUserAgentOverride(userAgent) }
    }

    /** Enables/disables JavaScript at runtime. */
    fun setJavaScriptEnabled(enabled: Boolean) {
        run("setJavaScriptEnabled") { it.setJavaScriptEnabled(enabled) }
    }

    /** Sets the zoom level (0 = 100%; factor = 1.2^level). */
    fun setZoomLevel(zoomLevel: Double) {
        run("setZoomLevel") { it.setZoomLevel(zoomLevel) }
    }

    /**
     * Enables or disables pinch-to-zoom (and page zoom) for arbitrary page
     * content. Enabled by default. When disabled, a script injected on every
     * load-finish forces a non-user-scalable viewport and blocks the multi-touch
     * pinch gesture. See [com.cefrium.CefriumBrowser.setPinchToZoomEnabled].
     */
    fun setPinchToZoomEnabled(enabled: Boolean) {
        run("setPinchToZoomEnabled") { it.setPinchToZoomEnabled(enabled) }
    }

    // ----- Mouse events -------------------------------------------------------
    // Programmatic mouse injection (position/hover, click, right-click, wheel).
    // Units as in com.cefrium.CefriumBrowser.sendMouseMove: device-independent pixels.

    /** Move the mouse pointer to (x, y). Drives mousemove / :hover. */
    fun sendMouseMove(x: Int, y: Int, modifiers: Int = 0) {
        checkMainThread("sendMouseMove")
        state?.browser?.sendMouseMove(x, y, modifiers)
    }

    /** Signal the pointer leaving the view at (x, y) (clears :hover). */
    fun sendMouseLeave(x: Int, y: Int) {
        checkMainThread("sendMouseLeave")
        state?.browser?.sendMouseLeave(x, y)
    }

    /** Click at (x, y) with [button] (down + up). Defaults to a single left click. */
    fun sendMouseClick(
        x: Int, y: Int, button: Int = MOUSE_BUTTON_LEFT, clickCount: Int = 1, modifiers: Int = 0
    ) {
        checkMainThread("sendMouseClick")
        state?.browser?.sendMouseClick(x, y, button, clickCount, modifiers)
    }

    /** Left click at (x, y). */
    fun sendLeftClick(x: Int, y: Int) {
        checkMainThread("sendLeftClick")
        state?.browser?.sendLeftClick(x, y)
    }

    /** Right click at (x, y) -- also opens the context menu. */
    fun sendRightClick(x: Int, y: Int) {
        checkMainThread("sendRightClick")
        state?.browser?.sendRightClick(x, y)
    }

    /** Double left click at (x, y). */
    fun sendDoubleClick(x: Int, y: Int) {
        checkMainThread("sendDoubleClick")
        state?.browser?.sendDoubleClick(x, y)
    }

    /** Press a mouse button at (x, y) without releasing (pair with [sendMouseUp] for drags). */
    fun sendMouseDown(x: Int, y: Int, button: Int = MOUSE_BUTTON_LEFT, modifiers: Int = 0) {
        checkMainThread("sendMouseDown")
        state?.browser?.sendMouseDown(x, y, button, modifiers)
    }

    /** Release a mouse button at (x, y). */
    fun sendMouseUp(x: Int, y: Int, button: Int = MOUSE_BUTTON_LEFT, modifiers: Int = 0) {
        checkMainThread("sendMouseUp")
        state?.browser?.sendMouseUp(x, y, button, modifiers)
    }

    /** Scroll the mouse wheel at (x, y). deltaX/deltaY in pixels (~120 per notch). */
    fun sendMouseWheel(x: Int, y: Int, deltaX: Int, deltaY: Int, modifiers: Int = 0) {
        checkMainThread("sendMouseWheel")
        state?.browser?.sendMouseWheel(x, y, deltaX, deltaY, modifiers)
    }

    // ----- Touch automation ---------------------------------------------------
    // Single-finger touch injection. Units as in com.cefrium.CefriumBrowser.sendTouchDown.
    // A tap synthesizes a click; down/move/up drive drags and scroll/fling.

    /** Press a finger down at (x, y). */
    fun sendTouchDown(x: Float, y: Float) { checkMainThread("sendTouchDown"); state?.browser?.sendTouchDown(x, y) }

    /** Move the pressed finger to (x, y). */
    fun sendTouchMove(x: Float, y: Float) { checkMainThread("sendTouchMove"); state?.browser?.sendTouchMove(x, y) }

    /** Lift the finger at (x, y). */
    fun sendTouchUp(x: Float, y: Float) { checkMainThread("sendTouchUp"); state?.browser?.sendTouchUp(x, y) }

    /** Tap at (x, y) -- synthesizes a click on the element there. */
    fun sendTap(x: Float, y: Float) { checkMainThread("sendTap"); state?.browser?.sendTap(x, y) }

    /** Swipe from (x1,y1) to (x2,y2) over [steps] moves (scroll/fling). */
    fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, steps: Int = 10) {
        checkMainThread("sendSwipe")
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

    /**
     * Sets the interceptor that may cancel or answer every request of this
     * browser (null removes it). See `CefriumBrowser.setResourceInterceptor`.
     */
    fun setResourceInterceptor(interceptor: com.cefrium.CefriumResourceInterceptor?) {
        run("setResourceInterceptor") { it.setResourceInterceptor(interceptor) }
    }

    /** Sets a programmatic request interceptor (block decisions; null clears). */
    @Deprecated("Use setResourceInterceptor", ReplaceWith("setResourceInterceptor(interceptor)"))
    @Suppress("DEPRECATION")
    fun setRequestInterceptor(interceptor: com.cefrium.CefriumBrowser.RequestInterceptor?) {
        run("setRequestInterceptor") { it.setRequestInterceptor(interceptor) }
    }

    // ── Permissions ──

    /**
     * Sets the client that answers what pages ask of the app, permission
     * requests first (null removes it). See `CefriumBrowser.setChromeClient`.
     */
    fun setChromeClient(client: com.cefrium.CefriumChromeClient?) {
        run("setChromeClient") { it.setChromeClient(client) }
    }

    /**
     * Sets the client told of this browser's navigations and loads (null
     * removes it). See `CefriumBrowser.setNavigationClient`. The state this
     * view hoists keeps updating either way.
     */
    fun setNavigationClient(client: com.cefrium.CefriumNavigationClient?) {
        run("setNavigationClient") { it.setNavigationClient(client) }
    }

    /**
     * Stores a decision for [permission] on [origin], so the page is answered
     * without asking. See `CefriumBrowser.setContentSetting`.
     */
    fun setContentSetting(
        origin: String,
        permission: com.cefrium.CefriumPermission,
        value: com.cefrium.CefriumContentSetting,
    ) {
        run("setContentSetting") { it.setContentSetting(origin, permission, value) }
    }

    /** Sets the web permission handler (geolocation, camera, mic; null = deny all). */
    @Deprecated("Use setChromeClient", ReplaceWith("setChromeClient(client)"))
    @Suppress("DEPRECATION")
    fun setPermissionHandler(handler: com.cefrium.CefriumBrowser.PermissionHandler?) {
        run("setPermissionHandler") { it.setPermissionHandler(handler) }
    }

    /**
     * Pre-grants (or blocks) a per-origin web permission without a prompt.
     * [permission] is one of `CefriumBrowser.PERMISSION_*`; [value] one of
     * `CefriumBrowser.SETTING_*`.
     */
    @Deprecated("Use the CefriumPermission / CefriumContentSetting overload")
    @Suppress("DEPRECATION")
    fun setContentSetting(requestingUrl: String, permission: Int, value: Int) {
        run("setContentSetting") { it.setContentSetting(requestingUrl, permission, value) }
    }

    /** Sets the SSL/certificate error handler (proceed/cancel; null = cancel all). */
    @Deprecated("Use setNavigationClient (onSslError)")
    @Suppress("DEPRECATION")
    fun setSslErrorHandler(handler: com.cefrium.CefriumBrowser.SslErrorHandler?) {
        run("setSslErrorHandler") { it.setSslErrorHandler(handler) }
    }

    /** Sets the HTTP auth handler (supply credentials/cancel; null = cancel all). */
    @Deprecated("Use setNavigationClient (onHttpAuthRequest)")
    @Suppress("DEPRECATION")
    fun setHttpAuthHandler(handler: com.cefrium.CefriumBrowser.HttpAuthHandler?) {
        run("setHttpAuthHandler") { it.setHttpAuthHandler(handler) }
    }

    /**
     * Sets the device chooser handler for the frontier device APIs (Web
     * Bluetooth, WebUSB, Web Serial). When a page calls `requestDevice()` /
     * `requestPort()` the handler presents a picker and resolves it via the
     * [com.cefrium.CefriumBrowser.DeviceChooserSession]. Null = cancel all
     * device requests (the page promise rejects).
     */
    fun setDeviceChooserHandler(handler: com.cefrium.CefriumBrowser.DeviceChooserHandler?) {
        run("setDeviceChooserHandler") { it.setDeviceChooserHandler(handler) }
    }

    /**
     * Configures the proxy of this browser's PROFILE (every browser of the profile
     * uses it; see `CefriumBrowser.setProxy`). [mode] is one of
     * `CefriumBrowser.PROXY_*`; [server] is `"host:port"` for fixed servers, the
     * PAC URL for `PROXY_PAC`, ignored otherwise. Returns false if not applied.
     */
    fun setProxy(mode: String, server: String?): Boolean {
        checkMainThread("setProxy")
        return state?.browser?.setProxy(mode, server) ?: false
    }

    /** Convenience: route through a fixed proxy `"host:port"`. */
    fun setProxy(server: String): Boolean {
        checkMainThread("setProxy")
        return state?.browser?.setProxy(server) ?: false
    }

    /** Convenience: disable proxying (direct connection). */
    fun clearProxy(): Boolean {
        checkMainThread("clearProxy")
        return state?.browser?.clearProxy() ?: false
    }

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
        run("find") { it.find(text, forward, matchCase, findNext) }
    }

    /** Ends the active find session ([clearSelection] clears the highlight). */
    fun stopFinding(clearSelection: Boolean = true) {
        run("stopFinding") { it.stopFinding(clearSelection) }
    }

    // ── Context menu ──

    /**
     * Invokes a context-menu command id (from [CefriumViewState.contextMenu]'s
     * serialized items) after the user picks one.
     */
    fun contextMenuCommand(commandId: Int) {
        run("contextMenuCommand") { it.contextMenuCommand(commandId) }
    }

    // ── Display / media ──

    /** Forces dark mode for page rendering (prefers-color-scheme). */
    fun setDarkMode(dark: Boolean) {
        run("setDarkMode") { it.setDarkMode(dark) }
    }

    /** Enables the lock-screen / shade media-session controls for this browser. */
    fun setMediaSessionEnabled(enabled: Boolean) {
        run("setMediaSessionEnabled") { it.setMediaSessionEnabled(enabled) }
    }

    /**
     * Sets the PendingIntent fired when the user taps this tab's media control -- use it
     * to open the exact tab that is playing. Null reverts to the host app launcher.
     */
    fun setMediaSessionActivity(activity: android.app.PendingIntent?) {
        run("setMediaSessionActivity") { it.setMediaSessionActivity(activity) }
    }

    /**
     * Whether this tab currently has audible media playing (not paused). Use it
     * to avoid discarding/closing a tab that is actively playing sound.
     */
    fun isPlayingAudio(): Boolean {
        checkMainThread("isPlayingAudio")
        return state?.browser?.isPlayingAudio() ?: false
    }

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
        run("onHostPause") { it.onPause() }
    }

    /** "Host is foregrounding": unmute, show the page and resume suspended media. */
    fun onHostResume() {
        run("onHostResume") { it.onResume() }
    }

    /** Suspends active media (pause; position preserved). See [resumeMedia]. */
    fun suspendMedia() {
        run("suspendMedia") { it.suspendMedia() }
    }

    /** Resumes media suspended by [suspendMedia] / [onHostPause]. */
    fun resumeMedia() {
        run("resumeMedia") { it.resumeMedia() }
    }

    /** Mutes/unmutes all page audio (media keeps playing, just silenced). */
    fun setAudioMuted(muted: Boolean) {
        run("setAudioMuted") { it.setAudioMuted(muted) }
    }

    /** Whether page audio is muted via [setAudioMuted]. */
    val isAudioMuted: Boolean
        get() = state?.browser?.isAudioMuted ?: false

    /** Sets page visibility (compositor + `document.hidden`); does not stop audio. */
    fun setPageVisible(visible: Boolean) {
        run("setPageVisible") { it.setPageVisible(visible) }
    }

    /**
     * Gives or takes keyboard focus for the browser.
     *
     * An on-screen `CefriumView` is focused by the Android view system, so you
     * rarely need this. An **off-screen (OSR)** browser has no view, so nothing
     * focuses it unless you call this and its document stays unfocused.
     *
     * That reaches further than typing: Blink gates some APIs on document focus
     * *before* any permission check, so they fail looking like permission bugs.
     * `navigator.clipboard.readText()` is the clear case -- unfocused it rejects
     * with `NotAllowedError: Document is not focused.` and no permission handler
     * is ever consulted.
     *
     * Focus is independent of [setPageVisible]: a browser can be visible and
     * unfocused, or focused and hidden.
     */
    fun setFocus(focus: Boolean) {
        run("setFocus") { it.setFocus(focus) }
    }

    /** Hard-stops all media (not resumable) and mutes; for "release audio on background". */
    fun stopAllMedia() {
        run("stopAllMedia") { it.stopAllMedia() }
    }

    /**
     * Stops the page's media session and dismisses its media notification (also stops
     * media in cross-origin iframes, e.g. a YouTube embed). Use on navigate-away when
     * [stopAllMedia] (pause + mute) isn't enough because you want the tray notification gone.
     */
    fun stopMediaSession() {
        run("stopMediaSession") { it.stopMediaSession() }
    }
}

/** Remembers a [CefriumNavigator] across recompositions. */
@Composable
fun rememberCefriumNavigator(): CefriumNavigator =
    remember { CefriumNavigator() }
