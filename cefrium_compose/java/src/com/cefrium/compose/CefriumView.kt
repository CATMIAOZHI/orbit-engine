// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.compose

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.cefrium.CefriumBrowser

/**
 * How a [CefriumView] reacts to the host lifecycle for INLINE MEDIA. The default
 * ([PAUSE_ON_STOP]) is what most apps want: a video/audio inside the page stops
 * when the host is backgrounded (HOME / recents) and resumes on return. Pick
 * another policy -- or pass `onHostLifecycle` to [CefriumView] for full control --
 * if you need different behavior (e.g. a music player that keeps playing).
 */
enum class BackgroundMediaPolicy {
    /** Pause media + hide page on ON_STOP (backgrounded); resume on ON_START. Default. */
    PAUSE_ON_STOP,
    /** Pause already on ON_PAUSE (partially obscured, e.g. a dialog); resume on ON_RESUME. */
    PAUSE_ON_PAUSE,
    /** Only mute audio on ON_STOP (media keeps decoding/advancing); unmute on ON_START. */
    MUTE_ON_STOP,
    /** Do nothing -- the host manages media itself (e.g. background audio playback). */
    NONE,
}

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
 * @param bridge Handles `window.cefriumQuery` calls from page JavaScript, for
 *        the origins it names (see [CefriumBridge]). Omit to keep the JS bridge
 *        disabled for this view (queries then fail with -1).
 * @param onBrowserReady Escape hatch to the underlying [CefriumBrowser] for
 *        features not yet on the navigator (find in page, downloads, dark mode).
 * @param backgroundMediaPolicy How inline media reacts to the host lifecycle.
 *        Default [BackgroundMediaPolicy.PAUSE_ON_STOP] stops a playing video/audio
 *        when the host is backgrounded and resumes on return -- zero work for the
 *        consumer. Set [BackgroundMediaPolicy.NONE] to keep playing (background
 *        audio), or pass [onHostLifecycle] for full control.
 * @param onHostLifecycle If non-null, OVERRIDES [backgroundMediaPolicy]: called on
 *        every host lifecycle event with the [CefriumBrowser], so the host can
 *        implement any custom media/visibility policy (mute, pause, stop, nothing).
 */
@Composable
fun CefriumView(
    state: CefriumViewState,
    modifier: Modifier = Modifier,
    navigator: CefriumNavigator = rememberCefriumNavigator(),
    bridge: CefriumBridge? = null,
    onBrowserReady: ((CefriumBrowser) -> Unit)? = null,
    backgroundMediaPolicy: BackgroundMediaPolicy = BackgroundMediaPolicy.PAUSE_ON_STOP,
    onHostLifecycle: ((Lifecycle.Event, CefriumBrowser) -> Unit)? = null,
) {
    val activity = LocalContext.current as Activity

    val browser = remember(state) {
        state.getOrCreateBrowser(activity).also { b ->
            navigator.state = state
            // JS bridge opt-in: window.cefriumQuery is handled only if the host
            // provides a bridge. Without it, queries fail with -1 --
            // a deliberate security default (the bridge is off unless asked for).
            if (bridge != null) {
                b.setQueryHandler(bridge.origins, object : CefriumBrowser.QueryHandler {
                    override fun onQuery(
                        queryId: Long,
                        request: String,
                        origin: String,
                        callback: CefriumBrowser.QueryCallback,
                    ) = bridge.onQuery(request, origin, callback)

                    override fun onQuery(
                        queryId: Long,
                        request: String,
                        origin: String,
                        isMainFrame: Boolean,
                        callback: CefriumBrowser.QueryCallback,
                    ) = bridge.onFrameQuery?.invoke(request, origin, isMainFrame, callback)
                        ?: bridge.onQuery(request, origin, callback)
                })
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

    // Auto-wire inline-media behavior to the host lifecycle (the browser itself
    // stays alive across background -- this only suspends/resumes media). The host
    // Activity is the LifecycleOwner; if it is not one (rare), wiring is skipped.
    val lifecycleOwner = activity as? LifecycleOwner
    if (lifecycleOwner != null && (onHostLifecycle != null ||
            backgroundMediaPolicy != BackgroundMediaPolicy.NONE)) {
        DisposableEffect(lifecycleOwner, browser, backgroundMediaPolicy, onHostLifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                val override = onHostLifecycle
                if (override != null) {
                    override(event, browser)
                    return@LifecycleEventObserver
                }
                when (backgroundMediaPolicy) {
                    BackgroundMediaPolicy.PAUSE_ON_STOP -> when (event) {
                        Lifecycle.Event.ON_STOP -> browser.onPause()
                        Lifecycle.Event.ON_START -> browser.onResume()
                        else -> {}
                    }
                    BackgroundMediaPolicy.PAUSE_ON_PAUSE -> when (event) {
                        Lifecycle.Event.ON_PAUSE -> browser.onPause()
                        Lifecycle.Event.ON_RESUME -> browser.onResume()
                        else -> {}
                    }
                    BackgroundMediaPolicy.MUTE_ON_STOP -> when (event) {
                        Lifecycle.Event.ON_STOP -> browser.setAudioMuted(true)
                        Lifecycle.Event.ON_START -> browser.setAudioMuted(false)
                        else -> {}
                    }
                    BackgroundMediaPolicy.NONE -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
}
