// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.os.Looper;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumCookie;
import com.cefrium.CefriumCookieManager;
import com.cefrium.CefriumProfile;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * CefriumProfile.getCookieManager(): a Set-Cookie value with Max-Age,
 * HttpOnly and SameSite is stored with those attributes and comes back
 * typed; a browser of the profile sends it; a session cookie has no expiry;
 * a cookie the page sets is read back; removing one, all, and Max-Age=0 work;
 * results arrive on the UI thread. A profile of its own: cookies ignore the
 * port, so the loopback servers of other tests would share them.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumCookieManagerTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private final List<String> offUi = new CopyOnWriteArrayList<>();

    private <T> T await(Consumer<Consumer<T>> call) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        call.accept(v -> {
            if (Looper.myLooper() != Looper.getMainLooper()) offUi.add("result");
            result.set(v);
            done.countDown();
        });
        Assert.assertTrue("no result", done.await(15, TimeUnit.SECONDS));
        return result.get();
    }

    private static CefriumCookie named(List<CefriumCookie> list, String name) {
        for (CefriumCookie c : list) if (c.getName().equals(name)) return c;
        return null;
    }

    @Test
    public void cookiesOfAProfile() throws Exception {
        CefriumProfile profile = CefriumProfile.get("cookie-manager-test");
        CefriumCookieManager cookies = profile.getCookieManager();
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        List<String> titles = new CopyOnWriteArrayList<>();
        try (LocalHttpServer server = new LocalHttpServer("<!doctype html><script>"
                + "document.cookie = 'p=3; path=/'; document.title = 'loaded';</script>")) {
            String url = server.url();
            Integer cleared = await(done -> cookies.removeAllCookies(done));
            Assert.assertNotNull(cleared);

            long before = System.currentTimeMillis();
            Boolean ok = await(done -> cookies.setCookie(url,
                    "a=1; Max-Age=3600; Path=/; HttpOnly; SameSite=Lax", done));
            Assert.assertTrue("the engine refused the cookie", ok);
            Assert.assertTrue(await(done -> cookies.setCookie(url, "s=2; Path=/", done)));

            List<CefriumCookie> list = await(done -> cookies.getCookies(url, done));
            CefriumCookie a = named(list, "a");
            Assert.assertNotNull("a is missing: " + list.size() + " cookies", a);
            Assert.assertEquals("1", a.getValue());
            Assert.assertTrue(a.isHttpOnly());
            Assert.assertEquals(CefriumCookie.SameSite.LAX, a.getSameSite());
            long expected = before + 3600_000L;
            Assert.assertTrue("expiry " + a.getExpiresAtMillis() + " vs " + expected,
                    Math.abs(a.getExpiresAtMillis() - expected) < 120_000L);
            Assert.assertEquals("a session cookie has no expiry", 0,
                    named(list, "s").getExpiresAtMillis());

            // A browser of the profile sends them, and the page's own cookie
            // is read back.
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f, profile));
                browser.set(b);
                b.setOnTitleChangedListener(titles::add);
                b.loadUrl(url + "page");
            });
            long end = SystemClock.uptimeMillis() + 20000;
            while (!titles.contains("loaded") && SystemClock.uptimeMillis() < end) {
                Thread.sleep(100);
            }
            Assert.assertTrue("the page did not load", titles.contains("loaded"));
            boolean sent = false;
            for (String h : server.headers) {
                sent |= h.startsWith("/page Cookie:") && h.contains("a=1") && h.contains("s=2");
            }
            Assert.assertTrue("the browser did not send the cookies: " + server.headers, sent);
            String header = await(done -> cookies.getCookie(url, done));
            Assert.assertTrue(header, header.contains("p=3") && header.contains("a=1"));

            Integer removed = await(done -> cookies.removeCookies(url, "a", done));
            Assert.assertEquals(Integer.valueOf(1), removed);
            Assert.assertNull(named(await(done -> cookies.getCookies(url, done)), "a"));

            // An expired cookie may be reported as refused; it must be gone.
            this.<Boolean>await(done -> cookies.setCookie(url, "s=gone; Max-Age=0; Path=/", done));
            Assert.assertNull("Max-Age=0 did not remove it",
                    named(await(done -> cookies.getCookies(url, done)), "s"));

            Integer all = await(done -> cookies.removeAllCookies(done));
            Assert.assertTrue("removed " + all, all >= 1);
            Assert.assertTrue(await((Consumer<List<CefriumCookie>> done) ->
                    cookies.getCookies(null, done)).isEmpty());

            CountDownLatch f = new CountDownLatch(1);
            cookies.flush(f::countDown);
            Assert.assertTrue("flush never finished", f.await(15, TimeUnit.SECONDS));
            Assert.assertTrue("results off the UI thread: " + offUi, offUi.isEmpty());
        }
    }

    @Test
    public void badSetCookieIsRejected() {
        CefriumCookieManager cookies = CefriumProfile.get("cookie-manager-test").getCookieManager();
        try {
            cookies.setCookie("http://127.0.0.1/", "no-value-here", null);
            Assert.fail("no exception");
        } catch (IllegalArgumentException expected) {
            // a value without name=value
        }
    }
}
