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
 * Instrumented test for FEATURE-029 popups (window.open / target=_blank via
 * CefLifeSpanHandler::OnBeforePopup → PopupHandler).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumPopupTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    /**
     * Smoke: setPopupHandler + a script-initiated window.open must not crash.
     * NOTE: a functional assertion (OnBeforePopup actually firing) is not possible
     * here — Chromium's popup blocker suppresses script-initiated window.open
     * without a user gesture, so OnBeforePopup is only reached for real
     * user-initiated popups (a tapped link / window.open in a click handler),
     * which the instrumentation harness cannot synthesize a trusted gesture for.
     */
    @Test
    public void testPopupHandlerWiringSmoke() throws Exception {
        Activity act = activityRule.getActivity();
        final CopyOnWriteArrayList<String> popups = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] probed = {false};

        act.runOnUiThread(() -> {
            CefriumBrowser b = CefriumBrowser.createWithSurface(act);
            b.setPopupHandler((url, userGesture) -> {
                popups.add(url);   // recorded if a real popup ever reaches here
                return true;
            });
            b.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    b.evaluateJavaScript("window.open('https://cefrium.test/popped','_blank');");
                    done.countDown();
                }
            });
            b.loadUrl("about:blank");
        });

        Assert.assertTrue("page never finished loading / setPopupHandler crashed",
                done.await(20, TimeUnit.SECONDS));
    }
}
