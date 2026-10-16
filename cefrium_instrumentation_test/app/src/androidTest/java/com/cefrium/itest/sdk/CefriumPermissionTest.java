package com.cefrium.itest.sdk;

import com.cefrium.itest.HostActivity;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.itest.LocalHttpServer;

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
 * CEF-Android -- see FEATURE-032).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumPermissionTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    // Grant the OS-level location permission so a web-granted geolocation can
    // reach the provider (otherwise the system layer denies -> err:1).
    @Rule
    public GrantPermissionRule locationPermission = GrantPermissionRule.grant(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION);

    /** A handler that grants must be consulted, and geolocation must proceed
     *  past the permission gate (result is NOT PERMISSION_DENIED, no crash). */
    // Distinct secure origins per test: geolocation decisions persist per-origin
    // in the shared profile, so the grant and deny tests must not share an origin.
    // Each runGeolocation() starts its own loopback server -- a fresh
    // 127.0.0.1:<port> origin (localhost is the secure context geolocation needs),
    // so the two tests are isolated, with no external site.

    @Test
    public void testGeolocationGrantedReachesProvider() throws Exception {
        String geo = runGeolocation(/*grant=*/true);
        // Granted: must not be permission-denied (err code 1). With no OS
        // location permission in the harness it will be err:2 (unavailable) or
        // err:3 (timeout), or geo:ok -- any of these proves the gate was passed.
        Assert.assertNotNull("no geolocation result (request hung/crashed)", geo);
        Assert.assertFalse("granted request should not be PERMISSION_DENIED: " + geo,
                geo.startsWith("geo:err:1"));
    }

    /** A handler that denies must produce PERMISSION_DENIED (err code 1). */
    @Test
    public void testGeolocationDenied() throws Exception {
        String geo = runGeolocation(/*grant=*/false);
        Assert.assertNotNull("no geolocation result (request hung/crashed)", geo);
        Assert.assertTrue("denied request should be PERMISSION_DENIED: " + geo,
                geo.startsWith("geo:err:1"));
    }

    /**
     * Issue #12: an OSR browser must survive a reload of a page it granted
     * geolocation to.
     *
     * Chromium's ShouldRepromptUserForPermissions() dereferenced
     * web_contents->GetNativeView() without checking it, and an off-screen
     * WebContents has no native view. The call sits behind
     * `status == GRANTED &&`, so only the SECOND request reaches it -- which is
     * why this test has to grant, wait for the result, reload, and then wait
     * again. One request proves nothing.
     *
     * This must be an OSR browser (`new CefriumBrowser(w, h)`), not
     * createWithSurface: a surface-backed browser HAS a WindowAndroid and never
     * saw this crash, which is why the tests above missed it entirely.
     *
     * Without the fix the browser process dies with a SIGSEGV, so the failure
     * mode is the runner reporting a crashed process rather than an assertion.
     */
    @Test
    public void testGeolocationReloadAfterGrantDoesNotCrashOsr() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch second = new CountDownLatch(1);
        final AtomicReference<String> firstResult = new AtomicReference<>(null);
        final AtomicReference<String> secondResult = new AtomicReference<>(null);
        final AtomicInteger loads = new AtomicInteger(0);
        final AtomicReference<CefriumBrowser> ref = new AtomicReference<>(null);
        final LocalHttpServer server = new LocalHttpServer(
                "<!doctype html><title>geo-reload</title><body>geo</body>");

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = new CefriumBrowser(720, 1280);
            ref.set(browser);
            browser.setPermissionHandler((origin, permissions, callback) -> callback.respond(true));
            browser.setQueryHandler((queryId, request, origin, callback) -> {
                if (request == null || !request.startsWith("geo:")) {
                    return false;
                }
                callback.success("ack");
                if (firstResult.compareAndSet(null, request)) {
                    first.countDown();
                } else {
                    secondResult.set(request);
                    second.countDown();
                }
                return true;
            });
            browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (loading || loads.incrementAndGet() > 2) {
                    return;
                }
                browser.evaluateJavaScript(
                    "navigator.geolocation.getCurrentPosition("
                    + "  function(p){window.cefriumQuery({request:'geo:ok'});},"
                    + "  function(e){window.cefriumQuery({request:'geo:err:'+e.code});},"
                    + "  {timeout:8000,maximumAge:0});");
            });
            browser.loadUrl(server.url());
        });

        try {
            Assert.assertTrue("no first geolocation result (request hung)",
                    first.await(40, TimeUnit.SECONDS));
            // The site setting must be stored before the reload, or the second
            // load short-circuits exactly like the first and nothing is proven.
            Assert.assertFalse("first request was denied, so the second one will "
                            + "not reach the regressed code path: " + firstResult.get(),
                    firstResult.get().startsWith("geo:err:1"));

            activity.runOnUiThread(() -> ref.get().reload());

            Assert.assertTrue("no geolocation result after the reload -- the browser"
                            + " process almost certainly crashed; check"
                            + " `dumpsys activity exit-info` for reason=5",
                    second.await(40, TimeUnit.SECONDS));
            // Surviving is the point, but a reload that silently started
            // denying would also be a regression, and the remembered ALLOW is
            // what put us on the crashing path in the first place.
            Assert.assertFalse("after the reload the stored grant was not honoured: "
                            + secondResult.get(),
                    secondResult.get().startsWith("geo:err:1"));
        } finally {
            server.close();
            activity.runOnUiThread(() -> {
                CefriumBrowser b = ref.getAndSet(null);
                if (b != null) {
                    b.close();
                }
            });
        }
    }

    private String runGeolocation(boolean grant) throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>(null);
        final AtomicInteger seenPermissions = new AtomicInteger(-1);
        final boolean[] probed = {false};
        final LocalHttpServer server = new LocalHttpServer(
                "<!doctype html><title>geo</title><body>geo</body>");
        final String url = server.url();

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
        server.close();
        // The handler must have been consulted with the geolocation bit set.
        Assert.assertTrue("PermissionHandler was not consulted (perms="
                        + seenPermissions.get() + ")",
                (seenPermissions.get() & CefriumBrowser.PERMISSION_GEOLOCATION) != 0);
        return arrived ? result.get() : null;
    }
}
