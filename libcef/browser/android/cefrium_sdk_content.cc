// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// FEATURE-090: app content on an https origin the app owns, answered from
// Java (CefriumProfile.registerContentServer). One scheme handler factory per
// origin and request context, so requests with no browser (service workers,
// workers) are answered too. Open, Skip and Read are asynchronous: each asks
// ContentServerHost on a Java pool thread, which answers through the natives
// below.

#include <jni.h>

#include <atomic>
#include <cstring>
#include <map>
#include <string>
#include <utility>
#include <vector>

#include "base/android/jni_android.h"
#include "base/android/jni_array.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "base/memory/ref_counted.h"
#include "base/no_destructor.h"
#include "base/synchronization/lock.h"
#include "cef/include/cef_request.h"
#include "cef/include/cef_request_context.h"
#include "cef/include/cef_resource_handler.h"
#include "cef/include/cef_response.h"
#include "cef/include/cef_scheme.h"
#include "cef/include/cef_task.h"
#include "cef/libcef/browser/android/cefrium_profiles.h"
#include "cef/libcef/browser/android/cefrium_sdk_internal.h"
#include "net/base/net_errors.h"

using base::android::AttachCurrentThread;
using base::android::ScopedJavaGlobalRef;
using base::android::ScopedJavaLocalRef;
using cefrium_sdk::JStr;

namespace {

// A local reference of its own to a JNI argument, for the JavaRef APIs.
template <typename T>
ScopedJavaLocalRef<T> Local(JNIEnv* env, T obj) {
  return ScopedJavaLocalRef<T>::Adopt(env, static_cast<T>(env->NewLocalRef(obj)));
}

// com.cefrium.ContentServerHost and its static methods, looked up once on
// the main thread at the first registration: a pool or IO thread's
// FindClass cannot see app classes.
struct Host {
  ScopedJavaGlobalRef<jclass> clazz;
  jmethodID start = nullptr;
  jmethodID read = nullptr;
  jmethodID skip = nullptr;
  jmethodID cancel = nullptr;
};
Host& JavaHost() {
  static base::NoDestructor<Host> host;
  return *host;
}

bool InitJavaHost(JNIEnv* env, jclass clazz) {
  Host& host = JavaHost();
  if (host.start) {
    return true;
  }
  host.start = env->GetStaticMethodID(
      clazz, "start",
      "(JLcom/cefrium/CefriumContentServer;Ljava/lang/String;"
      "Ljava/lang/String;[Ljava/lang/String;[B)V");
  host.read = env->GetStaticMethodID(clazz, "read", "(JI)V");
  host.skip = env->GetStaticMethodID(clazz, "skip", "(JJ)V");
  host.cancel = env->GetStaticMethodID(clazz, "cancel", "(J)V");
  if (!host.start || !host.read || !host.skip || !host.cancel) {
    env->ExceptionClear();
    host.start = nullptr;
    return false;
  }
  host.clazz.Reset(env, Local(env, clazz));
  return true;
}

void ClearException(JNIEnv* env) {
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

// A Java CefriumContentServer, shared by its factory and the requests.
class JavaServer : public base::RefCountedThreadSafe<JavaServer> {
 public:
  JavaServer(JNIEnv* env, jobject server)
      : server_(env, Local(env, server)) {}
  jobject obj() const { return server_.obj(); }

 private:
  friend class base::RefCountedThreadSafe<JavaServer>;
  ~JavaServer() = default;

  ScopedJavaGlobalRef<jobject> server_;
};

class ContentResourceHandler;

// A request's headers as alternating names and values.
ScopedJavaLocalRef<jobjectArray> HeadersOf(JNIEnv* env,
                                           CefRefPtr<CefRequest> request) {
  CefRequest::HeaderMap headers;
  request->GetHeaderMap(headers);
  std::vector<std::string> pairs;
  for (const auto& [name, value] : headers) {
    pairs.push_back(name.ToString());
    pairs.push_back(value.ToString());
  }
  return base::android::ToJavaArrayOfStrings(env, pairs);
}

// A request's body bytes; null when it has no body.
ScopedJavaLocalRef<jbyteArray> BodyOf(JNIEnv* env,
                                      CefRefPtr<CefRequest> request) {
  CefRefPtr<CefPostData> post = request->GetPostData();
  if (!post) {
    return ScopedJavaLocalRef<jbyteArray>();
  }
  std::string body;
  CefPostData::ElementVector elements;
  post->GetElements(elements);
  for (const auto& element : elements) {
    // Files and blobs are not read here; see getBody().
    if (element->GetType() == PDE_TYPE_BYTES && element->GetBytesCount()) {
      const size_t start = body.size();
      body.resize(start + element->GetBytesCount());
      element->GetBytes(element->GetBytesCount(), body.data() + start);
    }
  }
  return base::android::ToJavaByteArray(env, body);
}

// The requests Java is answering, by id. Java's answers find their handler
// here; a cancelled request is gone, and its late answers are dropped.
base::Lock& LiveLock() {
  static base::NoDestructor<base::Lock> lock;
  return *lock;
}
std::map<jlong, CefRefPtr<ContentResourceHandler>>& Live() {
  static base::NoDestructor<std::map<jlong, CefRefPtr<ContentResourceHandler>>>
      live;
  return *live;
}
CefRefPtr<ContentResourceHandler> Find(jlong id) {
  base::AutoLock lock(LiveLock());
  auto it = Live().find(id);
  return it == Live().end() ? nullptr : it->second;
}
void Forget(jlong id) {
  CefRefPtr<ContentResourceHandler> keep;  // released outside the lock
  base::AutoLock lock(LiveLock());
  auto it = Live().find(id);
  if (it != Live().end()) {
    keep = it->second;
    Live().erase(it);
  }
}

class ContentResourceHandler : public CefResourceHandler {
 public:
  explicit ContentResourceHandler(scoped_refptr<JavaServer> server)
      : server_(std::move(server)) {
    static std::atomic<jlong> next_id{1};
    id_ = next_id++;
  }

  bool Open(CefRefPtr<CefRequest> request,
            bool& handle_request,
            CefRefPtr<CefCallback> callback) override {
    handle_request = false;
    {
      base::AutoLock lock(lock_);
      open_callback_ = callback;
    }
    {
      base::AutoLock lock(LiveLock());
      Live()[id_] = this;
    }

    JNIEnv* env = AttachCurrentThread();
    ScopedJavaLocalRef<jobjectArray> j_headers = HeadersOf(env, request);

    ScopedJavaLocalRef<jbyteArray> j_body = BodyOf(env, request);

    const Host& host = JavaHost();
    env->CallStaticVoidMethod(
        host.clazz.obj(), host.start, id_, server_->obj(),
        base::android::ConvertUTF8ToJavaString(env,
                                               request->GetMethod().ToString())
            .obj(),
        base::android::ConvertUTF8ToJavaString(env,
                                               request->GetURL().ToString())
            .obj(),
        j_headers.obj(), j_body.obj());
    ClearException(env);
    return true;
  }

  void GetResponseHeaders(CefRefPtr<CefResponse> response,
                          int64_t& response_length,
                          CefString& redirect_url) override {
    {
      base::AutoLock lock(lock_);
      response->SetStatus(status_);
      response->SetStatusText(reason_);
      response->SetMimeType(mime_type_);
      if (!charset_.empty()) {
        response->SetCharset(charset_);
      }
      response->SetHeaderMap(headers_);
      response_length = length_;
      if (length_ != 0) {
        return;
      }
      done_ = true;
    }
    // No body (HEAD, 416): no Read follows, so release both sides now.
    Forget(id_);
    JNIEnv* env = AttachCurrentThread();
    env->CallStaticVoidMethod(JavaHost().clazz.obj(), JavaHost().cancel, id_);
    ClearException(env);
  }

  bool Skip(int64_t bytes_to_skip,
            int64_t& bytes_skipped,
            CefRefPtr<CefResourceSkipCallback> callback) override {
    {
      base::AutoLock lock(lock_);
      skip_callback_ = callback;
    }
    bytes_skipped = 0;
    JNIEnv* env = AttachCurrentThread();
    env->CallStaticVoidMethod(JavaHost().clazz.obj(), JavaHost().skip, id_,
                              static_cast<jlong>(bytes_to_skip));
    ClearException(env);
    return true;
  }

  bool Read(void* data_out,
            int bytes_to_read,
            int& bytes_read,
            CefRefPtr<CefResourceReadCallback> callback) override {
    bytes_read = 0;
    {
      base::AutoLock lock(lock_);
      if (done_) {
        return false;
      }
      read_out_ = data_out;
      read_callback_ = callback;
    }
    JNIEnv* env = AttachCurrentThread();
    env->CallStaticVoidMethod(JavaHost().clazz.obj(), JavaHost().read, id_,
                              static_cast<jint>(bytes_to_read));
    ClearException(env);
    return true;
  }

  void Cancel() override {
    {
      base::AutoLock lock(lock_);
      done_ = true;
      open_callback_ = nullptr;
      skip_callback_ = nullptr;
      read_callback_ = nullptr;
      read_out_ = nullptr;
    }
    Forget(id_);
    JNIEnv* env = AttachCurrentThread();
    env->CallStaticVoidMethod(JavaHost().clazz.obj(), JavaHost().cancel, id_);
    ClearException(env);
  }

  // From Java, on a pool thread.
  void OnResponse(int status,
                  std::string reason,
                  std::string mime_type,
                  std::string charset,
                  CefResponse::HeaderMap headers,
                  int64_t length) {
    CefRefPtr<CefCallback> callback;
    {
      base::AutoLock lock(lock_);
      status_ = status;
      reason_ = std::move(reason);
      mime_type_ = std::move(mime_type);
      charset_ = std::move(charset);
      headers_ = std::move(headers);
      length_ = length;
      callback.swap(open_callback_);
    }
    if (callback) {
      callback->Continue();
    }
  }

  void OnSkipped(int64_t result) {
    CefRefPtr<CefResourceSkipCallback> callback;
    {
      base::AutoLock lock(lock_);
      callback.swap(skip_callback_);
    }
    if (callback) {
      callback->Continue(result);
    }
  }

  // n > 0: |data| holds n bytes; 0: the end; < 0: an error.
  void OnData(const jbyte* data, int n) {
    CefRefPtr<CefResourceReadCallback> callback;
    {
      base::AutoLock lock(lock_);
      if (n > 0 && read_out_) {
        // |read_out_| stays valid until the callback runs (CEF contract).
        std::memcpy(read_out_, data, n);
      }
      if (n <= 0) {
        done_ = true;
      }
      read_out_ = nullptr;
      callback.swap(read_callback_);
    }
    if (n <= 0) {
      Forget(id_);
    }
    if (callback) {
      callback->Continue(n > 0 ? n : (n == 0 ? 0 : net::ERR_FAILED));
    }
  }

 private:
  const scoped_refptr<JavaServer> server_;
  jlong id_;

  base::Lock lock_;
  CefRefPtr<CefCallback> open_callback_;
  CefRefPtr<CefResourceSkipCallback> skip_callback_;
  CefRefPtr<CefResourceReadCallback> read_callback_;
  void* read_out_ = nullptr;
  bool done_ = false;

  int status_ = 500;
  CefString reason_ = "Internal Server Error";
  CefString mime_type_ = "text/plain";
  CefString charset_;
  CefResponse::HeaderMap headers_;
  int64_t length_ = 0;

  IMPLEMENT_REFCOUNTING(ContentResourceHandler);
};

class ContentSchemeHandlerFactory : public CefSchemeHandlerFactory {
 public:
  explicit ContentSchemeHandlerFactory(scoped_refptr<JavaServer> server)
      : server_(std::move(server)) {}

  CefRefPtr<CefResourceHandler> Create(CefRefPtr<CefBrowser> browser,
                                       CefRefPtr<CefFrame> frame,
                                       const CefString& scheme_name,
                                       CefRefPtr<CefRequest> request) override {
    return new ContentResourceHandler(server_);
  }

 private:
  const scoped_refptr<JavaServer> server_;
  IMPLEMENT_REFCOUNTING(ContentSchemeHandlerFactory);
};

// The registered servers: by profile (incognito, name), then by host. Kept
// so a context created later (the incognito one is new for every private
// session) gets them too. Touched only on the CEF UI thread.
using ProfileKey = std::pair<bool, std::string>;
std::map<ProfileKey, std::map<std::string, scoped_refptr<JavaServer>>>&
Registrations() {
  static base::NoDestructor<
      std::map<ProfileKey, std::map<std::string, scoped_refptr<JavaServer>>>>
      registrations;
  return *registrations;
}

constexpr char kScheme[] = "https";

}  // namespace

namespace {

// FEATURE-092: CefriumResourceInterceptor.shouldInterceptRequest. Java
// decides on its content pool while the request waits (RV_CONTINUE_ASYNC);
// an answer is then served by a ContentResourceHandler, null lets the
// request go to the network.
struct PendingDecision {
  CefRefPtr<CefCallback> callback;
  uint64_t request_id;
};
base::Lock& InterceptLock() {
  static base::NoDestructor<base::Lock> lock;
  return *lock;
}
std::map<jlong, PendingDecision>& PendingDecisions() {
  static base::NoDestructor<std::map<jlong, PendingDecision>> pending;
  return *pending;
}
std::map<uint64_t, scoped_refptr<JavaServer>>& Answered() {
  static base::NoDestructor<std::map<uint64_t, scoped_refptr<JavaServer>>> a;
  return *a;
}

}  // namespace

namespace cefrium_sdk {

bool StartInterception(JNIEnv* env,
                       jobject java_browser,
                       jmethodID method,
                       CefRefPtr<CefRequest> request,
                       CefRefPtr<CefCallback> callback) {
  if (!JavaHost().start) {
    return false;  // no interceptor was ever set on the main thread
  }
  static std::atomic<jlong> next_id{1};
  const jlong id = next_id++;
  {
    base::AutoLock lock(InterceptLock());
    PendingDecisions()[id] = {callback, request->GetIdentifier()};
  }
  ScopedJavaLocalRef<jobjectArray> headers = HeadersOf(env, request);
  ScopedJavaLocalRef<jbyteArray> body = BodyOf(env, request);
  env->CallVoidMethod(
      java_browser, method, id,
      base::android::ConvertUTF8ToJavaString(env,
                                             request->GetMethod().ToString())
          .obj(),
      base::android::ConvertUTF8ToJavaString(env, request->GetURL().ToString())
          .obj(),
      headers.obj(), body.obj());
  if (env->ExceptionCheck()) {
    ClearException(env);
    base::AutoLock lock(InterceptLock());
    PendingDecisions().erase(id);
    return false;
  }
  return true;
}

CefRefPtr<CefResourceHandler> TakeInterceptedHandler(uint64_t request_id) {
  scoped_refptr<JavaServer> server;
  {
    base::AutoLock lock(InterceptLock());
    auto it = Answered().find(request_id);
    if (it == Answered().end()) {
      return nullptr;
    }
    server = it->second;
    Answered().erase(it);
  }
  return new ContentResourceHandler(server);
}

void ApplyContentServers(CefRefPtr<CefRequestContext> context,
                         bool incognito,
                         const std::string& profile) {
  auto it = Registrations().find({incognito, profile});
  if (it == Registrations().end()) {
    return;
  }
  for (const auto& [host, server] : it->second) {
    context->RegisterSchemeHandlerFactory(
        kScheme, host, new ContentSchemeHandlerFactory(server));
  }
}

}  // namespace cefrium_sdk

// CefriumProfile.registerContentServer: |host| is validated and lower case.
// Applies at once to the profile's context if it exists, and to any it gets
// later. Main (CEF UI) thread.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_CefriumProfile_nativeRegisterContentServer(JNIEnv* env,
                                                            jclass,
                                                            jboolean incognito,
                                                            jstring profile_j,
                                                            jstring host_j,
                                                            jobject server,
                                                            jclass host_class) {
  if (!CefCurrentlyOn(TID_UI) || !InitJavaHost(env, host_class)) {
    return JNI_FALSE;
  }
  const bool private_profile = incognito == JNI_TRUE;
  const std::string profile = JStr(env, profile_j).ToString();
  const std::string host = JStr(env, host_j).ToString();
  auto java_server = base::MakeRefCounted<JavaServer>(env, server);
  Registrations()[{private_profile, profile}][host] = java_server;
  if (CefRefPtr<CefRequestContext> context =
          cefrium::ContextForProfile(private_profile, profile)) {
    return context->RegisterSchemeHandlerFactory(
               kScheme, host, new ContentSchemeHandlerFactory(java_server))
               ? JNI_TRUE
               : JNI_FALSE;
  }
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumProfile_nativeUnregisterContentServer(
    JNIEnv* env,
    jclass,
    jboolean incognito,
    jstring profile_j,
    jstring host_j) {
  if (!CefCurrentlyOn(TID_UI)) {
    return;
  }
  const bool private_profile = incognito == JNI_TRUE;
  const std::string profile = JStr(env, profile_j).ToString();
  const std::string host = JStr(env, host_j).ToString();
  Registrations()[{private_profile, profile}].erase(host);
  if (CefRefPtr<CefRequestContext> context =
          cefrium::ContextForProfile(private_profile, profile)) {
    context->RegisterSchemeHandlerFactory(kScheme, host, nullptr);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_ContentServerHost_nativeOnResponse(JNIEnv* env,
                                                    jclass,
                                                    jlong id,
                                                    jint status,
                                                    jstring reason,
                                                    jstring mime_type,
                                                    jstring charset,
                                                    jobjectArray header_pairs,
                                                    jlong length) {
  CefRefPtr<ContentResourceHandler> handler = Find(id);
  if (!handler) {
    return;
  }
  std::vector<std::string> pairs;
  base::android::AppendJavaStringArrayToStringVector(
      env, Local(env, header_pairs), &pairs);
  CefResponse::HeaderMap headers;
  for (size_t i = 0; i + 1 < pairs.size(); i += 2) {
    headers.insert({pairs[i], pairs[i + 1]});
  }
  handler->OnResponse(status, JStr(env, reason).ToString(),
                      JStr(env, mime_type).ToString(),
                      JStr(env, charset).ToString(), std::move(headers),
                      length);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_ContentServerHost_nativeOnData(JNIEnv* env,
                                                jclass,
                                                jlong id,
                                                jbyteArray data,
                                                jint n) {
  CefRefPtr<ContentResourceHandler> handler = Find(id);
  if (!handler) {
    return;
  }
  if (n > 0 && data) {
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    handler->OnData(bytes, n);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
  } else {
    handler->OnData(nullptr, n);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_ContentServerHost_nativeOnSkipped(JNIEnv* env,
                                                   jclass,
                                                   jlong id,
                                                   jlong result) {
  if (CefRefPtr<ContentResourceHandler> handler = Find(id)) {
    handler->OnSkipped(result);
  }
}

// The interceptor's answer for decision |id|: |server| serves the response,
// or null for the network. Any thread.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeOnInterceptDecision(JNIEnv* env,
                                                         jclass,
                                                         jlong id,
                                                         jobject server) {
  CefRefPtr<CefCallback> callback;
  {
    base::AutoLock lock(InterceptLock());
    auto it = PendingDecisions().find(id);
    if (it == PendingDecisions().end()) {
      return;
    }
    callback = it->second.callback;
    if (server) {
      Answered()[it->second.request_id] =
          base::MakeRefCounted<JavaServer>(env, server);
    }
    PendingDecisions().erase(it);
  }
  callback->Continue();
}

// Looks up ContentServerHost on the main thread, where app classes are
// visible, before the first interception.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_CefriumBrowser_nativeInitContentHost(JNIEnv* env,
                                                     jclass,
                                                     jclass host_class) {
  return InitJavaHost(env, host_class) ? JNI_TRUE : JNI_FALSE;
}
