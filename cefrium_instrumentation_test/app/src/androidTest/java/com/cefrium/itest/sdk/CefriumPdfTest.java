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

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Instrumented test for FEATURE-031 PrintToPDF (CefBrowserHost::PrintToPDF).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumPdfTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Test
    public void testPrintToPdf() throws Exception {
        Activity act = activityRule.getActivity();
        File out = new File(act.getCacheDir(), "cefrium-test.pdf");
        if (out.exists()) Assert.assertTrue(out.delete());
        final String path = out.getAbsolutePath();
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = {false};
        final boolean[] probed = {false};

        act.runOnUiThread(() -> {
            CefriumBrowser b = CefriumBrowser.createWithSurface(act);
            b.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    b.printToPdf(path, (p, success) -> {
                        ok[0] = success;
                        done.countDown();
                    });
                }
            });
            b.loadHtml("<!doctype html><html><body><h1>Cefrium PDF</h1>"
                    + "<p>printed by PrintToPDF</p></body></html>");
        });

        Assert.assertTrue("PrintToPDF never finished", done.await(30, TimeUnit.SECONDS));
        Assert.assertTrue("PrintToPDF reported failure", ok[0]);
        Assert.assertTrue("PDF file not written", out.exists() && out.length() > 0);
    }
}
