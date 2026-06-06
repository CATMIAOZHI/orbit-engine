package com.cefrium.itest.sdk;

import com.cefrium.itest.HostActivity;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP Basic authentication via CefRequestHandler::GetAuthCredentials routed to
 * CefriumBrowser.setHttpAuthHandler (WebView onReceivedHttpAuthRequest parity).
 * Uses httpbin.org/basic-auth, which answers 401 until the right credentials
 * are supplied, then returns {"authenticated": true}.
 *
 * Core property: a 401 challenge reaches the app's HttpAuthHandler, and the
 * supplied credentials actually satisfy the challenge (the page loads).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumHttpAuthTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    private static final String USER = "cefrium";
    private static final String PASS = "s3cret";
    private static final String AUTH_URL =
            "https://httpbin.org/basic-auth/" + USER + "/" + PASS;

    /** The 401 challenge is delivered to the handler and the supplied
     *  credentials authenticate (httpbin returns authenticated:true). */
    @Test
    public void testHttpAuthConsultedAndCredentialsAuthenticate() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicBoolean consulted = new AtomicBoolean(false);
        final AtomicReference<String> seenHost = new AtomicReference<>(null);
        final AtomicReference<String> result = new AtomicReference<>(null);
        final boolean[] probed = {false};

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            browser.setHttpAuthHandler((host, realm, port, isProxy, callback) -> {
                consulted.set(true);
                seenHost.set(host);
                callback.proceed(USER, PASS);
            });
            browser.setQueryHandler((queryId, request, origin, callback) -> {
                if (request != null && request.startsWith("auth:")) {
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
                    // After authenticating, httpbin returns a JSON body; relay it.
                    browser.evaluateJavaScript(
                        "window.cefriumQuery({request:'auth:'+document.body.innerText});");
                }
            });
            browser.loadUrl(AUTH_URL);
        });

        boolean arrived = got.await(40, TimeUnit.SECONDS);
        Assert.assertTrue("HttpAuthHandler was not consulted on a 401 challenge",
                consulted.get());
        Assert.assertNotNull("auth challenge host not delivered to the handler",
                seenHost.get());
        Assert.assertTrue("handler host should be httpbin.org: " + seenHost.get(),
                seenHost.get().contains("httpbin.org"));
        Assert.assertTrue("credentials did not satisfy the challenge (no result)",
                arrived);
        Assert.assertTrue("expected authenticated:true after supplying creds: "
                        + result.get(),
                result.get() != null && result.get().contains("authenticated"));
    }
}
