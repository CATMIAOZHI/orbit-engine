// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

/**
 * An AndroidX host, as most apps have (AppCompat, Compose): the SDK gets
 * activity results through its result registry, with nothing forwarded.
 */
class ComponentHostActivity : ComponentActivity() {
    lateinit var container: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
    }
}
