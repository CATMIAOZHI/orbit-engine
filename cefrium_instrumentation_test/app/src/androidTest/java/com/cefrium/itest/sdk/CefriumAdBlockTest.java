// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.Cefrium;
import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumProfile;
import com.cefrium.CefriumRenderHandler;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Network ad blocking. The page at 127.0.0.1 fetches a path on localhost, a
 * different host, and titles itself "loaded" or "blocked". A filter list is
 * only active once loaded; nothing is built in.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumAdBlockTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE = "<html><body><script>"
            + "fetch('http://localhost:'+location.port+location.hash.slice(1),{mode:'no-cors'})"
            + ".then(function(){document.title='loaded';},function(){document.title='blocked';});"
            + "</script></body></html>";

    private File list;

    @Before
    public void setUp() {
        list = new File(activityRule.getActivity().getCacheDir(), "adblock-test.txt");
    }

    @After
    public void clearTheList() throws Exception {
        load("");
        onMain(() -> {
            CefriumProfile.getDefault().setAdBlockExceptions(null);
            CefriumProfile.incognito().setAdBlockExceptions(null);
            return true;
        });
    }

    private boolean onMain(java.util.concurrent.Callable<Boolean> call) throws Exception {
        boolean[] result = new boolean[1];
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() -> {
            try {
                result[0] = call.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            done.countDown();
        });
        done.await(10, java.util.concurrent.TimeUnit.SECONDS);
        return result[0];
    }

    private int load(String rules) throws Exception {
        write(list, "[Adblock Plus 2.0]\n" + rules);
        return Cefrium.loadAdBlockList(list.getPath());
    }

    private static void write(File f, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Opens the page fetching {@code path} on localhost; returns its title and blocked count. */
    private String fetch(LocalHttpServer server, String path,
            java.util.function.Consumer<CefriumBrowser> setup) throws Exception {
        return fetch(CefriumProfile.getDefault(), server, path, setup);
    }

    private String fetch(CefriumProfile profile, LocalHttpServer server, String path,
            java.util.function.Consumer<CefriumBrowser> setup) throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<String> title = new AtomicReference<>("");
        CefriumBrowser[] holder = new CefriumBrowser[1];
        activity.runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f, profile));
            b.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer px) {}
            });
            b.setOnTitleChangedListener(title::set);
            if (setup != null) setup.accept(b);
            holder[0] = b;
            b.loadUrl(server.url() + "#" + path);
        });
        long deadline = System.currentTimeMillis() + 20_000;
        while (!title.get().equals("loaded") && !title.get().equals("blocked")
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        return title.get() + " count=" + holder[0].getBlockedCount();
    }

    @Test
    public void withoutAListNothingIsBlocked() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            Assert.assertEquals("loaded count=0", fetch(server, "/ad/x", null));
        }
    }

    @Test
    public void aLoadedListBlocksItsHosts() throws Exception {
        Assert.assertEquals(1, load("||localhost^"));
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            Assert.assertEquals("blocked count=1", fetch(server, "/ad/x", null));
        }
    }

    @Test
    public void loadingTheListAgainReplacesItsRules() throws Exception {
        load("||localhost^");
        load("||example.com^");
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            Assert.assertEquals("loaded count=0", fetch(server, "/ad/x", null));
        }
    }

    // The page's own host is listed: the page still opens (its script runs and
    // titles it); only its subresources, such as the favicon, are blocked.
    @Test
    public void theMainFrameIsNeverBlocked() throws Exception {
        load("||127.0.0.1^");
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            Assert.assertTrue(fetch(server, "/ad/x", null).startsWith("loaded "));
        }
    }

    @Test
    public void anExceptionRuleWins() throws Exception {
        load("||localhost^\n@@||localhost/ok/");
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            Assert.assertEquals("loaded count=0", fetch(server, "/ok/x", null));
            Assert.assertEquals("blocked count=1", fetch(server, "/ad/x", null));
        }
    }

    @Test
    public void aDocumentExceptionAllowsEverythingOnThatSite() throws Exception {
        load("||localhost^\n@@||127.0.0.1^$document");
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            Assert.assertEquals("loaded count=0", fetch(server, "/ad/x", null));
        }
    }

    // The page is on 127.0.0.1 and fetches from localhost.
    @Test
    public void aSiteExceptionAllowsEverythingOnThatSite() throws Exception {
        load("||localhost^");
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            Assert.assertEquals("blocked count=1", fetch(server, "/ad/x", null));
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault()
                    .setAdBlockExceptions(Collections.singleton("127.0.0.1"))));
            Assert.assertEquals("loaded count=0", fetch(server, "/ad/x", null));
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault()
                    .setAdBlockExceptions(Collections.singleton(server.origin()))));
            Assert.assertEquals("loaded count=0", fetch(server, "/ad/x", null));
            // An origin covers only its own port.
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault()
                    .setAdBlockExceptions(Collections.singleton("http://127.0.0.1:1"))));
            Assert.assertEquals("blocked count=1", fetch(server, "/ad/x", null));
        }
    }

    @Test
    public void siteExceptionsBelongToTheirProfile() throws Exception {
        load("||localhost^");
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            Assert.assertTrue(onMain(() -> CefriumProfile.getDefault()
                    .setAdBlockExceptions(Collections.singleton("127.0.0.1"))));
            Assert.assertEquals("blocked count=1",
                    fetch(CefriumProfile.incognito(), server, "/ad/x", null));
        }
    }

    @Test
    public void aSiteExceptionMustBeAHostOrAnOrigin() throws Exception {
        for (String bad : new String[] {"example.com/ads", "https://example.com/ads", "", "a b"}) {
            Assert.assertFalse(bad, onMain(() -> CefriumProfile.getDefault()
                    .setAdBlockExceptions(Collections.singleton(bad))));
        }
        Assert.assertTrue(onMain(() -> CefriumProfile.getDefault()
                .setAdBlockExceptions(java.util.Arrays.asList("Example.com", "https://example.org"))));
    }

    @Test
    public void optionsNarrowARule() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            // fetch() is an xmlhttprequest, and localhost is third-party to 127.0.0.1.
            load("||localhost^$image");
            Assert.assertEquals("loaded count=0", fetch(server, "/ad/x", null));
            load("||localhost^$xmlhttprequest");
            Assert.assertEquals("blocked count=1", fetch(server, "/ad/x", null));
            load("||localhost^$~third-party");
            Assert.assertEquals("loaded count=0", fetch(server, "/ad/x", null));
            load("||localhost^$third-party,domain=127.0.0.1");
            Assert.assertEquals("blocked count=1", fetch(server, "/ad/x", null));
            load("||localhost^$domain=example.com");
            Assert.assertEquals("loaded count=0", fetch(server, "/ad/x", null));
            // An option the filter cannot apply skips the rule, not widens it.
            load("||localhost^$popup");
            Assert.assertEquals("loaded count=0", fetch(server, "/ad/x", null));
        }
    }

    @Test
    public void blockingCanBeTurnedOffPerBrowser() throws Exception {
        load("||localhost^");
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            Assert.assertEquals("loaded count=0",
                    fetch(server, "/ad/x", b -> b.setAdBlockEnabled(false)));
        }
    }

    @Test
    public void theYouTubeHeuristicsAreNotLoadedImplicitly() throws Exception {
        load("||example.com^");
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            Assert.assertEquals("loaded count=0", fetch(server, "/v?x=1&oad=1&ctier=L", null));
        }
    }

    // ----- downloadAndLoadAdBlockList -----------------------------------------

    private String download(String url) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        Cefrium.downloadAndLoadAdBlockList(activityRule.getActivity(), url, (rules, error) -> {
            result.set(rules + " " + error);
            done.countDown();
        });
        Assert.assertTrue("no result for " + url, done.await(30, TimeUnit.SECONDS));
        return result.get();
    }

    private void forgetDownloads() {
        File dir = new File(activityRule.getActivity().getFilesDir(), "cefrium-adblock");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    /** Leaves the list at {@code url} empty, from a fresh cached copy. */
    private void emptyDownloadedList(String url) throws Exception {
        File dir = new File(activityRule.getActivity().getFilesDir(), "cefrium-adblock");
        File[] files = dir.listFiles();
        Assert.assertNotNull(files);
        for (File f : files) write(f, "[Adblock Plus 2.0]\n");
        Assert.assertEquals("0 null", download(url));
    }

    @Test
    public void aDownloadedListIsSavedAndLoaded() throws Exception {
        forgetDownloads();
        try (LocalHttpServer lists = new LocalHttpServer("[Adblock Plus 2.0]\n||localhost^\n");
             LocalHttpServer server = new LocalHttpServer(PAGE)) {
            String url = lists.url() + "list.txt";
            Assert.assertEquals("1 null", download(url));
            Assert.assertEquals("blocked count=1", fetch(server, "/ad/x", null));
            emptyDownloadedList(url);
        } finally {
            forgetDownloads();
        }
    }

    @Test
    public void aPageThatIsNotAFilterListIsRejected() throws Exception {
        forgetDownloads();
        try (LocalHttpServer portal = new LocalHttpServer("<html>Sign in to the Wi-Fi</html>")) {
            Assert.assertEquals("-1 not a filter list", download(portal.url() + "list.txt"));
        } finally {
            forgetDownloads();
        }
    }

    // The download must not leave the device directly when the app routes the
    // engine through a proxy (Tor, a VPN-less privacy setup). adlist.test does
    // not resolve, so the list can only come through the proxy.
    @Test
    public void theDownloadGoesThroughTheProxy() throws Exception {
        forgetDownloads();
        String url = "http://adlist.test/list.txt";
        try (LocalHttpServer proxy = new LocalHttpServer("[Adblock Plus 2.0]\n||localhost^\n");
             LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser[] holder = new CefriumBrowser[1];
            AtomicReference<String> title = new AtomicReference<>("");
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f));
                b.setOnTitleChangedListener(title::set);
                holder[0] = b;
                b.loadUrl(server.url() + "#/x");
            });
            long deadline = System.currentTimeMillis() + 20_000;
            while (title.get().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100);
            AtomicReference<Boolean> set = new AtomicReference<>();
            activityRule.getActivity().runOnUiThread(
                    () -> set.set(holder[0].setProxy("127.0.0.1:" + proxy.url().split(":")[2].replace("/", ""))));
            while (set.get() == null) Thread.sleep(50);
            Assert.assertTrue("setProxy failed", set.get());
            try {
                Assert.assertEquals("1 null", download(url));
                emptyDownloadedList(url);
            } finally {
                activityRule.getActivity().runOnUiThread(() -> holder[0].clearProxy());
            }
        } finally {
            forgetDownloads();
        }
    }
}
