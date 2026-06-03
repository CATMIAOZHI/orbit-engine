package com.cefrium.itest.sdk;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.Cefrium;
import com.cefrium.CefriumBrowser;
import com.cefrium.itest.HostActivity;

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

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Test
    public void testMultipleBrowsersSequential() throws Exception {
        Activity activity = activityRule.getActivity();
        // Create and close 3 browsers sequentially — no leaks/crashes.
        for (int i = 0; i < 3; i++) {
            CountDownLatch latch = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
                browser.loadUrl("https://example.com");
                browser.close();
                latch.countDown();
            });
            Assert.assertTrue("Iteration " + i + " timed out",
                    latch.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testDoubleClose() throws Exception {
        Activity activity = activityRule.getActivity();
        CountDownLatch latch = new CountDownLatch(1);

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
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
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            browser.loadUrl("");  // Should not crash
            browser.loadUrl("https://example.com");
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
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
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
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            browser.close();
            // All of these should log warnings but not crash
            browser.loadUrl("https://example.com");
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
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
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
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            browser.setOnLoadingStateChangedListener((isLoading, b, f) -> {
                if (!isLoading) loadLatch.countDown();
            });
            browser.loadUrl("https://example.com");
        });

        Assert.assertTrue(loadLatch.await(20, TimeUnit.SECONDS));

        CountDownLatch darkLatch = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            // Toggle dark mode — should not crash
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
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
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
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
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            browser.close();
            // onActivityResult after close should not crash
            boolean handled = browser.onActivityResult(0, 0, null);
            Assert.assertFalse("Should return false after close", handled);
            latch.countDown();
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
