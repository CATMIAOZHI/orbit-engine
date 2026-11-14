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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An off-screen browser reports the page's scroll offset and opens the text
 * selection quick menu. Both come from the render frame metadata, delivered
 * once the frame is activated (FEATURE-066, issue #16).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumOsrFrameMetadataTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private CefriumBrowser open(LocalHttpServer server, AtomicReference<String> title,
            CefriumRenderHandler handler) throws Exception {
        Activity activity = activityRule.getActivity();
        CefriumBrowser[] holder = new CefriumBrowser[1];
        activity.runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(400, 300, 1.0f));
            b.setRenderHandler(handler);
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
        return holder[0];
    }

    @Test
    public void scrollOffsetIsReported() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        AtomicReference<String> offset = new AtomicReference<>("");
        try (LocalHttpServer server = new LocalHttpServer("<html><body style='height:3000px'>"
                + "<script>document.title='ready';</script></body></html>")) {
            CefriumBrowser b = open(server, title, new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer px) {}

                @Override
                public void onScrollOffsetChanged(double x, double y) {
                    offset.set((int) x + "," + (int) y);
                }
            });
            activityRule.getActivity().runOnUiThread(
                    () -> b.evaluateJavaScript("window.scrollTo(0, 500)"));
            long deadline = System.currentTimeMillis() + 10_000;
            while (!"0,500".equals(offset.get()) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            Assert.assertEquals("onScrollOffsetChanged", "0,500", offset.get());
        }
    }

    @Test
    public void longPressOnTextOpensTheQuickMenu() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        AtomicInteger flags = new AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicBoolean canCopy =
                new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch menu = new CountDownLatch(1);
        try (LocalHttpServer server = new LocalHttpServer("<html><body style='margin:0'>"
                + "<p style='font-size:60px;margin:0;line-height:300px'>Selectable</p>"
                + "<script>document.title='ready';</script></body></html>")) {
            CefriumBrowser b = open(server, title, new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer px) {}

                @Override
                public void onQuickMenu(int x, int y, int width, int height, int editFlags,
                        CefriumBrowser.QuickMenuCallback callback) {
                    flags.set(editFlags);
                    canCopy.set(callback.canCopy());
                    callback.cancel();
                    menu.countDown();
                }
            });
            Activity activity = activityRule.getActivity();
            activity.runOnUiThread(() -> b.sendTouchDown(100f, 150f));
            Thread.sleep(1200);
            activity.runOnUiThread(() -> b.sendTouchUp(100f, 150f));
            Assert.assertTrue("no quick menu after a long press on text",
                    menu.await(10, TimeUnit.SECONDS));
            Assert.assertTrue("the quick menu cannot copy: flags " + flags.get(),
                    (flags.get() & CefriumBrowser.QM_CAN_COPY) != 0);
            Assert.assertTrue("the menu says it cannot copy", canCopy.get());
        }
    }
}
