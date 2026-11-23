// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A native text field next to the browser keeps its focus: creating the
 * browser and the window regaining focus (a dialog closing, the app coming
 * back) must not move it to the page. A tap on the page still focuses it.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumNativeFieldFocusTest {
    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private boolean ui(java.util.function.BooleanSupplier f) throws Exception {
        AtomicBoolean r = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() -> {
            r.set(f.getAsBoolean());
            done.countDown();
        });
        Assert.assertTrue(done.await(5, TimeUnit.SECONDS));
        return r.get();
    }

    private void tap(CefriumBrowser b) throws Exception {
        long down = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            activityRule.getActivity().runOnUiThread(() -> {
                View v = b.getSurfaceContainer();
                MotionEvent ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        v.getWidth() / 2f, v.getHeight() / 2f, 0);
                ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                v.dispatchTouchEvent(ev);
                ev.recycle();
            });
            Thread.sleep(60);
        }
    }

    @Test
    public void theFieldKeepsFocus() throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<EditText> field = new AtomicReference<>();
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        AtomicReference<String> title = new AtomicReference<>("");
        try (LocalHttpServer server = new LocalHttpServer(
                "<!doctype html><title>ready</title><body style='margin:0'>"
                + "<div style='height:100vh'>page</div>")) {
            activity.runOnUiThread(() -> {
                LinearLayout root = new LinearLayout(activity);
                root.setOrientation(LinearLayout.VERTICAL);
                EditText e = new EditText(activity);
                root.addView(e, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                CefriumBrowser b = browsers.createWithSurface(activity);
                root.addView(b.getSurfaceContainer(), new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
                activity.setContentView(root);
                e.requestFocus();
                field.set(e);
                browser.set(b);
                b.setOnTitleChangedListener(title::set);
                b.loadUrl(server.url());
            });
            long end = SystemClock.uptimeMillis() + 20000;
            while (!"ready".equals(title.get()) && SystemClock.uptimeMillis() < end) {
                Thread.sleep(100);
            }
            Assert.assertEquals("ready", title.get());
            Thread.sleep(1500);
            Assert.assertTrue("the browser took the field's focus when it was created",
                    ui(() -> field.get().isFocused()));

            // The window loses and regains focus, as when a dialog closes.
            AtomicReference<AlertDialog> dialog = new AtomicReference<>();
            activity.runOnUiThread(() -> dialog.set(
                    new AlertDialog.Builder(activity).setMessage("x").show()));
            Thread.sleep(1000);
            activity.runOnUiThread(() -> dialog.get().dismiss());
            Thread.sleep(1500);
            Assert.assertTrue("the browser took the field's focus when the window regained it",
                    ui(() -> field.get().isFocused()));

            // A tap on the page still moves focus to the browser.
            tap(browser.get());
            Thread.sleep(1000);
            Assert.assertFalse("a tap on the page did not take focus from the field",
                    ui(() -> field.get().isFocused()));
        }
    }
}
