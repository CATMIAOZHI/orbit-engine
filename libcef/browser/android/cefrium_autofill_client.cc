// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/android/cefrium_autofill_client.h"

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

namespace cefrium {

void CefriumAutofillClient::CreateForWebContents(
    content::WebContents* contents) {
  DCHECK(contents);
  // Replace any existing ContentAutofillClient (e.g., ChromeAutofillClient)
  // with ours. ChromeAutofillClient creates BrowserAutofillManager which
  // doesn't forward form events to Android's AutofillProvider.
  // CefriumAutofillClient creates AndroidAutofillManager instead.
  contents->SetUserData(
      autofill::ContentAutofillClient::UserDataKey(),
      base::WrapUnique(new CefriumAutofillClient(contents)));
}

CefriumAutofillClient::CefriumAutofillClient(
    content::WebContents* web_contents)
    : autofill::ContentAutofillClient(web_contents) {
}

CefriumAutofillClient::~CefriumAutofillClient() {
  HideAutofillSuggestions(autofill::SuggestionHidingReason::kTabGone);
}

base::WeakPtr<autofill::AutofillClient> CefriumAutofillClient::GetWeakPtr() {
  return weak_ptr_factory_.GetWeakPtr();
}

const std::string& CefriumAutofillClient::GetAppLocale() const {
  NOTREACHED();
}

bool CefriumAutofillClient::IsOffTheRecord() const {
  auto* mutable_this = const_cast<CefriumAutofillClient*>(this);
  return mutable_this->GetWebContents().GetBrowserContext()->IsOffTheRecord();
}

scoped_refptr<network::SharedURLLoaderFactory>
CefriumAutofillClient::GetURLLoaderFactory() {
  return GetWebContents()
      .GetBrowserContext()
      ->GetDefaultStoragePartition()
      ->GetURLLoaderFactoryForBrowserProcess();
}

autofill::AutofillCrowdsourcingManager&
CefriumAutofillClient::GetCrowdsourcingManager() {
  if (!crowdsourcing_manager_) {
    crowdsourcing_manager_ =
        std::make_unique<autofill::AutofillCrowdsourcingManager>(
            this, GetChannel());
  }
  return *crowdsourcing_manager_;
}

autofill::VotesUploader& CefriumAutofillClient::GetVotesUploader() {
  return votes_uploader_;
}

bool CefriumAutofillClient::HasPersonalDataManager() const {
  return false;
}

autofill::PersonalDataManager& CefriumAutofillClient::GetPersonalDataManager() {
  NOTREACHED();
}

autofill::ValuablesDataManager*
CefriumAutofillClient::GetValuablesDataManager() {
  return nullptr;
}

autofill::EntityDataManager* CefriumAutofillClient::GetEntityDataManager() {
  return nullptr;
}

autofill::SingleFieldFillRouter&
CefriumAutofillClient::GetSingleFieldFillRouter() {
  NOTREACHED();
}

autofill::AutocompleteHistoryManager*
CefriumAutofillClient::GetAutocompleteHistoryManager() {
  NOTREACHED();
}

PrefService* CefriumAutofillClient::GetPrefs() {
  return const_cast<PrefService*>(std::as_const(*this).GetPrefs());
}

const PrefService* CefriumAutofillClient::GetPrefs() const {
  return user_prefs::UserPrefs::Get(GetWebContents().GetBrowserContext());
}

syncer::SyncService* CefriumAutofillClient::GetSyncService() {
  return nullptr;
}

signin::IdentityManager* CefriumAutofillClient::GetIdentityManager() {
  return nullptr;
}

const signin::IdentityManager* CefriumAutofillClient::GetIdentityManager()
    const {
  return nullptr;
}

autofill::FormDataImporter* CefriumAutofillClient::GetFormDataImporter() {
  return nullptr;
}

strike_database::StrikeDatabase* CefriumAutofillClient::GetStrikeDatabase() {
  return nullptr;
}

ukm::UkmRecorder* CefriumAutofillClient::GetUkmRecorder() {
  return nullptr;
}

autofill::AddressNormalizer* CefriumAutofillClient::GetAddressNormalizer() {
  return nullptr;
}

const GURL& CefriumAutofillClient::GetLastCommittedPrimaryMainFrameURL()
    const {
  return GetWebContents().GetPrimaryMainFrame()->GetLastCommittedURL();
}

url::Origin CefriumAutofillClient::GetLastCommittedPrimaryMainFrameOrigin()
    const {
  return GetWebContents().GetPrimaryMainFrame()->GetLastCommittedOrigin();
}

security_state::SecurityLevel
CefriumAutofillClient::GetSecurityLevelForUmaHistograms() {
  if (SecurityStateTabHelper* helper =
          ::SecurityStateTabHelper::FromWebContents(&GetWebContents())) {
    return helper->GetSecurityLevel();
  }
  return security_state::SecurityLevel::SECURITY_LEVEL_COUNT;
}

const translate::LanguageState* CefriumAutofillClient::GetLanguageState() {
  return nullptr;
}

translate::TranslateDriver* CefriumAutofillClient::GetTranslateDriver() {
  return nullptr;
}

void CefriumAutofillClient::ShowAutofillSettings(
    autofill::SuggestionType suggestion_type) {
  NOTIMPLEMENTED();
}

void CefriumAutofillClient::ConfirmSaveAddressProfile(
    const autofill::AutofillProfile& profile,
    const autofill::AutofillProfile* original_profile,
    SaveAddressBubbleType save_address_bubble_type,
    AddressProfileSavePromptCallback callback) {
  NOTIMPLEMENTED();
}

autofill::AutofillClient::SuggestionUiSessionId
CefriumAutofillClient::ShowAutofillSuggestions(
    const autofill::AutofillClient::PopupOpenArgs& open_args,
    base::WeakPtr<autofill::AutofillSuggestionDelegate> delegate) {
  NOTIMPLEMENTED();
  return SuggestionUiSessionId();
}

void CefriumAutofillClient::UpdateAutofillDataListValues(
    base::span<const autofill::SelectOption> datalist) {}

void CefriumAutofillClient::HideAutofillSuggestions(
    autofill::SuggestionHidingReason reason) {}

bool CefriumAutofillClient::IsAutofillEnabled() const {
  NOTREACHED();
}

bool CefriumAutofillClient::IsAutofillProfileEnabled() const {
  NOTREACHED();
}

bool CefriumAutofillClient::IsWalletPublicPassStorageEnabled() const {
  return false;
}

bool CefriumAutofillClient::IsAutocompleteEnabled() const {
  return false;
}

bool CefriumAutofillClient::IsPasswordManagerEnabled() const {
  NOTREACHED();
}

bool CefriumAutofillClient::IsContextSecure() const {
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
CefriumAutofillClient::GetFormInteractionsUkmLogger() {
  return form_interactions_ukm_logger_;
}

content::WebContents& CefriumAutofillClient::GetWebContents() const {
  return const_cast<content::WebContents&>(
      ContentAutofillClient::GetWebContents());
}

std::unique_ptr<autofill::AutofillManager>
CefriumAutofillClient::CreateManager(
    base::PassKey<autofill::ContentAutofillDriver> pass_key,
    autofill::ContentAutofillDriver& driver) {
  return base::WrapUnique(new autofill::AndroidAutofillManager(&driver));
}

credential_management::ContentCredentialManager*
CefriumAutofillClient::GetContentCredentialManager() {
  return nullptr;
}

}  // namespace cefrium
