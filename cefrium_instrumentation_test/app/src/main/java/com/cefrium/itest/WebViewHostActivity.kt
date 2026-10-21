package com.cefrium.itest

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/**
 * Hosts the SYSTEM WebView in its own process (":wv" in the manifest), so a
 * test can start WebView in this package before Cefrium. WebView binds its
 * sandboxed renderer service under this app's package; CefriumCoexistenceTest
 * checks that Cefrium's renderer is not handed that service's process.
 *
 * Reports through a file because the test runs in another process: the file
 * appears once the page has finished loading in the WebView.
 */
class WebViewHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ready = File(filesDir, READY_FILE)
        ready.delete()
        val url = intent.getStringExtra(EXTRA_URL) ?: "about:blank"
        val webView = WebView(this)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                if (finishedUrl == url) ready.writeText(url)
            }
        }
        setContentView(webView)
        webView.loadUrl(url)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val READY_FILE = "webview-ready"
    }
}
