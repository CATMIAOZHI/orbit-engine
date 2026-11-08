// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumIdentityHandler;
import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import org.json.JSONObject;
import org.junit.Assert;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEATURE-086: a FedCM request (navigator.credentials.get({identity})) shows
 * its account chooser through CefriumIdentityHandler, and the account chosen
 * there signs the page in. A hermetic identity provider on localhost, the
 * relying party on 127.0.0.1 (another site). Surface and off-screen.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumIdentityTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private final List<String> idpLog = new CopyOnWriteArrayList<>();

    /** A minimal FedCM identity provider on http://localhost:<port>. */
    private final class Idp implements AutoCloseable {
        final ServerSocket server;
        final int port;
        volatile boolean running = true;

        Idp() throws Exception {
            server = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
            port = server.getLocalPort();
            Thread t = new Thread(this::serve, "test-idp");
            t.setDaemon(true);
            t.start();
        }

        private void serve() {
            while (running) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            s.getInputStream(), StandardCharsets.UTF_8));
                    String req = in.readLine();
                    String line;
                    String origin = "";
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("origin:")) {
                            origin = line.substring(7).trim();
                        }
                    }
                    String path = req == null ? "" : req.split(" ")[1].split("\\?")[0];
                    idpLog.add(path);
                    String body;
                    switch (path) {
                        case "/.well-known/web-identity":
                            body = "{\"provider_urls\":[\"http://localhost:" + port
                                    + "/config.json\"]}";
                            break;
                        case "/config.json":
                            body = "{\"accounts_endpoint\":\"/accounts\","
                                    + "\"id_assertion_endpoint\":\"/assert\","
                                    + "\"login_url\":\"/login\"}";
                            break;
                        case "/accounts":
                            body = "{\"accounts\":[{\"id\":\"1\",\"name\":\"Test User\","
                                    + "\"email\":\"user@example.test\",\"given_name\":\"Test\"},"
                                    + "{\"id\":\"2\",\"name\":\"Other User\","
                                    + "\"email\":\"other@example.test\"}]}";
                            break;
                        case "/assert":
                            body = "{\"token\":\"token-for-account\"}";
                            break;
                        default:
                            body = "{}";
                    }
                    byte[] b = body.getBytes(StandardCharsets.UTF_8);
                    String cors = origin.isEmpty() ? "" : "Access-Control-Allow-Origin: "
                            + origin + "\r\nAccess-Control-Allow-Credentials: true\r\n";
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" + cors
                            + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.write(b);
                    out.flush();
                } catch (Exception ignored) {
                    // accept() throws on close(); a partial request just drops.
                }
            }
        }

        @Override
        public void close() throws Exception {
            running = false;
            server.close();
        }
    }

    private CefriumBrowser open(boolean offscreen, String url, CefriumIdentityHandler h)
            throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<CefriumBrowser> created = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            CefriumBrowser b = offscreen
                    ? browsers.track(new CefriumBrowser(400, 300, 1.0f))
                    : browsers.createWithSurface(activity);
            created.set(b);
            b.setIdentityHandler(h);
            b.setOnTitleChangedListener(t -> {
                if ("ready".equals(t)) ready.countDown();
            });
            b.loadUrl(url);
        });
        Assert.assertTrue("the page did not load", ready.await(30, TimeUnit.SECONDS));
        return created.get();
    }

    // The outcome of the page's request: "resolved:<token>" or "rejected:<name>".
    private String request(CefriumBrowser b, int port) throws Exception {
        String js = "navigator.credentials.get({identity:{providers:[{"
                + "configURL:'http://localhost:" + port + "/config.json',clientId:'c'}]}})"
                + ".then(c => 'resolved:' + c.token, e => 'rejected:' + e.name)";
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        activityRule.getActivity().runOnUiThread(() -> b.executeDevToolsMethod(
                "Runtime.evaluate",
                "{\"expression\":" + JSONObject.quote(js)
                        + ",\"awaitPromise\":true,\"userGesture\":true}",
                (ok, json) -> {
                    try {
                        value.set(new JSONObject(json).getJSONObject("result")
                                .getString("value"));
                    } catch (Exception e) {
                        value.set("devtools: " + json);
                    }
                    done.countDown();
                }));
        Assert.assertTrue("the request never settled; idp saw " + idpLog,
                done.await(30, TimeUnit.SECONDS));
        return value.get();
    }

    private static final String PAGE =
            "<html><body><script>document.title='ready'</script></body></html>";

    private void chosenAccountSignsIn(boolean offscreen) throws Exception {
        AtomicReference<String> rp = new AtomicReference<>();
        List<CefriumIdentityHandler.Account> offered = new CopyOnWriteArrayList<>();
        CefriumIdentityHandler handler = (relyingParty, accounts, req) -> {
            rp.set(relyingParty);
            offered.addAll(accounts);
            req.select(accounts.get(0));
            return true;
        };
        try (Idp idp = new Idp(); LocalHttpServer site = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = open(offscreen, site.url(), handler);
            String outcome = request(b, idp.port);
            Assert.assertEquals("idp saw " + idpLog, "resolved:token-for-account", outcome);
            Assert.assertEquals(2, offered.size());
            Assert.assertEquals("1", offered.get(0).id);
            Assert.assertEquals("Test User", offered.get(0).name);
            Assert.assertEquals("user@example.test", offered.get(0).email);
            Assert.assertEquals("localhost", offered.get(0).identityProvider);
            Assert.assertTrue("relying party " + rp.get(), rp.get().contains("127.0.0.1"));
            Assert.assertTrue(idpLog.toString(), idpLog.contains("/assert"));
        }
    }

    @Test
    public void chosenAccountSignsInSurface() throws Exception {
        chosenAccountSignsIn(false);
    }

    @Test
    public void chosenAccountSignsInOffscreen() throws Exception {
        chosenAccountSignsIn(true);
    }

    @Test
    public void cancelledChooserRejects() throws Exception {
        CefriumIdentityHandler handler = (relyingParty, accounts, req) -> {
            req.cancel();
            return true;
        };
        try (Idp idp = new Idp(); LocalHttpServer site = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = open(true, site.url(), handler);
            String outcome = request(b, idp.port);
            Assert.assertTrue(outcome, outcome.startsWith("rejected:"));
            Assert.assertFalse(idpLog.toString(), idpLog.contains("/assert"));
        }
    }

    // The page giving up (AbortController) closes the open chooser.
    @Test
    public void abortedRequestClosesTheChooser() throws Exception {
        CountDownLatch shown = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        CefriumIdentityHandler handler = new CefriumIdentityHandler() {
            @Override public boolean onShowAccountChooser(String relyingParty,
                    List<Account> accounts, Request request) {
                shown.countDown();
                return true;  // never answered
            }

            @Override public void onAccountChooserClosed(Request request) {
                closed.countDown();
            }
        };
        try (Idp idp = new Idp(); LocalHttpServer site = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = open(true, site.url(), handler);
            String js = "window.abort = new AbortController();"
                    + "navigator.credentials.get({signal: abort.signal, identity:{providers:[{"
                    + "configURL:'http://localhost:" + idp.port + "/config.json',"
                    + "clientId:'c'}]}}).catch(() => {});1";
            activityRule.getActivity().runOnUiThread(() -> b.executeDevToolsMethod(
                    "Runtime.evaluate", "{\"expression\":" + JSONObject.quote(js)
                            + ",\"userGesture\":true}", (ok, json) -> {}));
            Assert.assertTrue("no chooser; idp saw " + idpLog,
                    shown.await(20, TimeUnit.SECONDS));
            activityRule.getActivity().runOnUiThread(() -> b.executeDevToolsMethod(
                    "Runtime.evaluate", "{\"expression\":\"abort.abort();1\"}",
                    (ok, json) -> {}));
            Assert.assertTrue("onAccountChooserClosed not called",
                    closed.await(10, TimeUnit.SECONDS));
        }
    }
}
