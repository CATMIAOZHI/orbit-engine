// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// JNI_OnLoad for libcef.so on Android.
//
// Called in EVERY process (browser, renderer, GPU, utility) when
// libcef.so is loaded. In child processes, registers a ContentMainDelegate
// so that ContentMain.start() can find it. In the browser process,
// CefMainRunner handles delegate creation during CefInitialize().

#include <jni.h>
#include <unistd.h>

#include "base/android/jni_android.h"
#include "cef/libcef/common/chrome/chrome_main_delegate_cef.h"
#include "content/public/app/content_jni_onload.h"
#include "content/public/app/content_main.h"

namespace {

// Check if this is a child process by examining /proc/self/cmdline.
// Child processes have ":" in their process name (e.g., ":sandboxed_process0").
bool IsChildProcess() {
  char buf[256] = {};
  FILE* f = fopen("/proc/self/cmdline", "r");
  if (f) {
    fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
  }
  return strstr(buf, ":") != nullptr;
}

}  // namespace

extern "C" {

__attribute__((visibility("default")))
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  base::android::InitVM(vm);

  if (!content::android::OnJNIOnLoadInit()) {
    return -1;
  }

  if (IsChildProcess()) {
    // Child processes (renderer, GPU, utility) need a ContentMainDelegate
    // before ContentMain.start() is called. The browser process handles
    // this in CefMainRunner::ContentMainInitialize() with actual
    // runner/settings values.
    content::SetContentMainDelegate(
        new ChromeMainDelegateCef(/*runner=*/nullptr, /*settings=*/nullptr,
                                  /*application=*/nullptr));
  }

  return JNI_VERSION_1_4;
}

}  // extern "C"
