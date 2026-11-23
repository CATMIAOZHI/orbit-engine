// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// FEATURE-023: window.cefriumQuery, the JS -> Java bridge.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_BRIDGE_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_BRIDGE_H_

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

// FEATURE-023 step 4: browser-side handler for window.cefriumQuery. Forwards
// each query to the Java CefriumBrowser (onJsQuery); Java dispatches to the
// app's QueryHandler and resolves via nativeQueryResult -> ResolveQuery.
//
// The Java handler may answer within onQuery or later, from any thread:
// nativeQueryResult posts to the UI thread, where ResolveQuery finds the query
// in pending_ (kept until answered or cancelled) under the browser-data lock.
class CefriumBridgeHandler : public CefMessageRouterBrowserSide::Handler {
 public:
  ~CefriumBridgeHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) {
        env->DeleteGlobalRef(java_browser_);
      }
    }
  }

  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    if (java_browser_) {
      env->DeleteGlobalRef(java_browser_);
    }
    java_browser_ = java_browser ? env->NewGlobalRef(java_browser) : nullptr;
  }

  bool OnQuery(CefRefPtr<CefBrowser> browser,
               CefRefPtr<CefFrame> frame,
               int64_t query_id,
               const CefString& request,
               bool persistent,
               CefRefPtr<Callback> callback) override {
    if (!java_browser_) {
      return false;  // No handler registered -> JS onFailure(-1).
    }
    // The origin of the document that SENT the query, stamped by the renderer
    // at send time. The browser can handle the query after the frame has
    // committed the next document (measured: a parent firing queries from an
    // iframe's initial document saw them judged as the iframe's next page), so
    // the committed origin alone does not identify the sender.
    std::string sender_origin = "null";
    if (frame) {
      auto* host = static_cast<CefFrameHostImpl*>(frame.get());
      if (const auto& sender = host->current_message_sender_origin()) {
        sender_origin = sender->Serialize();
      }
    }
    pending_[query_id] = callback;
    // The query and the commit of the document that sent it come over
    // different pipes, so a query sent while a document loads can arrive
    // before its commit, while the frame still holds the previous document.
    // Judge it once a commit makes the two agree, or as it stands after
    // kCommitWait.
    if (frame && sender_origin != CommittedOrigin(frame)) {
      deferred_[query_id] = {frame, request, sender_origin};
      CefPostDelayedTask(TID_UI,
                         base::BindOnce(&CefriumBridgeHandler::Judge,
                                        weak_factory_.GetWeakPtr(), query_id),
                         kCommitWaitMs);
      return true;
    }
    if (!Dispatch(frame, query_id, request, sender_origin)) {
      pending_.erase(query_id);
      return false;
    }
    return true;
  }

  // A frame committed a document: judge the deferred queries whose frame now
  // holds the document that sent them.
  void OnCommit() {
    std::vector<int64_t> ready;
    for (const auto& [query_id, deferred] : deferred_) {
      if (deferred.sender_origin == CommittedOrigin(deferred.frame)) {
        ready.push_back(query_id);
      }
    }
    for (int64_t query_id : ready) {
      Judge(query_id);
    }
  }

  base::WeakPtr<CefriumBridgeHandler> GetWeakPtr() {
    return weak_factory_.GetWeakPtr();
  }

 private:
  struct Deferred {
    CefRefPtr<CefFrame> frame;
    CefString request;
    std::string sender_origin;
  };

  static constexpr int64_t kCommitWaitMs = 1000;

  static std::string CommittedOrigin(CefRefPtr<CefFrame> frame) {
    auto* rfh = static_cast<CefFrameHostImpl*>(frame.get())->GetRenderFrameHost();
    return rfh ? rfh->GetLastCommittedOrigin().Serialize() : "null";
  }

  void Judge(int64_t query_id) {
    auto it = deferred_.find(query_id);
    if (it == deferred_.end()) {
      return;
    }
    Deferred deferred = std::move(it->second);
    deferred_.erase(it);
    if (!Dispatch(deferred.frame, query_id, deferred.request,
                  deferred.sender_origin)) {
      ResolveQuery(query_id, false, CefString(), -1,
                   "No query handler registered");
    }
  }

  // Hands the query to Java with the frame's current state. False when it
  // could not, and the caller answers the query.
  bool Dispatch(CefRefPtr<CefFrame> frame,
                int64_t query_id,
                const CefString& request,
                const std::string& sender_origin) {
    if (!java_browser_) {
      return false;
    }
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) {
      return false;
    }
    // The frame's URL is the origin of the query — passed to Java so the app
    // can gate which pages/iframes are allowed to use the bridge (security).
    std::string origin = frame ? frame->GetURL().ToString() : std::string();
    // The committed origin as well: a sandboxed frame at an allowed URL has an
    // opaque origin, and Java denies it. A detached frame counts as opaque.
    std::string committed_origin = "null";
    // The main frame of the caller's own frame tree. Java admits a subframe
    // only when the policy opted into subframes and allows this main frame
    // too: an allowed page framed by a foreign one must not reach the handler.
    bool is_main = false;
    std::string main_url;
    std::string main_committed_origin = "null";
    if (frame) {
      auto* host = static_cast<CefFrameHostImpl*>(frame.get());
      if (auto* rfh = host->GetRenderFrameHost()) {
        committed_origin = rfh->GetLastCommittedOrigin().Serialize();
        is_main = frame->IsMain();
        content::RenderFrameHost* main = rfh->GetMainFrame();
        main_url = main->GetLastCommittedURL().spec();
        main_committed_origin = main->GetLastCommittedOrigin().Serialize();
      }
    }
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(
        cls, "onJsQuery",
        "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        "Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;)V");
    if (method) {
      jstring j_req = env->NewStringUTF(request.ToString().c_str());
      jstring j_origin = env->NewStringUTF(origin.c_str());
      jstring j_committed = env->NewStringUTF(committed_origin.c_str());
      jstring j_sender = env->NewStringUTF(sender_origin.c_str());
      jstring j_main_url = env->NewStringUTF(main_url.c_str());
      jstring j_main_committed = env->NewStringUTF(main_committed_origin.c_str());
      env->CallVoidMethod(java_browser_, method, static_cast<jlong>(query_id),
                          j_req, j_origin, j_committed, j_sender,
                          static_cast<jboolean>(is_main), j_main_url,
                          j_main_committed);
      env->DeleteLocalRef(j_req);
      env->DeleteLocalRef(j_origin);
      env->DeleteLocalRef(j_committed);
      env->DeleteLocalRef(j_sender);
      env->DeleteLocalRef(j_main_url);
      env->DeleteLocalRef(j_main_committed);
    } else {
      // A missing method would leave the query pending forever.
      env->ExceptionClear();
      env->DeleteLocalRef(cls);
      return false;
    }
    env->DeleteLocalRef(cls);
    return true;
  }

 public:
  void OnQueryCanceled(CefRefPtr<CefBrowser> browser,
                       CefRefPtr<CefFrame> frame,
                       int64_t query_id) override {
    deferred_.erase(query_id);
    pending_.erase(query_id);
  }

  // Resolves a query. Always invoked on the browser UI thread (nativeQueryResult
  // posts here), so the pending map and the Callback are touched UI-thread-only.
  void ResolveQuery(int64_t query_id,
                    bool success,
                    const CefString& response,
                    int error_code,
                    const CefString& error_message) {
    auto it = pending_.find(query_id);
    if (it == pending_.end()) {
      return;
    }
    CefRefPtr<Callback> callback = it->second;
    pending_.erase(it);
    if (success) {
      callback->Success(response);
    } else {
      callback->Failure(error_code, error_message);
    }
  }

 private:
  jobject java_browser_ = nullptr;
  std::map<int64_t, CefRefPtr<Callback>> pending_;
  std::map<int64_t, Deferred> deferred_;
  base::WeakPtrFactory<CefriumBridgeHandler> weak_factory_{this};
};

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_BRIDGE_H_
