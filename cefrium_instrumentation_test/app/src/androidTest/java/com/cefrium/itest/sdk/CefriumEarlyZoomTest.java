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

import java.util.concurrent.atomic.AtomicReference;

/**
 * A zoom level set before the first page loads applies to that page. Zoom is
 * per host in Chromium, and before the first page there is no host.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumEarlyZoomTest {
    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    // The instrument: a zoom set on a loaded page shows in devicePixelRatio.
    @Test
    public void zoomSetAfterLoadApplies() throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<String> title = new AtomicReference<>("");
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer(
                "<!doctype html><script>document.title = 'dpr:' + devicePixelRatio;"
                + "addEventListener('resize', () => document.title = 'dpr2:'"
                + " + devicePixelRatio + ':' + innerWidth);</script>")) {
            activity.runOnUiThread(() -> {
                // Zoom is kept per host in the profile: a fresh one each time.
                CefriumBrowser b = browsers.createWithSurface(activity,
                        CefriumProfile.get("zoom-after-" + System.nanoTime()));
                browser.set(b);
                activity.setContentView(b.getSurfaceContainer());
                b.setOnTitleChangedListener(title::set);
                b.loadUrl(server.url());
            });
            long end = System.currentTimeMillis() + 20_000;
            while (!title.get().startsWith("dpr:") && System.currentTimeMillis() < end) {
                Thread.sleep(100);
            }
            Thread.sleep(1000);
            activity.runOnUiThread(() -> browser.get().setZoomLevel(2.0));
            end = System.currentTimeMillis() + 10_000;
            while (!title.get().startsWith("dpr2:") && System.currentTimeMillis() < end) {
                Thread.sleep(100);
            }
            Assert.assertTrue(title.get(), title.get().startsWith("dpr2:"));
            double dpr = Double.parseDouble(title.get().split(":")[1]);
            Assert.assertEquals("devicePixelRatio after setZoomLevel(2)",
                    activity.getResources().getDisplayMetrics().density * 1.44, dpr, 0.05);
        }
    }

    @Test
    public void zoomSetBeforeTheFirstLoadApplies() throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<String> title = new AtomicReference<>("");
        float density = activity.getResources().getDisplayMetrics().density;
        try (LocalHttpServer server = new LocalHttpServer(
                "<!doctype html><script>document.title = 'dpr:' + devicePixelRatio;</script>")) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activity,
                        CefriumProfile.get("zoom-before-" + System.nanoTime()));
                activity.setContentView(b.getSurfaceContainer());
                b.setZoomLevel(2.0);
                b.setOnTitleChangedListener(title::set);
                b.loadUrl(server.url());
            });
            long end = System.currentTimeMillis() + 20_000;
            while (!title.get().startsWith("dpr:") && System.currentTimeMillis() < end) {
                Thread.sleep(100);
            }
            Assert.assertTrue(title.get(), title.get().startsWith("dpr:"));
            double dpr = Double.parseDouble(title.get().substring(4));
            Assert.assertEquals("devicePixelRatio, density " + density,
                    density * 1.44, dpr, 0.05);
        }
    }
}
