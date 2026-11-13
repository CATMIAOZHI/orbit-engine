// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;

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
 * Which device APIs (WebUSB, Web Bluetooth, Web Serial, WebHID) a page sees on
 * a secure origin. An engine bump that adds or drops one fails here, so the
 * chooser work (FEATURE-053) follows what the engine exposes.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumDeviceApiSurfaceTest {

    private static final String EXPECTED =
            "devices:usb=object;bluetooth=object;serial=object;hid=undefined;secure=true";

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    @Test
    public void deviceApisMatchTheEngine() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>(null);
        final boolean[] probed = {false};
        final LocalHttpServer server = new LocalHttpServer(
                "<!doctype html><title>devices</title><body>devices</body>");
        final String url = server.url();

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.setQueryHandler(CefriumBrowser.QueryOriginPolicy.anyOrigin(), (queryId, request, origin, callback) -> {
                if (request != null && request.startsWith("devices:")) {
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
                        "window.cefriumQuery({request:'devices:'"
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
        Assert.assertTrue("the page never reported its device APIs", arrived);
        Assert.assertEquals(EXPECTED, result.get());
    }
}
