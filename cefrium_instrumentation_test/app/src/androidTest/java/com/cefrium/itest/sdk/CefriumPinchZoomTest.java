// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * setPinchToZoomEnabled(false) stops a two-finger pinch from zooming, in both
 * modes, on a page whose JavaScript is off (an app that runs no page script
 * must be able to use it). The page scale is read through the DevTools
 * protocol (Page.getLayoutMetrics), which needs no page script either.
 *
 * The enabled cases prove the pinch is real: without them a pinch that never
 * reached the engine would pass the disabled cases.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumPinchZoomTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE =
            "<!doctype html><html><head>"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "</head><body style=\"margin:0\">"
            + "<div style=\"height:3000px;background:linear-gradient(#eee,#999)\">"
            + "</div></body></html>";

    @Test
    public void surfacePinchZoomsWhenEnabled() throws Exception {
        Assert.assertTrue("a pinch must zoom by default (instrument check)",
                surfaceScaleAfterPinch(true) > 1.05);
    }

    @Test
    public void surfacePinchBlockedWhenDisabled() throws Exception {
        double scale = surfaceScaleAfterPinch(false);
        Assert.assertEquals("pinch zoomed with setPinchToZoomEnabled(false)",
                1.0, scale, 0.01);
    }

    @Test
    public void surfacePinchBlockedAfterNavigation() throws Exception {
        double scale = surfaceScaleAfterPinch(false, true);
        Assert.assertEquals("setPinchToZoomEnabled(false) did not hold across a navigation",
                1.0, scale, 0.01);
    }

    @Test
    public void osrPinchZoomsWhenEnabled() throws Exception {
        Assert.assertTrue("a pinch must zoom by default (instrument check)",
                osrScaleAfterPinch(true) > 1.05);
    }

    @Test
    public void osrPinchBlockedWhenDisabled() throws Exception {
        double scale = osrScaleAfterPinch(false);
        Assert.assertEquals("pinch zoomed with setPinchToZoomEnabled(false)",
                1.0, scale, 0.01);
    }

    private double surfaceScaleAfterPinch(boolean zoom) throws Exception {
        return surfaceScaleAfterPinch(zoom, false);
    }

    // navigate: load a first page, then pinch on a second one.
    private double surfaceScaleAfterPinch(boolean zoom, boolean navigate) throws Exception {
        Activity activity = activityRule.getActivity();
        try (LocalHttpServer server = new LocalHttpServer(PAGE);
                LocalHttpServer second = new LocalHttpServer(PAGE)) {
            AtomicReference<CefriumBrowser> ref = new AtomicReference<>();
            AtomicReference<View> viewRef = new AtomicReference<>();
            CountDownLatch loaded = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activity);
                viewRef.set(b.getSurfaceContainer());
                activity.setContentView(viewRef.get());
                configure(b, zoom, loaded);
                ref.set(b);
                b.loadUrl(server.url());
            });
            Assert.assertTrue("page did not load", loaded.await(30, TimeUnit.SECONDS));
            if (navigate) {
                CountDownLatch loaded2 = new CountDownLatch(1);
                activity.runOnUiThread(() -> {
                    ref.get().setOnLoadingStateChangedListener((loading, back, fwd) -> {
                        if (!loading) loaded2.countDown();
                    });
                    ref.get().loadUrl(second.url());
                });
                Assert.assertTrue("second page did not load",
                        loaded2.await(30, TimeUnit.SECONDS));
            }
            Thread.sleep(1000);
            View view = viewRef.get();
            float cx = view.getWidth() / 2f;
            float cy = view.getHeight() / 2f;
            float d = view.getResources().getDisplayMetrics().density;
            pinchOnView(activity, view, cx, cy, 40 * d, 160 * d);
            return scale(activity, ref.get());
        }
    }

    private double osrScaleAfterPinch(boolean zoom) throws Exception {
        Activity activity = activityRule.getActivity();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            AtomicReference<CefriumBrowser> ref = new AtomicReference<>();
            CountDownLatch loaded = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(400, 600, 1.0f));
                configure(b, zoom, loaded);
                ref.set(b);
                b.loadUrl(server.url());
            });
            Assert.assertTrue("page did not load", loaded.await(30, TimeUnit.SECONDS));
            Thread.sleep(1000);
            CefriumBrowser b = ref.get();
            // Two fingers 40 DIP apart, spread to 160 DIP around the centre.
            float cx = 200, cy = 300;
            ui(activity, () -> {
                b.sendTouchEvent(0, cx - 20, cy, 0);
                b.sendTouchEvent(0, cx + 20, cy, 1);
            });
            for (int i = 1; i <= 12; i++) {
                float half = 20 + 60f * i / 12;
                ui(activity, () -> {
                    b.sendTouchEvent(2, cx - half, cy, 0);
                    b.sendTouchEvent(2, cx + half, cy, 1);
                });
                Thread.sleep(16);
            }
            ui(activity, () -> {
                b.sendTouchEvent(1, cx + 80, cy, 1);
                b.sendTouchEvent(1, cx - 80, cy, 0);
            });
            return scale(activity, b);
        }
    }

    private static void configure(CefriumBrowser b, boolean zoom, CountDownLatch loaded) {
        b.setJavaScriptEnabled(false);
        b.setPinchToZoomEnabled(zoom);
        b.setOnLoadingStateChangedListener((loading, back, fwd) -> {
            if (!loading) loaded.countDown();
        });
    }

    // A real two-pointer gesture through the view, as a pair of fingers.
    private static void pinchOnView(Activity activity, View view, float cx, float cy,
            float startSpan, float endSpan) throws Exception {
        long down = SystemClock.uptimeMillis();
        float h0 = startSpan / 2;
        send(activity, view, down, MotionEvent.ACTION_DOWN, cx - h0, cy, -1, -1);
        send(activity, view, down,
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                cx - h0, cy, cx + h0, cy);
        float h = h0;
        for (int i = 1; i <= 12; i++) {
            h = (startSpan + (endSpan - startSpan) * i / 12) / 2;
            Thread.sleep(16);
            send(activity, view, down, MotionEvent.ACTION_MOVE, cx - h, cy, cx + h, cy);
        }
        send(activity, view, down,
                MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                cx - h, cy, cx + h, cy);
        send(activity, view, down, MotionEvent.ACTION_UP, cx - h, cy, -1, -1);
    }

    private static void send(Activity activity, View view, long down, int action,
            float x0, float y0, float x1, float y1) throws Exception {
        int n = x1 < 0 ? 1 : 2;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        float[][] xy = {{x0, y0}, {x1, y1}};
        for (int i = 0; i < n; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xy[i][0];
            coords[i].y = xy[i][1];
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        MotionEvent ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, n,
                props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        ui(activity, () -> {
            view.dispatchTouchEvent(ev);
            ev.recycle();
        });
    }

    private static double scale(Activity activity, CefriumBrowser b) throws Exception {
        Thread.sleep(1500);
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> b.executeDevToolsMethod("Page.getLayoutMetrics", null,
                (success, json) -> {
                    result.set(success ? json : "error " + json);
                    done.countDown();
                }));
        Assert.assertTrue("Page.getLayoutMetrics did not answer",
                done.await(10, TimeUnit.SECONDS));
        String json = result.get();
        Assert.assertFalse(json, json.startsWith("error"));
        return new JSONObject(json).getJSONObject("cssVisualViewport").getDouble("scale");
    }

    private static void ui(Activity activity, Runnable r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            r.run();
            done.countDown();
        });
        Assert.assertTrue(done.await(5, TimeUnit.SECONDS));
    }
}
