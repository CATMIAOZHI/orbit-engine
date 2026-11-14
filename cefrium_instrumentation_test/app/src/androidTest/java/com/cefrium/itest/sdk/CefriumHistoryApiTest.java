// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * WebView's history calls: canGoForward, canGoBackOrForward and
 * goBackOrForward(n) move through the history; getTitle and getProgress
 * report it; saveState carries the whole history, so a new browser restored
 * from it can go back and forward (it carried only the URL); and
 * setUserAgentOverride(null) gives the engine's User-Agent back.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumHistoryApiTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE =
            "<!doctype html><script>document.title = location.pathname;</script>";

    private <T> T onMain(Supplier<T> s) {
        AtomicReference<T> r = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> r.set(s.get()));
        return r.get();
    }

    private static void awaitLast(List<String> titles, String t) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < end) {
            if (!titles.isEmpty() && titles.get(titles.size() - 1).equals(t)) return;
            Thread.sleep(100);
        }
        Assert.fail("title never became " + t + "; titles " + titles);
    }

    private CefriumBrowser surface(List<String> titles) {
        return onMain(() -> {
            CefriumBrowser b = browsers.createWithSurface(activityRule.getActivity());
            activityRule.getActivity().addContentView(b.getSurfaceContainer(),
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400));
            b.setOnTitleChangedListener(titles::add);
            return b;
        });
    }

    @Test
    public void historyCallsAndSavedState() throws Exception {
        List<String> titles = new CopyOnWriteArrayList<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = surface(titles);
            for (String p : new String[] {"p1", "p2", "p3"}) {
                onMain(() -> { b.loadUrl(server.url() + p); return null; });
                awaitLast(titles, "/" + p);
            }
            Thread.sleep(500);
            Assert.assertEquals("/p3", onMain(b::getTitle));
            Assert.assertEquals(Integer.valueOf(100), onMain(b::getProgress));
            Assert.assertFalse(onMain(b::canGoForward));
            Assert.assertTrue(onMain(() -> b.canGoBackOrForward(-2)));
            Assert.assertFalse(onMain(() -> b.canGoBackOrForward(-3)));

            onMain(() -> { b.goBackOrForward(-2); return null; });
            awaitLast(titles, "/p1");
            Thread.sleep(500);
            Assert.assertTrue(onMain(b::canGoForward));
            Assert.assertTrue(onMain(() -> b.canGoBackOrForward(2)));
            Assert.assertFalse(onMain(() -> b.canGoBackOrForward(3)));

            // The saved state restores the history in a new browser.
            Bundle saved = onMain(b::saveState);
            List<String> restoredTitles = new CopyOnWriteArrayList<>();
            CefriumBrowser r = surface(restoredTitles);
            onMain(() -> { r.restoreState(saved); return null; });
            awaitLast(restoredTitles, "/p1");
            Thread.sleep(500);
            Assert.assertTrue("the restored browser has no forward history",
                    onMain(r::canGoForward));
            onMain(() -> { r.goForward(); return null; });
            awaitLast(restoredTitles, "/p2");
        }
    }

    @Test
    public void nullUserAgentOverrideRestoresTheEngines() throws Exception {
        List<String> titles = new CopyOnWriteArrayList<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = surface(titles);
            onMain(() -> { b.setUserAgentOverride("Cefrium-Test-UA/1.0");
                    b.loadUrl(server.url() + "ua1"); return null; });
            awaitLast(titles, "/ua1");
            onMain(() -> { b.setUserAgentOverride(null);
                    b.loadUrl(server.url() + "ua2"); return null; });
            awaitLast(titles, "/ua2");
            String first = null;
            String second = null;
            for (String req : server.requests) {
                if (req.startsWith("/ua1 ")) first = req.substring(5);
                if (req.startsWith("/ua2 ")) second = req.substring(5);
            }
            Assert.assertEquals("Cefrium-Test-UA/1.0", first);
            Assert.assertNotNull(second);
            Assert.assertTrue("not the engine's User-Agent: " + second,
                    second.contains("Chrome/") && !second.contains("Cefrium-Test-UA"));
        }
    }
}
