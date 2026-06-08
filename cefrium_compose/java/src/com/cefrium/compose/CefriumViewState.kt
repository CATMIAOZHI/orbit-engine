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

    /** True while a page element is in HTML5 fullscreen. */
    var isFullscreen: Boolean by mutableStateOf(false)
        internal set

    /** Find-in-page: 1-based ordinal of the active match (0 = none). */
    var findActiveMatch: Int by mutableStateOf(0)
        internal set

    /** Find-in-page: total matches for the current query (0 = none). */
    var findMatchCount: Int by mutableStateOf(0)
        internal set

    /** The most recent download update (null until the first download). */
    var lastDownload: DownloadInfo? by mutableStateOf(null)
        internal set

    /**
     * A pending context-menu request from the page, or null. When non-null, show
     * your menu UI, call [CefriumNavigator.contextMenuCommand] with the chosen
     * command id, then set this back to null to dismiss.
     */
    var contextMenu: ContextMenuInfo? by mutableStateOf(null)

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

            override fun onFullscreenChanged(fullscreen: Boolean) {
                isFullscreen = fullscreen
            }

            override fun onFindResult(activeMatch: Int, totalMatches: Int) {
                findActiveMatch = activeMatch
                findMatchCount = totalMatches
            }

            override fun onDownloadUpdated(
                name: String, path: String, percent: Int,
                complete: Boolean, canceled: Boolean,
            ) {
                lastDownload = DownloadInfo(name, path, percent, complete, canceled)
            }

            override fun onContextMenu(menuItems: String, url: String) {
                contextMenu = ContextMenuInfo(menuItems, url)
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

/** A download progress update, delivered via [CefriumViewState.lastDownload]. */
data class DownloadInfo(
    val name: String,
    val path: String,
    val percent: Int,
    val complete: Boolean,
    val canceled: Boolean,
)

/**
 * A pending context-menu request, delivered via [CefriumViewState.contextMenu].
 * [menuItems] is the engine's serialized menu (the same payload the View API's
 * context-menu listener receives); [url] is the link/page URL under the cursor.
 */
data class ContextMenuInfo(val menuItems: String, val url: String)
