// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/component_updater_filter.h"

#include <array>
#include <string_view>

#include "base/containers/contains.h"

namespace cef_component_updater {

namespace {

// Components this embedding keeps, by component id.
//
// The ids were read off a device rather than copied from a doc: they are the
// keys of `updateclientdata.apps` in the profile's Local State, correlated with
// the installed version directories under the profile cache, so each line below
// is a component that was observed registering and downloading.
//
// Every entry says why it stays. "Security data" means the engine's correctness
// degrades silently without it, which is the bar for inclusion.
constexpr auto kKeep = std::to_array<std::string_view>({
    // --- security data: removing any of these freezes trust data at build time
    "hfnkpimlhhgieaddgfemjhofmfblmnib",  // CRLSet: certificate revocation
    "efniojlnjndmcbiieegkicadnoecjjef",  // PKI metadata: Certificate Transparency
    "oaanfgijljhkdknnacjidbpmmgnghhjj",  // PKI metadata fast push
    "khaoiebndkojlmppeemjhbpbandiljpe",  // File type policies: dangerous downloads
    "giekcmmlnklenlaomppkphknjmnnpneh",  // SSL error assistant: TLS diagnosis
    "kiabhabjdbkjdpjbpigfodbdjmbglcoo",  // Trust token key commitments

    // --- web-platform correctness
    "gonpemdgkjcecdgbnaabipppbmgfggbe",  // First-Party Sets: cookie isolation
    "llkgjffcdpffmhiakmfcdcblohccpfmo",  // Origin trials

    // --- an SDK feature depends on it
    "gcmjkmgdlgnkkcocmoeiminaijmmjnii",  // Subresource filter rules: ad blocking

    // --- anti-abuse, kept deliberately after review
    // Crowd Deny is notification-permission abuse reputation. It is Chrome
    // product plumbing, but what it protects users from is real and an embedder
    // showing web notifications inherits the problem. Keeping it is a judgement
    // call, recorded here so it is not mistaken for an oversight.
    "ggkkehgbnfjpeggfpleeakpidbkibbmn",  // Crowd Deny
});

// Components deliberately NOT registered, kept here as a record so that the
// next person does not have to re-derive the decision. Listing them costs
// nothing and stops a future reader assuming they were simply forgotten.
//
//   cocncanleafgejenidihemfflagifjic  Commerce heuristics      Chrome price tracking
//   hajigopbbjhghbfimgkfmpenfkclmohk  Amount extraction regex  Chrome checkout
//   obedbbhbpmojnkanicioggnmelmoomoc  On-device head suggest   Chrome omnibox
//   lmelglejhemejginpboagddgdfbepgmp  Optimization hints       Chrome-only features
//   laoigpblnllgcgjnjnllmfolckpjlhki  MEI preload              Chrome autoplay policy
//   pficcddpglkpaaihklmahepgjmefdnom  Captcha providers        Chrome product
//   ninodabcejpeglfjbkhdplaoglpcbffj  Actor safety lists       Chrome agentic features
//   jflookgnkcckhobaglndicnbbgbonegd  Safety tips              Chrome UI surface
//   aagaghndoahmfdbmfnajfklaomcanlnh  Real-time URL allowlist  cannot work: no API keys
//   niikhdgajlphfehepabhhblakbdgeefj  Privacy Sandbox attest.  we expose no Privacy Sandbox APIs
//
// Two of those deserve a note. The real-time URL checks allowlist serves Safe
// Browsing lookups that cannot function at all in this build, which ships
// without Google API keys. Privacy Sandbox attestations only matter to an
// embedder exposing those APIs; if that ever changes, move it to kKeep.

}  // namespace

bool ShouldRegisterComponent(std::string_view app_id) {
  return base::Contains(kKeep, app_id);
}

}  // namespace cef_component_updater
