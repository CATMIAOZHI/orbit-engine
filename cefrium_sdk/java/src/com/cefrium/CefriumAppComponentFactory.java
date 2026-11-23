// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import androidx.core.app.CoreComponentFactory;

import java.util.regex.Pattern;

/**
 * Keeps the app's {@link Application} out of the engine's child processes.
 *
 * <p>Android creates the app's Application in every process of the app,
 * including the engine's isolated renderer processes, where files and
 * preferences cannot be opened. Main-process work in
 * {@code Application.onCreate()} then kills the renderer and pages stay blank.
 * This factory, set by the SDK manifest, gives those processes a plain
 * Application instead; the engine needs nothing from the app's one there.
 * Everything else goes to androidx's factory, which this one replaces.
 *
 * <p>An app with its own factory extends this class instead of
 * {@code AppComponentFactory} and calls {@code super}.
 */
public class CefriumAppComponentFactory extends AppComponentFactory {
    // The engine's services in the SDK manifest. A process started with
    // bindIsolatedService gets ":<instance>" appended.
    private static final Pattern ENGINE_PROCESS =
            Pattern.compile("[^:]+:(sandboxed|privileged)_process\\d+(:.*)?");

    private final CoreComponentFactory mAndroidx = new CoreComponentFactory();

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (isEngineProcess(Application.getProcessName())) {
            return new Application();
        }
        return mAndroidx.instantiateApplication(cl, className);
    }

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo aInfo) {
        return mAndroidx.instantiateClassLoader(cl, aInfo);
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return mAndroidx.instantiateActivity(cl, className, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return mAndroidx.instantiateReceiver(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return mAndroidx.instantiateService(cl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return mAndroidx.instantiateProvider(cl, className);
    }

    /**
     * Whether {@code processName} is one of the engine's child processes
     * (renderer, GPU, utility), as the SDK manifest declares them. For an
     * app's own factory, or an Application that checks for itself:
     * {@code isEngineProcess(Application.getProcessName())}.
     */
    public static boolean isEngineProcess(String processName) {
        return processName != null && ENGINE_PROCESS.matcher(processName).matches();
    }

    /**
     * Logs one error when the app has its own Application and its manifest
     * replaced this factory with one that does not extend it: that
     * Application then runs in the engine's processes again. Called once in
     * the main process at start-up.
     */
    static void checkInstalled(Context context) {
        ApplicationInfo info = context.getApplicationInfo();
        if (info.className == null) {
            return;
        }
        String name = info.appComponentFactory;
        try {
            if (name != null && CefriumAppComponentFactory.class.isAssignableFrom(
                    Class.forName(name, false, context.getClassLoader()))) {
                return;
            }
        } catch (ClassNotFoundException e) {
            // Reported below.
        }
        Log.e("Cefrium", "android:appComponentFactory is " + name
                + ", which does not extend com.cefrium.CefriumAppComponentFactory, so "
                + info.className + " also runs in the engine's isolated processes;"
                + " if its onCreate() touches files or preferences, pages stay blank."
                + " Extend CefriumAppComponentFactory, or return early from onCreate()"
                + " when CefriumAppComponentFactory.isEngineProcess("
                + "Application.getProcessName()). https://cefrium.com/agents.md");
    }
}
