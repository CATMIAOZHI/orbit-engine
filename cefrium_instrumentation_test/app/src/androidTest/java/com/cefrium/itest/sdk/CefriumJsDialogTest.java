package com.cefrium.itest.sdk;

import com.cefrium.itest.HostActivity;

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

/**
 * Instrumented test for FEATURE-028 JS dialogs (alert/confirm/prompt via
 * CefJSDialogHandler). Uses a custom JsDialogHandler that records + auto-continues
 * (no UI), so it's deterministic.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumJsDialogTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Test
    public void testAlertConfirmPrompt() throws Exception {
        Activity act = activityRule.getActivity();
        final CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
        final CountDownLatch three = new CountDownLatch(3);
        final boolean[] probed = {false};

        act.runOnUiThread(() -> {
            CefriumBrowser b = CefriumBrowser.createWithSurface(act);
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
}
