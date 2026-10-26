// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;
import android.os.Looper;
import android.os.Handler;

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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instrumented test for FEATURE-028 JS dialogs (alert/confirm/prompt via
 * CefJSDialogHandler). Uses a custom JsDialogHandler that records + auto-continues
 * (no UI), so it's deterministic.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumJsDialogTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    @Test
    public void testAlertConfirmPrompt() throws Exception {
        Activity act = activityRule.getActivity();
        final CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
        final CountDownLatch three = new CountDownLatch(3);
        final boolean[] probed = {false};

        act.runOnUiThread(() -> {
            CefriumBrowser b = browsers.createWithSurface(act);
            b.setJsDialogHandler((type, message, defaultPrompt, cb) -> {
                seen.add(type + ":" + message);
                cb.continueDialog(true, "promptval");  // OK + input
                three.countDown();
                return true;  // handled (no UI)
            });
            b.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    b.evaluateJavaScript("alert('A'); confirm('C'); prompt('P','d');");
                }
            });
            b.loadUrl("about:blank");
        });

        Assert.assertTrue("not all three JS dialogs were delivered; saw: " + seen,
                three.await(20, TimeUnit.SECONDS));
        Assert.assertTrue("alert missing; saw: " + seen,
                seen.contains(CefriumBrowser.JS_DIALOG_ALERT + ":A"));
        Assert.assertTrue("confirm missing; saw: " + seen,
                seen.contains(CefriumBrowser.JS_DIALOG_CONFIRM + ":C"));
        Assert.assertTrue("prompt missing; saw: " + seen,
                seen.contains(CefriumBrowser.JS_DIALOG_PROMPT + ":P"));
    }

    /**
     * SC-rc-23: after the embedder answers an alert(), injected mouse and touch
     * input still reaches the page's DOM listeners. A dialog pauses the
     * renderer and Chromium ignores input while one is showing; this checks
     * the gate reopens once it is answered.
     */
    @Test
    public void inputReachesThePageAfterAnAnsweredAlert() throws Exception {
        final String page = "<!doctype html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "</head><body style=\"margin:0;width:100vw;height:100vh\"><script>"
                + "var c=0,t=0;function show(){document.title='c'+c+'t'+t;}"
                + "document.addEventListener('click',function(){c++;show();});"
                + "document.addEventListener('touchstart',function(){t++;show();},{passive:true});"
                + "</script></body></html>";
        Activity act = activityRule.getActivity();
        final Handler main = new Handler(Looper.getMainLooper());
        final CountDownLatch answered = new CountDownLatch(1);
        final CountDownLatch clicked = new CountDownLatch(1);
        final CountDownLatch tapped = new CountDownLatch(1);
        final AtomicReference<String> last = new AtomicReference<>();
        final boolean[] probed = {false};
        try (LocalHttpServer server = new LocalHttpServer(page)) {
            act.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(act);
                act.setContentView(b.getSurfaceContainer());
                b.setJsDialogHandler((type, message, defaultPrompt, cb) -> {
                    cb.continueDialog(true, null);
                    answered.countDown();
                    // Inject only after the dialog has been answered.
                    main.postDelayed(() -> b.sendLeftClick(150, 300), 500);
                    return true;
                });
                b.setOnTitleChangedListener(title -> {
                    if (title == null) return;
                    last.set(title);
                    if (title.startsWith("c1t0")) {
                        clicked.countDown();
                        main.postDelayed(() -> b.sendTap(150, 300), 300);
                    } else if (title.startsWith("c") && title.contains("t1")) {
                        tapped.countDown();
                    }
                });
                b.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                    if (!loading && !probed[0]) {
                        probed[0] = true;
                        b.evaluateJavaScript("alert('before input');");
                    }
                });
                b.loadUrl(server.url());
            });
            Assert.assertTrue("the alert never reached the handler",
                    answered.await(20, TimeUnit.SECONDS));
            Assert.assertTrue("the click after the answered alert never reached the DOM; "
                    + "title=" + last.get(), clicked.await(15, TimeUnit.SECONDS));
            Assert.assertTrue("the tap after the answered alert never reached the DOM; "
                    + "title=" + last.get(), tapped.await(15, TimeUnit.SECONDS));
        }
    }
}
