// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_BRIDGE_CONSTANTS_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_BRIDGE_CONSTANTS_H_

// FEATURE-023 JS bridge: the JavaScript function names added to window. These
// are part of the public contract and MUST match on both the renderer side
// (cefrium_app.cc) and the browser side (cefrium_sdk_jni.cc).
namespace cefrium_bridge {

inline constexpr char kQueryFunction[] = "cefriumQuery";
inline constexpr char kCancelFunction[] = "cefriumQueryCancel";

}  // namespace cefrium_bridge

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_BRIDGE_CONSTANTS_H_
