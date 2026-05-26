// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be found
// in the LICENSE file.

#include "cef/libcef/browser/native/menu_runner_android.h"

#include "base/logging.h"

CefMenuRunnerAndroid::CefMenuRunnerAndroid() = default;

bool CefMenuRunnerAndroid::RunContextMenu(
    AlloyBrowserHostImpl* browser,
    CefMenuModelImpl* model,
    const content::ContextMenuParams& params) {
  // Android does not have a platform-native context menu widget like
  // Views/Aura on desktop. The menu display must be handled by the
  // client's CefContextMenuHandler::RunContextMenu() override, which
  // is called before this runner. If the client does not implement
  // RunContextMenu, the context menu will not be shown.
  //
  // To display a context menu on Android, implement
  // CefContextMenuHandler::RunContextMenu() and show an AlertDialog
  // or PopupMenu from your Java/Kotlin Activity.
  LOG(WARNING) << "Default Android context menu runner has no platform UI. "
                  "Implement CefContextMenuHandler::RunContextMenu() to "
                  "display context menus on Android.";
  return false;
}

void CefMenuRunnerAndroid::CancelContextMenu() {
  // No platform menu to cancel.
}
