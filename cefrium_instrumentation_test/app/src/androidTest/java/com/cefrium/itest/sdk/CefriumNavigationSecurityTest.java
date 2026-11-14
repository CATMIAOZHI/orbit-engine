// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;
import com.cefrium.itest.LocalHttpsServer;

import android.os.Looper;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumNavigationClient;
import com.cefrium.CefriumSslError;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Certificate errors and HTTP authentication through CefriumNavigationClient:
 * the error carries the net code and the server's certificate (the same bytes
 * as the test keystore's), proceeding from another thread loads the page; an
 * auth challenge carries host and realm, and the credentials given reach the
 * server.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumNavigationSecurityTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private final List<String> titles = new CopyOnWriteArrayList<>();

    private void awaitTitle(String t) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + 30000;
        while (SystemClock.uptimeMillis() < end) {
            if (titles.contains(t)) return;
            Thread.sleep(100);
        }
        Assert.fail("title never became " + t + "; titles " + titles);
    }

    // |incognito|: a proceeded certificate is remembered for the host in its
    // profile, and would hide the next test's certificate error (it did).
    private void open(String url, boolean incognito, CefriumNavigationClient client) {
        activityRule.getActivity().runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(400, 300, 1.0f,
                    incognito ? com.cefrium.CefriumProfile.incognito()
                            : com.cefrium.CefriumProfile.getDefault()));
            b.setOnTitleChangedListener(titles::add);
            b.setNavigationClient(client);
            b.loadUrl(url);
        });
    }

    @Test
    public void sslErrorCarriesTheCertificate() throws Exception {
        char[] password = "changeit".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("test-selfsigned.p12")) {
            ks.load(in, password);
        }
        Certificate expected = ks.getCertificate(ks.aliases().nextElement());
        AtomicReference<CefriumSslError> seen = new AtomicReference<>();
        AtomicReference<Boolean> onUi = new AtomicReference<>();
        InputStream in = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("test-selfsigned.p12");
        try (LocalHttpsServer server = new LocalHttpsServer(in, password,
                "<!doctype html><title>secure-page</title>")) {
            in.close();
            open(server.url(), true, new CefriumNavigationClient() {
                @Override
                public void onSslError(CefriumBrowser b, CefriumSslError error,
                        CefriumBrowser.SslErrorCallback callback) {
                    onUi.set(Looper.myLooper() == Looper.getMainLooper());
                    seen.set(error);
                    new Thread(callback::proceed).start();
                }
            });
            awaitTitle("secure-page");
        }
        CefriumSslError error = seen.get();
        Assert.assertNotNull("the navigation client was not asked", error);
        Assert.assertTrue("asked off the UI thread", onUi.get());
        Assert.assertEquals("ERR_CERT_AUTHORITY_INVALID", -202, error.getErrorCode());
        Assert.assertTrue(error.getUrl(), error.getUrl().startsWith("https://127.0.0.1:"));
        Assert.assertNotNull("no certificate", error.getCertificate());
        Assert.assertTrue("not the server's certificate",
                Arrays.equals(expected.getEncoded(), error.getCertificate().getEncoded()));
    }

    @Test
    public void httpAuthCredentialsReachTheServer() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer(
                "<!doctype html><title>authed</title>")) {
            open(server.url() + "auth/page", false, new CefriumNavigationClient() {
                @Override
                public void onHttpAuthRequest(CefriumBrowser b, String host, String realm,
                        int port, boolean isProxy, CefriumBrowser.HttpAuthCallback callback) {
                    seen.set((Looper.myLooper() == Looper.getMainLooper() ? "" : "OFF-UI ")
                            + host + " " + realm + " " + isProxy);
                    new Thread(() -> callback.proceed("u", "p")).start();
                }
            });
            awaitTitle("authed");
            Assert.assertEquals("127.0.0.1 " + LocalHttpServer.REALM + " false", seen.get());
            Assert.assertTrue("the credentials did not reach the server: " + server.headers,
                    server.headers.contains("/auth/page Authorization: Basic dTpw"));
        }
    }
}
