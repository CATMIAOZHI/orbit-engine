// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest

import android.app.Activity
import android.content.Intent
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

    /** Where a test forwards results, as an app with a plain Activity does. */
    var onResult: ((Int, Int, Intent?) -> Unit)? = null

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        onResult?.invoke(requestCode, resultCode, data)
    }

    /** Where a test forwards permission results, as onResult. */
    var onPermissions: ((Int, Array<String>, IntArray) -> Unit)? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onPermissions?.invoke(requestCode, permissions, grantResults)
    }
}
