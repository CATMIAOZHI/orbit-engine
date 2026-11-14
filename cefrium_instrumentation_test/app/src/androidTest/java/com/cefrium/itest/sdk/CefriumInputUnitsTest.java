// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;

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
 * The units of injected input, as the javadoc states them. Off-screen, touch
 * and mouse take device-independent pixels: at scale 2 a touch at (100, 60)
 * lands at CSS (100, 60), not (50, 30). A surface browser takes DIPs for both
 * too.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumInputUnitsTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE = "<!doctype html><html><head>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'></head>"
            + "<body style='margin:0;height:100vh'><script>"
            + "document.title = 'ready:' + devicePixelRatio + ':' + innerWidth;"
            + "addEventListener('pointerdown', e => document.title = 'down:'"
            + "  + e.pointerType + ':' + Math.round(e.clientX) + ',' + Math.round(e.clientY));"
            + "</script></body></html>";

    private final List<String> titles = new CopyOnWriteArrayList<>();

    private String pointerDown(boolean mouse, boolean surface) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch down = new CountDownLatch(1);
        AtomicReference<String> readyTitle = new AtomicReference<>();
        AtomicReference<String> downTitle = new AtomicReference<>();
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        Handler main = new Handler(Looper.getMainLooper());
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = surface
                        ? browsers.createWithSurface(activityRule.getActivity())
                        : browsers.track(new CefriumBrowser(800, 600, 2.0f));
                browser.set(b);
                if (surface) {
                    activityRule.getActivity().addContentView(b.getSurfaceContainer(),
                            new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
                }
                b.setOnTitleChangedListener(t -> {
                    titles.add(t);
                    if (t.startsWith("ready:")) { readyTitle.set(t); ready.countDown(); }
                    if (t.startsWith("down:")) { downTitle.set(t); down.countDown(); }
                });
                b.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                    if (!loading) loaded.countDown();
                });
                b.loadUrl(server.url());
            });
            Assert.assertTrue("the page did not load; titles " + titles,
                    ready.await(30, TimeUnit.SECONDS));
            // The precondition: 800 px at scale 2 is a 400 CSS px viewport.
            if (!surface) Assert.assertEquals("ready:2:400", readyTitle.get());
            Assert.assertTrue(loaded.await(30, TimeUnit.SECONDS));
            // Input sent as soon as the script runs never arrived (measured).
            Thread.sleep(1000);

            main.post(() -> {
                CefriumBrowser b = browser.get();
                if (mouse) {
                    b.sendMouseClick(100, 60, CefriumBrowser.MOUSE_BUTTON_LEFT);
                } else {
                    b.sendTouchDown(100, 60);
                    main.postDelayed(() -> { if (!b.isClosed()) b.sendTouchUp(100, 60); }, 150);
                }
            });
            Assert.assertTrue("no pointerdown reached the page; titles " + titles,
                    down.await(10, TimeUnit.SECONDS));
            return downTitle.get();
        }
    }

    @Test
    public void touchCoordinatesAreDips() throws Exception {
        Assert.assertEquals("down:touch:100,60", pointerDown(false, false));
    }

    @Test
    public void mouseCoordinatesAreDips() throws Exception {
        Assert.assertEquals("down:mouse:100,60", pointerDown(true, false));
    }

    // A surface browser's touch takes DIPs too (FEATURE-092 D3; it took the
    // view's physical pixels, so this was "down:touch:57,34" at density 1.75),
    // and is a finger to the page.
    @Test
    public void surfaceTouchCoordinatesAreDips() throws Exception {
        Assert.assertEquals("down:touch:100,60", pointerDown(false, true));
    }

    @Test
    public void surfaceMouseCoordinatesAreDips() throws Exception {
        Assert.assertEquals("down:mouse:100,60", pointerDown(true, true));
    }
}
