// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The request listener and Callback.onRequestIntercepted are called on the UI
 * thread, like the rest of Callback, with the request's whole URL (a long
 * query string included).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumRequestListenerTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static String longQuery() {
        StringBuilder q = new StringBuilder("?q=");
        for (int i = 0; i < 300; i++) q.append((char) ('a' + i % 26));
        return q.toString();
    }

    private void reported(boolean viaCallback) throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        AtomicReference<String> url = new AtomicReference<>();
        AtomicReference<Boolean> onMain = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer("<!doctype html><title>t</title>")) {
            String target = server.url() + longQuery();
            CefriumBrowser.OnRequestInterceptedListener record = (u, method, blocked) -> {
                if (u.startsWith(server.url()) && url.compareAndSet(null, u)) {
                    onMain.set(Looper.myLooper() == Looper.getMainLooper());
                    seen.countDown();
                }
            };
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(400, 300, 1.0f));
                if (viaCallback) {
                    b.setCallback(new CefriumBrowser.Callback() {
                        @Override
                        public void onRequestIntercepted(String u, String m, boolean bl) {
                            record.onRequestIntercepted(u, m, bl);
                        }
                    });
                } else {
                    b.setOnRequestInterceptedListener(record);
                }
                b.loadUrl(target);
            });
            Assert.assertTrue("no request was reported", seen.await(30, TimeUnit.SECONDS));
            Assert.assertTrue("reported off the UI thread", onMain.get());
            Assert.assertEquals(target, url.get());
        }
    }

    @Test
    public void listenerGetsWholeUrlOnUiThread() throws Exception {
        reported(false);
    }

    @Test
    public void callbackGetsWholeUrlOnUiThread() throws Exception {
        reported(true);
    }
}
