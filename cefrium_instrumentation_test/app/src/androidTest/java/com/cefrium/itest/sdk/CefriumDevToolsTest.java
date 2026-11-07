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

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEATURE-082: executeDevToolsMethod returns the method's result or error,
 * events reach the listener once their domain is enabled, and
 * evaluateJavaScript(script, callback) returns the value as JSON. Surface and
 * off-screen browsers.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumDevToolsTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE =
            "<html><body><script>window.answer=42;document.title='ready';</script></body></html>";

    private CefriumBrowser open(boolean offscreen, String url) throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<CefriumBrowser> created = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            CefriumBrowser b = offscreen
                    ? browsers.track(new CefriumBrowser(400, 300, 1.0f))
                    : browsers.createWithSurface(activity);
            created.set(b);
            b.setOnTitleChangedListener(t -> {
                if ("ready".equals(t)) ready.countDown();
            });
            b.loadUrl(url);
        });
        Assert.assertTrue("the page did not load", ready.await(30, TimeUnit.SECONDS));
        return created.get();
    }

    private static final class Result {
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean success;
        volatile String json;
    }

    private Result call(CefriumBrowser b, String method, String params) throws Exception {
        Result r = new Result();
        activityRule.getActivity().runOnUiThread(() -> b.executeDevToolsMethod(method, params,
                (ok, json) -> {
                    r.success = ok;
                    r.json = json;
                    r.done.countDown();
                }));
        Assert.assertTrue(method + ": no answer in 10 s", r.done.await(10, TimeUnit.SECONDS));
        return r;
    }

    private String eval(CefriumBrowser b, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        activityRule.getActivity().runOnUiThread(() -> b.evaluateJavaScript(script, v -> {
            value.set(v);
            done.countDown();
        }));
        Assert.assertTrue(script + ": no answer in 10 s", done.await(10, TimeUnit.SECONDS));
        return value.get();
    }

    private void methodResultAndError(boolean offscreen) throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = open(offscreen, server.url());
            Result ok = call(b, "Runtime.evaluate", "{\"expression\":\"window.answer+1\"}");
            Assert.assertTrue("Runtime.evaluate failed: " + ok.json, ok.success);
            Assert.assertEquals(ok.json, 43,
                    new JSONObject(ok.json).getJSONObject("result").getInt("value"));

            Result bad = call(b, "Nope.notAMethod", null);
            Assert.assertFalse("an unknown method succeeded", bad.success);
            Assert.assertTrue("no error message: " + bad.json,
                    new JSONObject(bad.json).getString("message").length() > 0);

            Result badParams = call(b, "Runtime.evaluate", "[1]");
            Assert.assertFalse("a non-object params was accepted", badParams.success);
        }
    }

    @Test
    public void methodResultAndErrorSurface() throws Exception {
        methodResultAndError(false);
    }

    @Test
    public void methodResultAndErrorOffscreen() throws Exception {
        methodResultAndError(true);
    }

    // Issue #18: User-Agent Client Hints (navigator.userAgentData), which
    // setUserAgentOverride(String) does not change, set through CDP.
    @Test
    public void userAgentMetadataReachesThePage() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = open(true, server.url());
            Result set = call(b, "Emulation.setUserAgentOverride", "{"
                    + "\"userAgent\":\"Mozilla/5.0 (X11; Linux x86_64) CefriumTest/1\","
                    + "\"userAgentMetadata\":{\"brands\":[{\"brand\":\"CefriumTest\",\"version\":\"1\"}],"
                    + "\"fullVersion\":\"1.0\",\"platform\":\"Linux\",\"platformVersion\":\"6.0\","
                    + "\"architecture\":\"x86\",\"model\":\"\",\"mobile\":false}}");
            Assert.assertTrue("setUserAgentOverride failed: " + set.json, set.success);
            Result reload = call(b, "Page.reload", null);
            Assert.assertTrue("Page.reload failed: " + reload.json, reload.success);
            String seen = null;
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                seen = eval(b, "navigator.userAgentData.brands.map(x=>x.brand).join(',')"
                        + "+'|'+navigator.userAgentData.mobile"
                        + "+'|'+navigator.userAgentData.platform+'|'+navigator.userAgent");
                if (seen != null) seen = seen.replace("\\/", "/");
                if (seen != null && seen.contains("CefriumTest|false|Linux|")) break;
                Thread.sleep(200);
            }
            Assert.assertTrue("seen=" + seen, seen != null
                    && seen.contains("CefriumTest|false|Linux|Mozilla/5.0 (X11; Linux x86_64) CefriumTest/1"));
        }
    }

    @Test
    public void eventsReachTheListener() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = open(false, server.url());
            List<String> events = new CopyOnWriteArrayList<>();
            CountDownLatch loaded = new CountDownLatch(1);
            activityRule.getActivity().runOnUiThread(() -> b.setDevToolsEventListener((m, p) -> {
                events.add(m);
                if ("Page.loadEventFired".equals(m)) loaded.countDown();
            }));
            Assert.assertTrue("Page.enable failed", call(b, "Page.enable", null).success);
            activityRule.getActivity().runOnUiThread(b::reload);
            Assert.assertTrue("no Page.loadEventFired; got " + events,
                    loaded.await(20, TimeUnit.SECONDS));

            // A removed listener gets nothing more.
            activityRule.getActivity().runOnUiThread(() -> b.setDevToolsEventListener(null));
            Thread.sleep(1000);  // events of the load still in flight
            int before = events.size();
            Assert.assertTrue(call(b, "Runtime.enable", null).success);
            activityRule.getActivity().runOnUiThread(b::reload);
            Thread.sleep(3000);
            Assert.assertEquals("events after the listener was removed: " + events,
                    before, events.size());
        }
    }

    @Test
    public void evaluateJavaScriptReturnsJson() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = open(false, server.url());
            Assert.assertEquals("42", eval(b, "window.answer"));
            Assert.assertEquals("\"a\\\"b\"", eval(b, "'a\"b'"));
            Assert.assertEquals("{\"x\":[1,true]}", eval(b, "({x:[1,true]})"));
            Assert.assertEquals("null", eval(b, "undefined"));
            Assert.assertEquals("null", eval(b, "null"));
            Assert.assertNull("a throwing script gave a value", eval(b, "throw new Error('x')"));
        }
    }

    @Test
    public void callAfterCloseFails() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = open(false, server.url());
            activityRule.getActivity().runOnUiThread(b::close);
            Thread.sleep(2000);
            Result r = call(b, "Runtime.evaluate", "{\"expression\":\"1\"}");
            Assert.assertFalse("a call on a closed browser succeeded", r.success);
        }
    }
}
