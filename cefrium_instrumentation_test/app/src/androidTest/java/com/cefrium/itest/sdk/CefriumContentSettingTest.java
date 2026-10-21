package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.itest.LocalHttpServer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEATURE-037: per-origin content settings via
 * {@link CefriumBrowser#setContentSetting}. Pre-granting notifications for an
 * origin must make {@code Notification.permission === "granted"} on that origin
 * WITHOUT a prompt — the mechanism a consumer uses so a service worker's
 * {@code showNotification()} works for its own pages. The complementary case:
 * blocking yields {@code "denied"}.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumContentSettingTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    // Distinct secure origins: notification decisions persist per-origin in the
    // shared profile, so the allow and block probes must not share an origin.
    // Distinct secure origins per test: notification decisions persist per-origin
    // in the shared profile, so each probe starts its own loopback server -- a
    // fresh 127.0.0.1:<port> secure origin -- with no external site.

    @Test
    public void testPreGrantMakesNotificationPermissionGranted() throws Exception {
        String perm = probeNotificationPermission(CefriumBrowser.SETTING_ALLOW);
        Assert.assertEquals("pre-granted origin should report granted", "granted", perm);
    }

    @Test
    public void testPreBlockMakesNotificationPermissionDenied() throws Exception {
        String perm = probeNotificationPermission(CefriumBrowser.SETTING_BLOCK);
        Assert.assertEquals("pre-blocked origin should report denied", "denied", perm);
    }

    /**
     * Applies [value] to PERMISSION_NOTIFICATIONS for [url]'s origin, loads the
     * page, and reads back Notification.permission via the JS bridge.
     */
    private String probeNotificationPermission(int value) throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>(null);
        final boolean[] probed = {false};
        final LocalHttpServer server = new LocalHttpServer(
                "<!doctype html><title>n</title><body>n</body>");
        final String origin = server.origin();
        final String url = server.url();

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            // Pre-set the per-origin notification content setting (no prompt).
            browser.setContentSetting(origin, CefriumBrowser.PERMISSION_NOTIFICATIONS, value);
            browser.setQueryHandler(CefriumBrowser.QueryOriginPolicy.anyOrigin(), (queryId, request, reqOrigin, callback) -> {
                if (request != null && request.startsWith("perm:")) {
                    result.set(request.substring("perm:".length()));
                    callback.success("ack");
                    got.countDown();
                    return true;
                }
                return false;
            });
            browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    // Poll for the Notification API: on 149 the first
                    // loading=false can fire before the page's JS context is
                    // fully established, so probing once races. Retry up to ~3s.
                    // If setContentSetting did not grant, permission reports
                    // 'default' (still a failure) -- this fixes only the timing,
                    // it does not mask a real content-setting regression.
                    browser.evaluateJavaScript(
                        "(function(){var n=0;var iv=setInterval(function(){n++;"
                        + "if(typeof Notification!=='undefined'){clearInterval(iv);"
                        + "window.cefriumQuery({request:'perm:'+Notification.permission});}"
                        + "else if(n>=15){clearInterval(iv);"
                        + "window.cefriumQuery({request:'perm:undefined'});}},200);})();");
                }
            });
            browser.loadUrl(url);
        });

        boolean arrived = got.await(40, TimeUnit.SECONDS);
        server.close();
        Assert.assertTrue("Notification.permission probe never returned (hung/crashed)", arrived);
        return result.get();
    }
}
