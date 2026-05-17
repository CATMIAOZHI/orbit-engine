// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.cefrium.test;

import android.app.Activity;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import org.chromium.base.ApplicationStatus;
import org.chromium.base.CommandLine;
import org.chromium.base.ContextUtils;
import org.chromium.base.PathUtils;
import org.chromium.base.library_loader.LibraryLoader;
import org.chromium.base.library_loader.LibraryProcessType;
import org.chromium.ui.base.ResourceBundle;

/**
 * Minimal Activity that initializes CEF and logs the result.
 * This is the Phase 2 test harness — proves CefInitialize works on Android.
 */
public class CefriumTestActivity extends Activity {
    private static final String TAG = "Cefrium";
    private static final int CEF_MESSAGE_LOOP_INTERVAL_MS = 16; // ~60fps
    private Handler mHandler;
    private boolean mCefRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Initialize Chromium base utilities BEFORE super.onCreate() so that
        // lifecycle callbacks find ApplicationStatus properly configured.
        ContextUtils.initApplicationContext(getApplicationContext());
        if (!CommandLine.isInitialized()) {
            CommandLine.init(null);
        }
        PathUtils.setPrivateDataDirectorySuffix("cefrium");
        ApplicationStatus.initialize(getApplication());

        super.onCreate(savedInstanceState);

        Log.i(TAG, "CefriumTestActivity.onCreate");

        // Register available locale paks with Chromium's ResourceBundle.
        ResourceBundle.setAvailablePakLocales(new String[]{"en-US"});

        // Load native libraries. libcef.so's JNI_OnLoad registers the JVM
        // and JNI natives with Chromium's base layer.
        try {
            System.loadLibrary("cef");
            Log.i(TAG, "libcef.so loaded");
            System.loadLibrary("cefrium_test");
            Log.i(TAG, "libcefrium_test.so loaded");
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

            // Set a minimal content view to keep the Activity alive.
            TextView tv = new TextView(this);
            tv.setText("Cefrium: CEF initialized. Message loop running.");
            setContentView(tv);

            // Start pumping the CEF message loop on the UI thread.
            mCefRunning = true;
            mHandler = new Handler(Looper.getMainLooper());
            mHandler.post(this::pumpCefMessageLoop);
        } else {
            Log.e(TAG, "CEF initialization failed with code: " + result);
        }
    }

    private void pumpCefMessageLoop() {
        if (mCefRunning) {
            nativeDoMessageLoopWork();
            mHandler.postDelayed(this::pumpCefMessageLoop,
                                 CEF_MESSAGE_LOOP_INTERVAL_MS);
        }
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "CefriumTestActivity.onDestroy — shutting down CEF");
        mCefRunning = false;
        nativeShutdownCef();
        super.onDestroy();
    }

    // JNI methods implemented in cefrium_test_jni.cc
    private static native int nativeInitCef(String cacheDir, String dataDir);
    private static native void nativeDoMessageLoopWork();
    private static native void nativeShutdownCef();

    private void extractAssetToFile(String assetPath, File outFile) {
        if (outFile.exists()) return;
        outFile.getParentFile().mkdirs();
        try (InputStream in = getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            Log.i(TAG, "Extracted " + assetPath + " to " + outFile);
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract " + assetPath + ": " + e);
        }
    }
}
