// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.app.Activity;
import android.content.pm.PackageManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.ActivityResultRegistryOwner;
import androidx.activity.result.contract.ActivityResultContracts;

import org.chromium.ui.permissions.ActivityAndroidPermissionDelegate;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The runtime permissions the engine asks for (the camera, before its file
 * chooser offers a capture), requested through an AndroidX activity's result
 * registry, so the answer comes back with nothing for the app to forward.
 * Chromium's own bookkeeping (what may be asked again) is unchanged.
 */
final class ActivityResultPermissionDelegate extends ActivityAndroidPermissionDelegate {
    private static final AtomicInteger sKeys = new AtomicInteger();

    private final WeakReference<Activity> mOwner;

    ActivityResultPermissionDelegate(Activity activity) {
        super(new WeakReference<>(activity));
        mOwner = new WeakReference<>(activity);
    }

    static boolean canUse(Activity activity) {
        return activity instanceof ActivityResultRegistryOwner;
    }

    @Override
    protected boolean requestPermissionsFromRequester(String[] permissions, int requestCode) {
        Activity activity = mOwner.get();
        if (!(activity instanceof ActivityResultRegistryOwner)) {
            return super.requestPermissionsFromRequester(permissions, requestCode);
        }
        ActivityResultRegistry registry =
                ((ActivityResultRegistryOwner) activity).getActivityResultRegistry();
        @SuppressWarnings("unchecked")
        ActivityResultLauncher<String[]>[] launcher = new ActivityResultLauncher[1];
        launcher[0] = registry.register("com.cefrium.permissions." + sKeys.incrementAndGet(),
                new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                    launcher[0].unregister();
                    handlePermissionResult(requestCode, permissions, results(permissions, granted));
                });
        launcher[0].launch(permissions);
        return true;
    }

    private static int[] results(String[] permissions, Map<String, Boolean> granted) {
        int[] out = new int[permissions.length];
        for (int i = 0; i < permissions.length; i++) {
            out[i] = Boolean.TRUE.equals(granted.get(permissions[i]))
                    ? PackageManager.PERMISSION_GRANTED
                    : PackageManager.PERMISSION_DENIED;
        }
        return out;
    }
}
