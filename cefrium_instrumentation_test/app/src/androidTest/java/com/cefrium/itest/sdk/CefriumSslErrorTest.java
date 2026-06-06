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

    // Self-signed certificate → fails validation → OnCertificateError fires.
    private static final String BAD_CERT_URL = "https://self-signed.badssl.com/";

    /** Proceed: the handler is consulted and the self-signed page then loads. */
    @Test
    public void testSslErrorConsultedAndProceedLoads() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicBoolean consulted = new AtomicBoolean(false);
        final AtomicReference<String> seenUrl = new AtomicReference<>(null);
        final AtomicReference<String> result = new AtomicReference<>(null);
        final boolean[] probed = {false};

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
            browser.loadUrl(BAD_CERT_URL);
        });

        boolean arrived = got.await(40, TimeUnit.SECONDS);
        Assert.assertTrue("SslErrorHandler was not consulted for an invalid cert",
                consulted.get());
        Assert.assertNotNull("invalid-cert URL not delivered to the handler",
                seenUrl.get());
        Assert.assertTrue("handler URL should be the badssl host: " + seenUrl.get(),
                seenUrl.get().contains("badssl.com"));
        Assert.assertTrue("proceed() did not continue the load (no page result)",
                arrived);
        Assert.assertTrue("self-signed page did not render after proceed: "
                        + result.get(),
                result.get() != null && result.get().contains("badssl.com"));
    }
}
