package org.chromium.build;
/** Build configuration for Cefrium SDK. */
public class BuildConfig {
    public static boolean ENABLE_ASSERTS;
    public static boolean IS_UBSAN;
    public static boolean IS_CHROME_BRANDED;
    public static int R_STRING_PRODUCT_VERSION;
    public static int MIN_SDK_VERSION = 29;
    public static long VERSION_CODE = 1;
    public static boolean IS_INCREMENTAL_INSTALL;
    public static boolean IS_FOR_TEST;
    public static boolean IS_CRONET_BUILD;
    public static boolean CRONET_FOR_AOSP_BUILD;
    public static boolean WRITE_CLANG_PROFILING_DATA;
    public static boolean ENABLE_DEBUG_LOGS;
    public static String[] APK_ASSETS_SUFFIXED_LIST = {};
    public static String APK_ASSETS_SUFFIX = null;
    public static boolean IS_DESKTOP_ANDROID;
    public static boolean IS_PROGUARD_ENABLED = false;
    public static boolean JAVALESS_RENDERERS_AVAILABLE = true;
    public static boolean DISABLE_STRICT_MODE_CONTEXT;
    public static final String LOGTAG_PREFIX = "cr_";
}
