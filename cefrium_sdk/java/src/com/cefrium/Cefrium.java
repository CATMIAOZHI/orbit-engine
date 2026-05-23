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
 * <h3>User-Agent</h3>
 *
 * <p>Cefrium identifies itself as Chrome (because it IS Chromium) with a
 * Cefrium product token appended:</p>
 *
 * <pre>
 * Mozilla/5.0 (Linux; Android ...) AppleWebKit/537.36 (KHTML, like Gecko)
 *     Chrome/148.0.7778.167 Cefrium/1.0 Mobile Safari/537.36
 * </pre>
 *
 * <p>This ensures full site compatibility while transparently identifying
 * the client. The User-Agent is configured via {@code CefSettings.user_agent_product}
 * in the native layer. To customize it, use the {@code --user-agent-product}
 * command-line switch before calling {@link #initialize}:</p>
 *
 * <pre>
 * // Custom product string (replaces the default):
 * CommandLine.getInstance().appendSwitchWithValue(
 *     "user-agent-product",
 *     "Chrome/148.0.7778.167 MyApp/2.0 Mobile");
 * Cefrium.initialize(context);
 * </pre>
 *
 * <h3>Basic usage</h3>
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

        // ContextUtils, ResourceBundle, PathUtils, ApplicationStatus,
        // and LibraryProcessType must be set by the Application's
        // attachBaseContext() BEFORE calling this method.
        // See SampleApplication for the pattern.
        if (!CommandLine.isInitialized()) {
            CommandLine.init(null);
        }
        CommandLine.getInstance().appendSwitch("in-process-gpu");
        CommandLine.getInstance().appendSwitch("no-sandbox");
        // Force software video decoding — hardware MediaCodec outputs to a
        // GPU texture that doesn't reach the off-screen rendering buffer.
        CommandLine.getInstance().appendSwitch("disable-accelerated-video-decode");

        // Set device scale factor from screen density so pages render
        // with correct mobile viewport (e.g., 1080px at 2.75x = ~393 CSS px).
        float density = appContext.getResources().getDisplayMetrics().density;
        CommandLine.getInstance().appendSwitchWithValue(
                "force-device-scale-factor", String.valueOf(density));

        try {
            LibraryLoader.getInstance().ensureInitialized();
        } catch (Exception e) {
            return false;
        }

        // Initialize CEF native layer (browser process only).
        String cacheDir = appContext.getCacheDir().getAbsolutePath();
        String dataDir = appContext.getFilesDir().getAbsolutePath();
        int result = nativeInitialize(cacheDir, dataDir);
        if (result != 0) {
            return false;
        }

        sInitialized = true;
        // No manual message loop pumping needed — CEF integrates with
        // Android's Looper when multi_threaded_message_loop = false.
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
