// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// JNI bridge for Cefrium SDK Java API (com.cefrium.*).
// Implements native methods declared in Cefrium.java and CefriumBrowser.java.

#include <cinttypes>
#include <fstream>
#include <iterator>
#include <jni.h>
#include <map>
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

#include "base/no_destructor.h"
#include "base/synchronization/lock.h"
#include "cef/include/cef_request_handler.h"
#include "cef/include/cef_task.h"
#include "cef/include/wrapper/cef_closure_task.h"
#include "cef/include/wrapper/cef_message_router.h"
#include "cef/libcef/browser/android/cefrium_bridge_constants.h"
#include "cef/libcef/browser/android/cefrium_profiles.h"
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
#include "ui/events/keycodes/keyboard_code_conversion_android.h"
#include "url/gurl.h"
#include "base/barrier_closure.h"
#include "cef/libcef/browser/request_context_impl.h"
#include "chrome/browser/browsing_data/chrome_browsing_data_model_delegate.h"
#include "chrome/browser/profiles/profile.h"
#include "components/browsing_data/content/browsing_data_model.h"
#include "third_party/abseil-cpp/absl/functional/overload.h"
#include "url/origin.h"

#define TAG "Cefrium"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// Forward declaration — full definition below handlers.
struct SdkBrowserData;

namespace {

// FEATURE-023 step 4b: registry of live SdkBrowserData pointers, so an
// asynchronous window.cefriumQuery response (resolved later, possibly after the
// browser was closed and its data deleted) can be dropped safely instead of
// dereferencing a dangling native pointer. The lock serializes nativeClose's
// erase with the resolution's check+dereference. base::NoDestructor avoids
// exit-time destructors (Chromium global-object rule).
base::Lock& BridgeDataLock() {
  static base::NoDestructor<base::Lock> lock;
  return *lock;
}
std::set<jlong>& LiveBridgeData() {
  static base::NoDestructor<std::set<jlong>> data;
  return *data;
}

// FEATURE-057: incognito (off-the-record) request context, SHARED by all
// incognito browsers so they see one common session, ISOLATED from the global
// (persistent) context used by normal tabs. Created with an empty cache_path ->
// fully in-memory (no cookies/history/cache on disk). Dropped when the last
// incognito browser closes, so the session is cleared and the next incognito
// tab starts fresh (clear-on-close, like Chrome/Firefox). Touched only on the
// CEF UI thread (browser create in nativeCreateWithSurface, release in
// OnBeforeClose), so no extra lock is needed.
CefRefPtr<CefRequestContext>& IncognitoContext() {
  static base::NoDestructor<CefRefPtr<CefRequestContext>> ctx;
  return *ctx;
}

// Profile preferences set from Java (proxy, WebRTC IP policy), by profile:
// "" is the default profile, kIncognitoKey the incognito one, anything else a
// named profile. A context initializes asynchronously, and the incognito one
// is recreated for every private session, so what the app set is kept here and
// applied when a context becomes ready; CEF creates no browser before that.
// Touched only on the CEF UI thread.
constexpr char kIncognitoKey[] = "\x01incognito";
using PrefMap = std::map<std::string, CefRefPtr<CefValue>>;
std::map<std::string, PrefMap>& ProfilePrefs() {
  static base::NoDestructor<std::map<std::string, PrefMap>> prefs;
  return *prefs;
}
std::set<std::string>& ReadyProfiles() {
  static base::NoDestructor<std::set<std::string>> ready;
  return *ready;
}

// The settings the incognito profile takes from the default one unless it has
// its own, as Chrome's incognito does. Our incognito context does not read the
// default profile's preferences by itself, so without this a proxy set on the
// default profile left private browsers connecting directly.
constexpr const char* kInheritedByIncognito[] = {"proxy",
                                                 "webrtc.ip_handling_policy"};

bool ApplyProfilePref(CefRefPtr<CefRequestContext> context,
                      const std::string& name,
                      CefRefPtr<CefValue> value) {
  CefString error;
  if (!context->SetPreference(name, value, error)) {
    LOG(ERROR) << "Cefrium: preference " << name << ": " << error.ToString();
    return false;
  }
  return true;
}

class ProfilePrefsApplier : public CefRequestContextHandler {
 public:
  explicit ProfilePrefsApplier(std::string key) : key_(std::move(key)) {}

  void OnRequestContextInitialized(
      CefRefPtr<CefRequestContext> context) override {
    ReadyProfiles().insert(key_);
    const PrefMap& own = ProfilePrefs()[key_];
    if (key_ == kIncognitoKey) {
      // Includes what the default profile saved in an earlier run.
      CefRefPtr<CefRequestContext> global =
          CefRequestContext::GetGlobalContext();
      for (const char* name : kInheritedByIncognito) {
        if (!own.count(name)) {
          if (CefRefPtr<CefValue> value = global->GetPreference(name)) {
            ApplyProfilePref(context, name, value);
          }
        }
      }
    }
    for (const auto& [name, value] : own) {
      ApplyProfilePref(context, name, value);
    }
  }

  IMPLEMENT_REFCOUNTING(ProfilePrefsApplier);

 private:
  const std::string key_;
};
int& IncognitoBrowserCount() {
  static int count = 0;
  return count;
}
// Get-or-create the shared incognito context and register one more user.
CefRefPtr<CefRequestContext> AcquireIncognitoContext() {
  if (!IncognitoContext()) {
    CefRequestContextSettings settings;
    // Empty cache_path -> isolated, in-memory "incognito" context.
    ReadyProfiles().erase(kIncognitoKey);
    IncognitoContext() = CefRequestContext::CreateContext(
        settings, new ProfilePrefsApplier(kIncognitoKey));
  }
  ++IncognitoBrowserCount();
  return IncognitoContext();
}
// Drop one user; when none remain, release the context to clear the session.
void ReleaseIncognitoContext() {
  if (IncognitoBrowserCount() > 0 && --IncognitoBrowserCount() == 0) {
    IncognitoContext() = nullptr;
  }
}

// FEATURE-071: the engine's data root (CefSettings.root_cache_path), set by
// nativeInitialize. Named profiles live in <root>/profile-<name>: the Chrome
// runtime only creates a disk profile whose directory is an immediate child of
// the root, and silently falls back to an in-memory one otherwise.
std::string& DataRoot() {
  static base::NoDestructor<std::string> root;
  return *root;
}

// FEATURE-071: one persistent context per named profile, created on first use
// and kept for the life of the process (CEF does not close a context's
// databases when its last browser goes away). Java validates the name.
// Touched only on the CEF UI thread, like IncognitoContext.
std::map<std::string, CefRefPtr<CefRequestContext>>& ProfileContexts() {
  static base::NoDestructor<std::map<std::string, CefRefPtr<CefRequestContext>>>
      contexts;
  return *contexts;
}
CefRefPtr<CefRequestContext> ProfileContext(const std::string& name) {
  CefRefPtr<CefRequestContext>& ctx = ProfileContexts()[name];
  if (!ctx) {
    CefRequestContextSettings settings;
    CefString(&settings.cache_path) = DataRoot() + "/profile-" + name;
    ctx = CefRequestContext::CreateContext(settings,
                                           new ProfilePrefsApplier(name));
  }
  return ctx;
}

}  // namespace

namespace cefrium {

CefRefPtr<CefRequestContext> ContextForProfile(bool incognito,
                                               const std::string& name) {
  if (incognito) {
    return IncognitoContext();
  }
  return name.empty() ? CefRequestContext::GetGlobalContext()
                      : ProfileContext(name);
}

}  // namespace cefrium

// Document-start scripts, as Android WebView's addDocumentStartJavaScript:
// components/js_injection's host hands each script to every frame of the
// WebContents as the frame is created, over the frame's own channel, so the
// renderer has it before the frame's first document commits (cross-process
// iframes and new renderer processes included). One host per WebContents,
// gone with it.
class DocumentScripts
    : public content::WebContentsUserData<DocumentScripts> {
 public:
  js_injection::JsCommunicationHost& host() { return host_; }

 private:
  explicit DocumentScripts(content::WebContents* web_contents)
      : content::WebContentsUserData<DocumentScripts>(*web_contents),
        host_(web_contents) {}
  friend WebContentsUserData;
  WEB_CONTENTS_USER_DATA_KEY_DECL();

  js_injection::JsCommunicationHost host_;
};
WEB_CONTENTS_USER_DATA_KEY_IMPL(DocumentScripts);

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
  void OnTouchHandleStateChanged(CefRefPtr<CefBrowser> browser,
                                 const CefTouchHandleState& state) override {
    if (!java_browser_) return;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onTouchHandleStateChanged",
                                        "(IIIZ)V");
    if (method) {
      // id/orientation, x, y, enabled.
      env->CallVoidMethod(java_browser_, method,
                          static_cast<jint>(state.orientation),
                          state.origin.x, state.origin.y,
                          state.enabled ? JNI_TRUE : JNI_FALSE);
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

  // FEATURE-029: window.open / target=_blank. Embedded single-surface model:
  // never spawn a separate window. Ask the Java PopupHandler; if it doesn't
  // take it, navigate the same browser to the target URL.
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
                     bool* no_javascript_access) override {
    bool handled = false;
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) {
        jclass cls = env->GetObjectClass(java_browser_);
        jmethodID mid = env->GetMethodID(cls, "onOpenPopup",
                                         "(Ljava/lang/String;Z)Z");
        if (mid) {
          jstring ju = env->NewStringUTF(target_url.ToString().c_str());
          handled = env->CallBooleanMethod(java_browser_, mid, ju,
                                           user_gesture ? JNI_TRUE : JNI_FALSE);
          env->DeleteLocalRef(ju);
        }
        env->DeleteLocalRef(cls);
      }
    }
    if (!handled && browser && browser->GetMainFrame() &&
        !target_url.empty()) {
      browser->GetMainFrame()->LoadURL(target_url);
    }
    return true;  // always cancel the separate popup window
  }

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

  // FEATURE-063: per-frame navigation start (main frame only up-called).
  void OnLoadStart(CefRefPtr<CefBrowser> browser,
                   CefRefPtr<CefFrame> frame,
                   TransitionType transition_type) override {
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

  void OnTitleChange(CefRefPtr<CefBrowser> browser,
                     const CefString& title) override {
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
    jmethodID method = env->GetMethodID(cls, "onConsoleMessage",
                                        "(ILjava/lang/String;Ljava/lang/String;I)V");
    if (method) {
      jstring j_msg = env->NewStringUTF(message.ToString().c_str());
      jstring j_src = env->NewStringUTF(source.ToString().c_str());
      env->CallVoidMethod(java_browser_, method, static_cast<jint>(level),
                          j_msg, j_src, line);
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
};

// The app's own Downloads directory, set by Cefrium.initialize. A download the
// app allows without a path is saved there.
std::string& DefaultDownloadDir() {
  static base::NoDestructor<std::string> dir;
  return *dir;
}

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

    // Build menu items string: "id1|label1\nid2|label2\n..."
    std::string menu_data;
    for (size_t i = 0; i < model->GetCount(); i++) {
      if (i > 0) menu_data += "\n";
      menu_data += std::to_string(model->GetCommandIdAt(i)) + "|" +
                   model->GetLabelAt(i).ToString();
    }

    pending_callback_ = callback;

    // Call Java to show AlertDialog.
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(cls, "onContextMenu",
        "(Ljava/lang/String;Ljava/lang/String;)V");
    if (method) {
      jstring j_menu = env->NewStringUTF(menu_data.c_str());
      std::string url = params->GetLinkUrl().ToString();
      if (url.empty()) url = params->GetSourceUrl().ToString();
      jstring j_url = env->NewStringUTF(url.c_str());
      env->CallVoidMethod(java_browser_, method, j_menu, j_url);
      env->DeleteLocalRef(j_menu);
      env->DeleteLocalRef(j_url);
    }
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

// Errors that only a proxy causes: the request failed because of the proxy,
// not the site.
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
  int blocked_count() const { return blocked_count_; }

  // The Java browser is the same object for the browser's lifetime; the first
  // reference is kept, so the IO thread never sees it change.
  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    if (!java_browser || java_browser_) {
      return;
    }
    jclass cls = env->GetObjectClass(java_browser);
    should_block_ = env->GetMethodID(
        cls, "onShouldBlockRequest", "(Ljava/lang/String;Ljava/lang/String;Z)Z");
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
    const int observers = observers_;
    std::shared_ptr<const AdBlockFilter> filter =
        adblock_enabled_ ? AdBlockFilter::Current() : nullptr;
    if (!filter && !(observers & (kInterceptor | kInterceptListener))) {
      return RV_CONTINUE;
    }
    const std::string url = request->GetURL().ToString();

    bool blocked =
        filter && filter->ShouldBlock(GURL(url), DocumentOf(frame, request),
                                      request->GetResourceType());

    jobject jb = java_browser_;
    JNIEnv* env = jb ? base::android::AttachCurrentThread() : nullptr;
    if (!blocked && env && should_block_ && (observers & kInterceptor)) {
      jstring ju = env->NewStringUTF(url.c_str());
      jstring jm = env->NewStringUTF(request->GetMethod().ToString().c_str());
      blocked = env->CallBooleanMethod(jb, should_block_, ju, jm,
                                       frame && frame->IsMain());
      env->DeleteLocalRef(ju);
      env->DeleteLocalRef(jm);
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
                         CefRefPtr<CefRequest> request) {
    if (!frame) {
      return GURL(request->GetReferrerURL().ToString());
    }
    if (request->GetResourceType() == RT_SUB_FRAME) {
      if (CefRefPtr<CefFrame> parent = frame->GetParent()) {
        return GURL(parent->GetURL().ToString());
      }
    }
    return GURL(frame->GetURL().ToString());
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
  std::atomic<int> blocked_count_{0};
  std::atomic<bool> response_tap_enabled_{false};
  std::atomic<bool> cookie_filter_enabled_{false};
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

// FEATURE-023 step 4: browser-side handler for window.cefriumQuery. Forwards
// each query to the Java CefriumBrowser (onJsQuery); Java dispatches to the
// app's QueryHandler and resolves via nativeQueryResult -> ResolveQuery.
//
// Step 4 supports SYNCHRONOUS responses (the Java handler calls the callback
// within onQuery, on the browser UI thread). Asynchronous responses (off the UI
// thread) are step 4b and require UI-thread posting + a ref-counted handler for
// lifetime safety; ResolveQuery rejects off-thread calls until then.
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
    pending_[query_id] = callback;
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) {
      pending_.erase(query_id);
      return false;
    }
    // The frame's URL is the origin of the query — passed to Java so the app
    // can gate which pages/iframes are allowed to use the bridge (security).
    std::string origin = frame ? frame->GetURL().ToString() : std::string();
    // The committed origin as well: a sandboxed frame at an allowed URL has an
    // opaque origin, and Java denies it. A detached frame counts as opaque.
    std::string committed_origin = "null";
    // The origin of the document that SENT the query, stamped by the renderer
    // at send time. The browser can handle the query after the frame has
    // committed the next document (measured: a parent firing queries from an
    // iframe's initial document saw them judged as the iframe's next page), so
    // the committed origin alone does not identify the sender.
    std::string sender_origin = "null";
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
      if (const auto& sender = host->current_message_sender_origin()) {
        sender_origin = sender->Serialize();
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
      pending_.erase(query_id);
      env->DeleteLocalRef(cls);
      return false;
    }
    env->DeleteLocalRef(cls);
    return true;
  }

  void OnQueryCanceled(CefRefPtr<CefBrowser> browser,
                       CefRefPtr<CefFrame> frame,
                       int64_t query_id) override {
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
                                     "(JLjava/lang/String;I)V");
    if (mid) {
      jstring jorigin =
          env->NewStringUTF(requesting_origin.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, static_cast<jlong>(prompt_id),
                          jorigin, static_cast<jint>(requested_permissions));
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
    jint java_mask = 0;
    if (requested_permissions & CEF_MEDIA_PERMISSION_DEVICE_VIDEO_CAPTURE) {
      java_mask |= (1 << 2);   // PERMISSION_CAMERA
    }
    if (requested_permissions & CEF_MEDIA_PERMISSION_DEVICE_AUDIO_CAPTURE) {
      java_mask |= (1 << 12);  // PERMISSION_MICROPHONE
    }
    JNIEnv* env = base::android::AttachCurrentThread();
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid = env->GetMethodID(cls, "onPermissionRequest",
                                     "(JLjava/lang/String;I)V");
    if (mid) {
      jstring jorigin =
          env->NewStringUTF(requesting_origin.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, static_cast<jlong>(id), jorigin,
                          java_mask);
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
        cls, "onFileDialog", "(JILjava/lang/String;[Ljava/lang/String;)V");
    bool dispatched = false;
    if (mid) {
      jstring j_title = env->NewStringUTF(title.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, id, static_cast<jint>(mode),
                          j_title, arr);
      env->DeleteLocalRef(j_title);
      dispatched = true;
    }
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(str_cls);
    if (!dispatched) {
      base::AutoLock l(lock_);
      pending_.erase(id);
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

class SdkClient : public CefClient {
 public:
  SdkClient(CefRefPtr<SdkRenderHandler> render_handler,
            CefRefPtr<SdkLifeSpanHandler> life_span_handler,
            bool with_request_handler = false,
            struct SdkBrowserData* browser_data = nullptr)
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
    if (browser_data) {
      load_handler_->SetBrowserData(browser_data);
    }

    // FEATURE-023: browser-side JS bridge router for window.cefriumQuery. The
    // config MUST match the renderer side (shared constants).
    CefMessageRouterConfig config;
    config.js_query_function = cefrium_bridge::kQueryFunction;
    config.js_cancel_function = cefrium_bridge::kCancelFunction;
    message_router_ = CefMessageRouterBrowserSide::Create(config);
    bridge_handler_ = std::make_unique<CefriumBridgeHandler>();
    message_router_->AddHandler(bridge_handler_.get(), /*first=*/false);
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

// Per-browser data stored as the native pointer in Java.
// A loadDataWithBaseUrl() requested before the native browser exists. The browser
// is created lazily (it needs a URL at creation for the renderer to spawn), so a
// cold first data-load is created at about:blank and applied in OnAfterCreated.
struct SdkPendingDataLoad {
  std::string data;
  std::string mime_type;
  std::string charset;
  std::string base_url;
  std::string history_url;
};

// Issue #16: <select>, date/time and colour pickers of a browser without a
// window. Each request goes to the Java CefriumBrowser, which answers through
// nativePicker*Result with the request's id.
class SdkPickerHost : public cef_android::PickerHost {
 public:
  static SdkPickerHost* Get() {
    static base::NoDestructor<SdkPickerHost> host;
    return host.get();
  }

  void ShowSelect(content::WebContents* web_contents,
                  std::vector<SelectItem> items,
                  int selected_item,
                  bool allow_multiple_selection,
                  SelectCallback callback) override {
    jobject java_browser = SdkClient::JavaBrowserFor(web_contents);
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!java_browser || !env) {
      std::move(callback).Run(std::nullopt);
      return;
    }
    const int64_t id = ++last_id_;
    selects_[id] = std::move(callback);

    const jsize n = static_cast<jsize>(items.size());
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray labels = env->NewObjectArray(n, string_class, nullptr);
    std::vector<jint> types(n);
    std::vector<jboolean> enabled(n);
    std::vector<jboolean> checked(n);
    for (jsize i = 0; i < n; ++i) {
      jstring label =
          base::android::ConvertUTF16ToJavaString(env, items[i].label).Release();
      env->SetObjectArrayElement(labels, i, label);
      env->DeleteLocalRef(label);
      types[i] = static_cast<jint>(items[i].type);
      enabled[i] = items[i].enabled;
      checked[i] = items[i].checked;
    }
    jintArray jtypes = env->NewIntArray(n);
    env->SetIntArrayRegion(jtypes, 0, n, types.data());
    jbooleanArray jenabled = env->NewBooleanArray(n);
    env->SetBooleanArrayRegion(jenabled, 0, n, enabled.data());
    jbooleanArray jchecked = env->NewBooleanArray(n);
    env->SetBooleanArrayRegion(jchecked, 0, n, checked.data());

    jclass cls = env->GetObjectClass(java_browser);
    jmethodID mid = env->GetMethodID(cls, "onShowSelect",
                                     "(J[Ljava/lang/String;[I[Z[ZIZ)Z");
    bool shown = mid && env->CallBooleanMethod(java_browser, mid, id, labels,
                                               jtypes, jenabled, jchecked,
                                               selected_item,
                                               allow_multiple_selection);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      shown = false;
    }
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(jchecked);
    env->DeleteLocalRef(jenabled);
    env->DeleteLocalRef(jtypes);
    env->DeleteLocalRef(labels);
    env->DeleteLocalRef(string_class);
    if (!shown) {
      ResolveSelect(id, std::nullopt);
    }
  }

  void ShowDateTime(content::WebContents* web_contents,
                    int dialog_type,
                    double value,
                    double minimum,
                    double maximum,
                    double step,
                    DateTimeCallback callback) override {
    jobject java_browser = SdkClient::JavaBrowserFor(web_contents);
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!java_browser || !env) {
      std::move(callback).Run(std::nullopt);
      return;
    }
    const int64_t id = ++last_id_;
    date_times_[id] = std::move(callback);
    jclass cls = env->GetObjectClass(java_browser);
    jmethodID mid = env->GetMethodID(cls, "onShowDateTime", "(JIDDDD)Z");
    bool shown = mid && env->CallBooleanMethod(java_browser, mid, id,
                                               dialog_type, value, minimum,
                                               maximum, step);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      shown = false;
    }
    env->DeleteLocalRef(cls);
    if (!shown) {
      ResolveDateTime(id, std::nullopt);
    }
  }

  void ShowColor(content::WebContents* web_contents,
                 SkColor color,
                 std::vector<SkColor> suggestions,
                 ColorCallback callback) override {
    jobject java_browser = SdkClient::JavaBrowserFor(web_contents);
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!java_browser || !env) {
      std::move(callback).Run(std::nullopt);
      return;
    }
    const int64_t id = ++last_id_;
    colors_[id] = std::move(callback);
    const jsize n = static_cast<jsize>(suggestions.size());
    std::vector<jint> values(suggestions.begin(), suggestions.end());
    jintArray jsuggestions = env->NewIntArray(n);
    env->SetIntArrayRegion(jsuggestions, 0, n, values.data());
    jclass cls = env->GetObjectClass(java_browser);
    jmethodID mid = env->GetMethodID(cls, "onShowColor", "(JI[I)Z");
    bool shown = mid && env->CallBooleanMethod(java_browser, mid, id,
                                               static_cast<jint>(color),
                                               jsuggestions);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      shown = false;
    }
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(jsuggestions);
    if (!shown) {
      ResolveColor(id, std::nullopt);
    }
  }

  // UI thread.
  void ResolveSelect(int64_t id, std::optional<std::vector<int>> indices) {
    auto it = selects_.find(id);
    if (it == selects_.end()) {
      return;
    }
    SelectCallback callback = std::move(it->second);
    selects_.erase(it);
    std::move(callback).Run(std::move(indices));
  }

  void ResolveDateTime(int64_t id, std::optional<double> value) {
    auto it = date_times_.find(id);
    if (it == date_times_.end()) {
      return;
    }
    DateTimeCallback callback = std::move(it->second);
    date_times_.erase(it);
    std::move(callback).Run(value);
  }

  void ResolveColor(int64_t id, std::optional<SkColor> color) {
    auto it = colors_.find(id);
    if (it == colors_.end()) {
      return;
    }
    ColorCallback callback = std::move(it->second);
    colors_.erase(it);
    std::move(callback).Run(color);
  }

 private:
  int64_t last_id_ = 0;
  std::map<int64_t, SelectCallback> selects_;
  std::map<int64_t, DateTimeCallback> date_times_;
  std::map<int64_t, ColorCallback> colors_;
};

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativePickerSelectResult(JNIEnv* env,
                                                         jclass clazz,
                                                         jlong id,
                                                         jintArray indices) {
  std::optional<std::vector<int>> chosen;
  if (indices) {
    std::vector<jint> values(env->GetArrayLength(indices));
    env->GetIntArrayRegion(indices, 0, static_cast<jsize>(values.size()),
                           values.data());
    chosen = std::vector<int>(values.begin(), values.end());
  }
  CefPostTask(TID_UI, base::BindOnce(
                          [](int64_t id, std::optional<std::vector<int>> v) {
                            SdkPickerHost::Get()->ResolveSelect(id,
                                                                std::move(v));
                          },
                          static_cast<int64_t>(id), std::move(chosen)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativePickerDateTimeResult(JNIEnv* env,
                                                           jclass clazz,
                                                           jlong id,
                                                           jboolean ok,
                                                           jdouble value) {
  std::optional<double> chosen;
  if (ok) {
    chosen = value;
  }
  CefPostTask(TID_UI, base::BindOnce(
                          [](int64_t id, std::optional<double> v) {
                            SdkPickerHost::Get()->ResolveDateTime(id, v);
                          },
                          static_cast<int64_t>(id), chosen));
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativePickerColorResult(JNIEnv* env,
                                                        jclass clazz,
                                                        jlong id,
                                                        jboolean ok,
                                                        jint color) {
  std::optional<SkColor> chosen;
  if (ok) {
    chosen = static_cast<SkColor>(color);
  }
  CefPostTask(TID_UI, base::BindOnce(
                          [](int64_t id, std::optional<SkColor> v) {
                            SdkPickerHost::Get()->ResolveColor(id, v);
                          },
                          static_cast<int64_t>(id), chosen));
}

struct SdkBrowserData {
  CefRefPtr<CefBrowser> browser;
  // Background of an off-screen browser, applied when it is created at the
  // first load. A fully transparent colour makes transparent page areas come
  // back with alpha 0 in OnPaint (measured on 156).
  cef_color_t background_color = CefColorSetARGB(255, 255, 255, 255);
  CefRefPtr<SdkRenderHandler> render_handler;
  CefRefPtr<SdkLifeSpanHandler> life_span_handler;
  CefRefPtr<SdkClient> client;
  std::string pending_url;
  std::unique_ptr<SdkPendingDataLoad> pending_data;
  // Navigation stack to Restore() once the browser exists (session/tab restore):
  // the browser is created at about:blank so the renderer spawns, then this is
  // applied in OnAfterCreated before any real navigation.
  std::vector<uint8_t> pending_nav_state;
  // setMediaPlaybackRequiresUserGesture before the browser exists; applied in
  // OnAfterCreated, which runs before the first navigation.
  std::optional<bool> media_playback_requires_user_gesture;
  // addDocumentStartJavaScript: scripts added before the browser exists, and
  // our ids for the scripts the JsCommunicationHost holds (ours -> its).
  struct PendingScript {
    int id;
    std::u16string script;
    std::vector<std::string> rules;
  };
  std::vector<PendingScript> pending_scripts;
  std::map<int, int> script_ids;
  int next_script_id = 1;

  // Surface rendering mode (null for OSR mode).
  std::unique_ptr<BrowserSurfaceAndroid> surface_bridge;
  ui::WindowAndroid* window_android = nullptr;  // Owned by Java via prevent-GC ref.
  bool is_surface_mode = false;
  // FEATURE-057: created with the shared off-the-record context (private tab).
  bool is_incognito = false;
  // FEATURE-071: named profile; empty = the global (default) context.
  std::string profile;
  // Whether this browser holds an incognito reference to release on close.
  bool holds_incognito = false;
};

namespace {
CefString JStr(JNIEnv* env, jstring j);  // Defined with the cookie helpers.

// FEATURE-071: the request context a new browser of |data| is created with:
// incognito, a named profile, or nullptr for the global context. Takes the
// incognito reference that OnBeforeClose gives back.
CefRefPtr<CefRequestContext> ContextFor(SdkBrowserData* data) {
  if (data->is_incognito) {
    // One reference per browser, even if both lazy OSR paths run before the
    // browser exists.
    if (data->holds_incognito) {
      return IncognitoContext();
    }
    data->holds_incognito = true;
    return AcquireIncognitoContext();
  }
  if (!data->profile.empty()) {
    return ProfileContext(data->profile);
  }
  return nullptr;
}
}  // namespace

// Rebuild a navigation stack captured by nativeSerializeNavigationState onto
// |wc|'s controller (must be a fresh WebContents -- no committed navigation) and
// load the selected entry, so back/forward works without re-fetching pages.
void ApplyNavigationState(content::WebContents* wc,
                          const std::vector<uint8_t>& bytes) {
  if (!wc || bytes.empty()) {
    return;
  }
  base::Pickle pickle = base::Pickle::WithData(bytes);
  base::PickleIterator it(pickle);
  int current = 0, count = 0;
  if (!it.ReadInt(&current) || !it.ReadInt(&count) || count <= 0) {
    return;
  }
  content::BrowserContext* browser_context = wc->GetBrowserContext();
  std::unique_ptr<content::NavigationEntryRestoreContext> restore_context =
      content::NavigationEntryRestoreContext::Create();
  std::vector<std::unique_ptr<content::NavigationEntry>> entries;
  entries.reserve(count);
  for (int i = 0; i < count; ++i) {
    std::string url_str, page_state_str;
    std::u16string title;
    if (!it.ReadString(&url_str) || !it.ReadString16(&title) ||
        !it.ReadString(&page_state_str)) {
      return;
    }
    std::unique_ptr<content::NavigationEntry> entry =
        content::NavigationController::CreateNavigationEntry(
            GURL(url_str), content::Referrer(), /*initiator_origin=*/std::nullopt,
            /*initiator_base_url=*/std::nullopt, ui::PAGE_TRANSITION_RELOAD,
            /*is_renderer_initiated=*/false, /*extra_headers=*/std::string(),
            browser_context, /*blob_url_loader_factory=*/nullptr);
    if (!page_state_str.empty()) {
      entry->SetPageState(blink::PageState::CreateFromEncodedData(page_state_str),
                          restore_context.get());
    }
    entry->SetTitle(title);
    entries.push_back(std::move(entry));
  }
  if (current < 0 || current >= count) {
    current = count - 1;
  }
  // Cancel any pending initial navigation so Restore() runs on an empty
  // controller (it requires no committed/pending entry). Do NOT load here: the
  // restored page is loaded later, from connectWebContentsInternal via
  // nativeLoadRestoredIfNecessary(), once the on-screen surface and the
  // pull-to-refresh overscroll handler are set up. Loading before that setup
  // navigates while the OverscrollControllerAndroid has no handler, and the
  // RenderFrameHost swap on commit crashes on the render-per-browser process
  // model (RWHVA::HideInternal -> OverscrollControllerAndroid::Disable).
  content::NavigationController& controller = wc->GetController();
  wc->Stop();
  controller.DiscardNonCommittedEntries();
  controller.Restore(current, content::RestoreType::kRestored, &entries);
}

void SdkLifeSpanHandler::OnAfterCreated(CefRefPtr<CefBrowser> browser) {
  LOGI("OnAfterCreated: browser ready (id=%d, surface_mode=%d)",
       browser->GetIdentifier(), data_ ? data_->is_surface_mode : 0);
  if (data_) {
    data_->browser = browser;
    if (!data_->pending_scripts.empty()) {
      content::WebContents* wc = static_cast<CefBrowserHostBase*>(
                                     browser->GetHost().get())
                                     ->GetWebContents();
      if (wc) {
        DocumentScripts::CreateForWebContents(wc);
        auto& host = DocumentScripts::FromWebContents(wc)->host();
        for (auto& pending : data_->pending_scripts) {
          auto result = host.AddPersistentJavaScript(
              pending.script,
              js_injection::mojom::DocumentInjectionTime::kDocumentStart,
              pending.rules, /*world_identifier=*/0);
          if (result.script_id) {
            data_->script_ids[pending.id] = *result.script_id;
          }
        }
      }
      data_->pending_scripts.clear();
    }
    if (data_->media_playback_requires_user_gesture.has_value()) {
      static_cast<CefBrowserHostBase*>(browser->GetHost().get())
          ->SetMediaPlaybackRequiresUserGesture(
              data_->media_playback_requires_user_gesture);
    }

    // In Surface mode, attach the WebContents to the compositor.
    if (data_->is_surface_mode && data_->surface_bridge) {
      auto* browser_impl = static_cast<CefBrowserHostBase*>(
          browser->GetHost().get());
      content::WebContents* web_contents =
          browser_impl ? browser_impl->GetWebContents() : nullptr;
      if (web_contents) {
        data_->surface_bridge->AttachWebContents(web_contents);
        LOGI("Surface mode: WebContents attached to compositor");
      }
    }

    // Apply a loadDataWithBaseUrl() that arrived before the browser existed
    // (the browser was created at about:blank to spawn the renderer).
    if (data_->pending_data) {
      auto* host =
          static_cast<CefBrowserHostBase*>(browser->GetHost().get());
      if (host) {
        host->LoadMainFrameDataWithBaseURL(
            data_->pending_data->data, data_->pending_data->mime_type,
            data_->pending_data->charset, data_->pending_data->base_url,
            data_->pending_data->history_url);
      }
      data_->pending_data.reset();
    }

    // Apply a navigation stack that arrived before the browser existed (tab
    // restore): the browser was created at about:blank so the controller is at
    // its initial entry -- Restore replaces it and loads the selected page.
    if (!data_->pending_nav_state.empty()) {
      auto* host =
          static_cast<CefBrowserHostBase*>(browser->GetHost().get());
      ApplyNavigationState(host ? host->GetWebContents() : nullptr,
                           data_->pending_nav_state);
      data_->pending_nav_state.clear();
    }
  }

  // The WebContents exists from here on. Tell Java, so a surface-mode browser
  // connects it to its WindowAndroid now instead of after a fixed delay: until
  // that connection no renderer of this browser receives BeginFrames, so it
  // cannot paint, cannot end paint holding, and Blink suppresses its input.
  browser_created_ = true;
  NotifyJavaBrowserCreated();
}

void SdkLifeSpanHandler::NotifyJavaBrowserCreated() {
  if (!browser_created_ || !java_browser_ || created_notified_) {
    return;
  }
  created_notified_ = true;
  JNIEnv* env = base::android::AttachCurrentThread();
  if (!env) {
    return;
  }
  jclass cls = env->GetObjectClass(java_browser_);
  jmethodID mid = env->GetMethodID(cls, "onNativeBrowserCreated", "()V");
  if (mid) {
    env->CallVoidMethod(java_browser_, mid);
  } else {
    env->ExceptionClear();
  }
  env->DeleteLocalRef(cls);
}

// Binds the Java browser right after creation to the handlers whose events do
// not depend on the embedder registering a listener (nativeSetDisplayCallback)
// first: the created notification, and proxy errors, which are reported per
// profile.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeBindJavaBrowser(JNIEnv* env,
                                                      jclass clazz,
                                                      jlong native_ptr,
                                                      jobject java_browser) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) {
    return;
  }
  if (data->life_span_handler) {
    data->life_span_handler->SetJavaBrowser(env, java_browser);
  }
  if (data->client && data->client->request_handler() &&
      data->client->request_handler()->resource_request_handler()) {
    data->client->request_handler()->resource_request_handler()->SetJavaBrowser(
        env, java_browser);
  }
}

// Out-of-line: needs the full SdkBrowserData definition (member access on data_).
void SdkLifeSpanHandler::OnBeforeClose(CefRefPtr<CefBrowser> browser) {
  extern std::atomic<int> g_closed_browser_count;
  g_closed_browser_count.fetch_add(1, std::memory_order_relaxed);
  if (message_router_) {
    message_router_->OnBeforeClose(browser);
  }
  if (!data_) {
    return;
  }
  // FEATURE-057: last incognito tab closing drops the shared OTR context, which
  // clears its in-memory cookies/history/cache (clear-on-close).
  if (data_->holds_incognito) {
    ReleaseIncognitoContext();
  }
  // The browser has now actually closed -- safe to tear down. (Doing this in
  // nativeClose, before the async close completed, orphaned the WebContents.)
  data_->surface_bridge.reset();
  {
    base::AutoLock lock(BridgeDataLock());
    LiveBridgeData().erase(reinterpret_cast<jlong>(data_));
  }
  // Tell Java to destroy its ContentViewRenderView + WindowAndroid now.
  if (java_browser_) {
    JNIEnv* env = base::android::AttachCurrentThread();
    if (env) {
      jclass cls = env->GetObjectClass(java_browser_);
      jmethodID mid = env->GetMethodID(cls, "onNativeBrowserClosed", "()V");
      if (mid) {
        env->CallVoidMethod(java_browser_, mid);
      }
      env->DeleteLocalRef(cls);
    }
  }
  // Defer the delete: we are inside data_->life_span_handler's own method, so
  // freeing data_ (which owns this handler) now could drop the last ref mid-call.
  SdkBrowserData* d = data_;
  data_ = nullptr;
  CefPostTask(TID_UI, CefCreateClosureTask(base::BindOnce(
                          [](SdkBrowserData* p) { delete p; }, d)));
}

// Out-of-line definition — needs full SdkBrowserData.
void SdkLoadHandler::OnLoadEnd(CefRefPtr<CefBrowser> browser,
                               CefRefPtr<CefFrame> frame,
                               int httpStatusCode) {
  LOGI("OnLoadEnd: status %d, isMain=%d", httpStatusCode, frame->IsMain());
  if (frame->IsMain()) {
    browser->GetHost()->Invalidate(PET_VIEW);
    if (data_ && data_->is_surface_mode && data_->surface_bridge) {
      data_->surface_bridge->TryAttachRWHVLayer();
    }
  }
}

// FEATURE: Web Share (navigator.share). A DocumentService-scoped implementation
// of blink::mojom::ShareService that routes the share to the embedding app's
// Android share sheet via the SdkClient. Replaces the default Android binding
// (ForwardToJavaWebContents), whose Chrome Java share machinery is absent here.
class CefShareServiceImpl
    : public content::DocumentService<blink::mojom::ShareService> {
 public:
  static void Create(
      content::RenderFrameHost* render_frame_host,
      mojo::PendingReceiver<blink::mojom::ShareService> receiver) {
    // Owns itself; destroyed when the document goes away or the pipe closes.
    new CefShareServiceImpl(*render_frame_host, std::move(receiver));
  }

  // blink::mojom::ShareService:
  void Share(const std::string& title,
             const std::string& text,
             const GURL& url,
             std::vector<blink::mojom::SharedFilePtr> files,
             ShareCallback callback) override {
    // File sharing is not supported yet; title/text/url cover navigator.share's
    // common case.
    CefRefPtr<CefBrowserHostBase> browser =
        CefBrowserHostBase::GetBrowserForHost(&render_frame_host());
    SdkClient* client =
        browser ? static_cast<SdkClient*>(browser->GetClient().get()) : nullptr;
    const bool ok =
        client && client->FireWebShare(title, text, url.is_valid() ? url.spec()
                                                                    : std::string());
    std::move(callback).Run(ok ? blink::mojom::ShareError::OK
                               : blink::mojom::ShareError::INTERNAL_ERROR);
  }

 private:
  CefShareServiceImpl(
      content::RenderFrameHost& render_frame_host,
      mojo::PendingReceiver<blink::mojom::ShareService> receiver)
      : DocumentService(render_frame_host, std::move(receiver)) {}
};

namespace cefrium {
void BindShareService(
    content::RenderFrameHost* frame,
    mojo::PendingReceiver<blink::mojom::ShareService> receiver) {
  CefShareServiceImpl::Create(frame, std::move(receiver));
}
}  // namespace cefrium

// --- Cefrium.java native methods ---

extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_Cefrium_nativeInitialize(
    JNIEnv* env, jclass clazz, jstring cache_dir_jstr, jstring data_dir_jstr,
    jstring download_dir_jstr) {
  const char* cache_dir = env->GetStringUTFChars(cache_dir_jstr, nullptr);
  const char* data_dir = env->GetStringUTFChars(data_dir_jstr, nullptr);
  DefaultDownloadDir() =
      base::android::ConvertJavaStringToUTF8(env, download_dir_jstr);

  static const char* argv[] = {"cefrium"};
  CefMainArgs main_args(1, const_cast<char**>(argv));
  // No browser-process app: browser-side handlers live on the SdkClient, and
  // the renderer's app is registered in cef_jni_onload.cc.
  int exit_code = CefExecuteProcess(main_args, nullptr, nullptr);
  if (exit_code >= 0) {
    env->ReleaseStringUTFChars(cache_dir_jstr, cache_dir);
    env->ReleaseStringUTFChars(data_dir_jstr, data_dir);
    return exit_code;
  }

  CefSettings settings;
  // no_sandbox stays UNSET. On Android it does not change child routing --
  // renderers always land in an isolated SandboxedProcessService -- it only
  // stops renderer_main from installing Chromium's seccomp-BPF policy, which
  // left every child on the Android zygote baseline filter alone.
  settings.windowless_rendering_enabled = true;
  settings.multi_threaded_message_loop = false;
  settings.log_severity = LOGSEVERITY_INFO;
  CefString(&settings.root_cache_path).FromASCII(cache_dir);
  DataRoot() = cache_dir;

  // Identify as Chrome Android so sites serve full-featured content. CEF IS
  // Chromium -- accurate identification, not spoofing.
  //
  // The version comes from CEF's build constants, NOT version_info. Since
  // Chromium 156 (upstream 7cb5ec9c41e7f) version_info::GetVersionNumber() on
  // Android returns apk_info::package_version_name(), i.e. the HOST APP's
  // android:versionName -- so every consumer advertised its own app version as
  // the Chrome version. A sample at versionName "0.1.0" shipped
  // "Chrome/0.1.0". The engine version is a build property; read it as one.
  std::string ua_product =
      "Chrome/" MAKE_STRING(CHROME_VERSION_MAJOR) "." MAKE_STRING(
          CHROME_VERSION_MINOR) "." MAKE_STRING(CHROME_VERSION_BUILD) "." MAKE_STRING(CHROME_VERSION_PATCH)
      " Cefrium/1.0 Mobile";
  CefString(&settings.user_agent_product).FromASCII(ua_product.c_str());

  std::string locales_dir = std::string(data_dir) + "/locales";
  CefString(&settings.locales_dir_path).FromASCII(locales_dir.c_str());

  bool result = CefInitialize(main_args, settings, nullptr, nullptr);
  if (result) {
    cef_android::SetPickerHost(SdkPickerHost::Get());
  }
  env->ReleaseStringUTFChars(cache_dir_jstr, cache_dir);
  env->ReleaseStringUTFChars(data_dir_jstr, data_dir);

  return result ? 0 : 1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_Cefrium_nativeShutdown(JNIEnv* env, jclass clazz) {
  CefShutdown();
}

// --- CefriumBrowser.java native methods ---

extern "C" JNIEXPORT jlong JNICALL
Java_com_cefrium_CefriumBrowser_nativeCreate(
    JNIEnv* env, jclass clazz, jint width, jint height, jfloat scale,
    jboolean incognito, jstring profile) {
  auto* data = new SdkBrowserData();
  data->is_incognito = (incognito == JNI_TRUE);
  data->profile = JStr(env, profile).ToString();
  data->render_handler = new SdkRenderHandler(width, height, scale);
  data->life_span_handler = new SdkLifeSpanHandler();
  data->life_span_handler->SetBrowserData(data);
  data->client = new SdkClient(data->render_handler, data->life_span_handler,
                               /*with_request_handler=*/true);
  LOGI("nativeCreate: %dx%d (browser not yet created, adblock active)", width, height);
  {
    base::AutoLock lock(BridgeDataLock());
    LiveBridgeData().insert(reinterpret_cast<jlong>(data));
  }
  return reinterpret_cast<jlong>(data);
}

// Actually create the browser with the URL (must have URL at creation time
// for renderer process to spawn correctly).
// Sets the background of an off-screen browser that does not exist yet. Returns
// false when it is too late (already created) or not an off-screen browser.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetBackgroundColor(JNIEnv* env,
                                                         jclass clazz,
                                                         jlong native_ptr,
                                                         jint argb) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || data->browser || data->is_surface_mode) {
    return JNI_FALSE;
  }
  data->background_color = static_cast<cef_color_t>(argb);
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeLoadUrl(
    JNIEnv* env, jobject thiz, jlong native_ptr, jstring url_jstr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) return;
  const char* url = env->GetStringUTFChars(url_jstr, nullptr);

  if (!data->browser) {
    // First loadUrl — create the browser with this URL.
    CefWindowInfo window_info;
    window_info.SetAsWindowless(0);

    CefBrowserSettings browser_settings;
    browser_settings.background_color = data->background_color;

    data->pending_url = url;
    CefBrowserHost::CreateBrowserSync(window_info, data->client, CefString(url),
                                      browser_settings, nullptr,
                                      ContextFor(data));
    LOGI("CreateBrowser with URL: %s", url);
  } else {
    data->browser->GetMainFrame()->LoadURL(CefString(url));
    LOGI("loadUrl: %s", url);
  }
  env->ReleaseStringUTFChars(url_jstr, url);
}

// Reap render processes left orphaned (0 RenderFrameHosts) after a browser closes
// -- e.g. when CefriumBrowserPool evicts an LRU browser. CloseBrowser destroys the
// WebContents but Chromium may keep the child process around; on process-per-browser
// devices (HyperOS / Android 16) the sandboxed_process count then climbs ~1 per
// eviction (PSS stays flat). Only truly unreferenced, non-spare processes are shut
// down (FastShutdownIfPossible is a no-op when unsafe), so this never kills a live
// renderer; on shared-renderer devices it simply finds nothing to reap.
static void ReapUnusedRenderersOnUI() {
  CEF_REQUIRE_UIT();
  int found = 0, reaped = 0;
  for (auto it = content::RenderProcessHost::AllHostsIterator(); !it.IsAtEnd();
       it.Advance()) {
    content::RenderProcessHost* host = it.GetCurrentValue();
    if (host && host->IsInitializedAndNotDead() && !host->IsSpare() &&
        host->GetRenderFrameHostCount() == 0) {
      found++;
      // Defensive only: the real teardown happens when the browser actually
      // closes (CloseHostWindow -> WindowDestroyed -> DestroyBrowser destroys
      // the WebContents and releases all its render processes, including
      // cross-origin subframes). This just nudges any process Chromium still
      // keeps as a warm reuse candidate after its frames are gone. Use the
      // SAFE default FastShutdownIfPossible(): it is a no-op when the process
      // still has keep-alive / pending-reuse refs, so it can never kill a
      // process a live browser is about to reuse.
      if (host->FastShutdownIfPossible()) {
        reaped++;
      }
    }
  }
  LOGI("ReapUnusedRenderers: %d zero-frame render process(es), shut down %d", found,
       reaped);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeReapUnusedRenderers(JNIEnv*, jclass) {
  // Delay so a just-closed browser's RenderFrameHosts finish tearing down (its
  // process then reports 0 frames and becomes reapable).
  CefPostDelayedTask(
      TID_UI, CefCreateClosureTask(base::BindOnce(&ReapUnusedRenderersOnUI)), 1500);
}

// Exact count of live RENDERER processes (RenderProcessHost only -- utility,
// network, audio, storage and GPU use separate host types and are NOT counted, so
// this sidesteps the `ps | grep sandboxed_process` ambiguity). Excludes the spare.
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeActiveRenderProcessCount(JNIEnv*, jclass) {
  if (!CEF_CURRENTLY_ON_UIT()) {
    return -1;  // RenderProcessHost is UI-thread-only; call on the main thread.
  }
  jint n = 0;
  for (auto it = content::RenderProcessHost::AllHostsIterator(); !it.IsAtEnd();
       it.Advance()) {
    content::RenderProcessHost* host = it.GetCurrentValue();
    if (host && host->IsInitializedAndNotDead() && !host->IsSpare()) {
      n++;
    }
  }
  return n;
}

// --- Diagnostic counters (rich API for the pool / renderer-lifetime debugging) ---
// Cumulative count of CEF browsers whose OnBeforeClose has fired (i.e. that actually
// finished closing). If this does NOT grow as a pool evicts browsers, CloseBrowser
// is not completing -- the WebContents (and its renderers) leak.
std::atomic<int> g_closed_browser_count{0};

// Render processes (non-spare, initialized) that currently host ZERO frames -- i.e.
// reuse zombies the reap targets. High while the pool runs = orphaned-but-not-killed.
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeZeroFrameRenderProcessCount(JNIEnv*, jclass) {
  if (!CEF_CURRENTLY_ON_UIT()) return -1;
  jint n = 0;
  for (auto it = content::RenderProcessHost::AllHostsIterator(); !it.IsAtEnd();
       it.Advance()) {
    content::RenderProcessHost* host = it.GetCurrentValue();
    if (host && host->IsInitializedAndNotDead() && !host->IsSpare() &&
        host->GetRenderFrameHostCount() == 0) {
      n++;
    }
  }
  return n;
}

// Total live RenderFrameHosts across all renderer processes -- the real "working
// frame" count. If this grows ~+2/lesson and never drops, the evicted browsers'
// frames (host page + cross-origin iframe) are NOT being destroyed (close() bug).
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeTotalRenderFrameHostCount(JNIEnv*, jclass) {
  if (!CEF_CURRENTLY_ON_UIT()) return -1;
  jint n = 0;
  for (auto it = content::RenderProcessHost::AllHostsIterator(); !it.IsAtEnd();
       it.Advance()) {
    content::RenderProcessHost* host = it.GetCurrentValue();
    if (host && host->IsInitializedAndNotDead()) {
      n += host->GetRenderFrameHostCount();
    }
  }
  return n;
}

// Cumulative count of browsers that finished closing (OnBeforeClose fired).
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeClosedBrowserCount(JNIEnv*, jclass) {
  return g_closed_browser_count.load(std::memory_order_relaxed);
}

// Live (not-yet-closed) CefriumBrowser native instances.
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeLiveBrowserCount(JNIEnv*, jclass) {
  base::AutoLock lock(BridgeDataLock());
  return static_cast<jint>(LiveBridgeData().size());
}

// Load an HTML/data string with an explicit base URL (origin + referrer),
// mirroring Android WebView.loadDataWithBaseURL(). Gives string-loaded content a
// real (non-opaque) origin so embeds that validate their parent origin (e.g. the
// YouTube IFrame player) work. See CefBrowserHostBase::LoadMainFrameDataWithBaseURL.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeLoadDataWithBaseUrl(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring base_url_j,
    jstring data_j, jstring mime_j, jstring charset_j, jstring history_j) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) return;
  // Self-contained jstring -> std::string (the file's JStr() helper is defined
  // later in an anonymous namespace; this function precedes it).
  auto to_str = [env](jstring j) -> std::string {
    if (!j) return std::string();
    const char* s = env->GetStringUTFChars(j, nullptr);
    std::string out(s ? s : "");
    if (s) env->ReleaseStringUTFChars(j, s);
    return out;
  };
  const std::string base_url = to_str(base_url_j);
  const std::string html = to_str(data_j);
  const std::string mime = to_str(mime_j);
  const std::string charset = to_str(charset_j);
  const std::string history = to_str(history_j);

  // approach (A): serve the HTML from a genuine network origin == base_url so the
  // committed document is network-served (embeds like the YouTube IFrame player
  // play; a data: document is rejected client-side -> Error 152). Register the
  // HTML under the normalized base_url; the navigation there is intercepted by
  // SdkResourceRequestHandler::GetResourceHandler and served as a real 200.
  GURL gurl(base_url);
  if (!gurl.is_valid()) {
    LOGI("loadDataWithBaseUrl: invalid base_url '%s' -- ignored",
         base_url.c_str());
    return;
  }
  const std::string served_url = gurl.spec();
  const std::string ctype =
      (mime.empty() ? std::string("text/html") : mime) +
      "; charset=" + (charset.empty() ? std::string("utf-8") : charset);
  RegisterPendingDataDoc(served_url, html, ctype);

  if (!data->browser) {
    // First load — create the browser directly at the served URL; the renderer
    // spawns and the navigation is intercepted to deliver our HTML.
    CefWindowInfo window_info;
    window_info.SetAsWindowless(0);
    CefBrowserSettings browser_settings;
    browser_settings.background_color = data->background_color;
    data->pending_url = served_url;
    CefBrowserHost::CreateBrowserSync(window_info, data->client,
                                      CefString(served_url), browser_settings,
                                      nullptr, ContextFor(data));
    LOGI("CreateBrowser (data-with-base served origin=%s)", served_url.c_str());
  } else {
    data->browser->GetMainFrame()->LoadURL(served_url);
    LOGI("loadDataWithBaseUrl served origin=%s", served_url.c_str());
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetRenderHandler(
    JNIEnv* env, jclass clazz, jlong native_ptr, jobject java_browser) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->render_handler) {
    data->render_handler->SetJavaBrowser(env, java_browser);
  }
  // Also connect the display handler for URL change callbacks.
  if (data && data->client) {
    data->client->display_handler()->SetJavaBrowser(env, java_browser);
    // FEATURE-066: the OSR text-selection quick menu (RunQuickMenu) up-calls
    // Java via the context-menu handler's own java_browser. OSR consumers use
    // setRenderHandler (this path) and may never call nativeSetDisplayCallback,
    // so wire it here too or the quick menu silently no-ops (java_browser null).
    data->client->context_menu_handler()->SetJavaBrowser(env, java_browser);
    // FEATURE-062 follow-up: the client's own java_browser drives the
    // focused-node process-message up-call (OnProcessMessageReceived). In OSR
    // mode nativeSetDisplayCallback may never run, so set it here too (idempotent).
    data->client->SetJavaBrowser(env, java_browser);
  }
}

// Initialize Android Autofill for form auto-completion.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeInitAutofill(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  content::WebContents* wc = nullptr;
  auto* browser_host = static_cast<CefBrowserHostBase*>(
      data->browser->GetHost().get());
  if (browser_host) {
    wc = browser_host->GetWebContents();
  }
  if (!wc) return;
  // Use CefriumAutofillClient instead of Chrome's AndroidAutofillClient.
  // Chrome's version requires PasswordManager infrastructure not
  // available in CEF. Our version just initializes the keyboard
  // suppressor for the AutofillProvider.
  cef::CefAutofillClientAndroid::CreateForWebContents(wc);
  LOGI("CefriumAutofillClient created for WebContents");
}

// Connect display handler for URL/title change callbacks (Surface mode).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetDisplayCallback(
    JNIEnv* env, jclass clazz, jlong native_ptr, jobject java_browser) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client) {
    data->client->display_handler()->SetJavaBrowser(env, java_browser);
    data->client->context_menu_handler()->SetJavaBrowser(env, java_browser);
    data->client->load_handler_ref()->SetJavaBrowser(env, java_browser);
    data->client->find_handler()->SetJavaBrowser(env, java_browser);
    data->client->js_dialog_handler()->SetJavaBrowser(env, java_browser);
    data->client->permission_handler()->SetJavaBrowser(env, java_browser);
    data->client->device_chooser_handler()->SetJavaBrowser(env, java_browser);
    // FEATURE-063 parity handlers.
    data->client->keyboard_handler()->SetJavaBrowser(env, java_browser);
    data->client->focus_handler()->SetJavaBrowser(env, java_browser);
    data->client->drag_handler()->SetJavaBrowser(env, java_browser);
    data->client->audio_handler()->SetJavaBrowser(env, java_browser);
    data->client->frame_handler()->SetJavaBrowser(env, java_browser);
    data->client->command_handler()->SetJavaBrowser(env, java_browser);
    data->client->dialog_handler()->SetJavaBrowser(env, java_browser);
    data->client->SetJavaBrowser(env, java_browser);  // for Web Share
    if (data->client->request_handler()) {
      data->client->request_handler()->SetJavaBrowser(env, java_browser);
    }
    if (data->life_span_handler) {
      data->life_span_handler->SetJavaBrowser(env, java_browser);
    }
    data->client->download_handler()->SetJavaBrowser(env, java_browser);
    // JS bridge (FEATURE-023): deliver window.cefriumQuery to Java.
    if (data->client->bridge_handler()) {
      data->client->bridge_handler()->SetJavaBrowser(env, java_browser);
    }
    {
      auto* cmh = static_cast<SdkContextMenuHandler*>(
          data->client->GetContextMenuHandler().get());
      if (cmh) cmh->SetJavaBrowser(env, java_browser);
    }
    if (data->client->GetRequestHandler()) {
      auto* rh = static_cast<SdkRequestHandler*>(
          data->client->GetRequestHandler().get());
      if (rh->resource_request_handler()) {
        rh->resource_request_handler()->SetJavaBrowser(env, java_browser);
      }
    }
  }
}


// FEATURE-032: Java delivers the user's/app's permission decision here.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeRespondPermission(
    JNIEnv* env, jclass, jlong native_ptr, jlong prompt_id, jboolean grant) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client) {
    data->client->permission_handler()->Respond(
        static_cast<uint64_t>(prompt_id), grant == JNI_TRUE);
  }
}

// FEATURE-060: Java delivers the app's allow/deny decision for a pending
// download here (from CefriumDownloadHandler.onBeforeDownload). Posted to the UI
// thread so the consumer may respond from any thread.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeRespondBeforeDownload(
    JNIEnv* env, jclass, jlong native_ptr, jint download_id, jboolean allow,
    jstring path) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->client) return;
  CefRefPtr<SdkDownloadHandler> handler = data->client->download_handler();
  std::string p;
  if (path) {
    const char* s = env->GetStringUTFChars(path, nullptr);
    if (s) {
      p = s;
      env->ReleaseStringUTFChars(path, s);
    }
  }
  uint32_t id = static_cast<uint32_t>(download_id);
  bool a = allow == JNI_TRUE;
  if (CefCurrentlyOn(TID_UI)) {
    handler->RespondBeforeDownload(id, a, p);
  } else {
    CEF_POST_TASK(TID_UI,
                  base::BindOnce(&SdkDownloadHandler::RespondBeforeDownload,
                                 handler, id, a, p));
  }
}

// FEATURE-060: cancel/pause/resume an in-progress download (0=cancel, 1=pause,
// 2=resume), from DownloadItem.cancel()/pause()/resume().
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeDownloadControl(
    JNIEnv* env, jclass, jlong native_ptr, jint download_id, jint action) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->client) return;
  CefRefPtr<SdkDownloadHandler> handler = data->client->download_handler();
  uint32_t id = static_cast<uint32_t>(download_id);
  int act = static_cast<int>(action);
  if (CefCurrentlyOn(TID_UI)) {
    handler->ControlDownload(id, act);
  } else {
    CEF_POST_TASK(TID_UI, base::BindOnce(&SdkDownloadHandler::ControlDownload,
                                         handler, id, act));
  }
}

// FEATURE-053: enable/disable routing of device chooser requests to the app
// (called from CefriumBrowser.setDeviceChooserHandler).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetDeviceChooserHandler(
    JNIEnv* env, jclass, jlong native_ptr, jboolean enabled) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client) {
    data->client->device_chooser_handler()->SetEnabled(enabled == JNI_TRUE);
  }
}

// FEATURE-053: the app's picker selected a device.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeDeviceChooserSelect(
    JNIEnv* env, jclass, jlong native_ptr, jlong id, jstring device_id_jstr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client) {
    const char* device_id = env->GetStringUTFChars(device_id_jstr, nullptr);
    data->client->device_chooser_handler()->Select(static_cast<uint64_t>(id),
                                                    CefString(device_id));
    env->ReleaseStringUTFChars(device_id_jstr, device_id);
  }
}

// FEATURE-053: the app dismissed the picker.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeDeviceChooserCancel(
    JNIEnv* env, jclass, jlong native_ptr, jlong id) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client) {
    data->client->device_chooser_handler()->Cancel(static_cast<uint64_t>(id));
  }
}

// Maps a CefriumBrowser.PERMISSION_* mask to a CEF content setting type.
static cef_content_setting_types_t SdkPermissionToContentType(jint permission) {
  switch (permission) {
    case (1 << 8):   // PERMISSION_GEOLOCATION
      return CEF_CONTENT_SETTING_TYPE_GEOLOCATION;
    case (1 << 2):   // PERMISSION_CAMERA
      return CEF_CONTENT_SETTING_TYPE_MEDIASTREAM_CAMERA;
    case (1 << 12):  // PERMISSION_MICROPHONE
      return CEF_CONTENT_SETTING_TYPE_MEDIASTREAM_MIC;
    // Use the enum, not a literal: this bit moved from 29 to 30 when CEF 8078
    // took 29 for AMBIENT_LOGIN, and a literal here would have silently kept
    // mapping the old bit. The neighbouring cases mirror the Java constants.
    case CEF_PERMISSION_TYPE_NFC:  // PERMISSION_NFC
      return CEF_CONTENT_SETTING_TYPE_NFC;
    case (1 << 15):  // PERMISSION_NOTIFICATIONS
    default:
      return CEF_CONTENT_SETTING_TYPE_NOTIFICATIONS;
  }
}

// Maps a CefriumBrowser.SETTING_* value to a CEF content setting value.
static cef_content_setting_values_t SdkIntToSettingValue(jint value) {
  switch (value) {
    case 1:  // SETTING_ALLOW
      return CEF_CONTENT_SETTING_VALUE_ALLOW;
    case 2:  // SETTING_BLOCK
      return CEF_CONTENT_SETTING_VALUE_BLOCK;
    case 3:  // SETTING_ASK
      return CEF_CONTENT_SETTING_VALUE_ASK;
    case 0:  // SETTING_DEFAULT
    default:
      return CEF_CONTENT_SETTING_VALUE_DEFAULT;
  }
}

// Sets a per-origin content setting (web permission) without a prompt, via the
// browser's request context. Runs on the CEF UI thread (JNI calls in this SDK
// are dispatched on the UI thread, same as nativeRespondPermission).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetContentSetting(
    JNIEnv* env, jclass, jlong native_ptr, jstring url_jstr, jint permission,
    jint value) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) {
    return;
  }
  CefRefPtr<CefRequestContext> context = data->browser->GetHost()->GetRequestContext();
  if (!context) {
    return;
  }
  const char* url = env->GetStringUTFChars(url_jstr, nullptr);
  context->SetContentSetting(CefString(url), CefString(),
                             SdkPermissionToContentType(permission),
                             SdkIntToSettingValue(value));
  LOGI("setContentSetting url=%s permission=%d value=%d", url, permission, value);
  env->ReleaseStringUTFChars(url_jstr, url);
}

// Sets a preference of a profile's request context: now if the context is
// ready, otherwise when it is (see ProfilePrefs). Must run on the UI thread.
// Returns false if the context rejected it.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_CefriumProfile_nativeSetPreference(JNIEnv* env,
                                                    jclass,
                                                    jboolean incognito,
                                                    jstring profile_j,
                                                    jstring name_j,
                                                    jstring json_j) {
  if (!CefCurrentlyOn(TID_UI)) {
    LOG(ERROR) << "Cefrium: profile preferences are set on the main thread";
    return JNI_FALSE;
  }
  const std::string profile = JStr(env, profile_j).ToString();
  const std::string key = incognito == JNI_TRUE ? kIncognitoKey : profile;
  const std::string name = JStr(env, name_j).ToString();
  CefRefPtr<CefValue> value =
      CefParseJSON(JStr(env, json_j), JSON_PARSER_RFC);
  if (!value) {
    return JNI_FALSE;
  }
  CefRefPtr<CefRequestContext> context;
  if (incognito == JNI_TRUE) {
    context = IncognitoContext();
  } else if (profile.empty()) {
    context = CefRequestContext::GetGlobalContext();
    ReadyProfiles().insert(key);
  } else {
    context = ProfileContext(profile);
  }
  if (context && ReadyProfiles().count(key) &&
      !ApplyProfilePref(context, name, value)) {
    return JNI_FALSE;
  }
  ProfilePrefs()[key][name] = value;
  // An open incognito session follows the default profile's change unless it
  // set this itself.
  if (key.empty() && IncognitoContext() && ReadyProfiles().count(kIncognitoKey) &&
      !ProfilePrefs()[kIncognitoKey].count(name) &&
      std::count(std::begin(kInheritedByIncognito),
                 std::end(kInheritedByIncognito), name)) {
    ApplyProfilePref(IncognitoContext(), name, value);
  }
  return JNI_TRUE;
}

// Drops the incognito profile's own proxy and WebRTC settings, so it follows
// the default profile's again.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumProfile_nativeFollowDefaultProfile(JNIEnv* env,
                                                           jclass) {
  if (!CefCurrentlyOn(TID_UI)) {
    LOG(ERROR) << "Cefrium: profile preferences are set on the main thread";
    return;
  }
  PrefMap& own = ProfilePrefs()[kIncognitoKey];
  CefRefPtr<CefRequestContext> global = CefRequestContext::GetGlobalContext();
  for (const char* name : kInheritedByIncognito) {
    own.erase(name);
    if (IncognitoContext() && ReadyProfiles().count(kIncognitoKey)) {
      if (CefRefPtr<CefValue> value = global->GetPreference(name)) {
        ApplyProfilePref(IncognitoContext(), name, value);
      }
    }
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

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetSize(JNIEnv* env,
                                              jclass clazz,
                                              jlong native_ptr,
                                              jint width,
                                              jint height) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->render_handler) {
    return;
  }
  data->render_handler->SetSize(width, height);
  // Before the first load there is no browser yet; it starts at the new size.
  if (data->browser) {
    // The screen rect GetScreenInfo reports is the view's, so it changed too.
    data->browser->GetHost()->NotifyScreenInfoChanged();
    data->browser->GetHost()->WasResized();
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeInvalidate(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) {
    data->browser->GetHost()->Invalidate(PET_VIEW);
  }
}

// FEATURE-062 (issue #9): OSR IME. These forward an Android InputConnection's
// composing/commit calls to CEF's OSR IME path (CefBrowserHost::Ime*), which
// routes to the renderer. Text is taken as UTF-16 (jchar) so CJK/emoji and
// supplementary-plane characters survive (GetStringUTFChars would mangle them).
namespace {
CefString JStringToCef16(JNIEnv* env, jstring j) {
  CefString out;
  if (!j) return out;
  const jchar* chars = env->GetStringChars(j, nullptr);
  jsize len = env->GetStringLength(j);
  if (chars) {
    out.FromString16(reinterpret_cast<const char16_t*>(chars),
                     static_cast<size_t>(len));
    env->ReleaseStringChars(j, chars);
  }
  return out;
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeImeSetComposition(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring text_jstr,
    jint sel_start, jint sel_end) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefString text = JStringToCef16(env, text_jstr);

  std::vector<CefCompositionUnderline> underlines;
  CefCompositionUnderline underline;
  underline.range = CefRange(0, static_cast<uint32_t>(text.length()));
  underline.color = 0xFF000000;       // opaque black
  underline.background_color = 0;
  underline.thick = 0;
  underline.style = CEF_CUS_SOLID;
  underlines.push_back(underline);

  // No replacement range: replace the existing composition/selection.
  CefRange replacement_range = CefRange::InvalidRange();
  CefRange selection_range(static_cast<uint32_t>(sel_start),
                           static_cast<uint32_t>(sel_end));
  data->browser->GetHost()->ImeSetComposition(text, underlines,
                                              replacement_range,
                                              selection_range);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeImeCommitText(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring text_jstr,
    jint relative_cursor_pos) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefString text = JStringToCef16(env, text_jstr);
  data->browser->GetHost()->ImeCommitText(text, CefRange::InvalidRange(),
                                          relative_cursor_pos);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeImeFinishComposingText(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean keep_selection) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  data->browser->GetHost()->ImeFinishComposingText(keep_selection);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeImeCancelComposition(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  data->browser->GetHost()->ImeCancelComposition();
}

// FEATURE-062 (issue #9): OSR key events. Needed alongside IME for backspace,
// enter, arrows and any non-composed keystroke. |type| is cef_key_event_type_t
// (0=RAWKEYDOWN, 1=KEYDOWN, 2=KEYUP, 3=CHAR). |windows_key_code| is a Windows
// virtual-key code (the platform-independent code Blink expects);
// |native_key_code| is the raw Android key code; |char_code| is the produced
// UTF-16 unit (for CHAR events).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSendKeyEvent(
    JNIEnv* env, jclass clazz, jlong native_ptr, jint type, jint modifiers,
    jint windows_key_code, jint native_key_code, jint char_code) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefKeyEvent event;
  event.type = static_cast<cef_key_event_type_t>(type);
  event.modifiers = static_cast<uint32_t>(modifiers);
  event.windows_key_code = windows_key_code;
  event.native_key_code = native_key_code;
  event.is_system_key = 0;
  event.character = static_cast<char16_t>(char_code);
  event.unmodified_character = static_cast<char16_t>(char_code);
  event.focus_on_editable_field = true;
  data->browser->GetHost()->SendKeyEvent(event);
}

// Issue #11: send a key event described the way ANDROID describes it.
//
// The raw nativeSendKeyEvent above needs a WINDOWS virtual-key code, because
// that is what Blink derives DOM `key` and `code` from. An Android embedder has
// an android.view.KeyEvent and no reason to know what a Windows VK is, so the
// five-int API is easy to get wrong: passing windowsKeyCode=0 yields keydown and
// keyup events whose key/code are empty strings and whose keyCode is 0, which
// looks like the engine dropping the event rather than a mis-filled struct.
//
// Do the conversion here with Chromium's own table rather than a hand-written
// one in Java: ui::KeyboardCodeFromAndroidKeyCode is the mapping the rest of
// Chromium-on-Android uses, so it cannot drift from the platform. This mirrors
// what the Windows and Linux platform delegates do with their own
// keyboard_code_conversion_* headers.
//
// `android_meta_state` is an android.view.KeyEvent metaState; the bit values are
// Android's public API constants and are stable.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSendKeyEventAndroid(
    JNIEnv* env, jclass clazz, jlong native_ptr, jint type,
    jint android_key_code, jint android_meta_state, jint unicode_char) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;

  // android.view.KeyEvent.META_* -> cef_event_flags_t.
  enum : int {
    kMetaShiftOn = 0x00000001,
    kMetaAltOn = 0x00000002,
    kMetaSymOn = 0x00000004,
    kMetaCapsLockOn = 0x00100000,
    kMetaCtrlOn = 0x00001000,
    kMetaNumLockOn = 0x00200000,
  };
  uint32_t modifiers = EVENTFLAG_NONE;
  if (android_meta_state & kMetaShiftOn) modifiers |= EVENTFLAG_SHIFT_DOWN;
  if (android_meta_state & kMetaAltOn) modifiers |= EVENTFLAG_ALT_DOWN;
  if (android_meta_state & kMetaCtrlOn) modifiers |= EVENTFLAG_CONTROL_DOWN;
  if (android_meta_state & kMetaCapsLockOn) modifiers |= EVENTFLAG_CAPS_LOCK_ON;
  if (android_meta_state & kMetaNumLockOn) modifiers |= EVENTFLAG_NUM_LOCK_ON;

  CefKeyEvent event;
  event.type = static_cast<cef_key_event_type_t>(type);
  event.modifiers = modifiers;
  event.windows_key_code =
      ui::KeyboardCodeFromAndroidKeyCode(android_key_code);
  event.native_key_code = android_key_code;
  event.is_system_key = 0;
  event.character = static_cast<char16_t>(unicode_char);
  event.unmodified_character = static_cast<char16_t>(unicode_char);
  event.focus_on_editable_field = true;
  data->browser->GetHost()->SendKeyEvent(event);
}

// FEATURE-066: Java returns the chosen OSR quick-menu command (a QM_EDITFLAG_CAN_*
// value, or 0 to cancel).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeQuickMenuCommand(
    JNIEnv* env, jclass clazz, jlong native_ptr, jint command) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->client) return;
  auto* cmh = static_cast<SdkContextMenuHandler*>(
      data->client->GetContextMenuHandler().get());
  if (cmh) cmh->ResolveQuickMenu(command);
}

// FEATURE-063: Java returns the file-chooser selection (paths/URIs), or an
// empty array to cancel.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeFileDialogResult(
    JNIEnv* env, jclass clazz, jlong native_ptr, jlong id,
    jobjectArray paths) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->client) return;
  std::vector<CefString> out;
  if (paths) {
    jsize n = env->GetArrayLength(paths);
    for (jsize i = 0; i < n; ++i) {
      jstring s = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
      if (s) {
        const char* c = env->GetStringUTFChars(s, nullptr);
        if (c) {
          out.push_back(CefString(c));
          env->ReleaseStringUTFChars(s, c);
        }
        env->DeleteLocalRef(s);
      }
    }
  }
  data->client->dialog_handler()->Resolve(id, out);
}

// FEATURE-063: enable/disable the raw PCM audio tap (opt-in; redirects audio).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetAudioCaptureEnabled(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean enabled) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client) {
    data->client->SetAudioCaptureEnabled(enabled == JNI_TRUE);
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

// Issue #10: give or take keyboard focus.
//
// An off-screen (OSR) browser has no platform widget, so nothing ever delivers
// focus to it and its document stays unfocused forever. That is not cosmetic:
// Blink gates several APIs on document focus BEFORE any permission check, so
// they fail in a way that looks like a permission bug. The reported case is
// navigator.clipboard.readText(), which rejects with
//   NotAllowedError: "Document is not focused."
// in clipboard_promise.cc, without the permission handler ever being consulted.
//
// CefBrowserHost::SetFocus is the public CEF API for this and is what the
// desktop ports drive from their platform focus events; on-screen Android
// browsers get it from the View, OSR browsers had no way to receive it at all.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetFocus(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean focus) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) {
    data->browser->GetHost()->SetFocus(focus == JNI_TRUE);
  }
}

// FEATURE-063: programmatically exit HTML fullscreen (e.g. back button).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeExitFullscreen(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean will_cause_resize) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) {
    data->browser->GetHost()->ExitFullscreen(will_cause_resize == JNI_TRUE);
  }
}

// FEATURE-063: force-download a URL.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeStartDownload(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring url_jstr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser || !url_jstr) return;
  const char* url = env->GetStringUTFChars(url_jstr, nullptr);
  if (url) {
    data->browser->GetHost()->StartDownload(CefString(url));
    env->ReleaseStringUTFChars(url_jstr, url);
  }
}

// FEATURE-063: history list with titles. Returns String[] of
// "<isCurrent 0/1>\t<url>\t<title>", oldest-first.
namespace {
class NavEntryCollector : public CefNavigationEntryVisitor {
 public:
  bool Visit(CefRefPtr<CefNavigationEntry> entry,
             bool current, int index, int total) override {
    std::string row = current ? "1\t" : "0\t";
    row += entry->GetURL().ToString();
    row += "\t";
    row += entry->GetTitle().ToString();
    entries_.push_back(row);
    return true;
  }
  std::vector<std::string> entries_;
  IMPLEMENT_REFCOUNTING(NavEntryCollector);
};
}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_cefrium_CefriumBrowser_nativeGetNavigationEntries(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  jclass str_cls = env->FindClass("java/lang/String");
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) {
    return env->NewObjectArray(0, str_cls, nullptr);
  }
  CefRefPtr<NavEntryCollector> visitor(new NavEntryCollector());
  data->browser->GetHost()->GetNavigationEntries(visitor, /*current_only=*/false);
  jobjectArray arr = env->NewObjectArray(
      static_cast<jsize>(visitor->entries_.size()), str_cls, nullptr);
  for (size_t i = 0; i < visitor->entries_.size(); ++i) {
    jstring s = env->NewStringUTF(visitor->entries_[i].c_str());
    env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
    env->DeleteLocalRef(s);
  }
  return arr;
}

// FEATURE-063: fetch an image (e.g. favicon) to PNG bytes, delivered to Java
// onImageDownloaded(url, byte[]). byte[] is null on failure.
namespace {
class ImageDownloadCb : public CefDownloadImageCallback {
 public:
  explicit ImageDownloadCb(jobject java_browser_global)
      : java_browser_(java_browser_global) {}
  void OnDownloadImageFinished(const CefString& image_url,
                               int http_status_code,
                               CefRefPtr<CefImage> image) override {
    JNIEnv* env = base::android::AttachCurrentThread();
    if (env && java_browser_) {
      jbyteArray jbytes = nullptr;
      if (image) {
        int w = 0, h = 0;
        CefRefPtr<CefBinaryValue> png =
            image->GetAsPNG(1.0f, true, w, h);
        if (png) {
          size_t sz = png->GetSize();
          jbytes = env->NewByteArray(static_cast<jsize>(sz));
          std::vector<uint8_t> buf(sz);
          png->GetData(buf.data(), sz, 0);
          env->SetByteArrayRegion(jbytes, 0, static_cast<jsize>(sz),
                                  reinterpret_cast<const jbyte*>(buf.data()));
        }
      }
      jclass cls = env->GetObjectClass(java_browser_);
      jmethodID mid = env->GetMethodID(cls, "onImageDownloaded",
                                       "(Ljava/lang/String;[B)V");
      if (mid) {
        jstring j = env->NewStringUTF(image_url.ToString().c_str());
        env->CallVoidMethod(java_browser_, mid, j, jbytes);
        env->DeleteLocalRef(j);
      }
      if (jbytes) env->DeleteLocalRef(jbytes);
      env->DeleteLocalRef(cls);
      env->DeleteGlobalRef(java_browser_);
      java_browser_ = nullptr;
    }
  }
  jobject java_browser_;
  IMPLEMENT_REFCOUNTING(ImageDownloadCb);
};
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeDownloadImage(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring url_jstr,
    jobject java_browser, jint max_size) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser || !url_jstr || !java_browser) return;
  const char* url = env->GetStringUTFChars(url_jstr, nullptr);
  if (url) {
    jobject global = env->NewGlobalRef(java_browser);
    data->browser->GetHost()->DownloadImage(
        CefString(url), /*is_favicon=*/true, static_cast<uint32_t>(max_size),
        /*bypass_cache=*/false, new ImageDownloadCb(global));
    env->ReleaseStringUTFChars(url_jstr, url);
  }
}

// Touch event forwarding: Android MotionEvent → CEF mouse/touch events.
// action: 0=DOWN, 1=UP, 2=MOVE
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSendTouchEvent(
    JNIEnv* env, jclass clazz, jlong native_ptr,
    jint action, jfloat x, jfloat y, jint pointer_id) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;

  auto host = data->browser->GetHost();
  CefTouchEvent event;
  event.id = pointer_id;
  event.x = x;
  event.y = y;
  event.radius_x = 10;
  event.radius_y = 10;
  event.pressure = 1.0f;

  switch (action) {
    case 0:  // ACTION_DOWN
      event.type = CEF_TET_PRESSED;
      host->SendTouchEvent(event);
      break;
    case 1:  // ACTION_UP
      event.type = CEF_TET_RELEASED;
      event.pressure = 0.0f;
      host->SendTouchEvent(event);
      break;
    case 2:  // ACTION_MOVE
      event.type = CEF_TET_MOVED;
      host->SendTouchEvent(event);
      break;
    case 3:  // ACTION_CANCEL
      event.type = CEF_TET_CANCELLED;
      host->SendTouchEvent(event);
      break;
  }
}

// Mouse event forwarding: programmatic SendMouse* injection (position/hover,
// click, right-click, wheel). Coordinates are view-relative (upper-left origin),
// like touch. |modifiers| are cef_event_flags_t bits (shift/ctrl/alt, and any
// held mouse buttons for drags). |button|: 0=MBT_LEFT, 1=MBT_MIDDLE, 2=MBT_RIGHT.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSendMouseMoveEvent(
    JNIEnv* env, jclass clazz, jlong native_ptr,
    jint x, jint y, jint modifiers, jboolean mouse_leave) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefMouseEvent event;
  event.x = x;
  event.y = y;
  event.modifiers = static_cast<uint32_t>(modifiers);
  data->browser->GetHost()->SendMouseMoveEvent(event, mouse_leave);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSendMouseClickEvent(
    JNIEnv* env, jclass clazz, jlong native_ptr,
    jint x, jint y, jint modifiers, jint button, jboolean mouse_up,
    jint click_count) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefMouseEvent event;
  event.x = x;
  event.y = y;
  event.modifiers = static_cast<uint32_t>(modifiers);
  data->browser->GetHost()->SendMouseClickEvent(
      event, static_cast<cef_mouse_button_type_t>(button), mouse_up,
      click_count);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSendMouseWheelEvent(
    JNIEnv* env, jclass clazz, jlong native_ptr,
    jint x, jint y, jint modifiers, jint delta_x, jint delta_y) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefMouseEvent event;
  event.x = x;
  event.y = y;
  event.modifiers = static_cast<uint32_t>(modifiers);
  data->browser->GetHost()->SendMouseWheelEvent(event, delta_x, delta_y);
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

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeClose(
    JNIEnv* env, jobject thiz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) {
    return;
  }
  if (data->browser) {
    // Only INITIATE the close. The surface reset, LiveBridgeData erase, Java view
    // destruction and `delete data` are all deferred to OnBeforeClose, which fires
    // once the close actually completes (CloseHostWindow -> WindowDestroyed ->
    // DestroyBrowser). Doing them here, before the async close completes, would
    // tear down the Java window out from under the still-closing WebContents.
    // See SdkLifeSpanHandler::OnBeforeClose and the Android platform delegate.
    data->browser->GetHost()->CloseBrowser(true);
  } else {
    // Browser creation failed. The browser is created with CreateBrowserSync,
    // so a created browser always has data->browser set by the time Java can
    // call close(); without one, OnAfterCreated will never run and no close
    // will fire, so clean up synchronously here.
    {
      base::AutoLock lock(BridgeDataLock());
      LiveBridgeData().erase(native_ptr);
    }
    delete data;
  }
}

// --- Surface rendering mode ---

extern "C" JNIEXPORT jlong JNICALL
Java_com_cefrium_CefriumBrowser_nativeCreateWithSurface(
    JNIEnv* env, jclass clazz, jobject java_window_android,
    jobject java_surface, jint width, jint height, jfloat scale,
    jboolean incognito, jstring profile) {
  auto* data = new SdkBrowserData();
  data->is_surface_mode = true;
  data->is_incognito = (incognito == JNI_TRUE);
  data->profile = JStr(env, profile).ToString();

  // Create life span handler to capture the browser reference.
  data->life_span_handler = new SdkLifeSpanHandler();
  data->life_span_handler->SetBrowserData(data);

  // Create client without render handler (Surface mode doesn't use OnPaint).
  // Rendering is handled by ContentViewRenderView on the Java side.
  data->client = new SdkClient(
      nullptr,  // No render handler — ContentViewRenderView handles rendering
      data->life_span_handler,
      /*with_request_handler=*/true);

  LOGI("nativeCreateWithSurface: %dx%d (windowed mode, ContentViewRenderView)", width, height);

  // Create browser in NON-windowless mode.
  // Chromium creates WebContentsViewAndroid + RenderWidgetHostViewAndroid.
  // ContentViewRenderView (Java) manages the compositor and Surface.
  CefWindowInfo window_info;
  // Do NOT call SetAsWindowless — leave default (windowed/Surface mode).

  CefBrowserSettings browser_settings;
  browser_settings.background_color = CefColorSetARGB(255, 255, 255, 255);

  // Incognito tabs share one off-the-record context (isolated + in-memory), a
  // named profile has its own persistent one, and the rest use the global.
  CefBrowserHost::CreateBrowserSync(window_info, data->client, CefString(""),
                                    browser_settings, nullptr,
                                    ContextFor(data));

  {
    base::AutoLock lock(BridgeDataLock());
    LiveBridgeData().insert(reinterpret_cast<jlong>(data));
  }
  return reinterpret_cast<jlong>(data);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeUpdateSurface(
    JNIEnv* env, jclass clazz, jlong native_ptr,
    jobject java_surface, jint width, jint height) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->surface_bridge) {
    auto j_surface = base::android::ScopedJavaLocalRef<jobject>::Adopt(env, java_surface);
    data->surface_bridge->SurfaceChanged(env, j_surface, width, height);
    j_surface.Release();
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeDetachSurface(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->surface_bridge) {
    data->surface_bridge->SurfaceDestroyed();
  }
}

// Return the Java WebContents object for this browser.
// Used by Surface rendering to pass WebContents to ContentViewRenderView.
extern "C" JNIEXPORT jobject JNICALL
Java_com_cefrium_CefriumBrowser_nativeGetWebContents(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return nullptr;

  auto* browser_impl = static_cast<CefBrowserHostBase*>(
      data->browser->GetHost().get());
  if (!browser_impl) return nullptr;
  content::WebContents* web_contents = browser_impl->GetWebContents();
  if (!web_contents) return nullptr;

  return web_contents->GetJavaWebContents().Release();
}

// Navigation methods.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeGoBack(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) data->browser->GoBack();
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeGoForward(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) data->browser->GoForward();
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeReload(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) data->browser->Reload();
}

// Serialize the full back/forward navigation stack (URL + blink PageState +
// current index per entry) for session restore -- Chrome-style tab restore, so
// a restored tab can go back, not just show its current page. Null if empty.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_cefrium_CefriumBrowser_nativeSerializeNavigationState(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return nullptr;
  auto* host = static_cast<CefBrowserHostBase*>(data->browser->GetHost().get());
  content::WebContents* wc = host ? host->GetWebContents() : nullptr;
  if (!wc) return nullptr;
  content::NavigationController& controller = wc->GetController();
  const int count = controller.GetEntryCount();
  if (count <= 0) return nullptr;
  base::Pickle pickle;
  pickle.WriteInt(controller.GetCurrentEntryIndex());
  pickle.WriteInt(count);
  for (int i = 0; i < count; ++i) {
    content::NavigationEntry* entry = controller.GetEntryAtIndex(i);
    pickle.WriteString(entry->GetURL().spec());
    pickle.WriteString16(entry->GetTitleForDisplay());
    pickle.WriteString(entry->GetPageState().ToEncodedData());
  }
  return base::android::ToJavaByteArray(
             env, reinterpret_cast<const uint8_t*>(pickle.data()), pickle.size())
      .Release();
}

// Restore a stack captured by nativeSerializeNavigationState onto a FRESH
// browser (empty controller): rebuild the entries and Restore() so back/forward
// works without re-fetching each page. Must run before the first navigation.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeRestoreNavigationState(
    JNIEnv* env, jclass clazz, jlong native_ptr, jbyteArray blob) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !blob) return;
  const jsize len = env->GetArrayLength(blob);
  if (len <= 0) return;
  std::vector<uint8_t> bytes(static_cast<size_t>(len));
  env->GetByteArrayRegion(blob, 0, len, reinterpret_cast<jbyte*>(bytes.data()));
  if (data->browser) {
    // Browser already exists: apply now (caller guarantees a fresh controller).
    auto* host = static_cast<CefBrowserHostBase*>(data->browser->GetHost().get());
    ApplyNavigationState(host ? host->GetWebContents() : nullptr, bytes);
    return;
  }
  // Browser not created yet: the surface browser (createWithSurface) is still
  // being created asynchronously. Just stash the stack -- its OnAfterCreated
  // applies it. Do NOT create a second (windowless) browser here.
  data->pending_nav_state = std::move(bytes);
}

// Trigger the deferred load of a restored navigation stack. Called by the SDK
// from connectWebContentsInternal after the on-screen surface and the
// pull-to-refresh overscroll handler are set up. No-op if nothing needs loading.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeLoadRestoredIfNecessary(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) {
    return;
  }
  auto* host = static_cast<CefBrowserHostBase*>(data->browser->GetHost().get());
  content::WebContents* wc = host ? host->GetWebContents() : nullptr;
  if (wc) {
    wc->GetController().LoadIfNecessary();
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeEvaluateJavaScript(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring script_jstr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefRefPtr<CefFrame> frame = data->browser->GetMainFrame();
  if (!frame) return;
  const char* script = env->GetStringUTFChars(script_jstr, nullptr);
  // Fire-and-forget execution in the main frame; no return value.
  frame->ExecuteJavaScript(CefString(script), frame->GetURL(), 0);
  env->ReleaseStringUTFChars(script_jstr, script);
}

// Resolves a bridge query on the browser UI thread. The lock + live-registry
// check make this safe even if the browser was closed (data deleted) after the
// async response was posted (FEATURE-023 step 4b).
static void ResolveBridgeQueryOnUI(jlong native_ptr,
                                   int64_t query_id,
                                   bool success,
                                   CefString response,
                                   int error_code,
                                   CefString error_message) {
  base::AutoLock lock(BridgeDataLock());
  if (!LiveBridgeData().count(native_ptr)) {
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

// ───────────────────────── Cookies (FEATURE-024) ─────────────────────────

namespace {

CefString JStr(JNIEnv* env, jstring j) {
  CefString out;
  if (j) {
    const char* s = env->GetStringUTFChars(j, nullptr);
    out = s;
    env->ReleaseStringUTFChars(j, s);
  }
  return out;
}

// Cookie manager for a browser's own request context (per-browser store).
CefRefPtr<CefCookieManager> CookieManagerFor(jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser || !data->browser->GetHost()) {
    return nullptr;
  }
  CefRefPtr<CefRequestContext> ctx =
      data->browser->GetHost()->GetRequestContext();
  return ctx ? ctx->GetCookieManager(nullptr) : nullptr;
}

// Delivers visited cookies to Java CefriumBrowser.onCookiesVisited (TID_UI).
// Owns the global ref; releases it after the call.
void DeliverCookiesOnUI(jobject java_browser,
                        jlong req_id,
                        std::vector<std::string> names,
                        std::vector<std::string> values,
                        std::vector<std::string> domains,
                        std::vector<std::string> paths,
                        std::vector<uint8_t> secure,
                        std::vector<uint8_t> httponly) {
  JNIEnv* env = base::android::AttachCurrentThread();
  const int n = static_cast<int>(names.size());
  jclass str_cls = env->FindClass("java/lang/String");
  auto mk = [&](const std::vector<std::string>& v) -> jobjectArray {
    jobjectArray a = env->NewObjectArray(n, str_cls, nullptr);
    for (int i = 0; i < n; ++i) {
      jstring s = env->NewStringUTF(v[i].c_str());
      env->SetObjectArrayElement(a, i, s);
      env->DeleteLocalRef(s);
    }
    return a;
  };
  jobjectArray jn = mk(names), jv = mk(values), jd = mk(domains), jp = mk(paths);
  jbooleanArray js = env->NewBooleanArray(n), jh = env->NewBooleanArray(n);
  if (n > 0) {
    env->SetBooleanArrayRegion(js, 0, n,
        reinterpret_cast<const jboolean*>(secure.data()));
    env->SetBooleanArrayRegion(jh, 0, n,
        reinterpret_cast<const jboolean*>(httponly.data()));
  }
  jclass cls = env->GetObjectClass(java_browser);
  jmethodID m = env->GetMethodID(cls, "onCookiesVisited",
      "(J[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;"
      "[Ljava/lang/String;[Z[Z)V");
  if (m) {
    env->CallVoidMethod(java_browser, m, req_id, jn, jv, jd, jp, js, jh);
  }
  env->DeleteGlobalRef(java_browser);
}

// Accumulates cookies during a visit and delivers them when CEF releases it.
class SdkCookieVisitor : public CefCookieVisitor {
 public:
  SdkCookieVisitor(jobject java_browser_global, jlong req_id)
      : java_browser_(java_browser_global), req_id_(req_id) {}

  bool Visit(const CefCookie& cookie, int, int, bool&) override {
    names_.push_back(CefString(&cookie.name).ToString());
    values_.push_back(CefString(&cookie.value).ToString());
    domains_.push_back(CefString(&cookie.domain).ToString());
    paths_.push_back(CefString(&cookie.path).ToString());
    secure_.push_back(cookie.secure ? 1 : 0);
    httponly_.push_back(cookie.httponly ? 1 : 0);
    return true;
  }

  ~SdkCookieVisitor() override {
    CefPostTask(TID_UI,
                base::BindOnce(&DeliverCookiesOnUI, java_browser_, req_id_,
                               std::move(names_), std::move(values_),
                               std::move(domains_), std::move(paths_),
                               std::move(secure_), std::move(httponly_)));
  }

 private:
  jobject java_browser_;
  jlong req_id_;
  std::vector<std::string> names_, values_, domains_, paths_;
  std::vector<uint8_t> secure_, httponly_;
  IMPLEMENT_REFCOUNTING(SdkCookieVisitor);
};

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetCookie(
    JNIEnv* env, jclass, jlong native_ptr, jstring url_j, jstring name_j,
    jstring value_j, jboolean secure, jboolean http_only, jint same_site) {
  CefRefPtr<CefCookieManager> mgr = CookieManagerFor(native_ptr);
  if (!mgr) return;
  CefString url = JStr(env, url_j);
  CefCookie cookie;  // zero-initialized (CefStructBase)
  CefString(&cookie.name) = JStr(env, name_j);
  CefString(&cookie.value) = JStr(env, value_j);
  cookie.has_expires = false;  // session cookie; domain/path inferred from url
  cookie.secure = secure ? 1 : 0;
  cookie.httponly = http_only ? 1 : 0;
  // same_site matches CefriumBrowser.SAME_SITE_* == cef_cookie_same_site_t
  // (0 unspecified, 1 no-restriction/None, 2 Lax, 3 Strict). None requires Secure.
  cookie.same_site = static_cast<cef_cookie_same_site_t>(same_site);
  mgr->SetCookie(url, cookie, nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeGetCookies(
    JNIEnv* env, jclass, jlong native_ptr, jobject browser_j, jstring url_j,
    jlong req_id) {
  jobject global = env->NewGlobalRef(browser_j);
  CefRefPtr<CefCookieManager> mgr = CookieManagerFor(native_ptr);
  if (!mgr) {
    // Deliver an empty result so the Java callback is invoked and removed.
    CefPostTask(TID_UI, base::BindOnce(&DeliverCookiesOnUI, global, req_id,
                                       std::vector<std::string>(),
                                       std::vector<std::string>(),
                                       std::vector<std::string>(),
                                       std::vector<std::string>(),
                                       std::vector<uint8_t>(),
                                       std::vector<uint8_t>()));
    return;
  }
  CefRefPtr<SdkCookieVisitor> visitor = new SdkCookieVisitor(global, req_id);
  mgr->VisitUrlCookies(JStr(env, url_j), /*includeHttpOnly=*/true, visitor);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeDeleteCookies(
    JNIEnv* env, jclass, jlong native_ptr, jstring url_j, jstring name_j) {
  CefRefPtr<CefCookieManager> mgr = CookieManagerFor(native_ptr);
  if (mgr) mgr->DeleteCookies(JStr(env, url_j), JStr(env, name_j), nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeFlushCookies(
    JNIEnv* env, jclass, jlong native_ptr) {
  CefRefPtr<CefCookieManager> mgr = CookieManagerFor(native_ptr);
  if (mgr) mgr->FlushStore(nullptr);
}

// FEATURE-030: clear the HTTP cache via the DevTools protocol.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeClearCache(
    JNIEnv* env, jclass, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser && data->browser->GetHost()) {
    data->browser->GetHost()->ExecuteDevToolsMethod(
        1, "Network.clearBrowserCache", nullptr);
  }
}

// FEATURE-071: per-site data of a profile, through Chromium's
// BrowsingDataModel (what Chrome's per-site storage screen uses). Listing
// reports each data owner (host or origin) with its storage size and cookie
// count; clearing removes everything the model knows for that owner. The HTTP
// cache is not attributed per site by Chromium, so it is in neither.
namespace {

// The context for (incognito, profile): null when incognito has no open
// browser, i.e. no data.
CefRefPtr<CefRequestContext> SiteDataContext(JNIEnv* env, jboolean incognito,
                                             jstring profile) {
  return cefrium::ContextForProfile(incognito == JNI_TRUE,
                                    JStr(env, profile).ToString());
}

std::string OwnerString(const BrowsingDataModel::DataOwner& owner) {
  return std::visit(
      absl::Overload{[](const std::string& host) { return host; },
                     [](const url::Origin& origin) { return origin.Serialize(); }},
      owner);
}

void BuildSiteDataModel(
    CefRefPtr<CefRequestContext> context,
    base::OnceCallback<void(std::unique_ptr<BrowsingDataModel>)> done) {
  Profile* profile = CefRequestContextImpl::GetProfile(context);
  BrowsingDataModel::BuildFromDisk(
      profile, ChromeBrowsingDataModelDelegate::CreateForProfile(profile),
      std::move(done));
}

// Calls CefriumProfile.onSiteDataResult(requestId, owners, bytes, cookies) on
// the UI thread; owners is null when the request failed.
void DeliverSiteData(jclass clazz, jlong request_id,
                     const std::vector<std::string>* owners,
                     const std::vector<int64_t>* bytes,
                     const std::vector<int64_t>* cookies) {
  JNIEnv* env = base::android::AttachCurrentThread();
  jmethodID method = env->GetStaticMethodID(clazz, "onSiteDataResult",
                                            "(J[Ljava/lang/String;[J[J)V");
  if (!method) {
    env->ExceptionClear();
    env->DeleteGlobalRef(clazz);
    return;
  }
  jobjectArray j_owners = nullptr;
  jlongArray j_bytes = nullptr;
  jlongArray j_cookies = nullptr;
  if (owners) {
    const jsize n = static_cast<jsize>(owners->size());
    j_owners = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    j_bytes = env->NewLongArray(n);
    j_cookies = env->NewLongArray(n);
    for (jsize i = 0; i < n; ++i) {
      jstring o = env->NewStringUTF((*owners)[i].c_str());
      env->SetObjectArrayElement(j_owners, i, o);
      env->DeleteLocalRef(o);
    }
    env->SetLongArrayRegion(j_bytes, 0, n,
                            reinterpret_cast<const jlong*>(bytes->data()));
    env->SetLongArrayRegion(j_cookies, 0, n,
                            reinterpret_cast<const jlong*>(cookies->data()));
  }
  env->CallStaticVoidMethod(clazz, method, request_id, j_owners, j_bytes,
                            j_cookies);
  if (j_owners) env->DeleteLocalRef(j_owners);
  if (j_bytes) env->DeleteLocalRef(j_bytes);
  if (j_cookies) env->DeleteLocalRef(j_cookies);
  env->DeleteGlobalRef(clazz);
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumProfile_nativeListSiteData(
    JNIEnv* env, jclass clazz, jboolean incognito, jstring profile,
    jlong request_id) {
  jclass global = static_cast<jclass>(env->NewGlobalRef(clazz));
  CefRefPtr<CefRequestContext> context =
      SiteDataContext(env, incognito, profile);
  if (!context) {
    std::vector<std::string> none;
    std::vector<int64_t> zero;
    DeliverSiteData(global, request_id, &none, &zero, &zero);
    return;
  }
  BuildSiteDataModel(
      context,
      base::BindOnce(
          [](jclass clazz, jlong request_id,
             std::unique_ptr<BrowsingDataModel> model) {
            // One row per owner: a model entry is per (owner, storage key).
            std::map<std::string, std::pair<int64_t, int64_t>> rows;
            for (const auto& entry : *model) {
              auto& row = rows[OwnerString(*entry.data_owner)];
              row.first += entry.data_details->storage_size;
              row.second += entry.data_details->cookie_count;
            }
            std::vector<std::string> owners;
            std::vector<int64_t> bytes;
            std::vector<int64_t> cookies;
            for (const auto& [owner, sizes] : rows) {
              owners.push_back(owner);
              bytes.push_back(sizes.first);
              cookies.push_back(sizes.second);
            }
            DeliverSiteData(clazz, request_id, &owners, &bytes, &cookies);
          },
          global, request_id));
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumProfile_nativeClearSiteData(
    JNIEnv* env, jclass clazz, jboolean incognito, jstring profile,
    jstring site, jlong request_id) {
  jclass global = static_cast<jclass>(env->NewGlobalRef(clazz));
  CefRefPtr<CefRequestContext> context =
      SiteDataContext(env, incognito, profile);
  const std::string wanted = JStr(env, site).ToString();
  if (!context) {
    DeliverSiteData(global, request_id, nullptr, nullptr, nullptr);
    return;
  }
  BuildSiteDataModel(
      context,
      base::BindOnce(
          [](jclass clazz, jlong request_id, std::string wanted,
             std::unique_ptr<BrowsingDataModel> model) {
            // The site as listed (host or origin), or an origin URL, which
            // also matches the entries Chromium groups under its host.
            const GURL url(wanted);
            const std::string host =
                url.is_valid() ? std::string(url.host()) : std::string();
            std::vector<BrowsingDataModel::DataOwner> matches;
            std::set<std::string> seen;
            for (const auto& entry : *model) {
              const std::string owner = OwnerString(*entry.data_owner);
              if ((owner == wanted || (!host.empty() &&
                                       (owner == host ||
                                        owner == url::Origin::Create(url).Serialize()))) &&
                  seen.insert(owner).second) {
                matches.push_back(*entry.data_owner);
              }
            }
            BrowsingDataModel* raw = model.get();
            auto finish = base::BindOnce(
                [](jclass clazz, jlong request_id,
                   std::unique_ptr<BrowsingDataModel>, size_t count) {
                  std::vector<std::string> cleared(count);
                  std::vector<int64_t> zero(count, 0);
                  DeliverSiteData(clazz, request_id, &cleared, &zero, &zero);
                },
                clazz, request_id, std::move(model), matches.size());
            if (matches.empty()) {
              std::move(finish).Run();
              return;
            }
            base::RepeatingClosure barrier =
                base::BarrierClosure(matches.size(), std::move(finish));
            for (const auto& owner : matches) {
              raw->RemoveBrowsingData(owner, barrier);
            }
          },
          global, request_id, wanted));
}

// FEATURE-031: PrintToPDF (URL/page -> PDF file). Delivers the result to Java
// onPdfPrinted on the UI thread; owns the global ref (freed in the destructor).
namespace {
class SdkPdfCallback : public CefPdfPrintCallback {
 public:
  SdkPdfCallback(jobject java_browser_global, jlong req_id)
      : java_browser_(java_browser_global), req_id_(req_id) {}
  void OnPdfPrintFinished(const CefString& path, bool ok) override {
    JNIEnv* env = base::android::AttachCurrentThread();
    if (!env) return;
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid =
        env->GetMethodID(cls, "onPdfPrinted", "(JLjava/lang/String;Z)V");
    if (mid) {
      jstring jp = env->NewStringUTF(path.ToString().c_str());
      env->CallVoidMethod(java_browser_, mid, req_id_, jp,
                          ok ? JNI_TRUE : JNI_FALSE);
      env->DeleteLocalRef(jp);
    }
    env->DeleteLocalRef(cls);
  }
  ~SdkPdfCallback() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }
 private:
  jobject java_browser_;
  jlong req_id_;
  IMPLEMENT_REFCOUNTING(SdkPdfCallback);
};
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativePrintToPdf(
    JNIEnv* env, jclass, jlong native_ptr, jobject browser_j, jstring path_j,
    jlong req_id) {
  jobject global = env->NewGlobalRef(browser_j);
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  CefRefPtr<SdkPdfCallback> cb = new SdkPdfCallback(global, req_id);
  if (!data || !data->browser || !data->browser->GetHost()) {
    cb->OnPdfPrintFinished(CefString(), false);  // deliver failure
    return;
  }
  CefPdfPrintSettings settings;  // zero-initialized defaults
  data->browser->GetHost()->PrintToPDF(JStr(env, path_j), settings, cb);
}

// ───────────────────── Dynamic WebSettings (FEATURE-025) ─────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetUserAgentOverride(
    JNIEnv* env, jclass, jlong native_ptr, jstring ua_j) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser || !data->browser->GetHost()) return;
  CefRefPtr<CefDictionaryValue> params = CefDictionaryValue::Create();
  params->SetString("userAgent", JStr(env, ua_j));
  data->browser->GetHost()->ExecuteDevToolsMethod(
      1, "Emulation.setUserAgentOverride", params);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetJavaScriptEnabled(
    JNIEnv* env, jclass, jlong native_ptr, jboolean enabled) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser || !data->browser->GetHost()) return;
  CefRefPtr<CefDictionaryValue> params = CefDictionaryValue::Create();
  params->SetBool("value", enabled == JNI_FALSE);  // setScriptExecutionDisabled
  data->browser->GetHost()->ExecuteDevToolsMethod(
      1, "Emulation.setScriptExecutionDisabled", params);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetZoomLevel(
    JNIEnv* env, jclass, jlong native_ptr, jdouble zoom) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser && data->browser->GetHost()) {
    data->browser->GetHost()->SetZoomLevel(zoom);
  }
}

// ───────────────────── Navigation extras (FEATURE-026) ─────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativePostUrl(
    JNIEnv* env, jclass, jlong native_ptr, jstring url_j, jbyteArray data_j) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser || !data->browser->GetMainFrame()) return;
  CefRefPtr<CefRequest> request = CefRequest::Create();
  request->SetURL(JStr(env, url_j));
  request->SetMethod("POST");
  if (data_j) {
    jsize len = env->GetArrayLength(data_j);
    jbyte* bytes = env->GetByteArrayElements(data_j, nullptr);
    CefRefPtr<CefPostData> post_data = CefPostData::Create();
    CefRefPtr<CefPostDataElement> element = CefPostDataElement::Create();
    element->SetToBytes(static_cast<size_t>(len), bytes);
    post_data->AddElement(element);
    request->SetPostData(post_data);
    env->ReleaseByteArrayElements(data_j, bytes, JNI_ABORT);
  }
  data->browser->GetMainFrame()->LoadRequest(request);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeStopLoading(
    JNIEnv* env, jclass, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) data->browser->StopLoad();
}

// FEATURE-028: Java resolves a JS dialog (alert/confirm/prompt).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeJsDialogResult(
    JNIEnv* env, jclass, jlong native_ptr, jlong token, jboolean success,
    jstring input_j) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client && data->client->js_dialog_handler()) {
    data->client->js_dialog_handler()->Resolve(token, success == JNI_TRUE,
                                               JStr(env, input_j));
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_CefriumBrowser_nativeCanGoBack(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) return data->browser->CanGoBack();
  return false;
}

// Dark mode via CEF API: CefRequestContext::SetChromeColorSchemeMode.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetDarkMode(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean dark) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) {
    // SetChromeColorScheme (ThemeService) is a no-op on Android; drive the web
    // content's prefers-color-scheme directly via a per-browser WebPreferences
    // override instead.
    auto* browser_impl =
        static_cast<CefBrowserHostBase*>(data->browser->GetHost().get());
    if (browser_impl) {
      browser_impl->SetPreferredColorSchemeDark(dark == JNI_TRUE);
    }
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetMediaPlaybackRequiresUserGesture(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean required) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) {
    return;
  }
  data->media_playback_requires_user_gesture = required == JNI_TRUE;
  if (data->browser) {
    static_cast<CefBrowserHostBase*>(data->browser->GetHost().get())
        ->SetMediaPlaybackRequiresUserGesture(required == JNI_TRUE);
  }
}

// Returns our id for the script (> 0), or -(n + 1) when rule n is invalid.
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeAddDocumentStartJavaScript(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring script_j,
    jobjectArray rules_j) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) {
    return 0;
  }
  std::vector<std::string> rules;
  origin_matcher::OriginMatcher matcher;
  const jsize n = env->GetArrayLength(rules_j);
  for (jsize i = 0; i < n; ++i) {
    auto rule_j = static_cast<jstring>(env->GetObjectArrayElement(rules_j, i));
    std::string rule = JStr(env, rule_j).ToString();
    env->DeleteLocalRef(rule_j);
    if (!matcher.AddRuleFromString(rule)) {
      return -(i + 1);
    }
    rules.push_back(std::move(rule));
  }
  const int id = data->next_script_id++;
  std::u16string script = JStr(env, script_j).ToString16();
  content::WebContents* wc =
      data->browser ? static_cast<CefBrowserHostBase*>(
                          data->browser->GetHost().get())
                          ->GetWebContents()
                    : nullptr;
  if (!wc) {
    data->pending_scripts.push_back({id, std::move(script), std::move(rules)});
    return id;
  }
  DocumentScripts::CreateForWebContents(wc);
  auto result = DocumentScripts::FromWebContents(wc)->host()
                    .AddPersistentJavaScript(
                        std::move(script),
                        js_injection::mojom::DocumentInjectionTime::kDocumentStart,
                        rules, /*world_identifier=*/0);
  if (!result.script_id) {
    return 0;
  }
  data->script_ids[id] = *result.script_id;
  return id;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeRemoveDocumentStartJavaScript(
    JNIEnv* env, jclass clazz, jlong native_ptr, jint id) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) {
    return;
  }
  std::erase_if(data->pending_scripts,
                [id](const auto& pending) { return pending.id == id; });
  auto it = data->script_ids.find(id);
  if (it == data->script_ids.end()) {
    return;
  }
  const int host_id = it->second;
  data->script_ids.erase(it);
  content::WebContents* wc =
      data->browser ? static_cast<CefBrowserHostBase*>(
                          data->browser->GetHost().get())
                          ->GetWebContents()
                    : nullptr;
  if (auto* scripts = wc ? DocumentScripts::FromWebContents(wc) : nullptr) {
    scripts->host().RemovePersistentJavaScript(host_id);
  }
}

// Context menu command — called from Java when user selects an option.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeContextMenuCommand(
    JNIEnv* env, jclass clazz, jlong native_ptr, jint command_id) {
  LOGI("nativeContextMenuCommand: ptr=%ld, cmd=%d", native_ptr, command_id);
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client) {
    auto* handler = static_cast<SdkContextMenuHandler*>(
        data->client->GetContextMenuHandler().get());
    if (handler) {
      if (command_id < 0) {
        handler->CancelMenu();
      } else {
        handler->ExecuteCommand(command_id);
      }
    }
  }
}

// Find in page via CEF API: CefBrowserHost::Find / StopFinding.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeFind(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring text_jstr,
    jboolean forward, jboolean match_case, jboolean find_next) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) {
    const char* text = env->GetStringUTFChars(text_jstr, nullptr);
    data->browser->GetHost()->Find(text, forward, match_case, find_next);
    env->ReleaseStringUTFChars(text_jstr, text);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeStopFinding(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean clear_selection) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) {
    data->browser->GetHost()->StopFinding(clear_selection);
  }
}

// Sets an EasyList-format list from a file; `list` names it, so loading the
// same list again replaces its rules. Returns its rule count, -1 if unreadable.
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_Cefrium_nativeLoadAdBlockList(
    JNIEnv* env, jclass clazz, jstring list_j, jstring path_j) {
  std::ifstream file(JStr(env, path_j).ToString());
  if (!file.is_open()) {
    return -1;
  }
  std::string content((std::istreambuf_iterator<char>(file)),
                      std::istreambuf_iterator<char>());
  return AdBlockFilter::Load(JStr(env, list_j).ToString(), content);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_Cefrium_nativeLoadYouTubeAdRules(JNIEnv* env, jclass clazz) {
  return AdBlockFilter::LoadYouTubeAdRules();
}

// Chrome's secure DNS settings, which apply to the whole browser. The templates
// are checked as Chrome checks them; secure mode needs at least one.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_Cefrium_nativeSetSecureDns(JNIEnv* env,
                                            jclass clazz,
                                            jstring mode_j,
                                            jstring templates_j) {
  const std::string mode = JStr(env, mode_j).ToString();
  const std::string templates = JStr(env, templates_j).ToString();
  if (mode != "off" && mode != "automatic" && mode != "secure") {
    return JNI_FALSE;
  }
  if (!templates.empty() &&
      !net::DnsOverHttpsConfig::FromString(templates).has_value()) {
    return JNI_FALSE;
  }
  if (mode == "secure" && templates.empty()) {
    return JNI_FALSE;
  }
  CefRefPtr<CefPreferenceManager> prefs =
      CefPreferenceManager::GetGlobalPreferenceManager();
  CefString error;
  for (const auto& [name, value] :
       {std::make_pair("dns_over_https.templates", templates),
        std::make_pair("dns_over_https.mode", mode)}) {
    CefRefPtr<CefValue> v = CefValue::Create();
    v->SetString(value);
    if (!prefs->SetPreference(name, v, error)) {
      LOG(ERROR) << "setSecureDns: " << name << ": " << error.ToString();
      return JNI_FALSE;
    }
  }
  return JNI_TRUE;
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
  const std::string url = JStr(env, url_j).ToString();
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
