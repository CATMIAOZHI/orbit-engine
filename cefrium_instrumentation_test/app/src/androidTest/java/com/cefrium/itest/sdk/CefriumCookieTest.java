package com.cefrium.itest.sdk;

import com.cefrium.itest.HostActivity;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instrumented tests for the FEATURE-024 cookie API
 * (setCookie / getCookies / deleteCookies / flushCookies on CefriumBrowser,
 * backed by CefCookieManager). No network needed — the cookie store is local.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumCookieTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    private static final String URL = "https://cefrium.test/";

    // CefCookieManager operations are async; poll getCookies until a cookie with
    // the given name/value appears (or time out).
    private List<CefriumBrowser.Cookie> awaitCookie(
            CefriumBrowser browser, Activity act, String name, String value)
            throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<List<CefriumBrowser.Cookie>> got =
                    new AtomicReference<>();
            act.runOnUiThread(() -> browser.getCookies(URL, cookies -> {
                got.set(cookies);
                latch.countDown();
            }));
            Assert.assertTrue("getCookies callback timed out",
                    latch.await(10, TimeUnit.SECONDS));
            for (CefriumBrowser.Cookie c : got.get()) {
                if (name.equals(c.name) && (value == null || value.equals(c.value))) {
                    return got.get();
                }
            }
            Thread.sleep(250);
        }
        return null;
    }

    @Test
    public void testSetAndGetCookie() throws Exception {
        Activity act = activityRule.getActivity();
        final CefriumBrowser[] holder = new CefriumBrowser[1];
        final CountDownLatch created = new CountDownLatch(1);
        act.runOnUiThread(() -> {
            holder[0] = CefriumBrowser.createWithSurface(act);
            holder[0].setCookie(URL, "sid", "abc123");
            created.countDown();
        });
        Assert.assertTrue(created.await(10, TimeUnit.SECONDS));

        List<CefriumBrowser.Cookie> cookies =
                awaitCookie(holder[0], act, "sid", "abc123");
        Assert.assertNotNull("set cookie was not read back", cookies);
        boolean found = false;
        for (CefriumBrowser.Cookie c : cookies) {
            if ("sid".equals(c.name)) { found = true; Assert.assertEquals("abc123", c.value); }
        }
        Assert.assertTrue("cookie 'sid' missing from getCookies result", found);
    }

    @Test
    public void testDeleteCookie() throws Exception {
        Activity act = activityRule.getActivity();
        final CefriumBrowser[] holder = new CefriumBrowser[1];
        final CountDownLatch created = new CountDownLatch(1);
        act.runOnUiThread(() -> {
            holder[0] = CefriumBrowser.createWithSurface(act);
            holder[0].setCookie(URL, "todelete", "x");
            created.countDown();
        });
        Assert.assertTrue(created.await(10, TimeUnit.SECONDS));

        Assert.assertNotNull("cookie should exist before delete",
                awaitCookie(holder[0], act, "todelete", "x"));

        act.runOnUiThread(() -> holder[0].deleteCookies(URL, "todelete"));

        // Poll until the cookie is gone.
        long deadline = System.currentTimeMillis() + 15000;
        boolean gone = false;
        while (System.currentTimeMillis() < deadline && !gone) {
            final CountDownLatch latch = new CountDownLatch(1);
            final CopyOnWriteArrayList<String> names = new CopyOnWriteArrayList<>();
            act.runOnUiThread(() -> holder[0].getCookies(URL, cookies -> {
                for (CefriumBrowser.Cookie c : cookies) names.add(c.name);
                latch.countDown();
            }));
            Assert.assertTrue(latch.await(10, TimeUnit.SECONDS));
            gone = !names.contains("todelete");
            if (!gone) Thread.sleep(250);
        }
        Assert.assertTrue("cookie 'todelete' still present after deleteCookies", gone);
    }

    /** clearCache() must not crash (FEATURE-030 smoke). */
    @Test
    public void testClearCacheSmoke() throws Exception {
        Activity act = activityRule.getActivity();
        final CountDownLatch done = new CountDownLatch(1);
        act.runOnUiThread(() -> {
            CefriumBrowser b = CefriumBrowser.createWithSurface(act);
            b.loadUrl("about:blank");
            b.clearCache();
            done.countDown();
        });
        Assert.assertTrue(done.await(10, TimeUnit.SECONDS));
    }
}
