// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
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

    private static final int FIRST = 0xFF204060;
    private static final int SECOND = 0xFF602040;

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

    // Frames arrive asynchronously: read until the colour shows, or report the
    // last one read.
    private static void assertCenter(CefriumBrowser b, int expected, String when)
            throws Exception {
        long got = 0;
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            got = centerPixel(b);
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

    // The pixel at the center of the browser's view, read from a screenshot
    // of the composited screen (Chromium draws into SurfaceControl layers that
    // PixelCopy of the SurfaceView does not see). As a non-negative long, or -1
    // when no screenshot came back.
    private static long centerPixel(CefriumBrowser b) throws Exception {
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
        return shot.getPixel(at[0] + size[0] / 2, at[1] + size[1] / 2) & 0xFFFFFFFFL;
    }
}
