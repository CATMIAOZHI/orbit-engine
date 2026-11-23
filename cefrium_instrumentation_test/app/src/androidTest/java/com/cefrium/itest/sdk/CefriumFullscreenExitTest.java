// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
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
 * The app hears both edges of fullscreen: the page entering it and the page
 * leaving it with document.exitFullscreen(). An app that missed the exit
 * kept the system bars hidden and its own UI gone.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumFullscreenExitTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    // The page enters on a tap, as on a real site: Chromium refuses fullscreen
    // to a view without focus (SuppressFullscreenFromUnfocusedView), and the
    // tap is what gives the browser's view focus.
    private static final String PAGE = "<!doctype html><title>ready</title>"
            + "<body style='margin:0'><div id=v style='height:100vh;background:#08f'"
            + " onclick='this.requestFullscreen()'>v</div>"
            + "<script>document.addEventListener('fullscreenchange', () =>"
            + " document.title = document.fullscreenElement ? 'in' : 'out');</script>";

    private final List<String> titles = new CopyOnWriteArrayList<>();
    private final List<Boolean> edges = new CopyOnWriteArrayList<>();

    private void await(String what, java.util.function.BooleanSupplier ok) throws Exception {
        long end = SystemClock.uptimeMillis() + 15000;
        while (!ok.getAsBoolean()) {
            if (SystemClock.uptimeMillis() > end) {
                Assert.fail(what + "; titles " + titles + ", edges " + edges);
            }
            Thread.sleep(100);
        }
    }

    private void tap(CefriumBrowser b) throws Exception {
        long down = SystemClock.uptimeMillis();
        send(b, down, MotionEvent.ACTION_DOWN);
        Thread.sleep(60);
        send(b, down, MotionEvent.ACTION_UP);
    }

    /** A touch at the centre of the browser's view, dispatched on the UI thread. */
    private void send(CefriumBrowser b, long down, int action) {
        activityRule.getActivity().runOnUiThread(() -> {
            View v = b.getSurfaceContainer();
            MotionEvent ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    v.getWidth() / 2f, v.getHeight() / 2f, 0);
            ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            v.dispatchTouchEvent(ev);
            ev.recycle();
        });
    }

    private void eval(AtomicReference<CefriumBrowser> b, String js) {
        activityRule.getActivity().runOnUiThread(() -> b.get().executeDevToolsMethod(
                "Runtime.evaluate",
                "{\"expression\":\"" + js + ";1\",\"userGesture\":true}", (ok, r) -> {}));
    }

    @Test
    public void exitIsReported() throws Exception {
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
                    public void onFullscreenChanged(CefriumBrowser x, boolean fullscreen) {
                        edges.add(fullscreen);
                    }
                });
                b.loadUrl(server.url());
            });
            await("no load", () -> titles.contains("ready"));

            Thread.sleep(1000);
            tap(browser.get());
            await("the page did not enter", () -> titles.contains("in"));
            await("the app did not hear the entry", () -> edges.contains(true));

            eval(browser, "document.exitFullscreen()");
            await("the page did not leave", () -> titles.contains("out"));
            await("the app did not hear the exit",
                    () -> !edges.isEmpty() && !edges.get(edges.size() - 1));
        }
    }

    /** The same in a window the page opened (window.open, target=_blank). */
    @Test
    public void exitIsReportedInAWindow() throws Exception {
        AtomicReference<CefriumBrowser> opener = new AtomicReference<>();
        AtomicReference<CefriumBrowser> window = new AtomicReference<>();
        List<String> openerTitles = new CopyOnWriteArrayList<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activityRule.getActivity());
                opener.set(b);
                b.setOnTitleChangedListener(openerTitles::add);
                b.setPopupHandler(new CefriumBrowser.PopupHandler() {
                    @Override
                    public boolean onOpenPopup(String url, boolean gesture) {
                        return false;
                    }

                    @Override
                    public boolean onCreateWindow(CefriumBrowser popup, String url,
                            boolean gesture) {
                        browsers.track(popup);
                        window.set(popup);
                        activityRule.getActivity().addContentView(popup.getSurfaceContainer(),
                                new FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT));
                        popup.setOnTitleChangedListener(titles::add);
                        popup.setChromeClient(new CefriumChromeClient() {
                            @Override
                            public void onFullscreenChanged(CefriumBrowser x, boolean f) {
                                edges.add(f);
                            }
                        });
                        return true;
                    }
                });
                b.loadUrl(server.url());
            });
            await("no load", () -> openerTitles.contains("ready"));
            eval(opener, "window.open('/?w', 'w')");
            await("no window", () -> titles.contains("ready"));

            Thread.sleep(1000);
            tap(window.get());
            await("the page did not enter", () -> titles.contains("in"));
            await("the app did not hear the entry", () -> edges.contains(true));

            eval(window, "document.exitFullscreen()");
            await("the page did not leave", () -> titles.contains("out"));
            await("the app did not hear the exit",
                    () -> !edges.isEmpty() && !edges.get(edges.size() - 1));
        }
    }
}
