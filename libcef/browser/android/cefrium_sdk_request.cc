// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

#include "cef/libcef/browser/android/cefrium_sdk_request.h"

#include "cef/libcef/browser/android/cefrium_sdk_client.h"

base::Lock& PendingDocsLock() {
  static base::NoDestructor<base::Lock> lock;
  return *lock;
}

std::map<std::string, PendingDataDoc>& PendingDocs() {
  static base::NoDestructor<std::map<std::string, PendingDataDoc>> docs;
  return *docs;
}

void RegisterPendingDataDoc(const std::string& url,
                            std::string html,
                            std::string content_type) {
  base::AutoLock lock(PendingDocsLock());
  PendingDocs()[url] = PendingDataDoc{std::move(html), std::move(content_type)};
}

bool IsProxyError(cef_errorcode_t error) {
  switch (error) {
    case ERR_TUNNEL_CONNECTION_FAILED:
    case ERR_PROXY_AUTH_UNSUPPORTED:
    case ERR_SOCKS_CONNECTION_FAILED:
    case ERR_SOCKS_CONNECTION_HOST_UNREACHABLE:
    case ERR_PROXY_AUTH_REQUESTED:
    case ERR_PROXY_CONNECTION_FAILED:
    case ERR_MANDATORY_PROXY_CONFIGURATION_FAILED:
    case ERR_PROXY_CERTIFICATE_INVALID:
    case ERR_PROXY_UNABLE_TO_CONNECT_TO_DESTINATION:
    case ERR_PAC_SCRIPT_FAILED:
    case ERR_NO_SUPPORTED_PROXIES:
    case ERR_PAC_NOT_IN_DHCP:
    case ERR_PROXY_AUTH_REQUESTED_WITH_NO_CONNECTION:
    case ERR_PROXY_HTTP_1_1_REQUIRED:
    case ERR_PAC_SCRIPT_TERMINATED:
      return true;
    default:
      return false;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeRespondSslError(
    JNIEnv* env, jclass, jlong native_ptr, jlong id, jboolean proceed) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client && data->client->request_handler()) {
    data->client->request_handler()->RespondSslError(
        static_cast<uint64_t>(id), proceed == JNI_TRUE);
  }
}

// FEATURE-063: Java selects a client certificate by index (or -1 for none).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSelectClientCertificate(
    JNIEnv* env, jclass, jlong native_ptr, jlong id, jint index) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client && data->client->request_handler()) {
    data->client->request_handler()->RespondClientCert(
        static_cast<uint64_t>(id), index);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeRespondHttpAuth(
    JNIEnv* env, jclass, jlong native_ptr, jlong id, jstring username,
    jstring password) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->client || !data->client->request_handler()) {
    return;
  }
  if (username == nullptr) {
    data->client->request_handler()->RespondHttpAuth(
        static_cast<uint64_t>(id), nullptr, nullptr);
    return;
  }
  const char* user = env->GetStringUTFChars(username, nullptr);
  const char* pass =
      password ? env->GetStringUTFChars(password, nullptr) : nullptr;
  data->client->request_handler()->RespondHttpAuth(static_cast<uint64_t>(id),
                                                    user, pass);
  env->ReleaseStringUTFChars(username, user);
  if (pass) {
    env->ReleaseStringUTFChars(password, pass);
  }
}

// FEATURE-063 (opt-in): enable the passthrough response-body tap (onResponseData).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetResponseTapEnabled(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean enabled) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client && data->client->request_handler() &&
      data->client->request_handler()->resource_request_handler()) {
    data->client->request_handler()->resource_request_handler()
        ->SetResponseTapEnabled(enabled == JNI_TRUE);
  }
}

// FEATURE-063 (opt-in): enable the per-cookie access filter (onCanSend/SaveCookie).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetCookieFilterEnabled(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean enabled) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client && data->client->request_handler() &&
      data->client->request_handler()->resource_request_handler()) {
    data->client->request_handler()->resource_request_handler()
        ->SetCookieFilterEnabled(enabled == JNI_TRUE);
  }
}

namespace {
SdkResourceRequestHandler* RequestHandlerOf(jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->client || !data->client->request_handler()) {
    return nullptr;
  }
  return data->client->request_handler()->resource_request_handler().get();
}
}  // namespace

// Ad blocking per browser: on by default once a list is loaded.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetAdBlockEnabled(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean enabled) {
  if (auto* handler = RequestHandlerOf(native_ptr)) {
    handler->SetAdBlockEnabled(enabled == JNI_TRUE);
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeGetBlockedCount(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* handler = RequestHandlerOf(native_ptr);
  return handler ? handler->blocked_count() : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetRequestObservers(
    JNIEnv* env, jclass clazz, jlong native_ptr, jint observers) {
  if (auto* handler = RequestHandlerOf(native_ptr)) {
    handler->SetObservers(observers);
  }
}

// Sets an EasyList-format list from a file; `list` names it, so loading the
// same list again replaces its rules. Returns its rule count, -1 if unreadable.
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_Cefrium_nativeLoadAdBlockList(
    JNIEnv* env, jclass clazz, jstring list_j, jstring path_j) {
  std::ifstream file(cefrium_sdk::JStr(env, path_j).ToString());
  if (!file.is_open()) {
    return -1;
  }
  std::string content((std::istreambuf_iterator<char>(file)),
                      std::istreambuf_iterator<char>());
  return AdBlockFilter::Load(cefrium_sdk::JStr(env, list_j).ToString(), content);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_Cefrium_nativeLoadYouTubeAdRules(JNIEnv* env, jclass clazz) {
  return AdBlockFilter::LoadYouTubeAdRules();
}

// CefriumProfile.setAdBlockExceptions: the sites, a host or an origin each.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_CefriumProfile_nativeSetAdBlockExceptions(JNIEnv* env,
                                                         jclass clazz,
                                                         jboolean incognito,
                                                         jstring profile,
                                                         jobjectArray sites_j) {
  std::vector<std::string> sites;
  const jsize n = sites_j ? env->GetArrayLength(sites_j) : 0;
  for (jsize i = 0; i < n; ++i) {
    auto site = static_cast<jstring>(env->GetObjectArrayElement(sites_j, i));
    sites.push_back(cefrium_sdk::JStr(env, site).ToString());
    env->DeleteLocalRef(site);
  }
  return AdBlockExceptions::Set(incognito == JNI_TRUE,
                                cefrium_sdk::JStr(env, profile).ToString(), sites)
             ? JNI_TRUE
             : JNI_FALSE;
}

namespace {

// Fetches a filter list through the engine's own network stack, so it follows
// the default profile's proxy instead of leaving the device directly. The body
// goes back to Cefrium.onAdBlockListFetched(id, status, body, error).
class AdBlockListFetch : public CefURLRequestClient {
 public:
  AdBlockListFetch(jclass cefrium, jlong id) : cefrium_(cefrium), id_(id) {}

  void OnRequestComplete(CefRefPtr<CefURLRequest> request) override {
    JNIEnv* env = base::android::AttachCurrentThread();
    jmethodID done = env->GetStaticMethodID(
        cefrium_, "onAdBlockListFetched", "(JI[BLjava/lang/String;)V");
    if (done) {
      CefRefPtr<CefResponse> response = request->GetResponse();
      const int status = response ? response->GetStatus() : 0;
      const bool ok = request->GetRequestStatus() == UR_SUCCESS;
      jbyteArray body = nullptr;
      if (ok) {
        body = env->NewByteArray(static_cast<jsize>(body_.size()));
        env->SetByteArrayRegion(body, 0, static_cast<jsize>(body_.size()),
                                reinterpret_cast<const jbyte*>(body_.data()));
      }
      jstring error = ok ? nullptr
                         : env->NewStringUTF(
                               ("network error " +
                                std::to_string(request->GetRequestError()))
                                   .c_str());
      env->CallStaticVoidMethod(cefrium_, done, id_, status, body, error);
      if (body) env->DeleteLocalRef(body);
      if (error) env->DeleteLocalRef(error);
    }
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
    }
    env->DeleteGlobalRef(cefrium_);
  }

  void OnDownloadData(CefRefPtr<CefURLRequest> request,
                      const void* data,
                      size_t data_length) override {
    body_.append(static_cast<const char*>(data), data_length);
  }

  void OnUploadProgress(CefRefPtr<CefURLRequest>, int64_t, int64_t) override {}
  void OnDownloadProgress(CefRefPtr<CefURLRequest>, int64_t, int64_t) override {}
  bool GetAuthCredentials(bool,
                          const CefString&,
                          int,
                          const CefString&,
                          const CefString&,
                          CefRefPtr<CefAuthCallback>) override {
    return false;
  }

  IMPLEMENT_REFCOUNTING(AdBlockListFetch);

 private:
  jclass cefrium_;
  const jlong id_;
  std::string body_;
};

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_Cefrium_nativeFetchAdBlockList(
    JNIEnv* env, jclass clazz, jstring url_j, jlong id) {
  auto cefrium = static_cast<jclass>(env->NewGlobalRef(clazz));
  const std::string url = cefrium_sdk::JStr(env, url_j).ToString();
  CefPostTask(TID_UI, base::BindOnce(
                          [](std::string url, jclass cefrium, jlong id) {
                            CefRefPtr<CefRequest> request = CefRequest::Create();
                            request->SetURL(url);
                            request->SetMethod("GET");
                            CefURLRequest::Create(
                                request, new AdBlockListFetch(cefrium, id),
                                nullptr);
                          },
                          url, cefrium, id));
}
