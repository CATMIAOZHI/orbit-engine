// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_BRIDGE_CONSTANTS_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_BRIDGE_CONSTANTS_H_

// FEATURE-023 JS bridge: the JavaScript function names added to window. These
// are part of the public contract and MUST match on both the renderer side
// (cefrium_app.cc) and the browser side (cefrium_sdk_jni.cc).
namespace cefrium_bridge {

inline constexpr char kQueryFunction[] = "cefriumQuery";
inline constexpr char kCancelFunction[] = "cefriumQueryCancel";

// FEATURE-062 (issue #9 follow-up): renderer -> browser message carrying the
// focused editable node's value + placeholder + type, for OSR consumers that
// mirror the field in a native overlay. Args: [0]=bool editable, [1]=string
// value, [2]=string placeholder, [3]=string type.
inline constexpr char kFocusNodeMessage[] = "cefrium.focusNode";

}  // namespace cefrium_bridge

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_BRIDGE_CONSTANTS_H_
