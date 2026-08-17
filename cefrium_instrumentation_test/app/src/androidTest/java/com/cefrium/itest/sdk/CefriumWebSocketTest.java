package com.cefrium.itest.sdk;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalWebSocketServer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression guard for cef-android issue #1: a WebSocket to a local/private address
 * must connect and echo, not stall.
 *
 * Chromium 151 enabled Local Network Access (LNA) checks for WebSockets by default.
 * A page in a more-public address space connecting to a loopback/LAN WebSocket now
 * gates the handshake on an LNA permission decision; Cefrium has no LNA prompt UI, so
 * the handshake stalled forever (reported as extreme WebSocket latency after the
 * 0.6.x -> 0.7.0 engine bump). Cefrium disables the LNA feature to match Android
 * WebView. This test reproduces the exact trigger -- a PUBLIC-origin document (loaded
 * via loadHtmlWithBaseUrl with an https base) opening ws://127.0.0.1 -- so it FAILS if
 * LNA (or anything) ever re-blocks the handshake. Hermetic: the echo server is
 * in-process on loopback, never an external host.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumWebSocketTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    // PUBLIC base origin: makes the document's address space more-public than the
    // loopback WS target, which is exactly what triggers the LNA WebSocket check.
    private static final String PUBLIC_BASE = "https://ws-guard.cefrium.example/";

    @Test
    public void testLocalWebSocketConnectsAndEchoes() throws Exception {
        Activity activity = activityRule.getActivity();
        final LocalWebSocketServer ws = new LocalWebSocketServer();
        final String wsUrl = ws.url();

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>(null);

        // The page opens the WS, sends one frame, and reports the outcome through the
        // JS bridge: 'ws:ok' on a successful echo, 'ws:err:*' otherwise.
        final String html =
                "<!doctype html><meta charset=utf-8><title>ws-guard</title><script>"
                + "var ws=new WebSocket('" + wsUrl + "');"
                + "ws.onopen=function(){ws.send('ping');};"
                + "ws.onmessage=function(e){window.cefriumQuery({request:'ws:ok:'+e.data});};"
                + "ws.onerror=function(){window.cefriumQuery({request:'ws:err:error'});};"
                + "ws.onclose=function(e){window.cefriumQuery({request:'ws:err:close:'+e.code});};"
                + "</script>";

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            browser.setQueryHandler((queryId, request, origin, callback) -> {
                if (request != null && request.startsWith("ws:")) {
                    result.set(request);
                    callback.success("ack");
                    done.countDown();
                    return true;
                }
                return false;
            });
            browser.loadHtmlWithBaseUrl(html, PUBLIC_BASE);
        });

        // On the LNA-block regression the handshake never completes and neither
        // onmessage nor onclose fires -> the latch times out and the test fails.
        boolean arrived = done.await(20, TimeUnit.SECONDS);
        ws.close();

        Assert.assertTrue(
                "WebSocket to a local address did not connect+echo within 20s "
                        + "(LNA regression -- the handshake stalled). result=" + result.get(),
                arrived);
        Assert.assertTrue(
                "WebSocket did not echo successfully; got: " + result.get(),
                result.get() != null && result.get().startsWith("ws:ok:"));
    }
}
