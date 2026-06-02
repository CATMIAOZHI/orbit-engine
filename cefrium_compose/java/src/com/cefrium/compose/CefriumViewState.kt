package com.cefrium.compose

import android.app.Activity
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cefrium.CefriumBrowser

/**
 * Hoisted state for [CefriumView], created with [rememberCefriumViewState].
 *
 * The underlying [CefriumBrowser] is owned by this state, NOT by the
 * [CefriumView] composable. That lets the browser survive the composable being
 * detached — e.g. when switching browser tabs the inactive tab keeps its page
 * alive instead of reloading. The browser is released by [close]:
 *  - automatically when a state created via [rememberCefriumViewState] leaves
 *    composition (via [RememberObserver]);
 *  - explicitly by the caller for states constructed directly (e.g. one per
 *    tab, closed when the tab closes).
 */
@Stable
class CefriumViewState(val initialUrl: String) : RememberObserver {

    var lastLoadedUrl: String? by mutableStateOf(null)
        internal set

    var pageTitle: String? by mutableStateOf(null)
        internal set

    var isLoading: Boolean by mutableStateOf(false)
        internal set

    var canGoBack: Boolean by mutableStateOf(false)
        internal set

    var canGoForward: Boolean by mutableStateOf(false)
        internal set

    internal var browser: CefriumBrowser? = null
        private set

    private var closed = false

    /** Returns the existing browser or creates one (loading [initialUrl] once). */
    internal fun getOrCreateBrowser(activity: Activity): CefriumBrowser {
        browser?.let { return it }
        val b = CefriumBrowser.createWithSurface(activity)
        b.setCallback(object : CefriumBrowser.Callback() {
            override fun onUrlChanged(url: String) {
                lastLoadedUrl = url
            }

            override fun onTitleChanged(title: String) {
                pageTitle = title
            }

            override fun onLoadingStateChanged(loading: Boolean, back: Boolean, forward: Boolean) {
                isLoading = loading
                canGoBack = back
                canGoForward = forward
            }
        })
        browser = b
        b.loadUrl(initialUrl)
        return b
    }

    /** Releases the browser. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        browser?.close()
        browser = null
    }

    // RememberObserver — auto-close for states made via rememberCefriumViewState.
    override fun onRemembered() {}
    override fun onForgotten() = close()
    override fun onAbandoned() = close()
}

/**
 * Remembers a [CefriumViewState] for [url]. The browser is released
 * automatically when this leaves composition. For caller-managed lifecycles
 * (e.g. a browser with tabs), construct [CefriumViewState] directly and call
 * [CefriumViewState.close].
 */
@Composable
fun rememberCefriumViewState(url: String): CefriumViewState =
    remember(url) { CefriumViewState(url) }
