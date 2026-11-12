// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentSender;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.ActivityResultRegistryOwner;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import org.chromium.ui.base.IntentRequestTracker;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts the engine's intents (file chooser, camera, document pickers) for a
 * surface browser. An AndroidX activity gets them through its
 * {@link ActivityResultRegistry}, so the result comes back with nothing for
 * the app to forward. Another activity gets them with
 * {@code startActivityForResult}, and the app forwards
 * {@code onActivityResult} to {@link CefriumBrowser#onActivityResult}.
 */
final class ActivityResultIntentDelegate implements IntentRequestTracker.Delegate {
    private static final AtomicInteger sKeys = new AtomicInteger();

    private final WeakReference<Activity> mActivity;
    private IntentRequestTracker mTracker;

    private ActivityResultIntentDelegate(Activity activity) {
        mActivity = new WeakReference<>(activity);
    }

    static IntentRequestTracker createTracker(Activity activity) {
        ActivityResultIntentDelegate delegate = new ActivityResultIntentDelegate(activity);
        delegate.mTracker = IntentRequestTracker.createFromDelegate(delegate);
        return delegate.mTracker;
    }

    @Override
    public boolean startActivityForResult(Intent intent, int requestCode) {
        Activity activity = mActivity.get();
        if (activity == null || intent == null) return false;
        ActivityResultRegistry registry = registryOf(activity);
        if (registry == null) {
            try {
                activity.startActivityForResult(intent, requestCode);
                return true;
            } catch (ActivityNotFoundException e) {
                return false;
            }
        }
        return launch(registry, new ActivityResultContracts.StartActivityForResult(), intent,
                requestCode);
    }

    @Override
    public boolean startIntentSenderForResult(IntentSender intentSender, int requestCode) {
        Activity activity = mActivity.get();
        if (activity == null) return false;
        ActivityResultRegistry registry = registryOf(activity);
        if (registry == null) {
            try {
                activity.startIntentSenderForResult(intentSender, requestCode, null, 0, 0, 0);
                return true;
            } catch (IntentSender.SendIntentException e) {
                return false;
            }
        }
        return launch(registry, new ActivityResultContracts.StartIntentSenderForResult(),
                new IntentSenderRequest.Builder(intentSender).build(), requestCode);
    }

    @Override
    public void finishActivity(int requestCode) {
        // Only for a request started with startActivityForResult: a
        // registry request has a code of the registry's own.
        Activity activity = mActivity.get();
        if (activity != null && registryOf(activity) == null) activity.finishActivity(requestCode);
    }

    @Override
    public WeakReference<Activity> getActivity() {
        return mActivity;
    }

    private static ActivityResultRegistry registryOf(Activity activity) {
        return activity instanceof ActivityResultRegistryOwner
                ? ((ActivityResultRegistryOwner) activity).getActivityResultRegistry()
                : null;
    }

    // One registration per request, removed when its result arrives. Not tied
    // to a lifecycle: the engine's request outlives no activity it needs.
    private <I> boolean launch(ActivityResultRegistry registry,
            androidx.activity.result.contract.ActivityResultContract<I,
                    androidx.activity.result.ActivityResult> contract,
            I input, int requestCode) {
        String key = "com.cefrium.intent." + sKeys.incrementAndGet();
        @SuppressWarnings("unchecked")
        ActivityResultLauncher<I>[] launcher = new ActivityResultLauncher[1];
        launcher[0] = registry.register(key, contract, result -> {
            launcher[0].unregister();
            mTracker.onActivityResult(requestCode, result.getResultCode(), result.getData());
        });
        try {
            launcher[0].launch(input);
            return true;
        } catch (ActivityNotFoundException e) {
            launcher[0].unregister();
            return false;
        }
    }
}
