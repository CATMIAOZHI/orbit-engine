// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// FEATURE-086: the FedCM account chooser of a CefriumBrowser
// (CefriumIdentityHandler, or the default dialog).

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_IDENTITY_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_IDENTITY_H_

#include <jni.h>

#include <cstdint>
#include <map>

#include "cef/include/cef_identity_request_handler.h"

// Asks Java (onShowAccountChooser) and keeps each open chooser's callback by
// id until Java answers or CEF closes it. UI thread.
class SdkIdentityHandler : public CefIdentityRequestHandler {
 public:
  SdkIdentityHandler();
  ~SdkIdentityHandler() override;

  void SetJavaBrowser(JNIEnv* env, jobject java_browser);

  // Java's answer for chooser |id|.
  void Select(int64_t id, const CefString& account_id);
  void Cancel(int64_t id);

  // CefIdentityRequestHandler:
  bool OnShowAccountChooser(
      CefRefPtr<CefBrowser> browser,
      const CefString& relying_party,
      const std::vector<CefRefPtr<CefIdentityAccount>>& accounts,
      CefRefPtr<CefIdentityRequestCallback> callback) override;
  void OnAccountChooserClosed(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefIdentityRequestCallback> callback) override;

 private:
  CefRefPtr<CefIdentityRequestCallback> Take(int64_t id);

  jobject java_browser_ = nullptr;
  int64_t next_id_ = 1;
  std::map<int64_t, CefRefPtr<CefIdentityRequestCallback>> pending_;

  IMPLEMENT_REFCOUNTING(SdkIdentityHandler);
};

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_IDENTITY_H_
