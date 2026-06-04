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
 * Instrumented tests for FEATURE-025 dynamic WebSettings
 * (setUserAgentOverride / setJavaScriptEnabled / setZoomLevel).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumSettingsTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    /**
     * Runtime UA override: set it, load a page, then read navigator.userAgent
     * from page JS via the bridge and assert it reflects the override.
     */
    @Test
    public void testUserAgentOverride() throws Exception {
        Activity act = activityRule.getActivity();
        final CopyOnWriteArrayList<String> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch got = new CountDownLatch(1);
        final boolean[] probed = {false};

        act.runOnUiThread(() -> {
            CefriumBrowser b = CefriumBrowser.createWithSurface(act);
            b.setQueryHandler((id, request, origin, cb) -> {
                requests.add(request);
                cb.success("ok");
                got.countDown();
                return true;
            });
            b.setUserAgentOverride("CefriumTest/9.9");
            b.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    b.evaluateJavaScript(
                        "window.cefriumQuery({request: navigator.userAgent});");
                }
            });
            b.loadUrl("about:blank");
        });

        Assert.assertTrue("UA probe timed out", got.await(20, TimeUnit.SECONDS));
        boolean overridden = false;
        for (String r : requests) {
            if (r != null && r.contains("CefriumTest/9.9")) overridden = true;
        }
        Assert.assertTrue("navigator.userAgent did not reflect the override; got: "
                + requests, overridden);
    }

    /** setZoomLevel + setJavaScriptEnabled must not crash (smoke). */
    @Test
    public void testZoomAndJavaScriptToggleSmoke() throws Exception {
        Activity act = activityRule.getActivity();
        final CountDownLatch done = new CountDownLatch(1);
        act.runOnUiThread(() -> {
            CefriumBrowser b = CefriumBrowser.createWithSurface(act);
            b.loadUrl("about:blank");
            b.setZoomLevel(2.0);
            b.setZoomLevel(0.0);
            b.setJavaScriptEnabled(false);
            b.setJavaScriptEnabled(true);
            done.countDown();
        });
        Assert.assertTrue(done.await(10, TimeUnit.SECONDS));
    }
}
