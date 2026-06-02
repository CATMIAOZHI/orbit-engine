package com.cefrium.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Hoisted state for [WebView], created with [rememberWebViewState].
 *
 * The host observes loading/title/URL through this holder; navigation is driven
 * through [WebViewNavigator]. This is the idiomatic Compose state-hoisting
 * pattern, replacing the callback-as-props style of [CefriumWebView].
 *
 * ```kotlin
 * val state = rememberWebViewState("https://example.com")
 * val navigator = rememberWebViewNavigator()
 * WebView(state = state, navigator = navigator)
 * if (state.isLoading) LinearProgressIndicator()
 * Text(state.pageTitle ?: "")
 * ```
 */
@Stable
class WebViewState(initialUrl: String) {

    /** URL loaded when the [WebView] first appears. Later navigation goes
     *  through [WebViewNavigator], not by reassigning this value. */
    val initialUrl: String = initialUrl

    /** Last URL the browser actually navigated to (from CEF, not the request). */
    var lastLoadedUrl: String? by mutableStateOf(null)
        internal set

    /** Current page title, or null before the first title change. */
    var pageTitle: String? by mutableStateOf(null)
        internal set

    /** Whether a navigation is in progress. */
    var isLoading: Boolean by mutableStateOf(false)
        internal set

    /** Whether the back history is non-empty. */
    var canGoBack: Boolean by mutableStateOf(false)
        internal set

    /** Whether the forward history is non-empty. */
    var canGoForward: Boolean by mutableStateOf(false)
        internal set
}

/** Remembers a [WebViewState] for [url] across recompositions. */
@Composable
fun rememberWebViewState(url: String): WebViewState =
    remember(url) { WebViewState(url) }
