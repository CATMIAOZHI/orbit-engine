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
 * <p>No setup is required: the SDK's {@code CefriumInitProvider} performs the
 * per-process plumbing at app launch, and the engine initializes lazily the first
 * time a browser is created. Just create one:</p>
 *
 * <pre>
 * CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
 * browser.loadUrl("https://example.com");
 *
 * // When done:
 * Cefrium.shutdown();
 * </pre>
 *
 * <p>Advanced: to control init timing (e.g. to pass command-line switches before
 * native init) call {@link #initialize(Context)} yourself before the first browser.
 * It is idempotent and self-sufficient — no custom {@code Application} needed.</p>
 */
public final class Cefrium {
    private static boolean sInitialized;
    private static boolean sProcessSetUp;

    private Cefrium() {}

    /**
     * Run the per-process Chromium setup the browser process needs before native
     * init. Idempotent and safe to call from any process: it is a no-op in
     * Chromium child processes (sandboxed renderer/GPU/utility), which self-init
     * via {@code ChildProcessService}. The SDK's {@code CefriumInitProvider} calls
     * this automatically at app launch, and {@link #initialize(Context)} calls it
     * too, so consumers normally never call it directly.
     *
     * @param context Any context (its application context is used).
     */
    public static synchronized void setupProcess(Context context) {
        if (sProcessSetUp) {
            return;
        }
        // Child processes bootstrap themselves (ContextUtils + LibraryProcessType
        // are set by ChildProcessService / from Intent extras, and JNI_OnLoad
        // registers the child CefApp). Do nothing here for them.
        String processName = ContextUtils.getProcessName();
        boolean isBrowserProcess = processName == null || !processName.contains(":");
        if (!isBrowserProcess) {
            sProcessSetUp = true;
            return;
        }

        Context appContext = context.getApplicationContext();
        ContextUtils.initApplicationContext(appContext);
        ResourceBundle.setAvailablePakLocales(new String[] {"en-US"});
        LibraryLoader.getInstance()
                .setLibraryProcessType(LibraryProcessType.PROCESS_BROWSER);
        PathUtils.setPrivateDataDirectorySuffix("cefrium");
        if (appContext instanceof Application) {
            ApplicationStatus.initialize((Application) appContext);
        }
        sProcessSetUp = true;
    }

    /**
     * Initialize the Cefrium engine using the application context captured during
     * per-process setup. Equivalent to {@link #initialize(Context)} but for callers
     * that have no Context handy (e.g. off-screen browser creation). Requires that
     * {@link #setupProcess(Context)} has run — which the SDK's
     * {@code CefriumInitProvider} guarantees at app launch.
     *
     * @return true if initialization succeeded.
     */
    public static synchronized boolean initialize() {
        return initialize(ContextUtils.getApplicationContext());
    }

    /**
     * Initialize the Cefrium engine. Safe to call more than once (idempotent) and
     * self-sufficient: it runs {@link #setupProcess(Context)} itself, so no
     * {@code Application} boilerplate is required. Must be called on the main thread
     * (the SDK does this lazily on first browser creation).
     *
     * @param context Application context.
     * @return true if initialization succeeded.
     */
    public static synchronized boolean initialize(Context context) {
        if (sInitialized) {
            return true;
        }

        // Browser-process only: child processes (sandboxed renderer/GPU/utility)
        // self-initialize via ChildProcessService and must never run the browser
        // nativeInitialize. The provider may run in a non-isolated child, so guard.
        String processName = ContextUtils.getProcessName();
        if (processName != null && processName.contains(":")) {
            return false;
        }

        Context appContext = context.getApplicationContext();

        // Per-process plumbing (ContextUtils, ResourceBundle, LibraryProcessType,
        // PathUtils, ApplicationStatus). Idempotent — a no-op if CefriumInitProvider
        // already ran it at app launch.
        setupProcess(appContext);

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
