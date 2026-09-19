// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/net_service/android_asset_response_delegate.h"

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "base/strings/string_util.h"
#include "components/embedder_support/android/util/input_stream.h"
#include "net/http/http_response_headers.h"

using base::android::AttachCurrentThread;
using base::android::ConvertUTF8ToJavaString;
using base::android::ConvertJavaStringToUTF8;
using base::android::ScopedJavaLocalRef;

namespace cef {

namespace {

constexpr char kAndroidAssetPath[] = "/android_asset/";
constexpr char kAndroidResourcePath[] = "/android_res/";
constexpr char kHandlerClass[] = "com/cefrium/CefAndroidProtocolHandler";

// Resolves and caches the CefAndroidProtocolHandler class (a global ref). Uses
// base::android::GetClass (classloader-aware) rather than JNIEnv::FindClass:
// FindClass on a worker/IO thread uses the system classloader, which cannot see
// app classes, so it would return null and every asset load would fail.
jclass GetHandlerClass(JNIEnv* env) {
  static jclass g_class = nullptr;
  if (!g_class) {
    ScopedJavaLocalRef<jclass> local =
        base::android::GetClass(env, kHandlerClass);
    if (local.is_null()) {
      return nullptr;
    }
    g_class = static_cast<jclass>(env->NewGlobalRef(local.obj()));
  }
  return g_class;
}

}  // namespace

bool IsAndroidSpecialFileUrl(const GURL& url) {
  if (!url.is_valid() || !url.SchemeIsFile() || !url.has_path()) {
    return false;
  }
  const std::string_view path = url.path();
  return base::StartsWith(path, kAndroidAssetPath,
                          base::CompareCase::SENSITIVE) ||
         base::StartsWith(path, kAndroidResourcePath,
                          base::CompareCase::SENSITIVE);
}

CefAndroidAssetResponseDelegate::CefAndroidAssetResponseDelegate(const GURL& url)
    : url_(url) {}

CefAndroidAssetResponseDelegate::~CefAndroidAssetResponseDelegate() = default;

std::unique_ptr<embedder_support::InputStream>
CefAndroidAssetResponseDelegate::OpenInputStream(JNIEnv* env) {
  jclass clazz = GetHandlerClass(env);
  if (!clazz) {
    return nullptr;
  }
  jmethodID open = env->GetStaticMethodID(
      clazz, "open", "(Ljava/lang/String;)Ljava/io/InputStream;");
  if (!open) {
    env->ExceptionClear();
    return nullptr;
  }
  ScopedJavaLocalRef<jstring> j_url =
      ConvertUTF8ToJavaString(env, url_.possibly_invalid_spec());
  ScopedJavaLocalRef<jobject> stream = ScopedJavaLocalRef<jobject>::Adopt(
      env, env->CallStaticObjectMethod(clazz, open, j_url.obj()));
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return nullptr;
  }
  if (stream.is_null()) {
    return nullptr;
  }
  return std::make_unique<embedder_support::InputStream>(stream);
}

bool CefAndroidAssetResponseDelegate::OnInputStreamOpenFailed() {
  // Not restarted; let the loader complete the request as a failure (404-like).
  return false;
}

bool CefAndroidAssetResponseDelegate::GetMimeType(
    JNIEnv* env,
    const GURL& url,
    embedder_support::InputStream* stream,
    std::string* mime_type) {
  jclass clazz = GetHandlerClass(env);
  if (!clazz || !stream) {
    return false;
  }
  jmethodID get_mime = env->GetStaticMethodID(
      clazz, "getMimeType",
      "(Ljava/io/InputStream;Ljava/lang/String;)Ljava/lang/String;");
  if (!get_mime) {
    env->ExceptionClear();
    return false;
  }
  ScopedJavaLocalRef<jstring> j_url =
      ConvertUTF8ToJavaString(env, url.possibly_invalid_spec());
  ScopedJavaLocalRef<jstring> result = ScopedJavaLocalRef<jstring>::Adopt(
      env, static_cast<jstring>(env->CallStaticObjectMethod(
               clazz, get_mime, stream->jobj().obj(), j_url.obj())));
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return false;
  }
  if (result.is_null()) {
    return false;
  }
  std::string type = ConvertJavaStringToUTF8(env, result.obj());
  if (type.empty()) {
    return false;
  }
  *mime_type = type;
  return true;
}

void CefAndroidAssetResponseDelegate::GetCharset(
    JNIEnv* env,
    const GURL& url,
    embedder_support::InputStream* stream,
    std::string* charset) {}

void CefAndroidAssetResponseDelegate::AppendResponseHeaders(
    JNIEnv* env,
    net::HttpResponseHeaders* headers) {}

}  // namespace cef
