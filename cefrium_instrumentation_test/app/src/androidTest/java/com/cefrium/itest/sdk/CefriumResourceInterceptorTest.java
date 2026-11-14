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
import com.cefrium.CefriumContentRequest;
import com.cefrium.CefriumContentResponse;
import com.cefrium.CefriumResourceInterceptor;
import com.cefrium.CefriumResourceType;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebView's loadUrl(String, Map) and shouldInterceptRequest:
 * - the headers of loadUrl reach the page's own request, not its resources;
 * - shouldBlockRequest sees each request's CefriumResourceType and cancels;
 * - shouldInterceptRequest answers a POST with its body, lets another request
 *   go to the network when it returns null, and serves a whole https page of
 *   a domain that does not exist;
 * - neither interceptor method runs on the UI thread.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumResourceInterceptorTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE = "<!doctype html><title>loading</title>"
            + "<img src='/img.png'><script src='/blocked.js'></script><script>"
            + "if (location.pathname == '/start') {"
            + "  fetch('/intercepted?x=1', {method: 'POST', body: 'abc'})"
            + "    .then(r => r.text())"
            + "    .then(t => fetch('/passthrough').then(r =>"
            + "      document.title = 'done:' + t + ':' + r.status))"
            + "    .catch(e => document.title = 'error:' + e);"
            + "}</script>";

    private static final String OFFLINE = "https://offline.example/page";

    private final List<String> titles = new CopyOnWriteArrayList<>();

    private void awaitTitle(String prefix) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + 30000;
        while (SystemClock.uptimeMillis() < end) {
            for (String t : titles) if (t.startsWith(prefix)) return;
            Thread.sleep(100);
        }
        Assert.fail("no title starting " + prefix + "; titles " + titles);
    }

    private static String path(String url) {
        String p = url.substring(url.indexOf('/', url.indexOf("//") + 2));
        int q = p.indexOf('?');
        return q < 0 ? p : p.substring(0, q);
    }

    @Test
    public void headersBlockingAndAnswers() throws Exception {
        Map<String, CefriumResourceType> types = new ConcurrentHashMap<>();
        AtomicBoolean blockOnUi = new AtomicBoolean();
        AtomicBoolean interceptOnUi = new AtomicBoolean();
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumResourceInterceptor interceptor = new CefriumResourceInterceptor() {
                @Override
                public boolean shouldBlockRequest(CefriumBrowser.ResourceRequest r) {
                    if (Looper.myLooper() == Looper.getMainLooper()) blockOnUi.set(true);
                    types.put(path(r.url), r.getType());
                    return path(r.url).equals("/blocked.js");
                }

                @Override
                public CefriumContentResponse shouldInterceptRequest(CefriumContentRequest r) {
                    if (Looper.myLooper() == Looper.getMainLooper()) interceptOnUi.set(true);
                    if (r.getUrl().startsWith(OFFLINE)) {
                        return CefriumContentResponse.ofText("text/html",
                                "<title>offline-copy:" + r.getMethod() + "</title>");
                    }
                    if (r.getPath().equals("/intercepted")) {
                        byte[] body = r.getBody();
                        return CefriumContentResponse.ofText("text/plain",
                                "answered-" + r.getMethod() + "-" + r.getQuery() + "-"
                                + (body == null ? "nobody"
                                        : new String(body, StandardCharsets.UTF_8)));
                    }
                    return null;
                }
            };
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(400, 300, 1.0f));
                browser.set(b);
                b.setOnTitleChangedListener(titles::add);
                b.setResourceInterceptor(interceptor);
                b.loadUrl(server.url() + "start",
                        Collections.singletonMap("X-Cefrium-Test", "hello"));
            });
            awaitTitle("done:");
            Assert.assertEquals("done:answered-POST-x=1-abc:200",
                    titles.get(titles.size() - 1));

            // The headers of loadUrl: on the page's request only.
            Assert.assertTrue("the page's request lacks the header; " + server.headers,
                    server.headers.contains("/start X-Cefrium-Test: hello"));
            for (String h : server.headers) {
                Assert.assertFalse("a resource carried the page's header: " + h,
                        h.contains("X-Cefrium-Test") && !h.startsWith("/start "));
            }

            // Blocking, with the type of each request.
            Assert.assertEquals(CefriumResourceType.MAIN_FRAME, types.get("/start"));
            Assert.assertEquals(CefriumResourceType.IMAGE, types.get("/img.png"));
            Assert.assertEquals(CefriumResourceType.SCRIPT, types.get("/blocked.js"));
            Assert.assertEquals(CefriumResourceType.XHR, types.get("/passthrough"));
            List<String> paths = new CopyOnWriteArrayList<>();
            for (String r : server.requests) paths.add(path("http://h" + r.split(" ")[0]));
            Assert.assertFalse("a blocked request reached the server: " + paths,
                    paths.contains("/blocked.js"));
            // Answered by the interceptor, not the server; null went to it.
            Assert.assertFalse("an answered request reached the server: " + paths,
                    paths.contains("/intercepted"));
            Assert.assertTrue("a null answer did not go to the network: " + paths,
                    paths.contains("/passthrough"));

            // A whole page of a domain that does not exist.
            activityRule.getActivity().runOnUiThread(() -> browser.get().loadUrl(OFFLINE));
            awaitTitle("offline-copy:");
            Assert.assertTrue(titles.contains("offline-copy:GET"));

            Assert.assertFalse("shouldBlockRequest ran on the UI thread", blockOnUi.get());
            Assert.assertFalse("shouldInterceptRequest ran on the UI thread",
                    interceptOnUi.get());
        }
    }
}
