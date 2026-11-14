// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Regression guard for the mouse-injection API (CefriumBrowser.sendMouseMove /
 * sendLeftClick / sendRightClick). Exercises a WINDOWED (createWithSurface /
 * ContentViewRenderView) browser -- the mode the flagship browser uses -- where
 * the events must be forwarded through RenderWidgetHostViewAndroid. This broke
 * silently before the CefBrowserPlatformDelegateNativeAndroid SendMouse* override
 * (the base methods were NOTIMPLEMENTED), so keep this green.
 *
 * Hermetic: a loopback LocalHttpServer serves a page that reports (via the JS
 * bridge) once it has seen mousemove + click + contextmenu.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumMouseTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE =
            "<!doctype html><html><head>"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "</head><body style=\"margin:0;width:100vw;height:100vh;background:#eee\">"
            + "<div style=\"width:100%;height:100%\"></div><script>"
            + "var g={m:0,c:0,x:0},sent=false;"
            + "function chk(){if(!sent&&g.m&&g.c&&g.x){sent=true;"
            + "window.cefriumQuery({request:'mouseok'});}}"
            + "document.addEventListener('mousemove',function(){g.m=1;chk();});"
            + "document.addEventListener('click',function(){g.c=1;chk();});"
            + "document.addEventListener('contextmenu',function(e){e.preventDefault();g.x=1;chk();});"
            + "</script></body></html>";

    @Test
    public void mouseEventsReachWindowedRenderer() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch got = new CountDownLatch(1);
        final boolean[] injected = {false};
        final LocalHttpServer server = new LocalHttpServer(PAGE);
        final Handler main = new Handler(Looper.getMainLooper());

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            // Attach the rendering surface so the windowed compositor presents
            // frames -- windowed mouse input requires a shown, sized view.
            activity.setContentView(browser.getSurfaceContainer());
            browser.setQueryHandler(CefriumBrowser.QueryOriginPolicy.anyOrigin(), (queryId, request, origin, callback) -> {
                if (request != null && request.contains("mouseok")) {
                    callback.success("ack");
                    got.countDown();
                    return true;
                }
                return false;
            });
            browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !injected[0]) {
                    injected[0] = true;
                    // Give the windowed compositor a moment to present a frame, then
                    // inject at a point inside the (full-viewport) page.
                    main.postDelayed(() -> { if (!browser.isClosed()) browser.sendMouseMove(150, 300); }, 600);
                    main.postDelayed(() -> { if (!browser.isClosed()) browser.sendLeftClick(150, 300); }, 1100);
                    main.postDelayed(() -> { if (!browser.isClosed()) browser.sendRightClick(150, 300); }, 1600);
                }
            });
            browser.loadUrl(server.url());
        });

        boolean ok = got.await(30, TimeUnit.SECONDS);
        server.close();
        Assert.assertTrue(
                "windowed browser never reported mousemove+click+contextmenu from the"
                        + " injected mouse API", ok);
    }
}
