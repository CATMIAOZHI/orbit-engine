// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// Requests of a CefriumBrowser: the request and resource-request handlers,
// served data documents, the response tap, the cookie filter and ad blocking.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_REQUEST_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_REQUEST_H_

#include <cinttypes>
#include <fstream>
#include <iterator>
#include <jni.h>
#include <map>
#include <optional>
#include <string>
#include <variant>
#include <vector>
#include "base/base64.h"
#include "cef/include/cef_app.h"
#include "cef/libcef/browser/android/cef_autofill_client_android.h"
#include "components/android_autofill/browser/autofill_provider.h"
#include "cef/include/cef_browser.h"
#include "cef/include/cef_client.h"
#include "cef/include/cef_cookie.h"
#include "cef/include/cef_request.h"
#include "cef/include/cef_parser.h"
#include "cef/include/cef_preference.h"
#include "cef/include/cef_request_context.h"
#include "cef/include/cef_urlrequest.h"
#include "components/js_injection/browser/js_communication_host.h"
#include "components/js_injection/common/enum.mojom.h"
#include "components/origin_matcher/origin_matcher.h"
#include "net/dns/public/dns_over_https_config.h"
#include "content/public/browser/web_contents_user_data.h"
#include "cef/include/cef_values.h"
#include "cef/include/cef_version.h"
#include "cef/include/cef_context_menu_handler.h"
#include "cef/include/cef_device_chooser_handler.h"
#include "cef/include/cef_display_handler.h"
#include "cef/include/cef_download_handler.h"
#include "cef/include/cef_find_handler.h"
#include "cef/include/cef_jsdialog_handler.h"
#include "cef/include/cef_life_span_handler.h"
#include "cef/include/cef_frame.h"
#include "cef/include/cef_permission_handler.h"
#include "cef/include/cef_load_handler.h"
#include "cef/include/cef_render_handler.h"
#include <map>
#include <set>
#include "base/functional/callback.h"
#include "base/memory/weak_ptr.h"
#include "base/no_destructor.h"
#include "base/strings/string_util.h"
#include "base/synchronization/lock.h"
#include "cef/include/cef_request_handler.h"
#include "cef/include/cef_task.h"
#include "cef/include/wrapper/cef_closure_task.h"
#include "cef/include/wrapper/cef_message_router.h"
#include "cef/libcef/browser/android/cefrium_bridge_constants.h"
#include "cef/libcef/browser/android/cefrium_profiles.h"
#include "cef/libcef/browser/android/cefrium_sdk_devtools.h"
#include "cef/libcef/browser/android/cefrium_sdk_identity.h"
#include "cef/libcef/browser/android/cefrium_sdk_internal.h"
#include "cef/include/cef_resource_request_handler.h"
#include "cef/include/cef_resource_handler.h"
#include "cef/include/cef_response.h"
#include "cef/include/cef_callback.h"
#include <algorithm>
#include <atomic>
#include <cstring>
#include <android/log.h>
#include "base/android/jni_android.h"
#include "base/android/jni_array.h"
#include "base/android/jni_string.h"
#include "base/pickle.h"
#include "cef/libcef/browser/android/adblock_filter.h"
#include "cef/libcef/browser/android/browser_surface_android.h"
#include "cef/libcef/browser/android/cef_share_service.h"
#include "cef/libcef/browser/android/picker_host_android.h"
#include "cef/libcef/browser/browser_host_base.h"
#include "cef/libcef/browser/frame_host_impl.h"
#include "content/public/browser/browser_context.h"
#include "content/public/browser/document_service.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/navigation_entry.h"
#include "content/public/browser/navigation_entry_restore_context.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/render_process_host.h"
#include "content/public/common/referrer.h"
#include "content/public/browser/restore_type.h"
#include "content/public/browser/web_contents.h"
#include "services/network/public/cpp/shared_url_loader_factory.h"
#include "third_party/blink/public/common/page_state/page_state.h"
#include "ui/android/window_android.h"
#include "ui/base/page_transition_types.h"
#include "url/gurl.h"
#include "cef/libcef/browser/request_context_impl.h"
#include "chrome/browser/android/devtools_server.h"
#include "chrome/browser/profiles/profile.h"
#include "url/origin.h"

// loadHtmlWithBaseUrl via a GENUINE network-served response (not a data: URL).
//
// A data-with-base (data:) document is rejected client-side by some embedded
// players -- notably the YouTube IFrame player, which returns Error 152 because
// the embedding document was never served over the network (verified on device:
// origin/Referer/Origin are correct, yet the player aborts; the same HTML served
// from a real http origin PLAYS). So instead of a LOAD_TYPE_DATA navigation, we
// register the HTML under its base_url and, when the browser navigates there,
// serve it as a real 200 response. The committed document then has a true network
// origin == base_url. Every OTHER request (the embed iframe, base.js, googlevideo)
// passes through to the real network. This is something CEF can do that Android
// WebView cannot: WebView's loadDataWithBaseURL always yields a data: document.
struct PendingDataDoc {
  std::string html;
  std::string content_type;  // e.g. "text/html; charset=utf-8"
};

base::Lock& PendingDocsLock();
std::map<std::string, PendingDataDoc>& PendingDocs();
void RegisterPendingDataDoc(const std::string& url,
                            std::string html,
                            std::string content_type);

// Serves stored bytes as the response for an intercepted main-frame navigation.
class DataDocResourceHandler : public CefResourceHandler {
 public:
  DataDocResourceHandler(std::string data, std::string content_type)
      : data_(std::move(data)), content_type_(std::move(content_type)) {}

  bool Open(CefRefPtr<CefRequest> request,
            bool& handle_request,
            CefRefPtr<CefCallback> callback) override {
    handle_request = true;  // handle synchronously, no rescheduling
    return true;
  }

  void GetResponseHeaders(CefRefPtr<CefResponse> response,
                          int64_t& response_length,
                          CefString& redirectUrl) override {
    response->SetStatus(200);
    response->SetStatusText("OK");
    response->SetMimeType("text/html");
    response->SetHeaderByName("Content-Type", content_type_, true);
    response_length = static_cast<int64_t>(data_.size());
  }

  bool Read(void* data_out,
            int bytes_to_read,
            int& bytes_read,
            CefRefPtr<CefResourceReadCallback> callback) override {
    const size_t remaining = data_.size() - offset_;
    if (remaining == 0) {
      bytes_read = 0;
      return false;  // EOF
    }
    const int n = static_cast<int>(
        std::min(static_cast<size_t>(bytes_to_read), remaining));
    std::memcpy(data_out, data_.data() + offset_, n);
    offset_ += n;
    bytes_read = n;
    return true;
  }

  void Cancel() override {}

 private:
  std::string data_;
  std::string content_type_;
  size_t offset_ = 0;
  IMPLEMENT_REFCOUNTING(DataDocResourceHandler);
};

// Ad-blocking request handler using EasyList filter engine.
// FEATURE-063 (opt-in): passthrough response filter that taps each body chunk to
// Java (onResponseData). Installed only when the consumer enabled it, so there is
// zero cost by default. Runs on the IO thread.
class SdkResponseFilter : public CefResponseFilter {
 public:
  SdkResponseFilter(jobject java_browser_global, const std::string& url)
      : java_browser_(java_browser_global), url_(url) {}
  bool InitFilter() override { return true; }
  FilterStatus Filter(void* data_in, size_t data_in_size, size_t& data_in_read,
                      void* data_out, size_t data_out_size,
                      size_t& data_out_written) override {
    size_t n = data_in_size < data_out_size ? data_in_size : data_out_size;
    if (data_in && n > 0) {
      memcpy(data_out, data_in, n);
      // Tap the chunk to Java (copy happens Java-side during the call).
      if (java_browser_) {
        JNIEnv* env = base::android::AttachCurrentThread();
        if (env) {
          jobject buf = env->NewDirectByteBuffer(data_out,
                                                 static_cast<jlong>(n));
          if (buf) {
            jclass cls = env->GetObjectClass(java_browser_);
            jmethodID mid = env->GetMethodID(cls, "onResponseData",
                "(Ljava/lang/String;Ljava/nio/ByteBuffer;)V");
            if (mid) {
              jstring ju = env->NewStringUTF(url_.c_str());
              env->CallVoidMethod(java_browser_, mid, ju, buf);
              env->DeleteLocalRef(ju);
            }
            env->DeleteLocalRef(cls);
            env->DeleteLocalRef(buf);
          }
        }
      }
    }
    data_in_read = n;
    data_out_written = n;
    return (data_in_read < data_in_size) ? RESPONSE_FILTER_NEED_MORE_DATA
                                         : RESPONSE_FILTER_DONE;
  }
  ~SdkResponseFilter() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }
  IMPLEMENT_REFCOUNTING(SdkResponseFilter);
 private:
  jobject java_browser_;
  std::string url_;
};

// FEATURE-063 (opt-in): per-cookie access filter. Synchronous jboolean up-calls
// (default allow). Installed only when enabled -> zero cost by default.
class SdkCookieAccessFilter : public CefCookieAccessFilter {
 public:
  explicit SdkCookieAccessFilter(jobject java_browser_global)
      : java_browser_(java_browser_global) {}
  bool CanSendCookie(CefRefPtr<CefBrowser> browser,
                     CefRefPtr<CefFrame> frame,
                     CefRefPtr<CefRequest> request,
                     const CefCookie& cookie) override {
    return Ask("onCanSendCookie", request->GetURL(), cookie);
  }
  bool CanSaveCookie(CefRefPtr<CefBrowser> browser,
                     CefRefPtr<CefFrame> frame,
                     CefRefPtr<CefRequest> request,
                     CefRefPtr<CefResponse> response,
                     const CefCookie& cookie) override {
    return Ask("onCanSaveCookie", request->GetURL(), cookie);
  }
  bool Ask(const char* name, const CefString& url, const CefCookie& cookie) {
    if (!java_browser_) return true;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return true;
    CefString cn, cv;
    cn.FromString16(reinterpret_cast<const char16_t*>(cookie.name.str),
                    cookie.name.length);
    cv.FromString16(reinterpret_cast<const char16_t*>(cookie.value.str),
                    cookie.value.length);
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, name,
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
    jboolean allow = JNI_TRUE;
    if (mid) {
      jstring ju = env->NewStringUTF(url.ToString().c_str());
      jstring jn = env->NewStringUTF(cn.ToString().c_str());
      jstring jv = env->NewStringUTF(cv.ToString().c_str());
      allow = env->CallBooleanMethod(java_browser_, mid, ju, jn, jv);
      env->DeleteLocalRef(ju);
      env->DeleteLocalRef(jn);
      env->DeleteLocalRef(jv);
    }
    env->DeleteLocalRef(cls);
    return allow == JNI_TRUE;
  }
  ~SdkCookieAccessFilter() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }
  IMPLEMENT_REFCOUNTING(SdkCookieAccessFilter);
 private:
  jobject java_browser_;
};

// Sites where a profile's browsers block nothing, as @@||site^$document does:
// a host covers its subdomains, an origin only itself. Written on the UI thread
// by CefriumProfile.setAdBlockExceptions, read on the IO thread per request.
class AdBlockExceptions {
 public:
  // False, and nothing changed, if an entry is neither a host nor an origin.
  static bool Set(bool incognito,
                  const std::string& profile,
                  const std::vector<std::string>& sites) {
    std::vector<std::string> hosts;
    std::vector<url::Origin> origins;
    for (const std::string& site : sites) {
      if (site.find("://") != std::string::npos) {
        GURL url(site);
        if (!url.is_valid() || !url.has_host() ||
            (url.has_path() && url.path() != "/") || url.has_query()) {
          return false;
        }
        origins.push_back(url::Origin::Create(url));
      } else {
        GURL url("https://" + site + "/");
        if (site.empty() || !url.is_valid() || url.host() != base::ToLowerASCII(site)) {
          return false;
        }
        hosts.push_back(std::string(url.host()));
      }
    }
    base::AutoLock lock(Lock());
    Entry& entry = Map()[{incognito, profile}];
    entry.hosts = std::move(hosts);
    entry.origins = std::move(origins);
    return true;
  }

  static bool Covers(bool incognito,
                     const std::string& profile,
                     const GURL& document) {
    if (!document.is_valid() || !document.has_host()) {
      return false;
    }
    base::AutoLock lock(Lock());
    auto it = Map().find({incognito, profile});
    if (it == Map().end()) {
      return false;
    }
    const std::string_view host = document.host();
    for (const std::string& h : it->second.hosts) {
      if (host == h || (host.size() > h.size() &&
                        host.ends_with(h) &&
                        host[host.size() - h.size() - 1] == '.')) {
        return true;
      }
    }
    const url::Origin origin = url::Origin::Create(document);
    for (const url::Origin& o : it->second.origins) {
      if (o.IsSameOriginWith(origin)) {
        return true;
      }
    }
    return false;
  }

 private:
  struct Entry {
    std::vector<std::string> hosts;
    std::vector<url::Origin> origins;
  };
  static base::Lock& Lock() {
    static base::NoDestructor<base::Lock> lock;
    return *lock;
  }
  static std::map<std::pair<bool, std::string>, Entry>& Map() {
    static base::NoDestructor<std::map<std::pair<bool, std::string>, Entry>>
        map;
    return *map;
  }
};

// Errors that only a proxy causes: the request failed because of the proxy,
// not the site.
bool IsProxyError(cef_errorcode_t error);

class SdkResourceRequestHandler : public CefResourceRequestHandler {
 public:
  // Which Java listeners exist, from CefriumBrowser.updateRequestObservers().
  // Every request runs through this handler on the IO thread, so a call into
  // Java that nobody listens to is not made.
  enum Observer {
    kInterceptor = 1 << 0,       // RequestInterceptor
    kInterceptListener = 1 << 1,  // onRequestIntercepted
    kResourceEvents = 1 << 2,     // CefriumRequestHandler redirect/response/complete
  };

  void SetResponseTapEnabled(bool e) { response_tap_enabled_ = e; }
  void SetCookieFilterEnabled(bool e) { cookie_filter_enabled_ = e; }
  void SetObservers(int observers) { observers_ = observers; }
  void SetAdBlockEnabled(bool enabled) { adblock_enabled_ = enabled; }
  // The profile whose ad-block exceptions apply; set once at creation.
  void SetProfile(bool incognito, const std::string& profile) {
    incognito_ = incognito;
    profile_ = profile;
  }
  int blocked_count() const { return blocked_count_; }

  // The Java browser is the same object for the browser's lifetime; the first
  // reference is kept, so the IO thread never sees it change.
  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    if (!java_browser || java_browser_) {
      return;
    }
    jclass cls = env->GetObjectClass(java_browser);
    should_block_ = env->GetMethodID(
        cls, "onShouldBlockRequest",
        "(Ljava/lang/String;Ljava/lang/String;ZILjava/lang/String;"
        "Ljava/lang/String;)Z");
    intercepted_ = env->GetMethodID(
        cls, "onRequestIntercepted", "(Ljava/lang/String;Ljava/lang/String;Z)V");
    redirect_ = env->GetMethodID(cls, "onResourceRedirect",
                                 "(Ljava/lang/String;Ljava/lang/String;)V");
    response_ =
        env->GetMethodID(cls, "onResourceResponse", "(Ljava/lang/String;I)V");
    complete_ = env->GetMethodID(cls, "onResourceLoadComplete",
                                 "(Ljava/lang/String;IJ)V");
    proxy_error_ =
        env->GetMethodID(cls, "onProxyError", "(Ljava/lang/String;IZ)V");
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
    }
    java_browser_ = env->NewGlobalRef(java_browser);
  }

  CefResourceRequestHandler::ReturnValue OnBeforeResourceLoad(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      CefRefPtr<CefRequest> request,
      CefRefPtr<CefCallback> callback) override {
    return BeforeResourceLoad(browser, frame, request, std::string());
  }

  // |initiator| is the origin of the document that made the request, as the
  // network stack has it; empty when unknown.
  CefResourceRequestHandler::ReturnValue BeforeResourceLoad(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      CefRefPtr<CefRequest> request,
      const std::string& initiator) {
    const int observers = observers_;
    std::shared_ptr<const AdBlockFilter> filter =
        adblock_enabled_ ? AdBlockFilter::Current() : nullptr;
    if (!filter && !(observers & (kInterceptor | kInterceptListener))) {
      return RV_CONTINUE;
    }
    const std::string url = request->GetURL().ToString();

    const cef_resource_type_t type = request->GetResourceType();
    const GURL document = DocumentOf(frame, request, initiator);
    bool blocked = false;
    if (filter) {
      blocked = !AdBlockExceptions::Covers(incognito_, profile_, document) &&
                filter->ShouldBlock(GURL(url), document, type);
    }

    jobject jb = java_browser_;
    JNIEnv* env = jb ? base::android::AttachCurrentThread() : nullptr;
    if (!blocked && env && should_block_ && (observers & kInterceptor)) {
      // A main-frame navigation is its own document and its own top page.
      std::string doc = document.is_valid() ? document.spec() : std::string();
      std::string top;
      if (type == RT_MAIN_FRAME) {
        doc = top = url;
      } else if (frame && frame->IsMain()) {
        top = doc;
      } else if (CefRefPtr<CefFrame> main = browser ? browser->GetMainFrame()
                                                    : nullptr) {
        top = main->GetURL().ToString();
      }
      jstring ju = env->NewStringUTF(url.c_str());
      jstring jm = env->NewStringUTF(request->GetMethod().ToString().c_str());
      jstring jd = env->NewStringUTF(doc.c_str());
      jstring jt = env->NewStringUTF(top.c_str());
      blocked = env->CallBooleanMethod(jb, should_block_, ju, jm,
                                       frame && frame->IsMain(),
                                       static_cast<jint>(type), jd, jt);
      env->DeleteLocalRef(ju);
      env->DeleteLocalRef(jm);
      env->DeleteLocalRef(jd);
      env->DeleteLocalRef(jt);
    }
    if (blocked) {
      blocked_count_++;
    }
    if (env && intercepted_ && (observers & kInterceptListener)) {
      jstring ju = env->NewStringUTF(url.substr(0, 200).c_str());
      jstring jm = env->NewStringUTF(request->GetMethod().ToString().c_str());
      env->CallVoidMethod(jb, intercepted_, ju, jm, blocked);
      env->DeleteLocalRef(ju);
      env->DeleteLocalRef(jm);
    }
    return blocked ? RV_CANCEL : RV_CONTINUE;
  }

  // Serve a registered loadHtmlWithBaseUrl document as a real network response.
  // One-shot: the entry is removed on first serve so it can't hijack later
  // navigations to the same URL. Returns nullptr for everything else -> the
  // request takes the normal network path.
  CefRefPtr<CefResourceHandler> GetResourceHandler(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      CefRefPtr<CefRequest> request) override {
    const std::string url = request->GetURL().ToString();
    base::AutoLock lock(PendingDocsLock());
    auto& docs = PendingDocs();
    auto it = docs.find(url);
    if (it == docs.end()) {
      return nullptr;
    }
    PendingDataDoc doc = std::move(it->second);
    docs.erase(it);
    return new DataDocResourceHandler(std::move(doc.html),
                                      std::move(doc.content_type));
  }

  // FEATURE-063: response-side inspection (WebView has request-side only).
  void OnResourceRedirect(CefRefPtr<CefBrowser> browser,
                          CefRefPtr<CefFrame> frame,
                          CefRefPtr<CefRequest> request,
                          CefRefPtr<CefResponse> response,
                          CefString& new_url) override {
    JNIEnv* env = ResourceEventsEnv();
    if (!env || !redirect_) {
      return;
    }
    jstring jo = env->NewStringUTF(request->GetURL().ToString().c_str());
    jstring jn = env->NewStringUTF(new_url.ToString().c_str());
    env->CallVoidMethod(java_browser_, redirect_, jo, jn);
    env->DeleteLocalRef(jo);
    env->DeleteLocalRef(jn);
  }

  bool OnResourceResponse(CefRefPtr<CefBrowser> browser,
                          CefRefPtr<CefFrame> frame,
                          CefRefPtr<CefRequest> request,
                          CefRefPtr<CefResponse> response) override {
    JNIEnv* env = ResourceEventsEnv();
    if (env && response_) {
      jstring ju = env->NewStringUTF(request->GetURL().ToString().c_str());
      env->CallVoidMethod(java_browser_, response_, ju, response->GetStatus());
      env->DeleteLocalRef(ju);
    }
    return false;  // do not re-issue the request
  }

  void OnResourceLoadComplete(CefRefPtr<CefBrowser> browser,
                              CefRefPtr<CefFrame> frame,
                              CefRefPtr<CefRequest> request,
                              CefRefPtr<CefResponse> response,
                              URLRequestStatus status,
                              int64_t received_content_length) override {
    // Reported whether or not anyone listens to the other events: it is rare,
    // and an app needs it to know its proxy (Tor, a VPN's) is down.
    jobject jb = java_browser_;
    if (status == UR_FAILED && response && jb && proxy_error_ &&
        IsProxyError(response->GetError())) {
      JNIEnv* penv = base::android::AttachCurrentThread();
      jstring ju = penv->NewStringUTF(request->GetURL().ToString().c_str());
      penv->CallVoidMethod(
          jb, proxy_error_, ju, static_cast<jint>(response->GetError()),
          static_cast<jboolean>(request->GetResourceType() == RT_MAIN_FRAME));
      penv->DeleteLocalRef(ju);
      if (penv->ExceptionCheck()) {
        penv->ExceptionClear();
      }
    }
    JNIEnv* env = ResourceEventsEnv();
    if (!env || !complete_) {
      return;
    }
    jstring ju = env->NewStringUTF(request->GetURL().ToString().c_str());
    env->CallVoidMethod(java_browser_, complete_, ju,
                        response ? response->GetStatus() : 0,
                        static_cast<jlong>(received_content_length));
    env->DeleteLocalRef(ju);
  }

  // FEATURE-063 (opt-in): body-tap response filter. null unless enabled.
  CefRefPtr<CefResponseFilter> GetResourceResponseFilter(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      CefRefPtr<CefRequest> request,
      CefRefPtr<CefResponse> response) override {
    if (!response_tap_enabled_ || !java_browser_) return nullptr;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return nullptr;
    jobject global = env->NewGlobalRef(java_browser_);
    return new SdkResponseFilter(global, request->GetURL().ToString());
  }

  // FEATURE-063 (opt-in): per-cookie access filter. null unless enabled.
  CefRefPtr<CefCookieAccessFilter> GetCookieAccessFilter(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      CefRefPtr<CefRequest> request) override {
    if (!cookie_filter_enabled_ || !java_browser_) return nullptr;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return nullptr;
    jobject global = env->NewGlobalRef(java_browser_);
    return new SdkCookieAccessFilter(global);
  }

  ~SdkResourceRequestHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }

  IMPLEMENT_REFCOUNTING(SdkResourceRequestHandler);

 private:
  // The document a request is made for: the parent page for an iframe
  // navigation, otherwise the frame's own page.
  static GURL DocumentOf(CefRefPtr<CefFrame> frame,
                         CefRefPtr<CefRequest> request,
                         const std::string& initiator) {
    if (!frame) {
      return GURL(request->GetReferrerURL().ToString());
    }
    if (request->GetResourceType() == RT_SUB_FRAME) {
      if (CefRefPtr<CefFrame> parent = frame->GetParent()) {
        return GURL(parent->GetURL().ToString());
      }
    }
    const GURL document(frame->GetURL().ToString());
    // The frame's URL is refreshed only once the browser has processed the
    // commit, after the new document's first requests: until then it is the
    // previous page's, or empty for a new frame. Use it only when it matches
    // the origin the request came from, otherwise report that origin.
    const GURL origin(initiator);
    if (origin.is_valid() &&
        !url::Origin::Create(document).IsSameOriginWith(origin)) {
      return origin;
    }
    return document;
  }

  // Checked before any jmethodID is read: they are written before
  // java_browser_ is published (SetJavaBrowser).
  JNIEnv* ResourceEventsEnv() const {
    if (!java_browser_ || !(observers_ & kResourceEvents)) {
      return nullptr;
    }
    return base::android::AttachCurrentThread();
  }

  std::atomic<jobject> java_browser_{nullptr};
  jmethodID should_block_ = nullptr;
  jmethodID intercepted_ = nullptr;
  jmethodID redirect_ = nullptr;
  jmethodID response_ = nullptr;
  jmethodID complete_ = nullptr;
  jmethodID proxy_error_ = nullptr;
  std::atomic<int> observers_{0};
  std::atomic<bool> adblock_enabled_{true};
  bool incognito_ = false;
  std::string profile_;
  std::atomic<int> blocked_count_{0};
  std::atomic<bool> response_tap_enabled_{false};
  std::atomic<bool> cookie_filter_enabled_{false};
};

// A subresource request's handler: the shared one, plus the request's
// initiator, which only GetResourceRequestHandler is given.
class SdkSubresourceRequestHandler : public CefResourceRequestHandler {
 public:
  SdkSubresourceRequestHandler(CefRefPtr<SdkResourceRequestHandler> shared,
                               const std::string& initiator)
      : shared_(shared), initiator_(initiator) {}

  ReturnValue OnBeforeResourceLoad(CefRefPtr<CefBrowser> browser,
                                   CefRefPtr<CefFrame> frame,
                                   CefRefPtr<CefRequest> request,
                                   CefRefPtr<CefCallback> callback) override {
    return shared_->BeforeResourceLoad(browser, frame, request, initiator_);
  }
  CefRefPtr<CefResourceHandler> GetResourceHandler(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      CefRefPtr<CefRequest> request) override {
    return shared_->GetResourceHandler(browser, frame, request);
  }
  void OnResourceRedirect(CefRefPtr<CefBrowser> browser,
                          CefRefPtr<CefFrame> frame,
                          CefRefPtr<CefRequest> request,
                          CefRefPtr<CefResponse> response,
                          CefString& new_url) override {
    shared_->OnResourceRedirect(browser, frame, request, response, new_url);
  }
  bool OnResourceResponse(CefRefPtr<CefBrowser> browser,
                          CefRefPtr<CefFrame> frame,
                          CefRefPtr<CefRequest> request,
                          CefRefPtr<CefResponse> response) override {
    return shared_->OnResourceResponse(browser, frame, request, response);
  }
  void OnResourceLoadComplete(CefRefPtr<CefBrowser> browser,
                              CefRefPtr<CefFrame> frame,
                              CefRefPtr<CefRequest> request,
                              CefRefPtr<CefResponse> response,
                              URLRequestStatus status,
                              int64_t received_content_length) override {
    shared_->OnResourceLoadComplete(browser, frame, request, response, status,
                                    received_content_length);
  }
  CefRefPtr<CefResponseFilter> GetResourceResponseFilter(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      CefRefPtr<CefRequest> request,
      CefRefPtr<CefResponse> response) override {
    return shared_->GetResourceResponseFilter(browser, frame, request,
                                              response);
  }
  CefRefPtr<CefCookieAccessFilter> GetCookieAccessFilter(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      CefRefPtr<CefRequest> request) override {
    return shared_->GetCookieAccessFilter(browser, frame, request);
  }

 private:
  CefRefPtr<SdkResourceRequestHandler> shared_;
  const std::string initiator_;
  IMPLEMENT_REFCOUNTING(SdkSubresourceRequestHandler);
};

class SdkRequestHandler : public CefRequestHandler {
 public:
  SdkRequestHandler() : resource_handler_(new SdkResourceRequestHandler()) {}

  CefRefPtr<CefResourceRequestHandler> GetResourceRequestHandler(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      CefRefPtr<CefRequest> request,
      bool is_navigation,
      bool is_download,
      const CefString& request_initiator,
      bool& disable_default_handling) override {
    if (!is_navigation && !request_initiator.empty()) {
      return new SdkSubresourceRequestHandler(resource_handler_,
                                              request_initiator.ToString());
    }
    return resource_handler_;
  }

  CefRefPtr<SdkResourceRequestHandler> resource_request_handler() {
    return resource_handler_;
  }

  // FEATURE-023: forward navigation / renderer-termination to the JS bridge
  // router so it can cancel pending queries (no leaks / stale state).
  bool OnBeforeBrowse(CefRefPtr<CefBrowser> browser,
                      CefRefPtr<CefFrame> frame,
                      CefRefPtr<CefRequest> request,
                      bool user_gesture,
                      bool is_redirect) override {
    if (message_router_) {
      message_router_->OnBeforeBrowse(browser, frame);
    }
    return false;  // never cancel the navigation
  }

  void OnRenderProcessTerminated(CefRefPtr<CefBrowser> browser,
                                 TerminationStatus status,
                                 int error_code,
                                 const CefString& error_string) override {
    LOGI("OnRenderProcessTerminated status=%d error_code=%d",
         static_cast<int>(status), error_code);
    if (message_router_) {
      message_router_->OnRenderProcessTerminated(browser);
    }
    // Permanent public API (not benchmark-gated): a renderer can die without
    // killing the app process and without a logcat FATAL -- flagships need this
    // to show a reload UI, and the crash matrix counts renderer_crash_count from
    // it. status: TS_ABNORMAL_TERMINATION=0, TS_PROCESS_WAS_KILLED=1,
    // TS_PROCESS_CRASHED=2, TS_PROCESS_OOM=3 (cef_termination_status_t).
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) {
        jclass cls = env->GetObjectClass(java_browser_);
        jmethodID mid =
            env->GetMethodID(cls, "onRenderProcessTerminated", "(II)V");
        if (mid) {
          env->CallVoidMethod(java_browser_, mid, static_cast<jint>(status),
                              static_cast<jint>(error_code));
        }
        env->DeleteLocalRef(cls);
      }
    }
  }

  // FEATURE-063: window.open / target=_blank / ctrl-click to a new tab. Notify
  // Java; return false so CEF's default (OnBeforePopup) path still runs.
  bool OnOpenURLFromTab(CefRefPtr<CefBrowser> browser,
                        CefRefPtr<CefFrame> frame,
                        const CefString& target_url,
                        WindowOpenDisposition target_disposition,
                        bool user_gesture) override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) {
        jclass cls = env->GetObjectClass(java_browser_);
        jmethodID mid = env->GetMethodID(cls, "onOpenUrlFromTab",
            "(Ljava/lang/String;IZ)V");
        if (mid) {
          jstring j = env->NewStringUTF(target_url.ToString().c_str());
          env->CallVoidMethod(java_browser_, mid, j,
                              static_cast<jint>(target_disposition),
                              user_gesture);
          env->DeleteLocalRef(j);
        }
        env->DeleteLocalRef(cls);
      }
    }
    return false;
  }

  // FEATURE-063: hung-renderer (ANR) detection. Notify Java; return false for
  // the default behaviour (Chromium's hang UI). WebView onRenderProcessUnresponsive.
  bool OnRenderProcessUnresponsive(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefUnresponsiveProcessCallback> callback) override {
    NotifyNoArg("onRenderUnresponsive");
    return false;
  }

  void OnRenderProcessResponsive(CefRefPtr<CefBrowser> browser) override {
    NotifyNoArg("onRenderResponsive");
  }

  void OnRenderViewReady(CefRefPtr<CefBrowser> browser) override {
    NotifyNoArg("onRenderViewReady");
  }

  void OnDocumentAvailableInMainFrame(CefRefPtr<CefBrowser> browser) override {
    NotifyNoArg("onDocumentAvailable");
  }

  // Small helper for the no-argument Java up-calls above.
  void NotifyNoArg(const char* method_name) {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, method_name, "()V");
    if (mid) env->CallVoidMethod(java_browser_, mid);
    env->DeleteLocalRef(cls);
  }

  void SetMessageRouter(CefRefPtr<CefMessageRouterBrowserSide> router) {
    message_router_ = router;
  }

  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb) {
      java_browser_ = env->NewGlobalRef(jb);
    }
  }

  // WebView parity: surface invalid-certificate decisions to the embedder
  // (onReceivedSslError). With no Java handler the request is cancelled — the
  // safe default, matching CEF's default false return.
  bool OnCertificateError(CefRefPtr<CefBrowser> browser,
                          cef_errorcode_t cert_error,
                          const CefString& request_url,
                          CefRefPtr<CefSSLInfo> ssl_info,
                          CefRefPtr<CefCallback> callback) override {
    if (!java_browser_) {
      return false;  // default: cancel the load
    }
    uint64_t id;
    {
      base::AutoLock lock(lock_);
      id = next_ssl_id_++;
      pending_ssl_[id] = callback;
    }
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid =
        env->GetMethodID(cls, "onSslError", "(JLjava/lang/String;I)V");
    if (mid) {
      jstring jurl = env->NewStringUTF(request_url.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, static_cast<jlong>(id), jurl,
                          static_cast<jint>(cert_error));
      env->DeleteLocalRef(jurl);
    } else {
      RespondSslError(id, false);
    }
    env->DeleteLocalRef(cls);
    return true;
  }

  // Called from nativeRespondSslError once Java decides proceed/cancel.
  void RespondSslError(uint64_t id, bool proceed) {
    CefRefPtr<CefCallback> cb;
    {
      base::AutoLock lock(lock_);
      auto it = pending_ssl_.find(id);
      if (it == pending_ssl_.end()) {
        return;
      }
      cb = it->second;
      pending_ssl_.erase(it);
    }
    if (proceed) {
      cb->Continue();
    } else {
      cb->Cancel();
    }
  }

  // WebView parity: surface HTTP/proxy auth challenges to the embedder
  // (onReceivedHttpAuthRequest). With no Java handler the challenge is
  // cancelled (no credentials), matching CEF's default false return.
  bool GetAuthCredentials(CefRefPtr<CefBrowser> browser,
                          const CefString& origin_url,
                          bool isProxy,
                          const CefString& host,
                          int port,
                          const CefString& realm,
                          const CefString& scheme,
                          CefRefPtr<CefAuthCallback> callback) override {
    if (!java_browser_) {
      return false;  // default: no credentials
    }
    uint64_t id;
    {
      base::AutoLock lock(lock_);
      id = next_auth_id_++;
      pending_auth_[id] = callback;
    }
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(
        cls, "onHttpAuthRequest", "(JLjava/lang/String;Ljava/lang/String;IZ)V");
    if (mid) {
      jstring jhost = env->NewStringUTF(host.ToString().c_str());
      jstring jrealm = env->NewStringUTF(realm.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, static_cast<jlong>(id), jhost,
                          jrealm, static_cast<jint>(port),
                          static_cast<jboolean>(isProxy));
      env->DeleteLocalRef(jhost);
      env->DeleteLocalRef(jrealm);
    } else {
      RespondHttpAuth(id, nullptr, nullptr);
    }
    env->DeleteLocalRef(cls);
    return true;
  }

  // Called from nativeRespondHttpAuth. Null user = cancel the challenge.
  void RespondHttpAuth(uint64_t id, const char* user, const char* pass) {
    CefRefPtr<CefAuthCallback> cb;
    {
      base::AutoLock lock(lock_);
      auto it = pending_auth_.find(id);
      if (it == pending_auth_.end()) {
        return;
      }
      cb = it->second;
      pending_auth_.erase(it);
    }
    if (user) {
      cb->Continue(CefString(user), CefString(pass ? pass : ""));
    } else {
      cb->Cancel();
    }
  }

  // FEATURE-063: client-certificate selection (enterprise/kiosk mTLS). Async:
  // marshal the cert subjects to Java, hold callback+list by id; Java responds
  // via nativeSelectClientCertificate(id, index) -> Select(certs[index]), or
  // index<0 to proceed with no client cert.
  bool OnSelectClientCertificate(
      CefRefPtr<CefBrowser> browser,
      bool isProxy,
      const CefString& host,
      int port,
      const X509CertificateList& certificates,
      CefRefPtr<CefSelectClientCertificateCallback> callback) override {
    if (!java_browser_ || certificates.empty()) return false;
    uint64_t id;
    {
      base::AutoLock lock(lock_);
      id = next_cert_id_++;
      pending_cert_[id] = {callback, certificates};
    }
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return false;
    jclass str_cls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(
        static_cast<jsize>(certificates.size()), str_cls, nullptr);
    for (size_t i = 0; i < certificates.size(); ++i) {
      std::string subj;
      if (certificates[i] && certificates[i]->GetSubject()) {
        subj = certificates[i]->GetSubject()->GetDisplayName().ToString();
      }
      jstring s = env->NewStringUTF(subj.c_str());
      env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
      env->DeleteLocalRef(s);
    }
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onSelectClientCertificate",
        "(JZLjava/lang/String;I[Ljava/lang/String;)V");
    bool dispatched = false;
    if (mid) {
      jstring jhost = env->NewStringUTF(host.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, static_cast<jlong>(id), isProxy,
                          jhost, port, arr);
      env->DeleteLocalRef(jhost);
      dispatched = true;
    }
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(str_cls);
    if (!dispatched) {
      base::AutoLock lock(lock_);
      pending_cert_.erase(id);
      return false;
    }
    return true;
  }

  void RespondClientCert(uint64_t id, int index) {
    CefRefPtr<CefSelectClientCertificateCallback> cb;
    X509CertificateList certs;
    {
      base::AutoLock lock(lock_);
      auto it = pending_cert_.find(id);
      if (it == pending_cert_.end()) return;
      cb = it->second.first;
      certs = it->second.second;
      pending_cert_.erase(it);
    }
    if (index >= 0 && index < static_cast<int>(certs.size())) {
      cb->Select(certs[index]);
    } else {
      cb->Select(nullptr);  // proceed with no client certificate
    }
  }

  ~SdkRequestHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) {
        env->DeleteGlobalRef(java_browser_);
      }
    }
  }

  IMPLEMENT_REFCOUNTING(SdkRequestHandler);

 private:
  CefRefPtr<SdkResourceRequestHandler> resource_handler_;
  CefRefPtr<CefMessageRouterBrowserSide> message_router_;
  jobject java_browser_ = nullptr;
  base::Lock lock_;
  std::map<uint64_t, CefRefPtr<CefCallback>> pending_ssl_;
  std::map<uint64_t, CefRefPtr<CefAuthCallback>> pending_auth_;
  std::map<uint64_t, std::pair<CefRefPtr<CefSelectClientCertificateCallback>,
                               X509CertificateList>>
      pending_cert_;
  uint64_t next_ssl_id_ = 1;
  uint64_t next_auth_id_ = 1;
  uint64_t next_cert_id_ = 1;
};

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_REQUEST_H_
