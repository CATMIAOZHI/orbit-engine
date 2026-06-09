package com.cefrium.itest.sdk;

import com.cefrium.itest.HostActivity;

import android.app.Activity;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP Basic authentication via CefRequestHandler::GetAuthCredentials routed to
 * CefriumBrowser.setHttpAuthHandler (WebView onReceivedHttpAuthRequest parity).
 *
 * Hermetic: a tiny in-process HTTP server on 127.0.0.1 issues the 401 Basic
 * challenge and returns an authenticated body once the right credentials arrive.
 * No external service -- an earlier version hit httpbin.org and failed the
 * release gate whenever the test device could not reach it; a gate must be
 * hermetic (a green gate means the code is good, never that a site was up).
 *
 * Core property: the 401 challenge reaches the app's HttpAuthHandler and the
 * supplied credentials satisfy it (the page loads with the authenticated body).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumHttpAuthTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    private static final String USER = "cefrium";
    private static final String PASS = "s3cret";

    private ServerSocket server;
    private volatile boolean running;
    private int port;

    /** Loopback HTTP server: 401 Basic challenge until the right creds arrive. */
    @Before
    public void startLocalServer() throws Exception {
        server = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        running = true;
        final String expected = "Basic " + Base64.encodeToString(
                (USER + ":" + PASS).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        Thread t = new Thread(() -> {
            while (running) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            s.getInputStream(), StandardCharsets.UTF_8));
                    boolean authed = false;
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        int c = line.indexOf(':');
                        if (c > 0
                                && line.substring(0, c).trim().equalsIgnoreCase("Authorization")
                                && line.substring(c + 1).trim().equals(expected)) {
                            authed = true;
                        }
                    }
                    OutputStream out = s.getOutputStream();
                    if (authed) {
                        byte[] body = "<html><body>{\"authenticated\": true}</body></html>"
                                .getBytes(StandardCharsets.UTF_8);
                        out.write(("HTTP/1.1 200 OK\r\n"
                                + "Content-Type: text/html\r\n"
                                + "Content-Length: " + body.length + "\r\n"
                                + "Connection: close\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                        out.write(body);
                    } else {
                        out.write(("HTTP/1.1 401 Unauthorized\r\n"
                                + "WWW-Authenticate: Basic realm=\"Cefrium Test\"\r\n"
                                + "Content-Length: 0\r\n"
                                + "Connection: close\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                    }
                    out.flush();
                } catch (Exception ignored) {
                    // accept() throws on shutdown close(); a partial request just
                    // drops -- keep serving until running is cleared.
                }
            }
        }, "cefrium-auth-test-server");
        t.setDaemon(true);
        t.start();
    }

    @After
    public void stopLocalServer() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
    }

    /** The 401 challenge is delivered to the handler and the supplied
     *  credentials authenticate (the local server returns the authenticated body). */
    @Test
    public void testHttpAuthConsultedAndCredentialsAuthenticate() throws Exception {
        final String authUrl = "http://127.0.0.1:" + port + "/";
        Activity activity = activityRule.getActivity();
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicBoolean consulted = new AtomicBoolean(false);
        final AtomicReference<String> seenHost = new AtomicReference<>(null);
        final AtomicReference<String> result = new AtomicReference<>(null);
        final boolean[] probed = {false};

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            browser.setHttpAuthHandler((host, realm, p, isProxy, callback) -> {
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
                    browser.evaluateJavaScript(
                        "window.cefriumQuery({request:'auth:'+document.body.innerText});");
                }
            });
            browser.loadUrl(authUrl);
        });

        boolean arrived = got.await(40, TimeUnit.SECONDS);
        Assert.assertTrue("HttpAuthHandler was not consulted on a 401 challenge",
                consulted.get());
        Assert.assertNotNull("auth challenge host not delivered to the handler",
                seenHost.get());
        Assert.assertTrue("handler host should be the local server: " + seenHost.get(),
                seenHost.get().contains("127.0.0.1"));
        Assert.assertTrue("credentials did not satisfy the challenge (no result)",
                arrived);
        Assert.assertTrue("expected authenticated:true after supplying creds: "
                        + result.get(),
                result.get() != null && result.get().contains("authenticated"));
    }
}
