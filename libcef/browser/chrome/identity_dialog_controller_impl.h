// Copyright 2026 The Chromium Embedded Framework Authors. Portions copyright
// 2026 The Chromium Authors. All rights reserved. Use of this source code is
// governed by a BSD-style license that can be found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_CHROME_IDENTITY_DIALOG_CONTROLLER_IMPL_H_
#define CEF_LIBCEF_BROWSER_CHROME_IDENTITY_DIALOG_CONTROLLER_IMPL_H_
#pragma once

#include <memory>
#include <string>
#include <vector>

#include "base/memory/raw_ptr.h"
#include "base/memory/weak_ptr.h"
#include "cef/include/cef_identity_request_handler.h"
#include "content/public/browser/webid/identity_request_dialog_controller.h"

namespace content {
class WebContents;
}

// FedCM dialogs for a CEF browser. The account chooser goes to the client's
// CefIdentityRequestHandler; when there is none, or it declines, every call
// goes to |chrome_| (Chrome's own UI).
class CefIdentityDialogController
    : public content::IdentityRequestDialogController {
 public:
  CefIdentityDialogController(
      content::WebContents* web_contents,
      std::unique_ptr<content::IdentityRequestDialogController> chrome);

  CefIdentityDialogController(const CefIdentityDialogController&) = delete;
  CefIdentityDialogController& operator=(const CefIdentityDialogController&) =
      delete;

  ~CefIdentityDialogController() override;

  // content::IdentityRequestDialogController methods:
  int GetBrandIconIdealSize(blink::mojom::RpMode rp_mode) override;
  int GetBrandIconMinimumSize(blink::mojom::RpMode rp_mode) override;
  void SetIsInterceptionEnabled(bool enabled) override;
  void GetPassiveDialogVolume(GetPassiveDialogVolumeCallback cb) override;
  bool ShowAccountsDialog(
      content::RelyingPartyData rp_data,
      const std::vector<scoped_refptr<content::IdentityProviderData>>&
          idp_list,
      const std::vector<scoped_refptr<content::IdentityRequestAccount>>&
          accounts,
      const std::vector<scoped_refptr<content::IdentityRequestAccount>>&
          filtered_accounts,
      blink::mojom::RpMode rp_mode,
      AccountSelectionCallback on_selected,
      LoginToIdPCallback on_add_account,
      DismissCallback dismiss_callback,
      AccountsDisplayedCallback accounts_displayed_callback) override;
  bool ShowFailureDialog(
      const content::RelyingPartyData& rp_data,
      const std::string& idp_for_display,
      blink::mojom::RpContext rp_context,
      blink::mojom::RpMode rp_mode,
      const content::IdentityProviderMetadata& idp_metadata,
      const std::vector<scoped_refptr<content::IdentityRequestAccount>>&
          filtered_accounts,
      DismissCallback dismiss_callback,
      LoginToIdPCallback login_callback) override;
  bool ShowErrorDialog(
      const content::RelyingPartyData& rp_data,
      const std::string& idp_for_display,
      blink::mojom::RpContext rp_context,
      blink::mojom::RpMode rp_mode,
      const content::IdentityProviderMetadata& idp_metadata,
      const std::optional<content::IdentityCredentialTokenError>& error,
      DismissCallback dismiss_callback,
      MoreDetailsCallback more_details_callback) override;
  bool ShowLoadingDialog(const content::RelyingPartyData& rp_data,
                         const std::string& idp_for_display,
                         blink::mojom::RpContext rp_context,
                         blink::mojom::RpMode rp_mode,
                         DismissCallback dismiss_callback) override;
  bool ShowVerifyingDialog(
      const content::RelyingPartyData& rp_data,
      const scoped_refptr<content::IdentityProviderData>& idp_data,
      const scoped_refptr<content::IdentityRequestAccount>& account,
      content::IdentityRequestAccount::SignInMode sign_in_mode,
      blink::mojom::RpMode rp_mode,
      AccountsDisplayedCallback accounts_displayed_callback) override;
  bool ShowNativeAppUi(
      const content::NativeAppRequestOptions& request_options,
      DismissCallback dismiss_callback,
      NativeAppResultCallback native_result_callback) override;
  std::string GetTitle() const override;
  std::optional<std::string> GetSubtitle() const override;
  void ShowUrl(LinkType type, const GURL& url) override;
  content::WebContents* ShowModalDialog(
      const GURL& url,
      blink::mojom::RpMode rp_mode,
      DismissCallback dismiss_callback,
      ShownModalAsyncCallback on_shown_async,
      NativeAppResultCallback native_result_callback) override;
  void CloseModalDialog() override;
  content::WebContents* GetRpWebContents() override;
  void RequestIdPRegistrationPermision(
      const url::Origin& origin,
      base::OnceCallback<void(bool accepted)> callback) override;
  void NotifyAutofillSourceReadyForTesting() override;

 private:
  CefRefPtr<CefIdentityRequestHandler> GetHandler();
  void OnSelect(const std::string& account_id);
  void OnCancel();
  // Tells the handler that the chooser is gone, if one is open.
  void CloseChooser();

  raw_ptr<content::WebContents> web_contents_;
  std::unique_ptr<content::IdentityRequestDialogController> chrome_;

  // The open chooser.
  CefRefPtr<CefIdentityRequestHandler> handler_;
  CefRefPtr<CefIdentityRequestCallback> callback_;
  std::vector<scoped_refptr<content::IdentityRequestAccount>> accounts_;
  AccountSelectionCallback on_selected_;
  DismissCallback dismiss_callback_;

  base::WeakPtrFactory<CefIdentityDialogController> weak_ptr_factory_{this};
};

#endif  // CEF_LIBCEF_BROWSER_CHROME_IDENTITY_DIALOG_CONTROLLER_IMPL_H_
