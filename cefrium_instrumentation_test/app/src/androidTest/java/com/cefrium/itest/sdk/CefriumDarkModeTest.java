// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumRenderHandler;
import com.cefrium.itest.HostActivity;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Regression guard for CefriumBrowser.setDarkMode() in OSR mode (issue #4).
 * setDarkMode() must flip the page's prefers-color-scheme media feature. On
 * Android the chrome ThemeService is not wired up, so SetChromeColorScheme is a
 * no-op; setDarkMode drives a per-browser WebPreferences override instead. The
 * page reports its scheme via document.title and updates on the media 'change'
 * event, so this exercises the exact issue flow: setDarkMode is called AFTER the
 * page has loaded.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumDarkModeTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    private static final String PAGE =
            "<!doctype html><html><head></head><body><script>"
            + "var mq=window.matchMedia('(prefers-color-scheme: dark)');"
            + "function upd(){document.title='SCHEME '+(mq.matches?'dark':'light');}"
            + "mq.addEventListener('change',upd);upd();"
            + "</script></body></html>";

    @Test
    public void setDarkModeAfterLoadFlipsPrefersColorScheme() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch dark = new CountDownLatch(1);
        final boolean[] loaded = {false};

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = new CefriumBrowser(800, 600, 1.0f);
            // OSR needs a render handler to composite; the pixels are irrelevant here.
            browser.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer pixels) { }
            });
            browser.setOnTitleChangedListener(title -> {
                if (title == null) return;
                if (title.equals("SCHEME light") && !loaded[0]) {
                    // Page loaded in the default (light) scheme -- now request dark,
                    // exactly as the issue does (after load). The media 'change'
                    // event must fire and retitle to "SCHEME dark".
                    loaded[0] = true;
                    browser.setDarkMode(true);
                } else if (title.equals("SCHEME dark")) {
                    dark.countDown();
                }
            });
            browser.loadHtml(PAGE);
        });

        Assert.assertTrue(
                "setDarkMode(true) did not flip prefers-color-scheme to dark in the"
                        + " OSR renderer", dark.await(30, TimeUnit.SECONDS));
    }
}
