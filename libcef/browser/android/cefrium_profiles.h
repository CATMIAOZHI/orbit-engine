// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_PROFILES_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_PROFILES_H_

#include <string>

#include "cef/include/cef_request_context.h"

namespace cefrium {

// The context of a CefriumProfile: the incognito one, the named one, or the
// global one for an empty name. Null for incognito before any private browser
// exists. UI thread only.
CefRefPtr<CefRequestContext> ContextForProfile(bool incognito,
                                               const std::string& name);

}  // namespace cefrium

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_PROFILES_H_
