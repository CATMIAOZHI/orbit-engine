// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;
import com.cefrium.itest.NotificationPermission;

import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;
import com.cefrium.CefriumContentSetting;
import com.cefrium.CefriumPermission;
import com.cefrium.CefriumPermissionRequest;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Permissions through CefriumChromeClient: a typed request (origin and a set
 * of CefriumPermission) answered from any thread, denied by default, and
 * deciding instead of a PermissionHandler; and setContentSetting by
 * permission, where the clipboard no longer sets notifications. Each test
 * runs its own loopback server, so its origin is fresh: decisions persist per
 * origin in the profile.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumChromeClientPermissionTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    @Rule
    public GrantPermissionRule location = GrantPermissionRule.grant(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION);

    @Rule
    public TestRule notifications = NotificationPermission.grant();

    private static final String GEO = "(function(){"
            + "navigator.geolocation.getCurrentPosition("
            + "  function(p){window.cefriumQuery({request:'r:ok'});},"
            + "  function(e){window.cefriumQuery({request:'r:err:'+e.code});},"
            + "  {timeout:8000,maximumAge:0});})();";

    private static final String NOTIFICATION_STATE =
            "window.cefriumQuery({request:'r:'+Notification.permission});";

    /**
     * Loads a fresh origin (|setup| gets the browser and its URL first), runs
     * |js| after the load, returns its "r:" answer.
     */
    private String probe(String js, BiConsumer<CefriumBrowser, String> setup) throws Exception {
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        boolean[] probed = {false};
        try (LocalHttpServer server = new LocalHttpServer("<!doctype html><title>p</title>")) {
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activityRule.getActivity());
                setup.accept(b, server.url());
                b.setQueryHandler(CefriumBrowser.QueryOriginPolicy.anyOrigin(),
                        (id, request, origin, cb) -> {
                            if (request == null || !request.startsWith("r:")) return false;
                            result.set(request.substring(2));
                            cb.success("ack");
                            got.countDown();
                            return true;
                        });
                b.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                    if (!loading && !probed[0]) {
                        probed[0] = true;
                        b.evaluateJavaScript(js);
                    }
                });
                b.loadUrl(server.url());
            });
            Assert.assertTrue("the page never answered", got.await(40, TimeUnit.SECONDS));
            return result.get();
        }
    }

    @Test
    public void typedRequestGrantedFromAnotherThread() throws Exception {
        AtomicReference<CefriumPermissionRequest> seen = new AtomicReference<>();
        AtomicReference<Boolean> onUi = new AtomicReference<>();
        String geo = probe(GEO, (b, url) -> b.setChromeClient(new CefriumChromeClient() {
            @Override
            public void onPermissionRequest(CefriumBrowser browser,
                    CefriumPermissionRequest request) {
                onUi.set(Looper.myLooper() == Looper.getMainLooper());
                seen.set(request);
                new Thread(request::grant).start();
            }
        }));
        Assert.assertNotNull("the chrome client was not asked", seen.get());
        Assert.assertTrue("asked off the UI thread", onUi.get());
        Assert.assertEquals(EnumSet.of(CefriumPermission.GEOLOCATION),
                seen.get().getPermissions());
        Assert.assertTrue(seen.get().getOrigin(),
                seen.get().getOrigin().startsWith("http://127.0.0.1:"));
        // Granted: anything but PERMISSION_DENIED (1) proves the gate was passed.
        Assert.assertNotEquals("err:1", geo);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void deniedByDefaultAndDecidesInsteadOfAHandler() throws Exception {
        boolean[] handlerAsked = {false};
        String geo = probe(GEO, (b, url) -> {
            b.setPermissionHandler((origin, permissions, callback) -> {
                handlerAsked[0] = true;
                callback.respond(true);
            });
            b.setChromeClient(new CefriumChromeClient() {});
        });
        Assert.assertEquals("err:1", geo);
        Assert.assertFalse("the PermissionHandler decided over the chrome client",
                handlerAsked[0]);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void clipboardSettingDoesNotAllowNotifications() throws Exception {
        // The instrument first: allowing notifications shows as "granted".
        String allowed = probe(NOTIFICATION_STATE, (b, url) -> b.setContentSetting(
                url, CefriumPermission.NOTIFICATIONS, CefriumContentSetting.ALLOW));
        Assert.assertEquals("granted", allowed);
        // The clipboard used to fall through to notifications.
        String clipboard = probe(NOTIFICATION_STATE, (b, url) -> b.setContentSetting(
                url, CefriumBrowser.PERMISSION_CLIPBOARD, CefriumBrowser.SETTING_ALLOW));
        Assert.assertEquals("default", clipboard);
    }
}
