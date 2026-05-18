// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// JNI_OnLoad for libcef.so on Android.
//
// Minimal: just registers the JVM pointer and sets up JNI natives.
// The full initialization (ICU, locale paks, process type detection)
// is handled by LibraryLoader.libraryLoaded() which is called by
// LibraryLoader.ensureInitialized() in both browser and child processes.
//
// This ensures consistent initialization across multi-process mode
// (browser, renderer, GPU, utility all use the same path).

#include <jni.h>

#include "base/android/jni_android.h"
#include "content/public/app/content_jni_onload.h"

extern "C" {

__attribute__((visibility("default")))
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  base::android::InitVM(vm);

  if (!content::android::OnJNIOnLoadInit()) {
    return -1;
  }

  return JNI_VERSION_1_4;
}

}  // extern "C"
