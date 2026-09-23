// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/android/cefrium_app.h"

#include "cef/include/cef_dom.h"
#include "cef/include/cef_process_message.h"
#include "cef/include/cef_values.h"
#include "cef/libcef/browser/android/cefrium_bridge_constants.h"

CefriumApp::CefriumApp() = default;

CefRefPtr<CefRenderProcessHandler> CefriumApp::GetRenderProcessHandler() {
  return this;
}

void CefriumApp::OnWebKitInitialized() {
  CefMessageRouterConfig config;
  config.js_query_function = cefrium_bridge::kQueryFunction;
  config.js_cancel_function = cefrium_bridge::kCancelFunction;
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

// FEATURE-062 follow-up (issue #9): when the focused editable node changes, read
// its current value + placeholder + type from the DOM (renderer-side; the browser
// process cannot see these) and forward them to the browser so OSR consumers can
// mirror the field in a native overlay. A null / non-editable node means blur.
void CefriumApp::OnFocusedNodeChanged(CefRefPtr<CefBrowser> browser,
                                      CefRefPtr<CefFrame> frame,
                                      CefRefPtr<CefDOMNode> node) {
  if (!frame) {
    return;
  }
  bool editable = false;
  std::string value;
  std::string placeholder;
  std::string type;
  if (node && node->IsElement() &&
      (node->IsEditable() || node->IsFormControlElement())) {
    editable = true;
    value = node->GetValue().ToString();
    placeholder = node->GetElementAttribute("placeholder").ToString();
    type = node->GetElementAttribute("type").ToString();
    if (type.empty()) {
      type = node->GetElementTagName().ToString();
    }
  }
  CefRefPtr<CefProcessMessage> msg =
      CefProcessMessage::Create(cefrium_bridge::kFocusNodeMessage);
  CefRefPtr<CefListValue> args = msg->GetArgumentList();
  args->SetBool(0, editable);
  args->SetString(1, value);
  args->SetString(2, placeholder);
  args->SetString(3, type);
  frame->SendProcessMessage(PID_BROWSER, msg);
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
