// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;
import com.cefrium.CefriumContextMenu;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The context menu through CefriumChromeClient, on an off-screen browser
 * with no activity: a right click on a link gives a typed menu (link, page,
 * entries, position) on the UI thread; "Copy link" run from another thread
 * puts the link on the clipboard (it copied nothing without an activity);
 * "Open in new tab" loads the link.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumContextMenuClientTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE = "<!doctype html><head>"
            + "<script>document.title = location.pathname;</script></head>"
            + "<body style='margin:0'><a href='/target'"
            + " style='display:block;height:300px;background:#cde'>link</a></body>";

    private final List<String> titles = new CopyOnWriteArrayList<>();

    private void awaitTitle(String t) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < end) {
            if (titles.contains(t)) return;
            Thread.sleep(100);
        }
        Assert.fail("title never became " + t + "; titles " + titles);
    }

    private static CefriumContextMenu.Item item(CefriumContextMenu menu, String label) {
        for (CefriumContextMenu.Item i : menu.getItems()) if (i.getLabel().equals(label)) return i;
        throw new AssertionError("no '" + label + "' item");
    }

    private String clipboardText() throws Exception {
        AtomicReference<String> text = new AtomicReference<>("");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ClipboardManager cm = (ClipboardManager) activityRule.getActivity()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).getText();
                text.set(t == null ? "" : t.toString());
            }
        });
        return text.get();
    }

    @Test
    public void typedMenuCopiesAndOpens() throws Exception {
        LinkedBlockingQueue<CefriumContextMenu> menus = new LinkedBlockingQueue<>();
        AtomicReference<Boolean> onUi = new AtomicReference<>();
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(400, 600, 1.0f));
                browser.set(b);
                b.setOnTitleChangedListener(titles::add);
                b.setChromeClient(new CefriumChromeClient() {
                    @Override
                    public boolean onContextMenu(CefriumBrowser x, CefriumContextMenu menu) {
                        onUi.set(Looper.myLooper() == Looper.getMainLooper());
                        menus.add(menu);
                        return true;
                    }
                });
                b.loadUrl(server.url() + "page");
            });
            awaitTitle("/page");
            Thread.sleep(1000);  // input before the first frame is dropped

            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ClipboardManager cm = (ClipboardManager) activityRule.getActivity()
                        .getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("before", "before"));
            });
            activityRule.getActivity().runOnUiThread(() -> browser.get().sendMouseClick(
                    100, 100, CefriumBrowser.MOUSE_BUTTON_RIGHT));
            CefriumContextMenu menu = menus.poll(15, TimeUnit.SECONDS);
            Assert.assertNotNull("no context menu", menu);
            Assert.assertTrue("not on the UI thread", onUi.get());
            List<String> labels = new ArrayList<>();
            for (CefriumContextMenu.Item i : menu.getItems()) labels.add(i.getLabel());
            Assert.assertEquals("[Open in new tab, Copy link, Download link]",
                    labels.toString());
            Assert.assertEquals(server.url() + "target", menu.getLinkUrl());
            Assert.assertEquals(server.url() + "page", menu.getPageUrl());
            Assert.assertEquals(CefriumContextMenu.MediaType.NONE, menu.getMediaType());
            Assert.assertEquals(100, menu.getX());
            Assert.assertEquals(100, menu.getY());

            CefriumContextMenu.Item copy = item(menu, "Copy link");
            new Thread(() -> {
                copy.execute();
                menu.cancel();  // only the first answer counts
            }).start();
            long end = SystemClock.uptimeMillis() + 10000;
            while (SystemClock.uptimeMillis() < end
                    && !clipboardText().equals(server.url() + "target")) {
                Thread.sleep(200);
            }
            Assert.assertEquals("Copy link left the clipboard alone",
                    server.url() + "target", clipboardText());

            activityRule.getActivity().runOnUiThread(() -> browser.get().sendMouseClick(
                    100, 100, CefriumBrowser.MOUSE_BUTTON_RIGHT));
            CefriumContextMenu second = menus.poll(15, TimeUnit.SECONDS);
            Assert.assertNotNull("no second context menu", second);
            item(second, "Open in new tab").execute();
            awaitTitle("/target");
        }
    }
}
