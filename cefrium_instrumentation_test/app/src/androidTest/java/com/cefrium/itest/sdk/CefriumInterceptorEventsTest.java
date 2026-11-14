// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.os.Looper;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumNavigationClient;
import com.cefrium.CefriumProfile;
import com.cefrium.CefriumResourceInterceptor;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The resource notifications on CefriumResourceInterceptor, on the network
 * thread, with nothing else to switch on: a redirect, the response with its
 * status, the load's end with its bytes, the body as it arrives, and a cookie
 * the filter refuses (it never reaches the server). The navigation client
 * hears onDocumentAvailable.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumInterceptorEventsTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE =
            "<!doctype html><title>events</title><body>body-marker-42</body>";

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> onUi = new CopyOnWriteArrayList<>();

    private void add(String e) {
        if (Looper.myLooper() == Looper.getMainLooper()) onUi.add(e);
        events.add(e);
    }

    private boolean has(String prefix) {
        for (String e : events) if (e.startsWith(prefix)) return true;
        return false;
    }

    @Test
    public void notificationsWithoutOptIns() throws Exception {
        CefriumProfile profile = CefriumProfile.get("interceptor-events-test");
        StringBuffer body = new StringBuffer();
        CountDownLatch document = new CountDownLatch(1);
        List<String> titles = new CopyOnWriteArrayList<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            String target = server.url() + "target";
            CountDownLatch cookieSet = new CountDownLatch(1);
            profile.getCookieManager().setCookie(server.url(), "secret=1; Path=/",
                    ok -> cookieSet.countDown());
            Assert.assertTrue(cookieSet.await(15, TimeUnit.SECONDS));
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f, profile));
                b.setOnTitleChangedListener(titles::add);
                b.setNavigationClient(new CefriumNavigationClient() {
                    @Override
                    public void onDocumentAvailable(CefriumBrowser x) {
                        document.countDown();
                    }
                });
                b.setResourceInterceptor(new CefriumResourceInterceptor() {
                    @Override
                    public void onResourceRedirect(String oldUrl, String newUrl) {
                        add("redirect " + oldUrl + " -> " + newUrl);
                    }

                    @Override
                    public void onResourceResponse(String url, int statusCode) {
                        add("response " + url + " " + statusCode);
                    }

                    @Override
                    public void onResourceLoadComplete(String url, int statusCode, long bytes) {
                        add("complete " + url + " " + statusCode + " " + (bytes > 0));
                    }

                    @Override
                    public void onResponseData(String url, ByteBuffer chunk) {
                        byte[] b = new byte[chunk.remaining()];
                        chunk.get(b);
                        body.append(new String(b, StandardCharsets.UTF_8));
                        add("data " + url);
                    }

                    @Override
                    public boolean onCanSendCookie(String url, String name, String value) {
                        add("send-cookie " + name);
                        return !"secret".equals(name);
                    }
                });
                b.loadUrl(server.url() + "redirect/target");
            });
            long end = SystemClock.uptimeMillis() + 20000;
            while (!titles.contains("events") && SystemClock.uptimeMillis() < end) {
                Thread.sleep(100);
            }
            Assert.assertTrue("the page did not load", titles.contains("events"));
            Thread.sleep(1000);
            Assert.assertTrue("no onDocumentAvailable", document.await(5, TimeUnit.SECONDS));
            String all = events.toString();
            Assert.assertTrue(all, has("redirect " + server.url() + "redirect/target -> " + target));
            Assert.assertTrue(all, has("response " + target + " 200"));
            Assert.assertTrue(all, has("complete " + target + " 200 true"));
            Assert.assertTrue(all, has("data " + target));
            Assert.assertTrue("body: " + body, body.toString().contains("body-marker-42"));
            Assert.assertTrue(all, has("send-cookie secret"));
            for (String h : server.headers) {
                Assert.assertFalse("a refused cookie was sent: " + h, h.contains("secret=1"));
            }
            Assert.assertTrue("notified on the UI thread: " + onUi, onUi.isEmpty());
        }
    }

    /**
     * onRequestFiltered hears every request after the block decision, with
     * nothing else set: the page, and a blocked image as blocked.
     */
    @Test
    public void everyRequestAfterTheBlockDecision() throws Exception {
        String page = "<!doctype html><title>filtered</title>"
                + "<img src='/blocked.png'><img src='/allowed.png'>";
        List<String> titles = new CopyOnWriteArrayList<>();
        try (LocalHttpServer server = new LocalHttpServer(page)) {
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f));
                b.setOnTitleChangedListener(titles::add);
                b.setResourceInterceptor(new CefriumResourceInterceptor() {
                    @Override
                    public boolean shouldBlockRequest(CefriumBrowser.ResourceRequest r) {
                        return r.url.endsWith("/blocked.png");
                    }

                    @Override
                    public void onRequestFiltered(String url, String method, boolean blocked) {
                        add((blocked ? "blocked " : "passed ") + method + " " + url);
                    }
                });
                b.loadUrl(server.url());
            });
            long end = SystemClock.uptimeMillis() + 20000;
            while (SystemClock.uptimeMillis() < end
                    && !(titles.contains("filtered") && has("blocked GET " + server.url()
                            + "blocked.png") && has("passed GET " + server.url() + "allowed.png"))) {
                Thread.sleep(100);
            }
            String all = events.toString();
            Assert.assertTrue(all, has("passed GET " + server.url()));
            Assert.assertTrue(all, has("blocked GET " + server.url() + "blocked.png"));
            Assert.assertTrue(all, has("passed GET " + server.url() + "allowed.png"));
            Assert.assertTrue("notified on the UI thread: " + onUi, onUi.isEmpty());
        }
    }
}
