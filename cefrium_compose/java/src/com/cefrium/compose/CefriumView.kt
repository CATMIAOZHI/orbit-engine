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
 * Idiomatic Compose view backed by a full Chromium engine (Cefrium).
 *
 * State-hoisting API: observe through [CefriumViewState], drive navigation
 * through [CefriumNavigator]. The browser's lifecycle belongs to [state], not
 * to this composable — so detaching this view (e.g. switching tabs) does NOT
 * close the browser; the page stays alive. Release it via
 * [CefriumViewState.close] (automatic for [rememberCefriumViewState]).
 *
 * For the lower-level callback-based API see [CefriumWebView].
 *
 * ```kotlin
 * val state = rememberCefriumViewState("https://example.com")
 * val navigator = rememberCefriumNavigator()
 * CefriumView(state, Modifier.fillMaxSize(), navigator)
 * ```
 *
 * @param state Hoisted state holder that owns the browser.
 * @param modifier Modifier for the browser view.
 * @param navigator Navigation controller bound to this state.
 * @param onQuery Handles `window.cefriumQuery` calls from page JavaScript.
 *        Return true and resolve via the callback (sync or async). Omit to keep
 *        the JS bridge disabled for this view (queries then fail with -1).
 * @param onBrowserReady Escape hatch to the underlying [CefriumBrowser] for
 *        features not yet on the navigator (find in page, downloads, dark mode).
 */
@Composable
fun CefriumView(
    state: CefriumViewState,
    modifier: Modifier = Modifier,
    navigator: CefriumNavigator = rememberCefriumNavigator(),
    onQuery: ((request: String, origin: String, callback: CefriumBrowser.QueryCallback) -> Boolean)? = null,
    onBrowserReady: ((CefriumBrowser) -> Unit)? = null,
) {
    val activity = LocalContext.current as Activity

    val browser = remember(state) {
        state.getOrCreateBrowser(activity).also { b ->
            navigator.state = state
            // JS bridge opt-in: window.cefriumQuery is handled only if the host
            // provides onQuery (FEATURE-023). Without it, queries fail with -1 —
            // a deliberate security default (the bridge is off unless asked for).
            if (onQuery != null) {
                b.setQueryHandler { _, request, origin, callback ->
                    onQuery(request, origin, callback)
                }
            }
            onBrowserReady?.invoke(b)
        }
    }

    AndroidView(
        factory = {
            browser.surfaceContainer.apply {
                // The surface view may still be attached to a previously
                // detached CefriumView (tab switch). Detach before re-adding.
                (parent as? ViewGroup)?.removeView(this)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        modifier = modifier,
    )

    // No DisposableEffect closing the browser: lifecycle belongs to CefriumViewState.
}
