// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.LocalMediaServer;
import com.cefrium.itest.NeedsUnlockedDevice;
import com.cefrium.itest.PipHostActivity;

import android.app.ActivityManager;
import android.app.PictureInPictureParams;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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
    private static final int GREEN = 0xFF00A000;

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

    /**
     * The screen goes off and on while in PiP: the activity stops, which runs
     * the deferred pause, and starts again still paused, so no onResume()
     * comes (Android 10). The page shows new frames in PiP and after
     * returning to full screen.
     */
    @Test
    @NeedsUnlockedDevice
    public void screenOffInPipPlaysOn() throws Exception {
        pip(true, false, true);
    }

    private void pip(boolean forwardOnPause) throws Exception {
        pip(forwardOnPause, false);
    }

    private void pip(boolean forwardOnPause, boolean closePip) throws Exception {
        pip(forwardOnPause, closePip, false);
    }

    private static void shell(String command) throws Exception {
        android.os.ParcelFileDescriptor out = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
        try (java.io.InputStream in = new java.io.FileInputStream(out.getFileDescriptor())) {
            while (in.read() >= 0) {}
        }
        out.close();
    }

    private void pip(boolean forwardOnPause, boolean closePip, boolean screenOff)
            throws Exception {
        PipHostActivity activity = activityRule.getActivity();
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        List<String> titles = new CopyOnWriteArrayList<>();
        List<String> events = new CopyOnWriteArrayList<>();
        try (LocalMediaServer server = new LocalMediaServer(PAGE)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activity);
                browser.set(b);
                activity.getContainer().addView(b.getSurfaceContainer(),
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                b.setOnTitleChangedListener(titles::add);
                activity.setLifecycle(event -> {
                    events.add(event);
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

            if (screenOff) {
                activity.runOnUiThread(() -> {
                    SurfaceView sv = findSurfaceView(browser.get().getSurfaceContainer());
                    Assert.assertNotNull("no SurfaceView", sv);
                    sv.getHolder().addCallback(new SurfaceHolder.Callback() {
                        @Override public void surfaceCreated(SurfaceHolder h) {
                            events.add("surfaceCreated");
                        }
                        @Override public void surfaceChanged(SurfaceHolder h, int f, int w,
                                int hh) {}
                        @Override public void surfaceDestroyed(SurfaceHolder h) {
                            events.add("surfaceDestroyed");
                        }
                    });
                });
                // The instrument sees the PiP window: the page's green.
                assertPipColor(browser.get(), GREEN, "in PiP before the screen went off");
                events.clear();
                shell("input keyevent KEYCODE_SLEEP");
                Thread.sleep(4000);
                shell("input keyevent KEYCODE_WAKEUP");
                shell("wm dismiss-keyguard");
                end = System.currentTimeMillis() + 10_000;
                while (!activity.isInPictureInPictureMode()
                        && System.currentTimeMillis() < end) {
                    Thread.sleep(100);
                }
                Assert.assertTrue("PiP did not come back after the screen",
                        activity.isInPictureInPictureMode());
                // The screen really went off: the activity stopped and the
                // surface was destroyed and made again.
                String seen = events.toString();
                int stop = events.indexOf("stop");
                int destroyed = events.indexOf("surfaceDestroyed");
                Assert.assertTrue("activity did not stop; " + seen, stop >= 0);
                Assert.assertTrue("surface not destroyed; " + seen, destroyed >= 0);
                // The PiP window shows a new frame.
                activity.runOnUiThread(() -> browser.get().evaluateJavaScript(
                        "document.body.style.background='#0000c0';"));
                assertPipColor(browser.get(), BLUE, "in PiP after the screen came back; "
                        + events);
                Thread.sleep(1500);
                titles.clear();
                await(titles, "visible:moving", "after the screen came back, in PiP");
            }

            if (closePip) {
                activity.runOnUiThread(() -> activity.moveTaskToBack(true));
                titles.clear();
                await(titles, "hidden:still", "closing PiP must hide the page and stop media");
                return;
            }

            // Back to full screen: bring the PiP task to the front. (An intent
            // with REORDER_TO_FRONT does not expand PiP on Android 10.)
            for (ActivityManager.AppTask task : activity.getSystemService(ActivityManager.class)
                    .getAppTasks()) {
                if (task.getTaskInfo().taskId == activity.getTaskId()) task.moveToFront();
            }
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

    private static SurfaceView findSurfaceView(View v) {
        if (v instanceof SurfaceView) return (SurfaceView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                SurfaceView s = findSurfaceView(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
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
        assertColor(b, BLUE, when);
    }

    private static void assertColor(CefriumBrowser b, int want, String when)
            throws Exception {
        long got = 0;
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            got = pixel(b);
            if (got >= 0 && close((int) got, want)) return;
            Thread.sleep(200);
        }
        Assert.fail(String.format("%s: pixel %08x, want %08x", when, got, want));
    }

    // The surface itself, through the SDK's PixelCopy: the PiP window is the
    // activity's window scaled down, so screen coordinates do not apply.
    private static void assertPipColor(CefriumBrowser b, int want, String when)
            throws Exception {
        long got = -1;
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            AtomicReference<Bitmap> shot = new AtomicReference<>();
            java.util.concurrent.CountDownLatch done =
                    new java.util.concurrent.CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> b.captureBitmap(bmp -> {
                shot.set(bmp);
                done.countDown();
            }));
            Assert.assertTrue(done.await(5, TimeUnit.SECONDS));
            Bitmap bmp = shot.get();
            // Right edge: the page's background, clear of the video.
            got = bmp == null ? -1
                    : bmp.getPixel(bmp.getWidth() * 11 / 12, bmp.getHeight() / 2)
                            & 0xFFFFFFFFL;
            if (got >= 0 && close((int) got, want)) return;
            Thread.sleep(200);
        }
        Assert.fail(String.format("%s: pixel %08x, want %08x", when, got, want));
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
