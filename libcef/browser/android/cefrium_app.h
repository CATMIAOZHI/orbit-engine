// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_APP_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_APP_H_

#include "cef/include/cef_app.h"
#include "cef/include/cef_render_process_handler.h"
#include "cef/include/wrapper/cef_message_router.h"

// FEATURE-023: renderer-side of the JavaScript<->native bridge.
//
// Hosts a CefMessageRouterRendererSide that exposes window.cefriumQuery /
// window.cefriumQueryCancel to page JavaScript. Registered in child (renderer)
// processes via cef_jni_onload.cc (ChromeMainDelegateCef's `application` arg);
// CefAppManager then drives this CefRenderProcessHandler in the renderer.
//
// The browser-side router (CefMessageRouterBrowserSide) lives on the SDK's
// CefClient, NOT here — and no CefApp is registered in the browser process
// (see FEATURE-023 / ROADMAP known issues).
class CefriumApp : public CefApp, public CefRenderProcessHandler {
 public:
  CefriumApp();

  CefriumApp(const CefriumApp&) = delete;
  CefriumApp& operator=(const CefriumApp&) = delete;

  // CefApp:
  CefRefPtr<CefRenderProcessHandler> GetRenderProcessHandler() override;

  // CefRenderProcessHandler:
  void OnWebKitInitialized() override;
  void OnContextCreated(CefRefPtr<CefBrowser> browser,
                        CefRefPtr<CefFrame> frame,
                        CefRefPtr<CefV8Context> context) override;
  void OnContextReleased(CefRefPtr<CefBrowser> browser,
                         CefRefPtr<CefFrame> frame,
                         CefRefPtr<CefV8Context> context) override;
  bool OnProcessMessageReceived(CefRefPtr<CefBrowser> browser,
                                CefRefPtr<CefFrame> frame,
                                CefProcessId source_process,
                                CefRefPtr<CefProcessMessage> message) override;

 private:
  CefRefPtr<CefMessageRouterRendererSide> message_router_;

  IMPLEMENT_REFCOUNTING(CefriumApp);
};

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_APP_H_
