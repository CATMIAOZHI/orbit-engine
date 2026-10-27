// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;
import android.os.Environment;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumRenderHandler;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A download the app allows without a path is saved in the app's own
 * Downloads directory, in both modes. Before, it went to /sdcard/Download
 * (on-screen) or the filesystem root (off-screen), and failed with access
 * denied.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumDownloadTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private static final String CONTENT = "saved by CefriumDownloadTest";

    @Test
    public void offScreen() throws Exception {
        download(false);
    }

    @Test
    public void onScreen() throws Exception {
        download(true);
    }

    private void download(boolean onScreen) throws Exception {
        Activity activity = activityRule.getActivity();
        String name = "cefrium-dl-" + System.nanoTime() + ".txt";
        String page = "<html><body><a id=a download='" + name
                + "' href='data:text/plain," + CONTENT.replace(" ", "%20") + "'>x</a>"
                + "<script>document.title='ready';</script></body></html>";
        File expected = new File(
                activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
        AtomicReference<String> title = new AtomicReference<>("");
        AtomicReference<String> finished = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        CefriumBrowser[] holder = new CefriumBrowser[1];
        try (LocalHttpServer server = new LocalHttpServer(page)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b;
                if (onScreen) {
                    b = browsers.createWithSurface(activity);
                    activity.setContentView(b.getSurfaceContainer());
                } else {
                    b = browsers.track(new CefriumBrowser(400, 300, 1.0f));
                    b.setRenderHandler(new CefriumRenderHandler() {
                        @Override
                        public void onPaint(int width, int height, ByteBuffer px) {}
                    });
                }
                b.setDownloadHandler(new CefriumBrowser.CefriumDownloadHandler() {
                    @Override
                    public void onBeforeDownload(CefriumBrowser.DownloadItem item,
                            CefriumBrowser.BeforeDownloadCallback cb) {
                        cb.allow(null);
                    }

                    @Override
                    public void onDownloadUpdated(CefriumBrowser.DownloadItem item) {
                        if (item.isComplete()) {
                            finished.set("complete " + item.getFullPath());
                            done.countDown();
                        } else if (item.isCanceled() || item.isInterrupted()) {
                            finished.set("failed " + item.getFullPath());
                            done.countDown();
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
            activity.runOnUiThread(() ->
                    holder[0].evaluateJavaScript("document.getElementById('a').click();"));
            Assert.assertTrue("the download never finished", done.await(20, TimeUnit.SECONDS));
            try {
                Assert.assertEquals("complete " + expected.getAbsolutePath(), finished.get());
                Assert.assertEquals(CONTENT, new String(
                        Files.readAllBytes(expected.toPath()), StandardCharsets.UTF_8));
            } finally {
                expected.delete();
            }
        }
    }
}
