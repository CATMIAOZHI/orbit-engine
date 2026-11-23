// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest

import android.app.Application
import android.content.Context
import android.util.Log
import com.cefrium.Cefrium
import org.chromium.base.ApplicationStatus
import org.chromium.base.CommandLine
import org.chromium.base.ContextUtils
import org.chromium.base.PathUtils
import org.chromium.base.library_loader.LibraryLoader
import org.chromium.base.library_loader.LibraryProcessType

/**
 * CEF process init for the instrumentation harness. Mirrors the canonical
 * SampleApplication: pass `this` to initApplicationContext and set the library
 * process type for BOTH browser and child processes before Cefrium.initialize.
 * Diverging from this trips Chromium asserts (null context;
 * PROCESS_UNINITIALIZED).
 */
class TestHostApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val isBrowserProcess = !ContextUtils.getProcessName().contains(":")
        ContextUtils.initApplicationContext(this)
        LibraryLoader.getInstance().setLibraryProcessType(
            if (isBrowserProcess) LibraryProcessType.PROCESS_BROWSER
            else LibraryProcessType.PROCESS_CHILD
        )
        if (isBrowserProcess) {
            PathUtils.setPrivateDataDirectorySuffix("cefrium")
            ApplicationStatus.initialize(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val isBrowserProcess = !ContextUtils.getProcessName().contains(":")
        if (isBrowserProcess) {
            // Out-of-process iframes, as Google's sign-in iframe always is:
            // a 127.0.0.1 frame in a localhost page gets its own process
            // (Chromium refuses to isolate localhost itself).
            if (!CommandLine.isInitialized()) CommandLine.init(null)
            CommandLine.getInstance().appendSwitchWithValue("isolate-origins", "http://127.0.0.1")
            val ok = Cefrium.initialize(this)
            Log.i("CefriumITest", "Cefrium.initialize: ${if (ok) "OK" else "FAILED"}")
        }
    }
}
