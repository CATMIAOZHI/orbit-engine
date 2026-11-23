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
import com.cefrium.CefriumUserAgentMetadata;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * User-Agent Client Hints: the engine's own agree with its User-Agent on
 * "mobile"; an override sends none unless metadata comes with it; the
 * engine's or the app's own metadata reach both the headers and
 * navigator.userAgentData.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumUserAgentHintsTest {
    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE = "<!doctype html><script>"
            + "const d = navigator.userAgentData;"
            + "document.title = 'r:' + JSON.stringify(d.brands.map(b => b.brand + '/' + b.version))"
            + " + ' mobile=' + d.mobile + ' platform=' + d.platform;</script>";

    private static final class Seen {
        String title;
        String ua;
        String chUa;
        String chMobile;
        String chPlatform;
    }

    private Seen load(Consumer<CefriumBrowser> configure) throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<String> title = new AtomicReference<>("");
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activity);
                activity.setContentView(b.getSurfaceContainer());
                configure.accept(b);
                b.setOnTitleChangedListener(title::set);
                b.loadUrl(server.url());
            });
            long end = System.currentTimeMillis() + 20_000;
            while (!title.get().startsWith("r:") && System.currentTimeMillis() < end) {
                Thread.sleep(100);
            }
            Seen s = new Seen();
            s.title = title.get();
            for (String h : server.headers) {
                if (!h.startsWith("/ ")) continue;  // the page's own request
                String line = h.substring(2);
                String l = line.toLowerCase();
                if (l.startsWith("user-agent:")) s.ua = line.substring(11).trim();
                if (l.startsWith("sec-ch-ua:")) s.chUa = line.substring(10).trim();
                if (l.startsWith("sec-ch-ua-mobile:")) s.chMobile = line.substring(17).trim();
                if (l.startsWith("sec-ch-ua-platform:")) {
                    s.chPlatform = line.substring(19).trim();
                }
            }
            return s;
        }
    }

    @Test
    public void theEngineAgreesWithItself() throws Exception {
        Seen s = load(b -> {});
        Assert.assertTrue(s.title, s.title.contains("Chromium/"));
        boolean uaMobile = s.ua.contains(" Mobile ");
        Assert.assertEquals("UA " + s.ua + " vs Sec-CH-UA-Mobile " + s.chMobile,
                uaMobile ? "?1" : "?0", s.chMobile);
        Assert.assertTrue(s.title, s.title.contains("mobile=" + uaMobile));
    }

    @Test
    public void anOverrideAloneSendsNoHints() throws Exception {
        Seen s = load(b -> b.setUserAgentOverride("Foo/1.0"));
        Assert.assertEquals("Foo/1.0", s.ua);
        Assert.assertNull(s.chUa);
        Assert.assertTrue(s.title, s.title.startsWith("r:[]"));
    }

    @Test
    public void theEngineHintsWithAnOverride() throws Exception {
        Seen s = load(b -> b.setUserAgentOverride("Foo/1.0",
                CefriumUserAgentMetadata.engineDefault()));
        Assert.assertEquals("Foo/1.0", s.ua);
        Assert.assertNotNull("no Sec-CH-UA", s.chUa);
        Assert.assertTrue(s.chUa, s.chUa.contains("\"Chromium\""));
        Assert.assertTrue(s.title, s.title.contains("Chromium/"));
    }

    @Test
    public void hintsOfTheAppsOwn() throws Exception {
        Seen s = load(b -> b.setUserAgentOverride("Foo/1.0",
                new CefriumUserAgentMetadata.Builder()
                        .addBrand("Foo", "9.1.2")
                        .setPlatform("Bar")
                        .setMobile(true)
                        .build()));
        Assert.assertEquals("\"Foo\";v=\"9\"", s.chUa);
        Assert.assertEquals("?1", s.chMobile);
        Assert.assertEquals("\"Bar\"", s.chPlatform);
        Assert.assertEquals("r:[\"Foo/9\"] mobile=true platform=Bar", s.title);
    }
}
