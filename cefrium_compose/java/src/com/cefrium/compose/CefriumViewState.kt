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
class CefriumViewState(
    val initialUrl: String,
    // Optional back/forward stack from a previous session (see
    // CefriumBrowser.serializeNavigationState). When set, the browser restores
    // this history instead of loading [initialUrl], so a restored tab can go back.
    val navigationState: ByteArray? = null,
    // FEATURE-057: private tab. Incognito browsers share one off-the-record
    // session isolated from normal tabs; cookies/history/cache are in-memory and
    // cleared when the last incognito browser closes.
    val incognito: Boolean = false,
) : RememberObserver {

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

    /**
     * Set when a renderer process for this page dies (an OEM low-memory kill or a
     * crash). When non-null the page is now blank/dead -- show a reload affordance
     * and reload (e.g. via [CefriumNavigator.reload]). Cleared automatically when
     * the next load starts, so the affordance dismisses itself on recovery.
     */
    var rendererTermination: RendererTermination? by mutableStateOf(null)
        internal set

    internal var browser: CefriumBrowser? = null
        private set

    private var closed = false

    /** Returns the existing browser or creates one (loading [initialUrl] once). */
    internal fun getOrCreateBrowser(activity: Activity): CefriumBrowser {
        browser?.let { return it }
        val b = CefriumBrowser.createWithSurface(activity, incognito)
        b.setCallback(object : CefriumBrowser.Callback() {
            override fun onUrlChanged(url: String) {
                lastLoadedUrl = url
            }

            override fun onTitleChanged(title: String) {
                pageTitle = title
            }

            override fun onLoadingStateChanged(loading: Boolean, back: Boolean, forward: Boolean) {
                // A new load clears any prior renderer-death affordance.
                if (loading) rendererTermination = null
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
        // Renderer-process termination is a separate SDK listener (not part of
        // Callback): surface it as hoisted state so the UI can show a reload banner.
        b.setOnRenderProcessTerminatedListener { status, errorCode ->
            rendererTermination = RendererTermination(rendererStatusOf(status), errorCode)
        }
        browser = b
        if (navigationState != null) {
            // Restore the saved back/forward stack (loads the current entry).
            b.restoreNavigationState(navigationState)
        } else {
            b.loadUrl(initialUrl)
        }
        return b
    }

    /**
     * Forward the hosting Activity's `onActivityResult` to this browser so the
     * file picker (`<input type=file>`, File System Access), camera capture and
     * other intent-for-result flows can deliver their result. Call this from your
     * Activity's `onActivityResult` for every visible state. Returns true if the
     * result was consumed.
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean {
        return browser?.onActivityResult(requestCode, resultCode, data) ?: false
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
fun rememberCefriumViewState(
    url: String,
    incognito: Boolean = false,
): CefriumViewState =
    remember(url, incognito) { CefriumViewState(url, incognito = incognito) }

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

/** Why a renderer process terminated. See [RendererTermination]. */
enum class RendererStatus {
    /** Abnormal exit, reason not otherwise classified. */
    ABNORMAL,
    /** Killed, e.g. by the OS / OEM low-memory killer. */
    KILLED,
    /** The renderer crashed. */
    CRASHED,
    /** The renderer ran out of memory. */
    OOM,
    /** A status this SDK version does not map (forward-compatibility). */
    UNKNOWN,
}

/**
 * A renderer-process termination for the page, delivered via
 * [CefriumViewState.rendererTermination]. [errorCode] is the platform error code
 * (0 if none).
 */
data class RendererTermination(val status: RendererStatus, val errorCode: Int)

/** Maps the SDK's CefriumBrowser.TERMINATION_* int to [RendererStatus]. */
private fun rendererStatusOf(status: Int): RendererStatus = when (status) {
    CefriumBrowser.TERMINATION_ABNORMAL -> RendererStatus.ABNORMAL
    CefriumBrowser.TERMINATION_KILLED -> RendererStatus.KILLED
    CefriumBrowser.TERMINATION_CRASHED -> RendererStatus.CRASHED
    CefriumBrowser.TERMINATION_OOM -> RendererStatus.OOM
    else -> RendererStatus.UNKNOWN
}
