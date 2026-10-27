// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.SlowDownloadServer;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumProfile;
import com.cefrium.CefriumRenderHandler;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * The downloads of a profile: reported whichever browser started them and
 * after it closes, listed, paused and resumed (a real resume, from where it
 * stopped) and removed through the profile.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumProfileDownloadsTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private static final int SIZE = 4 * 1024 * 1024;

    private final Map<String, CefriumProfile.Download> latest = new ConcurrentHashMap<>();
    private final AtomicReference<String> guid = new AtomicReference<>();
    private SlowDownloadServer server;
    private String name;

    @Before
    public void setUp() throws Exception {
        name = "cefrium-profile-dl-" + System.nanoTime() + ".bin";
        server = new SlowDownloadServer(name, SIZE, 50,
                "<html><body><a id=a href='/file'>x</a>"
                + "<script>document.title='ready';</script></body></html>");
        CountDownLatch set = new CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() -> {
            CefriumProfile.getDefault().setDownloadObserver(d -> {
                if (d.getUrl().equals(server.fileUrl())) {
                    guid.compareAndSet(null, d.getGuid());
                    latest.put(d.getGuid(), d);
                }
            });
            set.countDown();
        });
        set.await(5, TimeUnit.SECONDS);
    }

    @After
    public void tearDown() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() -> {
            CefriumProfile.getDefault().setDownloadObserver(null);
            CefriumProfile.Download d = guid.get() == null ? null : latest.get(guid.get());
            if (d != null) {
                if (d.getState() != CefriumProfile.DOWNLOAD_COMPLETE) d.cancel();
                d.remove();
                new File(d.getPath()).delete();
            }
            done.countDown();
        });
        done.await(5, TimeUnit.SECONDS);
        server.close();
    }

    private CefriumBrowser start() throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<String> title = new AtomicReference<>("");
        CefriumBrowser[] holder = new CefriumBrowser[1];
        activity.runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(400, 300, 1.0f));
            b.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer px) {}
            });
            b.setDownloadHandler(new CefriumBrowser.CefriumDownloadHandler() {
                @Override
                public void onBeforeDownload(CefriumBrowser.DownloadItem item,
                        CefriumBrowser.BeforeDownloadCallback cb) {
                    cb.allow(null);
                }

                @Override
                public void onDownloadUpdated(CefriumBrowser.DownloadItem item) {}
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
        activity.runOnUiThread(() ->
                holder[0].evaluateJavaScript("document.getElementById('a').click();"));
        await("the download never started", d -> d.getReceivedBytes() > 0, 20);
        return holder[0];
    }

    private CefriumProfile.Download await(String what, Predicate<CefriumProfile.Download> p,
                                          int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String g = guid.get();
            CefriumProfile.Download d = g == null ? null : latest.get(g);
            if (d != null && p.test(d)) return d;
            Thread.sleep(50);
        }
        String g = guid.get();
        CefriumProfile.Download d = g == null ? null : latest.get(g);
        Assert.fail(what + "; last state=" + (d == null ? "none"
                : d.getState() + " received=" + d.getReceivedBytes()
                + " reason=" + d.getInterruptReason()));
        return null;
    }

    private static void assertContent(String path) throws Exception {
        byte[] bytes = Files.readAllBytes(new File(path).toPath());
        Assert.assertEquals("file size", SIZE, bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != SlowDownloadServer.byteAt(i)) {
                Assert.fail("byte " + i + " is wrong");
            }
        }
    }

    private List<CefriumProfile.Download> list() throws InterruptedException {
        AtomicReference<List<CefriumProfile.Download>> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() ->
                CefriumProfile.getDefault().getDownloads(l -> {
                    out.set(l);
                    done.countDown();
                }));
        Assert.assertTrue("getDownloads never answered", done.await(10, TimeUnit.SECONDS));
        return out.get();
    }

    @Test
    public void aDownloadOutlivesItsBrowserAndIsListed() throws Exception {
        CefriumBrowser b = start();
        activityRule.getActivity().runOnUiThread(b::close);
        CefriumProfile.Download done = await("the download did not finish after its browser closed",
                d -> d.getState() == CefriumProfile.DOWNLOAD_COMPLETE, 60);
        assertContent(done.getPath());
        boolean listed = false;
        for (CefriumProfile.Download d : list()) {
            if (d.getGuid().equals(done.getGuid())) {
                listed = d.getState() == CefriumProfile.DOWNLOAD_COMPLETE;
            }
        }
        Assert.assertTrue("getDownloads does not list the finished download", listed);
    }

    @Test
    public void pauseAndResumeThroughTheProfile() throws Exception {
        start();
        CefriumProfile.Download running = latest.get(guid.get());
        activityRule.getActivity().runOnUiThread(running::pause);
        CefriumProfile.Download paused = await("it never paused", CefriumProfile.Download::isPaused, 10);
        Assert.assertTrue("a paused download says it cannot resume", paused.canResume());
        Thread.sleep(1000);
        activityRule.getActivity().runOnUiThread(() -> latest.get(guid.get()).resume());
        CefriumProfile.Download done = await("it did not finish after resuming",
                d -> d.getState() == CefriumProfile.DOWNLOAD_COMPLETE, 60);
        assertContent(done.getPath());
        List<Long> starts = server.rangeStarts();
        Assert.assertTrue("the resume started over instead of continuing: " + starts,
                starts.size() >= 2 && starts.get(starts.size() - 1) > 0);
    }

    @Test
    public void removeTakesItOffTheList() throws Exception {
        start();
        CefriumProfile.Download done = await("the download did not finish",
                d -> d.getState() == CefriumProfile.DOWNLOAD_COMPLETE, 60);
        CountDownLatch removed = new CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() -> {
            Assert.assertTrue(done.remove());
            removed.countDown();
        });
        removed.await(5, TimeUnit.SECONDS);
        for (CefriumProfile.Download d : list()) {
            Assert.assertNotEquals("removed, still listed", done.getGuid(), d.getGuid());
        }
        Assert.assertTrue("remove deleted the file", new File(done.getPath()).exists());
        new File(done.getPath()).delete();
        guid.set(null);
    }
}
