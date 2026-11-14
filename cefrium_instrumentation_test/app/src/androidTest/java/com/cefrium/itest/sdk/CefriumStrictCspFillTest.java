// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumContentRequest;
import com.cefrium.CefriumContentResponse;
import com.cefrium.CefriumResourceInterceptor;

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
 * An app fills a form on a page with a strict Content-Security-Policy
 * ("script-src 'self'": no inline script, no eval), as an agent app does
 * (Operit re-tests this on every release). The page's own inline script is
 * blocked, which proves the policy is in force; the app's script
 * (evaluateJavaScript, with and without a result) and real key presses still
 * fill the field.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumStrictCspFillTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String URL = "https://csp.example/form";
    private static final String PAGE = "<!doctype html><title>csp-page</title>"
            + "<input id=i autofocus style='font-size:30px;width:90%'>"
            + "<script>document.title = 'inline-ran';</script>";

    private final List<String> titles = new CopyOnWriteArrayList<>();

    private static void awaitTitle(List<String> titles, String t) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < end) {
            if (titles.contains(t)) return;
            Thread.sleep(100);
        }
        Assert.fail("title never became " + t + "; titles " + titles);
    }

    @Test
    public void appScriptAndKeysFillUnderStrictCsp() throws Exception {
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        activityRule.getActivity().runOnUiThread(() -> {
            CefriumBrowser b = browsers.createWithSurface(activityRule.getActivity());
            browser.set(b);
            activityRule.getActivity().addContentView(b.getSurfaceContainer(),
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            b.setOnTitleChangedListener(titles::add);
            b.setResourceInterceptor(new CefriumResourceInterceptor() {
                @Override
                public CefriumContentResponse shouldInterceptRequest(CefriumContentRequest r) {
                    if (!r.getUrl().startsWith(URL)) return null;
                    return CefriumContentResponse.ofText("text/html", PAGE)
                            .setHeader("Content-Security-Policy", "script-src 'self'");
                }
            });
            b.loadUrl(URL);
        });
        awaitTitle(titles, "csp-page");
        Thread.sleep(1500);
        Assert.assertFalse("the policy is not in force: the inline script ran " + titles,
                titles.contains("inline-ran"));

        // The app's script, fire-and-forget.
        activityRule.getActivity().runOnUiThread(() -> browser.get().evaluateJavaScript(
                "document.getElementById('i').value = 'filled';"
                + " document.title = 'value:' + document.getElementById('i').value;"));
        awaitTitle(titles, "value:filled");

        // The app's script with a result (the DevTools path).
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        activityRule.getActivity().runOnUiThread(() -> browser.get().evaluateJavaScript(
                "document.getElementById('i').value + '-read'", v -> {
                    result.set(v);
                    got.countDown();
                }));
        Assert.assertTrue("no result", got.await(15, TimeUnit.SECONDS));
        Assert.assertEquals("\"filled-read\"", result.get());

        // Real keys into the focused field.
        activityRule.getActivity().runOnUiThread(() -> {
            browser.get().getSurfaceContainer().requestFocus();
            browser.get().evaluateJavaScript("var e = document.getElementById('i');"
                    + " e.value = ''; e.focus();");
        });
        Thread.sleep(1500);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_O);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_K);
        Thread.sleep(1000);
        activityRule.getActivity().runOnUiThread(() -> browser.get().evaluateJavaScript(
                "document.title = 'typed:' + document.getElementById('i').value;"));
        awaitTitle(titles, "typed:ok");
    }
}
