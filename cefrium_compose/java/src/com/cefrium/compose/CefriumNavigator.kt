package com.cefrium.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * External navigation control for a [CefriumView], created with
 * [rememberCefriumNavigator] and passed to the composable. It operates on the
 * [CefriumBrowser] owned by the bound [CefriumViewState].
 *
 * Surface: load / back / forward / reload / evaluateJavaScript (fire-and-forget).
 * `loadHtml`, `postUrl`, `stopLoading` and a result-returning JS bridge remain
 * deferred to later Tier B slices; see FEATURE-022.
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
     * is not delivered back; a result-returning bridge is a later Tier B slice.
     */
    fun evaluateJavaScript(script: String) {
        state?.browser?.evaluateJavaScript(script)
    }

    // ── Cookies (FEATURE-024) ──

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

    // ── Dynamic WebSettings (FEATURE-025) ──

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
}

/** Remembers a [CefriumNavigator] across recompositions. */
@Composable
fun rememberCefriumNavigator(): CefriumNavigator =
    remember { CefriumNavigator() }
