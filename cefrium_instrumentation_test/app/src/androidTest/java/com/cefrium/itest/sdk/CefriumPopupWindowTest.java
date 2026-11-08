// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;
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
 * FEATURE-086: PopupHandler.onCreateWindow gives window.open a browser of its
 * own, so the page keeps window.opener (what sign-in popups post their result
 * to), and window.close() in it reaches onCloseWindow. Surface and off-screen.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumPopupWindowTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    // The opener shows what the popup posts; the popup posts if it has an
    // opener, then closes itself.
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
    private final List<String> fallbacks = new CopyOnWriteArrayList<>();

    private CefriumBrowser open(boolean offscreen, String url, CefriumBrowser.PopupHandler h)
            throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<CefriumBrowser> created = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            CefriumBrowser b = offscreen
                    ? browsers.track(new CefriumBrowser(400, 300, 1.0f))
                    : browsers.createWithSurface(activity);
            created.set(b);
            if (!offscreen) {
                activity.addContentView(b.getSurfaceContainer(), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 400));
            }
            b.setOnTitleChangedListener(t -> {
                openerTitles.add(t);
                if ("ready".equals(t)) ready.countDown();
            });
            b.setPopupHandler(h);
            b.loadUrl(url);
        });
        Assert.assertTrue("the page did not load", ready.await(30, TimeUnit.SECONDS));
        return created.get();
    }

    private void openPopup(CefriumBrowser b) {
        activityRule.getActivity().runOnUiThread(
                () -> b.executeDevToolsMethod("Runtime.evaluate", OPEN, (ok, json) -> {}));
    }

    private void keepsTheOpener(boolean offscreen) throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch got = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<CefriumBrowser> window = new AtomicReference<>();
        CefriumBrowser.PopupHandler handler = new CefriumBrowser.PopupHandler() {
            @Override public boolean onOpenPopup(String url, boolean gesture) {
                fallbacks.add(url);
                return true;
            }

            @Override public boolean onCreateWindow(CefriumBrowser popup, String url,
                    boolean gesture) {
                browsers.track(popup);
                window.set(popup);
                if (!offscreen) {
                    activity.addContentView(popup.getSurfaceContainer(),
                            new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400));
                }
                popup.setOnTitleChangedListener(popupTitles::add);
                return true;
            }

            @Override public void onCloseWindow(CefriumBrowser popup) {
                if (popup == window.get()) closed.countDown();
            }
        };
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = open(offscreen, server.url(), handler);
            CountDownLatch listening = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                b.setOnTitleChangedListener(t -> {
                    openerTitles.add(t);
                    if ("got:from-popup".equals(t)) got.countDown();
                });
                listening.countDown();
            });
            listening.await(5, TimeUnit.SECONDS);
            openPopup(b);
            Assert.assertTrue("the opener got nothing from its popup; opener " + openerTitles
                    + ", popup " + popupTitles + ", fallbacks " + fallbacks,
                    got.await(20, TimeUnit.SECONDS));
            Assert.assertTrue("popup " + popupTitles, popupTitles.contains("child:true"));
            Assert.assertTrue("fallback used: " + fallbacks, fallbacks.isEmpty());
            Assert.assertTrue("window.close() did not reach onCloseWindow",
                    closed.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void popupKeepsItsOpenerSurface() throws Exception {
        keepsTheOpener(false);
    }

    @Test
    public void popupKeepsItsOpenerOffscreen() throws Exception {
        keepsTheOpener(true);
    }

    // A declined window costs nothing: the URL goes to onOpenPopup and the
    // browser made for it is closed (BrowserRule fails on a leaked one).
    @Test
    public void declinedWindowFallsBackToOnOpenPopup() throws Exception {
        CountDownLatch fallback = new CountDownLatch(1);
        List<CefriumBrowser> offered = new CopyOnWriteArrayList<>();
        CefriumBrowser.PopupHandler handler = new CefriumBrowser.PopupHandler() {
            @Override public boolean onOpenPopup(String url, boolean gesture) {
                fallbacks.add(url);
                fallback.countDown();
                return true;
            }

            @Override public boolean onCreateWindow(CefriumBrowser popup, String url,
                    boolean gesture) {
                offered.add(popup);
                return false;
            }
        };
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = open(true, server.url(), handler);
            openPopup(b);
            Assert.assertTrue("onOpenPopup was not called", fallback.await(20, TimeUnit.SECONDS));
            Assert.assertEquals(1, offered.size());
            Assert.assertTrue(fallbacks.get(0), fallbacks.get(0).endsWith("/?child"));
        }
    }
}
