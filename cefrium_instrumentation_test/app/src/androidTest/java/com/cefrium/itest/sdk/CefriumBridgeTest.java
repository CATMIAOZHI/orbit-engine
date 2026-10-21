package com.cefrium.itest.sdk;

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

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

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
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
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
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
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
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
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
                CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
                created.set(browser);
                browser.setQueryHandler(CefriumBrowser.QueryOriginPolicy.loopback(),
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

    /** exactly() refuses an entry that could never match, instead of denying silently. */
    @Test(expected = IllegalArgumentException.class)
    public void exactlyRejectsAnEntryWithoutScheme() {
        CefriumBrowser.QueryOriginPolicy.exactly("cefrium.com");
    }
}
