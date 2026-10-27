// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where a browser keeps its cookies, local storage, IndexedDB, service workers
 * and cache. Browsers of one profile share that data; browsers of different
 * profiles see none of each other's.
 *
 * <p>Pass a profile when creating a browser, e.g.
 * {@code CefriumBrowser.createWithSurface(activity, CefriumProfile.get("work"))}.
 * A named profile lives in {@code profile-<name>} under the engine's data
 * directory ({@link Cefrium#setDataDirectory}) and persists across starts.
 * {@link #incognito()} keeps everything in memory and forgets it when its last
 * browser closes.</p>
 *
 * <p>The engine keeps a profile's databases open once it has been used, so do
 * not copy or delete its directory yourself while the app runs: use
 * {@link #delete(String)}, and back a profile up only from a process that has
 * not opened it.</p>
 */
public final class CefriumProfile {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final CefriumProfile DEFAULT = new CefriumProfile(null, false);
    private static final CefriumProfile INCOGNITO = new CefriumProfile(null, true);
    /** Directories of profiles deleted while open in a previous run. */
    private static final String PENDING_DELETE = "pending-delete";
    // The Chrome runtime only creates a disk profile in an immediate child of
    // the data root; the prefix keeps clear of Chrome's own entries there.
    private static final String DIR_PREFIX = "profile-";

    // Open browsers per named profile, and the named profiles whose engine
    // context exists in this process (it outlives its last browser).
    private static final Map<String, Integer> sOpenBrowsers = new HashMap<>();
    private static final Set<String> sUsedThisRun = new HashSet<>();
    private static File sRoot;

    private final String mName;
    private final boolean mIncognito;

    private CefriumProfile(String name, boolean incognito) {
        mName = name;
        mIncognito = incognito;
    }

    /** The engine's default persistent profile, used when none is given. */
    public static CefriumProfile getDefault() {
        return DEFAULT;
    }

    /** Off the record: in memory only, shared by every incognito browser. */
    public static CefriumProfile incognito() {
        return INCOGNITO;
    }

    /**
     * A named persistent profile, created on first use.
     *
     * @param name letters, digits, {@code -} and {@code _}, 1 to 64 characters.
     * @throws IllegalArgumentException for any other name.
     */
    public static CefriumProfile get(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("CefriumProfile name \"" + name
                    + "\" must be 1 to 64 letters, digits, '-' or '_'");
        }
        return new CefriumProfile(name, false);
    }

    /** @return the profile's name, or null for the default and incognito profiles. */
    public String getName() {
        return mName;
    }

    /** @return true for {@link #incognito()}. */
    public boolean isIncognito() {
        return mIncognito;
    }

    /**
     * @return where this profile's data lives: the engine's data directory for
     *         the default profile, {@code profile-<name>} under it for a named
     *         one, null for incognito.
     * @throws IllegalStateException if the engine cannot start.
     */
    public File getDirectory() {
        File root = root();
        if (mIncognito) return null;
        return mName == null ? root : new File(root, DIR_PREFIX + mName);
    }

    /**
     * Deletes a named profile's data.
     *
     * @return true if the data was deleted now; false if the profile was used
     *         in this run, in which case the engine still holds its databases
     *         open and the data is deleted on the next start, before the
     *         engine opens anything.
     * @throws IllegalStateException if a browser of this profile is open, or
     *         if the engine cannot start.
     */
    public static boolean delete(String name) {
        Cefrium.ensureInitialized();
        return deleteStarted(name);
    }

    private static synchronized boolean deleteStarted(String name) {
        CefriumProfile profile = get(name);
        Integer open = sOpenBrowsers.get(name);
        if (open != null && open > 0) {
            throw new IllegalStateException("CefriumProfile \"" + name + "\" has "
                    + open + " open browser(s); close them first");
        }
        File dir = profile.getDirectory();
        if (sUsedThisRun.contains(name)) {
            schedule(dir);
            return false;
        }
        deleteRecursively(dir);
        return true;
    }

    /** One site's data in a profile, as {@link #listSiteData} reports it. */
    public static final class SiteData {
        /** The site: a host, or an origin for data Chromium keys by origin. */
        public final String site;
        /** Bytes of storage: local and session storage, IndexedDB, Cache
         *  Storage, File System and the other per-site stores. The HTTP cache
         *  is not attributed per site and is not included. */
        public final long storageBytes;
        /** Number of cookies. */
        public final long cookieCount;

        SiteData(String site, long storageBytes, long cookieCount) {
            this.site = site;
            this.storageBytes = storageBytes;
            this.cookieCount = cookieCount;
        }
    }

    /** Receives {@link #listSiteData}'s result, on the UI thread. */
    public interface SiteDataCallback {
        void onSiteData(List<SiteData> sites);
    }

    private static final android.os.Handler sMain =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Map<Long, Object> sPending = new HashMap<>();
    private static long sNextRequest = 1;

    /**
     * Lists every site that has data in this profile, with its size and
     * cookie count: the same figures Chrome's per-site storage screen shows.
     *
     * @throws IllegalStateException if the engine cannot start.
     */
    public void listSiteData(SiteDataCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback must not be null");
        root();
        long id = register(callback);
        sMain.post(() -> nativeListSiteData(mIncognito, mName, id));
    }

    /**
     * Deletes everything this profile holds for {@code site}: cookies, local
     * and session storage, IndexedDB, Cache Storage, service workers and the
     * other per-site stores. {@code site} is a value from {@link #listSiteData}
     * or an origin URL such as {@code https://example.com}, which also covers
     * the data Chromium keeps under that host.
     *
     * @param done runs on the UI thread once the data is gone; may be null.
     * @throws IllegalStateException if the engine cannot start.
     */
    public void clearSiteData(String site, Runnable done) {
        if (site == null || site.isEmpty()) throw new IllegalArgumentException("site must not be empty");
        root();
        long id = register(done == null ? (Runnable) () -> {} : done);
        sMain.post(() -> nativeClearSiteData(mIncognito, mName, site, id));
    }

    // ----- Network privacy settings -------------------------------------------
    // Profile-wide: every browser of this profile uses them, including browsers
    // opened later. The default and named profiles save them with their data,
    // so they survive a restart until changed. The incognito profile follows
    // the default profile's, as in Chrome, unless it is given its own; those
    // last for this run, across private sessions. Named profiles are separate
    // and follow nothing. Call on the main thread; they can be set before any
    // browser of the profile exists.

    /**
     * For the incognito profile: drops the proxy and WebRTC settings set on it,
     * so it follows the default profile's again. Does nothing on other profiles.
     */
    public void followDefaultProfile() {
        if (!mIncognito) return;
        root();
        nativeFollowDefaultProfile();
    }

    /**
     * Routes this profile's traffic through a proxy. {@code mode} is one of the
     * {@code CefriumBrowser.PROXY_*} constants; {@code server} is
     * {@code "host:port"} or {@code "scheme://host:port"} (e.g.
     * {@code "socks5://127.0.0.1:9050"}) for {@code PROXY_FIXED}, the PAC URL
     * for {@code PROXY_PAC}, and ignored otherwise.
     *
     * <p>A fixed proxy has no direct fallback: when it cannot be reached,
     * requests fail instead of leaving without it. Loopback addresses
     * (localhost, 127.0.0.1) are never proxied, as in Chrome. WebRTC is not
     * covered by a proxy; see {@link #setWebRtcIpHandlingPolicy}.
     *
     * @return false if the engine rejected the setting.
     */
    public boolean setProxy(String mode, String server) {
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        StringBuilder json = new StringBuilder("{\"mode\":").append(org.json.JSONObject.quote(mode));
        if (server != null) {
            json.append(CefriumBrowser.PROXY_PAC.equals(mode) ? ",\"pac_url\":" : ",\"server\":")
                .append(org.json.JSONObject.quote(server));
        }
        return setPreference("proxy", json.append('}').toString());
    }

    /** {@link #setProxy(String, String)} with {@code PROXY_FIXED}. */
    public boolean setProxy(String server) {
        return setProxy(CefriumBrowser.PROXY_FIXED, server);
    }

    /** Direct connections again. */
    public boolean clearProxy() {
        return setProxy(CefriumBrowser.PROXY_DIRECT, null);
    }

    /** WebRTC policies for {@link #setWebRtcIpHandlingPolicy}, as in Chrome. */
    public static final String WEBRTC_DEFAULT = "default";
    public static final String WEBRTC_DEFAULT_PUBLIC_AND_PRIVATE_INTERFACES =
            "default_public_and_private_interfaces";
    public static final String WEBRTC_DEFAULT_PUBLIC_INTERFACE_ONLY =
            "default_public_interface_only";
    public static final String WEBRTC_DISABLE_NON_PROXIED_UDP = "disable_non_proxied_udp";

    /**
     * Which network interfaces and transports WebRTC may use. A proxy does not
     * carry WebRTC's UDP, so with a proxy alone a page can still learn the
     * device's address through a peer connection. With
     * {@link #WEBRTC_DISABLE_NON_PROXIED_UDP} WebRTC uses no UDP that bypasses
     * the proxy, and gathers no local or public address candidates.
     *
     * @return false if the engine rejected the setting.
     */
    public boolean setWebRtcIpHandlingPolicy(String policy) {
        if (policy == null) throw new IllegalArgumentException("policy must not be null");
        return setPreference("webrtc.ip_handling_policy", org.json.JSONObject.quote(policy));
    }

    private boolean setPreference(String name, String json) {
        root();
        return nativeSetPreference(mIncognito, mName, name, json);
    }

    // ----- Proxy errors -----

    /** Told when a request fails because of the proxy, on the main thread. */
    public interface ProxyErrorListener {
        /**
         * @param browser   the browser the request belongs to.
         * @param url       the request's URL.
         * @param error     the net error, as CEF's {@code cef_errorcode_t}: for
         *                  example -130 when the proxy cannot be reached,
         *                  -120 when a SOCKS proxy fails to connect.
         * @param mainFrame true for a page load, false for a part of a page.
         */
        void onProxyError(CefriumBrowser browser, String url, int error, boolean mainFrame);
    }

    private static final Map<CefriumProfile, ProxyErrorListener> sProxyErrorListeners =
            new HashMap<>();

    /**
     * Tells {@code listener} about every request of this profile's browsers
     * that fails because of the proxy (the proxy cannot be reached, refuses
     * the connection, asks for credentials, ...), so an app can tell its
     * user that Tor or the VPN is down. Requests that fail this way are not
     * retried without the proxy. Incognito browsers report to
     * {@link #incognito()}'s listener. Pass null to stop.
     */
    public void setProxyErrorListener(ProxyErrorListener listener) {
        synchronized (CefriumProfile.class) {
            if (listener == null) {
                sProxyErrorListeners.remove(this);
            } else {
                sProxyErrorListeners.put(this, listener);
            }
        }
    }

    void dispatchProxyError(CefriumBrowser browser, String url, int error, boolean mainFrame) {
        ProxyErrorListener listener;
        synchronized (CefriumProfile.class) {
            listener = sProxyErrorListeners.get(this);
        }
        if (listener != null) listener.onProxyError(browser, url, error, mainFrame);
    }

    // ----- Downloads -----

    /** {@link Download#getState()}: still running, or paused. */
    public static final int DOWNLOAD_IN_PROGRESS = 0;
    /** {@link Download#getState()}: finished. */
    public static final int DOWNLOAD_COMPLETE = 1;
    /** {@link Download#getState()}: canceled; the partial file is gone. */
    public static final int DOWNLOAD_CANCELED = 2;
    /**
     * {@link Download#getState()}: stopped on an error, or because the app
     * was closed while it ran. {@link Download#canResume()} says whether it
     * can continue.
     */
    public static final int DOWNLOAD_INTERRUPTED = 3;

    /**
     * A download of this profile as it was when reported. Downloads of a
     * persistent profile are kept by the engine across runs, so the list
     * includes earlier runs' downloads; one the app was closed in the middle
     * of comes back interrupted, and can be resumed if the server allows it.
     */
    public final class Download {
        private final String mGuid, mUrl, mPath, mMimeType;
        private final int mState, mInterruptReason;
        private final long mReceived, mTotal, mStart, mEnd;
        private final boolean mPaused, mCanResume;

        private Download(long[] n, String[] s) {
            mState = (int) n[0];
            mInterruptReason = (int) n[1];
            mReceived = n[2];
            mTotal = n[3];
            mStart = n[4];
            mEnd = n[5];
            mPaused = n[6] != 0;
            mCanResume = n[7] != 0;
            mGuid = s[0];
            mUrl = s[1];
            mPath = s[2];
            mMimeType = s[3];
        }

        /** Identifies the download in this profile, across runs. */
        public String getGuid() { return mGuid; }
        public String getUrl() { return mUrl; }
        /** Where the file is saved; empty before that is decided. */
        public String getPath() { return mPath; }
        public String getMimeType() { return mMimeType; }
        /** One of the {@code DOWNLOAD_*} states. */
        public int getState() { return mState; }
        /** CEF {@code cef_download_interrupt_reason_t}; 0 if none. */
        public int getInterruptReason() { return mInterruptReason; }
        public long getReceivedBytes() { return mReceived; }
        /** -1 or 0 if the server did not say. */
        public long getTotalBytes() { return mTotal; }
        /** Milliseconds since the epoch. */
        public long getStartTime() { return mStart; }
        /** Milliseconds since the epoch; 0 while it has not ended. */
        public long getEndTime() { return mEnd; }
        public boolean isPaused() { return mPaused; }
        /** Paused, or interrupted in a way it can continue from. */
        public boolean canResume() { return mCanResume; }
        /** The profile it belongs to. */
        public CefriumProfile getProfile() { return CefriumProfile.this; }

        /** Cancels it and deletes the partial file. */
        public boolean cancel() { return control(0); }
        public boolean pause() { return control(1); }
        /** Continues a paused or interrupted download; false if it cannot. */
        public boolean resume() { return control(2); }
        /** Removes it from the list. The file is kept. */
        public boolean remove() { return control(3); }

        private boolean control(int action) {
            return nativeControlDownload(mIncognito, nameForNative(), mGuid, action);
        }
    }

    /** Receives {@link #getDownloads}'s result, on the UI thread. */
    public interface DownloadsCallback {
        void onDownloads(List<Download> downloads);
    }

    /** Receives every change to a download of a profile, on the UI thread. */
    public interface DownloadObserver {
        void onDownloadUpdated(Download download);
    }

    private static final Map<CefriumProfile, DownloadObserver> sDownloadObservers =
            new HashMap<>();

    /**
     * Every download of this profile, newest last, including the downloads
     * of earlier runs of a persistent profile. Call on the UI thread.
     */
    public void getDownloads(DownloadsCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback must not be null");
        root();
        nativeGetDownloads(mIncognito, nameForNative(), register(callback));
    }

    /**
     * Reports every change to a download of this profile, whichever browser
     * started it and whether or not that browser is still open: a download
     * resumed after a restart has none. Pass null to stop. Call on the UI
     * thread.
     *
     * @return false if the profile is not available (incognito before any
     *         private browser exists).
     */
    public boolean setDownloadObserver(DownloadObserver observer) {
        root();
        synchronized (CefriumProfile.class) {
            if (observer == null) {
                sDownloadObservers.remove(this);
            } else {
                sDownloadObservers.put(this, observer);
            }
        }
        return nativeObserveDownloads(mIncognito, nameForNative(), observer != null);
    }

    private String nameForNative() {
        return mName == null ? "" : mName;
    }

    // Called by native code on the UI thread: requestId 0 is a change for the
    // profile's observer, any other a getDownloads answer. Per download, 8
    // numbers and 4 strings (cefrium_downloads.cc).
    @SuppressWarnings("unused")
    private static void onDownloads(long requestId, boolean incognito, String name,
                                    long[] numbers, String[] strings) {
        CefriumProfile profile =
                incognito ? INCOGNITO : name.isEmpty() ? DEFAULT : new CefriumProfile(name, false);
        List<Download> downloads = new ArrayList<>();
        for (int i = 0; i * 8 < numbers.length; i++) {
            downloads.add(profile.new Download(
                    java.util.Arrays.copyOfRange(numbers, i * 8, i * 8 + 8),
                    java.util.Arrays.copyOfRange(strings, i * 4, i * 4 + 4)));
        }
        if (requestId == 0) {
            DownloadObserver observer;
            synchronized (CefriumProfile.class) {
                observer = sDownloadObservers.get(profile);
            }
            if (observer != null) {
                for (Download d : downloads) observer.onDownloadUpdated(d);
            }
            return;
        }
        Object callback;
        synchronized (CefriumProfile.class) {
            callback = sPending.remove(requestId);
        }
        if (callback instanceof DownloadsCallback) {
            ((DownloadsCallback) callback).onDownloads(Collections.unmodifiableList(downloads));
        }
    }

    private static native void nativeGetDownloads(boolean incognito, String profile,
                                                  long requestId);
    private static native boolean nativeObserveDownloads(boolean incognito, String profile,
                                                         boolean observe);
    private static native boolean nativeControlDownload(boolean incognito, String profile,
                                                        String guid, int action);

    private static synchronized long register(Object callback) {
        long id = sNextRequest++;
        sPending.put(id, callback);
        return id;
    }

    // Called by native code on the UI thread; owners is null on failure.
    @SuppressWarnings("unused")
    private static void onSiteDataResult(long requestId, String[] owners, long[] bytes,
                                         long[] cookies) {
        Object callback;
        synchronized (CefriumProfile.class) {
            callback = sPending.remove(requestId);
        }
        if (callback instanceof Runnable) {
            ((Runnable) callback).run();
            return;
        }
        if (!(callback instanceof SiteDataCallback)) return;
        List<SiteData> sites = new ArrayList<>();
        if (owners != null) {
            for (int i = 0; i < owners.length; i++) {
                sites.add(new SiteData(owners[i], bytes[i], cookies[i]));
            }
        }
        ((SiteDataCallback) callback).onSiteData(Collections.unmodifiableList(sites));
    }

    private static native void nativeListSiteData(boolean incognito, String profile,
                                                  long requestId);
    private static native void nativeClearSiteData(boolean incognito, String profile,
                                                   String site, long requestId);
    private static native void nativeFollowDefaultProfile();
    private static native boolean nativeSetPreference(boolean incognito, String profile,
                                                      String name, String json);

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CefriumProfile)) return false;
        CefriumProfile p = (CefriumProfile) o;
        return mIncognito == p.mIncognito
                && (mName == null ? p.mName == null : mName.equals(p.mName));
    }

    @Override
    public int hashCode() {
        return (mName == null ? 0 : mName.hashCode()) * 31 + (mIncognito ? 1 : 0);
    }

    // ---- package-private: CefriumBrowser and Cefrium ----

    static synchronized void browserOpened(CefriumProfile profile) {
        if (profile.mName == null) return;
        sUsedThisRun.add(profile.mName);
        Integer n = sOpenBrowsers.get(profile.mName);
        sOpenBrowsers.put(profile.mName, n == null ? 1 : n + 1);
    }

    static synchronized void browserClosed(CefriumProfile profile) {
        if (profile.mName == null) return;
        Integer n = sOpenBrowsers.get(profile.mName);
        if (n != null) sOpenBrowsers.put(profile.mName, Math.max(0, n - 1));
    }

    /**
     * Records the engine's data root and deletes the profiles scheduled by
     * {@link #delete} in an earlier run. Called before the engine starts, so
     * nothing has those databases open.
     */
    static synchronized void onDataRoot(File root) {
        sRoot = root;
        File list = new File(root, PENDING_DELETE);
        if (!list.isFile()) return;
        try {
            for (String line : Files.readAllLines(list.toPath(), StandardCharsets.UTF_8)) {
                String name = line.trim();
                if (NAME.matcher(name).matches()) {
                    deleteRecursively(new File(root, DIR_PREFIX + name));
                }
            }
        } catch (IOException e) {
            android.util.Log.w("CefriumProfile", "Cannot read " + list, e);
        }
        list.delete();
    }

    // Starts the engine first; callers holding this class's lock must have
    // started it already (onDataRoot takes the lock on the main thread).
    private static File root() {
        Cefrium.ensureInitialized();
        synchronized (CefriumProfile.class) {
            if (sRoot == null) {
                throw new IllegalStateException("The engine did not report its data root");
            }
            return sRoot;
        }
    }

    private static void schedule(File dir) {
        File list = new File(root(), PENDING_DELETE);
        Set<String> names = new LinkedHashSet<>();
        try {
            if (list.isFile()) {
                names.addAll(Files.readAllLines(list.toPath(), StandardCharsets.UTF_8));
            }
            names.add(dir.getName().substring(DIR_PREFIX.length()));
            try (FileOutputStream out = new FileOutputStream(list)) {
                out.write((String.join("\n", names) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot schedule deleting " + dir, e);
        }
    }

    private static void deleteRecursively(File f) {
        // A link is removed, never followed: it could point outside the profile.
        if (Files.isSymbolicLink(f.toPath())) {
            f.delete();
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }
}
