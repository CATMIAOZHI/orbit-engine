// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest;

import android.Manifest;
import android.os.Build;

import androidx.test.rule.GrantPermissionRule;

import org.junit.rules.TestRule;

/**
 * Grants POST_NOTIFICATIONS where it exists. It was added in Android 13;
 * below that notifications need no grant, and granting it throws.
 */
public final class NotificationPermission {
    private NotificationPermission() {}

    public static TestRule grant() {
        return Build.VERSION.SDK_INT >= 33
                ? GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
                : (base, description) -> base;
    }
}
