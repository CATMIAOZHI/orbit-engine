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
import com.cefrium.itest.NotificationPermission;

import org.chromium.gms.ChromiumPlayServicesAvailability;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An app may ship without the Google libraries (run-instrumentation-suite.sh
 * with CEFRIUM_NO_GMS=1). The engine must then see Play services as missing,
 * and a page's Web Push subscribe, with no transport installed, must fail
 * with an error the page can handle instead of taking the app down (the FCM
 * InstanceID class is missing). With the libraries, the same tests check that
 * the engine sees them.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumNoGmsTest {

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
            + "var key=Uint8Array.from(atob('" + SERVER_KEY + "'.replace(/-/g,'+').replace(/_/g,'/')),"
            + "function(c){return c.charCodeAt(0);});"
            + "await reg.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:key});"
            + "document.title='subscribed';}"
            + "go().catch(function(e){document.title='error:'+e.name;});"
            + "</script></body></html>";
    private static final String SW = "self.addEventListener('push',function(e){});";

    private static boolean librariesInApp() {
        try {
            Class.forName("com.google.android.gms.common.GoogleApiAvailability");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    public void engineSeesWhetherTheGoogleLibrariesAreThere() {
        Assert.assertEquals(librariesInApp(), ChromiumPlayServicesAvailability.isGmsLinked());
        if (!librariesInApp()) {
            Assert.assertFalse(ChromiumPlayServicesAvailability.isGooglePlayServicesAvailable(
                    activityRule.getActivity()));
        }
    }

    @Test
    public void pushWithoutGoogleOrTransportFailsInThePage() throws Exception {
        if (librariesInApp()) {
            // The default transport is FCM, which this suite does not reach
            // (CefriumWebPushTest fakes it).
            return;
        }
        Activity activity = activityRule.getActivity();
        AtomicReference<String> title = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try (LocalHttpServer server = new LocalHttpServer(PAGE, "/sw.js", SW)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser browser = browsers.createWithSurface(activity);
                activity.setContentView(browser.getSurfaceContainer());
                browser.setContentSetting(CefriumBrowser.QueryOriginPolicy.originOf(server.url()),
                        CefriumBrowser.PERMISSION_NOTIFICATIONS, CefriumBrowser.SETTING_ALLOW);
                browser.setOnTitleChangedListener(t -> {
                    title.set(t);
                    if (t != null && (t.equals("subscribed") || t.startsWith("error:"))) {
                        done.countDown();
                    }
                });
                browser.loadUrl(server.url());
            });
            Assert.assertTrue("the page's subscribe never ended; title=" + title.get(),
                    done.await(30, TimeUnit.SECONDS));
            Assert.assertEquals("error:AbortError", title.get());
        }
    }
}
