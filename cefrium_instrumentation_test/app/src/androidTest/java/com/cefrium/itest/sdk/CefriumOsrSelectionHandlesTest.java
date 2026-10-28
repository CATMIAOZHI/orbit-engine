// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumRenderHandler;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An off-screen browser reports where to draw the selection handles, and a
 * drag that starts on a handle moves that end of the selection (FEATURE-078).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumOsrSelectionHandlesTest {
    private static final String PAGE = "<html><body style='margin:0'>"
            + "<p style='font-size:40px;margin:60px 0 0 20px;line-height:60px;white-space:nowrap'>"
            + "Selectable words here</p>"
            + "<script>document.title='ready';</script></body></html>";

    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    /** Every handle report, in arrival order: {orientation, x, y, enabled}. */
    private final List<int[]> mReports = new ArrayList<>();
    private final AtomicReference<String> mSelection = new AtomicReference<>("");
    /** The latest report of each handle, by id. */
    private final java.util.Map<Integer, CefriumRenderHandler.TouchHandle> mHandles =
            new java.util.TreeMap<>();

    private CefriumBrowser openAndLongPress(LocalHttpServer server) throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        Activity activity = activityRule.getActivity();
        CefriumBrowser[] holder = new CefriumBrowser[1];
        activity.runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(800, 300, 1.0f));
            b.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer px) {}

                @Override
                public void onQuickMenu(int x, int y, int width, int height, int editFlags,
                        CefriumBrowser.QuickMenuCallback callback) {
                    callback.cancel();
                }

                @Override
                public void onTextSelectionChanged(String text) {
                    mSelection.set(text);
                }

                @Override
                public void onTouchHandleChanged(CefriumRenderHandler.TouchHandle h) {
                    synchronized (mReports) {
                        mHandles.put(h.id, h);
                    }
                    CefriumRenderHandler.super.onTouchHandleChanged(h);
                }

                @Override
                public void onTouchHandleStateChanged(int orientation, int x, int y,
                        boolean enabled) {
                    synchronized (mReports) {
                        mReports.add(new int[] {orientation, x, y, enabled ? 1 : 0});
                    }
                }
            });
            b.setOnTitleChangedListener(title::set);
            b.loadUrl(server.url());
            holder[0] = b;
        });
        long deadline = System.currentTimeMillis() + 20_000;
        while (!"ready".equals(title.get()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        Assert.assertEquals("ready", title.get());
        Thread.sleep(500);
        CefriumBrowser b = holder[0];
        activity.runOnUiThread(() -> b.sendTouchDown(60f, 90f));
        Thread.sleep(1200);
        activity.runOnUiThread(() -> b.sendTouchUp(60f, 90f));
        deadline = System.currentTimeMillis() + 10_000;
        while (!"Selectable".equals(mSelection.get()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        Assert.assertEquals("the long press did not select the word", "Selectable",
                mSelection.get());
        Thread.sleep(1000);  // let the handles settle
        return b;
    }

    /** The shown handles: reports that say enabled at a real position, by x. */
    private List<int[]> shownHandles() {
        List<int[]> shown = new ArrayList<>();
        synchronized (mReports) {
            for (int[] r : mReports) {
                if (r[3] == 1 && (r[1] != 0 || r[2] != 0)) shown.add(r);
            }
        }
        return shown;
    }

    private String reports() {
        StringBuilder s = new StringBuilder();
        synchronized (mReports) {
            for (int[] r : mReports) {
                s.append(String.format(" [o=%d x=%d y=%d en=%d]", r[0], r[1], r[2], r[3]));
            }
        }
        return s.toString();
    }

    @Test
    public void bothSelectionHandlesAreReportedShownAtTheWordEnds() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            openAndLongPress(server);
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            for (int[] r : shownHandles()) {
                minX = Math.min(minX, r[1]);
                maxX = Math.max(maxX, r[1]);
            }
            // "Selectable" at 40px starts at x=20 and is well over 100px wide.
            Assert.assertTrue("no handle reported shown at a position:" + reports(),
                    minX != Integer.MAX_VALUE);
            Assert.assertTrue("the handles are not at both ends of the word:" + reports(),
                    minX < 60 && maxX > 120);
        }
    }

    /**
     * The handles as the current API reports them: one start (LEFT) and one end
     * (RIGHT) handle, each with its own id, shown and opaque, at the word ends.
     */
    @Test
    public void eachHandleIsReportedWholeWithItsId() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            openAndLongPress(server);
            CefriumRenderHandler.TouchHandle start = null;
            CefriumRenderHandler.TouchHandle end = null;
            String all;
            synchronized (mReports) {
                StringBuilder d = new StringBuilder();
                for (CefriumRenderHandler.TouchHandle h : mHandles.values()) {
                    d.append(String.format(" [id=%d en=%b o=%d x=%d y=%d a=%.2f]",
                            h.id, h.enabled, h.orientation, h.x, h.y, h.alpha));
                    if (!h.enabled) continue;
                    if (h.orientation == CefriumRenderHandler.TouchHandle.LEFT) start = h;
                    if (h.orientation == CefriumRenderHandler.TouchHandle.RIGHT) end = h;
                }
                all = d.toString();
            }
            Assert.assertNotNull("no shown start handle:" + all, start);
            Assert.assertNotNull("no shown end handle:" + all, end);
            Assert.assertNotEquals("start and end share an id:" + all, start.id, end.id);
            Assert.assertTrue("start is not left of end:" + all, start.x < 60 && end.x > 120);
            Assert.assertTrue("a shown handle is transparent:" + all,
                    start.alpha > 0.5f && end.alpha > 0.5f);
        }
    }

    @Test
    public void draggingTheEndHandleExtendsTheSelection() throws Exception {
        Assert.assertTrue("the drag left the selection at '" + dragEndHandle(0) + "'",
                mSelection.get().startsWith("Selectable words"));
    }

    /** Control: the same drag, started below the handle, does not move it. */
    @Test
    public void aDragThatMissesTheHandleLeavesTheSelection() throws Exception {
        dragEndHandle(80);
        Assert.assertFalse("a drag off the handle extended the selection to '"
                        + mSelection.get() + "'",
                mSelection.get().startsWith("Selectable words"));
    }

    /** Long-presses the first word, then drags from {@code below} under the end handle. */
    private String dragEndHandle(float below) throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = openAndLongPress(server);
            int[] end = null;
            for (int[] r : shownHandles()) {
                if (end == null || r[1] > end[1]) end = r;
            }
            Assert.assertNotNull("no end handle reported:" + reports(), end);
            final float x0 = end[1] + CefriumRenderHandler.TouchHandle.SIZE / 2f;
            final float y0 = end[2] + CefriumRenderHandler.TouchHandle.SIZE / 2f + below;
            Activity activity = activityRule.getActivity();
            activity.runOnUiThread(() -> b.sendTouchDown(x0, y0));
            for (int i = 1; i <= 10; i++) {
                final float x = x0 + i * 25f;
                Thread.sleep(30);
                activity.runOnUiThread(() -> b.sendTouchMove(x, y0));
            }
            Thread.sleep(30);
            activity.runOnUiThread(() -> b.sendTouchUp(x0 + 250f, y0));
            long deadline = System.currentTimeMillis() + 5_000;
            while (!mSelection.get().startsWith("Selectable words")
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            return mSelection.get();
        }
    }
}
