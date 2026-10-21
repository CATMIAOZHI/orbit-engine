package com.cefrium.itest.sdk;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumRenderHandler;
import com.cefrium.itest.HostActivity;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Off-screen frames carry transparency when the embedder asks for it
 * (SC-osr-01, SC-osr-02). The page paints its left half rgb(255,0,0) and leaves
 * the right half transparent; the alpha of each half is read from onPaint.
 * Before setBackgroundColor existed every pixel came back with alpha 255.
 * It also pins the byte order the SDK documents: RGBA, not BGRA.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumOsrTransparencyTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    private static final String PAGE = "<html><head><style>"
            + "html,body{margin:0;background:transparent;width:400px;height:200px}"
            + "#l{position:absolute;left:0;top:0;width:200px;height:200px;background:rgb(255,0,0)}"
            + "</style></head><body><div id=l></div><script>"
            + "requestAnimationFrame(function(){requestAnimationFrame(function(){"
            + "document.title='painted';});});</script></body></html>";

    /** Renders PAGE off-screen; backgroundArgb null keeps the default. */
    private String alphaPerHalf(Integer backgroundArgb) throws Exception {
        Activity activity = activityRule.getActivity();
        final AtomicReference<String> stats = new AtomicReference<>();
        final CountDownLatch measured = new CountDownLatch(1);
        final boolean[] painted = {false};
        final CefriumBrowser[] holder = new CefriumBrowser[1];
        activity.runOnUiThread(() -> {
            CefriumBrowser browser = new CefriumBrowser(400, 200, 1.0f);
            holder[0] = browser;
            if (backgroundArgb != null) browser.setBackgroundColor(backgroundArgb);
            browser.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer pixels) {
                    if (!painted[0] || measured.getCount() == 0) return;
                    int lMin = 255, lMax = 0, rMin = 255, rMax = 0;
                    for (int y = 10; y < height - 10; y += 4) {
                        for (int x = 10; x < width - 10; x += 4) {
                            int a = pixels.get((y * width + x) * 4 + 3) & 0xff;
                            if (x < width / 2 - 10) {
                                lMin = Math.min(lMin, a);
                                lMax = Math.max(lMax, a);
                            } else if (x > width / 2 + 10) {
                                rMin = Math.min(rMin, a);
                                rMax = Math.max(rMax, a);
                            }
                        }
                    }
                    // Byte order of one pixel inside the red half: the page paints
                    // rgb(255,0,0), so RGBA puts 255 first and BGRA would put it third.
                    int at = ((height / 2) * width + width / 4) * 4;
                    String order = (pixels.get(at) & 0xff) + "," + (pixels.get(at + 1) & 0xff)
                            + "," + (pixels.get(at + 2) & 0xff);
                    stats.set("left " + lMin + ".." + lMax + ", right " + rMin + ".." + rMax
                            + ", red pixel bytes " + order);
                    measured.countDown();
                }
            });
            browser.setOnTitleChangedListener(t -> {
                if ("painted".equals(t)) {
                    painted[0] = true;
                    browser.invalidate();
                }
            });
            browser.loadHtml(PAGE);
        });
        try {
            Assert.assertTrue("no frame after the page painted",
                    measured.await(20, TimeUnit.SECONDS));
            return stats.get();
        } finally {
            activity.runOnUiThread(() -> holder[0].close());
        }
    }

    @Test
    public void transparentBackgroundLeavesTransparentAreasTransparent() throws Exception {
        Assert.assertEquals("alpha per half and the byte order of a red pixel",
                "left 255..255, right 0..0, red pixel bytes 255,0,0",
                alphaPerHalf(0x00000000));
    }

    @Test
    public void defaultBackgroundStaysOpaque() throws Exception {
        Assert.assertEquals("alpha per half and the byte order of a red pixel",
                "left 255..255, right 255..255, red pixel bytes 255,0,0",
                alphaPerHalf(null));
    }

    @Test
    public void backgroundColorAfterTheFirstLoadIsRefused() throws Exception {
        Activity activity = activityRule.getActivity();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            CefriumBrowser browser = new CefriumBrowser(400, 200, 1.0f);
            browser.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer pixels) { }
            });
            browser.loadHtml("<html><body>x</body></html>");
            try {
                browser.setBackgroundColor(0x00000000);
            } catch (Throwable t) {
                thrown.set(t);
            }
            browser.close();
            done.countDown();
        });
        Assert.assertTrue(done.await(10, TimeUnit.SECONDS));
        Assert.assertTrue("a late setBackgroundColor must throw, got " + thrown.get(),
                thrown.get() instanceof IllegalStateException);
    }
}
