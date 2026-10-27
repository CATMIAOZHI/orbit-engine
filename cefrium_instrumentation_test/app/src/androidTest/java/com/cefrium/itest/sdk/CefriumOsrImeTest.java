// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumRenderHandler;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Issue #9: text from the soft keyboard (composition + commit) reaches a field
 * in an off-screen browser, in the main frame and inside a frame. The field is
 * focused with a tap, as a user would, and the page reports its value in the
 * title.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumOsrImeTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private static final String FILL =
            "style='position:absolute;left:0;top:0;width:400px;height:300px;"
            + "font-size:40px;border:0;margin:0;padding:0'";

    private final AtomicReference<LocalHttpServer> servers = new AtomicReference<>();
    private final AtomicReference<String> focused = new AtomicReference<>("");
    // The last mode onVirtualKeyboardRequested asked for, -1 before any.
    private final AtomicReference<Integer> keyboard = new AtomicReference<>(-1);

    @After
    public void closeServer() {
        LocalHttpServer s = servers.get();
        if (s != null) s.close();
    }

    private static String field(String extra) {
        return "<input id=i autocomplete=off " + FILL + " " + extra + ">";
    }

    // The field reports into the top document's title, wherever it lives.
    private static final String REPORT =
            "<script>var e=document.getElementById('i');"
            + "e.addEventListener('input',function(){top.document.title='v:'+e.value;});"
            + "top.document.title='ready';</script>";

    private static String mainFramePage(String extra) {
        return "<html><body style='margin:0'>" + field(extra) + REPORT + "</body></html>";
    }

    private static String framePage(String extra) {
        String inner = "<html><body style='margin:0'>" + field(extra) + REPORT + "</body></html>";
        return "<html><body style='margin:0'><iframe " + FILL + " srcdoc=\""
                + inner.replace("&", "&amp;").replace("\"", "&quot;") + "\"></iframe></body></html>";
    }

    private CefriumBrowser open(String html, AtomicReference<String> title) throws Exception {
        return open(html, title, 150f);
    }

    private CefriumBrowser open(String html, AtomicReference<String> title, float tapY)
            throws Exception {
        Activity activity = activityRule.getActivity();
        CefriumBrowser[] holder = new CefriumBrowser[1];
        LocalHttpServer server = new LocalHttpServer(html);
        servers.set(server);
        activity.runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(400, 300, 1.0f));
            b.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer px) {}

                @Override
                public void onFocusedNodeChanged(boolean editable, String value,
                        String placeholder, String type) {
                    focused.set(editable + ":" + type);
                }

                @Override
                public void onVirtualKeyboardRequested(int inputMode) {
                    keyboard.set(inputMode);
                }
            });
            b.setOnTitleChangedListener(title::set);
            b.loadUrl(server.url());
            holder[0] = b;
        });
        waitFor(title, "ready");
        Thread.sleep(500);
        CefriumBrowser b = holder[0];
        tap(b, tapY);
        return b;
    }

    private void tap(CefriumBrowser b, float y) throws InterruptedException {
        focused.set("");
        keyboard.set(-1);
        activityRule.getActivity().runOnUiThread(() -> b.sendTap(200f, y));
        waitFor(focused, "true:text");
    }

    // What an InputConnection does for one word: compose it, then commit it.
    private void type(CefriumBrowser b, String word) {
        activityRule.getActivity().runOnUiThread(() -> {
            for (int n = 1; n <= word.length(); n++) {
                String s = word.substring(0, n);
                b.imeSetComposition(s, s.length(), s.length());
            }
            b.imeCommitText(word, 1);
        });
    }

    // A keyboard owned by the app, with a mirror field of its own (Flutter,
    // for one): it shows the composition through the IME, then writes the
    // field's whole value with JavaScript instead of committing it. The value
    // written differs from the composition, so only the script can produce it.
    private static final String SET_VALUE = "var e=document.activeElement;e.value='123';"
            + "e.dispatchEvent(new Event('input',{bubbles:true}));"
            + "e.dispatchEvent(new Event('change',{bubbles:true}));";

    private void typeThroughAMirrorField(CefriumBrowser b, boolean focusedFrame) {
        activityRule.getActivity().runOnUiThread(() -> {
            b.imeSetComposition("12", 2, 2);
            if (focusedFrame) {
                b.evaluateJavaScriptInFocusedFrame(SET_VALUE);
            } else {
                b.evaluateJavaScript(SET_VALUE);
            }
        });
    }

    @Test
    public void mirrorFieldValueReachesAFieldInAFrame() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        CefriumBrowser b = open(framePage(""), title);
        typeThroughAMirrorField(b, true);
        waitFor(title, "v:123");
    }

    @Test
    public void mirrorFieldValueReachesAFieldInTheMainFrame() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        CefriumBrowser b = open(mainFramePage(""), title);
        typeThroughAMirrorField(b, true);
        waitFor(title, "v:123");
    }

    // Why the method above exists: the main frame's script cannot reach a
    // field inside a frame (its activeElement is the frame).
    @Test
    public void mainFrameScriptDoesNotReachAFieldInAFrame() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        CefriumBrowser b = open(framePage(""), title);
        typeThroughAMirrorField(b, false);
        Thread.sleep(2000);
        Assert.assertNotEquals("v:123", title.get());
    }

    @Test
    public void mainFrame() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        CefriumBrowser b = open(mainFramePage(""), title);
        type(b, "abc");
        waitFor(title, "v:abc");
    }

    @Test
    public void insideAFrame() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        CefriumBrowser b = open(framePage(""), title);
        type(b, "abc");
        waitFor(title, "v:abc");
    }

    // The reporter's field: it rewrites its own value on every input event.
    @Test
    public void insideAFrameWithAFieldThatRewritesItsValue() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        CefriumBrowser b = open(framePage(
                "oninput=\"this.value=this.value.replace(/[^\\d]/g,'')\""), title);
        type(b, "123");
        waitFor(title, "v:123");
    }

    // Keyboards send some keys as key events rather than text: Gboard sends
    // digits that way.
    @Test
    public void keyEventsReachAFieldInAFrame() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        CefriumBrowser b = open(framePage(
                "oninput=\"this.value=this.value.replace(/[^\\d]/g,'')\""), title);
        activityRule.getActivity().runOnUiThread(() -> {
            for (int code : new int[] {KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2}) {
                b.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                b.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
        });
        waitFor(title, "v:12");
    }

    // An app shows the soft keyboard, and with it an InputConnection, only
    // when it is asked to.
    @Test
    public void aFieldInAFrameAsksForTheKeyboard() throws Exception {
        AtomicReference<String> title = new AtomicReference<>("");
        open(framePage(""), title);
        long deadline = System.currentTimeMillis() + 5_000;
        while (keyboard.get() == -1 && System.currentTimeMillis() < deadline) Thread.sleep(100);
        Assert.assertNotEquals("no keyboard request", -1, (int) keyboard.get());
        Assert.assertNotEquals("the keyboard was asked to hide",
                CefriumBrowser.TEXT_INPUT_MODE_NONE, (int) keyboard.get());
    }

    @Test
    public void focusMovesFromTheMainFrameIntoAFrame() throws Exception {
        String half = "style='position:absolute;left:0;width:400px;height:150px;"
                + "font-size:40px;border:0;margin:0;padding:0;";
        String inner = "<html><body style='margin:0'><input id=f autocomplete=off "
                + half + "top:0'><script>var f=document.getElementById('f');"
                + "f.addEventListener('input',function(){top.document.title='f:'+f.value;});"
                + "</script></body></html>";
        String html = "<html><body style='margin:0'><input id=m autocomplete=off "
                + half + "top:0'><iframe " + half + "top:150px' srcdoc=\""
                + inner.replace("&", "&amp;").replace("\"", "&quot;") + "\"></iframe>"
                + "<script>var m=document.getElementById('m');"
                + "m.addEventListener('input',function(){document.title='m:'+m.value;});"
                + "document.querySelector('iframe').onload=function(){document.title='ready';};"
                + "</script></body></html>";
        AtomicReference<String> title = new AtomicReference<>("");
        CefriumBrowser b = open(html, title, 75f);
        type(b, "ab");
        waitFor(title, "m:ab");
        tap(b, 225f);
        type(b, "cd");
        waitFor(title, "f:cd");
    }

    private static void waitFor(AtomicReference<String> ref, String want)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (want.equals(ref.get())) return;
            Thread.sleep(100);
        }
        Assert.assertEquals(want, ref.get());
    }
}
