// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumPickerHandler;
import com.cefrium.CefriumRenderHandler;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Issue #16: in an off-screen browser the pickers of {@code <select>}, the
 * date input and {@code <input type=color>} reach the CefriumPickerHandler,
 * and its answer reaches the page. Before, nothing showed at all: Android
 * opens them as dialogs on a window an off-screen browser does not have.
 * Each input fills the page and is opened with a tap, as a user would.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumOsrPickerTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private static final String FILL =
            " style='position:absolute;left:0;top:0;width:400px;height:300px;font-size:40px'";

    private static String page(String input, String report) {
        return "<html><body style='margin:0'>" + input
                + "<script>var e=document.getElementById('i');"
                + "e.onchange=function(){document.title=" + report + ";};"
                + "document.title='ready';</script></body></html>";
    }

    private CefriumBrowser open(String html, AtomicReference<String> title,
            CefriumPickerHandler handler) throws Exception {
        Activity activity = activityRule.getActivity();
        CefriumBrowser[] holder = new CefriumBrowser[1];
        LocalHttpServer server = new LocalHttpServer(html);
        servers.set(server);
        activity.runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(400, 300, 1.0f));
            b.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer px) {}
            });
            b.setPickerHandler(handler);
            b.setOnTitleChangedListener(title::set);
            b.loadUrl(server.url());
            holder[0] = b;
        });
        waitForTitle(title, "ready");
        Thread.sleep(500);
        return holder[0];
    }

    private final AtomicReference<LocalHttpServer> servers = new AtomicReference<>();

    private void tap(CefriumBrowser b) {
        activityRule.getActivity().runOnUiThread(() -> b.sendTap(200f, 150f));
    }

    @org.junit.After
    public void closeServer() {
        LocalHttpServer s = servers.get();
        if (s != null) s.close();
    }

    @Test
    public void select() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        AtomicReference<String> seen = new AtomicReference<>();
        CefriumBrowser b = open(page("<select id=i" + FILL + "><optgroup label=G>"
                + "<option>a</option><option>b</option></optgroup><option>c</option></select>",
                "'sel:'+e.value"), title, new CefriumPickerHandler() {
                    @Override
                    public boolean onShowSelect(SelectItem[] items, int selected, boolean multiple,
                            SelectCallback callback) {
                        StringBuilder sb = new StringBuilder();
                        // Blink indents the options of a group.
                        for (SelectItem it : items) sb.append(it.label.trim()).append('/').append(it.type).append(' ');
                        seen.set(sb.toString().trim() + " selected=" + selected + " multiple=" + multiple);
                        callback.select(new int[] {items.length - 1});
                        return true;
                    }
                });
        tap(b);
        waitForTitle(title, "sel:c");
        Assert.assertEquals("G/2 a/0 b/0 c/0 selected=1 multiple=false", seen.get());
    }

    @Test
    public void date() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        AtomicReference<String> seen = new AtomicReference<>();
        CefriumBrowser b = open(page("<input type=date id=i" + FILL + ">", "'date:'+e.value"),
                title, new CefriumPickerHandler() {
                    @Override
                    public boolean onShowDateTime(int type, double value, double min, double max,
                            double step, DateTimeCallback callback) {
                        seen.set("type=" + type + " empty=" + Double.isNaN(value));
                        // 2026-10-26 00:00 UTC, in milliseconds.
                        callback.set(1792972800000.0);
                        return true;
                    }
                });
        tap(b);
        waitForTitle(title, "date:2026-10-26");
        Assert.assertEquals("type=" + CefriumPickerHandler.DATE + " empty=true", seen.get());
    }

    @Test
    public void color() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        AtomicReference<String> seen = new AtomicReference<>();
        CefriumBrowser b = open(page("<input type=color id=i value='#112233' list=l" + FILL + ">"
                + "<datalist id=l><option>#ff0000</option><option>#0000ff</option></datalist>",
                "'col:'+e.value"), title, new CefriumPickerHandler() {
                    @Override
                    public boolean onShowColor(int color, int[] suggestions, ColorCallback callback) {
                        StringBuilder sb = new StringBuilder(String.format("%08X", color));
                        for (int s : suggestions) sb.append(' ').append(String.format("%08X", s));
                        seen.set(sb.toString());
                        callback.set(0xFF00FF00);
                        return true;
                    }
                });
        tap(b);
        waitForTitle(title, "col:#00ff00");
        Assert.assertEquals("FF112233 FFFF0000 FF0000FF", seen.get());
    }

    @Test
    public void withoutAHandlerThePickerIsCancelledAndThePageCanTryAgain() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        CefriumBrowser b = open(page("<select id=i" + FILL + "><option>a</option><option>b</option></select>",
                "'sel:'+e.value"), title, null);
        tap(b);
        Thread.sleep(2000);
        Assert.assertEquals("a cancelled picker changed the value", "ready", title.get());
        activityRule.getActivity().runOnUiThread(() -> b.setPickerHandler(new CefriumPickerHandler() {
            @Override
            public boolean onShowSelect(SelectItem[] items, int selected, boolean multiple,
                    SelectCallback callback) {
                callback.select(new int[] {1});
                return true;
            }
        }));
        tap(b);
        waitForTitle(title, "sel:b");
    }

    private static void waitForTitle(AtomicReference<String> title, String want)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (want.equals(title.get())) return;
            Thread.sleep(100);
        }
        Assert.assertEquals(want, title.get());
    }
}
