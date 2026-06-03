// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/android/cefrium_app.h"

namespace {

// JavaScript bindings exposed on the page's window object. These names are part
// of the public contract — do not change once published (FEATURE-023).
constexpr char kQueryFunction[] = "cefriumQuery";
constexpr char kCancelFunction[] = "cefriumQueryCancel";

}  // namespace

CefriumApp::CefriumApp() = default;

CefRefPtr<CefRenderProcessHandler> CefriumApp::GetRenderProcessHandler() {
  return this;
}

void CefriumApp::OnWebKitInitialized() {
  CefMessageRouterConfig config;
  config.js_query_function = kQueryFunction;
  config.js_cancel_function = kCancelFunction;
  message_router_ = CefMessageRouterRendererSide::Create(config);
}

void CefriumApp::OnContextCreated(CefRefPtr<CefBrowser> browser,
                                  CefRefPtr<CefFrame> frame,
                                  CefRefPtr<CefV8Context> context) {
  if (message_router_) {
    message_router_->OnContextCreated(browser, frame, context);
  }
}

void CefriumApp::OnContextReleased(CefRefPtr<CefBrowser> browser,
                                   CefRefPtr<CefFrame> frame,
                                   CefRefPtr<CefV8Context> context) {
  if (message_router_) {
    message_router_->OnContextReleased(browser, frame, context);
  }
}

bool CefriumApp::OnProcessMessageReceived(
    CefRefPtr<CefBrowser> browser,
    CefRefPtr<CefFrame> frame,
    CefProcessId source_process,
    CefRefPtr<CefProcessMessage> message) {
  if (message_router_) {
    return message_router_->OnProcessMessageReceived(browser, frame,
                                                     source_process, message);
  }
  return false;
}
