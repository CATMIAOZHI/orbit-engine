// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.cefrium.test;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import org.chromium.base.CommandLine;
import org.chromium.base.library_loader.LibraryLoader;

/**
 * Minimal Activity that initializes CEF and loads a page.
 * Library loading is handled by LibraryLoader (not raw System.loadLibrary)
 * to support multi-process mode via Android Services.
 */
public class CefriumTestActivity extends Activity {
    private static final String TAG = "Cefrium";
    private Handler mHandler;
    private boolean mCefRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // CefriumTestApplication.attachBaseContext already initialized:
        // ContextUtils, PathUtils, ApplicationStatus, LibraryProcessType.
        if (!CommandLine.isInitialized()) {
            CommandLine.init(null);
        }

        super.onCreate(savedInstanceState);

        Log.i(TAG, "CefriumTestActivity.onCreate");

        // Load and initialize native library via LibraryLoader.
        // This handles both browser and child process initialization
        // consistently, enabling multi-process mode.
        try {
            LibraryLoader.getInstance().ensureInitialized();
            Log.i(TAG, "libcef.so loaded via LibraryLoader");
        } catch (Exception e) {
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

            TextView tv = new TextView(this);
            tv.setText("Cefrium: CEF initialized. Creating browser...");
            setContentView(tv);

            mHandler = new Handler(Looper.getMainLooper());
            mHandler.postDelayed(() -> {
                nativeCreateBrowser("data:text/html,<h1>CEF on Android</h1>");
            }, 500);
        } else {
            Log.e(TAG, "CEF initialization failed with code: " + result);
        }
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "CefriumTestActivity.onDestroy — shutting down CEF");
        mCefRunning = false;
        nativeShutdownCef();
        super.onDestroy();
    }

    // JNI methods implemented in cef_android_jni_bridge.cc (inside libcef.so)
    private static native int nativeInitCef(String cacheDir, String dataDir);
    private static native void nativeCreateBrowser(String url);
    private static native void nativeDoMessageLoopWork();
    private static native void nativeShutdownCef();
}
