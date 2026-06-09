package com.cefrium.itest.sdk;

import com.cefrium.itest.HostActivity;

import android.app.Activity;
import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.platform.app.InstrumentationRegistry;

import com.cefrium.CefriumBrowser;
import com.cefrium.itest.LocalHttpsServer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSL/certificate error handling via CefRequestHandler::OnCertificateError
 * routed to CefriumBrowser.setSslErrorHandler (WebView onReceivedSslError
 * parity). Uses the public badssl.com test hosts (self-signed / valid certs).
 *
 * Core property: an invalid certificate reaches the app's SslErrorHandler, and
 * the proceed decision actually continues the load (a self-signed page renders).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumSslErrorTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    // A local HTTPS server with a self-signed cert: Chromium validates the cert
    // even on 127.0.0.1, so it fails validation and OnCertificateError fires. No
    // external bad-cert host -- the gate must be hermetic.
    private static final String LOCAL_TITLE = "local-ssl-ok";

    /** Proceed: the handler is consulted and the self-signed page then loads. */
    @Test
    public void testSslErrorConsultedAndProceedLoads() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicBoolean consulted = new AtomicBoolean(false);
        final AtomicReference<String> seenUrl = new AtomicReference<>(null);
        final AtomicReference<String> result = new AtomicReference<>(null);
        final boolean[] probed = {false};
        Context testCtx = InstrumentationRegistry.getInstrumentation().getContext();
        InputStream ks = testCtx.getAssets().open("test-selfsigned.p12");
        final LocalHttpsServer server = new LocalHttpsServer(ks, "changeit".toCharArray(),
                "<!doctype html><title>" + LOCAL_TITLE + "</title><body>ssl</body>");
        ks.close();
        final String serverUrl = server.url();

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            browser.setSslErrorHandler((url, certError, callback) -> {
                consulted.set(true);
                seenUrl.set(url);
                callback.proceed();
            });
            browser.setQueryHandler((queryId, request, origin, callback) -> {
                if (request != null && request.startsWith("ssl:")) {
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
                    // After proceeding past the cert error the page renders;
                    // relay its title to prove the load actually completed.
                    browser.evaluateJavaScript(
                        "window.cefriumQuery({request:'ssl:'+document.title});");
                }
            });
            browser.loadUrl(serverUrl);
        });

        boolean arrived = got.await(40, TimeUnit.SECONDS);
        server.close();
        Assert.assertTrue("SslErrorHandler was not consulted for an invalid cert",
                consulted.get());
        Assert.assertNotNull("invalid-cert URL not delivered to the handler",
                seenUrl.get());
        Assert.assertTrue("handler URL should be the local server: " + seenUrl.get(),
                seenUrl.get().contains("127.0.0.1"));
        Assert.assertTrue("proceed() did not continue the load (no page result)",
                arrived);
        Assert.assertTrue("self-signed page did not render after proceed: "
                        + result.get(),
                result.get() != null && result.get().contains(LOCAL_TITLE));
    }
}
