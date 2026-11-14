// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.os.Looper;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;
import com.cefrium.CefriumConsoleMessage;
import com.cefrium.CefriumLoadError;
import com.cefrium.CefriumNavigationClient;
import com.cefrium.CefriumRendererTerminationStatus;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * The display and load events of CefriumChromeClient and
 * CefriumNavigationClient, typed and on the UI thread: title, console
 * messages with their level, favicon URLs, progress, load start and end, URL,
 * a load error with its net code, find results, the link status on hover and
 * a crashed renderer.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumClientEventsTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE = "<!doctype html><html><head>"
            + "<link rel=icon href='/favicon-x.png'><title>first</title></head>"
            + "<body style='margin:0'><a href='/hovered' style='display:block;"
            + "height:600px;background:#cde'>needle and needle</a><script>\n"
            + "console.warn('w-msg');\n"
            + "console.error('e-msg');\n"
            + "document.title = 'second';\n"
            + "</script></body></html>";

    /** Every event, as text; "OFF-UI" marks one that came off the UI thread. */
    private final List<String> events = new CopyOnWriteArrayList<>();

    private void add(String e) {
        events.add(Looper.myLooper() == Looper.getMainLooper() ? e : "OFF-UI " + e);
    }

    private String await(Predicate<String> p, String what) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < end) {
            for (String e : events) if (p.test(e)) return e;
            Thread.sleep(100);
        }
        Assert.fail(what + " never came; events " + events);
        return null;
    }

    @Test
    public void eventsArriveTypedOnTheUiThread() throws Exception {
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            String url = server.url() + "page";
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(400, 600, 1.0f));
                browser.set(b);
                b.setChromeClient(new CefriumChromeClient() {
                    @Override public void onTitleChanged(CefriumBrowser x, String t) {
                        add("title " + t);
                    }
                    @Override public void onConsoleMessage(CefriumBrowser x,
                            CefriumConsoleMessage m) {
                        add("console " + m.level() + " " + m.message() + " " + m.lineNumber());
                    }
                    @Override public void onFaviconUrlsChanged(CefriumBrowser x,
                            List<String> urls) {
                        add("favicon " + urls);
                    }
                    @Override public void onFindResult(CefriumBrowser x, int active, int n) {
                        add("find " + active + "/" + n);
                    }
                    @Override public void onStatusMessage(CefriumBrowser x, String text) {
                        add("status " + text);
                    }
                });
                b.setNavigationClient(new CefriumNavigationClient() {
                    @Override public void onUrlChanged(CefriumBrowser x, String u) {
                        add("url " + u);
                    }
                    @Override public void onLoadStart(CefriumBrowser x, String u) {
                        add("start " + u);
                    }
                    @Override public void onLoadingStateChanged(CefriumBrowser x,
                            boolean loading, boolean back, boolean fwd) {
                        add("loading " + loading);
                    }
                    @Override public void onProgressChanged(CefriumBrowser x, int p) {
                        add("progress " + p);
                    }
                    @Override public void onLoadError(CefriumBrowser x, CefriumLoadError e) {
                        add("error " + e.getErrorCode() + " " + e.getFailedUrl());
                    }
                    @Override public void onRenderProcessTerminated(CefriumBrowser x,
                            CefriumRendererTerminationStatus s, int code) {
                        add("terminated " + s);
                    }
                });
                b.loadUrl(url);
            });

            await(e -> e.equals("url " + url), "the URL");
            await(e -> e.equals("start " + url), "the load start");
            await(e -> e.equals("title second"), "the title");
            await(e -> e.equals("console WARNING w-msg 2"), "the warning");
            await(e -> e.equals("console ERROR e-msg 3"), "the error");
            await(e -> e.equals("favicon [" + server.url() + "favicon-x.png]"), "the favicon");
            await(e -> e.equals("progress 100"), "progress 100");
            await(e -> e.equals("loading false"), "the load end");

            Thread.sleep(1000);  // input before the first frame is dropped
            activityRule.getActivity().runOnUiThread(() -> browser.get().sendMouseMove(100, 100));
            await(e -> e.equals("status " + server.url() + "hovered"), "the link status");

            activityRule.getActivity().runOnUiThread(
                    () -> browser.get().find("needle", true, false, false));
            await(e -> e.endsWith("/2") && e.startsWith("find "), "the find result");

            // A port that was free a moment ago: nothing listens, so the
            // connection is refused (a low port like 1 is refused by policy).
            int closed;
            try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
                closed = s.getLocalPort();
            }
            String refused = "http://127.0.0.1:" + closed + "/refused";
            activityRule.getActivity().runOnUiThread(() -> browser.get().loadUrl(refused));
            await(e -> e.equals("error -102 " + refused), "the load error");

            activityRule.getActivity().runOnUiThread(
                    () -> browser.get().loadUrl("chrome://crash"));
            // Android cannot tell a crash of the page in use from a low-memory
            // kill (OOM_PROTECTED): either is right, a missing call is not.
            await(e -> e.equals("terminated CRASHED") || e.equals("terminated OOM"),
                    "the renderer crash");

            for (String e : events) {
                Assert.assertFalse("an event came off the UI thread: " + e,
                        e.startsWith("OFF-UI"));
            }
        }
    }
}
