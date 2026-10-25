package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumProfile;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** FEATURE-071: named profiles keep separate cookies and storage. */
@RunWith(AndroidJUnit4.class)
public class CefriumProfileTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    // ?write stores a persistent cookie and a localStorage entry; every load
    // reports what it can see.
    private static final String PAGE = "<html><body><script>"
            + "if(location.search==='?write'){"
            + "document.cookie='p=1; max-age=86400; path=/';localStorage.setItem('p','1');}"
            + "document.title='seen:cookie='+(document.cookie.indexOf('p=1')>=0)"
            + "+',storage='+(localStorage.getItem('p')==='1');"
            + "</script></body></html>";

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Loads url in a new browser of profile; returns what the page saw. */
    private String visit(CefriumProfile profile, String url,
                         AtomicReference<CefriumBrowser> keep) throws Exception {
        Activity activity = activityRule.getActivity();
        final AtomicReference<String> seen = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity, profile);
            if (keep != null) keep.set(browser);
            browser.setOnTitleChangedListener(t -> {
                if (t != null && t.startsWith("seen:")) {
                    seen.set(t);
                    done.countDown();
                }
            });
            browser.loadUrl(url);
        });
        Assert.assertTrue("the page never reported for " + url,
                done.await(20, TimeUnit.SECONDS));
        return seen.get();
    }

    private void close(AtomicReference<CefriumBrowser> b) {
        activityRule.getActivity().runOnUiThread(() -> {
            if (b.get() != null) b.get().close();
        });
    }

    /** Another profile and the default profile do not see profile A's data. */
    @Test
    public void profilesKeepSeparateCookiesAndStorage() throws Exception {
        CefriumProfile a = CefriumProfile.get(unique("a"));
        CefriumProfile b = CefriumProfile.get(unique("b"));
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            String none = "seen:cookie=false,storage=false";
            String both = "seen:cookie=true,storage=true";
            Assert.assertEquals(both, visit(a, server.url() + "?write", null));
            Assert.assertEquals("profile B saw profile A's data",
                    none, visit(b, server.url(), null));
            Assert.assertEquals("the default profile saw profile A's data",
                    none, visit(CefriumProfile.getDefault(), server.url(), null));
            // Positive control: a new browser of A sees what A stored.
            AtomicReference<CefriumBrowser> again = new AtomicReference<>();
            Assert.assertEquals(both, visit(a, server.url(), again));
            // On disk, not only in memory: an in-memory context would pass
            // every check above. The cookie store is written lazily; flush it.
            activityRule.getActivity().runOnUiThread(() -> again.get().flushCookies());
        }
        File dir = a.getDirectory();
        Assert.assertEquals("profile-" + a.getName(), dir.getName());
        File cookies = new File(dir, "Cookies");
        long deadline = System.currentTimeMillis() + 10_000;
        while (!cookies.isFile() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        String[] listing = dir.list();
        Assert.assertTrue("profile A's cookie store is not on disk at " + cookies
                + "; " + dir + " holds " + (listing == null ? "nothing" : String.join(",", listing)),
                cookies.isFile());
    }

    @Test
    public void namesThatCouldEscapeTheirDirectoryAreRejected() {
        String[] bad = {"", "..", "a/b", "a\\b", "a b", "x".repeat(65), null};
        for (String name : bad) {
            try {
                CefriumProfile.get(name);
                Assert.fail("accepted profile name \"" + name + "\"");
            } catch (IllegalArgumentException expected) {
                // Rejected, as it must be.
            }
        }
        Assert.assertEquals("ok-1_A", CefriumProfile.get("ok-1_A").getName());
        Assert.assertEquals(64, CefriumProfile.get("x".repeat(64)).getName().length());
    }

    /**
     * delete() refuses while a browser is open, schedules the deletion once
     * the profile has been used in this run, and deletes at once otherwise.
     */
    @Test
    public void deleteRespectsOpenBrowsersAndUseInThisRun() throws Exception {
        String used = unique("used");
        AtomicReference<CefriumBrowser> open = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            visit(CefriumProfile.get(used), server.url() + "?write", open);
            try {
                CefriumProfile.delete(used);
                Assert.fail("deleted a profile with an open browser");
            } catch (IllegalStateException expected) {
                // Refused, as it must be.
            }
        } finally {
            close(open);
        }
        Thread.sleep(500);  // close() runs on the UI thread
        Assert.assertFalse("a profile used in this run must be deleted on the next start",
                CefriumProfile.delete(used));
        Assert.assertTrue("the data must stay until the next start",
                CefriumProfile.get(used).getDirectory().isDirectory());

        String never = unique("never");
        File dir = CefriumProfile.get(never).getDirectory();
        Assert.assertTrue(new File(dir, "Default").mkdirs());
        Assert.assertTrue("a profile not used in this run is deleted at once",
                CefriumProfile.delete(never));
        Assert.assertFalse(dir.exists());
    }

    @Test
    public void incognitoAndDefaultAreDistinct() {
        Assert.assertTrue(CefriumProfile.incognito().isIncognito());
        Assert.assertNull(CefriumProfile.incognito().getDirectory());
        Assert.assertFalse(CefriumProfile.getDefault().isIncognito());
        Assert.assertNull(CefriumProfile.getDefault().getName());
        Assert.assertEquals(CefriumProfile.get("same"), CefriumProfile.get("same"));
    }
}
