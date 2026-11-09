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

/**
 * FEATURE-084: a setting the engine reads at startup, set after it started:
 * the value it already has is accepted (an Activity may set it in every
 * onCreate); another value throws, where it used to be ignored with a log
 * line that the W90 does not even show.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumStartupSettingsTest {

    @Test
    public void anotherValueAfterStartThrows() throws Exception {
        android.content.Context context =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> Cefrium.initialize(context));
        File other = new File(context.getCacheDir(), "other");

        Map<String, Runnable> same = new LinkedHashMap<>();
        same.put("setComponentUpdatesEnabled",
                () -> Cefrium.setComponentUpdatesEnabled(Cefrium.areComponentUpdatesEnabled()));
        same.put("setDataDirectory", () -> Cefrium.setDataDirectory(Cefrium.getDataDirectory()));
        same.put("setLibraryPrefetchEnabled",
                () -> Cefrium.setLibraryPrefetchEnabled(Cefrium.isLibraryPrefetchEnabled()));
        same.put("setGpuProcessEnabled",
                () -> Cefrium.setGpuProcessEnabled(Cefrium.isGpuProcessEnabled()));
        same.put("setNetworkTimeQueriesEnabled",
                () -> Cefrium.setNetworkTimeQueriesEnabled(Cefrium.areNetworkTimeQueriesEnabled()));
        same.put("setDrmPreprovisioningAtStartupEnabled",
                () -> Cefrium.setDrmPreprovisioningAtStartupEnabled(
                        Cefrium.isDrmPreprovisioningAtStartupEnabled()));

        Map<String, Runnable> changed = new LinkedHashMap<>();
        changed.put("setComponentUpdatesEnabled",
                () -> Cefrium.setComponentUpdatesEnabled(!Cefrium.areComponentUpdatesEnabled()));
        changed.put("setDataDirectory", () -> Cefrium.setDataDirectory(other));
        changed.put("setLibraryPrefetchEnabled",
                () -> Cefrium.setLibraryPrefetchEnabled(!Cefrium.isLibraryPrefetchEnabled()));
        changed.put("setGpuProcessEnabled",
                () -> Cefrium.setGpuProcessEnabled(!Cefrium.isGpuProcessEnabled()));
        changed.put("setNetworkTimeQueriesEnabled",
                () -> Cefrium.setNetworkTimeQueriesEnabled(!Cefrium.areNetworkTimeQueriesEnabled()));
        changed.put("setDrmPreprovisioningAtStartupEnabled",
                () -> Cefrium.setDrmPreprovisioningAtStartupEnabled(
                        !Cefrium.isDrmPreprovisioningAtStartupEnabled()));
        changed.put("setNetLogFile", () -> Cefrium.setNetLogFile(other));

        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, Runnable> e : same.entrySet()) {
            try {
                e.getValue().run();
            } catch (RuntimeException x) {
                broken.add(e.getKey() + " with its current value threw " + x);
            }
        }
        for (Map.Entry<String, Runnable> e : changed.entrySet()) {
            try {
                e.getValue().run();
                broken.add(e.getKey() + " with another value was accepted");
            } catch (IllegalStateException x) {
                if (!x.getMessage().startsWith(e.getKey())) {
                    broken.add(e.getKey() + ": message " + x.getMessage());
                }
            }
        }
        Assert.assertTrue(String.join("\n", broken), broken.isEmpty());
    }
}
