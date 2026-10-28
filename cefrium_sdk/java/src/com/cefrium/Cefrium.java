// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;

import org.chromium.base.ApkInfo;
import org.chromium.base.ApplicationState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.CommandLine;
import org.chromium.base.ContextUtils;
import org.chromium.base.PathUtils;
import org.chromium.base.library_loader.LibraryLoader;
import org.chromium.base.library_loader.LibraryProcessType;
import org.chromium.chrome.browser.profiles.ProfileManagerUtils;
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
 * per-process plumbing at app launch, and the engine starts the first time the
 * app uses it, so a process woken for something else (a push message, a job)
 * never loads it. Just create a browser:</p>
 *
 * <pre>
 * CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
 * browser.loadUrl("https://example.com");
 *
 * // When done:
 * Cefrium.shutdown();
 * </pre>
 *
 * <p>Advanced: to start the engine ahead of the first browser, or to pass
 * command-line switches before it starts, call {@link #initialize(Context)}
 * yourself. It is idempotent and self-sufficient; no custom
 * {@code Application} is needed.</p>
 */
public final class Cefrium {
    private static final String TAG = "Cefrium";

    private static boolean sInitialized;
    private static boolean sProcessSetUp;
    private static boolean sEngineVersionDeclared;

    // Engine background-network controls. See the setters for what each one
    // costs. Component updates keep the engine's default (on) because turning
    // them off trades away security-data freshness. The network time query keeps
    // no security data, so Cefrium defaults it off and diverges from Chromium
    // here deliberately.
    private static boolean sComponentUpdatesEnabled = true;
    private static boolean sLibraryPrefetchEnabled = true;
    private static boolean sNetworkTimeQueriesEnabled = false;
    private static java.io.File sNetLogFile;
    private static boolean sGpuProcessEnabled = false;
    private static java.io.File sDataDirectory;

    private Cefrium() {}

    /**
     * Control whether the engine keeps its security data up to date over the
     * network. Enabled by default. Must be called <b>before</b> the engine
     * initializes, i.e. before {@link #initialize(Context)} and before the first
     * browser is created; afterwards it has no effect and logs a warning.
     *
     * <p>The engine starts the first time the app uses it (a browser, a
     * profile, a filter list), so {@code Application.onCreate} is early enough
     * unless the app calls {@link #initialize(Context)} before that.</p>
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

    /**
     * Where the engine keeps its persistent data: cookies, history, local
     * storage, IndexedDB, service workers and autofill. Call before the first
     * browser is created.
     *
     * <p>The default is the app's private {@code app_cefrium} directory. Before
     * 0.14.0 the data lived in the app's cache directory, which Android may
     * clear when storage runs low, and with it every signed-in session. On the
     * first start of 0.14.0 that data is moved here once.</p>
     *
     * <p>A directory is used by one process at a time. To back it up or move
     * it, close every browser first: the engine keeps its databases open.</p>
     *
     * @param directory the data root; created if it does not exist.
     */
    public static synchronized void setDataDirectory(java.io.File directory) {
        if (sInitialized) {
            android.util.Log.w(TAG, "setDataDirectory() ignored: the engine is "
                    + "already initialized. Call it before the first browser is created.");
            return;
        }
        sDataDirectory = directory;
    }

    /**
     * @return the data root set with {@link #setDataDirectory}, or null for the
     *         default ({@code app_cefrium}).
     */
    public static synchronized java.io.File getDataDirectory() {
        return sDataDirectory;
    }

    /** @return whether engine component updates are enabled (default true). */
    public static synchronized boolean areComponentUpdatesEnabled() {
        return sComponentUpdatesEnabled;
    }

    /**
     * Read the engine's library ahead while it starts. On a cold start, when
     * libcef.so is not in the page cache, loading and initializing the engine
     * faults its code in from storage a few hundred random reads at a time; a
     * background thread reading the file sequentially brings it in first. On
     * two phones the first page ran 155-340 ms sooner on a cold start (about
     * 17%). When the library is already cached the thread stops after its
     * first read; a warm start measured within 10 ms of one without it. The
     * read goes through the page cache only (sendfile to /dev/null), so it adds
     * no memory to the app.
     *
     * <p>On by default. Call it before the engine starts (see
     * {@link #setComponentUpdatesEnabled}). Later calls are ignored and log a
     * warning.
     */
    public static synchronized void setLibraryPrefetchEnabled(boolean enabled) {
        if (sInitialized) {
            android.util.Log.w(TAG, "setLibraryPrefetchEnabled() ignored: the engine is "
                    + "already initialized. Call it before the first browser is created.");
            return;
        }
        sLibraryPrefetchEnabled = enabled;
    }

    /** @return whether the library is read ahead at startup (default true). */
    public static synchronized boolean isLibraryPrefetchEnabled() {
        return sLibraryPrefetchEnabled;
    }

    // The kernel's own read-ahead advice (fadvise WILLNEED) did nothing on the
    // phone tried, and mapping the file to touch it keeps it in the app's
    // memory until a GC; sendfile to /dev/null fills the page cache and stops.
    // Reading a fully cached file still costs time, so the first 16 MiB decide:
    // cached they came back in 1.5-5.5 ms on the phones measured, from storage
    // in 22-109 ms.
    private static final long PREFETCH_PROBE_BYTES = 16L << 20;
    private static final long PREFETCH_CACHED_NANOS = 12_000_000L;

    private static void prefetchLibrary(Context appContext) {
        final java.io.File lib = new java.io.File(
                appContext.getApplicationInfo().nativeLibraryDir, "libcef.so");
        if (!lib.isFile()) return;  // loaded from inside the APK: nothing to read ahead
        Thread t = new Thread(() -> {
            try (java.io.FileInputStream in = new java.io.FileInputStream(lib);
                 java.io.FileOutputStream sink = new java.io.FileOutputStream("/dev/null")) {
                java.nio.channels.FileChannel from = in.getChannel();
                long size = from.size();
                long start = System.nanoTime();
                long pos = transfer(from, 0, Math.min(PREFETCH_PROBE_BYTES, size), sink);
                if (System.nanoTime() - start < PREFETCH_CACHED_NANOS) return;
                transfer(from, pos, size, sink);
            } catch (java.io.IOException ignored) {
                // Only a head start: the library loads the same way without it.
            }
        }, "CefriumPrefetch");
        t.setDaemon(true);
        t.start();
    }

    /** Sends [pos, end) of the file to the sink; returns where it stopped. */
    private static long transfer(java.nio.channels.FileChannel from, long pos, long end,
            java.io.FileOutputStream sink) throws java.io.IOException {
        while (pos < end) {
            long sent = from.transferTo(pos, end - pos, sink.getChannel());
            if (sent <= 0) break;
            pos += sent;
        }
        return pos;
    }

    /**
     * Run the GPU in its own process instead of inside the browser process.
     * Disabled by default. Must be called <b>before</b> {@link #initialize(Context)}
     * and before the first browser is created; afterwards it has no effect and logs
     * a warning. The kiosk-pos sample shows the shape.
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
     * a warning.
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

    /**
     * Records the engine's network activity to {@code file} in Chromium's NetLog
     * format, from the engine's start until the process ends: every request, the
     * proxy chosen for it, DNS lookups and each socket with its address and the
     * bytes it carried. Open it with the NetLog viewer
     * (https://netlog-viewer.appspot.com). Cookies and credentials are left out,
     * but every URL the user visits and every address the engine connects to
     * is in it: treat the file as browsing history, and do not turn it on for a
     * user's own browsing without their consent.
     *
     * <p>Events reach the file in batches, and a process that is killed rather
     * than shut down leaves it without its closing brackets; the events written
     * so far are complete lines.</p>
     *
     * <p>Call it before the engine starts (see
     * {@link #setComponentUpdatesEnabled}); later calls are ignored and log a
     * warning. Pass null to record nothing, the default.</p>
     *
     * @param file where to write the log; the app must be able to write it.
     */
    public static synchronized void setNetLogFile(java.io.File file) {
        if (sInitialized) {
            android.util.Log.w(TAG, "setNetLogFile() ignored: the engine is already "
                    + "initialized. Call it before the first browser is created.");
            return;
        }
        sNetLogFile = file;
    }

    // Add a feature to --disable-features without clobbering a value the embedder
    // (or an earlier call) already put there.
    private static final String JAVALESS_MARKER = "com.cefrium.JAVALESS_RENDERERS";

    /** The renderer process services the engine uses; see {@link #getRendererServices}. */
    public static final class RendererServices {
        /** True when renderers start as native services, without a JVM (Android 17+). */
        public final boolean javaless;
        /** The service class prefix, e.g. {@code com.cefrium.ChildServices$Sandboxed}. */
        public final String serviceName;
        /** How many such services the app declares: the most renderers at once. */
        public final int count;

        RendererServices(boolean javaless, String serviceName, int count) {
            this.javaless = javaless;
            this.serviceName = serviceName;
            this.count = count;
        }

        @Override
        public String toString() {
            return serviceName + " x" + count + (javaless ? " (javaless)" : "");
        }
    }

    /**
     * Which renderer services the engine launches, as it decided for this
     * device and app: native (javaless) ones on Android 17+ when the app
     * declares them, the Java ones otherwise, and how many the manifest has.
     * Use it to verify a build instead of reading engine internals.
     *
     * Starts the engine if it has not started.
     */
    public static RendererServices getRendererServices() {
        ensureInitialized();
        Context context = ContextUtils.getApplicationContext();
        boolean javaless = org.chromium.content.browser.ChildProcessCreationParamsImpl
                .isNativeSandboxedServiceEnabled();
        String name = org.chromium.content.browser.ChildProcessCreationParamsImpl
                .getSandboxedServicesName();
        int count = 0;
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), android.content.pm.PackageManager.GET_SERVICES);
            if (info.services != null) {
                for (android.content.pm.ServiceInfo service : info.services) {
                    String suffix = service.name.startsWith(name)
                            ? service.name.substring(name.length()) : null;
                    if (suffix != null && suffix.matches("[0-9]+")) count++;
                }
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            // Our own package: cannot happen.
        }
        return new RendererServices(javaless, name, count);
    }

    private static boolean hasJavalessRenderers(Context appContext) {
        try {
            android.content.pm.ApplicationInfo info = appContext.getPackageManager()
                    .getApplicationInfo(appContext.getPackageName(),
                            android.content.pm.PackageManager.GET_META_DATA);
            return info.metaData != null && info.metaData.getBoolean(JAVALESS_MARKER, false);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

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
            // The engine version is not part of that plumbing, and an app that
            // predates it never declares it: its client hints would carry the
            // app's versionName. Measured: brand "1", uaFullVersion "1.0".
            declareEngineVersionToApkInfo(ContextUtils.getApplicationContext());
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
        if (sEngineVersionDeclared) return;
        sEngineVersionDeclared = true;
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
        if (sLibraryPrefetchEnabled) {
            prefetchLibrary(appContext);
        }

        // Per-process plumbing (ContextUtils, ResourceBundle, LibraryProcessType,
        // PathUtils, ApplicationStatus). Idempotent — a no-op if CefriumInitProvider
        // already ran it at app launch.
        setupProcess(appContext);
        // Before the native side first reads ApkInfo. A no-op if setupProcess
        // already declared it; covers an app that initializes the engine from
        // its own attachBaseContext, before the SDK's provider has run.
        declareEngineVersionToApkInfo(appContext);

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
        if (sNetLogFile != null) {
            CommandLine.getInstance().appendSwitchWithValue(
                    "log-net-log", sNetLogFile.getAbsolutePath());
        }
        // Chrome's omnibox AI Mode checks the user's eligibility with Google
        // (www.google.com/async/folae, with locale and country) for every
        // profile. An embedded engine has no omnibox to offer it in.
        disableFeature("AimEnabled");
        // Chrome keeps a connection open to the default search engine (Google)
        // for omnibox searches, renewed on a timer. Nothing in an embedded
        // engine searches from an omnibox.
        disableFeature("PreconnectToSearch");

        // Android 17+ renderers run as native services when the app declares
        // them, which the com.cefrium Gradle plugin does when the app compiles
        // against API 37 (FEATURE-072). Without the declarations Chromium would
        // still look for them, so keep renderers on the Java services.
        if (!hasJavalessRenderers(appContext)) {
            CommandLine.getInstance().appendSwitchWithValue("javaless-renderers", "disabled");
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
        // Canonical: CEF canonicalizes root_cache_path (/data/user/0 is a link to
        // /data/data) and silently keeps a profile in memory if its cache_path,
        // built from this string, is not literally under the canonical root.
        java.io.File root = dataRoot(appContext);
        String cacheDir;
        try {
            cacheDir = root.getCanonicalPath();
        } catch (java.io.IOException e) {
            cacheDir = root.getAbsolutePath();
        }
        String dataDir = appContext.getFilesDir().getAbsolutePath();
        int result = nativeInitialize(cacheDir, dataDir, downloadDir(appContext));
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
        flushWhenBackgrounded();

        sInitialized = true;
        // No manual message loop pumping needed — CEF integrates with
        // Android's Looper when multi_threaded_message_loop = false.
        return true;
    }

    private static boolean sFlushListenerRegistered;

    // As Chrome does: once no activity is visible Android may kill the process
    // without warning, so write what is still pending (preferences, cookies,
    // storage, Local State) for every loaded profile. Otherwise, for example, a
    // push subscription made seconds earlier is lost.
    private static void flushWhenBackgrounded() {
        if (sFlushListenerRegistered) return;
        sFlushListenerRegistered = true;
        ApplicationStatus.registerApplicationStateListener(state -> {
            if (state == ApplicationState.HAS_STOPPED_ACTIVITIES && sInitialized) {
                ProfileManagerUtils.flushPersistentDataForAllProfiles();
            }
        });
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

    /**
     * Starts the engine on first use, from every SDK entry point that needs it.
     * Off the main thread it starts it there and waits. Must not be called
     * while holding this class's lock: initialize() takes it on the main thread.
     *
     * @throws IllegalStateException if the engine cannot start.
     */
    static void ensureInitialized() {
        if (sInitialized) return;
        final Context context = ContextUtils.getApplicationContextUnsafe();
        if (context == null) {
            throw new IllegalStateException("Cefrium is not set up in this process");
        }
        boolean started;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            started = initialize(context);
        } else {
            java.util.concurrent.FutureTask<Boolean> task =
                    new java.util.concurrent.FutureTask<>(() -> initialize(context));
            new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
            try {
                started = task.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while the engine started", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException("the engine failed to start", e.getCause());
            }
        }
        if (!started) throw new IllegalStateException("the engine failed to start");
    }

    /** Cefrium SDK version. Kept in lockstep with the release by cut-release.sh. */
    public static final String VERSION = "0.16.0";

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

    // ----- Ad blocking -------------------------------------------------------
    // Network-level only: requests matching a loaded list are cancelled before
    // they leave the engine. Nothing is blocked until the app loads a list.
    // Cosmetic (element-hiding) rules are not applied. Lists such as EasyList
    // carry their own licence (EasyList: GPL-3.0 or CC BY-SA 3.0), which
    // applies if you ship the list inside your app.

    /** EasyList, the list {@link #downloadAndLoadEasyList} fetches. */
    public static final String EASYLIST_URL = "https://easylist.to/easylist/easylist.txt";

    /** The result of loading a filter list. */
    public interface AdBlockListCallback {
        /**
         * @param rules the rules the list now has in the filter, or -1 if none
         *     could be loaded.
         * @param error null on success; otherwise why the download failed (a
         *     cached copy may still have been loaded, see {@code rules}).
         */
        void onResult(int rules, String error);
    }

    /**
     * Loads an EasyList-format filter list from a file. Loading the same path
     * again replaces that list's rules. Supported: {@code ||host^} and
     * {@code ||host/path} rules, {@code @@} exceptions (including
     * {@code @@||site^$document}, which stops blocking on that site) and the
     * options third-party, domain= and the resource types; a rule with any
     * other option is skipped. Main-frame navigations are never blocked.
     * Call from a background thread: a full list takes a moment to parse.
     *
     * @return the list's rule count, or -1 if the file cannot be read.
     */
    public static int loadAdBlockList(String path) {
        ensureInitialized();
        return nativeLoadAdBlockList(path, path);
    }

    /**
     * Adds Cefrium's heuristics for YouTube's ad requests (ad breaks, ad
     * streams by their URL parameters, ad tracking). They are not part of any
     * list and YouTube changes these endpoints, so treat them as best effort.
     * Off unless called.
     *
     * @return the rules added, 0 if they were already loaded.
     */
    public static int loadYouTubeAdRules() {
        ensureInitialized();
        return nativeLoadYouTubeAdRules();
    }

    /** Secure DNS off: the system resolver, as Chrome's "off". */
    public static final String SECURE_DNS_OFF = "off";
    /**
     * Use DNS over HTTPS when the system's resolver has a provider that offers
     * it, and the system resolver otherwise; Chrome's default.
     */
    public static final String SECURE_DNS_AUTOMATIC = "automatic";
    /**
     * Only DNS over HTTPS through the given servers: if they cannot be reached,
     * names do not resolve.
     */
    public static final String SECURE_DNS_SECURE = "secure";

    /**
     * Chrome's secure DNS (DNS over HTTPS) setting, for every browser of the
     * app. It is kept across runs, and can be changed at any time.
     *
     * @param mode      {@link #SECURE_DNS_OFF}, {@link #SECURE_DNS_AUTOMATIC} or
     *                  {@link #SECURE_DNS_SECURE}.
     * @param templates DoH server URI templates (RFC 8484), separated by
     *                  spaces, e.g. {@code "https://dns.example/dns-query{?dns}"};
     *                  null or empty for none. Required for secure mode.
     * @return false if the mode or a template is not valid.
     * @throws IllegalStateException if not called on the main thread.
     */
    public static boolean setSecureDns(String mode, String templates) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            throw new IllegalStateException("setSecureDns must be called on the main thread");
        }
        ensureInitialized();
        return nativeSetSecureDns(mode == null ? "" : mode, templates == null ? "" : templates);
    }

    /** {@link #downloadAndLoadAdBlockList} for {@link #EASYLIST_URL}. */
    public static void downloadAndLoadEasyList(android.content.Context context) {
        downloadAndLoadAdBlockList(context, EASYLIST_URL, null);
    }

    /** {@link #downloadAndLoadAdBlockList} for {@link #EASYLIST_URL}. */
    public static void downloadAndLoadEasyList(android.content.Context context,
            AdBlockListCallback callback) {
        downloadAndLoadAdBlockList(context, EASYLIST_URL, callback);
    }

    /**
     * Downloads an EasyList-format list and loads it, at most once a day: a
     * copy younger than 24 hours is loaded from the app's files instead. The
     * download goes through the engine's network stack, so it uses the default
     * profile's proxy ({@link CefriumBrowser#setProxy}), not a direct
     * connection. If it fails, the last good copy is loaded, if any. Returns at
     * once; the callback (may be null) runs on a background thread.
     */
    public static void downloadAndLoadAdBlockList(android.content.Context context,
            String url, AdBlockListCallback callback) {
        final java.io.File file = adBlockListFile(context, url);
        sAdBlockExecutor.execute(() -> {
            ensureInitialized();
            if (file.exists()
                    && System.currentTimeMillis() - file.lastModified() < ADBLOCK_LIST_MAX_AGE_MS) {
                report(callback, nativeLoadAdBlockList(url, file.getPath()), null);
                return;
            }
            long id;
            synchronized (sAdBlockFetches) {
                id = ++sAdBlockFetchId;
                sAdBlockFetches.put(id, new AdBlockFetch(url, file, callback));
            }
            nativeFetchAdBlockList(url, id);
        });
    }

    private static final long ADBLOCK_LIST_MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final java.util.concurrent.Executor sAdBlockExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.Map<Long, AdBlockFetch> sAdBlockFetches =
            new java.util.HashMap<>();
    private static long sAdBlockFetchId;

    private static final class AdBlockFetch {
        final String url;
        final java.io.File file;
        final AdBlockListCallback callback;
        AdBlockFetch(String url, java.io.File file, AdBlockListCallback callback) {
            this.url = url;
            this.file = file;
            this.callback = callback;
        }
    }

    private static java.io.File adBlockListFile(android.content.Context context, String url) {
        java.io.File dir = new java.io.File(context.getFilesDir(), "cefrium-adblock");
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < 8; i++) name.append(String.format("%02x", hash[i]));
            return new java.io.File(dir, name + ".txt");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);  // SHA-256 is always present
        }
    }

    private static void report(AdBlockListCallback callback, int rules, String error) {
        if (error != null || rules < 0) {
            android.util.Log.w("Cefrium", "ad block list: " + (error != null ? error : "unreadable"));
        }
        if (callback != null) callback.onResult(rules, error);
    }

    // Called from native when nativeFetchAdBlockList finishes (UI thread).
    @SuppressWarnings("unused")
    private static void onAdBlockListFetched(long id, int status, byte[] body, String error) {
        final AdBlockFetch fetch;
        synchronized (sAdBlockFetches) {
            fetch = sAdBlockFetches.remove(id);
        }
        if (fetch == null) return;
        sAdBlockExecutor.execute(() -> {
            String problem = error;
            if (problem == null && status != 200) problem = "HTTP " + status;
            // A list starts with its "[Adblock Plus x.y]" header; anything else
            // (a captive portal, an error page) must not replace a good copy.
            if (problem == null && (body == null || !startsWith(body, "[Adblock"))) {
                problem = "not a filter list";
            }
            if (problem == null) {
                try {
                    writeAtomically(fetch.file, body);
                } catch (java.io.IOException e) {
                    problem = "cannot save the list: " + e.getMessage();
                }
            }
            int rules = fetch.file.exists()
                    ? nativeLoadAdBlockList(fetch.url, fetch.file.getPath()) : -1;
            report(fetch.callback, rules, problem);
        });
    }

    private static boolean startsWith(byte[] body, String prefix) {
        if (body.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (body[i] != prefix.charAt(i)) return false;
        }
        return true;
    }

    private static void writeAtomically(java.io.File file, byte[] body) throws java.io.IOException {
        java.io.File dir = file.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("mkdirs " + dir);
        java.io.File tmp = new java.io.File(dir, file.getName() + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(body);
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) throw new java.io.IOException("rename " + tmp);
    }

    // Native methods implemented in libcef.so.
    // Where a download the app allows without a path goes: the app's own
    // Downloads directory, which needs no permission.
    private static String downloadDir(Context context) {
        java.io.File dir =
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new java.io.File(context.getFilesDir(), "Download");
        dir.mkdirs();
        return dir.getAbsolutePath();
    }

    private static native int nativeInitialize(String cacheDir, String dataDir,
            String downloadDir);

    /** What a profile root holds that must survive, by Chromium's names. */
    private static final String[] PROFILE_ENTRIES = {"Default", "Local State"};

    /**
     * The engine's data root, after moving pre-0.14.0 data out of the cache
     * directory. Never loses data: if the move fails, this run keeps using
     * the old location and the next start tries again.
     */
    private static java.io.File dataRoot(Context appContext) {
        java.io.File root = sDataDirectory != null
                ? sDataDirectory : appContext.getDir("cefrium", Context.MODE_PRIVATE);
        java.io.File used = migrateEngineData(appContext.getCacheDir(), root);
        CefriumProfile.onDataRoot(used);
        return used;
    }

    /**
     * Moves the profile entries from {@code legacy} into {@code root} and
     * returns the root the engine must use. Package-private for tests.
     */
    static java.io.File migrateEngineData(java.io.File legacy, java.io.File root) {
        if (!new java.io.File(legacy, "Default").isDirectory()) {
            root.mkdirs();
            return root;
        }
        if (new java.io.File(root, "Default").exists()) {
            // Both exist: the new root wins and the old copy is left alone.
            android.util.Log.w(TAG, "Engine data found in both " + legacy + " and " + root
                    + "; using " + root + " and leaving the old copy untouched");
            return root;
        }
        if (!root.isDirectory() && !root.mkdirs()) {
            android.util.Log.w(TAG, "Cannot create " + root + "; keeping engine data in "
                    + legacy + " for this run");
            return legacy;
        }
        for (String name : PROFILE_ENTRIES) {
            java.io.File from = new java.io.File(legacy, name);
            if (!from.exists()) continue;
            if (!from.renameTo(new java.io.File(root, name))) {
                // Undo the part that moved, so the old root stays complete.
                for (String done : PROFILE_ENTRIES) {
                    if (done.equals(name)) break;
                    new java.io.File(root, done).renameTo(new java.io.File(legacy, done));
                }
                android.util.Log.w(TAG, "Moving engine data to " + root + " failed at "
                        + name + "; keeping it in " + legacy + " for this run");
                return legacy;
            }
        }
        android.util.Log.i(TAG, "Moved engine data from " + legacy + " to " + root);
        return root;
    }
    private static native void nativeShutdown();
    private static native int nativeLoadAdBlockList(String list, String path);
    private static native int nativeLoadYouTubeAdRules();
    private static native boolean nativeSetSecureDns(String mode, String templates);
    private static native void nativeFetchAdBlockList(String url, long id);
}
