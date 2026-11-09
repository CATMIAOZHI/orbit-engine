// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

#include "cef/libcef/browser/android/cefrium_sdk_bridge.h"

#include "cef/libcef/browser/android/cefrium_sdk_client.h"

// Resolves a bridge query on the browser UI thread. The lock + live-registry
// check make this safe even if the browser was closed (data deleted) after the
// async response was posted (FEATURE-023 step 4b).
static void ResolveBridgeQueryOnUI(jlong native_ptr,
                                   int64_t query_id,
                                   bool success,
                                   CefString response,
                                   int error_code,
                                   CefString error_message) {
  base::AutoLock lock(cefrium_sdk::BridgeDataLock());
  if (!cefrium_sdk::LiveBridgeData().count(native_ptr)) {
    return;  // Browser was closed; drop.
  }
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data->client && data->client->bridge_handler()) {
    data->client->bridge_handler()->ResolveQuery(query_id, success, response,
                                                 error_code, error_message);
  }
}

// FEATURE-023: Java resolves a window.cefriumQuery (success or failure). May be
// called on any thread (sync or async); resolution is marshalled to the UI
// thread for CEF-compliance and lifetime safety.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeQueryResult(
    JNIEnv* env, jclass clazz, jlong native_ptr, jlong query_id,
    jboolean success, jstring response_jstr, jint error_code,
    jstring error_msg_jstr) {
  CefString response;
  if (response_jstr) {
    const char* s = env->GetStringUTFChars(response_jstr, nullptr);
    response = s;
    env->ReleaseStringUTFChars(response_jstr, s);
  }
  CefString error_msg;
  if (error_msg_jstr) {
    const char* s = env->GetStringUTFChars(error_msg_jstr, nullptr);
    error_msg = s;
    env->ReleaseStringUTFChars(error_msg_jstr, s);
  }
  CefPostTask(TID_UI,
              base::BindOnce(&ResolveBridgeQueryOnUI, native_ptr, query_id,
                             static_cast<bool>(success), response, error_code,
                             error_msg));
}
