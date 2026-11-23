// Copyright 2026 The Chromium Embedded Framework Authors. Portions copyright
// 2026 The Chromium Authors. All rights reserved. Use of this source code is
// governed by a BSD-style license that can be found in the LICENSE file.

#include "cef/libcef/browser/chrome/identity_dialog_controller_impl.h"

#include <utility>

#include "base/functional/bind.h"
#include "base/strings/utf_string_conversions.h"
#include "cef/include/cef_client.h"
#include "cef/libcef/browser/browser_host_base.h"
#include "cef/libcef/browser/image_impl.h"
#include "content/public/browser/browser_thread.h"
#include "third_party/blink/public/mojom/webid/federated_request.mojom.h"
#include "ui/gfx/image/image.h"
#include "ui/gfx/image/image_skia.h"

namespace {

CefRefPtr<CefImage> ToCefImage(const gfx::Image& image) {
  if (image.IsEmpty()) {
    return nullptr;
  }
  return new CefImageImpl(image.AsImageSkia());
}

const char* DisclosureFieldName(content::IdentityRequestDialogDisclosureField f) {
  using Field = content::IdentityRequestDialogDisclosureField;
  switch (f) {
    case Field::kName:
      return "name";
    case Field::kEmail:
      return "email";
    case Field::kUsername:
      return "username";
    case Field::kPhoneNumber:
      return "phone";
    case Field::kPicture:
      return "picture";
  }
  return "";
}

class CefIdentityAccountImpl : public CefIdentityAccount {
 public:
  explicit CefIdentityAccountImpl(
      const content::IdentityRequestAccount& account)
      : id_(account.id),
        name_(account.name.empty() ? account.display_name : account.name),
        email_(account.email.empty() ? account.display_identifier
                                     : account.email),
        given_name_(account.given_name),
        picture_(account.picture.spec()),
        returning_(account.browser_trusted_login_state ==
                   content::IdentityRequestAccount::LoginState::kSignIn),
        decoded_picture_(account.decoded_picture) {
    if (const auto& idp = account.identity_provider) {
      idp_ = idp->idp_for_display;
      idp_icon_ = idp->idp_metadata.brand_decoded_icon;
      privacy_policy_ = idp->client_metadata.privacy_policy_url.spec();
      terms_of_service_ = idp->client_metadata.terms_of_service_url.spec();
      switch (idp->rp_context) {
        case blink::mojom::RpContext::kSignIn:
          context_ = "signin";
          break;
        case blink::mojom::RpContext::kSignUp:
          context_ = "signup";
          break;
        case blink::mojom::RpContext::kUse:
          context_ = "use";
          break;
        case blink::mojom::RpContext::kContinue:
          context_ = "continue";
          break;
      }
    }
    for (auto field : account.fields) {
      fields_.push_back(DisclosureFieldName(field));
    }
  }

  CefString GetId() override { return id_; }
  CefString GetName() override { return name_; }
  CefString GetEmail() override { return email_; }
  CefString GetPictureUrl() override { return picture_; }
  CefString GetIdentityProvider() override { return idp_; }
  bool IsReturningUser() override { return returning_; }
  CefString GetGivenName() override { return given_name_; }
  CefRefPtr<CefImage> GetPicture() override {
    return ToCefImage(decoded_picture_);
  }
  CefRefPtr<CefImage> GetIdentityProviderIcon() override {
    return ToCefImage(idp_icon_);
  }
  CefString GetPrivacyPolicyUrl() override { return privacy_policy_; }
  CefString GetTermsOfServiceUrl() override { return terms_of_service_; }
  void GetDisclosureFields(std::vector<CefString>& fields) override {
    fields.assign(fields_.begin(), fields_.end());
  }
  CefString GetContext() override { return context_; }

 private:
  const std::string id_;
  const std::string name_;
  const std::string email_;
  const std::string given_name_;
  const std::string picture_;
  const bool returning_;
  const gfx::Image decoded_picture_;
  std::string idp_;
  gfx::Image idp_icon_;
  std::string privacy_policy_;
  std::string terms_of_service_;
  std::vector<std::string> fields_;
  std::string context_ = "signin";

  IMPLEMENT_REFCOUNTING(CefIdentityAccountImpl);
};

// Select()/Cancel() may arrive on any thread, after the chooser is gone, or
// from inside OnShowAccountChooser: they always post to the UI thread (the
// answer may end the request, which destroys the controller) and run
// weak-pointer-bound continuations.
class CefIdentityRequestCallbackImpl : public CefIdentityRequestCallback {
 public:
  CefIdentityRequestCallbackImpl(
      base::RepeatingCallback<void(const std::string&)> select,
      base::RepeatingClosure cancel)
      : select_(std::move(select)), cancel_(std::move(cancel)) {}

  void Select(const CefString& account_id) override {
    Post(base::BindOnce(select_, account_id.ToString()));
  }

  void Cancel() override { Post(cancel_); }

 private:
  static void Post(base::OnceClosure task) {
    content::GetUIThreadTaskRunner({})->PostTask(FROM_HERE, std::move(task));
  }

  base::RepeatingCallback<void(const std::string&)> select_;
  base::RepeatingClosure cancel_;

  IMPLEMENT_REFCOUNTING(CefIdentityRequestCallbackImpl);
};

}  // namespace

CefIdentityDialogController::CefIdentityDialogController(
    content::WebContents* web_contents,
    std::unique_ptr<content::IdentityRequestDialogController> chrome)
    : web_contents_(web_contents), chrome_(std::move(chrome)) {}

CefIdentityDialogController::~CefIdentityDialogController() {
  CloseChooser();
}

CefRefPtr<CefIdentityRequestHandler> CefIdentityDialogController::GetHandler() {
  CefRefPtr<CefBrowserHostBase> browser =
      CefBrowserHostBase::GetBrowserForContents(web_contents_);
  if (!browser || !browser->GetClient()) {
    return nullptr;
  }
  return browser->GetClient()->GetIdentityRequestHandler();
}

bool CefIdentityDialogController::ShowAccountsDialog(
    content::RelyingPartyData rp_data,
    const std::vector<scoped_refptr<content::IdentityProviderData>>& idp_list,
    const std::vector<scoped_refptr<content::IdentityRequestAccount>>&
        accounts,
    const std::vector<scoped_refptr<content::IdentityRequestAccount>>&
        filtered_accounts,
    blink::mojom::RpMode rp_mode,
    AccountSelectionCallback on_selected,
    LoginToIdPCallback on_add_account,
    DismissCallback dismiss_callback,
    AccountsDisplayedCallback accounts_displayed_callback) {
  CloseChooser();
  CefRefPtr<CefIdentityRequestHandler> handler = GetHandler();
  if (handler) {
    std::vector<CefRefPtr<CefIdentityAccount>> list;
    for (const auto& account : accounts) {
      list.push_back(new CefIdentityAccountImpl(*account));
    }
    auto weak = weak_ptr_factory_.GetWeakPtr();
    CefRefPtr<CefIdentityRequestCallback> callback =
        new CefIdentityRequestCallbackImpl(
            base::BindRepeating(&CefIdentityDialogController::OnSelect, weak),
            base::BindRepeating(&CefIdentityDialogController::OnCancel, weak));
    CefRefPtr<CefBrowserHostBase> browser =
        CefBrowserHostBase::GetBrowserForContents(web_contents_);
    if (handler->OnShowAccountChooser(browser.get(), rp_data.rp_for_display,
                                      list, callback)) {
      handler_ = handler;
      callback_ = callback;
      accounts_ = accounts;
      on_selected_ = std::move(on_selected);
      dismiss_callback_ = std::move(dismiss_callback);
      std::move(accounts_displayed_callback).Run();
      return true;
    }
  }
  return chrome_->ShowAccountsDialog(
      std::move(rp_data), idp_list, accounts, filtered_accounts, rp_mode,
      std::move(on_selected), std::move(on_add_account),
      std::move(dismiss_callback), std::move(accounts_displayed_callback));
}

void CefIdentityDialogController::OnSelect(const std::string& account_id) {
  if (!on_selected_) {
    return;
  }
  for (const auto& account : accounts_) {
    if (account->id == account_id && account->identity_provider) {
      handler_ = nullptr;
      callback_ = nullptr;
      dismiss_callback_.Reset();
      std::move(on_selected_)
          .Run(account->identity_provider->idp_metadata.config_url,
               account->id,
               account->browser_trusted_login_state ==
                   content::IdentityRequestAccount::LoginState::kSignIn);
      return;
    }
  }
}

void CefIdentityDialogController::OnCancel() {
  if (!dismiss_callback_) {
    return;
  }
  handler_ = nullptr;
  callback_ = nullptr;
  on_selected_.Reset();
  std::move(dismiss_callback_).Run(DismissReason::kCloseButton);
}

void CefIdentityDialogController::CloseChooser() {
  if (!handler_) {
    return;
  }
  CefRefPtr<CefIdentityRequestHandler> handler = std::move(handler_);
  CefRefPtr<CefIdentityRequestCallback> callback = std::move(callback_);
  on_selected_.Reset();
  dismiss_callback_.Reset();
  CefRefPtr<CefBrowserHostBase> browser =
      CefBrowserHostBase::GetBrowserForContents(web_contents_);
  handler->OnAccountChooserClosed(browser.get(), callback);
}

// While a handler shows the chooser there is no Chrome UI to load into or to
// verify in: these succeed without UI.
bool CefIdentityDialogController::ShowLoadingDialog(
    const content::RelyingPartyData& rp_data,
    const std::string& idp_for_display,
    blink::mojom::RpContext rp_context,
    blink::mojom::RpMode rp_mode,
    DismissCallback dismiss_callback) {
  if (GetHandler()) {
    return true;
  }
  return chrome_->ShowLoadingDialog(rp_data, idp_for_display, rp_context,
                                    rp_mode, std::move(dismiss_callback));
}

bool CefIdentityDialogController::ShowVerifyingDialog(
    const content::RelyingPartyData& rp_data,
    const scoped_refptr<content::IdentityProviderData>& idp_data,
    const scoped_refptr<content::IdentityRequestAccount>& account,
    content::IdentityRequestAccount::SignInMode sign_in_mode,
    blink::mojom::RpMode rp_mode,
    AccountsDisplayedCallback accounts_displayed_callback) {
  if (GetHandler()) {
    std::move(accounts_displayed_callback).Run();
    return true;
  }
  return chrome_->ShowVerifyingDialog(rp_data, idp_data, account,
                                      sign_in_mode, rp_mode,
                                      std::move(accounts_displayed_callback));
}

int CefIdentityDialogController::GetBrandIconIdealSize(
    blink::mojom::RpMode rp_mode) {
  return chrome_->GetBrandIconIdealSize(rp_mode);
}

int CefIdentityDialogController::GetBrandIconMinimumSize(
    blink::mojom::RpMode rp_mode) {
  return chrome_->GetBrandIconMinimumSize(rp_mode);
}

void CefIdentityDialogController::SetIsInterceptionEnabled(bool enabled) {
  chrome_->SetIsInterceptionEnabled(enabled);
}

void CefIdentityDialogController::GetPassiveDialogVolume(
    GetPassiveDialogVolumeCallback cb) {
  chrome_->GetPassiveDialogVolume(std::move(cb));
}

bool CefIdentityDialogController::ShowFailureDialog(
    const content::RelyingPartyData& rp_data,
    const std::string& idp_for_display,
    blink::mojom::RpContext rp_context,
    blink::mojom::RpMode rp_mode,
    const content::IdentityProviderMetadata& idp_metadata,
    const std::vector<scoped_refptr<content::IdentityRequestAccount>>&
        filtered_accounts,
    DismissCallback dismiss_callback,
    LoginToIdPCallback login_callback) {
  return chrome_->ShowFailureDialog(rp_data, idp_for_display, rp_context,
                                    rp_mode, idp_metadata, filtered_accounts,
                                    std::move(dismiss_callback),
                                    std::move(login_callback));
}

bool CefIdentityDialogController::ShowErrorDialog(
    const content::RelyingPartyData& rp_data,
    const std::string& idp_for_display,
    blink::mojom::RpContext rp_context,
    blink::mojom::RpMode rp_mode,
    const content::IdentityProviderMetadata& idp_metadata,
    const std::optional<content::IdentityCredentialTokenError>& error,
    DismissCallback dismiss_callback,
    MoreDetailsCallback more_details_callback) {
  return chrome_->ShowErrorDialog(rp_data, idp_for_display, rp_context,
                                  rp_mode, idp_metadata, error,
                                  std::move(dismiss_callback),
                                  std::move(more_details_callback));
}

bool CefIdentityDialogController::ShowNativeAppUi(
    const content::NativeAppRequestOptions& request_options,
    DismissCallback dismiss_callback,
    NativeAppResultCallback native_result_callback) {
  return chrome_->ShowNativeAppUi(request_options, std::move(dismiss_callback),
                                  std::move(native_result_callback));
}

std::string CefIdentityDialogController::GetTitle() const {
  return chrome_->GetTitle();
}

std::optional<std::string> CefIdentityDialogController::GetSubtitle() const {
  return chrome_->GetSubtitle();
}

void CefIdentityDialogController::ShowUrl(LinkType type, const GURL& url) {
  chrome_->ShowUrl(type, url);
}

content::WebContents* CefIdentityDialogController::ShowModalDialog(
    const GURL& url,
    blink::mojom::RpMode rp_mode,
    DismissCallback dismiss_callback,
    ShownModalAsyncCallback on_shown_async,
    NativeAppResultCallback native_result_callback) {
  return chrome_->ShowModalDialog(url, rp_mode, std::move(dismiss_callback),
                                  std::move(on_shown_async),
                                  std::move(native_result_callback));
}

void CefIdentityDialogController::CloseModalDialog() {
  chrome_->CloseModalDialog();
}

content::WebContents* CefIdentityDialogController::GetRpWebContents() {
  return chrome_->GetRpWebContents();
}

void CefIdentityDialogController::RequestIdPRegistrationPermision(
    const url::Origin& origin,
    base::OnceCallback<void(bool accepted)> callback) {
  chrome_->RequestIdPRegistrationPermision(origin, std::move(callback));
}

void CefIdentityDialogController::NotifyAutofillSourceReadyForTesting() {
  chrome_->NotifyAutofillSourceReadyForTesting();
}
