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
import com.cefrium.CefriumJsDialogType;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JavaScript dialogs through CefriumChromeClient: alert, confirm and prompt
 * arrive typed on the UI thread, are answered from another thread (only the
 * first answer counts) and the page gets the answers; a beforeunload prompt
 * kept by the client stops the navigation, and answered "leave" lets it go.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumJsDialogClientTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private final List<String> titles = new CopyOnWriteArrayList<>();

    private void awaitTitle(String title) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < end) {
            if (titles.contains(title)) return;
            Thread.sleep(100);
        }
        Assert.fail("title never became " + title + "; titles " + titles);
    }

    private CefriumBrowser open(String url, CefriumChromeClient client) throws Exception {
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        activityRule.getActivity().runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(400, 600, 1.0f));
            browser.set(b);
            b.setOnTitleChangedListener(titles::add);
            b.setChromeClient(client);
            b.loadUrl(url);
        });
        awaitTitle("ready");
        return browser.get();
    }

    @Test
    public void dialogsArriveTypedAndGetTheAnswers() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        try (LocalHttpServer server = new LocalHttpServer(
                "<!doctype html><title>ready</title>")) {
            CefriumBrowser b = open(server.url(), new CefriumChromeClient() {
                @Override
                public boolean onJsDialog(CefriumBrowser x, CefriumJsDialogType type,
                        String message, String defaultText,
                        CefriumBrowser.JsDialogCallback cb) {
                    seen.add((Looper.myLooper() == Looper.getMainLooper() ? "" : "OFF-UI ")
                            + type + " " + message + " [" + defaultText + "]");
                    new Thread(() -> {
                        cb.continueDialog(true, "typed");
                        cb.continueDialog(false, "ignored");  // only the first counts
                    }).start();
                    return true;
                }
            });
            activityRule.getActivity().runOnUiThread(() -> b.evaluateJavaScript(
                    "setTimeout(() => { alert('a1'); var c = confirm('c1');"
                    + " var p = prompt('p1', 'def');"
                    + " document.title = 'done:' + c + ',' + p; }, 0);"));
            awaitTitle("done:true,typed");
            Assert.assertEquals("[ALERT a1 [], CONFIRM c1 [], PROMPT p1 [def]]",
                    seen.toString());
        }
    }

    @Test
    public void beforeUnloadKeptThenLeft() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        String page = "<!doctype html><title>ready</title><body style='margin:0'>"
                + "<div style='height:600px'>x</div><script>"
                + "addEventListener('beforeunload', e => { e.preventDefault();"
                + " e.returnValue = 'unsaved'; });"
                + "addEventListener('pointerdown', () => document.title = 'touched');"
                + "</script>";
        try (LocalHttpServer server = new LocalHttpServer(page)) {
            CefriumBrowser b = open(server.url(), new CefriumChromeClient() {
                @Override
                public boolean onBeforeUnloadDialog(CefriumBrowser x, String message,
                        boolean isReload, CefriumBrowser.JsDialogCallback cb) {
                    // The first time stay, the second time leave.
                    cb.continueDialog(asked.incrementAndGet() > 1, null);
                    return true;
                }
            });
            // A page gets a beforeunload prompt only after the user touched it.
            Thread.sleep(1000);
            activityRule.getActivity().runOnUiThread(() -> {
                b.sendTouchDown(100, 100);
                b.sendTouchUp(100, 100);
            });
            awaitTitle("touched");

            String other = server.localhostUrl() + "other";
            activityRule.getActivity().runOnUiThread(() -> b.loadUrl(other));
            long end = SystemClock.uptimeMillis() + 10000;
            while (asked.get() < 1 && SystemClock.uptimeMillis() < end) Thread.sleep(100);
            Assert.assertEquals("the beforeunload prompt was not offered", 1, asked.get());
            Thread.sleep(2000);
            Assert.assertEquals("the page was left although the client stayed",
                    "touched", titles.get(titles.size() - 1));

            activityRule.getActivity().runOnUiThread(() -> b.loadUrl(other));
            end = SystemClock.uptimeMillis() + 10000;
            while (asked.get() < 2 && SystemClock.uptimeMillis() < end) Thread.sleep(100);
            Assert.assertEquals(2, asked.get());
            // The other page is the same HTML on another origin.
            long until = SystemClock.uptimeMillis() + 15000;
            while (SystemClock.uptimeMillis() < until
                    && !"ready".equals(titles.get(titles.size() - 1))) {
                Thread.sleep(100);
            }
            Assert.assertEquals("the page was not left after leave was answered",
                    "ready", titles.get(titles.size() - 1));
        }
    }
}
