// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.NotificationPermission;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;

import com.cefrium.CefriumBrowser;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * FEATURE-036 (F5): system media controls. Wiring smoke test — enabling the
 * media session must create the Chromium MediaSession observer + Android
 * MediaSessionCompat + notification channel without crashing on this port.
 * Full lock-screen behaviour (notification on audible playback) needs a
 * user-gesture-driven manual check and is exercised by the sample apps.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumMediaSessionTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    @Rule
    public TestRule notifPerm = NotificationPermission.grant();

    @Test
    public void testMediaSessionBridgeInitializes() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch loaded = new CountDownLatch(1);
        final boolean[] probed = {false};

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.setMediaSessionEnabled(true);
            browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    loaded.countDown();
                }
            });
            browser.loadHtml("<!doctype html><html><body><video src='x'></video>"
                    + "</body></html>");
        });

        // Reaching load-complete means the media-session bridge was created on
        // connect (MediaSession.fromWebContents + observer + MediaSessionCompat
        // + channel) without throwing / crashing.
        Assert.assertTrue("page did not load (media-session wiring crashed?)",
                loaded.await(20, TimeUnit.SECONDS));
    }
}
