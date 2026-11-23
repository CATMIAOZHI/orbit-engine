// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/**
 * An Activity that can enter picture-in-picture (CefriumPipTest). It reports
 * its lifecycle to [lifecycle], where a test forwards it to the browser the
 * way an app does.
 */
class PipHostActivity : Activity() {
    lateinit var container: FrameLayout
        private set

    var lifecycle: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
    }

    override fun onStart() { super.onStart(); lifecycle?.invoke("start") }
    override fun onResume() { super.onResume(); lifecycle?.invoke("resume") }
    override fun onPause() { lifecycle?.invoke("pause"); super.onPause() }
    override fun onStop() { lifecycle?.invoke("stop"); super.onStop() }
}
