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

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instrumented tests for the FEATURE-023 JavaScript &lt;-&gt; native bridge
 * (window.cefriumQuery / QueryHandler) and evaluateJavaScript.
 *
 * Run on a real device or emulator with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4.class)
public class CefriumBridgeTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    /**
     * Full round trip: JS calls window.cefriumQuery, the native QueryHandler
     * responds, JS onSuccess receives the response and re-queries with it.
     * The second request proves native -> JS delivery; it also exercises
     * evaluateJavaScript (used to inject the probe).
     */
    @Test
    public void testQueryRoundTrip() throws Exception {
        Activity activity = activityRule.getActivity();
        final CopyOnWriteArrayList<String> requests = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> origins = new CopyOnWriteArrayList<>();
        final CountDownLatch twoQueries = new CountDownLatch(2);
        final boolean[] probed = {false};

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.setQueryHandler(CefriumBrowser.QueryOriginPolicy.anyOrigin(), (queryId, request, origin, callback) -> {
                requests.add(request);
                origins.add(origin);
                callback.success("pong:" + request);
                twoQueries.countDown();
                return true;
            });
            browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    // The V8 context (with window.cefriumQuery injected) exists
                    // by load-complete.
                    browser.evaluateJavaScript(
                        "window.cefriumQuery({request:'ping',"
                        + "onSuccess:function(r){window.cefriumQuery({request:'echoed:'+r});},"
                        + "onFailure:function(c,m){}});");
                }
            });
            browser.loadUrl("about:blank");
        });

        Assert.assertTrue("Bridge round trip timed out",
                twoQueries.await(20, TimeUnit.SECONDS));
        Assert.assertTrue("native should have received the 'ping' request",
                requests.contains("ping"));
        Assert.assertTrue(
                "JS onSuccess should have received the native response 'pong:ping'",
                requests.contains("echoed:pong:ping"));
        Assert.assertFalse("the calling frame origin should be passed to the handler",
                origins.isEmpty());
    }

    /**
     * An unhandled query (handler returns false) fails on the JS side: the
     * page's onFailure runs. We relay that back through a second query so the
     * test can assert it deterministically (no reliance on logcat).
     */
    @Test
    public void testUnhandledQueryFails() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch onFailureFired = new CountDownLatch(1);
        final boolean[] probed = {false};

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.setQueryHandler(CefriumBrowser.QueryOriginPolicy.anyOrigin(), (queryId, request, origin, callback) -> {
                if (request.equals("probe")) {
                    return false;  // refuse -> the page gets onFailure(-1)
                }
                if (request.equals("failed")) {
                    onFailureFired.countDown();
                    callback.success("ok");
                    return true;
                }
                return false;
            });
            browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    browser.evaluateJavaScript(
                        "window.cefriumQuery({request:'probe',"
                        + "onSuccess:function(r){},"
                        + "onFailure:function(c,m){"
                        + "window.cefriumQuery({request:'failed'});}});");
                }
            });
            browser.loadUrl("about:blank");
        });

        Assert.assertTrue("an unhandled query should trigger the page's onFailure",
                onFailureFired.await(15, TimeUnit.SECONDS));
    }

    // ── Origin policy (SC-rc-11 to SC-rc-13) ───────────────────────────────
    //
    // A denied query never reaches the handler, so the bridge cannot report
    // its own denial. The page writes the outcome into document.title and the
    // title listener is the verdict channel.

    private static final String PROBE =
            "window.cefriumQuery({request:'probe',"
            + "onSuccess:function(r){document.title='ok:'+r;},"
            + "onFailure:function(c,m){document.title='denied:'+c+':'+m;}});";

    /** Loads url under policy, runs PROBE, returns the title it produced. */
    private String probeUnder(String url, CefriumBrowser.QueryOriginPolicy policy,
                              AtomicInteger handlerCalls) throws Exception {
        Activity activity = activityRule.getActivity();
        final AtomicReference<String> verdict = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] probed = {false};
        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.setQueryHandler(policy, (queryId, request, origin, callback) -> {
                handlerCalls.incrementAndGet();
                callback.success("handled");
                return true;
            });
            browser.setOnTitleChangedListener(title -> {
                if (title != null && (title.startsWith("ok:") || title.startsWith("denied:"))) {
                    verdict.set(title);
                    done.countDown();
                }
            });
            browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    browser.evaluateJavaScript(PROBE);
                }
            });
            browser.loadUrl(url);
        });
        Assert.assertTrue("the probe never reported a verdict for " + url,
                done.await(20, TimeUnit.SECONDS));
        return verdict.get();
    }

    /** SC-rc-11: a real http origin outside the policy never reaches the handler. */
    @Test
    public void queryFromDisallowedOriginIsRejected() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LocalHttpServer server = new LocalHttpServer("<html><body>p</body></html>")) {
            String title = probeUnder(server.url(),
                    CefriumBrowser.QueryOriginPolicy.exactly("https://allowed.example"), calls);
            Assert.assertEquals("denied:" + CefriumBrowser.QUERY_ERROR_ORIGIN_DENIED
                    + ":origin not allowed", title);
        }
        Assert.assertEquals("a denied origin must never reach the handler", 0, calls.get());
    }

    /** SC-rc-12: an allowed loopback origin reaches the handler unchanged. */
    @Test
    public void loopbackOriginReachesHandler() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LocalHttpServer server = new LocalHttpServer("<html><body>p</body></html>")) {
            String title = probeUnder(server.url(),
                    CefriumBrowser.QueryOriginPolicy.loopback(), calls);
            Assert.assertEquals("ok:handled", title);
        }
        Assert.assertEquals(1, calls.get());
    }

    /** SC-rc-13: an opaque origin is denied by every policy but anyOrigin(). */
    @Test
    public void opaqueOriginIsDeniedUnlessAny() throws Exception {
        AtomicInteger denied = new AtomicInteger();
        String title = probeUnder("about:blank",
                CefriumBrowser.QueryOriginPolicy.loopback(), denied);
        Assert.assertEquals("denied:" + CefriumBrowser.QUERY_ERROR_ORIGIN_DENIED
                + ":origin not allowed", title);
        Assert.assertEquals(0, denied.get());

        AtomicInteger allowed = new AtomicInteger();
        Assert.assertEquals("ok:handled", probeUnder("about:blank",
                CefriumBrowser.QueryOriginPolicy.anyOrigin(), allowed));
        Assert.assertEquals(1, allowed.get());
    }

    /** SC-rc-13: a data: document is opaque under exactly(), admitted by anyOrigin(). */
    @Test
    public void dataUrlIsDeniedByExactlyAndAdmittedByAny() throws Exception {
        String dataUrl = "data:text/html,<html><body>d</body></html>";
        AtomicInteger denied = new AtomicInteger();
        Assert.assertEquals("denied:" + CefriumBrowser.QUERY_ERROR_ORIGIN_DENIED
                + ":origin not allowed", probeUnder(dataUrl,
                CefriumBrowser.QueryOriginPolicy.exactly("https://allowed.example"), denied));
        Assert.assertEquals(0, denied.get());

        AtomicInteger calls = new AtomicInteger();
        Assert.assertEquals("ok:handled", probeUnder(dataUrl,
                CefriumBrowser.QueryOriginPolicy.anyOrigin(), calls));
        Assert.assertEquals(1, calls.get());

        // A custom policy cannot admit an opaque origin: only anyOrigin() can.
        AtomicInteger custom = new AtomicInteger();
        Assert.assertEquals("denied:" + CefriumBrowser.QUERY_ERROR_ORIGIN_DENIED
                + ":origin not allowed", probeUnder(dataUrl, url -> true, custom));
        Assert.assertEquals(0, custom.get());
    }

    /**
     * SC-rc-21: a sandboxed frame at an allowed URL is denied, because its
     * committed origin is opaque. The page embeds itself as
     * {@code <iframe sandbox="allow-scripts" src="?child">}: same URL origin as
     * the parent, opaque by the web's rules. Both frames call the bridge; the
     * child relays its verdict with postMessage and the parent writes both.
     */
    @Test
    public void sandboxedIframeAtAllowedUrlIsDenied() throws Exception {
        final String html = "<html><body><script>"
                + "function ask(done){if(!window.cefriumQuery){done('nobridge');return;}"
                + "window.cefriumQuery({request:'probe',"
                + "onSuccess:function(r){done('ok:'+r);},"
                + "onFailure:function(c,m){done('denied:'+c);}});}"
                + "if(location.search==='?child'){"
                + "ask(function(v){parent.postMessage(v,'*');});"
                + "}else{"
                + "var mine=null,child=null;"
                + "function report(){if(mine&&child)document.title='parent='+mine+'|child='+child;}"
                + "window.addEventListener('message',function(e){child=e.data;report();});"
                + "ask(function(v){mine=v;report();});"
                + "var f=document.createElement('iframe');"
                + "f.setAttribute('sandbox','allow-scripts');f.src='?child';"
                + "document.body.appendChild(f);"
                + "}</script></body></html>";
        Activity activity = activityRule.getActivity();
        final AtomicReference<String> verdict = new AtomicReference<>();
        final AtomicReference<CefriumBrowser> created = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        try (LocalHttpServer server = new LocalHttpServer(html)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser browser = browsers.createWithSurface(activity);
                created.set(browser);
                // withSubframes(): the child must be denied for its opaque
                // origin, not merely for being a subframe.
                browser.setQueryHandler(CefriumBrowser.QueryOriginPolicy.loopback().withSubframes(),
                        (queryId, request, origin, callback) -> {
                            calls.incrementAndGet();
                            callback.success("handled");
                            return true;
                        });
                browser.setOnTitleChangedListener(title -> {
                    if (title != null && title.startsWith("parent=")) {
                        verdict.set(title);
                        done.countDown();
                    }
                });
                browser.loadUrl(server.url());
            });
            Assert.assertTrue("the page never reported both verdicts",
                    done.await(20, TimeUnit.SECONDS));
        } finally {
            activity.runOnUiThread(() -> {
                if (created.get() != null) created.get().close();
            });
        }
        Assert.assertEquals("parent=ok:handled|child=denied:"
                + CefriumBrowser.QUERY_ERROR_ORIGIN_DENIED, verdict.get());
        Assert.assertEquals("only the parent frame may reach the handler", 1, calls.get());
    }

    /**
     * SC-rc-20: in ONE view under bundledAssets(), the bundled page reaches the
     * handler and the next page, served from loopback, is denied. This also
     * proves a file:///android_asset frame's committed origin is not opaque:
     * the opaque check runs before any policy, so an opaque file origin would
     * deny every asset page.
     */
    @Test
    public void onlyTheBundledPageInTheSameViewReachesTheHandler() throws Exception {
        final String probe = "<html><body>p<script>"
                + "window.cefriumQuery({request:'probe',"
                + "onSuccess:function(r){document.title='ok:'+r;},"
                + "onFailure:function(c,m){document.title='denied:'+c+':'+m;}});"
                + "</script></body></html>";
        Activity activity = activityRule.getActivity();
        final List<String> verdicts = new CopyOnWriteArrayList<>();
        final AtomicReference<CefriumBrowser> created = new AtomicReference<>();
        final CountDownLatch both = new CountDownLatch(2);
        final AtomicInteger calls = new AtomicInteger();
        try (LocalHttpServer server = new LocalHttpServer(probe)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser browser = browsers.createWithSurface(activity);
                created.set(browser);
                browser.setQueryHandler(CefriumBrowser.QueryOriginPolicy.bundledAssets(),
                        (queryId, request, origin, callback) -> {
                            calls.incrementAndGet();
                            callback.success("handled");
                            return true;
                        });
                browser.setOnTitleChangedListener(title -> {
                    if (title == null || !(title.startsWith("ok:") || title.startsWith("denied:"))) {
                        return;
                    }
                    verdicts.add(title);
                    both.countDown();
                    if (verdicts.size() == 1) browser.loadUrl(server.url());
                });
                browser.loadUrl("file:///android_asset/bridge/probe.html");
            });
            Assert.assertTrue("both pages must report a verdict, got " + verdicts,
                    both.await(30, TimeUnit.SECONDS));
        } finally {
            activity.runOnUiThread(() -> {
                if (created.get() != null) created.get().close();
            });
        }
        Assert.assertEquals("ok:handled", verdicts.get(0));
        Assert.assertEquals("denied:" + CefriumBrowser.QUERY_ERROR_ORIGIN_DENIED
                + ":origin not allowed", verdicts.get(1));
        Assert.assertEquals("only the bundled page may reach the handler", 1, calls.get());
    }

    /**
     * Main page M for the SC-rc-22 tests: frames aUrl and writes every message
     * A posts up into its own title. Bridge calls are main frame only unless
     * the policy opts into subframes, so these tests run A inside an allowed M.
     */
    private static String relayPage(String aUrl) {
        return "<html><body>m<script>"
                + "window.addEventListener('message',function(e){document.title=String(e.data);});"
                + "var f=document.createElement('iframe');f.src='" + aUrl + "';"
                + "document.body.appendChild(f);"
                + "</script></body></html>";
    }

    /**
     * SC-rc-22: a query is judged by the document that sent it. Page A (not
     * allowed) creates an iframe to B (allowed) and keeps the cefriumQuery of
     * the iframe's initial empty document. Chromium reuses that frame's
     * RenderFrameHost for its first real navigation, so once B has committed,
     * a call through the kept function arrives from a frame whose committed
     * origin is B. It must still be denied: it was A's script, in A's document.
     */
    @Test
    public void keptQueryOfAnInitialEmptyDocumentIsNotJudgedAsTheNextDocument()
            throws Exception {
        final String child = "<html><body>b<script>"
                + "parent.postMessage('b-loaded','*');</script></body></html>";
        try (LocalHttpServer b = new LocalHttpServer(child)) {
            final String bOrigin = b.url().substring(0, b.url().length() - 1);
            final String parentPage = "<html><body>a<script>"
                    + "var said=false;function say(v){said=true;parent.postMessage(v,'*');}"
                    + "var f=document.createElement('iframe');document.body.appendChild(f);"
                    + "var kept=f.contentWindow.cefriumQuery;"
                    + "window.addEventListener('message',function(e){"
                    + "if(e.data!=='b-loaded')return;"
                    + "if(!kept){say('verdict:nobridge');return;}"
                    + "try{kept({request:'probe',"
                    + "onSuccess:function(r){say('verdict:ok:'+r);},"
                    + "onFailure:function(c,m){say('verdict:denied:'+c);}});"
                    + "setTimeout(function(){if(!said)say('verdict:silent');},3000);"
                    + "}catch(x){say('verdict:threw');}});"
                    + "f.src='" + b.url() + "';"
                    + "</script></body></html>";
            try (LocalHttpServer a = new LocalHttpServer(parentPage);
                 LocalHttpServer m = new LocalHttpServer(relayPage(a.url()))) {
                final String mOrigin = m.url().substring(0, m.url().length() - 1);
                Activity activity = activityRule.getActivity();
                final AtomicReference<String> verdict = new AtomicReference<>();
                final CountDownLatch done = new CountDownLatch(1);
                final AtomicInteger calls = new AtomicInteger();
                activity.runOnUiThread(() -> {
                    CefriumBrowser browser = browsers.createWithSurface(activity);
                    browser.setQueryHandler(
                            CefriumBrowser.QueryOriginPolicy.exactly(mOrigin, bOrigin)
                                    .withSubframes(),
                            (queryId, request, origin, callback) -> {
                                calls.incrementAndGet();
                                callback.success("handled");
                                return true;
                            });
                    browser.setOnTitleChangedListener(title -> {
                        if (title != null && title.startsWith("verdict:")) {
                            verdict.set(title);
                            done.countDown();
                        }
                    });
                    browser.loadUrl(m.url());
                });
                Assert.assertTrue("the page never reported", done.await(20, TimeUnit.SECONDS));
                Assert.assertEquals("A's script reached a handler that only admits B through "
                        + "the iframe's initial empty document; verdict=" + verdict.get(),
                        0, calls.get());
                // The attempt must have been made: "nobridge" would prove nothing.
                // Measured on the W90: the renderer-side router refuses a call
                // from a context that no longer exists ("threw"), so the query
                // never leaves the renderer. A denial would be acceptable too.
                String v = verdict.get();
                Assert.assertTrue("unexpected verdict " + v, "verdict:threw".equals(v)
                        || ("verdict:denied:" + CefriumBrowser.QUERY_ERROR_ORIGIN_DENIED).equals(v));
            }
        }
    }

    /**
     * SC-rc-22, the race itself: the iframe's initial empty document (A's
     * origin) sends a query right after its navigation to B has started.
     * The renderer sends the query before it can commit B, and the browser
     * must judge it as A: denied under exactly(B), never admitted.
     */
    @Test
    public void queryRacingTheFirstCommitIsJudgedAsTheSender() throws Exception {
        // A's queries are sent through each iframe's initial document; their
        // reply would go to a context B's commit destroys, so the browser side
        // is the verdict channel: the handler records which requests reach it.
        // Positive control in the same frames: each B document, once loaded,
        // calls the bridge itself. Those 10 must reach the handler, which also
        // proves the sender check does not deny the legitimate case.
        try (LocalHttpServer b = new LocalHttpServer("<html><body>b<script>"
                + "window.cefriumQuery({request:'own',"
                + "onSuccess:function(){parent.postMessage('b-ok','*');},"
                + "onFailure:function(c){parent.postMessage('b-denied:'+c,'*');}});"
                + "</script></body></html>")) {
            // 10 iframes x 20 queries: before the sender-origin stamp, 152 of
            // 200 were judged as B on a W90.
            final String parentPage = "<html><body>a<script>"
                    + "var ok=0,attempts=0;"
                    + "window.addEventListener('message',function(e){"
                    + "if(e.data==='b-ok'&&++ok===10)parent.postMessage('b-ok:'+attempts,'*');"
                    + "else if(String(e.data).indexOf('b-denied')===0)parent.postMessage(e.data,'*');});"
                    + "for(var n=0;n<10;n++){"
                    + "var f=document.createElement('iframe');document.body.appendChild(f);"
                    + "var q=f.contentWindow.cefriumQuery;"
                    + "f.src='" + b.url() + "?'+n;"
                    + "if(!q){parent.postMessage('nobridge','*');break;}"
                    + "for(var i=0;i<20;i++){q({request:'probe'});attempts++;}}"
                    + "</script></body></html>";
            final String bOrigin = b.url().substring(0, b.url().length() - 1);
            try (LocalHttpServer a = new LocalHttpServer(parentPage);
                 LocalHttpServer m = new LocalHttpServer(relayPage(a.url()))) {
                final String mOrigin = m.url().substring(0, m.url().length() - 1);
                Activity activity = activityRule.getActivity();
                final List<String> handled = new CopyOnWriteArrayList<>();
                final AtomicReference<String> title = new AtomicReference<>();
                final CountDownLatch done = new CountDownLatch(1);
                activity.runOnUiThread(() -> {
                    CefriumBrowser browser = browsers.createWithSurface(activity);
                    browser.setQueryHandler(CefriumBrowser.QueryOriginPolicy
                                    .exactly(mOrigin, bOrigin).withSubframes(),
                            (queryId, request, origin, callback) -> {
                                handled.add(request);
                                callback.success("handled");
                                return true;
                            });
                    browser.setOnTitleChangedListener(t -> {
                        title.set(t);
                        if (t != null && (t.startsWith("b-ok") || t.startsWith("b-denied")
                                || "nobridge".equals(t))) {
                            done.countDown();
                        }
                    });
                    browser.loadUrl(m.url());
                });
                Assert.assertTrue("the 10 B documents never all reached the handler; title="
                        + title.get(), done.await(30, TimeUnit.SECONDS));
                Assert.assertEquals("every B document must reach the handler, and A must "
                        + "have made all 200 attempts", "b-ok:200", title.get());
                Thread.sleep(1000);
                int own = 0;
                for (String r : handled) {
                    Assert.assertEquals("a query sent by A through an iframe's initial "
                            + "document reached the handler as B", "own", r);
                    own++;
                }
                Assert.assertEquals("positive control: one query per B document", 10, own);
            }
        }
    }

    /** exactly() refuses an entry that could never match, instead of denying silently. */
    // -- Main-frame isolation (sanchuang-dev/3c-app-prototype issue #3) ------
    //
    // The main page calls the bridge and embeds childUrl; the child calls it
    // too and relays its verdict with postMessage. The main page writes both
    // into its title, the one the listener sees.

    private static final String CHILD = "<html><body><script>"
            + "if(!window.cefriumQuery){parent.postMessage('nobridge','*');}else{"
            + "window.cefriumQuery({request:'probe',"
            + "onSuccess:function(r){parent.postMessage('ok:'+r,'*');},"
            + "onFailure:function(c,m){parent.postMessage('denied:'+c,'*');}});}"
            + "</script></body></html>";

    private static String mainPage(String childUrl) {
        return "<html><body><script>"
                + "var mine=null,child=null;"
                + "function report(){if(mine&&child)document.title='main='+mine+'|child='+child;}"
                + "window.addEventListener('message',function(e){child=e.data;report();});"
                + "window.cefriumQuery({request:'probe',"
                + "onSuccess:function(r){mine='ok:'+r;report();},"
                + "onFailure:function(c,m){mine='denied:'+c;report();}});"
                + "var f=document.createElement('iframe');f.src='" + childUrl + "';"
                + "document.body.appendChild(f);"
                + "</script></body></html>";
    }

    /** Loads mainUrl under policy; returns "main=...|child=..." from its title. */
    private String framesUnder(String mainUrl, CefriumBrowser.QueryOriginPolicy policy,
                               AtomicInteger handlerCalls) throws Exception {
        return framesUnder(mainUrl, policy, (queryId, request, origin, callback) -> {
            handlerCalls.incrementAndGet();
            callback.success("handled");
            return true;
        });
    }

    private String framesUnder(String mainUrl, CefriumBrowser.QueryOriginPolicy policy,
                               CefriumBrowser.QueryHandler handler) throws Exception {
        Activity activity = activityRule.getActivity();
        final AtomicReference<String> verdict = new AtomicReference<>();
        final AtomicReference<CefriumBrowser> created = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            activity.runOnUiThread(() -> {
                CefriumBrowser browser = browsers.createWithSurface(activity);
                created.set(browser);
                browser.setQueryHandler(policy, handler);
                browser.setOnTitleChangedListener(title -> {
                    if (title != null && title.startsWith("main=")) {
                        verdict.set(title);
                        done.countDown();
                    }
                });
                browser.loadUrl(mainUrl);
            });
            Assert.assertTrue("the page never reported both verdicts for " + mainUrl,
                    done.await(20, TimeUnit.SECONDS));
        } finally {
            activity.runOnUiThread(() -> {
                if (created.get() != null) created.get().close();
            });
        }
        return verdict.get();
    }

    private static final String DENIED = "denied:" + CefriumBrowser.QUERY_ERROR_ORIGIN_DENIED;

    /** By default only the main frame reaches the handler, even same-origin. */
    @Test
    public void sameOriginIframeIsDeniedByDefault() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LocalHttpServer child = new LocalHttpServer(CHILD);
             LocalHttpServer main = new LocalHttpServer(mainPage(child.url()))) {
            Assert.assertEquals("main=ok:handled|child=" + DENIED,
                    framesUnder(main.url(), CefriumBrowser.QueryOriginPolicy.loopback(), calls));
        }
        Assert.assertEquals("only the main frame may reach the handler", 1, calls.get());
    }

    /** A handler that admits subframes can tell them from the main frame. */
    @Test
    public void handlerIsToldWhetherTheCallerIsTheMainFrame() throws Exception {
        try (LocalHttpServer child = new LocalHttpServer(CHILD);
             LocalHttpServer main = new LocalHttpServer(mainPage(child.url()))) {
            Assert.assertEquals("main=ok:main|child=ok:sub",
                    framesUnder(main.url(),
                            CefriumBrowser.QueryOriginPolicy.loopback().withSubframes(),
                            new CefriumBrowser.QueryHandler() {
                                @Override
                                public boolean onQuery(long queryId, String request,
                                        String origin, CefriumBrowser.QueryCallback cb) {
                                    throw new AssertionError("the frameless form was called");
                                }

                                @Override
                                public boolean onQuery(long queryId, String request,
                                        String origin, boolean isMainFrame,
                                        CefriumBrowser.QueryCallback cb) {
                                    cb.success(isMainFrame ? "main" : "sub");
                                    return true;
                                }
                            }));
        }
    }

    /** withSubframes() admits an iframe when its main frame is allowed too. */
    @Test
    public void withSubframesAdmitsAnIframeOfAnAllowedPage() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LocalHttpServer child = new LocalHttpServer(CHILD);
             LocalHttpServer main = new LocalHttpServer(mainPage(child.url()))) {
            Assert.assertEquals("main=ok:handled|child=ok:handled",
                    framesUnder(main.url(),
                            CefriumBrowser.QueryOriginPolicy.loopback().withSubframes(), calls));
        }
        Assert.assertEquals(2, calls.get());
    }

    /**
     * The issue #3 case: an allowed page framed by a page the policy does not
     * allow stays denied, even with withSubframes(). The main frame is denied
     * as well, so nothing reaches the handler.
     */
    @Test
    public void allowedIframeInsideAForeignPageIsDenied() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LocalHttpServer child = new LocalHttpServer(CHILD);
             LocalHttpServer main = new LocalHttpServer(mainPage(child.url()))) {
            String childOrigin = CefriumBrowser.QueryOriginPolicy.originOf(child.url());
            Assert.assertEquals("main=" + DENIED + "|child=" + DENIED,
                    framesUnder(main.url(), CefriumBrowser.QueryOriginPolicy
                            .exactly(childOrigin).withSubframes(), calls));
        }
        Assert.assertEquals("a framed allowed page must not reach the handler", 0, calls.get());
    }

    /** A custom predicate is main frame only too, unless it opts in. */
    @Test
    public void customPolicyIsMainFrameOnlyByDefault() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LocalHttpServer child = new LocalHttpServer(CHILD);
             LocalHttpServer main = new LocalHttpServer(mainPage(child.url()))) {
            CefriumBrowser.QueryOriginPolicy everyLoopback =
                    url -> url != null && url.startsWith("http://127.0.0.1:");
            Assert.assertEquals("main=ok:handled|child=" + DENIED,
                    framesUnder(main.url(), everyLoopback, calls));
        }
        Assert.assertEquals(1, calls.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void exactlyRejectsAnEntryWithoutScheme() {
        CefriumBrowser.QueryOriginPolicy.exactly("cefrium.com");
    }
}
