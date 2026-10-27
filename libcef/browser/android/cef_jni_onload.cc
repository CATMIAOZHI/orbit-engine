// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// JNI_OnLoad for libcef.so on Android.
//
// Initialization happens in NativeInitializationHook(), which
// base::android::LibraryLoaded() calls in every process once its type is
// known, as Chrome does. Child processes register a ContentMainDelegate there
// so that ContentMain.start() can find it. In the browser process,
// CefMainRunner handles delegate creation during CefInitialize().
//
// JNI_OnLoad itself must not decide the process type: an app zygote loads the
// library before forking renderers from it, and is not a renderer itself.
// Javaless renderers (Android 17+) have no JVM and call the hook directly.

#include <jni.h>

#include "base/android/jni_android.h"
#include "base/android/library_loader/library_loader_hooks.h"
#include "cef/libcef/browser/android/cefrium_app.h"
#include "cef/libcef/common/chrome/chrome_main_delegate_cef.h"
#include "content/public/app/content_jni_onload.h"
#include "content/public/app/content_main.h"
#include "ui/base/resource/resource_bundle_android.h"

bool NativeInitializationHook(
    base::android::LibraryProcessType library_process_type) {
  static bool initialized = false;
  if (initialized) {
    return true;
  }
  if (!content::android::OnJNIOnLoadInit()) {
    return false;
  }
  initialized = true;

  // Tell Chromium that locale paks are stored as APK assets.
  // Must be set before InitializeICU() which is called by libraryLoaded().
  ui::SetLocalePaksStoredInApk(true);

  if (library_process_type == base::android::PROCESS_CHILD) {
    content::SetContentMainDelegate(
        new ChromeMainDelegateCef(/*runner=*/nullptr, /*settings=*/nullptr,
                                  /*application=*/new CefriumApp()));
  }
  return true;
}

extern "C" {

__attribute__((visibility("default")))
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  base::android::InitVM(vm);
  base::android::SetNativeInitializationHook(NativeInitializationHook);
  return JNI_VERSION_1_4;
}

}  // extern "C"
