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
import com.cefrium.CefriumProfile;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * setAcceptLanguages: the server receives the header and the page sees the
 * same list in navigator.languages.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumAcceptLanguagesTest {
    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    @Test
    public void headerAndNavigatorLanguages() throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<String> title = new AtomicReference<>("");
        AtomicBoolean set = new AtomicBoolean();
        CefriumProfile profile = CefriumProfile.get("accept-languages-test");
        try (LocalHttpServer server = new LocalHttpServer(
                "<!doctype html><script>document.title = 'langs:'"
                + " + navigator.languages.join(',');</script>")) {
            activity.runOnUiThread(() -> {
                set.set(profile.setAcceptLanguages("eo,de-AT"));
                CefriumBrowser b = browsers.createWithSurface(activity, profile);
                activity.setContentView(b.getSurfaceContainer());
                b.setOnTitleChangedListener(title::set);
                b.loadUrl(server.url());
            });
            long end = System.currentTimeMillis() + 20_000;
            while (!title.get().startsWith("langs:") && System.currentTimeMillis() < end) {
                Thread.sleep(100);
            }
            Assert.assertTrue("the engine rejected the setting", set.get());
            Assert.assertEquals("langs:eo,de-AT", title.get());
            boolean sent = false;
            for (String h : server.headers) {
                if (h.toLowerCase().contains("accept-language: eo,de-at")) sent = true;
            }
            Assert.assertTrue("Accept-Language not sent; headers " + server.headers, sent);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWhatIsNotALanguageList() throws Throwable {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() -> {
            try {
                CefriumProfile.get("accept-languages-test").setAcceptLanguages("es;q=0.9\r\nX: y");
            } catch (Throwable t) {
                thrown.set(t);
            }
            done.countDown();
        });
        done.await(5, java.util.concurrent.TimeUnit.SECONDS);
        if (thrown.get() != null) throw thrown.get();
    }
}
