// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;

import org.chromium.base.ApkInfo;
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
 *     Chrome/&lt;engine version&gt; Cefrium/1.0 Mobile Safari/537.36
 * </pre>
 *
 * <p>The Chrome token carries the bundled engine version, a build constant of
 * the SDK -- see {@link #getChromiumVersion()}. It is not your app's
 * {@code android:versionName}. The {@code Cefrium/1.0} token identifies the
 * embedder and does not track the SDK version; read {@link #getVersion()} for
 * that.</p>
 *
 * <p>Sites therefore serve what they serve to Chrome. The User-Agent is
 * configured via {@code CefSettings.user_agent_product}
 * in the native layer. To customize it, use the {@code --user-agent-product}
 * command-line switch before calling {@link #initialize}:</p>
 *
 * <pre>
 * // Custom product string (replaces the default):
 * CommandLine.getInstance().appendSwitchWithValue(
 *     "user-agent-product",
 *     "Chrome/149.0.7827.102 MyApp/2.0 Mobile");
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
    private static final String TAG = "Cefrium";

    private static boolean sInitialized;
    private static boolean sProcessSetUp;

    // Engine background-network controls. See the setters for what each one
    // costs. Component updates keep the engine's default (on) because turning
    // them off trades away security-data freshness. The network time query keeps
    // no security data, so Cefrium defaults it off and diverges from Chromium
    // here deliberately.
    private static boolean sComponentUpdatesEnabled = true;
    private static boolean sNetworkTimeQueriesEnabled = false;
    private static boolean sGpuProcessEnabled = false;

    private Cefrium() {}

    /**
     * Control whether the engine keeps its security data up to date over the
     * network. Enabled by default. Must be called <b>before</b> the engine
     * initializes, i.e. before {@link #initialize(Context)} and before the first
     * browser is created; afterwards it has no effect and logs a warning.
     *
     * <p><b>Call it from {@code Application.attachBaseContext}</b>, not from
     * {@code onCreate}. The SDK initializes the engine from a
     * {@code ContentProvider}, and Android creates providers between those two
     * callbacks, so a setter called in {@code onCreate} is already too late.</p>
     *
     * <p>When enabled (the default), Chromium's component updater contacts
     * {@code update.googleapis.com} and downloads data files the engine relies on:
     * the certificate revocation list (CRLSet), Certificate Transparency log
     * metadata, the dangerous-download file-type policy, TLS error diagnostics,
     * Trust Token key commitments, First-Party Sets and the subresource filtering
     * ruleset. The request carries component ids and versions, the platform, and a
     * random per-component install identifier. It carries no URL, no account and
     * nothing the user typed.</p>
     *
     * <p><b>What you give up by disabling it.</b> This is a security trade-off, not
     * merely a privacy setting. With component updates off, all of the above freeze
     * at the state baked into the SDK version you shipped. In particular
     * <b>certificate revocation data stops being refreshed</b>, so a certificate
     * revoked after your build date will still be accepted. The failure mode is
     * silent: nothing crashes and no user complains. If you disable this, you are
     * taking on responsibility for shipping SDK updates promptly.</p>
     *
     * <p>Legitimate reasons to disable it: an air-gapped or offline deployment where
     * the requests cannot succeed anyway, a kiosk on a metered or whitelisted
     * network, or a deployment whose compliance regime forbids unattended third-party
     * requests. In those cases pin the SDK version deliberately and track releases.</p>
     *
     * <p>Implemented with Chromium's own {@code --disable-component-update} switch,
     * which suppresses {@code RegisterComponentsForUpdate()} in the browser process.
     * It is an upstream switch, so it keeps working across engine updates.</p>
     *
     * @param enabled false to stop the engine's component update checks.
     */
    public static synchronized void setComponentUpdatesEnabled(boolean enabled) {
        if (sInitialized) {
            android.util.Log.w(TAG, "setComponentUpdatesEnabled() ignored: the engine is "
                    + "already initialized. Call it before the first browser is created.");
            return;
        }
        sComponentUpdatesEnabled = enabled;
    }

    /** @return whether engine component updates are enabled (default true). */
    public static synchronized boolean areComponentUpdatesEnabled() {
        return sComponentUpdatesEnabled;
    }

    /**
     * Run the GPU in its own process instead of inside the browser process.
     * Disabled by default. Must be called <b>before</b> {@link #initialize(Context)}
     * and before the first browser is created; afterwards it has no effect and logs
     * a warning. Call it from {@code Application.attachBaseContext}, not from
     * {@code onCreate}: the SDK initializes from a {@code ContentProvider}, which
     * Android creates between those two callbacks. The kiosk-pos sample shows the
     * shape.
     *
     * <p>By default Cefrium passes Chromium's {@code --in-process-gpu}, so GL
     * command buffers and the vendor's graphics driver run in the same process as
     * your application code and its data. That is a weaker boundary than desktop
     * Chrome, where the GPU is a separate process: a graphics-driver bug reached
     * from a compromised renderer lands in the process holding your app's
     * permissions.</p>
     *
     * <p>Enabling this moves the GPU into an Android service of its own
     * ({@code :privileged_process0}). The renderer then has to cross two process
     * boundaries to reach your code, and a driver crash no longer takes the
     * browser process with it. The GPU service is privileged rather than isolated,
     * because it needs the driver and the surface, so it shares your app's UID: it
     * is separated from the renderer, not from your app's files.</p>
     *
     * <p><b>It costs memory, which is why it is not the default.</b> Measured on a
     * 1920x1200 kiosk tablet with one browser showing a page, total PSS across the
     * app's processes: 591 MB in-process against 715 MB with the GPU out, about
     * 21% more, and one more process for Android's low-memory killer to choose
     * from. On dedicated hardware with 4 GB or less that matters, which is most
     * kiosk and signage deployments.</p>
     *
     * <p>Turn it on when the device has memory to spare and the content is not
     * fully under your control: a browser, a portal, anything rendering pages from
     * the open web. Leave it off for a fixed-content kiosk on constrained
     * hardware. The engine behaves identically either way; the full on-device
     * suite passes in both modes.</p>
     *
     * @param enabled true to run the GPU in a separate process.
     */
    public static synchronized void setGpuProcessEnabled(boolean enabled) {
        if (sInitialized) {
            android.util.Log.w(TAG, "setGpuProcessEnabled() ignored: the engine is "
                    + "already initialized. Call it before the first browser is created.");
            return;
        }
        sGpuProcessEnabled = enabled;
    }

    /** @return whether the GPU runs in its own process (default false). */
    public static synchronized boolean isGpuProcessEnabled() {
        return sGpuProcessEnabled;
    }

    /**
     * Control whether the engine queries Google's time service.
     * <b>Disabled by default in Cefrium</b>, which is a deliberate divergence from
     * Chromium, where the feature is enabled by default on Android. Must be called
     * <b>before</b> the engine initializes, i.e. before {@link #initialize(Context)}
     * and before the first browser is created; afterwards it has no effect and logs
     * a warning. Call it from {@code Application.attachBaseContext}, not from
     * {@code onCreate}: the SDK initializes from a {@code ContentProvider}, which
     * Android creates between those two callbacks.
     *
     * <p>When enabled, the engine performs an occasional request to
     * {@code http://clients2.google.com/time/1/current} (roughly daily by design) to
     * detect a badly set device clock. The request carries no identifier and no user
     * data.</p>
     *
     * <p><b>Why Cefrium defaults this off.</b> The query's only purpose is to explain
     * a certificate error better: on a device whose clock is wrong, Chromium can say
     * so instead of showing a generic "certificate expired". It carries no security
     * data, so switching it off costs no freshness -- unlike
     * {@link #setComponentUpdatesEnabled(boolean)}. On Android the system clock is
     * already synchronised by the carrier or by NTP, so the diagnosis is rarely
     * needed, and an unconditional daily request to a third party is a poor trade for
     * an embedded engine whose host app did not ask for it. Certificate validation
     * itself is unaffected either way.</p>
     *
     * <p>Pass true to restore Chromium's upstream behaviour. That is the right call
     * if your deployment runs on hardware with no reliable clock source -- devices
     * with no cellular modem and no NTP reachability, or a kiosk whose RTC has no
     * battery -- where a clock far enough off to break HTTPS is a real support case
     * and a precise error message saves a call.</p>
     *
     * <p>Implemented by disabling Chromium's {@code NetworkTimeServiceQuerying}
     * feature. Any {@code disable-features} value already on the command line is
     * preserved and appended to, not overwritten.</p>
     *
     * @param enabled true to restore the engine's network time queries.
     */
    public static synchronized void setNetworkTimeQueriesEnabled(boolean enabled) {
        if (sInitialized) {
            android.util.Log.w(TAG, "setNetworkTimeQueriesEnabled() ignored: the engine is "
                    + "already initialized. Call it before the first browser is created.");
            return;
        }
        sNetworkTimeQueriesEnabled = enabled;
    }

    /** @return whether engine network time queries are enabled (default false). */
    public static synchronized boolean areNetworkTimeQueriesEnabled() {
        return sNetworkTimeQueriesEnabled;
    }

    // Add a feature to --disable-features without clobbering a value the embedder
    // (or an earlier call) already put there.
    private static void disableFeature(String feature) {
        CommandLine commandLine = CommandLine.getInstance();
        String existing = commandLine.getSwitchValue("disable-features", "");
        String merged = (existing == null || existing.isEmpty())
                ? feature
                : existing + "," + feature;
        commandLine.appendSwitchWithValue("disable-features", merged);
    }

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

        // Backward compatibility: if the app's own Application already ran the
        // per-process plumbing (the legacy pattern, in attachBaseContext — which
        // runs before this provider's onCreate), do NOT run it again. These are
        // assert-once setters (e.g. ResourceBundle.setAvailablePakLocales throws
        // AssertionError on a second call). The application context being set is the
        // signal that the plumbing has already started.
        if (ContextUtils.getApplicationContextUnsafe() != null) {
            sProcessSetUp = true;
            return;
        }

        Context appContext = context.getApplicationContext();
        ContextUtils.initApplicationContext(appContext);
        declareEngineVersionToApkInfo(appContext);
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
     * Tell Chromium which version the ENGINE is, as opposed to the host app.
     *
     * Since Chromium 156, version_info::GetVersionNumber() on Android returns
     * ApkInfo's package_version_name, which defaults to the host APK's
     * android:versionName. The user-agent string no longer goes through it --
     * that is a build constant now -- but the client hints still do:
     * user_agent_utils.cc builds Sec-CH-UA and navigator.userAgentData from
     * version_info directly, bypassing user_agent_product. Measured on device
     * before this call existed, with the UA already correct:
     *     uaFullVersion: 0.1.0      (the sample app's versionName)
     *     brands: [..., {"brand":"Chromium","version":"0"}]
     *
     * setBrowserPackageInfo is the mechanism Chromium provides for an engine
     * whose host APK is not the engine; production WebView calls it the same
     * way, right after the application context exists.
     *
     * Only versionName is ours. packageName, applicationInfo and versionCode
     * stay the host's real values: they are passed to child processes on the
     * command line and checked against the child's sourceDir, so a synthetic
     * package here would break child launching.
     */
    private static void declareEngineVersionToApkInfo(Context appContext) {
        try {
            PackageInfo info = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            info.versionName = CHROMIUM_VERSION;
            ApkInfo.setBrowserPackageInfo(info);
        } catch (Throwable t) {
            // Non-fatal by design. The UA string is correct either way; only the
            // client hints would stay wrong, and that must not stop startup.
            android.util.Log.w("Cefrium", "could not declare the engine version: " + t);
        }
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
        // The GPU runs inside the browser process unless the embedder asks
        // otherwise. This switch had been here since the first SDK commit with no
        // reason recorded, which is how a bring-up expedient becomes a security
        // property nobody chose: GL command buffers and the vendor driver share a
        // process with the app's code and data. Out-of-process works -- suite
        // green, GPU service running, page rendering -- and costs about 21% more
        // memory, so it is an opt-in rather than a default. See
        // setGpuProcessEnabled.
        if (!sGpuProcessEnabled) {
            CommandLine.getInstance().appendSwitch("in-process-gpu");
        }
        // No "no-sandbox" switch. Renderers are isolated either way on Android,
        // but the switch stops renderer_main from installing Chromium's
        // seccomp-BPF policy, so children ran with the zygote baseline only.
        // Route HTTP/proxy auth challenges (401/407) to CefRequestHandler::
        // GetAuthCredentials -> CefriumBrowser.setHttpAuthHandler. Without this,
        // ChromeContentBrowserClientCef::CreateLoginDelegate falls through to
        // Chrome's native login prompt (which on Android never reaches the SDK
        // handler). This switch is CEF's documented "delegate all login requests
        // to the client" hook. WebView parity with onReceivedHttpAuthRequest.
        CommandLine.getInstance().appendSwitch("disable-chrome-login-prompt");
        // NOTE: Do NOT add --disable-accelerated-video-decode here.
        // Hardware video decoding (MediaCodec) is required for DRM/Widevine
        // content. It works in Surface mode (ContentViewRenderView).
        // Only OSR (off-screen) mode needs software decode.

        // WebAuthn/Passkeys: Requires Google Play Services browser verification
        // or Android CredentialManager with CREDENTIAL_MANAGER_SET_ORIGIN
        // permission (system apps only).
        // Preparatory patches saved in patch/patches/android/ for when
        // browser verification is obtained.

        // Engine background-network controls (see setComponentUpdatesEnabled /
        // setNetworkTimeQueriesEnabled).
        //
        // Component updates keep the engine's default (on): an embedder must opt out
        // explicitly, because that switch trades away security-data freshness
        // (CRLSet, CT metadata, download file-type policy) silently.
        if (!sComponentUpdatesEnabled) {
            CommandLine.getInstance().appendSwitch("disable-component-update");
        }
        // The network time query carries no security data -- it only lets Chromium
        // explain a certificate error as a wrong clock -- so Cefrium defaults it off
        // and an embedder opts back IN. Upstream switch, so it survives engine bumps.
        if (!sNetworkTimeQueriesEnabled) {
            disableFeature("NetworkTimeServiceQuerying");
        }

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

        // WebAuthn / passkeys: route navigator.credentials through the Android
        // Credential Manager (any app can use it) instead of the Play Services
        // FIDO2 *privileged* API, which is allowlisted to browser packages and
        // fails with RESTRICTED_PROFILE for an embedding app. Requires Android
        // 14+ with Credential Manager; harmless (best-effort) otherwise.
        configureWebAuthn();
        configureContactsPicker();

        sInitialized = true;
        // No manual message loop pumping needed — CEF integrates with
        // Android's Looper when multi_threaded_message_loop = false.
        return true;
    }

    // Route WebAuthn through the Credential Manager (CredMan-only) and set a
    // non-NONE global WebAuthn mode so the platform passkey UI is used. Guarded
    // so a build without these Chromium classes (or pre-Android-14) is a no-op.
    private static void configureWebAuthn() {
        try {
            org.chromium.components.webauthn.cred_man.CredManSupportProvider
                    .setForceCredManOnly(true);
            org.chromium.components.webauthn.WebauthnModeProvider.getInstance()
                    .setGlobalWebauthnMode(
                            org.chromium.components.webauthn.WebauthnMode.BROWSER);
            // Diagnostic: confirm CredMan is actually selected in this process.
            Object credSvc = null;
            try {
                credSvc = org.chromium.base.ContextUtils.getApplicationContext()
                        .getSystemService("credential");
            } catch (Throwable ignore) {}
            int support = org.chromium.components.webauthn.cred_man.CredManSupportProvider
                    .getCredManSupport();
            android.util.Log.i("Cefrium", "WebAuthn configured: getCredManSupport="
                    + support + " credentialService="
                    + (credSvc != null ? "present" : "null"));
        } catch (Throwable t) {
            android.util.Log.w("Cefrium", "WebAuthn CredMan setup skipped: " + t);
        }
    }

    // Wire the Contact Picker (navigator.contacts.select): a permission provider
    // and an app-drawn picker delegate (CefContactsPickerDelegate reads
    // ContactsContract and shows an AlertDialog). Chrome sets these from
    // chrome/android; an embedder must do it too or the API throws "Unable to
    // open a contact selector". Consumers also need the READ_CONTACTS runtime
    // permission. Guarded for builds without these classes.
    private static void configureContactsPicker() {
        try {
            org.chromium.content_public.browser.ContactsDialogHost.setPermissionProvider(
                    new org.chromium.components.browser_ui.contacts_picker
                            .AndroidContactsPermissionProviderImpl());
            org.chromium.content_public.browser.ContactsPicker.setContactsPickerDelegate(
                    new CefContactsPickerDelegate());
        } catch (Throwable t) {
            android.util.Log.w("Cefrium", "Contacts picker setup skipped: " + t);
        }
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

    /** Cefrium SDK version. Kept in lockstep with the release by cut-release.sh. */
    public static final String VERSION = "0.10.2";

    /** Bundled Chromium engine version (pinned to the build). */
    public static final String CHROMIUM_VERSION = "156.0.8078.17";

    /**
     * @return the Cefrium SDK version, e.g. {@code "0.5.0"}.
     */
    public static String getVersion() {
        return VERSION;
    }

    /**
     * @return the bundled Chromium engine version, e.g. {@code "149.0.7827.102"}.
     */
    public static String getChromiumVersion() {
        return CHROMIUM_VERSION;
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
