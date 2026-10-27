// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Stub implementations for CEF symbols that reference desktop-only features
// (Views UI) not yet available on Android.
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
#include "cef/libcef/browser/browser_host_base.h"

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
// Stubs for desktop-only symbols referenced by browser_host_base.cc
// =============================================================================

#include "cef/libcef/browser/devtools/devtools_window_runner.h"

CefDevToolsWindowRunner* CefBrowserHostBase::GetDevToolsWindowRunner() {
  return nullptr;
}

void CefDevToolsWindowRunner::ShowDevTools(
    CefBrowserHostBase*,
    std::unique_ptr<CefShowDevToolsParams>) {}

namespace chrome {
bool CanResetZoom(content::WebContents*) { return false; }
bool CanZoomIn(content::WebContents*) { return false; }
bool CanZoomOut(content::WebContents*) { return false; }
}  // namespace chrome

#include "cef/libcef/browser/native/cursor_util.h"
#include "cef/libcef/browser/views/browser_view_impl.h"

// static
CefRefPtr<CefBrowserViewImpl> CefBrowserViewImpl::CreateForPopup(
    const CefBrowserSettings&,
    CefRefPtr<CefBrowserViewDelegate>,
    bool, cef_runtime_style_t) {
  return nullptr;
}

std::unique_ptr<cursor_util::ScopedCursorHandle>
cursor_util::ScopedCursorHandle::Create(CefRefPtr<CefBrowser>,
                                        const ui::Cursor&) {
  return nullptr;
}

namespace print_util {
void Print(content::WebContents*, bool) {}
void PrintToPDF(content::WebContents*, const CefString&,
                const CefPdfPrintSettings&,
                CefRefPtr<CefPdfPrintCallback>) {}
}  // namespace print_util

#endif  // BUILDFLAG(IS_ANDROID)
