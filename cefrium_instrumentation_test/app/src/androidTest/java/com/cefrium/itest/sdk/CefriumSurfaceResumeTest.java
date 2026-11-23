// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalMediaServer;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.Intent;
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

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A surface browser draws again after the app goes to the background and
 * comes back, with or without a video playing. The page then changes colour,
 * so the check is a live frame, not the last one before leaving. Read from a
 * screenshot of the screen (the SurfaceView is in it).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumSurfaceResumeTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final int GREEN = 0xFF00A000;
    private static final int BLUE = 0xFF0000C0;

    private static String page(boolean video) {
        return "<!doctype html><html><head>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'></head>"
                + "<body style='margin:0;background:#00a000;height:100vh'>"
                + (video ? "<video id=v autoplay muted loop playsinline width=160 height=120"
                        + " src='/h264.mp4' onplaying=\"document.title='playing'\"></video>"
                        : "")
                + "<script>" + (video ? "" : "document.title='ready';") + "</script>"
                + "</body></html>";
    }

    @Test
    public void drawsAfterBackground() throws Exception {
        backgroundAndBack(false);
    }

    @Test
    public void drawsAfterBackgroundWithVideo() throws Exception {
        backgroundAndBack(true);
    }

    private void backgroundAndBack(boolean video) throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        String want = video ? "playing" : "ready";
        try (LocalMediaServer server = new LocalMediaServer(page(video))) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activity);
                browser.set(b);
                activity.addContentView(b.getSurfaceContainer(), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                b.setOnTitleChangedListener(t -> {
                    if (want.equals(t)) loaded.countDown();
                });
                b.loadUrl(server.url());
            });
            Assert.assertTrue("the page did not load", loaded.await(30, TimeUnit.SECONDS));
            assertAt(browser.get(), GREEN, "before the background");

            // To the background, as an app forwards its lifecycle.
            activity.runOnUiThread(() -> browser.get().onPause());
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            long end = System.currentTimeMillis() + 10_000;
            while (activity.hasWindowFocus() && System.currentTimeMillis() < end) {
                Thread.sleep(100);
            }
            Thread.sleep(3000);

            // And back.
            Intent back = new Intent(activity, HostActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            InstrumentationRegistry.getInstrumentation().getTargetContext().startActivity(back);
            end = System.currentTimeMillis() + 15_000;
            while (!activity.hasWindowFocus() && System.currentTimeMillis() < end) {
                Thread.sleep(100);
            }
            Assert.assertTrue("the activity did not come back", activity.hasWindowFocus());
            activity.runOnUiThread(() -> browser.get().onResume());

            assertAt(browser.get(), GREEN, "after coming back");
            activity.runOnUiThread(() -> browser.get().evaluateJavaScript(
                    "document.body.style.background='#0000c0';"));
            assertAt(browser.get(), BLUE, "a change after coming back (a live frame)");
        }
    }

    private static boolean close(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > 24) return false;
        }
        return true;
    }

    private static void assertAt(CefriumBrowser b, int expected, String when)
            throws Exception {
        long got = 0;
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            got = pixelAt(b);
            if (got >= 0 && close((int) got, expected)) return;
            Thread.sleep(200);
        }
        Assert.fail(got < 0
                ? when + ": no screenshot"
                : String.format("%s: pixel %08x, want %08x", when, got, expected));
    }

    // Below the video, in the page's own background.
    private static long pixelAt(CefriumBrowser b) throws Exception {
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
        return shot.getPixel(at[0] + size[0] / 2, at[1] + (int) (size[1] * 0.7f))
                & 0xFFFFFFFFL;
    }
}
