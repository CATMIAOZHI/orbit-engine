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
        // NOTE: Do NOT add --disable-accelerated-video-decode here.
        // Hardware video decoding (MediaCodec) is required for DRM/Widevine
        // content. It works in Surface mode (ContentViewRenderView).
        // Only OSR (off-screen) mode needs software decode.

        // WebAuthn/Passkeys: Requires Google Play Services browser verification
        // or Android CredentialManager with CREDENTIAL_MANAGER_SET_ORIGIN
        // permission (system apps only).
        // Preparatory patches saved in patch/patches/android/ for when
        // browser verification is obtained.

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

    /**
     * Load ad-blocking filter rules from an EasyList-format file.
     * Can be called multiple times to load additional lists.
     *
     * @param path Absolute path to the filter list file.
     * @return Number of rules loaded.
     */
    public static int loadAdBlockList(String path) {
        return nativeLoadAdBlockList(path);
    }

    /**
     * Download and load EasyList from the internet.
     * Call from a background thread — performs network I/O.
     *
     * @param context Application context for file storage.
     */
    public static void downloadAndLoadEasyList(final android.content.Context context) {
        new Thread(() -> {
            try {
                java.io.File dir = context.getFilesDir();
                java.io.File file = new java.io.File(dir, "easylist.txt");

                // Download if not exists or older than 24 hours.
                boolean needsDownload = !file.exists() ||
                        (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000);

                if (needsDownload) {
                    java.net.URL url = new java.net.URL("https://easylist.to/easylist/easylist.txt");
                    java.io.InputStream in = url.openStream();
                    java.io.FileOutputStream out = new java.io.FileOutputStream(file);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();
                }

                int count = nativeLoadAdBlockList(file.getAbsolutePath());
                android.util.Log.i("Cefrium", "EasyList loaded: " + count + " rules");
            } catch (Exception e) {
                android.util.Log.e("Cefrium", "EasyList download failed: " + e.getMessage());
            }
        }).start();
    }

    // Native methods implemented in libcef.so.
    private static native int nativeInitialize(String cacheDir, String dataDir);
    private static native void nativeShutdown();
    private static native int nativeLoadAdBlockList(String path);
}
