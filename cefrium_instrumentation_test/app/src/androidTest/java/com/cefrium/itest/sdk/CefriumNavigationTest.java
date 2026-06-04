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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Instrumented tests for FEATURE-026 navigation extras
 * (loadHtml / postUrl / stopLoading on CefriumBrowser).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumNavigationTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    /**
     * loadHtml end-to-end: the loaded HTML declares a distinctive &lt;title&gt;;
     * receiving it via onTitleChanged proves the HTML string was loaded and
     * parsed by the engine. (Asserting via the title avoids racing the JS-bridge
     * injection against an inline script at parse time.)
     */
    @Test
    public void testLoadHtml() throws Exception {
        Activity act = activityRule.getActivity();
        final String marker = "CEFRIUM_LOADHTML_OK";
        final CountDownLatch got = new CountDownLatch(1);

        act.runOnUiThread(() -> {
            CefriumBrowser b = CefriumBrowser.createWithSurface(act);
            b.setOnTitleChangedListener(title -> {
                if (marker.equals(title)) got.countDown();
            });
            b.loadHtml("<!doctype html><html><head><title>" + marker
                    + "</title></head><body>hello</body></html>");
        });

        Assert.assertTrue("loadHtml did not load/parse the HTML (title never arrived)",
                got.await(20, TimeUnit.SECONDS));
    }

    /** postUrl + stopLoading must not crash (smoke). */
    @Test
    public void testPostUrlAndStopSmoke() throws Exception {
        Activity act = activityRule.getActivity();
        final CountDownLatch done = new CountDownLatch(1);
        act.runOnUiThread(() -> {
            CefriumBrowser b = CefriumBrowser.createWithSurface(act);
            b.postUrl("https://cefrium.test/post",
                    "field=value".getBytes(StandardCharsets.UTF_8));
            b.stopLoading();
            done.countDown();
        });
        Assert.assertTrue(done.await(10, TimeUnit.SECONDS));
    }
}
