// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

#include "cef/libcef/browser/android/cefrium_sdk_identity.h"

#include <string>
#include <vector>

#include "base/android/jni_android.h"
#include "base/android/jni_array.h"
#include "base/android/jni_string.h"
#include "cef/libcef/browser/android/cefrium_sdk_client.h"

SdkIdentityHandler::SdkIdentityHandler() = default;

SdkIdentityHandler::~SdkIdentityHandler() {
  if (java_browser_) {
    JNIEnv* env = base::android::AttachCurrentThread();
    if (env) {
      env->DeleteGlobalRef(java_browser_);
    }
  }
}

void SdkIdentityHandler::SetJavaBrowser(JNIEnv* env, jobject java_browser) {
  if (java_browser && !java_browser_) {
    java_browser_ = env->NewGlobalRef(java_browser);
  }
}

bool SdkIdentityHandler::OnShowAccountChooser(
    CefRefPtr<CefBrowser> browser,
    const CefString& relying_party,
    const std::vector<CefRefPtr<CefIdentityAccount>>& accounts,
    CefRefPtr<CefIdentityRequestCallback> callback) {
  JNIEnv* env = base::android::AttachCurrentThread();
  if (!java_browser_ || !env || accounts.empty()) {
    return false;
  }
  std::vector<std::string> ids, names, emails, pictures, idps;
  std::vector<bool> returning;
  for (const auto& account : accounts) {
    ids.push_back(account->GetId().ToString());
    names.push_back(account->GetName().ToString());
    emails.push_back(account->GetEmail().ToString());
    pictures.push_back(account->GetPictureUrl().ToString());
    idps.push_back(account->GetIdentityProvider().ToString());
    returning.push_back(account->IsReturningUser());
  }
  const int64_t id = next_id_++;
  pending_[id] = callback;

  jclass cls = env->GetObjectClass(java_browser_);
  jmethodID mid = env->GetMethodID(
      cls, "onShowAccountChooser",
      "(JLjava/lang/String;[Ljava/lang/String;[Ljava/lang/String;"
      "[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Z)Z");
  bool shown = false;
  if (mid) {
    using base::android::ToJavaArrayOfStrings;
    auto jrp = base::android::ConvertUTF8ToJavaString(
        env, relying_party.ToString());
    auto jids = ToJavaArrayOfStrings(env, ids);
    auto jnames = ToJavaArrayOfStrings(env, names);
    auto jemails = ToJavaArrayOfStrings(env, emails);
    auto jpictures = ToJavaArrayOfStrings(env, pictures);
    auto jidps = ToJavaArrayOfStrings(env, idps);
    auto jreturning = base::android::ToJavaBooleanArray(env, returning);
    shown = env->CallBooleanMethod(java_browser_, mid, static_cast<jlong>(id),
                                   jrp.obj(), jids.obj(), jnames.obj(),
                                   jemails.obj(), jpictures.obj(), jidps.obj(),
                                   jreturning.obj());
  } else {
    env->ExceptionClear();
  }
  env->DeleteLocalRef(cls);
  if (!shown) {
    pending_.erase(id);
  }
  return shown;
}

void SdkIdentityHandler::OnAccountChooserClosed(
    CefRefPtr<CefBrowser> browser,
    CefRefPtr<CefIdentityRequestCallback> callback) {
  for (auto it = pending_.begin(); it != pending_.end(); ++it) {
    if (it->second.get() != callback.get()) {
      continue;
    }
    const int64_t id = it->first;
    pending_.erase(it);
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!java_browser_ || !env) {
      return;
    }
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onAccountChooserClosed", "(J)V");
    if (mid) {
      env->CallVoidMethod(java_browser_, mid, static_cast<jlong>(id));
    } else {
      env->ExceptionClear();
    }
    env->DeleteLocalRef(cls);
    return;
  }
}

CefRefPtr<CefIdentityRequestCallback> SdkIdentityHandler::Take(int64_t id) {
  auto it = pending_.find(id);
  if (it == pending_.end()) {
    return nullptr;
  }
  CefRefPtr<CefIdentityRequestCallback> callback = it->second;
  pending_.erase(it);
  return callback;
}

void SdkIdentityHandler::Select(int64_t id, const CefString& account_id) {
  if (auto callback = Take(id)) {
    callback->Select(account_id);
  }
}

void SdkIdentityHandler::Cancel(int64_t id) {
  if (auto callback = Take(id)) {
    callback->Cancel();
  }
}

// FEATURE-086: the app answered FedCM account chooser |id|.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeIdentitySelect(
    JNIEnv* env, jclass, jlong native_ptr, jlong id, jstring account_id) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client) {
    data->client->identity_handler()->Select(id, cefrium_sdk::JStr(env, account_id));
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeIdentityCancel(
    JNIEnv* env, jclass, jlong native_ptr, jlong id) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client) {
    data->client->identity_handler()->Cancel(id);
  }
}
