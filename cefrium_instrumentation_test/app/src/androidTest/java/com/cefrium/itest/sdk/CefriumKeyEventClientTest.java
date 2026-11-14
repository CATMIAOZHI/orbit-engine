// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Instrumentation;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keys through CefriumChromeClient, on a surface browser with real key
 * events: shouldOverrideKeyEvent sees each key as an Android KeyEvent (code,
 * action, meta) before the page and can keep it from the page;
 * onUnhandledKeyEvent gets the keys the page did not cancel. All on the UI
 * thread.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumKeyEventClientTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    // The page lists the keys it sees and cancels "c".
    private static final String PAGE = "<!doctype html><title>ready</title><script>"
            + "const seen = [];"
            + "addEventListener('keydown', e => { if (e.key === 'c') e.preventDefault();"
            + "  seen.push(e.key); document.title = 'keys:' + seen.join(','); });"
            + "</script><body>keys</body>";

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> titles = new CopyOnWriteArrayList<>();

    private void add(String e) {
        events.add(Looper.myLooper() == Looper.getMainLooper() ? e : "OFF-UI " + e);
    }

    private static String of(KeyEvent e) {
        return e.getAction() + " " + e.getKeyCode() + " " + e.getMetaState();
    }

    @Test
    public void keysAreOfferedAsKeyEvents() throws Exception {
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activityRule.getActivity());
                browser.set(b);
                activityRule.getActivity().addContentView(b.getSurfaceContainer(),
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                b.setOnTitleChangedListener(titles::add);
                b.setChromeClient(new CefriumChromeClient() {
                    @Override
                    public boolean shouldOverrideKeyEvent(CefriumBrowser x, KeyEvent e) {
                        add("pre " + of(e));
                        return e.getKeyCode() == KeyEvent.KEYCODE_B;
                    }
                    @Override
                    public void onUnhandledKeyEvent(CefriumBrowser x, KeyEvent e) {
                        add("post " + of(e));
                    }
                });
                b.loadUrl(server.url());
            });
            long end = SystemClock.uptimeMillis() + 20000;
            while (!titles.contains("ready") && SystemClock.uptimeMillis() < end) {
                Thread.sleep(100);
            }
            Assert.assertTrue("the page did not load", titles.contains("ready"));
            activityRule.getActivity().runOnUiThread(
                    () -> browser.get().getSurfaceContainer().requestFocus());
            Thread.sleep(1500);

            Instrumentation inst = InstrumentationRegistry.getInstrumentation();
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_A);
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_B);
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_C);
            long t = SystemClock.uptimeMillis();
            inst.sendKeySync(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0,
                    KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON));
            inst.sendKeySync(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0,
                    KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON));

            end = SystemClock.uptimeMillis() + 10000;
            while (!titles.contains("keys:a,c,A") && SystemClock.uptimeMillis() < end) {
                Thread.sleep(100);
            }
            Thread.sleep(500);
            String all = events + " titles " + titles;
            // The page saw a, c and A, not the b the client kept.
            Assert.assertTrue(all, titles.contains("keys:a,c,A"));
            Assert.assertFalse(all, titles.toString().contains("b"));
            // Before the page: each key as an Android KeyEvent.
            Assert.assertTrue(all, events.contains("pre 0 29 0"));
            Assert.assertTrue(all, events.contains("pre 1 29 0"));
            Assert.assertTrue(all, events.contains("pre 0 30 0"));
            Assert.assertTrue(all, events.contains("pre 0 31 0"));
            Assert.assertTrue(all, events.contains("pre 0 29 1"));
            // After it: a (not cancelled), never c (cancelled) or b (kept).
            Assert.assertTrue(all, events.contains("post 0 29 0"));
            for (String e : events) {
                Assert.assertFalse(all, e.startsWith("OFF-UI"));
                Assert.assertFalse(all, e.startsWith("post 0 31"));
                Assert.assertFalse(all, e.startsWith("post 0 30"));
            }
        }
    }
}
