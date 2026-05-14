// Copyright (c) 2015 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

#include "cef/libcef/browser/browser_guest_util.h"

#include "build/build_config.h"
#include "chrome/browser/browser_process.h"
#include "content/browser/browser_plugin/browser_plugin_guest.h"
#include "content/browser/web_contents/web_contents_impl.h"

#if !BUILDFLAG(IS_ANDROID)
#include "chrome/browser/printing/print_preview_dialog_controller.h"
#endif

namespace {

content::WebContents* GetOwnerForBrowserPluginGuest(
    const content::WebContents* guest) {
  auto* guest_impl = static_cast<const content::WebContentsImpl*>(guest);
  content::BrowserPluginGuest* plugin_guest =
      guest_impl->GetBrowserPluginGuest();
  if (plugin_guest) {
    return plugin_guest->owner_web_contents();
  }
  return nullptr;
}

#if !BUILDFLAG(IS_ANDROID)
content::WebContents* GetInitiatorForPrintPreviewDialog(
    const content::WebContents* guest) {
  auto print_preview_controller =
      g_browser_process->print_preview_dialog_controller();
  return print_preview_controller->GetInitiator(
      const_cast<content::WebContents*>(guest));
}
#endif

}  // namespace

content::WebContents* GetOwnerForGuestContents(
    const content::WebContents* guest) {
  if (auto* owner = GetOwnerForBrowserPluginGuest(guest)) {
    return owner;
  }

#if !BUILDFLAG(IS_ANDROID)
  if (auto* initiator = GetInitiatorForPrintPreviewDialog(guest)) {
    if (auto* owner = GetOwnerForBrowserPluginGuest(initiator)) {
      return owner;
    }
    return initiator;
  }
#endif

  return nullptr;
}

bool IsBrowserPluginGuest(const content::WebContents* web_contents) {
  return !!GetOwnerForBrowserPluginGuest(web_contents);
}

bool IsPrintPreviewDialog(const content::WebContents* web_contents) {
#if !BUILDFLAG(IS_ANDROID)
  return !!GetInitiatorForPrintPreviewDialog(web_contents);
#else
  return false;
#endif
}
