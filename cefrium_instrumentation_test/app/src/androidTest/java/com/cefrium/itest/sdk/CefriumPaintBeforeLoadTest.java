// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A page paints and takes input before its load event (SC-rc-24).
 *
 * The page references an image on a socket that accepts the connection and
 * never answers, so the load event never fires. A windowed browser must still
 * paint the page and deliver a click to it while it is loading: a page with
 * one slow subresource is not a blank page.
 *
 * A regression guard, not the test that found the defect. The SDK used to
 * connect the rendering pipeline on a fixed 2 s timer after the first
 * loadUrl, during which nothing painted and all input was dropped; this test
 * passed even then, because the hanging image keeps the page loading well past
 * 2 s. What caught the defect was the full suite with the app zygote enabled,
 * where renderers start fast enough to run into the gap. The cross-site hop
 * (127.0.0.1, then localhost) makes the navigation swap renderer processes, so
 * the guard covers a freshly created view as well as the initial one.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumPaintBeforeLoadTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    /** Accepts connections and never writes, holding the page's load open. */
    private static final class HangingServer implements AutoCloseable {
        final ServerSocket socket;
        final List<Socket> held = new CopyOnWriteArrayList<>();
        final Thread acceptor;

        HangingServer() throws Exception {
            socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(() -> {
                try {
                    while (!socket.isClosed()) {
                        held.add(socket.accept());
                    }
                } catch (Exception ignored) {
                    // Closed by close().
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            for (Socket s : held) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String page(int hangingPort) {
        return "<!doctype html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "</head><body style=\"margin:0;width:100vw;height:100vh\">"
                + "<p style=\"font:48px sans-serif\">Content that paints first</p>"
                + "<img src=\"http://127.0.0.1:" + hangingPort + "/never.png\">"
                + "<script>"
                + "requestAnimationFrame(function(){requestAnimationFrame(function(){"
                + "document.title='painted';});});"
                + "document.addEventListener('click',function(){document.title='clicked';});"
                + "</script></body></html>";
    }

    @Test
    public void pagePaintsAndTakesAClickBeforeItsLoadEvent() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch painted = new CountDownLatch(1);
        final CountDownLatch clicked = new CountDownLatch(1);
        final boolean[] loadingAtClick = {false};
        final boolean[] loading = {false};
        final boolean[] crossed = {false};
        final Handler main = new Handler(Looper.getMainLooper());

        try (HangingServer hang = new HangingServer();
             LocalHttpServer first = new LocalHttpServer("<html><body>first</body></html>");
             LocalHttpServer server = new LocalHttpServer(page(hang.port()))) {
            final String crossSite = server.url().replace("127.0.0.1", "localhost");
            activity.runOnUiThread(() -> {
                CefriumBrowser browser = browsers.createWithSurface(activity);
                activity.setContentView(browser.getSurfaceContainer());
                browser.setOnLoadingStateChangedListener((isLoading, back, fwd) -> {
                    loading[0] = isLoading;
                    // Once the first site has loaded, navigate cross-site.
                    if (!isLoading && !crossed[0]) {
                        crossed[0] = true;
                        browser.loadUrl(crossSite);
                    }
                });
                browser.setOnTitleChangedListener(title -> {
                    if ("painted".equals(title) && painted.getCount() > 0) {
                        painted.countDown();
                        // Inject once a frame has been produced, while the page
                        // is still loading.
                        main.postDelayed(() -> {
                            if (browser.isClosed()) return;
                            loadingAtClick[0] = loading[0];
                            browser.sendLeftClick(150, 300);
                        }, 300);
                    } else if ("clicked".equals(title)) {
                        clicked.countDown();
                    }
                });
                browser.loadUrl(first.url());
            });

            Assert.assertTrue("the page never produced a frame before its load event: "
                            + "a page with one hanging subresource stayed blank",
                    painted.await(15, TimeUnit.SECONDS));
            Assert.assertTrue("the click injected after the first frame never reached "
                            + "the page's DOM", clicked.await(10, TimeUnit.SECONDS));
            Assert.assertTrue("the click must land while the page is still loading, or "
                            + "this test proves nothing about paint before load",
                    loadingAtClick[0]);
        }
    }
}
