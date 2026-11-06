// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;
import com.cefrium.itest.NotificationPermission;

import android.Manifest;
import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumProfile;

import org.chromium.components.gcm_driver.instance_id.InstanceIDWithSubtype;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A push subscription's app id is on disk before anything depends on it.
 *
 * Android kills an app at any moment, so a subscription the page has been
 * given must already be in the profile's Preferences file, and an
 * unsubscribed app id must be gone from it before its InstanceID is deleted
 * on the push service. Otherwise a kill in between leaves a subscription
 * whose messages are dropped, or one that the next subscribe reuses after its
 * InstanceID was deleted. Both checks read the file itself, at the moment
 * that matters, so they do not depend on when the process would be killed.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumWebPushDurabilityTest {

    @Rule(order = 0)
    public TestRule notifications = NotificationPermission.grant();

    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private static final String SERVER_KEY =
            "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U";

    private static final String PAGE = "<html><body><script>"
            + "async function go(){"
            + "var reg=await navigator.serviceWorker.register('/sw.js');"
            + "await navigator.serviceWorker.ready;"
            + "if(location.search.indexOf('unsubscribe')>=0){"
            + "var s=await reg.pushManager.getSubscription();"
            + "await s.unsubscribe();document.title='unsubscribed';return;}"
            + "var key=Uint8Array.from(atob('" + SERVER_KEY + "'.replace(/-/g,'+').replace(/_/g,'/')),"
            + "function(c){return c.charCodeAt(0);});"
            + "await reg.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:key});"
            + "document.title='subscribed';}"
            + "go().catch(function(e){document.title='error:'+e;});"
            + "</script></body></html>";
    private static final String SW = "self.addEventListener('push',function(e){});";

    private static final List<String> sTokens = new CopyOnWriteArrayList<>();
    // For each deleteInstanceID: whether the Preferences file still held the app id.
    private static final List<Boolean> sStillOnDiskAtDelete = new CopyOnWriteArrayList<>();
    private static volatile File sPrefs;

    private static boolean onDisk(String appId) {
        try {
            return new String(Files.readAllBytes(sPrefs.toPath()), StandardCharsets.UTF_8)
                    .contains(appId);
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** Stands in for FCM registration; never calls Play services. */
    private static final class FakeInstanceID extends InstanceIDWithSubtype {
        private final String mSubtype;

        FakeInstanceID(String subtype) {
            super(null);
            mSubtype = subtype;
        }

        @Override
        public String getSubtype() {
            return mSubtype;
        }

        @Override
        public String getId() {
            return "fake-id-" + mSubtype.hashCode();
        }

        @Override
        public long getCreationTime() {
            return 1;
        }

        @Override
        public String getToken(String authorizedEntity, String scope) {
            sTokens.add(mSubtype);
            return "fake-token-" + sTokens.size();
        }

        @Override
        public void deleteToken(String authorizedEntity, String scope) {}

        @Override
        public void deleteInstanceID() {
            sStillOnDiskAtDelete.add(onDisk(mSubtype));
        }
    }

    @Before
    public void setUp() {
        sTokens.clear();
        sStillOnDiskAtDelete.clear();
        InstanceIDWithSubtype.setFakeFactoryForTesting(FakeInstanceID::new);
    }

    @After
    public void tearDown() {
        InstanceIDWithSubtype.setFakeFactoryForTesting(null);
    }

    private void open(CefriumProfile profile, String url, String waitFor) throws Exception {
        Activity activity = activityRule.getActivity();
        final AtomicReference<String> title = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity, profile);
            browser.setContentSetting(CefriumBrowser.QueryOriginPolicy.originOf(url),
                    CefriumBrowser.PERMISSION_NOTIFICATIONS, CefriumBrowser.SETTING_ALLOW);
            browser.setOnTitleChangedListener(t -> {
                title.set(t);
                if (t != null && (t.equals(waitFor) || t.startsWith("error:"))) done.countDown();
            });
            browser.loadUrl(url);
        });
        Assert.assertTrue("no '" + waitFor + "' from " + url + "; title=" + title.get(),
                done.await(30, TimeUnit.SECONDS));
        Assert.assertEquals(waitFor, title.get());
    }

    private CefriumProfile profile() {
        CefriumProfile profile =
                CefriumProfile.get("push-durable-" + UUID.randomUUID().toString().substring(0, 8));
        sPrefs = new File(profile.getDirectory(), "Preferences");
        return profile;
    }

    @Test
    public void aSubscriptionIsOnDiskWhenThePageGetsIt() throws Exception {
        CefriumProfile profile = profile();
        try (LocalHttpServer server = new LocalHttpServer(PAGE, "/sw.js", SW)) {
            open(profile, server.url(), "subscribed");
            Assert.assertEquals("the page did not register", 1, sTokens.size());
            Assert.assertTrue("the page has its subscription, but its app id is not on disk",
                    onDisk(sTokens.get(0)));
        }
    }

    @Test
    public void anUnsubscribedAppIdLeavesTheDiskBeforeItsInstanceIdIsDeleted() throws Exception {
        CefriumProfile profile = profile();
        try (LocalHttpServer server = new LocalHttpServer(PAGE, "/sw.js", SW)) {
            open(profile, server.url(), "subscribed");
            // Only an app id that reached the disk can be left behind on it.
            Assert.assertEquals("the page did not register", 1, sTokens.size());
            long written = System.currentTimeMillis() + 20_000;
            while (!onDisk(sTokens.get(0)) && System.currentTimeMillis() < written) {
                Thread.sleep(100);
            }
            Assert.assertTrue("the subscription's app id never reached the disk",
                    onDisk(sTokens.get(0)));
            open(profile, server.url() + "?unsubscribe", "unsubscribed");
            // The page is told before the InstanceID is deleted.
            long deadline = System.currentTimeMillis() + 10_000;
            while (sStillOnDiskAtDelete.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            Assert.assertEquals("the InstanceID was never deleted", 1, sStillOnDiskAtDelete.size());
            Assert.assertFalse("the InstanceID was deleted while its app id was still on disk",
                    sStillOnDiskAtDelete.get(0));
        }
    }
}
