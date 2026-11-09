// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;

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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FEATURE-087: window.print() reaches the app (it did nothing: Chromium only
 * prints from a Chrome Tab), and the engine prints to PDF at the paper size
 * and orientation Android's print dialog picks.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumPrintTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    private static final String PAGE =
            "<html><body><h1>Printed by Cefrium</h1><script>document.title='ready'</script>"
            + "</body></html>";

    private CefriumBrowser open(boolean offscreen, String url) throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<CefriumBrowser> created = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            CefriumBrowser b = offscreen
                    ? browsers.track(new CefriumBrowser(400, 300, 1.0f))
                    : browsers.createWithSurface(activity);
            created.set(b);
            b.setOnTitleChangedListener(t -> {
                if ("ready".equals(t)) ready.countDown();
            });
            b.loadUrl(url);
        });
        Assert.assertTrue("the page did not load", ready.await(30, TimeUnit.SECONDS));
        return created.get();
    }

    private void windowPrintReachesTheHandler(boolean offscreen) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch called = new CountDownLatch(1);
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = open(offscreen, server.url());
            String state = "";
            for (int i = 0; i < 50 && !"complete".equals(state); i++) {
                state = eval(b, "document.readyState");
                if (!"complete".equals(state)) Thread.sleep(200);
            }
            final String readyState = state;
            activityRule.getActivity().runOnUiThread(() -> {
                b.setPrintHandler(() -> {
                    requests.incrementAndGet();
                    called.countDown();
                    return true;
                });
                b.executeDevToolsMethod("Runtime.evaluate",
                        "{\"expression\":\"window.print();1\",\"userGesture\":true}",
                        (ok, json) -> {});
            });
            Assert.assertTrue("window.print() never reached the PrintHandler (readyState "
                    + readyState + ")",
                    called.await(15, TimeUnit.SECONDS));
            Thread.sleep(500);
            Assert.assertEquals(1, requests.get());
        }
    }

    private String eval(CefriumBrowser b, String expression) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        activityRule.getActivity().runOnUiThread(() -> b.executeDevToolsMethod(
                "Runtime.evaluate", "{\"expression\":" + org.json.JSONObject.quote(expression)
                        + ",\"returnByValue\":true}",
                (ok, json) -> {
                    try {
                        value.set(new org.json.JSONObject(json).getJSONObject("result")
                                .optString("value"));
                    } catch (Exception e) {
                        value.set(json);
                    }
                    done.countDown();
                }));
        done.await(10, TimeUnit.SECONDS);
        return value.get();
    }

    @Test
    public void windowPrintReachesTheHandlerSurface() throws Exception {
        windowPrintReachesTheHandler(false);
    }

    @Test
    public void windowPrintReachesTheHandlerOffscreen() throws Exception {
        windowPrintReachesTheHandler(true);
    }

    // [width, height] of the first page, in points.
    private static double[] mediaBox(File pdf) throws Exception {
        String text = new String(Files.readAllBytes(pdf.toPath()), StandardCharsets.ISO_8859_1);
        Assert.assertTrue("not a PDF", text.startsWith("%PDF"));
        Matcher m = Pattern.compile("/MediaBox\\s*\\[\\s*([-\\d.]+)\\s+([-\\d.]+)\\s+([-\\d.]+)"
                + "\\s+([-\\d.]+)\\s*\\]").matcher(text);
        Assert.assertTrue("no MediaBox", m.find());
        return new double[] {Double.parseDouble(m.group(3)) - Double.parseDouble(m.group(1)),
                Double.parseDouble(m.group(4)) - Double.parseDouble(m.group(2))};
    }

    private File print(CefriumBrowser b, String name, double w, double h, boolean landscape)
            throws Exception {
        File out = new File(activityRule.getActivity().getCacheDir(), name);
        out.delete();
        CountDownLatch done = new CountDownLatch(1);
        boolean[] ok = {false};
        activityRule.getActivity().runOnUiThread(() -> b.printToPdf(out.getPath(), w, h, landscape,
                (path, success) -> {
                    ok[0] = success;
                    done.countDown();
                }));
        Assert.assertTrue("PrintToPDF never finished", done.await(30, TimeUnit.SECONDS));
        Assert.assertTrue("PrintToPDF failed", ok[0]);
        return out;
    }

    @Test
    public void pdfAtThePaperSizeAndOrientationAsked() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            CefriumBrowser b = open(true, server.url());
            double[] portrait = mediaBox(print(b, "a4.pdf", 8.27, 11.69, false));
            Assert.assertEquals("A4 width", 595, portrait[0], 3);
            Assert.assertEquals("A4 height", 842, portrait[1], 3);
            double[] landscape = mediaBox(print(b, "a4-landscape.pdf", 8.27, 11.69, true));
            Assert.assertEquals("landscape width", 842, landscape[0], 3);
            Assert.assertEquals("landscape height", 595, landscape[1], 3);
        }
    }
}
