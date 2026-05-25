package com.cefrium.compose

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.cefrium.CefriumBrowser

/**
 * Composable that embeds a Cefrium browser with Surface rendering.
 *
 * Handles browser lifecycle, URL/title callbacks, and cleanup.
 * Video playback, audio, and ad blocking all work.
 *
 * ```kotlin
 * CefriumWebView(
 *     url = "https://example.com",
 *     modifier = Modifier.fillMaxSize(),
 *     onUrlChanged = { url -> /* update state */ },
 *     onTitleChanged = { title -> /* update title */ },
 *     onLoadingStateChanged = { loading, canGoBack, canGoForward -> },
 *     onFullscreenChanged = { fullscreen -> }
 * )
 * ```
 *
 * @param url The URL to load. Changes to this value trigger navigation.
 * @param modifier Modifier for the browser view.
 * @param onUrlChanged Called when the browser navigates to a new URL.
 * @param onTitleChanged Called when the page title changes.
 * @param onLoadingStateChanged Called when loading state changes.
 * @param onFullscreenChanged Called when fullscreen mode changes.
 * @param onRequestIntercepted Called for every network request (for inspection).
 * @param onBrowserReady Called with the CefriumBrowser instance for direct control.
 */
@Composable
fun CefriumWebView(
    url: String,
    modifier: Modifier = Modifier,
    onUrlChanged: ((String) -> Unit)? = null,
    onTitleChanged: ((String) -> Unit)? = null,
    onLoadingStateChanged: ((Boolean, Boolean, Boolean) -> Unit)? = null,
    onFullscreenChanged: ((Boolean) -> Unit)? = null,
    onRequestIntercepted: ((String, String, Boolean) -> Unit)? = null,
    onBrowserReady: ((CefriumBrowser) -> Unit)? = null
) {
    val activity = LocalContext.current as Activity
    var currentUrl by remember { mutableStateOf(url) }

    val browser = remember {
        CefriumBrowser.createWithSurface(activity).also { b ->
            onUrlChanged?.let { cb ->
                b.setOnUrlChangedListener { u -> cb(u) }
            }
            onTitleChanged?.let { cb ->
                b.setOnTitleChangedListener { t -> cb(t) }
            }
            onLoadingStateChanged?.let { cb ->
                b.setOnLoadingStateChangedListener { loading, back, fwd ->
                    cb(loading, back, fwd)
                }
            }
            onFullscreenChanged?.let { cb ->
                b.setOnFullscreenChangedListener { fs -> cb(fs) }
            }
            onRequestIntercepted?.let { cb ->
                b.setOnRequestInterceptedListener { u, m, blocked ->
                    cb(u, m, blocked)
                }
            }
            onBrowserReady?.invoke(b)
        }
    }

    // Navigate when url parameter changes.
    LaunchedEffect(url) {
        if (url != currentUrl) {
            currentUrl = url
            browser.loadUrl(url)
        }
    }

    // Initial load.
    LaunchedEffect(Unit) {
        browser.loadUrl(url)
    }

    // Cleanup on dispose.
    DisposableEffect(Unit) {
        onDispose {
            browser.close()
        }
    }

    AndroidView(
        factory = {
            browser.surfaceContainer.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = modifier
    )
}
