// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalWebSocketServer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

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

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    // PUBLIC base origin: makes the document's address space more-public than the
    // loopback WS target, which is exactly what triggers the LNA WebSocket check.
    private static final String PUBLIC_BASE = "https://ws-guard.cefrium.example/";

    @Test
    public void testLocalWebSocketConnectsAndEchoes() throws Exception {
        Activity activity = activityRule.getActivity();
        final LocalWebSocketServer ws = new LocalWebSocketServer();
        final String wsUrl = ws.url();

        // The page just opens the WS and sends a frame. We assert on the SERVER side
        // (same process) that the frame arrived -- this proves the handshake completed
        // and data flowed, without depending on any JS->app bridge. Repeated sends
        // ride out W90 renderer-startup latency; only the first arrival matters.
        final String html =
                "<!doctype html><meta charset=utf-8><title>ws-guard</title><script>"
                + "var ws=new WebSocket('" + wsUrl + "');"
                + "ws.onopen=function(){ws.send('ping');"
                + "  var i=setInterval(function(){try{ws.send('ping')}catch(e){clearInterval(i)}},500);};"
                + "</script>";

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.loadHtmlWithBaseUrl(html, PUBLIC_BASE);
        });

        // On the LNA-block regression the handshake never completes, so the server
        // never receives a frame -> awaitMessage times out and the test fails.
        boolean connected = ws.awaitMessage(20, TimeUnit.SECONDS);
        ws.close();

        Assert.assertTrue(
                "WebSocket to a local address did not connect within 20s "
                        + "(LNA regression -- the handshake stalled).",
                connected);
    }
}
