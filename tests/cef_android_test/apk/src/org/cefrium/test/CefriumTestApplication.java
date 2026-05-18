// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.cefrium.test;

import android.app.Application;
import android.content.Context;

import org.chromium.base.ApplicationStatus;
import org.chromium.base.CommandLine;
import org.chromium.base.ContextUtils;
import org.chromium.base.PathUtils;
import org.chromium.base.library_loader.LibraryLoader;
import org.chromium.base.library_loader.LibraryProcessType;
import org.chromium.ui.base.ResourceBundle;

/**
 * Application class for Cefrium test APK. Handles initialization shared
 * between the browser process and child service processes.
 *
 * Child processes (renderer, GPU, utility) run as Android Services in
 * separate OS processes. The process name contains ":" for child processes.
 */
public class CefriumTestApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        boolean isBrowserProcess = !ContextUtils.getProcessName().contains(":");
        ContextUtils.initApplicationContext(this);

        // Configure locale paks before library loading.
        ResourceBundle.setAvailablePakLocales(new String[]{"en-US"});

        // Tell LibraryLoader which process type this is. This must happen
        // before ensureInitialized() is called.
        LibraryLoader.getInstance()
                .setLibraryProcessType(
                        isBrowserProcess
                                ? LibraryProcessType.PROCESS_BROWSER
                                : LibraryProcessType.PROCESS_CHILD);

        if (isBrowserProcess) {
            PathUtils.setPrivateDataDirectorySuffix("cefrium");
            ApplicationStatus.initialize(this);
        }
    }
}
