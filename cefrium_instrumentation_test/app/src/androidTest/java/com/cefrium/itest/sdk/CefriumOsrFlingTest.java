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

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A quick swipe in an off-screen browser flings: the page keeps scrolling after
 * the finger lifts, the fling ends, and the next tap reaches the page. Before,
 * the fling never advanced, so the page stopped under the finger and the next
 * tap was taken as the one that stops the fling.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumOsrFlingTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    // A field pinned to the top, over a page tall enough to fling through.
    private static final String PAGE = "<html><head>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'></head>"
            + "<body style='margin:0;height:20000px'>"
            + "<input id=i style='position:fixed;left:0;top:0;width:400px;height:100px;"
            + "border:0;margin:0;padding:0'>"
            + "<script>document.getElementById('i').onfocus=function(){"
            + "document.title='focused';};document.title='ready';</script></body></html>";

    private final AtomicReference<LocalHttpServer> server = new AtomicReference<>();

    @After
    public void closeServer() {
        LocalHttpServer s = server.get();
        if (s != null) s.close();
    }

    @Test
    public void aSwipeFlingsAndTheNextTapReachesThePage() throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<String> title = new AtomicReference<>("");
        AtomicReference<Double> scrollY = new AtomicReference<>(0.0);
        CefriumBrowser[] holder = new CefriumBrowser[1];
        LocalHttpServer s = new LocalHttpServer(PAGE);
        server.set(s);
        activity.runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(400, 600, 1.0f));
            b.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer px) {}

                @Override
                public void onScrollOffsetChanged(double x, double y) {
                    scrollY.set(y);
                }
            });
            b.setOnTitleChangedListener(title::set);
            b.loadUrl(s.url());
            holder[0] = b;
        });
        waitFor(title, "ready");
        Thread.sleep(500);
        CefriumBrowser b = holder[0];

        // 350 px up in about 80 ms.
        activity.runOnUiThread(() -> b.sendTouchDown(200f, 500f));
        for (int n = 1; n <= 8; n++) {
            Thread.sleep(10);
            final float y = 500f - n * 350f / 8;
            activity.runOnUiThread(() -> b.sendTouchMove(200f, y));
        }
        activity.runOnUiThread(() -> b.sendTouchUp(200f, 150f));

        // Let the fling run out.
        double last = -1;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            double now = scrollY.get();
            if (now == last) break;
            last = now;
        }
        Assert.assertTrue("the page stopped under the finger, scrollY=" + scrollY.get(),
                scrollY.get() > 600);

        activity.runOnUiThread(() -> b.sendTap(200f, 50f));
        waitFor(title, "focused");
    }

    private static void waitFor(AtomicReference<String> ref, String want)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (want.equals(ref.get())) return;
            Thread.sleep(100);
        }
        Assert.assertEquals(want, ref.get());
    }
}
