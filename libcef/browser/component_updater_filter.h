// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_COMPONENT_UPDATER_FILTER_H_
#define CEF_LIBCEF_BROWSER_COMPONENT_UPDATER_FILTER_H_

#include <string_view>

namespace cef_component_updater {

// Returns true when the component with |app_id| should be registered with the
// component updater, and false when this embedding has no use for it.
//
// WHY THIS EXISTS
//
// In chrome-runtime mode CEF registers no components of its own: it inherits
// Chrome's entire list from chrome/browser/component_updater/registration.cc,
// which calls thirty-five Register*Component() functions. On Android roughly
// twenty of them end up active, each one talking to update.googleapis.com and
// downloading a payload. A good part of that list is Chrome product
// functionality an embedder does not expose at all, so the traffic and the disk
// buy nothing.
//
// WHY AN ALLOWLIST AND NOT A BLOCKLIST
//
// A list of subtractions would silently re-admit anything upstream adds in the
// next Chromium bump, which is the failure mode we would never notice. Naming
// what we keep means a new upstream component is excluded by default and shows
// up as a deliberate decision rather than as drift.
//
// WHY FILTERING HERE AND NOT IN registration.cc
//
// registration.cc churns upstream every release, so patching it means
// re-resolving a conflict every bump. ComponentUpdateService::RegisterComponent
// is the single choke point every registration passes through, and its
// signature is stable, so the Chromium-side patch stays one early return.
//
// WHAT MUST NOT BE REMOVED
//
// The kept set is not a matter of taste. These are the engine's security data
// feeds: certificate revocation, Certificate Transparency metadata, the
// dangerous-download file-type policy, TLS error diagnosis, Trust Token key
// commitments, First-Party Sets, origin trials and the subresource filtering
// ruleset. Dropping any of them freezes that data at build time, which
// contradicts the whole reason the engine tracks upstream releases. If in
// doubt, keep it.
bool ShouldRegisterComponent(std::string_view app_id);

}  // namespace cef_component_updater

#endif  // CEF_LIBCEF_BROWSER_COMPONENT_UPDATER_FILTER_H_
