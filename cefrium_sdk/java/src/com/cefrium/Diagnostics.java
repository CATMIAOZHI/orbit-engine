// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.webkit.WebView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** The text block behind {@link Cefrium#diagnostics}. */
final class Diagnostics {
    static final String ISSUES = "https://codeberg.org/cefrium/cef-android/issues";

    /** Appended to the messages of failures that are the SDK's, not the app's. */
    static final String REPORT = " Please report it at " + ISSUES
            + " with the text of Cefrium.diagnostics(context).";

    private Diagnostics() {}

    static String collect(Context context) {
        Context app = context.getApplicationContext();
        StringBuilder b = new StringBuilder("Cefrium diagnostics\n");
        line(b, "sdk", Cefrium.VERSION);
        line(b, "engine", "Chromium " + Cefrium.CHROMIUM_VERSION);
        line(b, "initialized", Cefrium.isInitialized() ? "yes" : "no");
        line(b, "device", Build.MANUFACTURER + " " + Build.MODEL + ", Android "
                + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        line(b, "abi", "process " + (Process.is64Bit() ? "64-bit" : "32-bit")
                + ", device " + String.join(" ", Build.SUPPORTED_ABIS));
        line(b, "google libraries in the app",
                hasClass("com.google.android.gms.common.GoogleApiAvailability") ? "yes" : "no");
        line(b, "play services on the device",
                installed(app, "com.google.android.gms") ? "yes" : "no");
        app(b, app);
        PackageInfo webview = WebView.getCurrentWebViewPackage();
        line(b, "webview", webview == null ? "none"
                : webview.packageName + " " + webview.versionName);
        exits(b, app);
        return b.toString();
    }

    private static void line(StringBuilder b, String key, String value) {
        b.append(key).append(": ").append(value).append('\n');
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, Diagnostics.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean installed(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static void app(StringBuilder b, Context context) {
        ApplicationInfo info = context.getApplicationInfo();
        String version = "?";
        try {
            version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            // Our own package: present.
        }
        line(b, "app", context.getPackageName() + " " + version
                + " (targetSdk " + info.targetSdkVersion + ")");
        line(b, "application class", info.className == null ? "none" : info.className);
        line(b, "appComponentFactory",
                info.appComponentFactory == null ? "none" : info.appComponentFactory);
    }

    // The engine's processes end in the app's exit records: a crash there is
    // what a blank page usually is.
    private static void exits(StringBuilder b, Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) return;
        List<ApplicationExitInfo> list;
        try {
            list = am.getHistoricalProcessExitReasons(null, 0, 8);
        } catch (RuntimeException e) {
            return;
        }
        b.append("recent process exits:").append(list.isEmpty() ? " none\n" : "\n");
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.ROOT);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        String pkg = context.getPackageName();
        for (ApplicationExitInfo e : list) {
            String process = e.getProcessName();
            if (process != null && process.startsWith(pkg)) {
                process = process.substring(pkg.length());
                if (process.isEmpty()) process = "(main)";
            }
            b.append("  ").append(utc.format(new Date(e.getTimestamp()))).append(' ')
                    .append(process).append(" reason ").append(e.getReason())
                    .append(" (").append(reason(e.getReason())).append(") status ")
                    .append(e.getStatus());
            if (e.getDescription() != null) b.append(" \"").append(e.getDescription()).append('"');
            b.append('\n');
        }
    }

    private static String reason(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "exit self";
            case ApplicationExitInfo.REASON_SIGNALED: return "signaled";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low memory";
            case ApplicationExitInfo.REASON_CRASH: return "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "init failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "permission change";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "resource usage";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "user requested";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "user stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "dependency died";
            case ApplicationExitInfo.REASON_OTHER: return "other";
            default: return "unknown";
        }
    }
}
