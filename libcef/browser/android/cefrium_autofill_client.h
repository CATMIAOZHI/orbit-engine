// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_AUTOFILL_CLIENT_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_AUTOFILL_CLIENT_H_

#include <memory>
#include <string>

#include "base/memory/weak_ptr.h"
#include "components/autofill/content/browser/content_autofill_client.h"
#include "components/autofill/core/browser/crowdsourcing/autofill_crowdsourcing_manager.h"
#include "components/autofill/core/browser/crowdsourcing/votes_uploader.h"
#include "components/autofill/core/browser/logging/log_manager.h"
#include "components/autofill/core/browser/metrics/autofill_metrics_utils.h"
#include "components/autofill/core/browser/metrics/form_interactions_ukm_logger.h"
#include "components/autofill/core/browser/payments/legal_message_line.h"
#include "components/autofill/core/browser/ui/autofill_suggestion_delegate.h"
#include "components/autofill/core/common/autofill_features.h"

namespace cefrium {

// Minimal ContentAutofillClient for CEF Android.
// Creates AndroidAutofillManager per frame so form events are forwarded
// to Android's AutofillProvider/AutofillManager framework.
// Modeled after components/android_autofill/browser/android_autofill_client.cc
// but without ThirdPartyCredentialManagerImpl and Chrome-specific deps.
class CefriumAutofillClient : public autofill::ContentAutofillClient {
 public:
  static void CreateForWebContents(content::WebContents* contents);
  ~CefriumAutofillClient() override;

  // AutofillClient:
  base::WeakPtr<autofill::AutofillClient> GetWeakPtr() override;
  const std::string& GetAppLocale() const override;
  bool IsOffTheRecord() const override;
  scoped_refptr<network::SharedURLLoaderFactory> GetURLLoaderFactory() override;
  autofill::AutofillCrowdsourcingManager& GetCrowdsourcingManager() override;
  autofill::VotesUploader& GetVotesUploader() override;
  bool HasPersonalDataManager() const override;
  autofill::PersonalDataManager& GetPersonalDataManager() override;
  autofill::ValuablesDataManager* GetValuablesDataManager() override;
  autofill::EntityDataManager* GetEntityDataManager() override;
  autofill::SingleFieldFillRouter& GetSingleFieldFillRouter() override;
  autofill::AutocompleteHistoryManager* GetAutocompleteHistoryManager()
      override;
  PrefService* GetPrefs() override;
  const PrefService* GetPrefs() const override;
  syncer::SyncService* GetSyncService() override;
  signin::IdentityManager* GetIdentityManager() override;
  const signin::IdentityManager* GetIdentityManager() const override;
  autofill::FormDataImporter* GetFormDataImporter() override;
  strike_database::StrikeDatabase* GetStrikeDatabase() override;
  ukm::UkmRecorder* GetUkmRecorder() override;
  autofill::AddressNormalizer* GetAddressNormalizer() override;
  const GURL& GetLastCommittedPrimaryMainFrameURL() const override;
  url::Origin GetLastCommittedPrimaryMainFrameOrigin() const override;
  security_state::SecurityLevel GetSecurityLevelForUmaHistograms() override;
  const translate::LanguageState* GetLanguageState() override;
  translate::TranslateDriver* GetTranslateDriver() override;
  void ShowAutofillSettings(
      autofill::SuggestionType suggestion_type) override;
  void ConfirmSaveAddressProfile(
      const autofill::AutofillProfile& profile,
      const autofill::AutofillProfile* original_profile,
      SaveAddressBubbleType save_address_bubble_type,
      AddressProfileSavePromptCallback callback) override;
  autofill::AutofillClient::SuggestionUiSessionId ShowAutofillSuggestions(
      const autofill::AutofillClient::PopupOpenArgs& open_args,
      base::WeakPtr<autofill::AutofillSuggestionDelegate> delegate) override;
  void UpdateAutofillDataListValues(
      base::span<const autofill::SelectOption> datalist) override;
  void HideAutofillSuggestions(
      autofill::SuggestionHidingReason reason) override;
  bool IsAutofillEnabled() const override;
  bool IsAutofillProfileEnabled() const override;
  bool IsWalletPublicPassStorageEnabled() const override;
  bool IsAutocompleteEnabled() const override;
  bool IsPasswordManagerEnabled() const override;
  bool IsContextSecure() const override;
  autofill::autofill_metrics::FormInteractionsUkmLogger&
  GetFormInteractionsUkmLogger() override;

  // ContentAutofillClient:
  std::unique_ptr<autofill::AutofillManager> CreateManager(
      base::PassKey<autofill::ContentAutofillDriver> pass_key,
      autofill::ContentAutofillDriver& driver) override;
  credential_management::ContentCredentialManager*
  GetContentCredentialManager() override;

 private:
  explicit CefriumAutofillClient(content::WebContents* web_contents);
  content::WebContents& GetWebContents() const;

  std::unique_ptr<autofill::AutofillCrowdsourcingManager>
      crowdsourcing_manager_;
  autofill::VotesUploader votes_uploader_{this};
  autofill::autofill_metrics::FormInteractionsUkmLogger
      form_interactions_ukm_logger_{this};

  base::WeakPtrFactory<CefriumAutofillClient> weak_ptr_factory_{this};
};

}  // namespace cefrium

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_AUTOFILL_CLIENT_H_
