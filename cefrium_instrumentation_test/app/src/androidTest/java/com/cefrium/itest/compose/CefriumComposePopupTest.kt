// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4

import com.cefrium.CefriumBrowser
import com.cefrium.compose.CefriumView
import com.cefrium.compose.CefriumViewState
import com.cefrium.compose.CefriumWindow

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FEATURE-086 in Compose: CefriumView(onCreateWindow) gives window.open a
 * browser shown with CefriumWindow; the page keeps window.opener and the
 * popup's window.close() reaches onCloseWindow.
 */
@RunWith(AndroidJUnit4::class)
class CefriumComposePopupTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val page = "<html><body><script>" +
        "if (location.search == '?child') {" +
        "  if (window.opener) window.opener.postMessage('from-popup', '*');" +
        "  setTimeout(() => window.close(), 300);" +
        "} else {" +
        "  addEventListener('message', e => document.title = 'got:' + e.data);" +
        "  document.title = 'ready';" +
        "}</script></body></html>"

    @Test
    fun popupKeepsItsOpenerAndCloses() {
        val server = com.cefrium.itest.LocalHttpServer(page)
        try {
            val state = CefriumViewState(server.url())
            var browser: CefriumBrowser? = null
            var created = 0
            var closed = 0
            composeRule.setContent {
                var popup by remember { mutableStateOf<CefriumBrowser?>(null) }
                CefriumView(
                    state = state,
                    modifier = Modifier,
                    onBrowserReady = { browser = it },
                    onCreateWindow = { p, _ ->
                        created++
                        popup = p
                        true
                    },
                    onCloseWindow = {
                        closed++
                        popup = null
                    },
                )
                popup?.let { CefriumWindow(it) }
            }
            composeRule.waitUntil(20_000) { state.pageTitle == "ready" }
            composeRule.runOnUiThread {
                browser!!.executeDevToolsMethod(
                    "Runtime.evaluate",
                    "{\"expression\":\"window.open('/?child','w');1\",\"userGesture\":true}",
                ) { _, _ -> }
            }
            composeRule.waitUntil(20_000) { state.pageTitle == "got:from-popup" }
            composeRule.waitUntil(10_000) { closed == 1 }
            Assert.assertEquals(1, created)
        } finally {
            server.close()
        }
    }

    private val tabPage = "<html><body><script>" +
        "if (location.search == '?tab') {" +
        "  document.title = 'child';" +
        "  if (window.opener) window.opener.postMessage('from-tab', '*');" +
        "} else {" +
        "  addEventListener('message', e => document.title = 'got:' + e.data);" +
        "  document.title = 'ready';" +
        "}" +
        "document.addEventListener('fullscreenchange', () =>" +
        " document.title = document.fullscreenElement ? 'in' : 'out');" +
        "</script><div id=v style='height:100vh' onclick='this.requestFullscreen()'>" +
        "v</div></body></html>"

    /**
     * A window adopted after its page already set its title and URL reports
     * them: forWindow takes what the window has, not only what changes later.
     * (The window loads on its own; a title set before the tab was composed
     * was lost, and the tab stayed untitled.)
     */
    @Test
    fun windowAdoptedLateKeepsItsTitleAndUrl() {
        val server = com.cefrium.itest.LocalHttpServer(tabPage)
        val opener = CefriumViewState(server.url())
        try {
            var openerBrowser: CefriumBrowser? = null
            var popup: CefriumBrowser? = null
            var popupUrl: String? = null
            var tab by mutableStateOf<CefriumViewState?>(null)
            composeRule.setContent {
                val t = tab
                if (t == null) {
                    CefriumView(
                        state = opener,
                        onBrowserReady = { openerBrowser = it },
                        onCreateWindow = { p, url ->
                            popup = p
                            popupUrl = url
                            true
                        },
                    )
                } else {
                    CefriumView(state = t)
                }
            }
            composeRule.waitUntil(20_000) { opener.pageTitle == "ready" }
            composeRule.runOnUiThread {
                openerBrowser!!.executeDevToolsMethod(
                    "Runtime.evaluate",
                    "{\"expression\":\"window.open('/?tab','w');1\",\"userGesture\":true}",
                ) { _, _ -> }
            }
            // Adopt only once the window's page has titled itself.
            composeRule.waitUntil(20_000) {
                var title: String? = null
                composeRule.runOnUiThread { title = popup?.title }
                title == "child"
            }
            composeRule.runOnUiThread { tab = CefriumViewState.forWindow(popup!!, popupUrl!!) }
            composeRule.waitUntil(10_000) { tab?.pageTitle == "child" }
            Assert.assertTrue("url: " + tab!!.lastLoadedUrl,
                tab!!.lastLoadedUrl?.endsWith("/?tab") == true)
        } finally {
            server.close()
        }
    }

    /**
     * A browser shows the window as a tab: CefriumViewState.forWindow gives it
     * its URL, title, history and fullscreen, and keeps window.opener. (Shown
     * bare, a window's fullscreen never reached the app: the bars stayed and
     * the video got the space left under them.)
     */
    @Test
    fun windowAsATabReportsLikeAPage() {
        val server = com.cefrium.itest.LocalHttpServer(tabPage)
        val opener = CefriumViewState(server.url())
        try {
            var openerBrowser: CefriumBrowser? = null
            var tabBrowser: CefriumBrowser? = null
            var tab by mutableStateOf<CefriumViewState?>(null)
            var closed = 0
            composeRule.setContent {
                val t = tab
                if (t == null) {
                    CefriumView(
                        state = opener,
                        onBrowserReady = { openerBrowser = it },
                        onCreateWindow = { p, url ->
                            tab = CefriumViewState.forWindow(p, url)
                            true
                        },
                        onCloseWindow = { closed++ },
                    )
                } else {
                    CefriumView(state = t, onBrowserReady = { tabBrowser = it })
                }
            }
            composeRule.waitUntil(20_000) { opener.pageTitle == "ready" }
            composeRule.runOnUiThread {
                openerBrowser!!.executeDevToolsMethod(
                    "Runtime.evaluate",
                    "{\"expression\":\"window.open('/?tab','w');1\",\"userGesture\":true}",
                ) { _, _ -> }
            }
            composeRule.waitUntil(20_000) { tab?.pageTitle == "child" }
            val t = tab!!
            Assert.assertTrue("url: " + t.lastLoadedUrl, t.lastLoadedUrl!!.endsWith("/?tab"))
            Assert.assertFalse(t.canGoBack)
            composeRule.waitUntil(10_000) { opener.pageTitle == "got:from-tab" }

            // A tap, as on a real site: Chromium refuses fullscreen to a view
            // without focus, and the tap gives the browser's view focus.
            Thread.sleep(1000)
            tap(tabBrowser!!)
            composeRule.waitUntil(10_000) { t.isFullscreen && t.pageTitle == "in" }
            composeRule.runOnUiThread {
                tabBrowser!!.executeDevToolsMethod(
                    "Runtime.evaluate",
                    "{\"expression\":\"document.exitFullscreen();1\",\"userGesture\":true}",
                ) { _, _ -> }
            }
            val left = java.util.concurrent.atomic.AtomicReference<String>("?")
            try {
                composeRule.waitUntil(10_000) { !t.isFullscreen && t.pageTitle == "out" }
            } catch (e: Throwable) {
                composeRule.runOnUiThread {
                    tabBrowser!!.executeDevToolsMethod(
                        "Runtime.evaluate",
                        "{\"expression\":\"String(!!document.fullscreenElement)\"}",
                    ) { _, r -> left.set(r) }
                }
                Thread.sleep(2000)
                throw AssertionError("exit not reported; page fullscreenElement: " + left.get(), e)
            }

            composeRule.runOnUiThread {
                openerBrowser!!.executeDevToolsMethod(
                    "Runtime.evaluate",
                    "{\"expression\":\"document.title='closing'; " +
                        "window.open('', 'w').close();1\"}",
                ) { _, _ -> }
            }
            composeRule.waitUntil(10_000) { closed == 1 }
            composeRule.runOnUiThread { t.close() }
        } finally {
            composeRule.runOnUiThread { opener.close() }
            server.close()
        }
    }

    /** A touch at the centre of the browser's view, dispatched on the UI thread. */
    private fun tap(b: CefriumBrowser) {
        val down = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            composeRule.runOnUiThread {
                val v: View = b.surfaceContainer
                val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    v.width / 2f, v.height / 2f, 0)
                ev.source = InputDevice.SOURCE_TOUCHSCREEN
                v.dispatchTouchEvent(ev)
                ev.recycle()
            }
            Thread.sleep(60)
        }
    }
}
