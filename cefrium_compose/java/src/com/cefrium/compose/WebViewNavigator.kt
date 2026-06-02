package com.cefrium.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * External navigation control for a [WebView], created with
 * [rememberWebViewNavigator] and passed to the composable. It operates on the
 * [CefriumBrowser] owned by the bound [WebViewState].
 *
 * Tier A surface: load / back / forward / reload. `loadHtml`, `postUrl`,
 * `stopLoading` and `evaluateJavaScript` are deferred to Tier B (need
 * native/JNI additions); see FEATURE-022.
 */
@Stable
class WebViewNavigator {

    // Bound by WebView() to the active state.
    internal var state: WebViewState? = null

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
}

/** Remembers a [WebViewNavigator] across recompositions. */
@Composable
fun rememberWebViewNavigator(): WebViewNavigator =
    remember { WebViewNavigator() }
