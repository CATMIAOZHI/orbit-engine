// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// FEATURE-082: CefriumBrowser.executeDevToolsMethod, DevTools events and
// Cefrium.setRemoteDebuggingEnabled.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_DEVTOOLS_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_DEVTOOLS_H_

#include <jni.h>

#include <string_view>

#include "cef/include/cef_browser.h"
#include "cef/include/cef_devtools_message_observer.h"

struct SdkBrowserData;

// Hands DevTools protocol results and events to Java (onDevToolsResult,
// onDevToolsEvent). Ids below the first app id belong to the SDK's own calls
// and are not reported. UI thread.
class SdkDevToolsObserver : public CefDevToolsMessageObserver {
 public:
  explicit SdkDevToolsObserver(jobject java_browser_global);
  ~SdkDevToolsObserver() override;

  void set_events_enabled(bool enabled) { events_enabled_ = enabled; }

  // CefDevToolsMessageObserver:
  void OnDevToolsMethodResult(CefRefPtr<CefBrowser> browser,
                              int message_id,
                              bool success,
                              const void* result,
                              size_t result_size) override;
  void OnDevToolsEvent(CefRefPtr<CefBrowser> browser,
                       const CefString& method,
                       const void* params,
                       size_t params_size) override;

 private:
  void Call(const char* name,
            const char* signature,
            int id,
            bool success,
            std::string_view json);

  jobject java_browser_;
  bool events_enabled_ = false;
  IMPLEMENT_REFCOUNTING(SdkDevToolsObserver);
};

// Runs the executeDevToolsMethod calls made before the browser existed, at
// its first main-frame commit.
void RunPendingDevTools(SdkBrowserData* data, CefRefPtr<CefBrowser> browser);

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_DEVTOOLS_H_
