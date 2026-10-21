package com.cefrium.itest.sdk;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;
import com.cefrium.itest.WebViewHostActivity;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The system WebView and Cefrium in one package, in separate processes
 * (SC-rc-19), in both start orders. WebView binds its sandboxed renderer service under this app's
 * package. With both engines naming that service
 * org.chromium.content.app.SandboxedProcessService0, instance 0, Android
 * handed WebView's renderer process to Cefrium and the page stayed blank.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumCoexistenceTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    @Test
    public void cefriumRendersAfterTheSystemWebViewStarted() throws Exception {
        Activity activity = activityRule.getActivity();
        try (LocalHttpServer wvPage = new LocalHttpServer("<html><body>wv</body></html>");
             LocalHttpServer cefPage = new LocalHttpServer(
                     "<html><head><title>cefrium-ok</title></head><body>c</body></html>")) {
            startWebViewAndWait(activity, wvPage.url());
            Assert.assertTrue("Cefrium's renderer never produced the page title after the "
                            + "system WebView started in this package: its renderer service "
                            + "was taken",
                    loadCefrium(activity, cefPage.url()));
        } finally {
            killWebViewProcess(activity);
        }
    }

    /** The other order: Cefrium first, then the system WebView must still work. */
    @Test
    public void systemWebViewWorksAfterCefriumStarted() throws Exception {
        Activity activity = activityRule.getActivity();
        try (LocalHttpServer wvPage = new LocalHttpServer("<html><body>wv</body></html>");
             LocalHttpServer cefPage = new LocalHttpServer(
                     "<html><head><title>cefrium-ok</title></head><body>c</body></html>")) {
            Assert.assertTrue("Cefrium did not load on its own",
                    loadCefrium(activity, cefPage.url()));
            startWebViewAndWait(activity, wvPage.url());
        } finally {
            killWebViewProcess(activity);
        }
    }

    /**
     * Starts the system WebView in this package's :wv process, waits until its
     * page has loaded in an out-of-process renderer, then brings the test's
     * activity back to the front.
     */
    private static void startWebViewAndWait(Activity activity, String url) throws Exception {
        Assert.assertNotNull("this device has no WebView provider",
                android.webkit.WebView.getCurrentWebViewPackage());
        File ready = new File(activity.getFilesDir(), WebViewHostActivity.READY_FILE);
        ready.delete();
        activity.startActivity(new Intent(activity, WebViewHostActivity.class)
                .putExtra(WebViewHostActivity.EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for (int i = 0; i < 300 && !ready.exists(); i++) Thread.sleep(100);
        Assert.assertTrue("the system WebView never loaded its page", ready.exists());
        Assert.assertEquals("WebView must render out of process, or this test cannot see "
                        + "the collision",
                "renderer=true", new String(java.nio.file.Files.readAllBytes(ready.toPath())));
        activity.startActivity(new Intent(activity, HostActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        Thread.sleep(500);
    }

    /** Loads url in a Cefrium browser; true once the renderer reported its title. */
    private boolean loadCefrium(Activity activity, String url) throws Exception {
        CountDownLatch titled = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            CefriumBrowser b = browsers.createWithSurface(activity);
            activity.setContentView(b.getSurfaceContainer());
            b.setOnTitleChangedListener(t -> {
                if ("cefrium-ok".equals(t)) titled.countDown();
            });
            b.loadUrl(url);
        });
        return titled.await(20, TimeUnit.SECONDS);
    }

    /**
     * Removes the WebView task, then ends the :wv process, which also ends
     * WebView's renderer bound from it. Removing the task first matters: a
     * killed activity left in a task is relaunched when that task returns.
     */
    private static void killWebViewProcess(Context context) throws Exception {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.AppTask task : am.getAppTasks()) {
            android.content.ComponentName base = task.getTaskInfo().baseActivity;
            if (base != null
                    && base.getClassName().equals(WebViewHostActivity.class.getName())) {
                task.finishAndRemoveTask();
            }
        }
        Thread.sleep(500);
        for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
            if (p.processName.endsWith(":wv")) Process.killProcess(p.pid);
        }
    }
}
