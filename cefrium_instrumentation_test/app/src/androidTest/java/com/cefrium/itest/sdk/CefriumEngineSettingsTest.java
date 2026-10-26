// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;

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
 * Engine settings take effect only before the engine initializes (SC-rc-08).
 *
 * By the time a test runs, the host application has already initialized the
 * engine, so every setter here is called too late on purpose. The value must
 * not change: a setter that silently "succeeded" after startup would tell an
 * embedder a security or privacy setting is in force when it is not.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumEngineSettingsTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    /**
     * The late setter must change neither the reported value nor where the GPU
     * actually runs. A page is rendered after the call, so a GPU process would
     * exist if the setting had taken effect; the process list is the evidence,
     * the getter alone would be a proxy.
     */
    @Test
    public void lateGpuProcessSettingIsRefused() throws Exception {
        boolean before = Cefrium.isGpuProcessEnabled();
        Cefrium.setGpuProcessEnabled(!before);
        Assert.assertEquals("setGpuProcessEnabled after initialization must not change "
                + "the value", before, Cefrium.isGpuProcessEnabled());

        Activity activity = activityRule.getActivity();
        CountDownLatch loaded = new CountDownLatch(1);
        try (LocalHttpServer server = new LocalHttpServer("<html><body>gpu</body></html>")) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activity);
                activity.setContentView(b.getSurfaceContainer());
                b.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                    if (!loading) loaded.countDown();
                });
                b.loadUrl(server.url());
            });
            Assert.assertTrue("the page never loaded", loaded.await(20, TimeUnit.SECONDS));
        }
        Thread.sleep(1000);  // a GPU process, if any, has started by now
        // getRunningAppProcesses lists this app's own processes; the GPU process
        // (:privileged_processN) runs under the app's UID, so it would appear.
        boolean gpuProcess = false;
        ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
            if (p.processName.contains(":privileged_process")) gpuProcess = true;
        }
        Assert.assertEquals("a GPU process exists exactly when the setting in force at "
                + "startup asked for one", before, gpuProcess);
    }

    /**
     * The late setter changes neither the getter nor the engine's command line,
     * which is what the component updater actually reads (SC-rc-15). This app
     * never opts out, so the default is on: no --disable-component-update.
     */
    @Test
    public void lateComponentUpdateSettingIsRefused() {
        boolean before = Cefrium.areComponentUpdatesEnabled();
        Cefrium.setComponentUpdatesEnabled(!before);
        Assert.assertEquals("setComponentUpdatesEnabled after initialization must not "
                + "change the value", before, Cefrium.areComponentUpdatesEnabled());
        Assert.assertTrue("this app never opts out, so updates must be on by default",
                before);
        Assert.assertFalse("the engine's command line carries --disable-component-update "
                        + "although no one opted out",
                org.chromium.base.CommandLine.getInstance()
                        .hasSwitch("disable-component-update"));
    }

    // The engine's own choice, read back: Java renderer services below
    // Android 17, or the native ones when the app declares them, and every
    // declared slot counted. The SDK declares 40 Java slots.
    @Test
    public void rendererServicesAreTheOnesTheEngineLaunches() {
        Cefrium.RendererServices services = Cefrium.getRendererServices();
        if (android.os.Build.VERSION.SDK_INT < 37) {
            Assert.assertFalse(services.toString(), services.javaless);
            Assert.assertEquals("com.cefrium.ChildServices$Sandboxed", services.serviceName);
            Assert.assertEquals(40, services.count);
        } else {
            Assert.assertTrue(services.toString(), services.count > 0);
        }
    }
}
