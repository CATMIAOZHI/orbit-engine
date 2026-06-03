package com.cefrium.itest.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4

import com.cefrium.CefriumBrowser
import com.cefrium.compose.CefriumNavigator
import com.cefrium.compose.CefriumView
import com.cefrium.compose.CefriumViewState

import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Instrumented tests for the idiomatic Compose API (FEATURE-022):
 * [CefriumView], [CefriumViewState] (browser ownership + tab keep-alive) and
 * [CefriumNavigator] (binding + evaluateJavaScript), plus the Compose-layer
 * wiring of the FEATURE-023 JS bridge (`onQuery`).
 *
 * Run on a device/emulator with:
 *   ./gradlew connectedAndroidTest
 *
 * The CefriumViewState.browser field is `internal` to the compose module, so
 * these tests assert only through the public API (onBrowserReady, observable
 * state, onQuery) — the same surface a real consumer sees.
 */
@RunWith(AndroidJUnit4::class)
class CefriumComposeViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Tab keep-alive: the browser is owned by [CefriumViewState], not by the
     * composable. Detaching [CefriumView] (e.g. switching to another tab) must
     * NOT close the browser; re-attaching must reuse the SAME instance rather
     * than recreate it. Proven via the public onBrowserReady hook: if the
     * browser had been closed on detach, getOrCreateBrowser would hand back a
     * different instance on re-attach.
     */
    @Test
    fun stateKeepsBrowserAliveAcrossDetach() {
        // Caller-managed state (constructed directly), so the test controls
        // close() and the compose rule does not auto-close it.
        val state = CefriumViewState("about:blank")
        val readyBrowsers = CopyOnWriteArrayList<CefriumBrowser>()
        val show = mutableStateOf(true)

        composeRule.setContent {
            if (show.value) {
                CefriumView(
                    state = state,
                    modifier = Modifier,
                    onBrowserReady = { b -> readyBrowsers.add(b) },
                )
            }
        }

        // Browser created once on first composition.
        composeRule.waitUntil(15_000) { readyBrowsers.isNotEmpty() }
        val first = readyBrowsers[0]

        // Detach the view (simulate switching to another tab).
        composeRule.runOnUiThread { show.value = false }
        composeRule.waitForIdle()

        // Re-attach with the SAME state.
        composeRule.runOnUiThread { show.value = true }
        composeRule.waitUntil(15_000) { readyBrowsers.size >= 2 }

        Assert.assertSame(
            "detaching CefriumView must not close/recreate the browser owned by " +
                "CefriumViewState (tab keep-alive)",
            first, readyBrowsers.last(),
        )

        composeRule.runOnUiThread { state.close() }
    }

    /**
     * [CefriumNavigator] is bound to the state by [CefriumView], and its
     * evaluateJavaScript drives the owned browser. We fire a `window.cefriumQuery`
     * from the navigator's evaluateJavaScript and assert the `onQuery` handler
     * wired at the Compose layer receives it — an end-to-end check of
     * Navigator binding + evaluateJavaScript + the JS bridge opt-in, all through
     * the public Compose API.
     */
    @Test
    fun navigatorDrivesBrowserAndQueryBridgeWorks() {
        val state = CefriumViewState("about:blank")
        val navigator = CefriumNavigator()
        val requests = CopyOnWriteArrayList<String>()
        val probeFired = AtomicBoolean(false)
        val browserReady = CountDownLatch(1)

        composeRule.setContent {
            CefriumView(
                state = state,
                modifier = Modifier,
                navigator = navigator,
                onQuery = { request, _, callback ->
                    requests.add(request)
                    callback.success("ok")
                    true
                },
                onBrowserReady = { b ->
                    // Gate the probe on load-complete (V8 context ready), the
                    // same reliable signal the SDK bridge test uses. This
                    // listener coexists with the Callback set by CefriumViewState.
                    b.setOnLoadingStateChangedListener { loading, _, _ ->
                        if (!loading && probeFired.compareAndSet(false, true)) {
                            // Drive JS through the NAVIGATOR (Compose layer), not
                            // the browser directly.
                            navigator.evaluateJavaScript(
                                "window.cefriumQuery({request:'compose-ping'});")
                        }
                    }
                    browserReady.countDown()
                },
            )
        }

        Assert.assertTrue("browser was never created by CefriumView",
            browserReady.await(15, TimeUnit.SECONDS))

        composeRule.waitUntil(20_000) { requests.contains("compose-ping") }
        Assert.assertTrue(
            "navigator.evaluateJavaScript -> window.cefriumQuery should reach the " +
                "onQuery handler wired by CefriumView",
            requests.contains("compose-ping"),
        )

        composeRule.runOnUiThread { state.close() }
    }
}
