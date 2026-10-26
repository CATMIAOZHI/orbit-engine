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
import com.cefrium.CefriumRenderHandler;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * setSize on an off-screen browser: the page sees the new viewport and a
 * resize event, and onPaint frames come at the new size. Before setSize
 * existed an OSR browser kept its constructor size for its whole life.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumOsrResizeTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE = "<html><body><script>var resized=0;"
            + "function show(){document.title='inner:'+innerWidth+'x'+innerHeight+' resized:'+resized;}"
            + "onresize=function(){resized++;show();};show();</script></body></html>";

    private CefriumBrowser open(LocalHttpServer server, AtomicReference<String> title,
            AtomicReference<String> paint, Runnable beforeLoad) {
        Activity activity = activityRule.getActivity();
        CefriumBrowser[] holder = new CefriumBrowser[1];
        activity.runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(400, 300, 1.0f));
            b.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer px) {
                    paint.set(width + "x" + height);
                }
            });
            b.setOnTitleChangedListener(title::set);
            holder[0] = b;
            if (beforeLoad != null) {
                b.setSize(320, 200);
            }
            b.loadUrl(server.url());
        });
        return waitFor(() -> holder[0]);
    }

    @Test
    public void resizeAfterLoad() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        AtomicReference<String> paint = new AtomicReference<>("");
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = open(server, title, paint, null);
            waitForValue(title, "inner:400x300 resized:0");
            waitForValue(paint, "400x300");
            activityRule.getActivity().runOnUiThread(() -> b.setSize(300, 500));
            waitForValue(title, "inner:300x500 resized:1");
            waitForValue(paint, "300x500");
        }
    }

    @Test
    public void sizeSetBeforeTheFirstLoad() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        AtomicReference<String> paint = new AtomicReference<>("");
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            open(server, title, paint, () -> {});
            waitForValue(title, "inner:320x200 resized:0");
            waitForValue(paint, "320x200");
        }
    }

    private static <T> T waitFor(java.util.function.Supplier<T> s) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            T v = s.get();
            if (v != null) return v;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("browser not created");
    }

    private static void waitForValue(AtomicReference<String> ref, String want)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (want.equals(ref.get())) return;
            Thread.sleep(100);
        }
        Assert.assertEquals(want, ref.get());
    }
}
