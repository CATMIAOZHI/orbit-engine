package com.cefrium.compose

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.cefrium.CefriumBrowser

/**
 * Idiomatic Compose WebView backed by a full Chromium engine (Cefrium).
 *
 * State-hoisting API: observe through [WebViewState], drive navigation through
 * [WebViewNavigator]. The browser's lifecycle belongs to [state], not to this
 * composable — so detaching this WebView (e.g. switching tabs) does NOT close
 * the browser; the page stays alive. Release it via [WebViewState.close]
 * (automatic for [rememberWebViewState]).
 *
 * For the lower-level callback-based API see [CefriumWebView].
 *
 * ```kotlin
 * val state = rememberWebViewState("https://example.com")
 * val navigator = rememberWebViewNavigator()
 * WebView(state, Modifier.fillMaxSize(), navigator)
 * ```
 *
 * @param state Hoisted state holder that owns the browser.
 * @param modifier Modifier for the browser view.
 * @param navigator Navigation controller bound to this state.
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

    val browser = remember(state) {
        state.getOrCreateBrowser(activity).also { b ->
            navigator.state = state
            onBrowserReady?.invoke(b)
        }
    }

    AndroidView(
        factory = {
            browser.surfaceContainer.apply {
                // The surface view may still be attached to a previously
                // detached WebView (tab switch). Detach before re-adding.
                (parent as? ViewGroup)?.removeView(this)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        modifier = modifier,
    )

    // No DisposableEffect closing the browser: lifecycle belongs to WebViewState.
}
