// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.content.Context;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.cefrium.Cefrium;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Cefrium.diagnostics() names what a bug report needs (SDK, engine, device,
 * app, exits) and nothing that identifies the device.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumDiagnosticsTest {
    @Test
    public void reportsWhatABugReportNeeds() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String d = Cefrium.diagnostics(context);
        Assert.assertTrue(d, d.contains("sdk: " + Cefrium.VERSION + "\n"));
        Assert.assertTrue(d, d.contains("engine: Chromium " + Cefrium.CHROMIUM_VERSION + "\n"));
        Assert.assertTrue(d, d.contains("initialized: "));
        Assert.assertTrue(d, d.contains("device: " + Build.MANUFACTURER + " " + Build.MODEL));
        Assert.assertTrue(d, d.contains("app: " + context.getPackageName() + " "));
        Assert.assertTrue(d, d.contains("appComponentFactory: "));
        // Exit records exist from Android 11, as the API documents.
        Assert.assertEquals(d, Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                d.contains("recent process exits:"));
        Assert.assertFalse("the build fingerprint identifies the device",
                d.contains(Build.FINGERPRINT));
        for (char c : d.toCharArray()) {
            Assert.assertTrue("non-ASCII in the report: " + d, c < 128);
        }
    }
}
