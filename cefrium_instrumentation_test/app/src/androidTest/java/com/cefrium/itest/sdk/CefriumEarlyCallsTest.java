// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

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
 * setFocus and setDarkMode made before the engine browser exists take effect
 * once it does: an off-screen browser before its first load, and a window in
 * onCreateWindow, where an app shows it focused (with the issue #20 setup, a
 * sign-in page in such a window took no typing). Read from the page: document.hasFocus()
 * and prefers-color-scheme.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumEarlyCallsTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    // Reports its state on load and whenever the focus changes.
    private static final String PAGE = "<html><body><script>"
            + "function report() { document.title = 'state:' + document.hasFocus()"
            + "  + ':' + matchMedia('(prefers-color-scheme: dark)').matches; }"
            + "addEventListener('focus', report); addEventListener('blur', report);"
            + "matchMedia('(prefers-color-scheme: dark)').addEventListener('change', report);"
            + "if (location.search == '?opener') document.title = 'ready';"
            + "else setTimeout(report, 300);"
            + "</script></body></html>";

    private static final String WANT = "state:true:true";

    private static final String OPEN =
            "{\"expression\":\"window.open('/?child','w');1\",\"userGesture\":true}";

    private final List<String> titles = new CopyOnWriteArrayList<>();
    private final AtomicReference<CefriumBrowser> probed = new AtomicReference<>();

    private void configure(CefriumBrowser b, CountDownLatch reached) {
        probed.set(b);
        b.setOnTitleChangedListener(t -> {
            titles.add(t);
            if (WANT.equals(t)) reached.countDown();
        });
        b.setFocus(true);
        b.setDarkMode(true);
    }

    // On a timeout, the page's own state goes in the message: a title that
    // never arrived and a state that is wrong read differently.
    private void await(CountDownLatch reached) throws Exception {
        if (reached.await(20, TimeUnit.SECONDS)) return;
        AtomicReference<String> page = new AtomicReference<>("no browser");
        CountDownLatch read = new CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() -> {
            CefriumBrowser b = probed.get();
            if (b == null) {
                read.countDown();
                return;
            }
            b.executeDevToolsMethod("Runtime.evaluate",
                    "{\"expression\":\"location.href + ' ' + document.title\"}",
                    (ok, json) -> {
                        page.set(json);
                        read.countDown();
                    });
        });
        read.await(10, TimeUnit.SECONDS);
        Assert.fail("the page never reported " + WANT + "; titles " + titles
                + "; page " + page.get());
    }

    @Test
    public void offscreenBeforeTheFirstLoad() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(400, 300, 1.0f));
                configure(b, reached);
                b.loadUrl(server.url());
            });
            await(reached);
        }
    }

    @Test
    public void windowInOnCreateWindow() throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch reached = new CountDownLatch(1);
        AtomicReference<CefriumBrowser> opener = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(400, 300, 1.0f));
                opener.set(b);
                b.setOnTitleChangedListener(t -> {
                    if ("ready".equals(t)) ready.countDown();
                });
                b.setPopupHandler(new CefriumBrowser.PopupHandler() {
                    @Override public boolean onOpenPopup(String url, boolean gesture) {
                        return false;
                    }

                    @Override public boolean onCreateWindow(CefriumBrowser popup, String url,
                            boolean gesture) {
                        configure(browsers.track(popup), reached);
                        return true;
                    }
                });
                b.loadUrl(server.url() + "?opener");
            });
            Assert.assertTrue("the opener did not load", ready.await(30, TimeUnit.SECONDS));
            activity.runOnUiThread(() ->
                    opener.get().executeDevToolsMethod("Runtime.evaluate", OPEN, (ok, j) -> {}));
            await(reached);
        }
    }
}
