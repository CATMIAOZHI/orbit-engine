// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.cefrium.test;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import org.chromium.base.ContextUtils;
import org.chromium.base.PathUtils;
import org.chromium.base.library_loader.LibraryLoader;
import org.chromium.base.library_loader.LibraryProcessType;

/**
 * Minimal Activity that initializes CEF and logs the result.
 * This is the Phase 2 test harness — proves CefInitialize works on Android.
 */
public class CefriumTestActivity extends Activity {
    private static final String TAG = "Cefrium";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "CefriumTestActivity.onCreate");

        // Register application context with Chromium's base library.
        ContextUtils.initApplicationContext(getApplicationContext());

        // Initialize path utilities before loading native libraries.
        PathUtils.setPrivateDataDirectorySuffix("cefrium");

        // Load native libraries via Chromium's LibraryLoader.
        // This handles: JNI_OnLoad, AndroidInfo initialization, PathUtils,
        // and all other base layer initialization that raw System.loadLibrary skips.
        try {
            LibraryLoader.getInstance().setLibraryProcessType(
                LibraryProcessType.PROCESS_BROWSER);
            LibraryLoader.getInstance().ensureInitialized();
            Log.i(TAG, "Chromium LibraryLoader initialized");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library: " + e.getMessage());
            finish();
            return;
        }

        // Get paths the native side needs.
        String cacheDir = getCacheDir().getAbsolutePath();
        String dataDir = getFilesDir().getAbsolutePath();

        // Initialize CEF from native code.
        int result = nativeInitCef(cacheDir, dataDir);
        Log.i(TAG, "nativeInitCef returned: " + result);

        if (result == 0) {
            Log.i(TAG, "CEF INITIALIZED SUCCESSFULLY ON ANDROID");
        } else {
            Log.e(TAG, "CEF initialization failed with code: " + result);
        }
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "CefriumTestActivity.onDestroy — shutting down CEF");
        nativeShutdownCef();
        super.onDestroy();
    }

    // JNI methods implemented in cefrium_test_jni.cc
    private static native int nativeInitCef(String cacheDir, String dataDir);
    private static native void nativeShutdownCef();
}
