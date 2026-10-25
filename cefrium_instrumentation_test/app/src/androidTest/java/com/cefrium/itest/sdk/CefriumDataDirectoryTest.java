package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;
import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.Cefrium;
import com.cefrium.CefriumBrowser;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEATURE-071: the engine keeps its persistent data out of the app's cache
 * directory, which Android may clear when storage runs low.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumDataDirectoryTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    /**
     * A page stores a persistent cookie and localStorage; the cookie database
     * appears under app_cefrium/Default and nothing is written to the cache
     * directory's Default.
     */
    @Test
    public void persistentDataLivesOutsideTheCacheDirectory() throws Exception {
        final String page = "<html><body><script>"
                + "document.cookie='k=v; max-age=86400; path=/';"
                + "localStorage.setItem('k','v');"
                + "document.title='stored:'+document.cookie;"
                + "</script></body></html>";
        Activity activity = activityRule.getActivity();
        final AtomicReference<CefriumBrowser> created = new AtomicReference<>();
        final AtomicReference<String> title = new AtomicReference<>();
        final CountDownLatch stored = new CountDownLatch(1);
        try (LocalHttpServer server = new LocalHttpServer(page)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser browser = browsers.createWithSurface(activity);
                created.set(browser);
                browser.setOnTitleChangedListener(t -> {
                    if (t != null && t.startsWith("stored:")) {
                        title.set(t);
                        stored.countDown();
                    }
                });
                browser.loadUrl(server.url());
            });
            Assert.assertTrue("the page never stored its data",
                    stored.await(20, TimeUnit.SECONDS));
            // Positive control: the page really set the cookie.
            Assert.assertEquals("stored:k=v", title.get());
            activity.runOnUiThread(() -> created.get().flushCookies());

            Context app = activity.getApplicationContext();
            File root = Cefrium.getDataDirectory() != null
                    ? Cefrium.getDataDirectory() : app.getDir("cefrium", Context.MODE_PRIVATE);
            File cookies = new File(root, "Default/Cookies");
            long deadline = System.currentTimeMillis() + 10_000;
            while (!cookies.isFile() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            Assert.assertTrue("no cookie database at " + cookies, cookies.isFile());
            Assert.assertFalse("engine data was written to the cache directory",
                    new File(app.getCacheDir(), "Default").exists());
        }
    }
}
