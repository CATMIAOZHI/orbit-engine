// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// JNI_OnLoad for libcef.so on Android.
//
// Called automatically by the Android runtime when the library is loaded
// via System.loadLibrary(). Registers the JVM with Chromium's base layer
// and initializes JNI native method registration so that all JNI calls
// throughout libcef.so use the correct JavaVM pointer and find their
// Java counterparts.
//
// This is the Android equivalent of DllMain on Windows — a platform-specific
// library entry point that CEF needs but the C API does not expose directly.

#include <jni.h>

#include "base/android/jni_android.h"

extern "C" {

__attribute__((visibility("default")))
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  base::android::InitVM(vm);
  return JNI_VERSION_1_4;
}

}  // extern "C"
