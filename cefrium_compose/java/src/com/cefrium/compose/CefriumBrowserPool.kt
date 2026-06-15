package com.cefrium.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.cefrium.CefriumBrowser

/**
 * A bounded LRU pool of [CefriumViewState] for screens that show many different
 * pages over time -- e.g. a list of lessons/articles, each rendered in a
 * [CefriumView].
 *
 * Why this exists: a Cefrium browser is HEAVY (a full Chromium renderer process,
 * tens-to-hundreds of MB -- far more than a WebView). Keeping one alive per item
 * you ever opened grows memory without bound. This pool keeps at most [maxAlive]
 * browsers alive and CLOSES the least-recently-used one when you open another, so
 * memory stays bounded while the most recent N keep their state (scroll, video
 * position) for instant return.
 *
 * For the simplest case -- showing one page at a time -- you do NOT need a pool:
 * reuse a single [CefriumView] and swap content with `navigator.loadUrl(...)` /
 * `loadHtmlWithBaseUrl(...)`. Use a pool only when you want the last few pages to
 * stay warm.
 *
 * ```kotlin
 * val pool = rememberCefriumBrowserPool(maxAlive = 2)
 * val state = pool.stateFor(lesson.id, lesson.url)   // reuses or creates
 * CefriumView(state = state, navigator = navigator)
 * // Opening a 3rd lesson closes the least-recently-used browser automatically.
 * ```
 *
 * The pool owns its states: when it leaves composition every pooled browser is
 * closed. Do NOT also wrap a pooled state in [rememberCefriumViewState] (that would
 * double-own it).
 */
@Stable
class CefriumBrowserPool internal constructor(private val maxAlive: Int) : RememberObserver {

    init { require(maxAlive >= 1) { "maxAlive must be >= 1, was $maxAlive" } }

    // Access-order map == LRU: get()/getOrPut touch an entry to most-recent; the
    // eldest is the least-recently-used. removeEldestEntry closes + evicts it.
    private val states =
        object : LinkedHashMap<String, CefriumViewState>(maxAlive + 1, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CefriumViewState>,
            ): Boolean {
                if (size > maxAlive) {
                    eldest.value.close()
                    // Reap the closed browser's orphaned render process so the
                    // sandboxed-process count stays bounded (process-per-browser
                    // devices); no-op where renderers are shared.
                    CefriumBrowser.reapUnusedRenderers()
                    return true
                }
                return false
            }
        }

    /**
     * Returns the pooled state for [key], creating one that loads [url] on first
     * use. Touching a key makes it most-recently-used; opening a new key beyond
     * [maxAlive] closes and evicts the least-recently-used state. [url] is used
     * only when the state is first created for [key].
     */
    fun stateFor(key: String, url: String): CefriumViewState =
        states.getOrPut(key) { CefriumViewState(url) }

    /** Closes and evicts the state for [key] (no-op if not pooled). */
    fun evict(key: String) {
        states.remove(key)?.let {
            it.close()
            CefriumBrowser.reapUnusedRenderers()
        }
    }

    /** Number of states currently held alive by the pool. */
    val size: Int get() = states.size

    override fun onRemembered() {}
    override fun onForgotten() = closeAll()
    override fun onAbandoned() = closeAll()

    private fun closeAll() {
        states.values.forEach { it.close() }
        states.clear()
    }
}

/**
 * Remembers a [CefriumBrowserPool] that keeps at most [maxAlive] browsers alive,
 * closing the least-recently-used when you open another. Every pooled browser is
 * released when this leaves composition. See [CefriumBrowserPool] for the pattern.
 */
@Composable
fun rememberCefriumBrowserPool(maxAlive: Int = 2): CefriumBrowserPool =
    remember(maxAlive) { CefriumBrowserPool(maxAlive) }
