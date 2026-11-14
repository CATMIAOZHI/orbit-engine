// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumContentRequest;
import com.cefrium.CefriumContentResponse;
import com.cefrium.CefriumProfile;
import com.cefrium.CefriumResourceInterceptor;

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
 * A profile's interceptor decides the requests that belong to no browser: a
 * service worker fetches one URL the interceptor answers and one it blocks,
 * and posts what it got to the page. Neither reaches the server.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumProfileInterceptorTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE = "<!doctype html><title>ready</title><script>"
            + "navigator.serviceWorker.addEventListener('message', e => document.title = e.data);"
            + "navigator.serviceWorker.register('/sw.js?v=' + location.hash.slice(1));"
            + "</script>";

    private static final String WORKER = "self.addEventListener('install', () => self.skipWaiting());"
            + "self.addEventListener('activate', e => e.waitUntil((async () => {"
            + "  await self.clients.claim();"
            + "  const probe = await fetch('/sw-probe').then(r => r.text(), () => 'probe-failed');"
            + "  const blocked = await fetch('/sw-blocked').then(() => 'blocked-loaded',"
            + "      () => 'blocked-refused');"
            + "  for (const c of await self.clients.matchAll({includeUncontrolled: true}))"
            + "    c.postMessage(probe + '|' + blocked);"
            + "})()));";

    private void run(CefriumProfile profile) throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        AtomicReference<String> title = new AtomicReference<>("");
        try (LocalHttpServer server = new LocalHttpServer(PAGE, "/sw.js", WORKER)) {
            CountDownLatch set = new CountDownLatch(1);
            activityRule.getActivity().runOnUiThread(() -> {
                profile.setResourceInterceptor(new CefriumResourceInterceptor() {
                    @Override
                    public boolean shouldBlockRequest(CefriumBrowser.ResourceRequest r) {
                        seen.add("block? " + r.url);
                        return r.url.endsWith("/sw-blocked");
                    }

                    @Override
                    public CefriumContentResponse shouldInterceptRequest(
                            CefriumContentRequest r) {
                        if (!r.getUrl().endsWith("/sw-probe")) return null;
                        return CefriumContentResponse.ofText("text/plain", "from-interceptor");
                    }
                });
                set.countDown();
            });
            Assert.assertTrue(set.await(5, TimeUnit.SECONDS));
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f, profile));
                b.setOnTitleChangedListener(title::set);
                b.loadUrl(server.url() + "#" + System.nanoTime());
            });
            long end = SystemClock.uptimeMillis() + 30000;
            while (!title.get().contains("|") && SystemClock.uptimeMillis() < end) {
                Thread.sleep(100);
            }
            Assert.assertEquals("seen " + seen, "from-interceptor|blocked-refused", title.get());
            for (String r : server.requests) {
                Assert.assertFalse("reached the server: " + r, r.contains("/sw-"));
            }
        } finally {
            CountDownLatch cleared = new CountDownLatch(1);
            activityRule.getActivity().runOnUiThread(() -> {
                profile.setResourceInterceptor(null);
                cleared.countDown();
            });
            cleared.await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void aNamedProfile() throws Exception {
        run(CefriumProfile.get("profile-interceptor-test"));
    }

    @Test
    public void theDefaultProfile() throws Exception {
        run(CefriumProfile.getDefault());
    }
}
