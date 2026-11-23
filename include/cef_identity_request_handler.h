// Copyright (c) 2026 Marshall A. Greenblatt. All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//    * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//    * Neither the name of Google Inc. nor the name Chromium Embedded
// Framework nor the names of its contributors may be used to endorse
// or promote products derived from this software without specific prior
// written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//
// ---------------------------------------------------------------------------
//
// The contents of this file must follow a specific format in order to
// support the CEF translator tool. See the translator.README.txt file in the
// tools directory for more information.
//

#ifndef CEF_INCLUDE_CEF_IDENTITY_REQUEST_HANDLER_H_
#define CEF_INCLUDE_CEF_IDENTITY_REQUEST_HANDLER_H_
#pragma once

#include <vector>

#include "include/cef_base.h"
#include "include/cef_browser.h"
#include "include/cef_image.h"

///
/// An account an identity provider offers for a FedCM request
/// (navigator.credentials.get({identity})).
///
/*--cef(source=library,added=experimental)--*/
class CefIdentityAccount : public virtual CefBaseRefCounted {
 public:
  ///
  /// The identifier to pass to CefIdentityRequestCallback::Select.
  ///
  /*--cef()--*/
  virtual CefString GetId() = 0;

  ///
  /// The account's full name.
  ///
  /*--cef()--*/
  virtual CefString GetName() = 0;

  ///
  /// The account's email address or other identifier shown to the user.
  ///
  /*--cef()--*/
  virtual CefString GetEmail() = 0;

  ///
  /// The URL of the account's picture, or empty.
  ///
  /*--cef()--*/
  virtual CefString GetPictureUrl() = 0;

  ///
  /// The identity provider offering the account, for display (for example
  /// "idp.example").
  ///
  /*--cef()--*/
  virtual CefString GetIdentityProvider() = 0;

  ///
  /// True if the user has signed in to the relying party with this account
  /// before.
  ///
  /*--cef()--*/
  virtual bool IsReturningUser() = 0;

  ///
  /// The account's given name, or empty.
  ///
  /*--cef()--*/
  virtual CefString GetGivenName() = 0;

  ///
  /// The account's picture as fetched by the browser, or NULL.
  ///
  /*--cef()--*/
  virtual CefRefPtr<CefImage> GetPicture() = 0;

  ///
  /// The identity provider's brand icon as fetched by the browser, or NULL.
  ///
  /*--cef()--*/
  virtual CefRefPtr<CefImage> GetIdentityProviderIcon() = 0;

  ///
  /// The relying party's privacy policy URL, as the identity provider gives
  /// it, or empty.
  ///
  /*--cef()--*/
  virtual CefString GetPrivacyPolicyUrl() = 0;

  ///
  /// The relying party's terms of service URL, as the identity provider gives
  /// it, or empty.
  ///
  /*--cef()--*/
  virtual CefString GetTermsOfServiceUrl() = 0;

  ///
  /// The fields the identity provider will share with the relying party if
  /// this account is selected ("name", "email", "picture", "phone",
  /// "username"). A chooser should disclose them to a user who is not
  /// returning.
  ///
  /*--cef()--*/
  virtual void GetDisclosureFields(std::vector<CefString>& fields) = 0;

  ///
  /// What the relying party asks for, for the chooser's title: "signin",
  /// "signup", "use" or "continue".
  ///
  /*--cef()--*/
  virtual CefString GetContext() = 0;
};

///
/// Callback for the asynchronous continuation of a FedCM account chooser.
///
/*--cef(source=library,added=experimental)--*/
class CefIdentityRequestCallback : public virtual CefBaseRefCounted {
 public:
  ///
  /// Continue with the account identified by |account_id|. Has no effect if
  /// the chooser is already closed.
  ///
  /*--cef(capi_name=cont)--*/
  virtual void Select(const CefString& account_id) = 0;

  ///
  /// Dismiss the chooser: the request fails. Has no effect if the chooser is
  /// already closed.
  ///
  /*--cef()--*/
  virtual void Cancel() = 0;
};

///
/// Implement this interface to show the account chooser of FedCM requests
/// (navigator.credentials.get({identity}), used by "Sign in with" prompts).
/// The methods of this class will be called on the browser process UI thread.
///
/*--cef(source=client,added=experimental)--*/
class CefIdentityRequestHandler : public virtual CefBaseRefCounted {
 public:
  ///
  /// Called when |relying_party| asks to sign in with one of |accounts|.
  /// Return true to show a chooser and call |callback| when the user acts.
  /// Return false (default) for the default behavior.
  ///
  /*--cef()--*/
  virtual bool OnShowAccountChooser(
      CefRefPtr<CefBrowser> browser,
      const CefString& relying_party,
      const std::vector<CefRefPtr<CefIdentityAccount>>& accounts,
      CefRefPtr<CefIdentityRequestCallback> callback) {
    return false;
  }

  ///
  /// Called when the chooser associated with |callback| is closed by the
  /// framework (for example the page navigated away). The client should
  /// dismiss its chooser UI. After this call |callback| is invalid.
  ///
  /*--cef()--*/
  virtual void OnAccountChooserClosed(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefIdentityRequestCallback> callback) {}
};

#endif  // CEF_INCLUDE_CEF_IDENTITY_REQUEST_HANDLER_H_
