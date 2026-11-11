// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Issue #20: a tap on an out-of-process iframe reaches the iframe, as Google's
 * sign-in button (an accounts.google.com iframe, always isolated) needs. The
 * test app isolates http://127.0.0.1 (TestHostApplication), so the 127.0.0.1
 * iframe in a localhost page gets a process of its own; the test checks that
 * first, or it would pass on an in-process iframe.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumOopifInputTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    // The page (localhost) embeds itself from 127.0.0.1; the frame reports
    // when it is ready and when a pointer goes down in it.
    private static final String PAGE = "<!doctype html><html><head>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'></head>"
            + "<body style='margin:0'><script>"
            + "if (location.hostname == '127.0.0.1') {"
            + "  document.body.style.cssText = 'margin:0;height:100vh;background:#cde';"
            + "  addEventListener('pointerdown', () => parent.postMessage('frame-down', '*'));"
            + "  parent.postMessage('frame-ready', '*');"
            + "} else {"
            + "  addEventListener('message', e => document.title = 'got:' + e.data);"
            + "  let n = 0;"
            + "  addEventListener('pointerdown', e =>"
            + "    document.title = 'page-down:' + (++n) + ':' + e.target.tagName);"
            + "  const f = document.createElement('iframe');"
            + "  f.src = 'http://127.0.0.1:' + location.port + '/';"
            + "  f.style.cssText = 'border:0;width:300px;height:200px;display:block';"
            + "  document.body.appendChild(f);"
            + "}</script></body></html>";

    private final List<String> titles = new CopyOnWriteArrayList<>();

    private void tapReachesTheIframe(boolean offscreen) throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch frameReady = new CountDownLatch(1);
        CountDownLatch frameDown = new CountDownLatch(1);
        CountDownLatch oopif = new CountDownLatch(1);
        CountDownLatch pageDown = new CountDownLatch(1);
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = offscreen
                        ? browsers.track(new CefriumBrowser(400, 300, 1.0f))
                        : browsers.createWithSurface(activity);
                browser.set(b);
                if (!offscreen) {
                    activity.addContentView(b.getSurfaceContainer(), new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
                // DevTools attaches an "iframe" target only to a frame in
                // another process.
                b.setDevToolsEventListener((method, params) -> {
                    if (method.equals("Target.attachedToTarget")
                            && params.contains("\"type\":\"iframe\"")) {
                        oopif.countDown();
                    }
                });
                b.executeDevToolsMethod("Target.setAutoAttach", "{\"autoAttach\":true,"
                        + "\"waitForDebuggerOnStart\":false,\"flatten\":false}", null);
                b.setOnTitleChangedListener(t -> {
                    titles.add(t);
                    if ("got:frame-ready".equals(t)) frameReady.countDown();
                    if ("got:frame-down".equals(t)) frameDown.countDown();
                    if (t.startsWith("page-down:")) pageDown.countDown();
                });
                b.loadUrl(server.localhostUrl());
            });
            Assert.assertTrue("the iframe did not load; titles " + titles,
                    frameReady.await(30, TimeUnit.SECONDS));

            Assert.assertTrue("the iframe is not out of process, so this test proves "
                    + "nothing", oopif.await(5, TimeUnit.SECONDS));

            // The instrument first: a tap below the iframe must reach the page.
            // (near the bottom of the view; the iframe is the top 200 CSS px).
            tap(browser.get(), 100, offscreen ? 260 : -100);
            Assert.assertTrue("a tap on the page itself did not arrive; titles " + titles,
                    pageDown.await(10, TimeUnit.SECONDS));
            // Past the first tap's up and the double-tap window, or the second
            // tap is read as part of the first gesture.
            Thread.sleep(1000);
            tap(browser.get(), 100, 100);
            Assert.assertTrue("the tap did not reach the out-of-process iframe; titles "
                    + titles, frameDown.await(10, TimeUnit.SECONDS));
        }
    }

    // A finger held for a moment, as a person taps: down and up in one task
    // give no click off-screen. A negative y counts from the bottom of a
    // surface browser's view.
    private static void tap(CefriumBrowser b, float x, float y) {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            float ty = y < 0 ? b.getSurfaceContainer().getHeight() + y : y;
            b.sendTouchDown(x, ty);
            main.postDelayed(() -> b.sendTouchUp(x, ty), 150);
        });
    }

    @Test
    public void tapReachesOutOfProcessIframeSurface() throws Exception {
        tapReachesTheIframe(false);
    }

    @Test
    public void tapReachesOutOfProcessIframeOffscreen() throws Exception {
        tapReachesTheIframe(true);
    }
}
