// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.Cefrium;
import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumRenderHandler;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cefrium.setSecureDns: in secure mode name lookups go to the given DNS over
 * HTTPS server, and with secure DNS off they do not. The "server" is a socket
 * on 127.0.0.1 that only records being connected to; that is enough to tell
 * where the engine sends its lookups.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumSecureDnsTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    @After
    public void restoreTheDefault() throws Exception {
        Assert.assertTrue(onMain(() -> Cefrium.setSecureDns(
                com.cefrium.CefriumSecureDnsMode.AUTOMATIC, null)));
    }

    private boolean onMain(java.util.concurrent.Callable<Boolean> call) throws Exception {
        boolean[] result = new boolean[1];
        Exception[] error = new Exception[1];
        CountDownLatch done = new CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() -> {
            try {
                result[0] = call.call();
            } catch (Exception e) {
                error[0] = e;
            }
            done.countDown();
        });
        done.await(10, TimeUnit.SECONDS);
        if (error[0] != null) throw error[0];
        return result[0];
    }

    /** Whether anything connects to a fresh listener while a host is loaded. */
    private boolean lookupReachesTheServer(com.cefrium.CefriumSecureDnsMode mode, int waitSeconds)
            throws Exception {
        try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            String template = "https://127.0.0.1:" + server.getLocalPort() + "/dns-query{?dns}";
            Assert.assertTrue("the setting was refused",
                    onMain(() -> Cefrium.setSecureDns(mode, template)));
            AtomicBoolean connected = new AtomicBoolean();
            Thread accept = new Thread(() -> {
                try (Socket s = server.accept()) {
                    connected.set(true);
                } catch (Exception ignored) {
                    // close() ends the wait.
                }
            });
            accept.start();
            Activity activity = activityRule.getActivity();
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(400, 300, 1.0f));
                b.setRenderHandler(new CefriumRenderHandler() {
                    @Override
                    public void onPaint(int width, int height, ByteBuffer px) {}
                });
                b.loadUrl("http://cefrium-secure-dns-" + System.nanoTime() + ".example/");
            });
            accept.join(waitSeconds * 1000L);
            return connected.get();
        }
    }

    @Test
    public void secureModeSendsLookupsToTheServer() throws Exception {
        Assert.assertTrue("no lookup reached the DNS over HTTPS server",
                lookupReachesTheServer(com.cefrium.CefriumSecureDnsMode.SECURE, 15));
    }

    @Test
    public void offModeDoesNotUseTheServer() throws Exception {
        Assert.assertFalse("a lookup went to the DNS over HTTPS server with secure DNS off",
                lookupReachesTheServer(com.cefrium.CefriumSecureDnsMode.OFF, 6));
    }

    @Test
    public void invalidSettingsAreRefused() throws Exception {
        Assert.assertFalse(onMain(() -> Cefrium.setSecureDns("sometimes", null)));
        Assert.assertFalse("secure mode needs a server",
                onMain(() -> Cefrium.setSecureDns(Cefrium.SECURE_DNS_SECURE, null)));
        Assert.assertFalse("a template must be https",
                onMain(() -> Cefrium.setSecureDns(Cefrium.SECURE_DNS_SECURE,
                        "http://127.0.0.1/dns-query{?dns}")));
        Assert.assertTrue(onMain(() -> Cefrium.setSecureDns(Cefrium.SECURE_DNS_OFF, null)));
    }
}
