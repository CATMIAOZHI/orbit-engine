// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumProfile;
import com.cefrium.CefriumRenderHandler;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Proxy and WebRTC settings of a profile. The "proxy" is a local server that
 * answers every request with a page titled "via-proxy"; proxied.test does not
 * resolve, so a page from it can only arrive through the proxy.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumProxyTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private static final String TARGET = "http://proxied.test/";
    private static final String NAMED = "proxytest";

    // The settings are saved with the profile: never leave one behind.
    @After
    public void restore() throws Exception {
        onMain(() -> {
            CefriumProfile.getDefault().clearProxy();
            CefriumProfile.get(NAMED).clearProxy();
            CefriumProfile.incognito().followDefaultProfile();
            CefriumProfile.getDefault().setWebRtcIpHandlingPolicy(CefriumProfile.WEBRTC_DEFAULT);
            CefriumProfile.getDefault().setProxyErrorListener(null);
            CefriumProfile.incognito().setProxyErrorListener(null);
            return true;
        });
    }

    private <T> T onMain(Supplier<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        activityRule.getActivity().runOnUiThread(() -> result.set(work.get()));
        long deadline = System.currentTimeMillis() + 10_000;
        while (result.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(20);
        return result.get();
    }

    private static String proxyAddress(LocalHttpServer proxy) {
        return proxy.origin().substring("http://".length());
    }

    /** Opens {@code url} in an off-screen browser; returns its title once it settles. */
    private String open(CefriumProfile profile, String url, java.util.function.Consumer<CefriumBrowser> setup)
            throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        activityRule.getActivity().runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f, profile));
            b.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer px) {}
            });
            b.setOnTitleChangedListener(title::set);
            if (setup != null) setup.accept(b);
            b.loadUrl(url);
        });
        return settle(title);
    }

    private static String settle(AtomicReference<String> title) throws Exception {
        // A page that cannot load shows the engine's error page, whose title is
        // the URL; give it time to get there.
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String t = title.get();
            if (t.equals("via-proxy") || t.equals("direct-ok") || t.startsWith("candidates:")) return t;
            Thread.sleep(100);
        }
        return title.get();
    }

    private static LocalHttpServer proxyServer() throws Exception {
        return new LocalHttpServer("<html><head><title>via-proxy</title></head></html>");
    }

    // Issue seen in an adopter: setProxy right after creating the browser
    // returned false, because the browser did not exist yet.
    @Test
    public void setProxyBeforeTheFirstLoadAppliesToIt() throws Exception {
        try (LocalHttpServer proxy = proxyServer()) {
            AtomicReference<Boolean> set = new AtomicReference<>();
            String title = open(CefriumProfile.getDefault(), TARGET,
                    b -> set.set(b.setProxy(proxyAddress(proxy))));
            Assert.assertEquals(Boolean.TRUE, set.get());
            Assert.assertEquals("via-proxy", title);
        }
    }

    @Test
    public void setProxyRightAfterCreateWithSurfaceAppliesToIt() throws Exception {
        try (LocalHttpServer proxy = proxyServer()) {
            AtomicReference<String> title = new AtomicReference<>("");
            AtomicReference<Boolean> set = new AtomicReference<>();
            Activity activity = activityRule.getActivity();
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(CefriumBrowser.createWithSurface(activity, false));
                set.set(b.setProxy(proxyAddress(proxy)));
                b.setOnTitleChangedListener(title::set);
                b.loadUrl(TARGET);
            });
            Assert.assertEquals("via-proxy", settle(title));
            Assert.assertEquals(Boolean.TRUE, set.get());
        }
    }

    @Test
    public void theProxyCoversEveryBrowserOfTheProfile() throws Exception {
        try (LocalHttpServer proxy = proxyServer()) {
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault().setProxy(proxyAddress(proxy))));
            Assert.assertEquals("via-proxy", open(CefriumProfile.getDefault(), TARGET, null));
            Assert.assertEquals("via-proxy", open(CefriumProfile.getDefault(), TARGET, null));
        }
    }

    @Test
    public void anIncognitoProxyStaysInIncognito() throws Exception {
        try (LocalHttpServer proxy = proxyServer()) {
            // Set while no private browser is open: kept for the next session.
            Assert.assertTrue(onMain(() -> CefriumProfile.incognito().setProxy(proxyAddress(proxy))));
            Assert.assertEquals("via-proxy", open(CefriumProfile.incognito(), TARGET, null));
            Assert.assertNotEquals("via-proxy", open(CefriumProfile.getDefault(), TARGET, null));
        }
    }

    @Test
    public void aNamedProfileProxyStaysInThatProfile() throws Exception {
        try (LocalHttpServer proxy = proxyServer()) {
            Assert.assertTrue(onMain(() -> CefriumProfile.get(NAMED).setProxy(proxyAddress(proxy))));
            Assert.assertEquals("via-proxy", open(CefriumProfile.get(NAMED), TARGET, null));
            Assert.assertNotEquals("via-proxy", open(CefriumProfile.getDefault(), TARGET, null));
        }
    }

    @Test
    public void loopbackIsNeverProxied() throws Exception {
        try (LocalHttpServer proxy = proxyServer();
             LocalHttpServer local = new LocalHttpServer(
                     "<html><head><title>direct-ok</title></head></html>")) {
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault().setProxy(proxyAddress(proxy))));
            Assert.assertEquals("direct-ok", open(CefriumProfile.getDefault(), local.url(), null));
        }
    }

    /** What a private browser does when only the default profile has a proxy. */
    @Test
    public void incognitoFollowsTheDefaultProfileProxy() throws Exception {
        try (LocalHttpServer proxy = proxyServer()) {
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault().setProxy(proxyAddress(proxy))));
            Assert.assertEquals("via-proxy", open(CefriumProfile.incognito(), TARGET, null));
        }
    }

    @Test
    public void incognitoFollowsTheDefaultAgainAfterFollowDefaultProfile() throws Exception {
        try (LocalHttpServer proxy = proxyServer()) {
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault().setProxy(proxyAddress(proxy))));
            Assert.assertTrue(onMain(() -> CefriumProfile.incognito().clearProxy()));
            Assert.assertNotEquals("via-proxy", open(CefriumProfile.incognito(), TARGET, null));
            Assert.assertTrue(onMain(() -> {
                CefriumProfile.incognito().followDefaultProfile();
                return true;
            }));
            Assert.assertEquals("via-proxy", open(CefriumProfile.incognito(), TARGET, null));
        }
    }

    // The typed settings (FEATURE-092): the same effect as the strings.
    @Test
    public void typedProxyModes() throws Exception {
        try (LocalHttpServer proxy = proxyServer()) {
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault().setProxy(
                    com.cefrium.CefriumProxyMode.FIXED, proxyAddress(proxy))));
            Assert.assertEquals("via-proxy", open(CefriumProfile.getDefault(), TARGET, null));
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault().setProxy(
                    com.cefrium.CefriumProxyMode.DIRECT, null)));
            Assert.assertNotEquals("via-proxy", open(CefriumProfile.getDefault(), TARGET, null));
        }
    }

    @Test
    public void typedWebRtcPolicy() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(CANDIDATES)) {
            // The instrument first: without the policy there are candidates.
            Assert.assertNotEquals("candidates:0",
                    open(CefriumProfile.getDefault(), server.url(), null));
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault().setWebRtcIpHandlingPolicy(
                    com.cefrium.CefriumWebRtcIpPolicy.DISABLE_NON_PROXIED_UDP)));
            Assert.assertEquals("candidates:0",
                    open(CefriumProfile.getDefault(), server.url(), null));
        }
    }

    // WebRTC is inherited the same way: a private tab must not gather the
    // addresses the default profile hides.
    @Test
    public void incognitoFollowsTheDefaultWebRtcPolicy() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(CANDIDATES)) {
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault().setWebRtcIpHandlingPolicy(
                    CefriumProfile.WEBRTC_DISABLE_NON_PROXIED_UDP)));
            Assert.assertEquals("candidates:0", open(CefriumProfile.incognito(), server.url(), null));
        }
    }

    // ----- fail closed ---------------------------------------------------------

    /** A server on the device's LAN address: reachable directly, and proxied. */
    private static final class LanServer implements AutoCloseable {
        final ServerSocket socket;
        final String url;

        LanServer() throws Exception {
            InetAddress lan = null;
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address) lan = a;
                }
            }
            Assert.assertNotNull("the device has no LAN address", lan);
            socket = new ServerSocket(0, 0, lan);
            url = "http://" + lan.getHostAddress() + ":" + socket.getLocalPort() + "/";
            Thread t = new Thread(() -> {
                byte[] body = "<html><head><title>direct-ok</title></head></html>"
                        .getBytes(StandardCharsets.UTF_8);
                while (!socket.isClosed()) {
                    try (Socket s = socket.accept()) {
                        s.getInputStream().read(new byte[4096]);
                        OutputStream out = s.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "
                                + body.length + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                        out.write(body);
                    } catch (Exception ignored) {
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }

    @Test
    public void anUnreachableProxyFailsClosed() throws Exception {
        try (LanServer lan = new LanServer(); ServerSocket closed = new ServerSocket(0)) {
            int deadPort = closed.getLocalPort();
            closed.close();
            // The control: without a proxy the LAN page loads.
            Assert.assertEquals("direct-ok", open(CefriumProfile.getDefault(), lan.url, null));
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault().setProxy("127.0.0.1:" + deadPort)));
            Assert.assertNotEquals("direct-ok", open(CefriumProfile.getDefault(), lan.url, null));
        }
    }

    // ----- proxy errors --------------------------------------------------------

    /** A proxy error as reported: browser, url, error, mainFrame. */
    private static final class Reported {
        final CefriumBrowser browser;
        final String url;
        final int error;
        final boolean mainFrame;

        Reported(CefriumBrowser browser, String url, int error, boolean mainFrame) {
            this.browser = browser;
            this.url = url;
            this.error = error;
            this.mainFrame = mainFrame;
        }
    }

    /** Loads TARGET in a browser of `profile` that has no listener of its own. */
    private CefriumBrowser loadWithoutListeners(CefriumProfile profile) {
        AtomicReference<CefriumBrowser> holder = new AtomicReference<>();
        CountDownLatch made = new CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f, profile));
            b.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer px) {}
            });
            b.loadUrl(TARGET);
            holder.set(b);
            made.countDown();
        });
        try {
            made.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return holder.get();
    }

    @Test
    public void anUnreachableProxyIsReportedToItsProfile() throws Exception {
        try (ServerSocket closed = new ServerSocket(0)) {
            int deadPort = closed.getLocalPort();
            closed.close();
            LinkedBlockingQueue<Reported> reported = new LinkedBlockingQueue<>();
            Assert.assertTrue(onMain(() -> {
                CefriumProfile.getDefault().setProxyErrorListener(
                        (b, url, error, mainFrame) -> reported.add(new Reported(b, url, error, mainFrame)));
                return CefriumProfile.getDefault().setProxy("127.0.0.1:" + deadPort);
            }));
            CefriumBrowser b = loadWithoutListeners(CefriumProfile.getDefault());
            Reported r = reported.poll(20, TimeUnit.SECONDS);
            Assert.assertNotNull("the proxy error was not reported", r);
            Assert.assertSame(b, r.browser);
            Assert.assertEquals(TARGET, r.url);
            Assert.assertEquals("ERR_PROXY_CONNECTION_FAILED", -130, r.error);
            Assert.assertTrue(r.mainFrame);
        }
    }

    @Test
    public void aWorkingProxyReportsNothing() throws Exception {
        try (LocalHttpServer proxy = proxyServer()) {
            LinkedBlockingQueue<Reported> reported = new LinkedBlockingQueue<>();
            Assert.assertTrue(onMain(() -> {
                CefriumProfile.getDefault().setProxyErrorListener(
                        (b, url, error, mainFrame) -> reported.add(new Reported(b, url, error, mainFrame)));
                return CefriumProfile.getDefault().setProxy(proxyAddress(proxy));
            }));
            Assert.assertEquals("via-proxy", open(CefriumProfile.getDefault(), TARGET, null));
            Assert.assertNull("a working proxy was reported", reported.poll(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void incognitoReportsToTheIncognitoProfile() throws Exception {
        try (ServerSocket closed = new ServerSocket(0)) {
            int deadPort = closed.getLocalPort();
            closed.close();
            LinkedBlockingQueue<Reported> toDefault = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<Reported> toIncognito = new LinkedBlockingQueue<>();
            Assert.assertTrue(onMain(() -> {
                CefriumProfile.getDefault().setProxyErrorListener(
                        (b, url, error, mainFrame) -> toDefault.add(new Reported(b, url, error, mainFrame)));
                CefriumProfile.incognito().setProxyErrorListener(
                        (b, url, error, mainFrame) -> toIncognito.add(new Reported(b, url, error, mainFrame)));
                // Incognito follows the default profile's proxy.
                return CefriumProfile.getDefault().setProxy("127.0.0.1:" + deadPort);
            }));
            loadWithoutListeners(CefriumProfile.incognito());
            Assert.assertNotNull("the incognito proxy error was not reported",
                    toIncognito.poll(20, TimeUnit.SECONDS));
            Assert.assertNull("it was reported to the default profile too",
                    toDefault.poll(1, TimeUnit.SECONDS));
        }
    }

    // ----- persistence ---------------------------------------------------------

    private static String readPrefs(File dir) throws Exception {
        for (String rel : new String[] {"Preferences", "Default/Preferences"}) {
            File f = new File(dir, rel);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        return "";
    }

    @Test
    public void theDefaultProfileKeepsItsProxyOnDisk() throws Exception {
        File dir = CefriumProfile.getDefault().getDirectory();
        Assert.assertTrue(onMain(() -> CefriumProfile.getDefault().setProxy("127.0.0.1:65001")));
        long deadline = System.currentTimeMillis() + 30_000;
        while (!readPrefs(dir).contains("127.0.0.1:65001") && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
        Assert.assertTrue("the proxy is not in the profile's Preferences",
                readPrefs(dir).contains("127.0.0.1:65001"));
        Assert.assertTrue(onMain(() -> CefriumProfile.getDefault().clearProxy()));
        deadline = System.currentTimeMillis() + 30_000;
        while (readPrefs(dir).contains("127.0.0.1:65001") && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
        Assert.assertFalse(readPrefs(dir).contains("127.0.0.1:65001"));
    }

    // ----- WebRTC --------------------------------------------------------------

    private static final String CANDIDATES = "<html><body><script>"
            + "var pc=new RTCPeerConnection({iceServers:[]}),n=0;"
            + "pc.createDataChannel('x');"
            + "pc.onicecandidate=function(e){if(e.candidate&&e.candidate.candidate)n++;"
            + "else if(!e.candidate)document.title='candidates:'+n;};"
            + "pc.createOffer().then(function(o){return pc.setLocalDescription(o);});"
            + "</script></body></html>";

    @Test
    public void disableNonProxiedUdpGathersNoCandidates() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(CANDIDATES)) {
            String before = open(CefriumProfile.getDefault(), server.url(), null);
            Assert.assertTrue("no candidates even by default: " + before,
                    before.startsWith("candidates:") && !before.equals("candidates:0"));
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault().setWebRtcIpHandlingPolicy(
                    CefriumProfile.WEBRTC_DISABLE_NON_PROXIED_UDP)));
            Assert.assertEquals("candidates:0", open(CefriumProfile.getDefault(), server.url(), null));
        }
    }
}
