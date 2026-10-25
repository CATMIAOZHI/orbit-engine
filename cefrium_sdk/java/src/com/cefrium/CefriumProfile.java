// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
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
     * @throws IllegalStateException before the engine is initialized.
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
     *         before the engine is initialized.
     */
    public static synchronized boolean delete(String name) {
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

    private static synchronized File root() {
        if (sRoot == null) {
            throw new IllegalStateException("The engine is not initialized yet");
        }
        return sRoot;
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
