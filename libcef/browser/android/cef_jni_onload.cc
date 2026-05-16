// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// JNI_OnLoad for libcef.so on Android.
//
// Called automatically by the Android runtime when the library is loaded
// via System.loadLibrary(). Registers the JVM with Chromium's base layer
// and initializes JNI native method registration so that Java→native
// dispatch via GEN_JNI works correctly.
//
// This follows the same pattern as content_shell's JNI_OnLoad:
// 1. InitVM — stores the JVM pointer for jni_zero::AttachCurrentThread
// 2. OnJNIOnLoadInit — creates AtExitManager + registers JNI natives
//
// The AtExitManager in OnJNIOnLoadInit uses shadow mode to coexist with
// the Zygote's AtExitManager (see library_loader_hooks.cc patch).

#include <jni.h>

#include "base/android/jni_android.h"
#include "content/public/app/content_jni_onload.h"
#include "ui/base/resource/resource_bundle_android.h"

extern "C" {

__attribute__((visibility("default")))
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  base::android::InitVM(vm);

  if (!content::android::OnJNIOnLoadInit()) {
    return -1;
  }

  // Locale paks are stored in APK assets/stored-locales/.
  ui::SetLocalePaksStoredInApk(true);

  return JNI_VERSION_1_4;
}

}  // extern "C"
