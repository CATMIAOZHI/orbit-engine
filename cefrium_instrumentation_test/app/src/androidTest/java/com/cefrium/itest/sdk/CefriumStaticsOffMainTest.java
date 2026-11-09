// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.cefrium.Cefrium;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEATURE-084: the Cefrium statics are callable from any thread by design
 * (synchronized configuration, loadAdBlockList documented for a background
 * thread, initialize posts and waits). Called off the main thread, each one
 * returns or throws an exception; none hangs or crashes. Shutdown, process
 * setup and the network downloads are left out.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumStaticsOffMainTest {

    @Test
    public void staticsAreSafeOffTheMainThread() throws Exception {
        android.content.Context context =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        Cefrium.initialize(context);
        File missing = new File(context.getCacheDir(), "no-such-list.txt");
        Map<String, Runnable> calls = new LinkedHashMap<>();
        // Setters get the current value: the point is the thread, not the state.
        calls.put("setComponentUpdatesEnabled",
                () -> Cefrium.setComponentUpdatesEnabled(Cefrium.areComponentUpdatesEnabled()));
        calls.put("setDataDirectory", () -> Cefrium.setDataDirectory(Cefrium.getDataDirectory()));
        calls.put("setLibraryPrefetchEnabled",
                () -> Cefrium.setLibraryPrefetchEnabled(Cefrium.isLibraryPrefetchEnabled()));
        calls.put("setGpuProcessEnabled",
                () -> Cefrium.setGpuProcessEnabled(Cefrium.isGpuProcessEnabled()));
        calls.put("setNetworkTimeQueriesEnabled",
                () -> Cefrium.setNetworkTimeQueriesEnabled(Cefrium.areNetworkTimeQueriesEnabled()));
        calls.put("setDrmPreprovisioningAtStartupEnabled",
                () -> Cefrium.setDrmPreprovisioningAtStartupEnabled(
                        Cefrium.isDrmPreprovisioningAtStartupEnabled()));
        calls.put("setNetLogFile", () -> Cefrium.setNetLogFile(null));
        calls.put("getRendererServices", Cefrium::getRendererServices);
        calls.put("initialize", Cefrium::initialize);
        calls.put("isInitialized", Cefrium::isInitialized);
        calls.put("getVersion", Cefrium::getVersion);
        calls.put("getChromiumVersion", Cefrium::getChromiumVersion);
        calls.put("loadAdBlockList", () -> Cefrium.loadAdBlockList(missing.getPath()));
        calls.put("loadYouTubeAdRules", Cefrium::loadYouTubeAdRules);
        calls.put("setSecureDns", () -> Cefrium.setSecureDns(Cefrium.SECURE_DNS_OFF, null));
        calls.put("setRemoteDebuggingEnabled", () -> Cefrium.setRemoteDebuggingEnabled(false));

        List<String> broken = new ArrayList<>();
        StringBuilder outcomes = new StringBuilder();
        for (Map.Entry<String, Runnable> e : calls.entrySet()) {
            AtomicReference<String> outcome = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    e.getValue().run();
                    outcome.set("returned");
                } catch (Exception x) {
                    outcome.set("threw " + x.getClass().getSimpleName() + ": " + x.getMessage());
                } catch (Throwable x) {
                    outcome.set("ERROR " + x);
                }
            }, "statics-" + e.getKey());
            t.start();
            t.join(10_000);
            String o = outcome.get();
            outcomes.append(e.getKey()).append(": ").append(o).append('\n');
            if (o == null) {
                broken.add(e.getKey() + ": did not return in 10 s");
            } else if (o.startsWith("ERROR")) {
                broken.add(e.getKey() + ": " + o);
            }
        }
        Assert.assertTrue(String.join("\n", broken) + "\n--\n" + outcomes, broken.isEmpty());
    }
}
