// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

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
 * Entry point for the Cefrium SDK. Initialize once before creating browsers.
 *
 * <pre>
 * // In Application.onCreate() or Activity.onCreate():
 * Cefrium.initialize(context);
 *
 * // Create browsers:
 * CefriumBrowser browser = new CefriumBrowser();
 * browser.loadUrl("https://example.com");
 *
 * // When done:
 * Cefrium.shutdown();
 * </pre>
 */
public final class Cefrium {
    private static boolean sInitialized;

    private Cefrium() {}

    /**
     * Initialize the Cefrium engine. Must be called once from the main thread
     * before creating any browsers.
     *
     * @param context Application context.
     * @return true if initialization succeeded.
     */
    public static synchronized boolean initialize(Context context) {
        if (sInitialized) {
            return true;
        }

        Context appContext = context.getApplicationContext();

        // Base Chromium utilities.
        ContextUtils.initApplicationContext(appContext);
        if (!CommandLine.isInitialized()) {
            CommandLine.init(null);
        }
        CommandLine.getInstance().appendSwitch("in-process-gpu");
        CommandLine.getInstance().appendSwitch("no-sandbox");
        PathUtils.setPrivateDataDirectorySuffix("cefrium");
        if (appContext instanceof Application) {
            ApplicationStatus.initialize((Application) appContext);
        }
        ResourceBundle.setAvailablePakLocales(new String[]{"en-US"});

        // Library loading via LibraryLoader (supports multi-process).
        LibraryLoader.getInstance()
                .setLibraryProcessType(LibraryProcessType.PROCESS_BROWSER);

        try {
            LibraryLoader.getInstance().ensureInitialized();
        } catch (Exception e) {
            return false;
        }

        // Initialize CEF native layer.
        String cacheDir = appContext.getCacheDir().getAbsolutePath();
        String dataDir = appContext.getFilesDir().getAbsolutePath();
        int result = nativeInitialize(cacheDir, dataDir);
        if (result != 0) {
            return false;
        }

        sInitialized = true;
        return true;
    }

    /**
     * Shut down the Cefrium engine. Call when the app is finishing.
     * After this call, no Cefrium APIs may be used.
     */
    public static synchronized void shutdown() {
        if (sInitialized) {
            nativeShutdown();
            sInitialized = false;
        }
    }

    /**
     * @return true if Cefrium has been initialized.
     */
    public static boolean isInitialized() {
        return sInitialized;
    }

    // Native methods implemented in libcef.so.
    private static native int nativeInitialize(String cacheDir, String dataDir);
    private static native void nativeShutdown();
}
