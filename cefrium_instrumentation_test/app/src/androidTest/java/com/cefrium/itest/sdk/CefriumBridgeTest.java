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
 * Instrumented tests for the FEATURE-023 JavaScript &lt;-&gt; native bridge
 * (window.cefriumQuery / QueryHandler) and evaluateJavaScript.
 *
 * Run on a real device or emulator with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4.class)
public class CefriumBridgeTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    /**
     * Full round trip: JS calls window.cefriumQuery, the native QueryHandler
     * responds, JS onSuccess receives the response and re-queries with it.
     * The second request proves native -> JS delivery; it also exercises
     * evaluateJavaScript (used to inject the probe).
     */
    @Test
    public void testQueryRoundTrip() throws Exception {
        Activity activity = activityRule.getActivity();
        final CopyOnWriteArrayList<String> requests = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> origins = new CopyOnWriteArrayList<>();
        final CountDownLatch twoQueries = new CountDownLatch(2);
        final boolean[] probed = {false};

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            browser.setQueryHandler((queryId, request, origin, callback) -> {
                requests.add(request);
                origins.add(origin);
                callback.success("pong:" + request);
                twoQueries.countDown();
                return true;
            });
            browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    // The V8 context (with window.cefriumQuery injected) exists
                    // by load-complete.
                    browser.evaluateJavaScript(
                        "window.cefriumQuery({request:'ping',"
                        + "onSuccess:function(r){window.cefriumQuery({request:'echoed:'+r});},"
                        + "onFailure:function(c,m){}});");
                }
            });
            browser.loadUrl("about:blank");
        });

        Assert.assertTrue("Bridge round trip timed out",
                twoQueries.await(20, TimeUnit.SECONDS));
        Assert.assertTrue("native should have received the 'ping' request",
                requests.contains("ping"));
        Assert.assertTrue(
                "JS onSuccess should have received the native response 'pong:ping'",
                requests.contains("echoed:pong:ping"));
        Assert.assertFalse("the calling frame origin should be passed to the handler",
                origins.isEmpty());
    }

    /**
     * An unhandled query (handler returns false) fails on the JS side: the
     * page's onFailure runs. We relay that back through a second query so the
     * test can assert it deterministically (no reliance on logcat).
     */
    @Test
    public void testUnhandledQueryFails() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch onFailureFired = new CountDownLatch(1);
        final boolean[] probed = {false};

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            browser.setQueryHandler((queryId, request, origin, callback) -> {
                if (request.equals("probe")) {
                    return false;  // refuse -> the page gets onFailure(-1)
                }
                if (request.equals("failed")) {
                    onFailureFired.countDown();
                    callback.success("ok");
                    return true;
                }
                return false;
            });
            browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    browser.evaluateJavaScript(
                        "window.cefriumQuery({request:'probe',"
                        + "onSuccess:function(r){},"
                        + "onFailure:function(c,m){"
                        + "window.cefriumQuery({request:'failed'});}});");
                }
            });
            browser.loadUrl("about:blank");
        });

        Assert.assertTrue("an unhandled query should trigger the page's onFailure",
                onFailureFired.await(15, TimeUnit.SECONDS));
    }
}
