// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEATURE-092 D1: after close() every method throws IllegalStateException
 * (it used to do nothing, which hid the caller's bug), except close(),
 * isClosed(), getProfile(), getSurfaceContainer() and the activity's hooks;
 * and a method whose callback IS the result rejects a null callback with
 * IllegalArgumentException (it used to do nothing).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumClosedContractTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static String outcome(Runnable r) {
        try {
            r.run();
            return "ok";
        } catch (IllegalStateException e) {
            return "ISE";
        } catch (IllegalArgumentException e) {
            return "IAE";
        }
    }

    @Test
    public void closedBrowserThrows() throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f));
            List<String> r = new ArrayList<>();
            r.add("open:" + b.isClosed());
            b.close();
            r.add("closed:" + b.isClosed());
            r.add("loadUrl:" + outcome(() -> b.loadUrl("about:blank")));
            r.add("getUrl:" + outcome(b::getUrl));
            r.add("reload:" + outcome(b::reload));
            r.add("setChromeClient:" + outcome(() -> b.setChromeClient(null)));
            r.add("getProfile:" + outcome(b::getProfile));
            r.add("getSurfaceContainer:" + outcome(b::getSurfaceContainer));
            r.add("onPause:" + outcome(b::onPause));
            r.add("onActivityResult:" + outcome(() -> b.onActivityResult(0, 0, null)));
            r.add("close:" + outcome(b::close));
            result.set(String.join(" ", r));
        });
        Assert.assertEquals("open:false closed:true loadUrl:ISE getUrl:ISE reload:ISE"
                + " setChromeClient:ISE getProfile:ok getSurfaceContainer:ok onPause:ok"
                + " onActivityResult:ok close:ok", result.get());
    }

    @Test
    public void nullResultCallbacksAreRejected() throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f));
            List<String> r = new ArrayList<>();
            r.add("getCookies:" + outcome(() -> b.getCookies("http://127.0.0.1/", null)));
            r.add("printToPdf:" + outcome(() -> b.printToPdf("/x.pdf", null)));
            r.add("captureBitmap:" + outcome(() -> b.captureBitmap(null)));
            r.add("downloadImage:" + outcome(() -> b.downloadImage("http://127.0.0.1/i.png", 16,
                    null)));
            result.set(String.join(" ", r));
        });
        Assert.assertEquals("getCookies:IAE printToPdf:IAE captureBitmap:IAE downloadImage:IAE",
                result.get());
    }
}
