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

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Instrumented test for FEATURE-027 request interceptor
 * (CefriumBrowser.RequestInterceptor / CefResourceRequestHandler).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumInterceptorTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    /**
     * The interceptor must be consulted for subresource requests. We load an
     * HTML page that references a distinctive image URL and assert the
     * interceptor was asked about it (and exercised the block path).
     */
    @Test
    public void testInterceptorSeesRequests() throws Exception {
        Activity act = activityRule.getActivity();
        final CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
        final CountDownLatch created = new CountDownLatch(1);

        act.runOnUiThread(() -> {
            CefriumBrowser b = CefriumBrowser.createWithSurface(act);
            b.setRequestInterceptor((url, method, isMainFrame) -> {
                seen.add(url);
                return url.contains("INTERCEPT");  // block the marker URL
            });
            b.loadHtml("<!doctype html><html><body>"
                    + "<img src=\"https://cefrium.test/pixel-INTERCEPT.png\">"
                    + "</body></html>");
            created.countDown();
        });
        Assert.assertTrue(created.await(10, TimeUnit.SECONDS));

        // Poll until the interceptor has been asked about the marker subresource.
        long deadline = System.currentTimeMillis() + 20000;
        boolean found = false;
        while (System.currentTimeMillis() < deadline && !found) {
            for (String u : seen) {
                if (u.contains("pixel-INTERCEPT")) { found = true; break; }
            }
            if (!found) Thread.sleep(200);
        }
        Assert.assertTrue("interceptor was never consulted for the subresource; "
                + "saw: " + seen, found);
    }
}
