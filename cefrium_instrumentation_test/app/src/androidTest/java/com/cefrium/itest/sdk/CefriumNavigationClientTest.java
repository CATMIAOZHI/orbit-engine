// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumNavigationClient;
import com.cefrium.CefriumNavigationRequest;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * CefriumNavigationClient.onBeforeNavigate, as WebViewClient's
 * shouldOverrideUrlLoading: a tapped link (with a gesture), a script
 * navigation (without), an iframe (not the main frame) and the server
 * redirect of an app load are offered on the UI thread; returning true stops
 * the navigation before its request is sent; an app load itself is not
 * offered.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumNavigationClientTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE = "<!doctype html><html><head>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<script>document.title = location.pathname;</script></head>"
            + "<body style='margin:0'><a href='/cancel-me'"
            + " style='display:block;height:2000px;background:#cde'>link</a></body></html>";

    /** One offered navigation: path, flags and whether it came on the UI thread. */
    private static final class Seen {
        final String path;
        final boolean main, redirect, gesture, uiThread;
        final String method;

        Seen(CefriumNavigationRequest r) {
            String u = r.getUrl();
            path = u.substring(u.indexOf('/', u.indexOf("//") + 2));
            main = r.isForMainFrame();
            redirect = r.isRedirect();
            gesture = r.hasGesture();
            method = r.getMethod();
            uiThread = Looper.myLooper() == Looper.getMainLooper();
        }

        @Override
        public String toString() {
            return path + (main ? " main" : " sub") + (redirect ? " redirect" : "")
                    + (gesture ? " gesture" : "") + (uiThread ? "" : " OFF-UI");
        }
    }

    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final List<String> titles = new CopyOnWriteArrayList<>();

    private Seen awaitSeen(String path) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) {
            for (Seen s : seen) if (s.path.equals(path)) return s;
            Thread.sleep(100);
        }
        Assert.fail(path + " was not offered; offered " + seen + ", titles " + titles);
        return null;
    }

    private void awaitTitle(String title) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < end) {
            if (!titles.isEmpty() && titles.get(titles.size() - 1).equals(title)) return;
            Thread.sleep(100);
        }
        Assert.fail("title never became " + title + "; titles " + titles + ", offered " + seen);
    }

    private static boolean requested(LocalHttpServer server, Predicate<String> p) {
        for (String r : server.requests) if (p.test(r.split(" ")[0])) return true;
        return false;
    }

    private void navigations(boolean offscreen) throws Exception {
        HostActivity activity = activityRule.getActivity();
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = offscreen
                        ? browsers.track(new CefriumBrowser(400, 600, 1.0f))
                        : browsers.createWithSurface(activity);
                browser.set(b);
                if (!offscreen) {
                    activity.addContentView(b.getSurfaceContainer(), new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                }
                b.setOnTitleChangedListener(titles::add);
                b.setNavigationClient(new CefriumNavigationClient() {
                    @Override
                    public boolean onBeforeNavigate(CefriumBrowser br,
                            CefriumNavigationRequest r) {
                        Seen s = new Seen(r);
                        seen.add(s);
                        return s.path.equals("/cancel-me");
                    }
                });
                b.loadUrl(server.url() + "start");
            });

            // An app load is not offered.
            awaitTitle("/start");
            Assert.assertTrue("an app load was offered: " + seen, seen.isEmpty());
            Thread.sleep(1000);  // input before the first frame is dropped

            // A tapped link: offered with a gesture, and cancelling stops it.
            activity.runOnUiThread(() -> browser.get().sendTap(100, 100));
            Seen tap = awaitSeen("/cancel-me");
            Assert.assertTrue(tap.toString(), tap.main && tap.gesture && !tap.redirect
                    && tap.uiThread && tap.method.equals("GET"));
            Thread.sleep(2000);
            Assert.assertFalse("the cancelled navigation was sent",
                    requested(server, t -> t.equals("/cancel-me")));
            Assert.assertEquals("/start", titles.get(titles.size() - 1));

            // A script navigation: offered without a gesture, and it goes on.
            activity.runOnUiThread(() ->
                    browser.get().evaluateJavaScript("location.href = '/script-nav';"));
            Seen script = awaitSeen("/script-nav");
            Assert.assertTrue(script.toString(), script.main && !script.gesture
                    && script.uiThread);
            awaitTitle("/script-nav");

            // An iframe: offered, not the main frame.
            activity.runOnUiThread(() -> browser.get().evaluateJavaScript(
                    "var f = document.createElement('iframe'); f.src = '/frame';"
                    + " document.body.appendChild(f);"));
            Seen frame = awaitSeen("/frame");
            Assert.assertTrue(frame.toString(), !frame.main && frame.uiThread);

            // The server redirect of an app load: the redirect is offered, the
            // app load is not.
            activity.runOnUiThread(() -> browser.get().loadUrl(
                    server.url() + "redirect/after-redirect"));
            Seen redirect = awaitSeen("/after-redirect");
            Assert.assertTrue(redirect.toString(), redirect.main && redirect.redirect
                    && redirect.uiThread);
            awaitTitle("/after-redirect");
            for (Seen s : seen) {
                Assert.assertFalse("the app load was offered: " + seen,
                        s.path.startsWith(LocalHttpServer.REDIRECT));
            }
        }
    }

    @Test
    public void navigationsAreOfferedSurface() throws Exception {
        navigations(false);
    }

    @Test
    public void navigationsAreOfferedOffscreen() throws Exception {
        navigations(true);
    }
}
