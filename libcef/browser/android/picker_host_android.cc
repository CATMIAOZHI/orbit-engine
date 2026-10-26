// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/android/picker_host_android.h"

#include "base/functional/bind.h"
#include "content/browser/date_time_chooser/android/date_time_chooser_android.h"

namespace cef_android {

namespace {

PickerHost* g_picker_host = nullptr;

void ShowDateTime(
    content::WebContents* web_contents,
    blink::mojom::DateTimeDialogValuePtr value,
    content::DateTimeChooser::OpenDateTimeDialogCallback callback) {
  if (!g_picker_host) {
    std::move(callback).Run(false, 0.0);
    return;
  }
  g_picker_host->ShowDateTime(
      web_contents, static_cast<int>(value->dialog_type), value->dialog_value,
      value->minimum, value->maximum, value->step,
      base::BindOnce(
          [](content::DateTimeChooser::OpenDateTimeDialogCallback callback,
             std::optional<double> chosen) {
            std::move(callback).Run(chosen.has_value(), chosen.value_or(0.0));
          },
          std::move(callback)));
}

}  // namespace

void SetPickerHost(PickerHost* host) {
  g_picker_host = host;
  content::DateTimeChooserAndroid::SetWindowlessHandler(
      host ? base::BindRepeating(&ShowDateTime)
           : content::DateTimeChooserAndroid::WindowlessHandler());
}

PickerHost* GetPickerHost() {
  return g_picker_host;
}

}  // namespace cef_android
