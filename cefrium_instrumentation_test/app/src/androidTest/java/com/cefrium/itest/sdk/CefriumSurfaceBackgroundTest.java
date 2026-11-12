// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A surface browser's setBackgroundColor: the colour shows where the page has
 * no background, it survives a navigation to another process (the
 * 127.0.0.1 page, isolated by the test app, gets a new renderer and view), and
 * it can change on a loaded page. Read from a screenshot of the screen.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumSurfaceBackgroundTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    // No background of its own; the title says which host loaded.
    private static final String PAGE = "<!doctype html><html><head>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'></head>"
            + "<body><script>document.title = 'at:' + location.hostname;</script>"
            + "</body></html>";

    // Transparent page with an opaque red square at the top left.
    private static final String SQUARE = "<!doctype html><html><head>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'></head>"
            + "<body style='margin:0'><div style='width:80px;height:80px;background:#f00'>"
            + "</div><script>document.title = 'ready';</script></body></html>";

    private static final int GAME = 0xFF00A000;
    private static final int RED = 0xFFFF0000;
    private static final int FIRST = 0xFF204060;
    private static final int SECOND = 0xFF602040;

    // The screenshots must show this activity. A 32-bit-only app (the
    // armeabi-v7a run) gets the system's DeprecatedAbiDialog over it at every
    // launch: dismiss it, and refuse to read pixels under any other window.
    @Before
    public void activityInFront() throws Exception {
        Activity activity = activityRule.getActivity();
        UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        String covering = null;
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            if (activity.hasWindowFocus()) return;
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            if (root != null && !activity.getPackageName().equals(root.getPackageName())) {
                covering = String.valueOf(root.getPackageName());
                if ("android".equals(covering)) {
                    for (AccessibilityNodeInfo ok :
                            root.findAccessibilityNodeInfosByViewId("android:id/button1")) {
                        ok.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            }
            Thread.sleep(200);
        }
        Assert.fail("the test activity never got the focus; in front: " + covering);
    }

    @Test
    public void backgroundColorShowsAndSurvivesNavigation() throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        AtomicReference<CountDownLatch> loaded = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<String> want = new AtomicReference<>("at:localhost");
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activity);
                browser.set(b);
                activity.addContentView(b.getSurfaceContainer(), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                b.setBackgroundColor(FIRST);
                b.setOnTitleChangedListener(t -> {
                    if (t.equals(want.get())) loaded.get().countDown();
                });
                b.loadUrl(server.localhostUrl());
            });
            Assert.assertTrue("the page did not load", loaded.get().await(30, TimeUnit.SECONDS));
            assertCenter(browser.get(), FIRST, "after the first load");

            // Another process: a new renderer and a new view.
            want.set("at:127.0.0.1");
            loaded.set(new CountDownLatch(1));
            activity.runOnUiThread(() -> browser.get().loadUrl(server.url()));
            Assert.assertTrue("the 127.0.0.1 page did not load",
                    loaded.get().await(30, TimeUnit.SECONDS));
            assertCenter(browser.get(), FIRST, "after a cross-process navigation");

            activity.runOnUiThread(() -> browser.get().setBackgroundColor(SECOND));
            assertCenter(browser.get(), SECOND, "after a change on a loaded page");

            AtomicReference<Throwable> thrown = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    browser.get().setBackgroundColor(0x80FFFFFF);
                } catch (Throwable t) {
                    thrown.set(t);
                }
                done.countDown();
            });
            done.await(5, TimeUnit.SECONDS);
            Assert.assertTrue("a translucent colour must be refused, got " + thrown.get(),
                    thrown.get() instanceof IllegalArgumentException);
        }
    }

    /**
     * A transparent surface browser over a game's own SurfaceView: the game
     * shows where the page draws nothing, the page's own content on top. Then
     * back to opaque.
     */
    @Test
    public void transparentBackgroundShowsTheSurfaceBelow() throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        CountDownLatch gameDrawn = new CountDownLatch(1);
        CountDownLatch loaded = new CountDownLatch(1);
        try (LocalHttpServer server = new LocalHttpServer(SQUARE)) {
            activity.runOnUiThread(() -> {
                android.view.SurfaceView game = new android.view.SurfaceView(activity);
                game.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                    @Override public void surfaceCreated(android.view.SurfaceHolder h) {
                        android.graphics.Canvas c = h.lockCanvas();
                        c.drawColor(GAME);
                        h.unlockCanvasAndPost(c);
                        gameDrawn.countDown();
                    }
                    @Override public void surfaceChanged(
                            android.view.SurfaceHolder h, int f, int w, int hh) {}
                    @Override public void surfaceDestroyed(android.view.SurfaceHolder h) {}
                });
                activity.addContentView(game, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                CefriumBrowser b = browsers.createWithSurface(activity);
                browser.set(b);
                b.setBackgroundColor(0x00000000);
                activity.addContentView(b.getSurfaceContainer(), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                b.setOnTitleChangedListener(t -> {
                    if ("ready".equals(t)) loaded.countDown();
                });
                b.loadUrl(server.url());
            });
            Assert.assertTrue("the game surface was not drawn",
                    gameDrawn.await(10, TimeUnit.SECONDS));
            Assert.assertTrue("the page did not load", loaded.await(30, TimeUnit.SECONDS));
            // The square first: the page has painted. Before that the empty
            // compositor shows the game whatever the page background is.
            assertAt(browser.get(), -40, -40, RED, "transparent, the page's own square");
            assertStable(browser.get(), 0.5f, 0.5f, GAME, "transparent, the game below");

            activity.runOnUiThread(() -> browser.get().setBackgroundColor(FIRST));
            assertAt(browser.get(), 0.5f, 0.5f, FIRST, "back to opaque");
        }
    }

    /**
     * The colour shows from the moment the view is on screen, not only once the
     * page has drawn: no white frame first. Screenshots run from before the
     * view is attached until the page has loaded.
     */
    @Test
    public void noWhiteBeforeTheFirstFrame() throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch attached = new CountDownLatch(1);
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            // The host's own views in the same colour, so only the browser can
            // show another.
            CountDownLatch painted = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                paintAll(activity.getWindow().getDecorView(), FIRST);
                painted.countDown();
            });
            Assert.assertTrue(painted.await(5, TimeUnit.SECONDS));
            AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activity);
                browser.set(b);
                b.setBackgroundColor(FIRST);
                b.setOnTitleChangedListener(t -> {
                    if (t.startsWith("at:")) loaded.countDown();
                });
                activity.addContentView(b.getSurfaceContainer(), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                attached.countDown();
                b.loadUrl(server.localhostUrl());
            });
            Assert.assertTrue(attached.await(10, TimeUnit.SECONDS));
            int shots = 0;
            long until = Long.MAX_VALUE;
            while (System.currentTimeMillis() < until) {
                long got = pixelAt(browser.get(), 0.5f, 0.5f);
                shots++;
                if (got >= 0 && !close((int) got, FIRST)) {
                    Assert.fail(String.format("shot %d: pixel %08x, want %08x", shots, got, FIRST));
                }
                if (until == Long.MAX_VALUE && loaded.getCount() == 0) {
                    until = System.currentTimeMillis() + 1000;
                }
                Assert.assertTrue("the page did not load", shots < 600);
            }
        }
    }

    private static void paintAll(View v, int color) {
        v.setBackgroundColor(color);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) paintAll(g.getChildAt(i), color);
        }
    }

    // Frames arrive asynchronously: read until the colour shows, or report the
    // last one read.
    private static void assertCenter(CefriumBrowser b, int expected, String when)
            throws Exception {
        assertAt(b, 0.5f, 0.5f, expected, when);
    }

    // The colour holds for a second, read five times: not a frame in passing.
    private static void assertStable(CefriumBrowser b, float x, float y, int expected,
            String when) throws Exception {
        for (int i = 0; i < 5; i++) {
            long got = pixelAt(b, x, y);
            if (got < 0 || !close((int) got, expected)) {
                Assert.fail(got < 0 ? when + ": no screenshot"
                        : String.format("%s: pixel %08x, want %08x (read %d)",
                                when, got, expected, i));
            }
            Thread.sleep(200);
        }
    }

    // A point of the browser's view: fractions of its size when in [0, 1],
    // or minus a distance in pixels from its top left corner (-40 = 40 px in).
    private static void assertAt(CefriumBrowser b, float x, float y, int expected,
            String when) throws Exception {
        long got = 0;
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            got = pixelAt(b, x, y);
            if (got >= 0 && close((int) got, expected)) return;
            Thread.sleep(200);
        }
        Assert.fail(got < 0
                ? when + ": no screenshot"
                : String.format("%s: center pixel %08x, want %08x", when, got, expected));
    }

    private static boolean close(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > 4) return false;
        }
        return true;
    }

    // A pixel of the browser's view (see assertAt), read from a screenshot
    // of the composited screen (Chromium draws into SurfaceControl layers that
    // PixelCopy of the SurfaceView does not see). As a non-negative long, or -1
    // when no screenshot came back.
    private static long pixelAt(CefriumBrowser b, float x, float y) throws Exception {
        int[] at = new int[2];
        int[] size = new int[2];
        CountDownLatch measured = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            View v = b.getSurfaceContainer();
            v.getLocationOnScreen(at);
            size[0] = v.getWidth();
            size[1] = v.getHeight();
            measured.countDown();
        });
        Assert.assertTrue(measured.await(5, TimeUnit.SECONDS));
        Bitmap shot = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .takeScreenshot();
        if (shot == null) return -1;
        int px = x < 0 ? (int) -x : (int) (size[0] * x);
        int py = y < 0 ? (int) -y : (int) (size[1] * y);
        return shot.getPixel(at[0] + px, at[1] + py) & 0xFFFFFFFFL;
    }
}
