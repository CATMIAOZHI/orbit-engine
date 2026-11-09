// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;

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
 * The app's own actions in the text-selection toolbar of a surface browser
 * (setTextSelectionActionModeCallback), and the selected text
 * (getSelectedText). A long press selects a word and opens the toolbar.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumTextSelectionTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE = "<html><head><meta name=viewport "
            + "content='width=device-width'></head><body style='margin:0'>"
            + "<p style='font:40px sans-serif;margin:20px'>Cefrium selection</p>"
            + "<script>document.title='ready'</script></body></html>";

    @Test
    public void appActionsJoinTheSelectionToolbar() throws Exception {
        Activity activity = activityRule.getActivity();
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        AtomicReference<String> menuTitles = new AtomicReference<>("");
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activity);
                browser.set(b);
                activity.setContentView(b.getSurfaceContainer());
                b.setTextSelectionActionModeCallback(new ActionMode.Callback() {
                    @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                        menu.add(Menu.NONE, 4242, Menu.NONE, "Look up");
                        events.add("create");
                        return true;
                    }

                    // After Cefrium's own items are in (the toolbar fills them here).
                    @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                        StringBuilder titles = new StringBuilder();
                        for (int i = 0; i < menu.size(); i++) {
                            titles.append(menu.getItem(i).getTitle()).append('|');
                        }
                        menuTitles.set(titles.toString());
                        events.add("prepare");
                        created.countDown();
                        return false;
                    }

                    @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                        return item.getItemId() == 4242;
                    }

                    @Override public void onDestroyActionMode(ActionMode mode) {
                        events.add("destroy");
                    }
                });
                b.setOnTitleChangedListener(t -> {
                    if ("ready".equals(t)) ready.countDown();
                });
                b.loadUrl(server.url());
            });
            Assert.assertTrue("the page did not load", ready.await(30, TimeUnit.SECONDS));
            Thread.sleep(1500);  // first frame on screen

            String before = onMain(() -> browser.get().getSelectedText());
            Assert.assertEquals("nothing is selected yet", "", before);

            // A long press on "Cefrium" (CSS px): selects the word, opens the toolbar.
            activity.runOnUiThread(() -> browser.get().executeDevToolsMethod(
                    "Input.synthesizeTapGesture",
                    "{\"x\":60,\"y\":45,\"duration\":1200,\"tapCount\":1}", (ok, json) -> {}));
            Assert.assertTrue("the selection toolbar never asked the app; events " + events,
                    created.await(15, TimeUnit.SECONDS));
            Assert.assertTrue("Cefrium's own items are gone: " + menuTitles.get(),
                    menuTitles.get().split("\\|").length > 1);
            Assert.assertTrue("the app's item is missing: " + menuTitles.get(),
                    menuTitles.get().contains("Look up"));
            String selected = onMain(() -> browser.get().getSelectedText());
            Assert.assertEquals("Cefrium", selected);
        }
    }

    private <T> T onMain(java.util.concurrent.Callable<T> call) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() -> {
            try {
                value.set(call.call());
            } catch (Exception e) {
                error.set(e);
            }
            done.countDown();
        });
        done.await(10, TimeUnit.SECONDS);
        if (error.get() != null) throw error.get();
        return value.get();
    }
}
