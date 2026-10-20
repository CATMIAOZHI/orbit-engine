package com.cefrium.itest.sdk;

import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scoping probe for FEATURE-053 (frontier device APIs): reports which of WebUSB,
 * Web Bluetooth and Web Serial Chromium-Android actually exposes inside Cefrium,
 * on a secure (loopback) origin. Not a pass/fail gate -- it logs the result so we
 * know what chooser delegates are even worth wiring (Web Serial is desktop-only in
 * Chrome, so navigator.serial is expected to be undefined on Android).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumFrontierApiProbeTest {

    private static final String TAG = "FrontierProbe";

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Test
    public void probeFrontierApiSurface() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>(null);
        final boolean[] probed = {false};
        final LocalHttpServer server = new LocalHttpServer(
                "<!doctype html><title>frontier</title><body>probe</body>");
        final String url = server.url();

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            browser.setQueryHandler(CefriumBrowser.QueryOriginPolicy.anyOrigin(), (queryId, request, origin, callback) -> {
                if (request != null && request.startsWith("frontier:")) {
                    result.set(request);
                    callback.success("ack");
                    got.countDown();
                    return true;
                }
                return false;
            });
            browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    browser.evaluateJavaScript(
                        "window.cefriumQuery({request:'frontier:'"
                        + "+'usb='+(typeof navigator.usb)"
                        + "+';bluetooth='+(typeof navigator.bluetooth)"
                        + "+';serial='+(typeof navigator.serial)"
                        + "+';hid='+(typeof navigator.hid)"
                        + "+';secure='+window.isSecureContext});");
                }
            });
            browser.loadUrl(url);
        });

        boolean arrived = got.await(40, TimeUnit.SECONDS);
        server.close();
        Log.i(TAG, "FRONTIER_API_SURFACE " + result.get());
        Assert.assertTrue("frontier API probe never returned", arrived);
        // Always report; this is a scoping probe, not a capability gate.
        Assert.assertNotNull(result.get());
    }
}
