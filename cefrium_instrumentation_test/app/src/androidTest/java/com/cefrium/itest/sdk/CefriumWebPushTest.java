// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;
import com.cefrium.itest.NotificationPermission;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumProfile;
import com.cefrium.CefriumPush;

import org.chromium.components.gcm_driver.instance_id.InstanceIDWithSubtype;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEATURE-073: a Web Push message reaches the service worker of the profile
 * that subscribed, and only that one: the other profile's subscription
 * survives. A fake InstanceID stands in for FCM registration.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumWebPushTest {

    @Rule(order = 0)
    public TestRule notifications = NotificationPermission.grant();

    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    // A P-256 public key: subscribe() needs an applicationServerKey; the fake
    // subscriber never contacts a push service.
    private static final String SERVER_KEY =
            "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U";

    private static final String PAGE = "<html><body><script>"
            + "navigator.serviceWorker.onmessage=function(e){document.title='push:'+e.data;};"
            + "async function go(){"
            + "var reg=await navigator.serviceWorker.register('/sw.js');"
            + "await navigator.serviceWorker.ready;"
            + "var key=Uint8Array.from(atob('" + SERVER_KEY + "'.replace(/-/g,'+').replace(/_/g,'/')),"
            + "function(c){return c.charCodeAt(0);});"
            + "await reg.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:key});"
            + "document.title='subscribed';}"
            + "go().catch(function(e){document.title='error:'+e;});"
            + "</script></body></html>";
    private static final String SW = "self.addEventListener('push',function(e){"
            + "e.waitUntil(self.registration.showNotification('push').then(function(){"
            + "return clients.matchAll({includeUncontrolled:true});}).then(function(cs){"
            + "cs.forEach(function(c){c.postMessage('received');});}));});";

    // Each subscription's FCM subtype (its app id), and the ones deleted.
    private static final List<String> sTokens = new CopyOnWriteArrayList<>();
    private static final List<String> sDeleted = new CopyOnWriteArrayList<>();

    /** Web Push subscribes through InstanceID; this one never calls FCM. */
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
        public void deleteToken(String authorizedEntity, String scope) {
            sDeleted.add(mSubtype);
        }

        @Override
        public void deleteInstanceID() {
            sDeleted.add(mSubtype);
        }
    }

    private CefriumBrowser open(CefriumProfile profile, String url, AtomicReference<String> title,
                                String waitFor) throws Exception {
        Activity activity = activityRule.getActivity();
        final AtomicReference<CefriumBrowser> created = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity, profile);
            created.set(browser);
            // Push needs notification permission; an app grants its own pages
            // ahead of time, without a prompt.
            browser.setContentSetting(originOf(url), CefriumBrowser.PERMISSION_NOTIFICATIONS,
                    CefriumBrowser.SETTING_ALLOW);
            browser.setOnTitleChangedListener(t -> {
                title.set(t);
                if (t != null && (t.startsWith(waitFor) || t.startsWith("error:"))) done.countDown();
            });
            browser.loadUrl(url);
        });
        Assert.assertTrue("no '" + waitFor + "' from " + url + "; title=" + title.get(),
                done.await(30, TimeUnit.SECONDS));
        Assert.assertEquals(waitFor, title.get());
        return created.get();
    }

    private static String originOf(String url) {
        return CefriumBrowser.QueryOriginPolicy.originOf(url);
    }

    @Test
    public void messageReachesTheSubscribingProfileOnly() throws Exception {
        sTokens.clear();
        sDeleted.clear();
        InstanceIDWithSubtype.setFakeFactoryForTesting(FakeInstanceID::new);
        try {
            run();
        } finally {
            InstanceIDWithSubtype.setFakeFactoryForTesting(null);
        }
    }

    private void run() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        // The default profile and a named one: both subscriptions are routed.
        CefriumProfile a = CefriumProfile.getDefault();
        CefriumProfile b = CefriumProfile.get("push-b-" + suffix);
        try (LocalHttpServer server = new LocalHttpServer(PAGE, "/sw.js", SW)) {
            AtomicReference<String> titleA = new AtomicReference<>();
            AtomicReference<String> titleB = new AtomicReference<>();
            open(a, server.url(), titleA, "subscribed");
            Assert.assertEquals("profile A did not register with FCM", 1, sTokens.size());
            open(b, server.url(), titleB, "subscribed");
            Assert.assertEquals("profile B did not register with FCM", 2, sTokens.size());
            String appIdA = sTokens.get(0);
            String appIdB = sTokens.get(1);
            Assert.assertNotEquals(appIdA, appIdB);

            Bundle data = new Bundle();
            data.putString("subtype", appIdB);
            Assert.assertTrue("not recognised as Web Push",
                    CefriumPush.handleMessage(activityRule.getActivity(), "sender", data));

            long deadline = System.currentTimeMillis() + 20_000;
            while (!"push:received".equals(titleB.get()) && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            Assert.assertEquals("profile B's service worker did not get the push",
                    "push:received", titleB.get());
            Thread.sleep(2000);
            Assert.assertEquals("profile A got profile B's push", "subscribed", titleA.get());
            Assert.assertFalse("profile A's subscription was revoked", sDeleted.contains(appIdA));
            Assert.assertFalse("profile B's subscription was revoked", sDeleted.contains(appIdB));

            Bundle appOwn = new Bundle();
            appOwn.putString("title", "the app's own message");
            Assert.assertFalse("an app message was taken for Web Push",
                    CefriumPush.handleMessage(activityRule.getActivity(), "sender", appOwn));
        }
    }

    private static final String UNSUBSCRIBE_PAGE = "<html><body><script>"
            + "async function go(){"
            + "var reg=await navigator.serviceWorker.register('/sw.js');"
            + "await navigator.serviceWorker.ready;"
            + "var key=Uint8Array.from(atob('" + SERVER_KEY + "'.replace(/-/g,'+').replace(/_/g,'/')),"
            + "function(c){return c.charCodeAt(0);});"
            + "var sub=await reg.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:key});"
            + "document.title='subscribed';"
            + "await new Promise(function(r){setTimeout(r,3000);});"
            + "await sub.unsubscribe();"
            + "document.title='unsubscribed';}"
            + "go().catch(function(e){document.title='error:'+e;});"
            + "</script></body></html>";

    // The engine's routes live in the app's default preferences (what
    // ContextUtils.getAppSharedPreferences returns); read them through the
    // public API, which R8 does not rename.
    private static String routeOf(String appId) {
        return android.preference.PreferenceManager.getDefaultSharedPreferences(
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                                .getTargetContext().getApplicationContext())
                .getString("gcm_driver_route." + appId, null);
    }

    /** Unsubscribing removes the subscription's message route. */
    @Test
    public void unsubscribeClearsTheRoute() throws Exception {
        sTokens.clear();
        sDeleted.clear();
        InstanceIDWithSubtype.setFakeFactoryForTesting(FakeInstanceID::new);
        try {
            CefriumProfile p = CefriumProfile.get("push-u-" + UUID.randomUUID().toString().substring(0, 8));
            try (LocalHttpServer server = new LocalHttpServer(UNSUBSCRIBE_PAGE, "/sw.js", SW)) {
                AtomicReference<String> title = new AtomicReference<>();
                open(p, server.url(), title, "subscribed");
                Assert.assertEquals("did not register with FCM", 1, sTokens.size());
                String appId = sTokens.get(0);
                Assert.assertNotNull("subscribing recorded no route", routeOf(appId));

                // The engine may finish (registration deleted, route gone) before
                // the page's unsubscribe() promise resolves: wait for all three.
                long deadline = System.currentTimeMillis() + 20_000;
                while (!("unsubscribed".equals(title.get()) && sDeleted.contains(appId)
                                && routeOf(appId) == null)
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200);
                }
                Assert.assertEquals("page did not unsubscribe", "unsubscribed", title.get());
                Assert.assertTrue("the FCM registration was not deleted", sDeleted.contains(appId));
                Assert.assertNull("the route outlived the subscription", routeOf(appId));
            }
        } finally {
            InstanceIDWithSubtype.setFakeFactoryForTesting(null);
        }
    }
}
