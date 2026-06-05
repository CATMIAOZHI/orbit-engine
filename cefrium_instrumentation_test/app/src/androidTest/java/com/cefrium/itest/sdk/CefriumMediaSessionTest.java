package com.cefrium.itest.sdk;

import com.cefrium.itest.HostActivity;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;

import com.cefrium.CefriumBrowser;

import org.junit.Assert;
import org.junit.Rule;
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

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule
    public GrantPermissionRule notifPerm = GrantPermissionRule.grant(
            "android.permission.POST_NOTIFICATIONS");

    @Test
    public void testMediaSessionBridgeInitializes() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch loaded = new CountDownLatch(1);
        final boolean[] probed = {false};

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
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
