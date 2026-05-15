// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// JNI bridge for the Cefrium Android test APK.
// Called from CefriumTestActivity.java to initialize and shut down CEF.

#include <jni.h>

#include <string>

// base::android for proper JVM initialization with JNI registration.
#include "base/android/jni_android.h"
#include "base/android/library_loader/library_loader_hooks.h"
#include "include/capi/cef_app_capi.h"
#include "include/capi/cef_browser_capi.h"
#include "include/internal/cef_types.h"

#include <android/log.h>

#define TAG "Cefrium"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool g_cef_initialized = false;

// Called by the VM when the shared library is loaded.
// Sets g_jvm so that jni_zero::IsVMInitialized() returns true and
// jni_zero::AttachCurrentThread() can obtain a JNIEnv.
// We do NOT call jni_zero::InitVM() because it requires JNI multiplexing
// hash alignment that is auto-generated per-APK. CEF will handle its own
// JNI class registration during CefInitialize.
extern "C" __attribute__((visibility("default")))
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  base::android::InitVM(vm);
  LOGI("JNI_OnLoad: Chromium JNI initialized");
  return JNI_VERSION_1_4;
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT jint JNICALL
Java_org_cefrium_test_CefriumTestActivity_nativeInitCef(
    JNIEnv* env,
    jobject /* this */,
    jstring cache_dir_jstr,
    jstring data_dir_jstr) {
  LOGI("nativeInitCef called");

  const char* cache_dir = env->GetStringUTFChars(cache_dir_jstr, nullptr);
  const char* data_dir = env->GetStringUTFChars(data_dir_jstr, nullptr);

  LOGI("cache_dir: %s", cache_dir);
  LOGI("data_dir: %s", data_dir);

  // CefExecuteProcess — returns -1 for the browser process.
  cef_main_args_t main_args = {};
  int exit_code = cef_execute_process(&main_args, nullptr, nullptr);
  if (exit_code >= 0) {
    LOGE("cef_execute_process returned %d (not browser process?)", exit_code);
    env->ReleaseStringUTFChars(cache_dir_jstr, cache_dir);
    env->ReleaseStringUTFChars(data_dir_jstr, data_dir);
    return exit_code;
  }
  LOGI("cef_execute_process returned %d, continuing as browser", exit_code);

  // Configure CEF settings.
  cef_settings_t settings = {};
  settings.size = sizeof(settings);
  settings.no_sandbox = 1;
  settings.windowless_rendering_enabled = 1;
  settings.multi_threaded_message_loop = 0;
  settings.log_severity = LOGSEVERITY_INFO;

  // Set cache path to the app's private cache directory.
  cef_string_utf8_to_utf16(cache_dir, strlen(cache_dir),
                           &settings.root_cache_path);

  LOGI("Calling cef_initialize...");
  int result = cef_initialize(&main_args, &settings, nullptr, nullptr);

  env->ReleaseStringUTFChars(cache_dir_jstr, cache_dir);
  env->ReleaseStringUTFChars(data_dir_jstr, data_dir);

  if (result) {
    LOGI("cef_initialize SUCCESS");
    g_cef_initialized = true;
    return 0;
  } else {
    LOGE("cef_initialize FAILED");
    return 1;
  }
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_org_cefrium_test_CefriumTestActivity_nativeShutdownCef(
    JNIEnv* /* env */,
    jobject /* this */) {
  if (g_cef_initialized) {
    LOGI("Calling cef_shutdown...");
    cef_shutdown();
    g_cef_initialized = false;
    LOGI("cef_shutdown complete");
  }
}
