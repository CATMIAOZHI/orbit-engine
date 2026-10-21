package com.cefrium.itest.sdk;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.Cefrium;
import com.cefrium.CefriumBrowser;
import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Hardening tests for build validation.
 * These test edge cases, error conditions, and stability.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumHardeningTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    @Test
    public void testMultipleBrowsersSequential() throws Exception {
        Activity activity = activityRule.getActivity();
        // Create and close 3 browsers sequentially — no leaks/crashes.
        for (int i = 0; i < 3; i++) {
            CountDownLatch latch = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                CefriumBrowser browser = browsers.createWithSurface(activity);
                browser.loadUrl("data:text/html,<title>CefriumTest</title><h1>ok</h1>");
                browser.close();
                latch.countDown();
            });
            Assert.assertTrue("Iteration " + i + " timed out",
                    latch.await(10, TimeUnit.SECONDS));
        }
    }

    /**
     * close() in the same main-looper turn as createWithSurface. The browser
     * must actually close, not leak with its renderer: closedBrowserCount()
     * grows by one and the live count returns to where it was. Creation is
     * synchronous on Android, so the native browser already exists here; this
     * guards that, because if creation ever became asynchronous, close() would
     * find no browser and free state that OnAfterCreated would then use.
     */
    @Test
    public void closeBeforeCreationStillClosesTheBrowser() throws Exception {
        closeImmediatelyAndExpectClosed(false);
    }

    /**
     * Same for incognito, whose close also releases the shared off-the-record
     * context.
     */
    @Test
    public void closeBeforeIncognitoCreationStillClosesTheBrowser() throws Exception {
        closeImmediatelyAndExpectClosed(true);
    }

    private void closeImmediatelyAndExpectClosed(boolean incognito) throws Exception {
        Activity activity = activityRule.getActivity();
        // Earlier tests' browsers finish closing asynchronously and bump the same
        // counter. Wait until it has been still for 3 s, or a stray close passes
        // this test for a browser that leaked.
        int last = CefriumBrowser.closedBrowserCount();
        for (int still = 0; still < 30; ) {
            Thread.sleep(100);
            int now = CefriumBrowser.closedBrowserCount();
            still = now == last ? still + 1 : 0;
            last = now;
        }
        final int[] before = new int[2];
        CountDownLatch closed = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            before[0] = CefriumBrowser.closedBrowserCount();
            before[1] = CefriumBrowser.liveBrowserCount();
            CefriumBrowser browser = browsers.createWithSurface(activity, incognito);
            browser.close();
            closed.countDown();
        });
        Assert.assertTrue(closed.await(10, TimeUnit.SECONDS));
        int closedNow = before[0];
        int liveNow = -1;
        for (int i = 0; i < 100 && closedNow == before[0]; i++) {
            Thread.sleep(100);
            closedNow = CefriumBrowser.closedBrowserCount();
        }
        liveNow = CefriumBrowser.liveBrowserCount();
        Assert.assertEquals("the browser created after an early close() was never closed",
                before[0] + 1, closedNow);
        Assert.assertEquals("an early close() left a live native browser behind",
                before[1], liveNow);
    }

    /**
     * Close the browser whose view is on screen, then resize that view: what an
     * app does when it closes the visible tab and the keyboard or a rotation
     * follows. The container kept the closed browser's WebContents, and the
     * next layout called setSize on it: "Native WebContents already destroyed",
     * and the app process died.
     */
    @Test
    public void closeTheVisibleBrowserThenLayoutDoesNotCrash() throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch loaded = new CountDownLatch(1);
        final CefriumBrowser[] holder = new CefriumBrowser[1];
        try (LocalHttpServer server = new LocalHttpServer("<html><body>v</body></html>")) {
            activity.runOnUiThread(() -> {
                CefriumBrowser browser = browsers.createWithSurface(activity);
                holder[0] = browser;
                activity.setContentView(browser.getSurfaceContainer());
                browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                    if (!loading) loaded.countDown();
                });
                browser.loadUrl(server.url());
            });
            Assert.assertTrue("the page never loaded", loaded.await(20, TimeUnit.SECONDS));
        }
        int closedBefore = CefriumBrowser.closedBrowserCount();
        activity.runOnUiThread(() -> holder[0].close());
        for (int i = 0; i < 100 && CefriumBrowser.closedBrowserCount() == closedBefore; i++) {
            Thread.sleep(100);
        }
        Assert.assertTrue("close() never completed",
                CefriumBrowser.closedBrowserCount() > closedBefore);
        // Force onSizeChanged on every view in the closed browser's container.
        CountDownLatch resized = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            android.view.View container = holder[0].getSurfaceContainer();
            android.view.ViewGroup.LayoutParams lp = container.getLayoutParams();
            lp.width = Math.max(1, container.getWidth() / 2);
            container.setLayoutParams(lp);
            container.post(resized::countDown);
        });
        Assert.assertTrue(resized.await(10, TimeUnit.SECONDS));
        // Two more frames, so the layout pass has certainly run.
        CountDownLatch frames = new CountDownLatch(1);
        activity.runOnUiThread(() -> android.view.Choreographer.getInstance()
                .postFrameCallback(t -> android.view.Choreographer.getInstance()
                        .postFrameCallback(t2 -> frames.countDown())));
        Assert.assertTrue("the app stopped drawing after the resize",
                frames.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testDoubleClose() throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch latch = new CountDownLatch(1);

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.close();
            browser.close();  // Should not crash
            latch.countDown();
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testLoadEmptyUrl() throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch latch = new CountDownLatch(1);

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.loadUrl("");  // Should not crash
            browser.loadUrl("data:text/html,<title>CefriumTest</title><h1>ok</h1>");
            browser.close();
            latch.countDown();
        });

        Assert.assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testLoadDataUrl() throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch loadLatch = new CountDownLatch(1);

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.setOnLoadingStateChangedListener((isLoading, back, fwd) -> {
                if (!isLoading) loadLatch.countDown();
            });
            browser.loadUrl("data:text/html,<h1>Test</h1>");
        });

        Assert.assertTrue("Data URL load timed out",
                loadLatch.await(15, TimeUnit.SECONDS));
    }

    @Test
    public void testNavigationAfterClose() throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch latch = new CountDownLatch(1);

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.close();
            // All of these should log warnings but not crash
            browser.loadUrl("data:text/html,<title>CefriumTest</title><h1>ok</h1>");
            browser.goBack();
            browser.goForward();
            browser.reload();
            browser.find("test", true, false, false);
            browser.stopFinding(true);
            browser.setDarkMode(true);
            browser.onPause();
            browser.onResume();
            latch.countDown();
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testCallbackAfterClose() throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch latch = new CountDownLatch(1);

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.close();
            // Setting callbacks after close should not crash
            browser.setCallback(new CefriumBrowser.Callback() {
                @Override
                public void onUrlChanged(String url) {}
            });
            browser.setOnUrlChangedListener(url -> {});
            browser.setOnTitleChangedListener(title -> {});
            browser.setOnLoadingStateChangedListener((l, b, f) -> {});
            browser.setOnFindResultListener((o, t) -> {});
            latch.countDown();
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDarkModeToggle() throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch loadLatch = new CountDownLatch(1);

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.setOnLoadingStateChangedListener((isLoading, b, f) -> {
                if (!isLoading) loadLatch.countDown();
            });
            browser.loadUrl("data:text/html,<title>CefriumTest</title><h1>ok</h1>");
        });

        Assert.assertTrue(loadLatch.await(20, TimeUnit.SECONDS));

        CountDownLatch darkLatch = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            // Toggle dark mode — should not crash
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.setDarkMode(true);
            browser.setDarkMode(false);
            browser.close();
            darkLatch.countDown();
        });

        Assert.assertTrue(darkLatch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testContextMenuCommandAfterClose() throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch latch = new CountDownLatch(1);

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.close();
            // Context menu command after close should not crash
            browser.contextMenuCommand(1);
            browser.contextMenuCommand(-1);
            latch.countDown();
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testOnActivityResultAfterClose() throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch latch = new CountDownLatch(1);

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity);
            browser.close();
            // onActivityResult after close should not crash
            boolean handled = browser.onActivityResult(0, 0, null);
            Assert.assertFalse("Should return false after close", handled);
            latch.countDown();
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
