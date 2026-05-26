// Copyright 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that can
// be found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_CHROME_CHROME_WEB_CONTENTS_VIEW_DELEGATE_ANDROID_H_
#define CEF_LIBCEF_BROWSER_CHROME_CHROME_WEB_CONTENTS_VIEW_DELEGATE_ANDROID_H_

#include "base/memory/raw_ptr.h"
#include "content/public/browser/web_contents_view_delegate.h"

namespace content {
class WebContents;
}

// Android WebContentsViewDelegate for CEF. Bridges the context menu
// event from WebContentsViewAndroid to AlloyBrowserHostImpl::ShowContextMenu.
class CefWebContentsViewDelegateAndroid
    : public content::WebContentsViewDelegate {
 public:
  explicit CefWebContentsViewDelegateAndroid(
      content::WebContents* web_contents);

  CefWebContentsViewDelegateAndroid(
      const CefWebContentsViewDelegateAndroid&) = delete;
  CefWebContentsViewDelegateAndroid& operator=(
      const CefWebContentsViewDelegateAndroid&) = delete;

  // WebContentsViewDelegate overrides.
  void ShowContextMenu(content::RenderFrameHost& render_frame_host,
                       const content::ContextMenuParams& params) override;

 private:
  raw_ptr<content::WebContents> web_contents_;
};

#endif  // CEF_LIBCEF_BROWSER_CHROME_CHROME_WEB_CONTENTS_VIEW_DELEGATE_ANDROID_H_
