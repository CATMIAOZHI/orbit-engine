// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.SlowDownloadServer;

import android.os.Looper;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumDownloadClient;
import com.cefrium.CefriumDownloadInterruptReason;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Downloads through CefriumDownloadClient: the browser and the item arrive on
 * the UI thread, an answer from another thread starts the download, and its
 * updates carry a typed interruption: NONE when it completes, SERVER when
 * the server goes away in the middle. A cancelled download ends cancelled.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumDownloadClientTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE =
            "<!doctype html><title>ready</title><a id=a href='/file'>file</a>";

    private final List<CefriumBrowser.DownloadItem> updates = new CopyOnWriteArrayList<>();
    private final List<String> offUi = new CopyOnWriteArrayList<>();
    private final List<String> titles = new CopyOnWriteArrayList<>();

    private CefriumBrowser.DownloadItem await(String what,
            Predicate<CefriumBrowser.DownloadItem> p, int seconds) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + seconds * 1000L;
        while (SystemClock.uptimeMillis() < end) {
            for (CefriumBrowser.DownloadItem d : updates) if (p.test(d)) return d;
            Thread.sleep(100);
        }
        Assert.fail(what + "; " + updates.size() + " updates");
        return null;
    }

    private CefriumBrowser start(SlowDownloadServer server, boolean allow,
            AtomicReference<String> asked) throws Exception {
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        activityRule.getActivity().runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(400, 300, 1.0f));
            browser.set(b);
            b.setOnTitleChangedListener(titles::add);
            b.setDownloadClient(new CefriumDownloadClient() {
                @Override
                public void onBeforeDownload(CefriumBrowser x, CefriumBrowser.DownloadItem item,
                        CefriumBrowser.BeforeDownloadCallback callback) {
                    if (Looper.myLooper() != Looper.getMainLooper()) offUi.add("before");
                    asked.set((x == browser.get() ? "browser " : "OTHER ")
                            + item.getSuggestedName());
                    new Thread(() -> {
                        if (allow) callback.allow(null); else callback.cancel();
                        callback.cancel();  // only the first answer counts
                    }).start();
                }

                @Override
                public void onDownloadUpdated(CefriumBrowser x, CefriumBrowser.DownloadItem item) {
                    if (Looper.myLooper() != Looper.getMainLooper()) offUi.add("update");
                    updates.add(item);
                }
            });
            b.loadUrl(server.url());
        });
        long end = SystemClock.uptimeMillis() + 20000;
        while (!titles.contains("ready") && SystemClock.uptimeMillis() < end) Thread.sleep(100);
        Assert.assertTrue("the page did not load", titles.contains("ready"));
        activityRule.getActivity().runOnUiThread(() -> browser.get()
                .evaluateJavaScript("document.getElementById('a').click();"));
        return browser.get();
    }

    @Test
    public void allowedDownloadCompletes() throws Exception {
        AtomicReference<String> asked = new AtomicReference<>();
        try (SlowDownloadServer server = new SlowDownloadServer("c.bin", 64 * 1024, 0, PAGE)) {
            start(server, true, asked);
            CefriumBrowser.DownloadItem done = await("the download did not complete",
                    CefriumBrowser.DownloadItem::isComplete, 30);
            Assert.assertEquals("browser c.bin", asked.get());
            Assert.assertEquals(64 * 1024, done.getReceivedBytes());
            Assert.assertEquals(CefriumDownloadInterruptReason.NONE, done.getInterruption());
            Assert.assertTrue("called off the UI thread: " + offUi, offUi.isEmpty());
        }
    }

    @Test
    public void interruptionIsTyped() throws Exception {
        AtomicReference<String> asked = new AtomicReference<>();
        SlowDownloadServer server = new SlowDownloadServer("i.bin", 4 * 1024 * 1024, 100, PAGE);
        try {
            start(server, true, asked);
            await("the download never started", d -> d.getReceivedBytes() > 0, 30);
        } finally {
            server.close();  // the server goes away in the middle
        }
        CefriumBrowser.DownloadItem stopped = await("no interrupted update",
                d -> d.getInterruption() != CefriumDownloadInterruptReason.NONE, 60);
        // The connection closes before the declared length: Chromium calls it
        // a server failure (CONTENT_LENGTH_MISMATCH / SERVER_FAILED), measured.
        Assert.assertEquals(CefriumDownloadInterruptReason.SERVER, stopped.getInterruption());
    }

    @Test
    public void cancelledDownloadWritesNothing() throws Exception {
        AtomicReference<String> asked = new AtomicReference<>();
        try (SlowDownloadServer server = new SlowDownloadServer("x.bin", 64 * 1024, 0, PAGE)) {
            start(server, false, asked);
            long end = SystemClock.uptimeMillis() + 10000;
            while (asked.get() == null && SystemClock.uptimeMillis() < end) Thread.sleep(100);
            Assert.assertEquals("browser x.bin", asked.get());
            // Chromium may fetch the start of the body while the app decides;
            // what matters is that the download ends cancelled, never complete.
            StringBuilder states = new StringBuilder();
            long until = SystemClock.uptimeMillis() + 10000;
            boolean canceled = false;
            while (!canceled && SystemClock.uptimeMillis() < until) {
                for (CefriumBrowser.DownloadItem d : updates) canceled |= d.isCanceled();
                Thread.sleep(200);
            }
            for (CefriumBrowser.DownloadItem d : updates) {
                states.append(d.getReceivedBytes()).append(d.isComplete() ? "C" : "")
                        .append(d.isCanceled() ? "X" : "").append(' ');
                Assert.assertFalse("a cancelled download completed: " + states, d.isComplete());
            }
            Assert.assertTrue("the download did not end cancelled: " + states, canceled);
        }
    }
}
