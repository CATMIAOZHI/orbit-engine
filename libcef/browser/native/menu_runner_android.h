// Copyright 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that can
// be found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_NATIVE_MENU_RUNNER_ANDROID_H_
#define CEF_LIBCEF_BROWSER_NATIVE_MENU_RUNNER_ANDROID_H_
#pragma once

#include "cef/libcef/browser/menu_runner.h"

// Android context menu runner. Shows menu items using a native Android
// AlertDialog via JNI callback. This is the Android equivalent of
// CefMenuRunnerViewsAura on Linux/Windows.
//
// The menu is displayed by calling a Java method on the browser's
// associated Java object. The Java side shows an AlertDialog and
// calls back with the selected command ID.
class CefMenuRunnerAndroid : public CefMenuRunner {
 public:
  CefMenuRunnerAndroid();

  // CefMenuRunner methods.
  bool RunContextMenu(AlloyBrowserHostImpl* browser,
                      CefMenuModelImpl* model,
                      const content::ContextMenuParams& params) override;
  void CancelContextMenu() override;
};

#endif  // CEF_LIBCEF_BROWSER_NATIVE_MENU_RUNNER_ANDROID_H_
