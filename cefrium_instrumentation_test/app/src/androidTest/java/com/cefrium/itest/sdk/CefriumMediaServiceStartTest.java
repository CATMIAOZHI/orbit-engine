// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.ActivityManager;
import android.app.Notification;
import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

/**
 * The media service is started with startForegroundService(), which obliges
 * it to call startForeground() even when there is nothing left to show:
 * otherwise Android kills the app ("did not then call startForeground").
 * Both ways it happened: the session was gone by the time the service
 * started, and a stop() arrived before the start (a video tab opened and
 * closed in a row). Each case waits past the system's deadline; a crash
 * kills the test process, which the suite reports.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumMediaServiceStartTest {

    // Past the 10 s Android gives a service to call startForeground().
    private static final long PAST_DEADLINE_MS = 15000;

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static void call(String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> c = Class.forName("com.cefrium.CefriumMediaService");
        Method m = c.getDeclaredMethod(name, types);
        m.setAccessible(true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                m.invoke(null, args);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void show() throws Exception {
        call("show", new Class<?>[] {Context.class, Notification.class}, context(), null);
    }

    private static void stop() throws Exception {
        call("stop", new Class<?>[] {Context.class}, context());
    }

    @SuppressWarnings("deprecation")
    private static boolean running() {
        ActivityManager am = context().getSystemService(ActivityManager.class);
        for (ActivityManager.RunningServiceInfo s : am.getRunningServices(50)) {
            if (s.service.getClassName().equals("com.cefrium.CefriumMediaService")) return true;
        }
        return false;
    }

    @Test
    public void startWithNoSessionLeft() throws Exception {
        show();
        Thread.sleep(PAST_DEADLINE_MS);
        Assert.assertFalse("the service kept running with nothing to show", running());
    }

    @Test
    public void stopBeforeTheStart() throws Exception {
        show();
        stop();
        Thread.sleep(PAST_DEADLINE_MS);
        Assert.assertFalse("the service kept running after stop()", running());
    }
}
