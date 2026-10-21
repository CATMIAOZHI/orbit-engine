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
 * (SC-rc-19). WebView binds its sandboxed renderer service under this app's
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
        Assert.assertNotNull("this device has no WebView provider",
                android.webkit.WebView.getCurrentWebViewPackage());
        File ready = new File(activity.getFilesDir(), WebViewHostActivity.READY_FILE);
        ready.delete();
        try (LocalHttpServer wvPage = new LocalHttpServer("<html><body>wv</body></html>");
             LocalHttpServer cefPage = new LocalHttpServer(
                     "<html><head><title>cefrium-ok</title></head><body>c</body></html>")) {
            Intent intent = new Intent(activity, WebViewHostActivity.class)
                    .putExtra(WebViewHostActivity.EXTRA_URL, wvPage.url())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            for (int i = 0; i < 300 && !ready.exists(); i++) Thread.sleep(100);
            Assert.assertTrue("the system WebView never loaded its page", ready.exists());

            CountDownLatch titled = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activity);
                activity.setContentView(b.getSurfaceContainer());
                b.setOnTitleChangedListener(t -> {
                    if ("cefrium-ok".equals(t)) titled.countDown();
                });
                b.loadUrl(cefPage.url());
            });
            Assert.assertTrue("Cefrium's page never rendered after the system WebView "
                            + "started in this package: its renderer service was taken",
                    titled.await(20, TimeUnit.SECONDS));
        } finally {
            killWebViewProcess(activity);
        }
    }

    /** Ends the :wv process, which also ends WebView's renderer bound from it. */
    private static void killWebViewProcess(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
            if (p.processName.endsWith(":wv")) Process.killProcess(p.pid);
        }
    }
}
