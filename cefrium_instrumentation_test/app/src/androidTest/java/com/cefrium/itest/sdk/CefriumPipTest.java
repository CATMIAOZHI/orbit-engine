// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.LocalMediaServer;
import com.cefrium.itest.PipHostActivity;

import android.app.PictureInPictureParams;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Picture-in-picture: the page stays visible (and its video plays) while the
 * activity is paused in the PiP window, whether the app forwards
 * Activity.onPause() or onStop() to the browser, and it draws live frames
 * after the app leaves PiP.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumPipTest {

    @Rule(order = 0)
    public ActivityTestRule<PipHostActivity> activityRule =
            new ActivityTestRule<>(PipHostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final int BLUE = 0xFF0000C0;

    // The title reports visibility and whether the video advances.
    private static final String PAGE = "<!doctype html><html><head>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'></head>"
            + "<body style='margin:0;background:#00a000;height:100vh'>"
            + "<video id=v autoplay muted loop playsinline width=160 height=120"
            + " src='/h264.mp4'></video><script>"
            + "let t0 = 0; setInterval(() => { const v = document.getElementById('v');"
            + " document.title = document.visibilityState + ':'"
            + " + (v.currentTime > t0 ? 'moving' : 'still'); t0 = v.currentTime; }, 500);"
            + "</script></body></html>";

    @Test
    public void visibleInPipWhenTheAppForwardsOnPause() throws Exception {
        pip(true);
    }

    @Test
    public void visibleInPipWhenTheAppForwardsOnStop() throws Exception {
        pip(false);
    }

    /** Closing the PiP window stops the activity: the deferred pause runs then. */
    @Test
    public void closingPipPauses() throws Exception {
        pip(true, true);
    }

    private void pip(boolean forwardOnPause) throws Exception {
        pip(forwardOnPause, false);
    }

    private void pip(boolean forwardOnPause, boolean closePip) throws Exception {
        PipHostActivity activity = activityRule.getActivity();
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        List<String> titles = new CopyOnWriteArrayList<>();
        try (LocalMediaServer server = new LocalMediaServer(PAGE)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activity);
                browser.set(b);
                activity.getContainer().addView(b.getSurfaceContainer(),
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                b.setOnTitleChangedListener(titles::add);
                activity.setLifecycle(event -> {
                    if (forwardOnPause ? event.equals("pause") : event.equals("stop")) {
                        b.onPause();
                    } else if (forwardOnPause ? event.equals("resume") : event.equals("start")) {
                        b.onResume();
                    }
                    return kotlin.Unit.INSTANCE;
                });
                b.loadUrl(server.url());
            });
            await(titles, "visible:moving", "the video did not play before PiP");

            activity.runOnUiThread(() -> activity.enterPictureInPictureMode(
                    new PictureInPictureParams.Builder()
                            .setAspectRatio(new Rational(16, 9)).build()));
            long end = System.currentTimeMillis() + 10_000;
            while (!activity.isInPictureInPictureMode() && System.currentTimeMillis() < end) {
                Thread.sleep(100);
            }
            Assert.assertTrue("did not enter PiP", activity.isInPictureInPictureMode());
            Thread.sleep(1500);
            titles.clear();
            await(titles, "visible:moving", "in PiP the page must stay visible and play");

            if (closePip) {
                activity.runOnUiThread(() -> activity.moveTaskToBack(true));
                titles.clear();
                await(titles, "hidden:still", "closing PiP must hide the page and stop media");
                return;
            }

            // Back to full screen.
            Intent back = new Intent(activity, PipHostActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            InstrumentationRegistry.getInstrumentation().getTargetContext().startActivity(back);
            end = System.currentTimeMillis() + 15_000;
            while (activity.isInPictureInPictureMode() && System.currentTimeMillis() < end) {
                Thread.sleep(100);
            }
            Assert.assertFalse("did not leave PiP", activity.isInPictureInPictureMode());
            Thread.sleep(1500);
            activity.runOnUiThread(() -> browser.get().evaluateJavaScript(
                    "document.body.style.background='#0000c0';"));
            assertLive(browser.get(), "after leaving PiP");
        }
    }

    private static void await(List<String> titles, String want, String what)
            throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        while (!titles.contains(want) && System.currentTimeMillis() < end) {
            Thread.sleep(100);
        }
        Assert.assertTrue(what + "; titles " + titles, titles.contains(want));
    }

    private static void assertLive(CefriumBrowser b, String when) throws Exception {
        long got = 0;
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            got = pixel(b);
            if (got >= 0 && close((int) got, BLUE)) return;
            Thread.sleep(200);
        }
        Assert.fail(String.format("%s: pixel %08x, want %08x", when, got, BLUE));
    }

    private static boolean close(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > 24) return false;
        }
        return true;
    }

    private static long pixel(CefriumBrowser b) throws Exception {
        int[] at = new int[2];
        int[] size = new int[2];
        java.util.concurrent.CountDownLatch measured = new java.util.concurrent.CountDownLatch(1);
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
