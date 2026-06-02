package com.cefrium.compose

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.cefrium.CefriumBrowser

/**
 * Idiomatic Compose WebView backed by a full Chromium engine (Cefrium).
 *
 * State-hoisting API: observe through [WebViewState], drive navigation through
 * [WebViewNavigator]. Mirrors the common cross-platform WebView surface so a
 * project using a system-WebView-based wrapper can migrate by import, gaining a
 * real, consistent Chromium engine on Android.
 *
 * For the lower-level callback-based API see [CefriumWebView].
 *
 * ```kotlin
 * val state = rememberWebViewState("https://example.com")
 * val navigator = rememberWebViewNavigator()
 * WebView(state, Modifier.fillMaxSize(), navigator)
 * ```
 *
 * @param state Hoisted state holder; its initial URL is loaded on first show.
 * @param modifier Modifier for the browser view.
 * @param navigator Navigation controller bound to this browser.
 * @param onBrowserReady Escape hatch to the underlying [CefriumBrowser] for
 *        features not yet on the navigator (find in page, downloads, dark mode).
 */
@Composable
fun WebView(
    state: WebViewState,
    modifier: Modifier = Modifier,
    navigator: WebViewNavigator = rememberWebViewNavigator(),
    onBrowserReady: ((CefriumBrowser) -> Unit)? = null,
) {
    val activity = LocalContext.current as Activity

    val browser = remember {
        CefriumBrowser.createWithSurface(activity).also { b ->
            b.setCallback(object : CefriumBrowser.Callback() {
                override fun onUrlChanged(url: String) {
                    state.lastLoadedUrl = url
                }

                override fun onTitleChanged(title: String) {
                    state.pageTitle = title
                }

                override fun onLoadingStateChanged(
                    isLoading: Boolean,
                    canGoBack: Boolean,
                    canGoForward: Boolean,
                ) {
                    state.isLoading = isLoading
                    state.canGoBack = canGoBack
                    state.canGoForward = canGoForward
                }
            })
            navigator.browser = b
            navigator.state = state
            onBrowserReady?.invoke(b)
        }
    }

    // Initial load of the state's URL.
    LaunchedEffect(Unit) {
        browser.loadUrl(state.initialUrl)
    }

    // Cleanup: unbind navigator and close the browser on dispose.
    DisposableEffect(Unit) {
        onDispose {
            navigator.browser = null
            navigator.state = null
            browser.close()
        }
    }

    AndroidView(
        factory = {
            browser.surfaceContainer.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        modifier = modifier,
    )
}
