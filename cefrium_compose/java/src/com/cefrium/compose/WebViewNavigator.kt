package com.cefrium.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.cefrium.CefriumBrowser

/**
 * External navigation control for a [WebView], created with
 * [rememberWebViewNavigator] and passed to the composable.
 *
 * Tier A surface: load / back / forward / reload, backed directly by
 * [CefriumBrowser]. `loadHtml`, `postUrl`, `stopLoading` and
 * `evaluateJavaScript` are deferred to Tier B (require native/JNI additions);
 * see FEATURE-022.
 */
@Stable
class WebViewNavigator {

    // Wired by WebView() while the browser is alive; cleared on dispose.
    internal var browser: CefriumBrowser? = null
    internal var state: WebViewState? = null

    /** Whether the browser can navigate back. Mirrors [WebViewState.canGoBack]. */
    val canGoBack: Boolean
        get() = state?.canGoBack ?: false

    /** Whether the browser can navigate forward. */
    val canGoForward: Boolean
        get() = state?.canGoForward ?: false

    /** Navigates to [url]. No-op before the browser is ready. */
    fun loadUrl(url: String) {
        browser?.loadUrl(url)
    }

    /** Reloads the current page. */
    fun reload() {
        browser?.reload()
    }

    /** Navigates back if [canGoBack]. */
    fun navigateBack() {
        browser?.goBack()
    }

    /** Navigates forward if [canGoForward]. */
    fun navigateForward() {
        browser?.goForward()
    }
}

/** Remembers a [WebViewNavigator] across recompositions. */
@Composable
fun rememberWebViewNavigator(): WebViewNavigator =
    remember { WebViewNavigator() }
