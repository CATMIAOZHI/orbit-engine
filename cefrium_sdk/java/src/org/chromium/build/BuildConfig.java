// Copyright 2015 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package org.chromium.build;
/**
 *  Build configuration. Generated on a per-target basis.
 */
public class BuildConfig {
    public static boolean ENABLE_ASSERTS;
    public static boolean IS_UBSAN;
    public static boolean IS_CHROME_BRANDED;
    // The ID of the android string resource that stores the product version.
    // This layer of indirection is necessary to make the resource dependency
    // optional for android_apk targets/base_java (ex. for cronet).
    public static int R_STRING_PRODUCT_VERSION;
    // Minimum SDK Version supported by this apk.
    // Be cautious when using this value, as it can happen that older apks get
    // installed on newer Android version (e.g. when a device goes through a
    // system upgrade). It is also convenient for developing to have all
    // features available through a single APK.
    // However, it's pretty safe to assument that a feature specific to KitKat
    // will never be needed in an APK with MIN_SDK_VERSION = Oreo.
    public static int MIN_SDK_VERSION = 29;
    // Value of android:versionCode at compile time.
    // Use ApkInfo.getPackageVersionCode() instead of this because this will not reflect any
    // version code updates made by patching tools.
    public static long ORIGINAL_VERSION_CODE = 1;
    public static boolean IS_INCREMENTAL_INSTALL;
    public static boolean IS_FOR_TEST;
    public static boolean IS_ROBOLECTRIC;
    public static boolean IS_CRONET_BUILD;
    public static boolean CRONET_FOR_AOSP_BUILD;
    public static boolean WRITE_CLANG_PROFILING_DATA;
    public static boolean ENABLE_DEBUG_LOGS;
    public static String[] APK_ASSETS_SUFFIXED_LIST = {};
    public static String APK_ASSETS_SUFFIX = null;
    // Use in conjunction with FEATURE_PC check to limit desktop-only
    // features to devices with build flag. Should only be used in
    // DeviceInfo.java since this is the source of truth for isDesktop check.
    // (TODO: crbug.com/430983585) Clean up this flag once the desktop
    // build is fully functional.
    public static boolean IS_DESKTOP_ANDROID;
    // Use to check if proguard is enabled for the current build.
    public static boolean IS_PROGUARD_ENABLED = false;
    // Controls whether or not StrictModeContext is a no-op.
    public static boolean DISABLE_STRICT_MODE_CONTEXT;
    public static final String LOGTAG_PREFIX = "cr_";
}
