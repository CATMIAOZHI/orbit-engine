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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEATURE-032: web permission routing via CefPermissionHandler on Android.
 *
 * navigator.geolocation requires a secure context, so the probe loads an https
 * page and triggers getCurrentPosition, relaying the outcome through the JS
 * bridge. The key property: the request reaches the app's PermissionHandler and
 * is resolved WITHOUT crashing (the default Android permission dialog crashes
 * CEF-Android — see FEATURE-032).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumPermissionTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    // Grant the OS-level location permission so a web-granted geolocation can
    // reach the provider (otherwise the system layer denies → err:1).
    @Rule
    public GrantPermissionRule locationPermission = GrantPermissionRule.grant(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION);

    /** A handler that grants must be consulted, and geolocation must proceed
     *  past the permission gate (result is NOT PERMISSION_DENIED, no crash). */
    // Distinct secure origins per test: geolocation decisions persist per-origin
    // in the shared profile, so reusing one origin would let the deny test's
    // persisted block poison the grant test (and vice versa).
    private static final String GRANT_URL = "https://cefrium.com/";
    private static final String DENY_URL = "https://codeberg.org/";

    @Test
    public void testGeolocationGrantedReachesProvider() throws Exception {
        String geo = runGeolocation(/*grant=*/true, GRANT_URL);
        // Granted: must not be permission-denied (err code 1). With no OS
        // location permission in the harness it will be err:2 (unavailable) or
        // err:3 (timeout), or geo:ok — any of these proves the gate was passed.
        Assert.assertNotNull("no geolocation result (request hung/crashed)", geo);
        Assert.assertFalse("granted request should not be PERMISSION_DENIED: " + geo,
                geo.startsWith("geo:err:1"));
    }

    /** A handler that denies must produce PERMISSION_DENIED (err code 1). */
    @Test
    public void testGeolocationDenied() throws Exception {
        String geo = runGeolocation(/*grant=*/false, DENY_URL);
        Assert.assertNotNull("no geolocation result (request hung/crashed)", geo);
        Assert.assertTrue("denied request should be PERMISSION_DENIED: " + geo,
                geo.startsWith("geo:err:1"));
    }

    private String runGeolocation(boolean grant, String url) throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>(null);
        final AtomicInteger seenPermissions = new AtomicInteger(-1);
        final boolean[] probed = {false};

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            browser.setPermissionHandler((origin, permissions, callback) -> {
                seenPermissions.set(permissions);
                callback.respond(grant);
            });
            browser.setQueryHandler((queryId, request, origin, callback) -> {
                if (request != null && request.startsWith("geo:")) {
                    result.set(request);
                    callback.success("ack");
                    got.countDown();
                    return true;
                }
                return false;
            });
            browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    browser.evaluateJavaScript(
                        "(function(){"
                        + "if(!navigator.geolocation){"
                        + "  window.cefriumQuery({request:'geo:undefined'});return;}"
                        + "navigator.geolocation.getCurrentPosition("
                        + "  function(p){window.cefriumQuery({request:'geo:ok:'+p.coords.latitude});},"
                        + "  function(e){window.cefriumQuery({request:'geo:err:'+e.code+':'+e.message});},"
                        + "  {timeout:8000,maximumAge:0,enableHighAccuracy:false});"
                        + "})();");
                }
            });
            browser.loadUrl(url);
        });

        boolean arrived = got.await(40, TimeUnit.SECONDS);
        // The handler must have been consulted with the geolocation bit set.
        Assert.assertTrue("PermissionHandler was not consulted (perms="
                        + seenPermissions.get() + ")",
                (seenPermissions.get() & CefriumBrowser.PERMISSION_GEOLOCATION) != 0);
        return arrived ? result.get() : null;
    }
}
