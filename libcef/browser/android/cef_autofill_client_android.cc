// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/android/cef_autofill_client_android.h"

#include <utility>

#include "base/notimplemented.h"
#include "base/notreached.h"
#include "components/android_autofill/browser/android_autofill_manager.h"
#include "components/autofill/content/browser/content_autofill_client.h"
#include "components/autofill/content/browser/content_autofill_driver.h"
#include "components/autofill/core/browser/crowdsourcing/autofill_crowdsourcing_manager.h"
#include "components/autofill/core/browser/suggestions/suggestion_type.h"
#include "components/autofill/core/common/aliases.h"
#include "components/prefs/pref_service.h"
#include "components/security_state/content/security_state_tab_helper.h"
#include "components/user_prefs/user_prefs.h"
#include "content/public/browser/browser_context.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/navigation_entry.h"
#include "content/public/browser/ssl_status.h"
#include "content/public/browser/storage_partition.h"
#include "content/public/browser/web_contents.h"

namespace cef {

void CefAutofillClientAndroid::CreateForWebContents(
    content::WebContents* contents) {
  DCHECK(contents);
  // Replace any existing ContentAutofillClient (e.g., ChromeAutofillClient)
  // with ours. ChromeAutofillClient creates BrowserAutofillManager which
  // doesn't forward form events to Android's AutofillProvider.
  // CefAutofillClientAndroid creates AndroidAutofillManager instead.
  contents->SetUserData(
      autofill::ContentAutofillClient::UserDataKey(),
      base::WrapUnique(new CefAutofillClientAndroid(contents)));
}

CefAutofillClientAndroid::CefAutofillClientAndroid(
    content::WebContents* web_contents)
    : autofill::ContentAutofillClient(web_contents) {
}

CefAutofillClientAndroid::~CefAutofillClientAndroid() {
  HideAutofillSuggestions(autofill::SuggestionHidingReason::kTabGone);
}

base::WeakPtr<autofill::AutofillClient> CefAutofillClientAndroid::GetWeakPtr() {
  return weak_ptr_factory_.GetWeakPtr();
}

const std::string& CefAutofillClientAndroid::GetAppLocale() const {
  NOTREACHED();
}

bool CefAutofillClientAndroid::IsOffTheRecord() const {
  auto* mutable_this = const_cast<CefAutofillClientAndroid*>(this);
  return mutable_this->GetWebContents().GetBrowserContext()->IsOffTheRecord();
}

scoped_refptr<network::SharedURLLoaderFactory>
CefAutofillClientAndroid::GetURLLoaderFactory() {
  return GetWebContents()
      .GetBrowserContext()
      ->GetDefaultStoragePartition()
      ->GetURLLoaderFactoryForBrowserProcess();
}

autofill::AutofillCrowdsourcingManager&
CefAutofillClientAndroid::GetCrowdsourcingManager() {
  if (!crowdsourcing_manager_) {
    crowdsourcing_manager_ =
        std::make_unique<autofill::AutofillCrowdsourcingManager>(
            this, GetChannel());
  }
  return *crowdsourcing_manager_;
}

autofill::VotesUploader& CefAutofillClientAndroid::GetVotesUploader() {
  return votes_uploader_;
}

bool CefAutofillClientAndroid::HasPersonalDataManager() const {
  return false;
}

autofill::PersonalDataManager& CefAutofillClientAndroid::GetPersonalDataManager() {
  NOTREACHED();
}

autofill::ValuablesDataManager*
CefAutofillClientAndroid::GetValuablesDataManager() {
  return nullptr;
}

autofill::EntityDataManager* CefAutofillClientAndroid::GetEntityDataManager() {
  return nullptr;
}

autofill::SingleFieldFillRouter&
CefAutofillClientAndroid::GetSingleFieldFillRouter() {
  NOTREACHED();
}

autofill::AutocompleteHistoryManager*
CefAutofillClientAndroid::GetAutocompleteHistoryManager() {
  NOTREACHED();
}

PrefService* CefAutofillClientAndroid::GetPrefs() {
  return const_cast<PrefService*>(std::as_const(*this).GetPrefs());
}

const PrefService* CefAutofillClientAndroid::GetPrefs() const {
  return user_prefs::UserPrefs::Get(GetWebContents().GetBrowserContext());
}

syncer::SyncService* CefAutofillClientAndroid::GetSyncService() {
  return nullptr;
}

signin::IdentityManager* CefAutofillClientAndroid::GetIdentityManager() {
  return nullptr;
}

const signin::IdentityManager* CefAutofillClientAndroid::GetIdentityManager()
    const {
  return nullptr;
}

autofill::FormDataImporter* CefAutofillClientAndroid::GetFormDataImporter() {
  return nullptr;
}

strike_database::StrikeDatabase* CefAutofillClientAndroid::GetStrikeDatabase() {
  return nullptr;
}

ukm::UkmRecorder* CefAutofillClientAndroid::GetUkmRecorder() {
  return nullptr;
}

autofill::AddressNormalizer* CefAutofillClientAndroid::GetAddressNormalizer() {
  return nullptr;
}

const GURL& CefAutofillClientAndroid::GetLastCommittedPrimaryMainFrameURL()
    const {
  return GetWebContents().GetPrimaryMainFrame()->GetLastCommittedURL();
}

url::Origin CefAutofillClientAndroid::GetLastCommittedPrimaryMainFrameOrigin()
    const {
  return GetWebContents().GetPrimaryMainFrame()->GetLastCommittedOrigin();
}

security_state::SecurityLevel
CefAutofillClientAndroid::GetSecurityLevelForUmaHistograms() {
  if (SecurityStateTabHelper* helper =
          ::SecurityStateTabHelper::FromWebContents(&GetWebContents())) {
    return helper->GetSecurityLevel();
  }
  return security_state::SecurityLevel::SECURITY_LEVEL_COUNT;
}

const translate::LanguageState* CefAutofillClientAndroid::GetLanguageState() {
  return nullptr;
}

translate::TranslateDriver* CefAutofillClientAndroid::GetTranslateDriver() {
  return nullptr;
}

void CefAutofillClientAndroid::ShowAutofillSettings(
    autofill::SuggestionType suggestion_type) {
  NOTIMPLEMENTED();
}

void CefAutofillClientAndroid::ConfirmSaveAddressProfile(
    const autofill::AutofillProfile& profile,
    const autofill::AutofillProfile* original_profile,
    SaveAddressBubbleType save_address_bubble_type,
    AddressProfileSavePromptCallback callback) {
  NOTIMPLEMENTED();
}

autofill::AutofillClient::SuggestionUiSessionId
CefAutofillClientAndroid::ShowAutofillSuggestions(
    const autofill::AutofillClient::PopupOpenArgs& open_args,
    base::WeakPtr<autofill::AutofillSuggestionDelegate> delegate) {
  NOTIMPLEMENTED();
  return SuggestionUiSessionId();
}

void CefAutofillClientAndroid::UpdateAutofillDataListValues(
    base::span<const autofill::SelectOption> datalist) {}

void CefAutofillClientAndroid::HideAutofillSuggestions(
    autofill::SuggestionHidingReason reason) {}

bool CefAutofillClientAndroid::IsAutofillEnabled() const {
  NOTREACHED();
}

bool CefAutofillClientAndroid::IsAutofillProfileEnabled() const {
  NOTREACHED();
}

bool CefAutofillClientAndroid::IsWalletPublicPassStorageEnabled() const {
  return false;
}

bool CefAutofillClientAndroid::IsAutocompleteEnabled() const {
  return false;
}

bool CefAutofillClientAndroid::IsPasswordManagerEnabled() const {
  NOTREACHED();
}

bool CefAutofillClientAndroid::IsContextSecure() const {
  if (SecurityStateTabHelper* helper =
          SecurityStateTabHelper::FromWebContents(&GetWebContents())) {
    return security_state::IsSslCertificateValid(helper->GetSecurityLevel());
  }

  content::NavigationEntry* navigation_entry =
      GetWebContents().GetController().GetLastCommittedEntry();
  if (!navigation_entry ||
      !navigation_entry->GetURL().SchemeIsCryptographic()) {
    return false;
  }

  content::SSLStatus ssl_status = navigation_entry->GetSSL();
  return ssl_status.certificate &&
         !net::IsCertStatusError(ssl_status.cert_status) &&
         !(ssl_status.content_status &
           content::SSLStatus::RAN_INSECURE_CONTENT);
}

autofill::autofill_metrics::FormInteractionsUkmLogger&
CefAutofillClientAndroid::GetFormInteractionsUkmLogger() {
  return form_interactions_ukm_logger_;
}

content::WebContents& CefAutofillClientAndroid::GetWebContents() const {
  return const_cast<content::WebContents&>(
      ContentAutofillClient::GetWebContents());
}

std::unique_ptr<autofill::AutofillManager>
CefAutofillClientAndroid::CreateManager(
    base::PassKey<autofill::ContentAutofillDriver> pass_key,
    autofill::ContentAutofillDriver& driver) {
  return base::WrapUnique(new autofill::AndroidAutofillManager(&driver));
}

credential_management::ContentCredentialManager*
CefAutofillClientAndroid::GetContentCredentialManager() {
  return nullptr;
}

}  // namespace cef
