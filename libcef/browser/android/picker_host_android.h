// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_PICKER_HOST_ANDROID_H_
#define CEF_LIBCEF_BROWSER_ANDROID_PICKER_HOST_ANDROID_H_

#include <optional>
#include <string>
#include <vector>

#include "base/functional/callback.h"
#include "third_party/skia/include/core/SkColor.h"

namespace content {
class WebContents;
}

namespace cef_android {

// Android Chromium opens <select>, the date and time inputs and
// <input type=color> as native dialogs attached to the WebContents' window.
// An off-screen browser has no window, so the embedder shows them through the
// host installed here. Each callback runs exactly once; with no host
// installed, the request is cancelled.
class PickerHost {
 public:
  struct SelectItem {
    enum class Type { kOption, kCheckableOption, kGroup, kSeparator };

    std::u16string label;
    Type type = Type::kOption;
    bool enabled = true;
    bool checked = false;
  };

  // Indices into the items, or nullopt if the picker was cancelled.
  using SelectCallback =
      base::OnceCallback<void(std::optional<std::vector<int>>)>;
  // The value in blink::mojom::DateTimeDialogValue units, or nullopt.
  using DateTimeCallback = base::OnceCallback<void(std::optional<double>)>;
  using ColorCallback = base::OnceCallback<void(std::optional<SkColor>)>;

  virtual ~PickerHost() = default;

  virtual void ShowSelect(content::WebContents* web_contents,
                          std::vector<SelectItem> items,
                          int selected_item,
                          bool allow_multiple_selection,
                          SelectCallback callback) = 0;

  // |dialog_type| is a ui::TextInputType.
  virtual void ShowDateTime(content::WebContents* web_contents,
                            int dialog_type,
                            double value,
                            double minimum,
                            double maximum,
                            double step,
                            DateTimeCallback callback) = 0;

  virtual void ShowColor(content::WebContents* web_contents,
                         SkColor color,
                         std::vector<SkColor> suggestions,
                         ColorCallback callback) = 0;
};

void SetPickerHost(PickerHost* host);
PickerHost* GetPickerHost();

}  // namespace cef_android

#endif  // CEF_LIBCEF_BROWSER_ANDROID_PICKER_HOST_ANDROID_H_
