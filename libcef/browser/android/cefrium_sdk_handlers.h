// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// The page handlers of a CefriumBrowser: rendering, life span, load,
// display, downloads, context menu, find, keyboard, focus, drag, audio,
// frames and commands.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_HANDLERS_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_HANDLERS_H_

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

// Per-browser render handler that forwards OnPaint to Java.
class SdkRenderHandler : public CefRenderHandler {
 public:
  SdkRenderHandler(int width, int height, float scale)
      : width_(width), height_(height), scale_(scale) {}

  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    java_browser_ = env->NewGlobalRef(java_browser);
  }

  // Physical pixels, as passed to the constructor.
  void SetSize(int width, int height) {
    width_ = width;
    height_ = height;
  }

  void GetViewRect(CefRefPtr<CefBrowser> browser, CefRect& rect) override {
    // Report CSS pixel dimensions (physical / scale) so pages use
    // mobile-width media queries (e.g., 1080/2.75 ≈ 393 CSS px).
    rect = CefRect(0, 0, static_cast<int>(width_ / scale_),
                   static_cast<int>(height_ / scale_));
  }

  bool GetScreenInfo(CefRefPtr<CefBrowser> browser,
                     CefScreenInfo& screen_info) override {
    screen_info.device_scale_factor = scale_;
    screen_info.depth = 32;
    screen_info.depth_per_component = 8;
    screen_info.is_monochrome = 0;
    screen_info.rect = {0, 0, static_cast<int>(width_ / scale_),
                        static_cast<int>(height_ / scale_)};
    screen_info.available_rect = screen_info.rect;
    return true;
  }

  void OnPaint(CefRefPtr<CefBrowser> browser,
               PaintElementType type,
               const RectList& dirtyRects,
               const void* buffer,
               int width,
               int height) override {
    if (!java_browser_ || width == 0 || height == 0) return;

    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;

    int size = width * height * 4;
    jobject byte_buffer = env->NewDirectByteBuffer(
        const_cast<void*>(buffer), size);
    if (!byte_buffer) return;

    jclass cls = env->GetObjectClass(java_browser_);
    if (type == PET_POPUP) {
      // FEATURE-063: OSR popup widget (HTML <select> dropdown etc.). Deliver the
      // popup pixels + its rect so the host composites it over the view.
      jmethodID method = env->GetMethodID(cls, "onPopupPaint",
          "(IIIILjava/nio/ByteBuffer;)V");
      if (method) {
        env->CallVoidMethod(java_browser_, method, popup_rect_.x, popup_rect_.y,
                            width, height, byte_buffer);
      }
    } else {
      jmethodID method = env->GetMethodID(cls, "onPaint",
          "(IILjava/nio/ByteBuffer;)V");
      if (method) {
        env->CallVoidMethod(java_browser_, method, width, height, byte_buffer);
      }
    }
    env->DeleteLocalRef(byte_buffer);
    env->DeleteLocalRef(cls);
  }

  // FEATURE-063: OSR popup widget show/hide + geometry (HTML <select> dropdown).
  void OnPopupShow(CefRefPtr<CefBrowser> browser, bool show) override {
    if (!show) popup_rect_ = CefRect(0, 0, 0, 0);
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onPopupShow", "(Z)V");
    if (method) env->CallVoidMethod(java_browser_, method, show);
    env->DeleteLocalRef(cls);
  }

  void OnPopupSize(CefRefPtr<CefBrowser> browser, const CefRect& rect) override {
    popup_rect_ = rect;
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onPopupSize", "(IIII)V");
    if (method) {
      env->CallVoidMethod(java_browser_, method, rect.x, rect.y, rect.width,
                          rect.height);
    }
    env->DeleteLocalRef(cls);
  }

  // FEATURE-063: IME composition rect (first character bound) for overlay
  // positioning; pairs with the OSR IME input path.
  void OnImeCompositionRangeChanged(
      CefRefPtr<CefBrowser> browser,
      const CefRange& selected_range,
      const RectList& character_bounds) override {
    if (!java_browser_) return;
    CefRect r = character_bounds.empty() ? CefRect(0, 0, 0, 0)
                                         : character_bounds.front();
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method =
        env->GetMethodID(cls, "onImeCompositionRangeChanged", "(IIII)V");
    if (method) {
      env->CallVoidMethod(java_browser_, method, r.x, r.y, r.width, r.height);
    }
    env->DeleteLocalRef(cls);
  }

  // FEATURE-063: text selection changed (selection handles / copy UI).
  void OnTextSelectionChanged(CefRefPtr<CefBrowser> browser,
                              const CefString& selected_text,
                              const CefRange& selected_range) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onTextSelectionChanged",
                                        "(Ljava/lang/String;)V");
    if (method) {
      jstring j = env->NewStringUTF(selected_text.ToString().c_str());
      env->CallVoidMethod(java_browser_, method, j);
      env->DeleteLocalRef(j);
    }
    env->DeleteLocalRef(cls);
  }

  // FEATURE-063: scroll offset changed.
  void OnScrollOffsetChanged(CefRefPtr<CefBrowser> browser,
                             double x, double y) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onScrollOffsetChanged", "(DD)V");
    if (method) env->CallVoidMethod(java_browser_, method, x, y);
    env->DeleteLocalRef(cls);
  }

  // FEATURE-066: OSR touch-selection handles. A non-zero size is REQUIRED for
  // the touch-selection controller to treat handles as shown, which gates the
  // quick menu (SELECTION_HANDLES_SHOWN). Return a standard handle size.
  void GetTouchHandleSize(CefRefPtr<CefBrowser> browser,
                          cef_horizontal_alignment_t orientation,
                          CefSize& size) override {
    size = CefSize(40, 40);
  }

  // Bridge handle state so a consumer can draw the draggable selection handles.
  // CEF reports one field per call (|flags|), so keep each handle's state and
  // hand Java the whole of it.
  void OnTouchHandleStateChanged(CefRefPtr<CefBrowser> browser,
                                 const CefTouchHandleState& state) override {
    TouchHandle& h = touch_handles_[state.touch_handle_id];
    if (state.flags & CEF_THS_FLAG_ENABLED) h.enabled = state.enabled;
    if (state.flags & CEF_THS_FLAG_ORIENTATION) {
      h.orientation = state.orientation;
      h.mirror_vertical = state.mirror_vertical;
      h.mirror_horizontal = state.mirror_horizontal;
    }
    if (state.flags & CEF_THS_FLAG_ORIGIN) h.origin = state.origin;
    if (state.flags & CEF_THS_FLAG_ALPHA) h.alpha = state.alpha;
    // Ids only grow; the controller keeps three handles at most.
    while (touch_handles_.size() > 8) touch_handles_.erase(touch_handles_.begin());

    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onTouchHandleStateChanged",
                                        "(IIZZIIFZ)V");
    if (method) {
      env->CallVoidMethod(java_browser_, method, state.touch_handle_id,
                          static_cast<jint>(h.orientation),
                          h.mirror_vertical ? JNI_TRUE : JNI_FALSE,
                          h.mirror_horizontal ? JNI_TRUE : JNI_FALSE,
                          h.origin.x, h.origin.y, h.alpha,
                          h.enabled ? JNI_TRUE : JNI_FALSE);
    }
    env->DeleteLocalRef(cls);
  }

  // FEATURE-062 (issue #9): OSR virtual keyboard. Fired when the focused
  // editable node changes. |input_mode| == CEF_TEXT_INPUT_MODE_NONE (1) means
  // no editable is focused (hide the soft keyboard); any other value means an
  // editable gained focus (show it). Forwarded to Java so the hosting View can
  // drive InputMethodManager.
  void OnVirtualKeyboardRequested(CefRefPtr<CefBrowser> browser,
                                  TextInputMode input_mode) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method =
        env->GetMethodID(cls, "onVirtualKeyboardRequested", "(I)V");
    if (method) {
      env->CallVoidMethod(java_browser_, method,
                          static_cast<jint>(input_mode));
    }
    env->DeleteLocalRef(cls);
  }

  ~SdkRenderHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }

  IMPLEMENT_REFCOUNTING(SdkRenderHandler);

 private:
  int width_;
  int height_;
  float scale_;
  jobject java_browser_ = nullptr;
  CefRect popup_rect_;  // FEATURE-063: last OSR popup geometry (for PET_POPUP).

  struct TouchHandle {
    bool enabled = false;
    cef_horizontal_alignment_t orientation = CEF_HORIZONTAL_ALIGNMENT_LEFT;
    bool mirror_vertical = false;
    bool mirror_horizontal = false;
    CefPoint origin;
    float alpha = 0.f;
  };
  std::map<int, TouchHandle> touch_handles_;
};

// Life span handler that captures the browser when created.
class SdkLifeSpanHandler : public CefLifeSpanHandler {
 public:
  void SetBrowserData(struct SdkBrowserData* data) { data_ = data; }

  void OnAfterCreated(CefRefPtr<CefBrowser> browser) override;

  // FEATURE-023: forward browser-close to the JS bridge router, AND do the
  // teardown of the surface / Java window / native data here -- the point at
  // which the browser has actually finished closing (the WebContents is being
  // destroyed). nativeClose() only INITIATES CloseBrowser(true); the surface
  // browser's close then completes via the Android platform delegate's
  // CloseHostWindow() -> WindowDestroyed() handshake (see
  // browser_platform_delegate_native_android.cc). Doing the teardown in
  // nativeClose() instead would tear down the Java window before the async
  // close completed. Defined out-of-line below: it needs the full
  // SdkBrowserData definition.
  void OnBeforeClose(CefRefPtr<CefBrowser> browser) override;
  // OnBeforeClose's teardown: also what a popup CEF never created gets.
  void Discard();

  void SetMessageRouter(CefRefPtr<CefMessageRouterBrowserSide> router) {
    message_router_ = router;
  }

  // Binding the Java browser is one of the two events (with OnAfterCreated)
  // that complete the created notification, so every binding path notifies.
  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb && !java_browser_) java_browser_ = env->NewGlobalRef(jb);
    NotifyJavaBrowserCreated();
  }

  // Tell Java the native browser and its WebContents exist, exactly once,
  // whichever of OnAfterCreated and the Java binding comes second. Both run
  // on the Android main thread (CEF's UI thread in this port), so the two
  // flags need no lock.
  void NotifyJavaBrowserCreated();
  bool browser_created_ = false;
  bool created_notified_ = false;

  // FEATURE-029 / FEATURE-086: window.open / target=_blank. The app may give
  // the popup a browser of its own (opener kept); otherwise the PopupHandler
  // or this browser loads the URL. Defined out-of-line below.
  bool OnBeforePopup(CefRefPtr<CefBrowser> browser,
                     CefRefPtr<CefFrame> frame,
                     int popup_id,
                     const CefString& target_url,
                     const CefString& target_frame_name,
                     WindowOpenDisposition target_disposition,
                     bool user_gesture,
                     const CefPopupFeatures& popupFeatures,
                     CefWindowInfo& windowInfo,
                     CefRefPtr<CefClient>& client,
                     CefBrowserSettings& settings,
                     CefRefPtr<CefDictionaryValue>& extra_info,
                     bool* no_javascript_access) override;
  void OnBeforePopupAborted(CefRefPtr<CefBrowser> browser,
                            int popup_id) override;
  // Popups handed to CEF and not created yet, by popup_id.
  std::map<int, SdkBrowserData*> pending_popups_;

  // FEATURE-063: window.close() / JS-initiated close. Notify Java; return false
  // for CEF's default close sequence.
  bool DoClose(CefRefPtr<CefBrowser> browser) override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) {
        jclass cls = env->GetObjectClass(java_browser_);
        jmethodID mid = env->GetMethodID(cls, "onDoClose", "()V");
        if (mid) env->CallVoidMethod(java_browser_, mid);
        env->DeleteLocalRef(cls);
      }
    }
    return false;
  }

  ~SdkLifeSpanHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }

  // The bound CefriumBrowser (a global reference), or null.
  jobject java_browser() const { return java_browser_; }

  IMPLEMENT_REFCOUNTING(SdkLifeSpanHandler);

 private:
  struct SdkBrowserData* data_ = nullptr;
  CefRefPtr<CefMessageRouterBrowserSide> message_router_;
  jobject java_browser_ = nullptr;
};

// Load handler that triggers Invalidate on load completion.
// Without this, the off-screen compositor never generates a frame.
class SdkLoadHandler : public CefLoadHandler {
 public:
  void SetBrowserData(struct SdkBrowserData* data) { data_ = data; }
  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    if (java_browser) java_browser_ = env->NewGlobalRef(java_browser);
  }

  // Defined out-of-line after SdkBrowserData is fully declared.
  void OnLoadEnd(CefRefPtr<CefBrowser> browser,
                 CefRefPtr<CefFrame> frame,
                 int httpStatusCode) override;

  void OnLoadingStateChange(CefRefPtr<CefBrowser> browser,
                            bool isLoading,
                            bool canGoBack,
                            bool canGoForward) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onLoadingStateChanged",
                                        "(ZZZ)V");
    if (method) {
      env->CallVoidMethod(java_browser_, method,
                          isLoading, canGoBack, canGoForward);
    }
    env->DeleteLocalRef(cls);
  }

  // Runs on every committed cross-document navigation, any frame.
  void SetOnCommit(base::RepeatingClosure on_commit) {
    on_commit_ = std::move(on_commit);
  }

  // FEATURE-063: per-frame navigation start (main frame only up-called).
  void OnLoadStart(CefRefPtr<CefBrowser> browser,
                   CefRefPtr<CefFrame> frame,
                   TransitionType transition_type) override {
    if (on_commit_) on_commit_.Run();
    if (frame->IsMain() && data_) {
      // A popup's configuration, held back since OnAfterCreated.
      data_->hold_calls = false;
      auto pending_calls = std::move(data_->pending_calls);
      for (auto& call : pending_calls) {
        std::move(call).Run(browser);
      }
      RunPendingDevTools(data_, browser);
    }
    if (!frame->IsMain() || !java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onLoadStart",
                                        "(Ljava/lang/String;)V");
    if (method) {
      jstring j = env->NewStringUTF(
          frame->GetURL().ToString().c_str());
      env->CallVoidMethod(java_browser_, method, j);
      env->DeleteLocalRef(j);
    }
    env->DeleteLocalRef(cls);
  }

  // FEATURE-063: per-frame load error. WebView onReceivedError. Main frame only.
  void OnLoadError(CefRefPtr<CefBrowser> browser,
                   CefRefPtr<CefFrame> frame,
                   ErrorCode errorCode,
                   const CefString& errorText,
                   const CefString& failedUrl) override {
    if (!frame->IsMain() || !java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onLoadError",
        "(ILjava/lang/String;Ljava/lang/String;)V");
    if (method) {
      jstring j_txt = env->NewStringUTF(errorText.ToString().c_str());
      jstring j_url = env->NewStringUTF(failedUrl.ToString().c_str());
      env->CallVoidMethod(java_browser_, method, static_cast<jint>(errorCode),
                          j_txt, j_url);
      env->DeleteLocalRef(j_txt);
      env->DeleteLocalRef(j_url);
    }
    env->DeleteLocalRef(cls);
  }

  ~SdkLoadHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }

  IMPLEMENT_REFCOUNTING(SdkLoadHandler);

 private:
  struct SdkBrowserData* data_ = nullptr;
  jobject java_browser_ = nullptr;
  base::RepeatingClosure on_commit_;
};

// Display handler — notifies Java of URL/title changes via JNI callback.
class SdkDisplayHandler : public CefDisplayHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    if (java_browser)
      java_browser_ = env->NewGlobalRef(java_browser);
  }

  void OnAddressChange(CefRefPtr<CefBrowser> browser,
                       CefRefPtr<CefFrame> frame,
                       const CefString& url) override {
    if (frame->IsMain()) {
      last_title_.reset();
    }
    if (!frame->IsMain() || !java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onUrlChanged",
                                        "(Ljava/lang/String;)V");
    if (method) {
      std::string url_str = url.ToString();
      jstring j_url = env->NewStringUTF(url_str.c_str());
      env->CallVoidMethod(java_browser_, method, j_url);
      env->DeleteLocalRef(j_url);
    }
    env->DeleteLocalRef(cls);
  }

  // CEF reports the title again when loading stops; report each title of a
  // page once, as WebView's onReceivedTitle.
  void OnTitleChange(CefRefPtr<CefBrowser> browser,
                     const CefString& title) override {
    if (last_title_ == title.ToString16()) return;
    last_title_ = title.ToString16();
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onTitleChanged",
                                        "(Ljava/lang/String;)V");
    if (method) {
      std::string title_str = title.ToString();
      jstring j_title = env->NewStringUTF(title_str.c_str());
      env->CallVoidMethod(java_browser_, method, j_title);
      env->DeleteLocalRef(j_title);
    }
    env->DeleteLocalRef(cls);
  }

  void OnFullscreenModeChange(CefRefPtr<CefBrowser> browser,
                              bool fullscreen) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onFullscreenChanged", "(Z)V");
    if (method) {
      env->CallVoidMethod(java_browser_, method, fullscreen);
    }
    env->DeleteLocalRef(cls);
  }

  // FEATURE-063 (WebView parity): favicon URLs. WebView onReceivedIcon/faviconUrl.
  void OnFaviconURLChange(CefRefPtr<CefBrowser> browser,
                          const std::vector<CefString>& icon_urls) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass str_cls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(
        static_cast<jsize>(icon_urls.size()), str_cls, nullptr);
    for (size_t i = 0; i < icon_urls.size(); ++i) {
      jstring s = env->NewStringUTF(icon_urls[i].ToString().c_str());
      env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
      env->DeleteLocalRef(s);
    }
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onFaviconUrlChanged",
                                        "([Ljava/lang/String;)V");
    if (method) {
      env->CallVoidMethod(java_browser_, method, arr);
    }
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(str_cls);
    env->DeleteLocalRef(cls);
  }

  // FEATURE-063: fractional load progress 0..1. WebView onProgressChanged.
  void OnLoadingProgressChange(CefRefPtr<CefBrowser> browser,
                               double progress) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onLoadingProgress", "(D)V");
    if (method) {
      env->CallVoidMethod(java_browser_, method, progress);
    }
    env->DeleteLocalRef(cls);
  }

  // FEATURE-063: JS console messages. WebView onConsoleMessage. Return false so
  // the message is still logged normally.
  bool OnConsoleMessage(CefRefPtr<CefBrowser> browser,
                        cef_log_severity_t level,
                        const CefString& message,
                        const CefString& source,
                        int line) override {
    if (!java_browser_) return false;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return false;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(
        cls, "onConsoleMessage", "(IILjava/lang/String;Ljava/lang/String;I)V");
    if (method) {
      // FEATURE-092: CefriumConsoleMessage.Level ordinal (VERBOSE, INFO,
      // WARNING, ERROR); the raw severity still goes to CefriumDisplayHandler.
      int cefrium_level = 1;
      switch (level) {
        case LOGSEVERITY_VERBOSE:
          cefrium_level = 0;
          break;
        case LOGSEVERITY_WARNING:
          cefrium_level = 2;
          break;
        case LOGSEVERITY_ERROR:
        case LOGSEVERITY_FATAL:
          cefrium_level = 3;
          break;
        default:
          cefrium_level = 1;
          break;
      }
      jstring j_msg = env->NewStringUTF(message.ToString().c_str());
      jstring j_src = env->NewStringUTF(source.ToString().c_str());
      env->CallVoidMethod(java_browser_, method, static_cast<jint>(level),
                          static_cast<jint>(cefrium_level), j_msg, j_src, line);
      env->DeleteLocalRef(j_msg);
      env->DeleteLocalRef(j_src);
    }
    env->DeleteLocalRef(cls);
    return false;
  }

  // FEATURE-063: link-hover status text.
  void OnStatusMessage(CefRefPtr<CefBrowser> browser,
                       const CefString& value) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onStatusMessage",
                                        "(Ljava/lang/String;)V");
    if (method) {
      jstring j = env->NewStringUTF(value.ToString().c_str());
      env->CallVoidMethod(java_browser_, method, j);
      env->DeleteLocalRef(j);
    }
    env->DeleteLocalRef(cls);
  }

  // FEATURE-063: camera/mic in-use change (privacy indicator).
  void OnMediaAccessChange(CefRefPtr<CefBrowser> browser,
                           bool has_video_access,
                           bool has_audio_access) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onMediaAccessChange", "(ZZ)V");
    if (method) {
      env->CallVoidMethod(java_browser_, method, has_video_access,
                          has_audio_access);
    }
    env->DeleteLocalRef(cls);
  }

  ~SdkDisplayHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }

  IMPLEMENT_REFCOUNTING(SdkDisplayHandler);

 private:
  jobject java_browser_ = nullptr;
  std::optional<std::u16string> last_title_;
};

// The app's own Downloads directory, set by Cefrium.initialize. A download the
// app allows without a path is saved there.
std::string& DefaultDownloadDir();

// Download handler — saves files to the app's download directory.
// Notifies Java of download progress via onDownloadUpdated callback.
class SdkDownloadHandler : public CefDownloadHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    if (java_browser) java_browser_ = env->NewGlobalRef(java_browser);
  }

  // FEATURE-060: allow/deny hook, synchronous. Also a diagnostic for the
  // schemes that never reach OnBeforeDownload (blob:/data:).
  bool CanDownload(CefRefPtr<CefBrowser> browser,
                   const CefString& url,
                   const CefString& request_method) override {
    LOGI("CanDownload: %s (%s)", url.ToString().c_str(),
         request_method.ToString().c_str());
    return true;
  }

  // FEATURE-060: async allow/deny. The pending callback is held by download id
  // until Java responds via nativeRespondBeforeDownload (allow(path) / cancel()).
  // Both OnBeforeDownload and OnDownloadUpdated run on the UI thread; the native
  // respond methods post to the UI thread too, so the maps are UIT-only (no lock).
  bool OnBeforeDownload(CefRefPtr<CefBrowser> browser,
                        CefRefPtr<CefDownloadItem> download_item,
                        const CefString& suggested_name,
                        CefRefPtr<CefBeforeDownloadCallback> callback) override {
    uint32_t id = download_item->GetId();
    std::string default_path =
        DefaultDownloadDir() + "/" + suggested_name.ToString();
    LOGI("Download: %s -> %s", download_item->GetURL().ToString().c_str(),
         default_path.c_str());
    // Backward compatibility: with no Java handler registered at all, keep the
    // old auto-download behavior (never leave the download waiting for a
    // response that will never arrive).
    if (!java_browser_) {
      callback->Continue(default_path, false);
      return true;
    }
    pending_before_[id] = callback;
    NotifyBeforeDownload(id, download_item->GetURL().ToString(),
                         suggested_name.ToString(),
                         download_item->GetMimeType().ToString(),
                         download_item->GetTotalBytes(), default_path);
    return true;  // decision is delivered asynchronously
  }

  void OnDownloadUpdated(CefRefPtr<CefBrowser> browser,
                         CefRefPtr<CefDownloadItem> download_item,
                         CefRefPtr<CefDownloadItemCallback> callback) override {
    uint32_t id = download_item->GetId();
    item_callbacks_[id] = callback;

    std::string filename = download_item->GetFullPath().ToString();
    size_t pos = filename.rfind('/');
    std::string name = (pos != std::string::npos) ?
        filename.substr(pos + 1) : filename;
    const bool complete = download_item->IsComplete();
    const bool canceled = download_item->IsCanceled();
    if (complete) {
      LOGI("Download complete: %s (%" PRId64 " bytes)", filename.c_str(),
           static_cast<int64_t>(download_item->GetReceivedBytes()));
    } else if (canceled) {
      LOGI("Download canceled");
    }

    // Rich callback (new API) + legacy callback (setOnDownloadListener compat).
    NotifyDownloadUpdated(id, download_item, name, filename);
    NotifyJavaLegacy(name, filename, download_item->GetPercentComplete(),
                     complete, canceled);

    if (complete || canceled) {
      pending_before_.erase(id);
      item_callbacks_.erase(id);
    }
  }

  // Called from nativeRespondBeforeDownload (posted to the UI thread).
  void RespondBeforeDownload(uint32_t id, bool allow, const std::string& path) {
    auto it = pending_before_.find(id);
    if (it == pending_before_.end()) return;
    CefRefPtr<CefBeforeDownloadCallback> cb = it->second;
    pending_before_.erase(it);
    // Continuing starts the download; NOT continuing (dropping the callback)
    // cancels it -- so denial needs no explicit call.
    if (allow) cb->Continue(path, false);
  }

  // Called from nativeDownloadControl (posted to the UI thread). 0=cancel,
  // 1=pause, 2=resume.
  void ControlDownload(uint32_t id, int action) {
    auto it = item_callbacks_.find(id);
    if (it == item_callbacks_.end()) return;
    CefRefPtr<CefDownloadItemCallback> cb = it->second;
    if (action == 0) {
      cb->Cancel();
    } else if (action == 1) {
      cb->Pause();
    } else if (action == 2) {
      cb->Resume();
    }
  }

  ~SdkDownloadHandler() {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }

  IMPLEMENT_REFCOUNTING(SdkDownloadHandler);

 private:
  void NotifyBeforeDownload(uint32_t id, const std::string& url,
                            const std::string& name, const std::string& mime,
                            int64_t total, const std::string& default_path) {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onBeforeDownload",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;)V");
    if (method) {
      jstring j_url = env->NewStringUTF(url.c_str());
      jstring j_name = env->NewStringUTF(name.c_str());
      jstring j_mime = env->NewStringUTF(mime.c_str());
      jstring j_path = env->NewStringUTF(default_path.c_str());
      env->CallVoidMethod(java_browser_, method, static_cast<jint>(id),
                          j_url, j_name, j_mime, static_cast<jlong>(total), j_path);
      env->DeleteLocalRef(j_url);
      env->DeleteLocalRef(j_name);
      env->DeleteLocalRef(j_mime);
      env->DeleteLocalRef(j_path);
    }
  }

  void NotifyDownloadUpdated(uint32_t id, CefRefPtr<CefDownloadItem> item,
                             const std::string& name, const std::string& path) {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onDownloadUpdatedRich",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        "JJIJZZZI)V");
    if (!method) return;
    jstring j_url = env->NewStringUTF(item->GetURL().ToString().c_str());
    jstring j_name = env->NewStringUTF(name.c_str());
    jstring j_mime = env->NewStringUTF(item->GetMimeType().ToString().c_str());
    jstring j_path = env->NewStringUTF(path.c_str());
    env->CallVoidMethod(java_browser_, method, static_cast<jint>(id),
        j_url, j_name, j_mime, j_path,
        static_cast<jlong>(item->GetTotalBytes()),
        static_cast<jlong>(item->GetReceivedBytes()),
        item->GetPercentComplete(),
        static_cast<jlong>(item->GetCurrentSpeed()),
        item->IsComplete(), item->IsCanceled(), item->IsPaused(),
        static_cast<jint>(item->GetInterruptReason()));
    env->DeleteLocalRef(j_url);
    env->DeleteLocalRef(j_name);
    env->DeleteLocalRef(j_mime);
    env->DeleteLocalRef(j_path);
  }

  void NotifyJavaLegacy(const std::string& name, const std::string& path,
                        int percent, bool complete, bool canceled) {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onDownloadUpdated",
        "(Ljava/lang/String;Ljava/lang/String;IZZ)V");
    if (method) {
      jstring j_name = env->NewStringUTF(name.c_str());
      jstring j_path = env->NewStringUTF(path.c_str());
      env->CallVoidMethod(java_browser_, method,
                          j_name, j_path, percent, complete, canceled);
      env->DeleteLocalRef(j_name);
      env->DeleteLocalRef(j_path);
    }
  }

  std::map<uint32_t, CefRefPtr<CefBeforeDownloadCallback>> pending_before_;
  std::map<uint32_t, CefRefPtr<CefDownloadItemCallback>> item_callbacks_;
  jobject java_browser_ = nullptr;
};

// Context menu handler — long-press menu shown as Android AlertDialog.
// Uses RunContextMenu to intercept display and forward to Java.
class SdkContextMenuHandler : public CefContextMenuHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    // Idempotent: both nativeSetRenderHandler (OSR) and nativeSetDisplayCallback
    // (Surface) wire this handler; guard against leaking a second global ref.
    if (java_browser && !java_browser_) {
      java_browser_ = env->NewGlobalRef(java_browser);
    }
  }

  void OnBeforeContextMenu(CefRefPtr<CefBrowser> browser,
                           CefRefPtr<CefFrame> frame,
                           CefRefPtr<CefContextMenuParams> params,
                           CefRefPtr<CefMenuModel> model) override {
    model->Clear();
    if (params->GetTypeFlags() & CM_TYPEFLAG_LINK) {
      model->AddItem(1, "Open in new tab");
      model->AddItem(2, "Copy link");
      model->AddItem(3, "Download link");
    }
    if (params->GetTypeFlags() & CM_TYPEFLAG_MEDIA &&
        params->GetMediaType() == CM_MEDIATYPE_IMAGE) {
      model->AddItem(4, "Save image");
      model->AddItem(5, "Copy image URL");
    }
  }

  bool RunContextMenu(CefRefPtr<CefBrowser> browser,
                      CefRefPtr<CefFrame> frame,
                      CefRefPtr<CefContextMenuParams> params,
                      CefRefPtr<CefMenuModel> model,
                      CefRefPtr<CefRunContextMenuCallback> callback) override {
    if (!java_browser_ || model->GetCount() == 0) {
      callback->Cancel();
      return true;
    }

    pending_callback_ = callback;

    // FEATURE-092: the items and the parameters, typed, for
    // CefriumContextMenu (and the legacy "id|label" listener, built in Java).
    // The ids are the SDK's own (OnBeforeContextMenu), never shown to apps.
    JNIEnv* env = base::android::AttachCurrentThread();
    const jsize n = static_cast<jsize>(model->GetCount());
    std::vector<jint> ids(n);
    std::vector<jboolean> enabled(n);
    std::vector<std::string> labels(n);
    for (jsize i = 0; i < n; i++) {
      ids[i] = model->GetCommandIdAt(i);
      enabled[i] = model->IsEnabledAt(i) ? JNI_TRUE : JNI_FALSE;
      labels[i] = model->GetLabelAt(i).ToString();
    }
    jintArray j_ids = env->NewIntArray(n);
    env->SetIntArrayRegion(j_ids, 0, n, ids.data());
    jbooleanArray j_enabled = env->NewBooleanArray(n);
    env->SetBooleanArrayRegion(j_enabled, 0, n, enabled.data());
    base::android::ScopedJavaLocalRef<jobjectArray> j_labels =
        base::android::ToJavaArrayOfStrings(env, labels);
    jint media = 4;  // CefriumContextMenu.MediaType.OTHER
    switch (params->GetMediaType()) {
      case CM_MEDIATYPE_NONE:
        media = 0;
        break;
      case CM_MEDIATYPE_IMAGE:
        media = 1;
        break;
      case CM_MEDIATYPE_VIDEO:
        media = 2;
        break;
      case CM_MEDIATYPE_AUDIO:
        media = 3;
        break;
      default:
        media = 4;
        break;
    }
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(
        cls, "onContextMenuShown",
        "([I[Ljava/lang/String;[ZLjava/lang/String;Ljava/lang/String;"
        "Ljava/lang/String;Ljava/lang/String;ZIII)V");
    if (method) {
      auto str = [env](const CefString& v) {
        return env->NewStringUTF(v.ToString().c_str());
      };
      jstring j_page = str(params->GetPageUrl());
      jstring j_link = str(params->GetLinkUrl());
      jstring j_source = str(params->GetSourceUrl());
      jstring j_selection = str(params->GetSelectionText());
      env->CallVoidMethod(java_browser_, method, j_ids, j_labels.obj(),
                          j_enabled, j_page, j_link, j_source, j_selection,
                          params->IsEditable() ? JNI_TRUE : JNI_FALSE, media,
                          params->GetXCoord(), params->GetYCoord());
      env->DeleteLocalRef(j_page);
      env->DeleteLocalRef(j_link);
      env->DeleteLocalRef(j_source);
      env->DeleteLocalRef(j_selection);
    } else {
      CancelMenu();
    }
    env->DeleteLocalRef(j_ids);
    env->DeleteLocalRef(j_enabled);
    env->DeleteLocalRef(cls);
    return true;  // We handle display.
  }

  // Called from Java when user selects a menu item.
  // Routes through CefRunContextMenuCallback::Continue() so that CEF
  // calls OnContextMenuCommand() — the correct CEF API flow.
  void ExecuteCommand(int command_id) {
    if (pending_callback_) {
      pending_callback_->Continue(command_id, EVENTFLAG_NONE);
      pending_callback_ = nullptr;
    }
  }

  // Called by CEF after CefRunContextMenuCallback::Continue().
  // This is the correct CEF API entry point for executing menu commands.
  bool OnContextMenuCommand(CefRefPtr<CefBrowser> browser,
                            CefRefPtr<CefFrame> frame,
                            CefRefPtr<CefContextMenuParams> params,
                            int command_id,
                            EventFlags event_flags) override {
    switch (command_id) {
      case 1:  // Open link
        browser->GetMainFrame()->LoadURL(params->GetLinkUrl());
        return true;
      case 2: {  // Copy link — use Java clipboard API (more reliable than JS)
        JNIEnv* env = base::android::AttachCurrentThread();
        if (java_browser_) {
          jclass cls = env->GetObjectClass(java_browser_);
          jmethodID method = env->GetMethodID(cls, "copyToClipboard",
              "(Ljava/lang/String;)V");
          if (method) {
            jstring j_text = env->NewStringUTF(
                params->GetLinkUrl().ToString().c_str());
            env->CallVoidMethod(java_browser_, method, j_text);
            env->DeleteLocalRef(j_text);
          }
          env->DeleteLocalRef(cls);
        }
        return true;
      }
      case 3:  // Download link
        browser->GetHost()->StartDownload(params->GetLinkUrl());
        return true;
      case 4:  // Save image
        browser->GetHost()->StartDownload(params->GetSourceUrl());
        return true;
      case 5: {  // Copy image URL — use Java clipboard API
        JNIEnv* env = base::android::AttachCurrentThread();
        if (java_browser_) {
          jclass cls = env->GetObjectClass(java_browser_);
          jmethodID method = env->GetMethodID(cls, "copyToClipboard",
              "(Ljava/lang/String;)V");
          if (method) {
            jstring j_text = env->NewStringUTF(
                params->GetSourceUrl().ToString().c_str());
            env->CallVoidMethod(java_browser_, method, j_text);
            env->DeleteLocalRef(j_text);
          }
          env->DeleteLocalRef(cls);
        }
        return true;
      }
    }
    return false;
  }

  void CancelMenu() {
    if (pending_callback_) {
      pending_callback_->Cancel();
      pending_callback_ = nullptr;
    }
  }

  // FEATURE-063: context-menu + touch quick-menu lifecycle (copy/share FAB).
  void OnContextMenuDismissed(CefRefPtr<CefBrowser> browser,
                              CefRefPtr<CefFrame> frame) override {
    NotifyNoArg("onContextMenuDismissed");
  }
  // FEATURE-066: OSR text-selection quick menu (copy/cut/paste). Without this
  // override the OSR quick menu never shows. Bridge the request to Java (which
  // draws the menu over its OSR view) and hold the callback; Java returns the
  // chosen QM_EDITFLAG_CAN_* via nativeQuickMenuCommand -> Continue(). CEF then
  // executes cut/copy/paste itself (ExecuteCommand -> host_delegate).
  bool RunQuickMenu(CefRefPtr<CefBrowser> browser,
                    CefRefPtr<CefFrame> frame,
                    const CefPoint& location,
                    const CefSize& size,
                    QuickMenuEditStateFlags edit_state_flags,
                    CefRefPtr<CefRunQuickMenuCallback> callback) override {
    if (!java_browser_) return false;
    pending_quick_ = callback;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return false;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onQuickMenu", "(IIIII)V");
    bool dispatched = false;
    if (mid) {
      env->CallVoidMethod(java_browser_, mid, location.x, location.y,
                          size.width, size.height,
                          static_cast<jint>(edit_state_flags));
      dispatched = true;
    }
    env->DeleteLocalRef(cls);
    if (!dispatched) {
      pending_quick_ = nullptr;
      return false;
    }
    return true;
  }
  // Called from nativeQuickMenuCommand: command 0 => cancel; else the chosen
  // QM_EDITFLAG_CAN_* value.
  void ResolveQuickMenu(int command) {
    CefRefPtr<CefRunQuickMenuCallback> cb = pending_quick_;
    pending_quick_ = nullptr;
    if (!cb) return;
    if (command == 0) {
      cb->Cancel();
    } else {
      cb->Continue(command, EVENTFLAG_NONE);
    }
  }

  bool OnQuickMenuCommand(CefRefPtr<CefBrowser> browser,
                          CefRefPtr<CefFrame> frame,
                          int command_id,
                          EventFlags event_flags) override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) {
        jclass cls = env->GetObjectClass(java_browser_);
        jmethodID mid = env->GetMethodID(cls, "onQuickMenuCommand", "(I)V");
        if (mid) env->CallVoidMethod(java_browser_, mid, command_id);
        env->DeleteLocalRef(cls);
      }
    }
    return false;
  }
  void OnQuickMenuDismissed(CefRefPtr<CefBrowser> browser,
                            CefRefPtr<CefFrame> frame) override {
    NotifyNoArg("onQuickMenuDismissed");
  }
  void NotifyNoArg(const char* name) {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, name, "()V");
    if (mid) env->CallVoidMethod(java_browser_, mid);
    env->DeleteLocalRef(cls);
  }

  ~SdkContextMenuHandler() {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }

  IMPLEMENT_REFCOUNTING(SdkContextMenuHandler);

 private:
  jobject java_browser_ = nullptr;
  CefRefPtr<CefRunContextMenuCallback> pending_callback_;
  CefRefPtr<CefRunQuickMenuCallback> pending_quick_;  // FEATURE-066 (OSR)
};

// Minimal client that connects handlers.
// Find handler — reports find results back to Java via CefFindHandler.
class SdkFindHandler : public CefFindHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    if (java_browser) java_browser_ = env->NewGlobalRef(java_browser);
  }

  void OnFindResult(CefRefPtr<CefBrowser> browser,
                    int identifier,
                    int count,
                    const CefRect& selectionRect,
                    int activeMatchOrdinal,
                    bool finalUpdate) override {
    if (!finalUpdate || !java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onFindResult", "(II)V");
    if (method) {
      env->CallVoidMethod(java_browser_, method, activeMatchOrdinal, count);
    }
  }

  ~SdkFindHandler() {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }

  IMPLEMENT_REFCOUNTING(SdkFindHandler);

 private:
  jobject java_browser_ = nullptr;
};

// FEATURE-063: keyboard handler. Synchronous up-calls return a jboolean so Java
// can intercept/block a key before the page (return true = handled).
class SdkKeyboardHandler : public CefKeyboardHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb) java_browser_ = env->NewGlobalRef(jb);
  }
  bool OnPreKeyEvent(CefRefPtr<CefBrowser> browser,
                     const CefKeyEvent& event,
                     CefEventHandle os_event,
                     bool* is_keyboard_shortcut) override {
    return CallJavaKey("onPreKeyEvent", event);
  }
  bool OnKeyEvent(CefRefPtr<CefBrowser> browser,
                  const CefKeyEvent& event,
                  CefEventHandle os_event) override {
    return CallJavaKey("onKeyEvent", event);
  }
  bool CallJavaKey(const char* name, const CefKeyEvent& event) {
    if (!java_browser_) return false;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return false;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, name, "(III)Z");
    jboolean handled = JNI_FALSE;
    if (mid) {
      handled = env->CallBooleanMethod(
          java_browser_, mid, static_cast<jint>(event.type),
          event.windows_key_code, static_cast<jint>(event.modifiers));
    }
    env->DeleteLocalRef(cls);
    return handled == JNI_TRUE;
  }
  ~SdkKeyboardHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }
  IMPLEMENT_REFCOUNTING(SdkKeyboardHandler);
 private:
  jobject java_browser_ = nullptr;
};

// FEATURE-063: focus handler. OnSetFocus returns a jboolean (true = cancel).
class SdkFocusHandler : public CefFocusHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb) java_browser_ = env->NewGlobalRef(jb);
  }
  void OnTakeFocus(CefRefPtr<CefBrowser> browser, bool next) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onTakeFocus", "(Z)V");
    if (mid) env->CallVoidMethod(java_browser_, mid, next);
    env->DeleteLocalRef(cls);
  }
  bool OnSetFocus(CefRefPtr<CefBrowser> browser, FocusSource source) override {
    if (!java_browser_) return false;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return false;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onSetFocus", "(I)Z");
    jboolean cancel = JNI_FALSE;
    if (mid) {
      cancel = env->CallBooleanMethod(java_browser_, mid,
                                      static_cast<jint>(source));
    }
    env->DeleteLocalRef(cls);
    return cancel == JNI_TRUE;
  }
  void OnGotFocus(CefRefPtr<CefBrowser> browser) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onGotFocus", "()V");
    if (mid) env->CallVoidMethod(java_browser_, mid);
    env->DeleteLocalRef(cls);
  }
  ~SdkFocusHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }
  IMPLEMENT_REFCOUNTING(SdkFocusHandler);
 private:
  jobject java_browser_ = nullptr;
};

// FEATURE-063: drag handler. OnDragEnter returns a jboolean (true = cancel drop).
class SdkDragHandler : public CefDragHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb) java_browser_ = env->NewGlobalRef(jb);
  }
  bool OnDragEnter(CefRefPtr<CefBrowser> browser,
                   CefRefPtr<CefDragData> dragData,
                   DragOperationsMask mask) override {
    if (!java_browser_) return false;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return false;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onDragEnter", "(I)Z");
    jboolean cancel = JNI_FALSE;
    if (mid) {
      cancel = env->CallBooleanMethod(java_browser_, mid,
                                      static_cast<jint>(mask));
    }
    env->DeleteLocalRef(cls);
    return cancel == JNI_TRUE;
  }
  void OnDraggableRegionsChanged(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      const std::vector<CefDraggableRegion>& regions) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    // Flatten to int[5*n]: x, y, width, height, draggable per region.
    jintArray arr = env->NewIntArray(static_cast<jsize>(regions.size() * 5));
    if (arr) {
      std::vector<jint> buf;
      buf.reserve(regions.size() * 5);
      for (const auto& r : regions) {
        buf.push_back(r.bounds.x);
        buf.push_back(r.bounds.y);
        buf.push_back(r.bounds.width);
        buf.push_back(r.bounds.height);
        buf.push_back(r.draggable ? 1 : 0);
      }
      if (!buf.empty()) {
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(buf.size()),
                               buf.data());
      }
      jclass cls = env->GetObjectClass(java_browser_);
      jmethodID mid = env->GetMethodID(cls, "onDraggableRegionsChanged", "([I)V");
      if (mid) env->CallVoidMethod(java_browser_, mid, arr);
      env->DeleteLocalRef(cls);
      env->DeleteLocalRef(arr);
    }
  }
  ~SdkDragHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }
  IMPLEMENT_REFCOUNTING(SdkDragHandler);
 private:
  jobject java_browser_ = nullptr;
};

// FEATURE-063: audio handler -- raw PCM tap of the page's audio. OPT-IN: enabling
// it REDIRECTS page audio to the handler (it no longer plays normally), so
// SdkClient returns this only when the consumer registered a Java audio handler.
class SdkAudioHandler : public CefAudioHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb) java_browser_ = env->NewGlobalRef(jb);
  }
  void OnAudioStreamStarted(CefRefPtr<CefBrowser> browser,
                            const CefAudioParameters& params,
                            int channels) override {
    channels_ = channels;
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onAudioStreamStarted", "(II)V");
    if (mid) {
      env->CallVoidMethod(java_browser_, mid, channels,
                          params.sample_rate);
    }
    env->DeleteLocalRef(cls);
  }
  void OnAudioStreamPacket(CefRefPtr<CefBrowser> browser,
                           const float** data,
                           int frames,
                           int64_t pts) override {
    if (!java_browser_ || channels_ <= 0) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    // Interleave planar channels into one float buffer for Java.
    std::vector<float> interleaved(static_cast<size_t>(frames) * channels_);
    for (int f = 0; f < frames; ++f) {
      for (int c = 0; c < channels_; ++c) {
        interleaved[f * channels_ + c] = data[c][f];
      }
    }
    jobject buf = env->NewDirectByteBuffer(
        interleaved.data(),
        static_cast<jlong>(interleaved.size() * sizeof(float)));
    if (buf) {
      jclass cls = env->GetObjectClass(java_browser_);
      jmethodID mid = env->GetMethodID(cls, "onAudioStreamPacket",
                                       "(Ljava/nio/ByteBuffer;IJ)V");
      if (mid) {
        env->CallVoidMethod(java_browser_, mid, buf, frames,
                            static_cast<jlong>(pts));
      }
      env->DeleteLocalRef(cls);
      env->DeleteLocalRef(buf);
    }
  }
  void OnAudioStreamStopped(CefRefPtr<CefBrowser> browser) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onAudioStreamStopped", "()V");
    if (mid) env->CallVoidMethod(java_browser_, mid);
    env->DeleteLocalRef(cls);
  }
  void OnAudioStreamError(CefRefPtr<CefBrowser> browser,
                          const CefString& message) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onAudioStreamError",
                                     "(Ljava/lang/String;)V");
    if (mid) {
      jstring j = env->NewStringUTF(message.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, j);
      env->DeleteLocalRef(j);
    }
    env->DeleteLocalRef(cls);
  }
  ~SdkAudioHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }
  IMPLEMENT_REFCOUNTING(SdkAudioHandler);
 private:
  jobject java_browser_ = nullptr;
  int channels_ = 0;
};

// FEATURE-063: frame lifecycle handler (notify-only).
class SdkFrameHandler : public CefFrameHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb) java_browser_ = env->NewGlobalRef(jb);
  }
  void OnFrameCreated(CefRefPtr<CefBrowser> browser,
                      CefRefPtr<CefFrame> frame) override {
    NotifyFrame("onFrameCreated", frame);
  }
  void OnFrameDestroyed(CefRefPtr<CefBrowser> browser,
                        CefRefPtr<CefFrame> frame) override {
    NotifyFrame("onFrameDestroyed", frame);
  }
  void OnFrameAttached(CefRefPtr<CefBrowser> browser,
                       CefRefPtr<CefFrame> frame,
                       bool reattached) override {
    NotifyFrame("onFrameAttached", frame);
  }
  void OnFrameDetached(CefRefPtr<CefBrowser> browser,
                       CefRefPtr<CefFrame> frame) override {
    NotifyFrame("onFrameDetached", frame);
  }
  void OnMainFrameChanged(CefRefPtr<CefBrowser> browser,
                          CefRefPtr<CefFrame> old_frame,
                          CefRefPtr<CefFrame> new_frame) override {
    NotifyFrame("onMainFrameChanged",
                new_frame ? new_frame : old_frame);
  }
  void NotifyFrame(const char* name, CefRefPtr<CefFrame> frame) {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, name,
        "(Ljava/lang/String;Z)V");
    if (mid) {
      std::string id = frame ? frame->GetIdentifier().ToString() : "";
      jstring j = env->NewStringUTF(id.c_str());
      env->CallVoidMethod(java_browser_, mid, j,
                          frame && frame->IsMain());
      env->DeleteLocalRef(j);
    }
    env->DeleteLocalRef(cls);
  }
  ~SdkFrameHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }
  IMPLEMENT_REFCOUNTING(SdkFrameHandler);
 private:
  jobject java_browser_ = nullptr;
};

// FEATURE-063: chrome command handler. Returns a jboolean (true = handled).
class SdkCommandHandler : public CefCommandHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb) java_browser_ = env->NewGlobalRef(jb);
  }
  bool OnChromeCommand(CefRefPtr<CefBrowser> browser,
                       int command_id,
                       cef_window_open_disposition_t disposition) override {
    if (!java_browser_) return false;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return false;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onChromeCommand", "(II)Z");
    jboolean handled = JNI_FALSE;
    if (mid) {
      handled = env->CallBooleanMethod(java_browser_, mid, command_id,
                                       static_cast<jint>(disposition));
    }
    env->DeleteLocalRef(cls);
    return handled == JNI_TRUE;
  }
  ~SdkCommandHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }
  IMPLEMENT_REFCOUNTING(SdkCommandHandler);
 private:
  jobject java_browser_ = nullptr;
};

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_HANDLERS_H_
