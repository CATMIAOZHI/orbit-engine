// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;

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
 * Windows through CefriumChromeClient: onCreateWindow gets the opener and the
 * popup on the UI thread, the page keeps window.opener (the popup posts to
 * it), and window.close() reaches onCloseWindow with the same two browsers.
 * A client that declines leaves the window to a PopupHandler set the old way
 * (what CefriumView uses), so setting a chrome client does not break it.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumPopupClientTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE = "<html><body><script>"
            + "if (location.search == '?child') {"
            + "  document.title = 'child:' + !!window.opener;"
            + "  if (window.opener) window.opener.postMessage('from-popup', '*');"
            + "  setTimeout(() => window.close(), 300);"
            + "} else {"
            + "  addEventListener('message', e => document.title = 'got:' + e.data);"
            + "  document.title = 'ready';"
            + "}</script></body></html>";

    private static final String OPEN =
            "{\"expression\":\"window.open('/?child','w');1\",\"userGesture\":true}";

    private final List<String> openerTitles = new CopyOnWriteArrayList<>();
    private final List<String> popupTitles = new CopyOnWriteArrayList<>();

    private void run(boolean offscreen, boolean viaClient) throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch got = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<CefriumBrowser> opener = new AtomicReference<>();
        AtomicReference<CefriumBrowser> window = new AtomicReference<>();
        AtomicReference<String> seen = new AtomicReference<>();
        java.util.function.Function<CefriumBrowser, Boolean> take = popup -> {
            browsers.track(popup);
            window.set(popup);
            if (!offscreen) {
                activity.addContentView(popup.getSurfaceContainer(),
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400));
            }
            popup.setOnTitleChangedListener(popupTitles::add);
            return true;
        };
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = offscreen
                        ? browsers.track(new CefriumBrowser(400, 300, 1.0f))
                        : browsers.createWithSurface(activity);
                opener.set(b);
                if (!offscreen) {
                    activity.addContentView(b.getSurfaceContainer(), new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 400));
                }
                b.setOnTitleChangedListener(t -> {
                    openerTitles.add(t);
                    if ("ready".equals(t)) ready.countDown();
                    if ("got:from-popup".equals(t)) got.countDown();
                });
                b.setChromeClient(new CefriumChromeClient() {
                    @Override
                    public boolean onCreateWindow(CefriumBrowser x, CefriumBrowser popup,
                            String url, boolean userGesture) {
                        seen.set((Looper.myLooper() == Looper.getMainLooper() ? "" : "OFF-UI ")
                                + (x == opener.get() ? "opener " : "OTHER ") + url + " "
                                + userGesture);
                        return viaClient && take.apply(popup);
                    }

                    @Override
                    public void onCloseWindow(CefriumBrowser x, CefriumBrowser popup) {
                        if (viaClient && x == opener.get() && popup == window.get()) {
                            closed.countDown();
                        }
                    }
                });
                if (!viaClient) {
                    b.setPopupHandler(new CefriumBrowser.PopupHandler() {
                        @Override public boolean onOpenPopup(String url, boolean gesture) {
                            return true;
                        }
                        @Override public boolean onCreateWindow(CefriumBrowser popup,
                                String url, boolean gesture) {
                            return take.apply(popup);
                        }
                        @Override public void onCloseWindow(CefriumBrowser popup) {
                            if (popup == window.get()) closed.countDown();
                        }
                    });
                }
                b.loadUrl(server.url());
            });
            Assert.assertTrue("the page did not load", ready.await(30, TimeUnit.SECONDS));
            activity.runOnUiThread(() -> opener.get().executeDevToolsMethod(
                    "Runtime.evaluate", OPEN, (ok, json) -> {}));
            Assert.assertTrue("the opener got nothing from its popup; opener " + openerTitles
                    + ", popup " + popupTitles + ", seen " + seen.get(),
                    got.await(20, TimeUnit.SECONDS));
            Assert.assertTrue("popup " + popupTitles, popupTitles.contains("child:true"));
            Assert.assertEquals("opener " + server.url() + "?child true", seen.get());
            Assert.assertTrue("window.close() did not reach onCloseWindow",
                    closed.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void clientTakesTheWindowOffscreen() throws Exception {
        run(true, true);
    }

    @Test
    public void clientTakesTheWindowSurface() throws Exception {
        run(false, true);
    }

    @Test
    public void declinedWindowGoesToThePopupHandler() throws Exception {
        run(true, false);
    }
}
