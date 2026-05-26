// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be found
// in the LICENSE file.

#include "cef/libcef/browser/chrome/chrome_web_contents_view_delegate_android.h"

#include "cef/libcef/browser/alloy/alloy_browser_host_impl.h"
#include "cef/libcef/browser/browser_host_base.h"
#include "content/public/browser/context_menu_params.h"
#include "content/public/browser/web_contents.h"

CefWebContentsViewDelegateAndroid::CefWebContentsViewDelegateAndroid(
    content::WebContents* web_contents)
    : web_contents_(web_contents) {}

void CefWebContentsViewDelegateAndroid::ShowContextMenu(
    content::RenderFrameHost& render_frame_host,
    const content::ContextMenuParams& params) {
  if (!web_contents_) {
    return;
  }

  auto browser = CefBrowserHostBase::GetBrowserForContents(web_contents_);
  if (!browser) {
    return;
  }

  // AlloyBrowserHostImpl provides the context menu infrastructure
  // (CefMenuManager + CefContextMenuHandler).
  auto* alloy_browser =
      static_cast<AlloyBrowserHostImpl*>(browser.get());
  alloy_browser->ShowContextMenu(params);
}
