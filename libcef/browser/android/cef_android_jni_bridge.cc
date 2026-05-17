// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// JNI bridge for Android test APK. Compiled into libcef.so (not a separate
// shared library) to share static globals with the rest of Chromium code.

#include <jni.h>
#include <string>

#include "cef/include/cef_app.h"
#include "cef/include/cef_browser.h"

#include <android/log.h>

#define TAG "Cefrium"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool g_cef_initialized = false;

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

  CefMainArgs main_args;
  int exit_code = CefExecuteProcess(main_args, nullptr, nullptr);
  if (exit_code >= 0) {
    LOGE("CefExecuteProcess returned %d", exit_code);
    env->ReleaseStringUTFChars(cache_dir_jstr, cache_dir);
    env->ReleaseStringUTFChars(data_dir_jstr, data_dir);
    return exit_code;
  }
  LOGI("CefExecuteProcess returned %d, continuing as browser", exit_code);

  CefSettings settings;
  settings.no_sandbox = true;
  settings.windowless_rendering_enabled = true;
  settings.multi_threaded_message_loop = false;
  settings.log_severity = LOGSEVERITY_INFO;
  CefString(&settings.root_cache_path).FromASCII(cache_dir);

  std::string locales_dir = std::string(data_dir) + "/locales";
  CefString(&settings.locales_dir_path).FromASCII(locales_dir.c_str());

  LOGI("Calling CefInitialize...");
  bool result = CefInitialize(main_args, settings, nullptr, nullptr);

  env->ReleaseStringUTFChars(cache_dir_jstr, cache_dir);
  env->ReleaseStringUTFChars(data_dir_jstr, data_dir);

  if (result) {
    LOGI("CefInitialize SUCCESS");
    g_cef_initialized = true;
    return 0;
  } else {
    LOGE("CefInitialize FAILED");
    return 1;
  }
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_org_cefrium_test_CefriumTestActivity_nativeDoMessageLoopWork(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  if (g_cef_initialized) {
    CefDoMessageLoopWork();
  }
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_org_cefrium_test_CefriumTestActivity_nativeShutdownCef(
    JNIEnv* /* env */,
    jobject /* this */) {
  if (g_cef_initialized) {
    LOGI("Calling CefShutdown...");
    CefShutdown();
    g_cef_initialized = false;
    LOGI("CefShutdown complete");
  }
}
