// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// What a page asks the user: JS dialogs, permissions, device choosers,
// the file chooser and, off-screen, the pickers.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_DIALOGS_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_DIALOGS_H_

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

// FEATURE-028: JS dialogs (alert/confirm/prompt) -> Java (custom handler or
// default Android AlertDialog). Always registered so alert() etc. work.
class SdkJSDialogHandler : public CefJSDialogHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb) java_browser_ = env->NewGlobalRef(jb);
  }

  bool OnJSDialog(CefRefPtr<CefBrowser> browser,
                  const CefString& origin_url,
                  JSDialogType dialog_type,
                  const CefString& message_text,
                  const CefString& default_prompt_text,
                  CefRefPtr<CefJSDialogCallback> callback,
                  bool& suppress_message) override {
    if (!java_browser_) return false;  // fall back to CEF default
    jlong token;
    {
      base::AutoLock l(lock_);
      token = next_token_++;
      pending_[token] = callback;
    }
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) {
      base::AutoLock l(lock_);
      pending_.erase(token);
      return false;
    }
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(
        cls, "onJsDialog", "(IJLjava/lang/String;Ljava/lang/String;)V");
    bool dispatched = false;
    if (mid) {
      jstring jmsg = env->NewStringUTF(message_text.ToString().c_str());
      jstring jdef = env->NewStringUTF(default_prompt_text.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, static_cast<jint>(dialog_type),
                          token, jmsg, jdef);
      env->DeleteLocalRef(jmsg);
      env->DeleteLocalRef(jdef);
      dispatched = true;
    }
    env->DeleteLocalRef(cls);
    if (!dispatched) {
      base::AutoLock l(lock_);
      pending_.erase(token);
      return false;
    }
    return true;  // handled asynchronously
  }

  // FEATURE-063: beforeunload "Leave site?" prompt for dirty forms. Same async
  // pending_/Resolve mechanism; success == leave. WebView onJsBeforeUnload.
  bool OnBeforeUnloadDialog(CefRefPtr<CefBrowser> browser,
                            const CefString& message_text,
                            bool is_reload,
                            CefRefPtr<CefJSDialogCallback> callback) override {
    if (!java_browser_) return false;
    jlong token;
    {
      base::AutoLock l(lock_);
      token = next_token_++;
      pending_[token] = callback;
    }
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) {
      base::AutoLock l(lock_);
      pending_.erase(token);
      return false;
    }
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onBeforeUnloadDialog",
                                     "(JLjava/lang/String;Z)V");
    bool dispatched = false;
    if (mid) {
      jstring jmsg = env->NewStringUTF(message_text.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, token, jmsg, is_reload);
      env->DeleteLocalRef(jmsg);
      dispatched = true;
    }
    env->DeleteLocalRef(cls);
    if (!dispatched) {
      base::AutoLock l(lock_);
      pending_.erase(token);
      return false;
    }
    return true;
  }

  // FEATURE-063: dialog lifecycle notifications.
  void OnResetDialogState(CefRefPtr<CefBrowser> browser) override {
    NotifyNoArg("onDialogResetState");
  }
  void OnDialogClosed(CefRefPtr<CefBrowser> browser) override {
    NotifyNoArg("onDialogClosed");
  }
  void NotifyNoArg(const char* method_name) {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, method_name, "()V");
    if (mid) env->CallVoidMethod(java_browser_, mid);
    env->DeleteLocalRef(cls);
  }

  void Resolve(jlong token, bool success, const CefString& input) {
    CefRefPtr<CefJSDialogCallback> cb;
    {
      base::AutoLock l(lock_);
      auto it = pending_.find(token);
      if (it != pending_.end()) {
        cb = it->second;
        pending_.erase(it);
      }
    }
    if (cb) cb->Continue(success, input);
  }

  ~SdkJSDialogHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }

  IMPLEMENT_REFCOUNTING(SdkJSDialogHandler);

 private:
  jobject java_browser_ = nullptr;
  base::Lock lock_;
  std::map<jlong, CefRefPtr<CefJSDialogCallback>> pending_;
  jlong next_token_ = 1;
};

// FEATURE-032: routes permission prompts (e.g. geolocation) to Java. On Android
// this MUST always handle the prompt (return true) so requests never fall
// through to Chromium's default Android Java permission dialog, which is not
// wired in CEF-Android and crashes (PermissionDialogJavaDelegate::CreateDialog).
// FEATURE-092: Cefrium's permission bits (CefriumBrowser.PERMISSION_* and
// CefriumPermission), owned by Cefrium and kept at their published values.
// CEF's bits are mapped to them by name, so a renumbering in CEF (NFC moved
// from bit 29 to 30 in 8078) cannot reach apps.
constexpr uint32_t kCefriumPermissionCamera = 1u << 2;
constexpr uint32_t kCefriumPermissionClipboard = 1u << 4;
constexpr uint32_t kCefriumPermissionGeolocation = 1u << 8;
constexpr uint32_t kCefriumPermissionMicrophone = 1u << 12;
constexpr uint32_t kCefriumPermissionNotifications = 1u << 15;
constexpr uint32_t kCefriumPermissionNfc = 1u << 30;

// The Cefrium bits of CEF's |cef| mask; |unmapped| gets the CEF bits that
// have no Cefrium value.
inline uint32_t CefriumPermissionBits(uint32_t cef, uint32_t* unmapped) {
  struct Pair {
    uint32_t cef;
    uint32_t cefrium;
  };
  static constexpr Pair kPairs[] = {
      {CEF_PERMISSION_TYPE_CAMERA_STREAM, kCefriumPermissionCamera},
      {CEF_PERMISSION_TYPE_CLIPBOARD, kCefriumPermissionClipboard},
      {CEF_PERMISSION_TYPE_GEOLOCATION, kCefriumPermissionGeolocation},
      {CEF_PERMISSION_TYPE_MIC_STREAM, kCefriumPermissionMicrophone},
      {CEF_PERMISSION_TYPE_NOTIFICATIONS, kCefriumPermissionNotifications},
      {CEF_PERMISSION_TYPE_NFC, kCefriumPermissionNfc},
  };
  uint32_t out = 0;
  uint32_t rest = cef;
  for (const Pair& p : kPairs) {
    if (cef & p.cef) {
      out |= p.cefrium;
      rest &= ~p.cef;
    }
  }
  *unmapped = rest;
  return out;
}

class SdkPermissionHandler : public CefPermissionHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb) {
      java_browser_ = env->NewGlobalRef(jb);
    }
  }

  bool OnShowPermissionPrompt(
      CefRefPtr<CefBrowser> browser,
      uint64_t prompt_id,
      const CefString& requesting_origin,
      uint32_t requested_permissions,
      CefRefPtr<CefPermissionPromptCallback> callback) override {
    if (!java_browser_) {
      // No Java browser wired: deny by default. Never proceed to default
      // (crashing) Android handling.
      callback->Continue(CEF_PERMISSION_RESULT_DENY);
      return true;
    }
    {
      base::AutoLock lock(lock_);
      pending_[prompt_id] = callback;
    }
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onPermissionRequest",
                                     "(JLjava/lang/String;II)V");
    if (mid) {
      jstring jorigin =
          env->NewStringUTF(requesting_origin.ToString().c_str());
      uint32_t unmapped = 0;
      const uint32_t bits =
          CefriumPermissionBits(requested_permissions, &unmapped);
      env->CallVoidMethod(java_browser_, mid, static_cast<jlong>(prompt_id),
                          jorigin, static_cast<jint>(bits),
                          static_cast<jint>(unmapped));
      env->DeleteLocalRef(jorigin);
    } else {
      Respond(prompt_id, false);
    }
    env->DeleteLocalRef(cls);
    return true;
  }

  // FEATURE-032/F2: getUserMedia (camera/mic) routes through a separate CEF
  // handler method. Bridge it to the same Java onPermissionRequest, translating
  // the media bitmask to the camera/mic permission constants.
  bool OnRequestMediaAccessPermission(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      const CefString& requesting_origin,
      uint32_t requested_permissions,
      CefRefPtr<CefMediaAccessCallback> callback) override {
    if (!java_browser_) {
      callback->Continue(CEF_MEDIA_PERMISSION_NONE);  // deny
      return true;
    }
    uint64_t id;
    {
      base::AutoLock lock(lock_);
      // Namespace media ids in the high bit so they can't collide with the
      // CEF-assigned prompt ids used by pending_.
      id = (1ULL << 63) | (next_media_id_++);
      pending_media_[id] = std::make_pair(callback, requested_permissions);
    }
    uint32_t java_mask = 0;
    if (requested_permissions & CEF_MEDIA_PERMISSION_DEVICE_VIDEO_CAPTURE) {
      java_mask |= kCefriumPermissionCamera;
    }
    if (requested_permissions & CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE) {
      java_mask |= kCefriumPermissionMicrophone;
    }
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onPermissionRequest",
                                     "(JLjava/lang/String;II)V");
    if (mid) {
      jstring jorigin =
          env->NewStringUTF(requesting_origin.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, static_cast<jlong>(id), jorigin,
                          static_cast<jint>(java_mask), 0);
      env->DeleteLocalRef(jorigin);
    } else {
      Respond(id, false);
    }
    env->DeleteLocalRef(cls);
    return true;
  }

  // Called from nativeRespondPermission once Java decides. Handles both prompt
  // and media-access requests (distinguished by the id namespace).
  void Respond(uint64_t id, bool grant) {
    CefRefPtr<CefPermissionPromptCallback> promptCb;
    CefRefPtr<CefMediaAccessCallback> mediaCb;
    uint32_t mediaRequested = 0;
    {
      base::AutoLock lock(lock_);
      auto mit = pending_media_.find(id);
      if (mit != pending_media_.end()) {
        mediaCb = mit->second.first;
        mediaRequested = mit->second.second;
        pending_media_.erase(mit);
      } else {
        auto it = pending_.find(id);
        if (it == pending_.end()) {
          return;
        }
        promptCb = it->second;
        pending_.erase(it);
      }
    }
    if (mediaCb) {
      mediaCb->Continue(grant ? mediaRequested : CEF_MEDIA_PERMISSION_NONE);
    } else if (promptCb) {
      promptCb->Continue(grant ? CEF_PERMISSION_RESULT_ACCEPT
                               : CEF_PERMISSION_RESULT_DENY);
    }
  }

  // FEATURE-063: a pending prompt was auto-dismissed (page navigated away).
  // Let Java tear down any prompt UI so it does not leak/stick.
  void OnDismissPermissionPrompt(
      CefRefPtr<CefBrowser> browser,
      uint64_t prompt_id,
      cef_permission_request_result_t result) override {
    {
      base::AutoLock l(lock_);
      pending_.erase(prompt_id);
    }
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onDismissPermissionPrompt", "(JI)V");
    if (mid) {
      env->CallVoidMethod(java_browser_, mid, static_cast<jlong>(prompt_id),
                          static_cast<jint>(result));
    }
    env->DeleteLocalRef(cls);
  }

  ~SdkPermissionHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) {
        env->DeleteGlobalRef(java_browser_);
      }
    }
  }

 private:
  jobject java_browser_ = nullptr;
  base::Lock lock_;
  std::map<uint64_t, CefRefPtr<CefPermissionPromptCallback>> pending_;
  std::map<uint64_t, std::pair<CefRefPtr<CefMediaAccessCallback>, uint32_t>>
      pending_media_;
  uint64_t next_media_id_ = 1;
  IMPLEMENT_REFCOUNTING(SdkPermissionHandler);
};

// FEATURE-053: routes the frontier device choosers (Web Bluetooth/USB/Serial)
// to the Java app. OnShow/OnDeviceAdded/OnDeviceChooserClosed all run on the CEF
// UI thread; the same CefDeviceChooserCallback instance is handed back on each
// call, so it is correlated with a Java-visible long id (assigned on show).
class SdkDeviceChooserHandler : public CefDeviceChooserHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb) {
      java_browser_ = env->NewGlobalRef(jb);
    }
  }

  // Toggled by CefriumBrowser.setDeviceChooserHandler(): when the app has no
  // handler the request is cancelled (the page promise rejects) rather than
  // hanging on a picker that will never appear.
  void SetEnabled(bool enabled) { enabled_ = enabled; }

  bool OnShowDeviceChooser(CefRefPtr<CefBrowser> browser,
                           CefRefPtr<CefFrame> frame,
                           DeviceChooserType type,
                           const CefString& requesting_origin,
                           CefRefPtr<CefDeviceChooserCallback> callback) override {
    if (!java_browser_ || !enabled_) {
      return false;  // cancels the request
    }
    uint64_t id;
    {
      base::AutoLock lock(lock_);
      id = next_id_++;
      pending_[id] = callback;
      by_callback_[callback.get()] = id;
    }
    LOGI("DeviceChooser: OnShow type=%d origin=%s id=%llu",
         static_cast<int>(type), requesting_origin.ToString().c_str(),
         (unsigned long long)id);
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onDeviceChooserShow",
                                     "(JILjava/lang/String;)V");
    if (mid) {
      jstring jorigin =
          env->NewStringUTF(requesting_origin.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, static_cast<jlong>(id),
                          static_cast<jint>(type), jorigin);
      env->DeleteLocalRef(jorigin);
    } else {
      Erase(id);
      env->DeleteLocalRef(cls);
      return false;
    }
    env->DeleteLocalRef(cls);
    return true;
  }

  void OnDeviceAdded(CefRefPtr<CefBrowser> browser,
                     CefRefPtr<CefDeviceChooserCallback> callback,
                     const CefString& device_id,
                     const CefString& device_name) override {
    if (!java_browser_) {
      return;
    }
    uint64_t id;
    {
      base::AutoLock lock(lock_);
      auto it = by_callback_.find(callback.get());
      if (it == by_callback_.end()) {
        return;
      }
      id = it->second;
    }
    LOGI("DeviceChooser: OnDeviceAdded id=%llu device_id='%s' name='%s'",
         (unsigned long long)id, device_id.ToString().c_str(),
         device_name.ToString().c_str());
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid =
        env->GetMethodID(cls, "onDeviceChooserDeviceAdded",
                         "(JLjava/lang/String;Ljava/lang/String;)V");
    if (mid) {
      jstring jid = env->NewStringUTF(device_id.ToString().c_str());
      jstring jname = env->NewStringUTF(device_name.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, static_cast<jlong>(id), jid,
                          jname);
      env->DeleteLocalRef(jid);
      env->DeleteLocalRef(jname);
    }
    env->DeleteLocalRef(cls);
  }

  void OnDeviceRemoved(CefRefPtr<CefBrowser> browser,
                       CefRefPtr<CefDeviceChooserCallback> callback,
                       const CefString& device_id) override {
    if (!java_browser_) {
      return;
    }
    uint64_t id;
    {
      base::AutoLock lock(lock_);
      auto it = by_callback_.find(callback.get());
      if (it == by_callback_.end()) {
        return;
      }
      id = it->second;
    }
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onDeviceChooserDeviceRemoved",
                                     "(JLjava/lang/String;)V");
    if (mid) {
      jstring jid = env->NewStringUTF(device_id.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, static_cast<jlong>(id), jid);
      env->DeleteLocalRef(jid);
    }
    env->DeleteLocalRef(cls);
  }

  void OnDeviceChooserClosed(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefDeviceChooserCallback> callback) override {
    uint64_t id;
    {
      base::AutoLock lock(lock_);
      auto it = by_callback_.find(callback.get());
      if (it == by_callback_.end()) {
        return;
      }
      id = it->second;
      pending_.erase(id);
      by_callback_.erase(it);
    }
    if (!java_browser_) {
      return;
    }
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onDeviceChooserClosed", "(J)V");
    if (mid) {
      env->CallVoidMethod(java_browser_, mid, static_cast<jlong>(id));
    }
    env->DeleteLocalRef(cls);
  }

  // Called from nativeDeviceChooserSelect once the app's picker resolves.
  void Select(uint64_t id, const CefString& device_id) {
    CefRefPtr<CefDeviceChooserCallback> cb = Find(id);
    if (cb) {
      cb->Select(device_id);
    }
  }

  // Called from nativeDeviceChooserCancel when the app dismisses the picker.
  void Cancel(uint64_t id) {
    CefRefPtr<CefDeviceChooserCallback> cb = Find(id);
    if (cb) {
      cb->Cancel();
    }
  }

  ~SdkDeviceChooserHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) {
        env->DeleteGlobalRef(java_browser_);
      }
    }
  }

 private:
  CefRefPtr<CefDeviceChooserCallback> Find(uint64_t id) {
    base::AutoLock lock(lock_);
    auto it = pending_.find(id);
    return it == pending_.end() ? nullptr : it->second;
  }
  void Erase(uint64_t id) {
    base::AutoLock lock(lock_);
    auto it = pending_.find(id);
    if (it != pending_.end()) {
      by_callback_.erase(it->second.get());
      pending_.erase(it);
    }
  }

  jobject java_browser_ = nullptr;
  bool enabled_ = false;
  base::Lock lock_;
  uint64_t next_id_ = 1;
  std::map<uint64_t, CefRefPtr<CefDeviceChooserCallback>> pending_;
  std::map<CefDeviceChooserCallback*, uint64_t> by_callback_;
  IMPLEMENT_REFCOUNTING(SdkDeviceChooserHandler);
};

// FEATURE-063: file dialog handler -- <input type=file> / capture picker.
// WebView onShowFileChooser. Async: hold the callback by id, up-call Java to
// launch an Intent (ACTION_GET_CONTENT / OPEN_DOCUMENT), Java returns selected
// paths via nativeFileDialogResult(id, paths) -> Continue()/Cancel().
class SdkDialogHandler : public CefDialogHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb) java_browser_ = env->NewGlobalRef(jb);
  }
  bool OnFileDialog(CefRefPtr<CefBrowser> browser,
                    FileDialogMode mode,
                    const CefString& title,
                    const CefString& default_file_path,
                    const std::vector<CefString>& accept_filters,
                    const std::vector<CefString>& accept_extensions,
                    const std::vector<CefString>& accept_descriptions,
                    CefRefPtr<CefFileDialogCallback> callback) override {
    if (!java_browser_) return false;  // fall back to CEF default (none)
    jlong id;
    {
      base::AutoLock l(lock_);
      id = next_id_++;
      pending_[id] = callback;
    }
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) {
      base::AutoLock l(lock_);
      pending_.erase(id);
      return false;
    }
    jclass str_cls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(
        static_cast<jsize>(accept_filters.size()), str_cls, nullptr);
    for (size_t i = 0; i < accept_filters.size(); ++i) {
      jstring s = env->NewStringUTF(accept_filters[i].ToString().c_str());
      env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
      env->DeleteLocalRef(s);
    }
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(
        cls, "onFileDialog", "(JILjava/lang/String;[Ljava/lang/String;)Z");
    bool dispatched = false;
    if (mid) {
      jstring j_title = env->NewStringUTF(title.ToString().c_str());
      // False: Java leaves it to the engine's own chooser (a surface browser
      // with no app handler), which needs the browser's WindowAndroid.
      dispatched = env->CallBooleanMethod(java_browser_, mid, id,
                                          static_cast<jint>(mode), j_title,
                                          arr) == JNI_TRUE;
      if (env->ExceptionCheck()) {
        env->ExceptionClear();
        dispatched = false;
      }
      env->DeleteLocalRef(j_title);
    }
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(str_cls);
    if (!dispatched) {
      {
        base::AutoLock l(lock_);
        pending_.erase(id);
      }
      // The engine's chooser needs a WindowAndroid, which an off-screen
      // browser does not have (it would dereference a null view).
      if (browser->GetHost()->IsWindowRenderingDisabled()) {
        callback->Cancel();
        return true;
      }
      return false;
    }
    return true;
  }
  // Called from nativeFileDialogResult; empty paths => Cancel.
  void Resolve(jlong id, const std::vector<CefString>& paths) {
    CefRefPtr<CefFileDialogCallback> cb;
    {
      base::AutoLock l(lock_);
      auto it = pending_.find(id);
      if (it == pending_.end()) return;
      cb = it->second;
      pending_.erase(it);
    }
    if (paths.empty()) {
      cb->Cancel();
    } else {
      cb->Continue(paths);
    }
  }
  ~SdkDialogHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }
  IMPLEMENT_REFCOUNTING(SdkDialogHandler);
 private:
  jobject java_browser_ = nullptr;
  base::Lock lock_;
  std::map<jlong, CefRefPtr<CefFileDialogCallback>> pending_;
  jlong next_id_ = 1;
};

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_DIALOGS_H_
