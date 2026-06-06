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
}

/** Remembers a [CefriumNavigator] across recompositions. */
@Composable
fun rememberCefriumNavigator(): CefriumNavigator =
    remember { CefriumNavigator() }
