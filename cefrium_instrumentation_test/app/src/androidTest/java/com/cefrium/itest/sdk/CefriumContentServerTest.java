// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;
import android.content.Context;
import android.util.Base64;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumContentRequest;
import com.cefrium.CefriumContentResponse;
import com.cefrium.CefriumContentServer;
import com.cefrium.CefriumContentServers;
import com.cefrium.CefriumPathContentServer;
import com.cefrium.CefriumProfile;
import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEATURE-090: an https origin served from Java (registerContentServer). The
 * pages report through the title; the server logs every path it is asked
 * for, so a request that never reached it (a service worker's own fetch)
 * shows.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumContentServerTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String ORIGIN = "https://app.test";
    private static final int BIG = 1 << 20;

    private static final String PNG_1X1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAA"
            + "DUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final Map<String, String> pages = new HashMap<>();

    @Before
    public void register() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = new File(context.getCacheDir(), "content-test");
        dir.mkdirs();
        write(new File(dir, "a.txt"), "0123456789".getBytes(StandardCharsets.UTF_8));
        byte[] big = new byte[BIG];
        for (int i = 0; i < BIG; i++) big[i] = (byte) (i * 31 + 7);
        write(new File(dir, "big.bin"), big);
        // Outside the served directory: a path that climbs out must not reach it.
        write(new File(context.getCacheDir(), "secret"), "secret".getBytes(StandardCharsets.UTF_8));

        pages.put("/index.html", page("const r = {secure: isSecureContext, origin: location.origin};"
                + "localStorage.setItem('k', 'v-' + Date.now()); r.k = localStorage.getItem('k');"
                + "document.title = 'index:' + JSON.stringify(r);"));
        pages.put("/read.html", page("document.title = 'read:' + localStorage.getItem('k');"));
        pages.put("/data.json", "{\"value\": 42}");
        pages.put("/sw-data.json", "{\"from\": \"server via sw\"}");
        pages.put("/suite.html", page(SUITE));
        pages.put("/sw-page.html", page(SW_PAGE));
        pages.put("/sw.js", SW);

        CefriumContentServer memory = request -> {
            String body = pages.get(request.getPath());
            if (request.getPath().equals("/echo")) {
                byte[] b = request.getBody();
                body = request.getMethod() + ":" + (b == null ? "" : new String(b, StandardCharsets.UTF_8))
                        + ":" + request.getHeader("x-test");
            } else if (request.getPath().equals("/img.png")) {
                byte[] png = Base64.decode(PNG_1X1, Base64.DEFAULT);
                return new CefriumContentResponse("image/png", new ByteArrayInputStream(png))
                        .setContentLength(png.length);
            } else if (request.getPath().equals("/x.wasm")) {
                return new CefriumContentResponse(
                        CefriumContentServers.mimeTypeOf(request.getPath()),
                        new ByteArrayInputStream(new byte[8])).setContentLength(8);
            }
            if (body == null) return null;
            return CefriumContentResponse.ofText(
                    CefriumContentServers.mimeTypeOf(request.getPath()), body);
        };
        CefriumContentServer server = new CefriumPathContentServer.Builder()
                .addPath("/", memory)
                .addPath("/files/", CefriumContentServers.files(dir))
                .addPath("/assets/", CefriumContentServers.assets(context, "content-test"))
                .build()
                .withResponseHeaders(isolation());
        CefriumContentServer logging = request -> {
            asked.add(request.getPath());
            return server.serve(request);
        };
        onMain(() -> CefriumProfile.getDefault().registerContentServer(ORIGIN, logging));
    }

    @After
    public void unregister() throws Exception {
        onMain(() -> CefriumProfile.getDefault().unregisterContentServer(ORIGIN));
    }

    /** The origin is secure and keeps its storage for the next browser. */
    @Test
    public void originIsSecureAndKeepsStorage() throws Exception {
        String index = load(offscreen(), ORIGIN + "/index.html", "index:");
        JSONObject r = new JSONObject(index.substring("index:".length()));
        Assert.assertTrue("not a secure context: " + index, r.getBoolean("secure"));
        Assert.assertEquals(ORIGIN, r.getString("origin"));
        String read = load(offscreen(), ORIGIN + "/read.html", "read:");
        Assert.assertEquals("localStorage did not survive to the next browser",
                "read:" + r.getString("k"), read);
    }

    /** What pages need from it: subresources, 404, ranges, HEAD, POST, headers, size. */
    @Test
    public void servesWhatPagesNeed() throws Exception {
        String suite = load(offscreen(), ORIGIN + "/suite.html", "suite:");
        JSONObject r = new JSONObject(suite.substring("suite:".length()));
        Assert.assertFalse(suite, r.has("error"));
        Assert.assertEquals(suite, 42, r.getInt("rel"));
        Assert.assertEquals(suite, 1, r.getInt("img"));
        Assert.assertEquals(suite, 404, r.getInt("missing"));
        Assert.assertEquals(suite, "[206,\"bytes 2-5\\/10\",\"2345\"]", r.getJSONArray("range").toString());
        Assert.assertEquals(suite, "[206,\"bytes 7-9\\/10\",\"789\"]", r.getJSONArray("suffix").toString());
        Assert.assertEquals(suite, 416, r.getJSONArray("unsat").getInt(0));
        Assert.assertEquals(suite, "[200,0,\"10\"]", r.getJSONArray("head").toString());
        Assert.assertEquals(suite, "POST:hello:yes", r.getString("post"));
        Assert.assertEquals(suite, BIG, r.getJSONArray("big").getInt(0));
        Assert.assertEquals(suite, expectedChecksum(), r.getJSONArray("big").getLong(1));
        Assert.assertEquals(suite, "<!doctype html><title>asset-index</title>\n", r.getString("assetIndex"));
        Assert.assertEquals(suite, "hello from assets\n", r.getString("asset"));
        Assert.assertEquals(suite, "application/wasm", r.getString("wasmType"));
        Assert.assertEquals(suite, 404, r.getInt("traversal"));
        Assert.assertTrue("not cross-origin isolated: " + suite, r.getBoolean("coi"));
    }

    /**
     * A service worker on the origin: its script, and a fetch it makes itself
     * (with no browser behind it), are answered by the server too.
     */
    @Test
    public void serviceWorkerIsServed() throws Exception {
        String sw = load(offscreen(), ORIGIN + "/sw-page.html", "sw:");
        Assert.assertEquals("sw:" + "from-sw|{\"from\": \"server via sw\"}", sw);
        Assert.assertTrue("the service worker script did not come from the server: " + asked,
                asked.contains("/sw.js"));
        Assert.assertTrue("the service worker's own fetch did not reach the server: " + asked,
                asked.contains("/sw-data.json"));
    }

    /** The same in a surface browser. */
    @Test
    public void surfaceBrowserLoadsTheOrigin() throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<CefriumBrowser> b = new AtomicReference<>();
        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            activity.addContentView(browser.getSurfaceContainer(), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            b.set(browser);
        });
        String index = load(b, ORIGIN + "/index.html", "index:");
        Assert.assertTrue(index, new JSONObject(index.substring(6)).getBoolean("secure"));
    }

    /** Nothing is served once the origin is unregistered. */
    @Test
    public void unregisteredOriginIsNotServed() throws Exception {
        load(offscreen(), ORIGIN + "/read.html", "read:");
        onMain(() -> CefriumProfile.getDefault().unregisterContentServer(ORIGIN));
        int before = asked.size();
        AtomicReference<CefriumBrowser> b = offscreen();
        CountDownLatch titled = new CountDownLatch(1);
        AtomicReference<String> title = new AtomicReference<>();
        onMain(() -> {
            b.get().setOnTitleChangedListener(t -> {
                if (t.startsWith("read:")) {
                    title.set(t);
                    titled.countDown();
                }
            });
            b.get().loadUrl(ORIGIN + "/read.html");
        });
        boolean loaded = titled.await(5, TimeUnit.SECONDS);
        Assert.assertFalse("the page loaded after unregister: " + title.get(), loaded);
        Assert.assertEquals("the server was asked after unregister: " + asked,
                before, asked.size());
    }

    // ---- pages ----

    private static final String SUITE = ""
            + "(async () => {"
            + " const r = {};"
            + " const t = async (k, f) => { try { r[k] = await f(); } catch (e) { r[k] = 'ERR ' + e; } };"
            + " await t('rel', async () => (await (await fetch('data.json')).json()).value);"
            + " await t('img', () => new Promise(ok => { const i = new Image(); i.onload = () => ok(i.naturalWidth);"
            + "   i.onerror = () => ok(-1); i.src = '/img.png'; }));"
            + " await t('missing', async () => (await fetch('/nope')).status);"
            + " const rng = async h => { const x = await fetch('/files/a.txt', {headers: {Range: h}});"
            + "   return [x.status, x.headers.get('content-range'), await x.text()]; };"
            + " await t('range', () => rng('bytes=2-5'));"
            + " await t('suffix', () => rng('bytes=-3'));"
            + " await t('unsat', () => rng('bytes=99-'));"
            + " await t('head', async () => { const hd = await fetch('/files/a.txt', {method: 'HEAD'});"
            + "   return [hd.status, (await hd.text()).length, hd.headers.get('content-length')]; });"
            + " await t('post', async () => (await fetch('/echo', {method: 'POST', body: 'hello',"
            + "   headers: {'X-Test': 'yes'}})).text());"
            + " await t('big', async () => {"
            + "   const big = new Uint8Array(await (await fetch('/files/big.bin')).arrayBuffer());"
            + "   let sum = 0; for (let i = 0; i < big.length; i++) sum = (sum * 33 + big[i]) % 2147483647;"
            + "   return [big.length, sum]; });"
            + " await t('assetIndex', async () => (await fetch('/assets/')).text());"
            + " await t('asset', async () => (await fetch('/assets/sub/hello.txt')).text());"
            + " await t('wasmType', async () => (await fetch('/x.wasm')).headers.get('content-type'));"
            + " await t('traversal', async () => (await fetch('/files/..%2Fsecret')).status);"
            + " r.coi = self.crossOriginIsolated;"
            + " document.title = 'suite:' + JSON.stringify(r);"
            + "})().catch(e => document.title = 'suite:' + JSON.stringify({error: String(e)}));";

    private static final String SW_PAGE = ""
            + "(async () => {"
            + " await navigator.serviceWorker.register('/sw.js');"
            + " await navigator.serviceWorker.ready;"
            + " if (!navigator.serviceWorker.controller) await new Promise(ok =>"
            + "   navigator.serviceWorker.addEventListener('controllerchange', ok, {once: true}));"
            + " const a = await (await fetch('/sw-only')).text();"
            + " const b = await (await fetch('/via-sw')).text();"
            + " document.title = 'sw:' + a + '|' + b;"
            + "})().catch(e => document.title = 'sw:error ' + e);";

    private static final String SW = ""
            + "self.addEventListener('install', e => self.skipWaiting());"
            + "self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));"
            + "self.addEventListener('fetch', e => {"
            + " const p = new URL(e.request.url).pathname;"
            + " if (p === '/sw-only') e.respondWith(new Response('from-sw'));"
            + " else if (p === '/via-sw') e.respondWith(fetch('/sw-data.json'));"
            + "});";

    private static String page(String script) {
        return "<!doctype html><html><head><meta charset=utf-8></head><body><script>"
                + script + "</script></body></html>";
    }

    // ---- helpers ----

    // Cross-origin isolation, as WebAssembly threads need.
    private static Map<String, String> isolation() {
        Map<String, String> h = new HashMap<>();
        h.put("Cross-Origin-Opener-Policy", "same-origin");
        h.put("Cross-Origin-Embedder-Policy", "require-corp");
        return h;
    }

    private static long expectedChecksum() {
        long sum = 0;
        for (int i = 0; i < BIG; i++) sum = (sum * 33 + ((i * 31 + 7) & 0xFF)) % 2147483647L;
        return sum;
    }

    private AtomicReference<CefriumBrowser> offscreen() throws Exception {
        AtomicReference<CefriumBrowser> b = new AtomicReference<>();
        onMain(() -> b.set(browsers.track(new CefriumBrowser(400, 300, 1.0f))));
        return b;
    }

    private String load(AtomicReference<CefriumBrowser> b, String url, String prefix)
            throws Exception {
        CountDownLatch titled = new CountDownLatch(1);
        AtomicReference<String> title = new AtomicReference<>();
        onMain(() -> {
            b.get().setOnTitleChangedListener(t -> {
                if (t.startsWith(prefix)) {
                    title.set(t);
                    titled.countDown();
                }
            });
            b.get().loadUrl(url);
        });
        Assert.assertTrue(url + " did not report; server was asked for " + asked,
                titled.await(30, TimeUnit.SECONDS));
        return title.get();
    }

    private void onMain(Runnable r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        activityRule.getActivity().runOnUiThread(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
            done.countDown();
        });
        Assert.assertTrue(done.await(10, TimeUnit.SECONDS));
        if (thrown.get() != null) throw new AssertionError(thrown.get());
    }

    private static void write(File f, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
    }
}
