// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.os.Handler;
import android.os.Looper;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The cookies of one {@link CefriumProfile}, from
 * {@link CefriumProfile#getCookieManager()}. Like
 * {@code android.webkit.CookieManager}, with the same {@code Set-Cookie}
 * syntax, but per profile and asynchronous: each method returns at once and
 * calls its callback on the UI thread. Callable from any thread.
 */
public final class CefriumCookieManager {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong IDS = new AtomicLong(1);
    private static final Map<Long, Object> PENDING = new ConcurrentHashMap<>();

    private final boolean mIncognito;
    private final String mProfile;

    CefriumCookieManager(boolean incognito, String profileForNative) {
        mIncognito = incognito;
        mProfile = profileForNative;
    }

    /**
     * Sets a cookie for {@code url} from a {@code Set-Cookie} value, e.g.
     * {@code "id=42; Max-Age=3600; Path=/; Secure; HttpOnly; SameSite=Lax"}.
     * {@code Expires}, {@code Max-Age} (which wins), {@code Domain},
     * {@code Path}, {@code Secure}, {@code HttpOnly} and {@code SameSite} are
     * read; without an expiry it is a session cookie. {@code done} gets
     * whether the engine accepted it (it refuses, for example,
     * {@code SameSite=None} without {@code Secure}); it may be null.
     *
     * @throws IllegalArgumentException if the value has no {@code name=value}
     */
    public void setCookie(String url, String setCookie, Consumer<Boolean> done) {
        Parsed c = parse(setCookie);
        long id = register(done);
        MAIN.post(() -> nativeSetCookie(mIncognito, mProfile, url, c.name, c.value, c.domain,
                c.path, c.secure, c.httpOnly, c.sameSite.ordinal(), c.expiresAtMillis, id));
    }

    /** The cookies {@code url} would send, as a {@code Cookie} header: {@code "a=1; b=2"}. */
    public void getCookie(String url, Consumer<String> result) {
        if (result == null) throw new IllegalArgumentException("result must not be null");
        getCookies(url, list -> {
            StringBuilder b = new StringBuilder();
            for (CefriumCookie c : list) {
                if (b.length() > 0) b.append("; ");
                b.append(c.getName()).append('=').append(c.getValue());
            }
            result.accept(b.toString());
        });
    }

    /** The cookies {@code url} would send; with a null or empty URL, every cookie. */
    public void getCookies(String url, Consumer<List<CefriumCookie>> result) {
        if (result == null) throw new IllegalArgumentException("result must not be null");
        long id = register(result);
        MAIN.post(() -> nativeGetCookies(mIncognito, mProfile, url == null ? "" : url, id));
    }

    /**
     * Removes the cookies of {@code url}, only those named {@code name} when it
     * is not null. {@code done} gets how many were removed; it may be null.
     */
    public void removeCookies(String url, String name, Consumer<Integer> done) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("url must not be empty; use removeAllCookies");
        }
        long id = register(done);
        MAIN.post(() -> nativeDeleteCookies(mIncognito, mProfile, url,
                name == null ? "" : name, id));
    }

    /** Removes every cookie of the profile. {@code done} gets how many; it may be null. */
    public void removeAllCookies(Consumer<Integer> done) {
        long id = register(done);
        MAIN.post(() -> nativeDeleteCookies(mIncognito, mProfile, "", "", id));
    }

    /** Writes the cookies to disk now rather than later; {@code done} may be null. */
    public void flush(Runnable done) {
        long id = register(done);
        MAIN.post(() -> nativeFlush(mIncognito, mProfile, id));
    }

    // ---- Set-Cookie parsing ----

    static final class Parsed {
        String name;
        String value;
        String domain = "";
        String path = "";
        boolean secure;
        boolean httpOnly;
        long expiresAtMillis;
        CefriumCookie.SameSite sameSite = CefriumCookie.SameSite.UNSPECIFIED;
    }

    static Parsed parse(String setCookie) {
        if (setCookie == null) throw new IllegalArgumentException("no cookie");
        String[] parts = setCookie.split(";");
        int eq = parts[0].indexOf('=');
        if (eq <= 0) throw new IllegalArgumentException("not name=value: " + setCookie);
        Parsed p = new Parsed();
        p.name = parts[0].substring(0, eq).trim();
        p.value = parts[0].substring(eq + 1).trim();
        if (p.name.isEmpty()) throw new IllegalArgumentException("empty name: " + setCookie);
        Long maxAge = null;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            int e = part.indexOf('=');
            String key = (e < 0 ? part : part.substring(0, e)).trim().toLowerCase(Locale.ROOT);
            String val = e < 0 ? "" : part.substring(e + 1).trim();
            switch (key) {
                case "domain": p.domain = val; break;
                case "path": p.path = val; break;
                case "secure": p.secure = true; break;
                case "httponly": p.httpOnly = true; break;
                case "samesite":
                    switch (val.toLowerCase(Locale.ROOT)) {
                        case "none": p.sameSite = CefriumCookie.SameSite.NONE; break;
                        case "lax": p.sameSite = CefriumCookie.SameSite.LAX; break;
                        case "strict": p.sameSite = CefriumCookie.SameSite.STRICT; break;
                        default: break;
                    }
                    break;
                case "max-age":
                    try {
                        maxAge = Long.parseLong(val);
                    } catch (NumberFormatException ignored) {
                        // An unreadable Max-Age is ignored, as browsers do.
                    }
                    break;
                case "expires":
                    try {
                        p.expiresAtMillis = ZonedDateTime.parse(val,
                                DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                    } catch (DateTimeParseException ignored) {
                        // An unreadable date is ignored, as browsers do.
                    }
                    break;
                default:
                    break;
            }
        }
        if (maxAge != null) {
            // Zero or less removes the cookie: an expiry in the past.
            p.expiresAtMillis = maxAge <= 0 ? 1 : System.currentTimeMillis() + maxAge * 1000;
        }
        return p;
    }

    // ---- results from native, on the UI thread ----

    private static long register(Object callback) {
        long id = IDS.getAndIncrement();
        if (callback != null) PENDING.put(id, callback);
        return id;
    }

    @SuppressWarnings({"unused", "unchecked"})
    private static void onSetCookieDone(long id, boolean ok) {
        Object cb = PENDING.remove(id);
        if (cb != null) ((Consumer<Boolean>) cb).accept(ok);
    }

    @SuppressWarnings({"unused", "unchecked"})
    private static void onCookiesDeleted(long id, int count) {
        Object cb = PENDING.remove(id);
        if (cb != null) ((Consumer<Integer>) cb).accept(count);
    }

    @SuppressWarnings("unused")
    private static void onFlushed(long id, boolean ok) {
        Object cb = PENDING.remove(id);
        if (cb != null) ((Runnable) cb).run();
    }

    @SuppressWarnings({"unused", "unchecked"})
    private static void onCookies(long id, String[] names, String[] values, String[] domains,
            String[] paths, boolean[] secure, boolean[] httpOnly, long[] expires,
            int[] sameSite) {
        Object cb = PENDING.remove(id);
        if (cb == null) return;
        List<CefriumCookie> list = new ArrayList<>(names.length);
        CefriumCookie.SameSite[] all = CefriumCookie.SameSite.values();
        for (int i = 0; i < names.length; i++) {
            list.add(new CefriumCookie(names[i], values[i], domains[i], paths[i], secure[i],
                    httpOnly[i], expires[i], all[sameSite[i]]));
        }
        ((Consumer<List<CefriumCookie>>) cb).accept(Collections.unmodifiableList(list));
    }

    private static native void nativeSetCookie(boolean incognito, String profile, String url,
            String name, String value, String domain, String path, boolean secure,
            boolean httpOnly, int sameSite, long expiresMs, long id);
    private static native void nativeGetCookies(boolean incognito, String profile, String url,
            long id);
    private static native void nativeDeleteCookies(boolean incognito, String profile,
            String url, String name, long id);
    private static native void nativeFlush(boolean incognito, String profile, long id);
}
