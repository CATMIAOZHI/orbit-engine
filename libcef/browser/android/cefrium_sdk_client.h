// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// The CefClient of a CefriumBrowser: owns one of each handler.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_CLIENT_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_CLIENT_H_

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
#include "cef/libcef/browser/android/cefrium_sdk_handlers.h"
#include "cef/libcef/browser/android/cefrium_sdk_request.h"
#include "cef/libcef/browser/android/cefrium_sdk_bridge.h"
#include "cef/libcef/browser/android/cefrium_sdk_dialogs.h"

class SdkClient : public CefClient {
 public:
  SdkClient(CefRefPtr<SdkRenderHandler> render_handler,
            CefRefPtr<SdkLifeSpanHandler> life_span_handler,
            bool with_request_handler = false)
      : render_handler_(render_handler),
        life_span_handler_(life_span_handler),
        load_handler_(new SdkLoadHandler()),
        display_handler_(new SdkDisplayHandler()),
        download_handler_(new SdkDownloadHandler()),
        context_menu_handler_(new SdkContextMenuHandler()),
        find_handler_(new SdkFindHandler()),
        js_dialog_handler_(new SdkJSDialogHandler()),
        permission_handler_(new SdkPermissionHandler()),
        device_chooser_handler_(new SdkDeviceChooserHandler()),
        identity_handler_(new SdkIdentityHandler()),
        keyboard_handler_(new SdkKeyboardHandler()),
        focus_handler_(new SdkFocusHandler()),
        drag_handler_(new SdkDragHandler()),
        audio_handler_(new SdkAudioHandler()),
        frame_handler_(new SdkFrameHandler()),
        command_handler_(new SdkCommandHandler()),
        dialog_handler_(new SdkDialogHandler()),
        request_handler_(with_request_handler ? new SdkRequestHandler() : nullptr) {
    {
      base::AutoLock lock(LiveLock());
      Live().insert(this);
    }
    // FEATURE-023: browser-side JS bridge router for window.cefriumQuery. The
    // config MUST match the renderer side (shared constants).
    CefMessageRouterConfig config;
    config.js_query_function = cefrium_bridge::kQueryFunction;
    config.js_cancel_function = cefrium_bridge::kCancelFunction;
    message_router_ = CefMessageRouterBrowserSide::Create(config);
    bridge_handler_ = std::make_unique<CefriumBridgeHandler>();
    message_router_->AddHandler(bridge_handler_.get(), /*first=*/false);
    load_handler_->SetOnCommit(base::BindRepeating(
        &CefriumBridgeHandler::OnCommit, bridge_handler_->GetWeakPtr()));
    if (life_span_handler_) {
      life_span_handler_->SetMessageRouter(message_router_);
    }
    if (request_handler_) {
      request_handler_->SetMessageRouter(message_router_);
    }
  }

  // FEATURE-023: deliver renderer->browser messages to the bridge router.
  bool OnProcessMessageReceived(CefRefPtr<CefBrowser> browser,
                                CefRefPtr<CefFrame> frame,
                                CefProcessId source_process,
                                CefRefPtr<CefProcessMessage> message) override {
    // FEATURE-062 follow-up (issue #9): focused-node value/placeholder/type from
    // the renderer -> Java, for OSR native-overlay consumers.
    if (message->GetName() == cefrium_bridge::kFocusNodeMessage) {
      if (java_browser_) {
        CefRefPtr<CefListValue> args = message->GetArgumentList();
        bool editable = args->GetBool(0);
        std::string value = args->GetString(1).ToString();
        std::string placeholder = args->GetString(2).ToString();
        std::string type = args->GetString(3).ToString();
        JNIEnv* env = base::android::AttachCurrentThread();
        if (env) {
          jclass cls = env->GetObjectClass(java_browser_);
          jmethodID mid = env->GetMethodID(cls, "onFocusedNodeChanged",
              "(ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
          if (mid) {
            jstring jv = env->NewStringUTF(value.c_str());
            jstring jp = env->NewStringUTF(placeholder.c_str());
            jstring jt = env->NewStringUTF(type.c_str());
            env->CallVoidMethod(java_browser_, mid, editable, jv, jp, jt);
            env->DeleteLocalRef(jv);
            env->DeleteLocalRef(jp);
            env->DeleteLocalRef(jt);
          }
          env->DeleteLocalRef(cls);
        }
      }
      return true;
    }
    if (message_router_) {
      return message_router_->OnProcessMessageReceived(browser, frame,
                                                       source_process, message);
    }
    return false;
  }

  CefRefPtr<CefRenderHandler> GetRenderHandler() override {
    return render_handler_;
  }

  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override {
    return life_span_handler_;
  }

  CefRefPtr<CefLoadHandler> GetLoadHandler() override {
    return load_handler_;
  }

  CefRefPtr<CefDisplayHandler> GetDisplayHandler() override {
    return display_handler_;
  }

  CefRefPtr<CefRequestHandler> GetRequestHandler() override {
    return request_handler_;
  }

  CefRefPtr<CefDownloadHandler> GetDownloadHandler() override {
    return download_handler_;
  }

  CefRefPtr<CefContextMenuHandler> GetContextMenuHandler() override {
    return context_menu_handler_;
  }

  CefRefPtr<CefFindHandler> GetFindHandler() override {
    return find_handler_;
  }

  CefRefPtr<CefJSDialogHandler> GetJSDialogHandler() override {
    return js_dialog_handler_;
  }

  CefRefPtr<CefPermissionHandler> GetPermissionHandler() override {
    return permission_handler_;
  }

  CefRefPtr<CefDeviceChooserHandler> GetDeviceChooserHandler() override {
    return device_chooser_handler_;
  }

  CefRefPtr<CefIdentityRequestHandler> GetIdentityRequestHandler() override {
    return identity_handler_;
  }

  // FEATURE-063 handler registrations.
  CefRefPtr<CefKeyboardHandler> GetKeyboardHandler() override {
    return keyboard_handler_;
  }
  CefRefPtr<CefFocusHandler> GetFocusHandler() override {
    return focus_handler_;
  }
  CefRefPtr<CefDragHandler> GetDragHandler() override {
    return drag_handler_;
  }
  CefRefPtr<CefFrameHandler> GetFrameHandler() override {
    return frame_handler_;
  }
  CefRefPtr<CefCommandHandler> GetCommandHandler() override {
    return command_handler_;
  }
  CefRefPtr<CefDialogHandler> GetDialogHandler() override {
    return dialog_handler_;
  }
  // OPT-IN: returning an audio handler REDIRECTS page audio to the tap (it stops
  // playing normally), so only expose it once the consumer enabled it.
  CefRefPtr<CefAudioHandler> GetAudioHandler() override {
    return audio_enabled_ ? audio_handler_ : nullptr;
  }
  void SetAudioCaptureEnabled(bool enabled) { audio_enabled_ = enabled; }

  CefRefPtr<SdkPermissionHandler> permission_handler() {
    return permission_handler_;
  }
  CefRefPtr<SdkDeviceChooserHandler> device_chooser_handler() {
    return device_chooser_handler_;
  }
  CefRefPtr<SdkIdentityHandler> identity_handler() { return identity_handler_; }

  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb && !java_browser_) {
      java_browser_ = env->NewGlobalRef(jb);
    }
  }

  // FEATURE: Web Share (navigator.share) -> the app's Android share sheet.
  // Returns false if there is no Java browser / activity to share from.
  bool FireWebShare(const std::string& title,
                    const std::string& text,
                    const std::string& url) {
    if (!java_browser_) {
      return false;
    }
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(
        cls, "onWebShare",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
    bool ok = false;
    if (mid) {
      jstring jt = env->NewStringUTF(title.c_str());
      jstring jx = env->NewStringUTF(text.c_str());
      jstring ju = env->NewStringUTF(url.c_str());
      ok = env->CallBooleanMethod(java_browser_, mid, jt, jx, ju) == JNI_TRUE;
      env->DeleteLocalRef(jt);
      env->DeleteLocalRef(jx);
      env->DeleteLocalRef(ju);
    }
    env->DeleteLocalRef(cls);
    return ok;
  }
  CefRefPtr<SdkContextMenuHandler> context_menu_handler() {
    return context_menu_handler_;
  }
  CefRefPtr<SdkRequestHandler> request_handler() { return request_handler_; }
  CefRefPtr<SdkJSDialogHandler> js_dialog_handler() { return js_dialog_handler_; }
  CefRefPtr<SdkDisplayHandler> display_handler() { return display_handler_; }
  CefRefPtr<SdkLoadHandler> load_handler_ref() { return load_handler_; }
  CefRefPtr<SdkDownloadHandler> download_handler() { return download_handler_; }
  CefRefPtr<SdkFindHandler> find_handler() { return find_handler_; }
  CefRefPtr<SdkKeyboardHandler> keyboard_handler() { return keyboard_handler_; }
  CefRefPtr<SdkFocusHandler> focus_handler() { return focus_handler_; }
  CefRefPtr<SdkDragHandler> drag_handler() { return drag_handler_; }
  CefRefPtr<SdkAudioHandler> audio_handler() { return audio_handler_; }
  CefRefPtr<SdkFrameHandler> frame_handler() { return frame_handler_; }
  CefRefPtr<SdkCommandHandler> command_handler() { return command_handler_; }
  CefRefPtr<SdkDialogHandler> dialog_handler() { return dialog_handler_; }
  CefriumBridgeHandler* bridge_handler() { return bridge_handler_.get(); }

  // The Java CefriumBrowser of a WebContents, or null if it is not one of
  // this SDK's browsers.
  static jobject JavaBrowserFor(content::WebContents* web_contents) {
    auto browser = CefBrowserHostBase::GetBrowserForContents(web_contents);
    if (!browser) {
      return nullptr;
    }
    CefRefPtr<CefClient> client = browser->GetClient();
    base::AutoLock lock(LiveLock());
    if (!client || !Live().contains(static_cast<SdkClient*>(client.get()))) {
      return nullptr;
    }
    return static_cast<SdkClient*>(client.get())->java_browser_;
  }

  ~SdkClient() override {
    {
      base::AutoLock lock(LiveLock());
      Live().erase(this);
    }
    if (java_browser_) {
      if (JNIEnv* env = base::android::AttachCurrentThread()) {
        env->DeleteGlobalRef(java_browser_);
      }
    }
  }

  IMPLEMENT_REFCOUNTING(SdkClient);

 private:
  // Clients alive, so JavaBrowserFor can tell its own clients from any other
  // CefClient before casting. A client may be released off the UI thread.
  static std::set<SdkClient*>& Live() {
    static base::NoDestructor<std::set<SdkClient*>> live;
    return *live;
  }
  static base::Lock& LiveLock() {
    static base::NoDestructor<base::Lock> lock;
    return *lock;
  }

  CefRefPtr<SdkRenderHandler> render_handler_;
  CefRefPtr<SdkLifeSpanHandler> life_span_handler_;
  CefRefPtr<SdkLoadHandler> load_handler_;
  CefRefPtr<SdkDisplayHandler> display_handler_;
  CefRefPtr<SdkDownloadHandler> download_handler_;
  CefRefPtr<SdkContextMenuHandler> context_menu_handler_;
  CefRefPtr<SdkFindHandler> find_handler_;
  CefRefPtr<SdkJSDialogHandler> js_dialog_handler_;
  CefRefPtr<SdkPermissionHandler> permission_handler_;
  CefRefPtr<SdkDeviceChooserHandler> device_chooser_handler_;
  CefRefPtr<SdkIdentityHandler> identity_handler_;
  CefRefPtr<SdkKeyboardHandler> keyboard_handler_;
  CefRefPtr<SdkFocusHandler> focus_handler_;
  CefRefPtr<SdkDragHandler> drag_handler_;
  CefRefPtr<SdkAudioHandler> audio_handler_;
  CefRefPtr<SdkFrameHandler> frame_handler_;
  CefRefPtr<SdkCommandHandler> command_handler_;
  CefRefPtr<SdkDialogHandler> dialog_handler_;
  bool audio_enabled_ = false;
  CefRefPtr<SdkRequestHandler> request_handler_;
  // Declared last so the router is destroyed before the handler it references.
  std::unique_ptr<CefriumBridgeHandler> bridge_handler_;
  CefRefPtr<CefMessageRouterBrowserSide> message_router_;
  jobject java_browser_ = nullptr;  // global ref; for Web Share routing.
};

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_CLIENT_H_
