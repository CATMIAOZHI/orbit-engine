// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Stub implementations for CEF symbols that reference desktop-only features
// (Views UI, browser host creation) not yet available on Android.
//
// These stubs are temporary. Each will be replaced with a proper Android
// implementation as the corresponding feature is ported. See TODO.md for
// the full list of features to implement.

#include "build/build_config.h"

#if BUILDFLAG(IS_ANDROID)

#include "base/logging.h"
#include "cef/include/cef_browser.h"
#include "cef/include/views/cef_browser_view.h"
#include "cef/include/views/cef_display.h"
#include "cef/include/views/cef_label_button.h"
#include "cef/include/views/cef_menu_button.h"
#include "cef/include/views/cef_panel.h"
#include "cef/include/views/cef_scroll_view.h"
#include "cef/include/views/cef_textfield.h"
#include "cef/include/views/cef_window.h"

// =============================================================================
// =============================================================================
// CefBrowserHost — browser creation for Android.
// browser_host_create.cc cannot be included on Android (desktop-only headers).
// This implementation creates Alloy-style windowless browsers directly.
// =============================================================================

#include "cef/libcef/browser/alloy/alloy_browser_host_impl.h"
#include "cef/libcef/browser/browser_host_base.h"
#include "cef/libcef/browser/context.h"
#include "cef/libcef/browser/thread_util.h"

// static
bool CefBrowserHost::CreateBrowser(
    const CefWindowInfo& windowInfo,
    CefRefPtr<CefClient> client,
    const CefString& url,
    const CefBrowserSettings& settings,
    CefRefPtr<CefDictionaryValue> extra_info,
    CefRefPtr<CefRequestContext> request_context) {
  // On Android, only Alloy style is supported.
  CefBrowserCreateParams params;
  params.window_info = std::make_unique<CefWindowInfo>(windowInfo);
  params.client = client;
  params.url = url;
  params.settings = settings;
  params.extra_info = extra_info;
  params.request_context = request_context;

  auto browser = AlloyBrowserHostImpl::Create(params);
  return browser.get() != nullptr;
}

// static
CefRefPtr<CefBrowser> CefBrowserHost::CreateBrowserSync(
    const CefWindowInfo& windowInfo,
    CefRefPtr<CefClient> client,
    const CefString& url,
    const CefBrowserSettings& settings,
    CefRefPtr<CefDictionaryValue> extra_info,
    CefRefPtr<CefRequestContext> request_context) {
  CefBrowserCreateParams params;
  params.window_info = std::make_unique<CefWindowInfo>(windowInfo);
  params.client = client;
  params.url = url;
  params.settings = settings;
  params.extra_info = extra_info;
  params.request_context = request_context;

  return AlloyBrowserHostImpl::Create(params);
}

// static
CefRefPtr<CefBrowser> CefBrowserHost::GetBrowserByIdentifier(
    int browser_id) {
  return nullptr;
}

// =============================================================================
// Views UI factories — not available on Android (toolkit_views=false)
//
// These return nullptr on Android. The Views framework is desktop-only.
// Android apps should use CefBrowserHost with windowless rendering and
// the Android-native UI framework instead.
// =============================================================================

// static
CefRefPtr<CefBrowserView> CefBrowserView::CreateBrowserView(
    CefRefPtr<CefClient> client,
    const CefString& url,
    const CefBrowserSettings& settings,
    CefRefPtr<CefDictionaryValue> extra_info,
    CefRefPtr<CefRequestContext> request_context,
    CefRefPtr<CefBrowserViewDelegate> delegate) {
  return nullptr;
}

// static
CefRefPtr<CefBrowserView> CefBrowserView::GetForBrowser(
    CefRefPtr<CefBrowser> browser) {
  return nullptr;
}

// static
CefRefPtr<CefDisplay> CefDisplay::GetPrimaryDisplay() {
  return nullptr;
}

// static
CefRefPtr<CefDisplay> CefDisplay::GetDisplayNearestPoint(
    const CefPoint& point,
    bool input_pixel_coords) {
  return nullptr;
}

// static
CefRefPtr<CefDisplay> CefDisplay::GetDisplayMatchingBounds(
    const CefRect& bounds,
    bool input_pixel_coords) {
  return nullptr;
}

// static
size_t CefDisplay::GetDisplayCount() {
  return 0;
}

// static
void CefDisplay::GetAllDisplays(
    std::vector<CefRefPtr<CefDisplay>>& displays) {
  displays.clear();
}

// static
CefPoint CefDisplay::ConvertScreenPointToPixels(const CefPoint& point) {
  return point;
}

// static
CefPoint CefDisplay::ConvertScreenPointFromPixels(const CefPoint& point) {
  return point;
}

// static
CefRect CefDisplay::ConvertScreenRectToPixels(const CefRect& rect) {
  return rect;
}

// static
CefRect CefDisplay::ConvertScreenRectFromPixels(const CefRect& rect) {
  return rect;
}

// static
CefRefPtr<CefLabelButton> CefLabelButton::CreateLabelButton(
    CefRefPtr<CefButtonDelegate> delegate,
    const CefString& text) {
  return nullptr;
}

// static
CefRefPtr<CefMenuButton> CefMenuButton::CreateMenuButton(
    CefRefPtr<CefMenuButtonDelegate> delegate,
    const CefString& text) {
  return nullptr;
}

// static
CefRefPtr<CefPanel> CefPanel::CreatePanel(
    CefRefPtr<CefPanelDelegate> delegate) {
  return nullptr;
}

// static
CefRefPtr<CefScrollView> CefScrollView::CreateScrollView(
    CefRefPtr<CefViewDelegate> delegate) {
  return nullptr;
}

// static
CefRefPtr<CefTextfield> CefTextfield::CreateTextfield(
    CefRefPtr<CefTextfieldDelegate> delegate) {
  return nullptr;
}

// static
CefRefPtr<CefWindow> CefWindow::CreateTopLevelWindow(
    CefRefPtr<CefWindowDelegate> delegate) {
  return nullptr;
}

// =============================================================================
// CefMediaRouter — media routing is not available on Android.
// =============================================================================

#include "cef/include/cef_media_router.h"

// static
CefRefPtr<CefMediaRouter> CefMediaRouter::GetGlobalMediaRouter(
    CefRefPtr<CefCompletionCallback> callback) {
  return nullptr;
}

// =============================================================================
// CefBrowserCreateParams — methods normally in browser_host_create.cc
// =============================================================================

void CefBrowserCreateParams::InitWindowInfo(CefWindowInfo* window_info,
                                            CefBrowserHostBase* opener) {
  if (opener->IsAlloyStyle()) {
    window_info->runtime_style = CEF_RUNTIME_STYLE_ALLOY;
  }
}

void CefBrowserCreateParams::MaybeSetWindowInfo(
    const CefWindowInfo& new_window_info,
    bool allow_alloy_style,
    bool allow_chrome_style) {
  if (allow_alloy_style) {
    window_info = std::make_unique<CefWindowInfo>(new_window_info);
  }
}

bool CefBrowserCreateParams::IsChromeStyle() const {
  return false;
}

bool CefBrowserCreateParams::IsWindowless() const {
  return window_info && window_info->windowless_rendering_enabled && client &&
         client->GetRenderHandler().get();
}

#endif  // BUILDFLAG(IS_ANDROID)
