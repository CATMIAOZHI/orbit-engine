// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;

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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * onTitleChanged reports each title once, as WebView's onReceivedTitle: CEF
 * reports the title again when loading stops, and an app that acts on a
 * title acted twice. A new page with the same title is still reported.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumTitleTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE =
            "<html><head><title>same</title></head><body>x</body></html>";

    private int count(List<String> titles, String title) {
        int n = 0;
        for (String t : titles) if (title.equals(t)) n++;
        return n;
    }

    private void reportsEachTitleOnce(boolean offscreen) throws Exception {
        Activity activity = activityRule.getActivity();
        List<String> titles = new CopyOnWriteArrayList<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser[] b = new CefriumBrowser[1];
            activity.runOnUiThread(() -> {
                b[0] = offscreen
                        ? browsers.track(new CefriumBrowser(400, 300, 1.0f))
                        : browsers.createWithSurface(activity);
                b[0].setOnTitleChangedListener(titles::add);
                b[0].loadUrl(server.url() + "a");
            });
            Thread.sleep(6000);  // past the load's stop
            Assert.assertEquals("first page: " + titles, 1, count(titles, "same"));

            activity.runOnUiThread(() -> b[0].loadUrl(server.url() + "b"));
            Thread.sleep(6000);
            Assert.assertEquals("a new page with the same title: " + titles, 2,
                    count(titles, "same"));
        }
    }

    @Test
    public void reportsEachTitleOnceSurface() throws Exception {
        reportsEachTitleOnce(false);
    }

    @Test
    public void reportsEachTitleOnceOffscreen() throws Exception {
        reportsEachTitleOnce(true);
    }
}
