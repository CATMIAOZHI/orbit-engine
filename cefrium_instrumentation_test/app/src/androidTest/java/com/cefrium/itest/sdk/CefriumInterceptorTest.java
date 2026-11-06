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

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Instrumented test for FEATURE-027 request interceptor
 * (CefriumBrowser.RequestInterceptor / CefResourceRequestHandler).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumInterceptorTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    /**
     * The interceptor must be consulted for subresource requests. We load an
     * HTML page that references a distinctive image URL and assert the
     * interceptor was asked about it (and exercised the block path).
     */
    @Test
    public void testInterceptorSeesRequests() throws Exception {
        Activity act = activityRule.getActivity();
        final CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
        final CountDownLatch created = new CountDownLatch(1);

        act.runOnUiThread(() -> {
            CefriumBrowser b = browsers.createWithSurface(act);
            b.setRequestInterceptor((url, method, isMainFrame) -> {
                seen.add(url);
                return url.contains("INTERCEPT");  // block the marker URL
            });
            b.loadHtml("<!doctype html><html><body>"
                    + "<img src=\"https://cefrium.test/pixel-INTERCEPT.png\">"
                    + "</body></html>");
            created.countDown();
        });
        Assert.assertTrue(created.await(10, TimeUnit.SECONDS));

        // Poll until the interceptor has been asked about the marker subresource.
        long deadline = System.currentTimeMillis() + 20000;
        boolean found = false;
        while (System.currentTimeMillis() < deadline && !found) {
            for (String u : seen) {
                if (u.contains("pixel-INTERCEPT")) { found = true; break; }
            }
            if (!found) Thread.sleep(200);
        }
        Assert.assertTrue("interceptor was never consulted for the subresource; "
                + "saw: " + seen, found);
    }

    /**
     * The interceptor learns what kind of request it is, which document made
     * it and which page is on top: here a page on 127.0.0.1 framing one on
     * localhost (another site), each with an image.
     */
    @Test
    public void interceptorSeesTheRequestContext() throws Exception {
        Activity act = activityRule.getActivity();
        final CopyOnWriteArrayList<CefriumBrowser.ResourceRequest> seen =
                new CopyOnWriteArrayList<>();
        try (LocalHttpServer inner = new LocalHttpServer(
                     "<html><body><img src='/pixel-INNER.png'></body></html>");
             LocalHttpServer top = new LocalHttpServer("<html><body>"
                     + "<iframe src='" + inner.localhostUrl() + "inner'></iframe>"
                     + "<img src='/pixel-TOP.png'></body></html>")) {
            act.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(act);
                b.setRequestInterceptor(new CefriumBrowser.RequestInterceptor() {
                    @Override
                    public boolean shouldBlockRequest(String url, String method,
                            boolean isMainFrame) {
                        throw new AssertionError("the context-free form was called for " + url);
                    }

                    @Override
                    public boolean shouldBlockRequest(CefriumBrowser.ResourceRequest r) {
                        seen.add(r);
                        return false;
                    }
                });
                b.loadUrl(top.url());
            });
            String topUrl = top.url();
            String innerUrl = inner.localhostUrl() + "inner";
            CefriumBrowser.ResourceRequest page = await(seen, topUrl);
            CefriumBrowser.ResourceRequest frame = await(seen, innerUrl);
            CefriumBrowser.ResourceRequest topImg = await(seen, topUrl + "pixel-TOP.png");
            CefriumBrowser.ResourceRequest innerImg =
                    await(seen, inner.localhostUrl() + "pixel-INNER.png");

            check(page, CefriumBrowser.ResourceRequest.TYPE_MAIN_FRAME, true, topUrl, topUrl);
            check(frame, CefriumBrowser.ResourceRequest.TYPE_SUB_FRAME, false, topUrl, topUrl);
            check(topImg, CefriumBrowser.ResourceRequest.TYPE_IMAGE, true, topUrl, topUrl);
            check(innerImg, CefriumBrowser.ResourceRequest.TYPE_IMAGE, false, innerUrl, topUrl);
            Assert.assertEquals("GET", innerImg.method);
        }
    }

    private static CefriumBrowser.ResourceRequest await(
            CopyOnWriteArrayList<CefriumBrowser.ResourceRequest> seen, String url)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            for (CefriumBrowser.ResourceRequest r : seen) {
                if (r.url.equals(url)) return r;
            }
            Thread.sleep(100);
        }
        StringBuilder all = new StringBuilder();
        for (CefriumBrowser.ResourceRequest r : seen) all.append("\n  ").append(r.url);
        throw new AssertionError("no request for " + url + "; saw:" + all);
    }

    private static void check(CefriumBrowser.ResourceRequest r, int type, boolean mainFrame,
            String document, String topLevel) {
        String what = r.url + " type=" + r.resourceType + " main=" + r.isMainFrame
                + " doc=" + r.documentUrl + " top=" + r.topLevelUrl;
        Assert.assertEquals("type of " + what, type, r.resourceType);
        Assert.assertEquals("isMainFrame of " + what, mainFrame, r.isMainFrame);
        // A subresource that starts before the browser has seen its page commit
        // gets only the page's origin (see ResourceRequest.documentUrl).
        boolean originOnly = type != CefriumBrowser.ResourceRequest.TYPE_MAIN_FRAME
                && type != CefriumBrowser.ResourceRequest.TYPE_SUB_FRAME
                && originOf(document).equals(r.documentUrl);
        if (!originOnly) {
            Assert.assertEquals("documentUrl of " + what, document, r.documentUrl);
        }
        Assert.assertEquals("topLevelUrl of " + what, topLevel, r.topLevelUrl);
    }

    // "http://host:port/" for any URL on that origin.
    private static String originOf(String url) {
        android.net.Uri u = android.net.Uri.parse(url);
        return u.getScheme() + "://" + u.getAuthority() + "/";
    }

    /**
     * A page with no URL of its own (an iframe srcdoc document is
     * about:srcdoc) is reported by the origin its requests come from: what a
     * blocking policy can use. Takes the same path as a subresource that
     * starts before its page's commit reached the browser.
     */
    @Test
    public void aSrcdocFramesRequestsCarryItsOrigin() throws Exception {
        Activity act = activityRule.getActivity();
        final CopyOnWriteArrayList<CefriumBrowser.ResourceRequest> seen =
                new CopyOnWriteArrayList<>();
        try (LocalHttpServer top = new LocalHttpServer("<html><body>"
                + "<iframe srcdoc=\"<img src='/pixel-SRCDOC.png'>\"></iframe></body></html>")) {
            act.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(act);
                b.setRequestInterceptor(new CefriumBrowser.RequestInterceptor() {
                    @Override
                    public boolean shouldBlockRequest(String url, String method,
                            boolean isMainFrame) {
                        return false;
                    }

                    @Override
                    public boolean shouldBlockRequest(CefriumBrowser.ResourceRequest r) {
                        seen.add(r);
                        return false;
                    }
                });
                b.loadUrl(top.url());
            });
            CefriumBrowser.ResourceRequest img = await(seen, top.url() + "pixel-SRCDOC.png");
            Assert.assertEquals("documentUrl of the srcdoc frame's image",
                    originOf(top.url()), img.documentUrl);
            Assert.assertEquals(top.url(), img.topLevelUrl);
            Assert.assertFalse(img.isMainFrame);
        }
    }
}
