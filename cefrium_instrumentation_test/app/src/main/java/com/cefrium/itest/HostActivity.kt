package com.cefrium.itest

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/**
 * Minimal Activity host for SDK instrumented tests that need an [Activity] to
 * create a browser surface against. Replaces the sample's SurfaceActivity so
 * these tests no longer depend on the (separate) samples repo.
 */
class HostActivity : Activity() {
    lateinit var container: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
    }
}
