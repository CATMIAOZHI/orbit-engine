// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// JNI bridge for Cefrium SDK Java API (com.cefrium.*).
// Implements native methods declared in Cefrium.java and CefriumBrowser.java.

#include <cinttypes>
#include <jni.h>
#include <map>
#include <string>
#include <vector>

#include "base/base64.h"
#include "cef/include/cef_app.h"
#include "cef/libcef/browser/android/cef_autofill_client_android.h"
#include "components/android_autofill/browser/autofill_provider.h"
#include "cef/include/cef_browser.h"
#include "cef/include/cef_client.h"
#include "cef/include/cef_cookie.h"
#include "cef/include/cef_request.h"
#include "cef/include/cef_request_context.h"
#include "cef/include/cef_values.h"
#include "cef/include/cef_context_menu_handler.h"
#include "cef/include/cef_display_handler.h"
#include "cef/include/cef_download_handler.h"
#include "cef/include/cef_find_handler.h"
#include "cef/include/cef_jsdialog_handler.h"
#include "cef/include/cef_life_span_handler.h"
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
#include "cef/include/cef_resource_request_handler.h"

#include <android/log.h>
#include "base/android/jni_android.h"
#include "cef/libcef/browser/android/adblock_filter.h"
#include "cef/libcef/browser/android/browser_surface_android.h"
#include "cef/libcef/browser/browser_host_base.h"
#include "content/public/browser/web_contents.h"
#include "ui/android/window_android.h"

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

}  // namespace

// Per-browser render handler that forwards OnPaint to Java.
class SdkRenderHandler : public CefRenderHandler {
 public:
  SdkRenderHandler(int width, int height, float scale)
      : width_(width), height_(height), scale_(scale) {}

  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    java_browser_ = env->NewGlobalRef(java_browser);
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
    jmethodID method = env->GetMethodID(cls, "onPaint",
        "(IILjava/nio/ByteBuffer;)V");
    if (method) {
      env->CallVoidMethod(java_browser_, method, width, height, byte_buffer);
    }
    env->DeleteLocalRef(byte_buffer);
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
};

// Life span handler that captures the browser when created.
class SdkLifeSpanHandler : public CefLifeSpanHandler {
 public:
  void SetBrowserData(struct SdkBrowserData* data) { data_ = data; }

  void OnAfterCreated(CefRefPtr<CefBrowser> browser) override;

  // FEATURE-023: forward browser-close to the JS bridge router for cleanup.
  void OnBeforeClose(CefRefPtr<CefBrowser> browser) override {
    if (message_router_) {
      message_router_->OnBeforeClose(browser);
    }
  }

  void SetMessageRouter(CefRefPtr<CefMessageRouterBrowserSide> router) {
    message_router_ = router;
  }

  void SetJavaBrowser(JNIEnv* env, jobject jb) {
    if (jb) java_browser_ = env->NewGlobalRef(jb);
  }

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

// Download handler — saves files to the app's download directory.
// Notifies Java of download progress via onDownloadUpdated callback.
class SdkDownloadHandler : public CefDownloadHandler {
 public:
  void SetDownloadDir(const std::string& dir) { download_dir_ = dir; }

  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    if (java_browser) java_browser_ = env->NewGlobalRef(java_browser);
  }

  bool OnBeforeDownload(CefRefPtr<CefBrowser> browser,
                        CefRefPtr<CefDownloadItem> download_item,
                        const CefString& suggested_name,
                        CefRefPtr<CefBeforeDownloadCallback> callback) override {
    std::string path = download_dir_ + "/" + suggested_name.ToString();
    LOGI("Download: %s -> %s", download_item->GetURL().ToString().c_str(),
         path.c_str());
    callback->Continue(path, false);
    NotifyJava(suggested_name.ToString(), path,
               download_item->GetPercentComplete(), false, false);
    return true;
  }

  void OnDownloadUpdated(CefRefPtr<CefBrowser> browser,
                         CefRefPtr<CefDownloadItem> download_item,
                         CefRefPtr<CefDownloadItemCallback> callback) override {
    std::string filename = download_item->GetFullPath().ToString();
    // Extract just the filename from the path.
    size_t pos = filename.rfind('/');
    std::string name = (pos != std::string::npos) ?
        filename.substr(pos + 1) : filename;

    if (download_item->IsComplete()) {
      LOGI("Download complete: %s (%" PRId64 " bytes)",
           filename.c_str(),
           static_cast<int64_t>(download_item->GetReceivedBytes()));
      NotifyJava(name, filename, 100, true, false);
    } else if (download_item->IsCanceled()) {
      LOGI("Download canceled");
      NotifyJava(name, filename, 0, false, true);
    } else {
      NotifyJava(name, filename,
                 download_item->GetPercentComplete(), false, false);
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
  void NotifyJava(const std::string& name, const std::string& path,
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

  std::string download_dir_;
  jobject java_browser_ = nullptr;
};

// Context menu handler — long-press menu shown as Android AlertDialog.
// Uses RunContextMenu to intercept display and forward to Java.
class SdkContextMenuHandler : public CefContextMenuHandler {
 public:
  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    if (java_browser) java_browser_ = env->NewGlobalRef(java_browser);
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
};

// Global AdBlock filter engine — shared across all browsers.
static AdBlockFilter* g_adblock_filter = nullptr;

// Ad-blocking request handler using EasyList filter engine.
class SdkResourceRequestHandler : public CefResourceRequestHandler {
 public:
  explicit SdkResourceRequestHandler(int* blocked_count)
      : blocked_count_(blocked_count) {}

  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    if (java_browser) java_browser_ = env->NewGlobalRef(java_browser);
  }

  CefResourceRequestHandler::ReturnValue OnBeforeResourceLoad(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      CefRefPtr<CefRequest> request,
      CefRefPtr<CefCallback> callback) override {
    std::string url = request->GetURL().ToString();
    std::string method = request->GetMethod().ToString();

    bool blocked = g_adblock_filter && g_adblock_filter->ShouldBlock(url);

    if (blocked) {
      if (blocked_count_) (*blocked_count_)++;
    }

    // Consumer interceptor (FEATURE-027): if not already ad-blocked, ask the
    // Java RequestInterceptor synchronously (we are on the IO thread).
    if (!blocked && java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) {
        jclass cls = env->GetObjectClass(java_browser_);
        jmethodID mid = env->GetMethodID(
            cls, "onShouldBlockRequest",
            "(Ljava/lang/String;Ljava/lang/String;Z)Z");
        if (mid) {
          jstring ju = env->NewStringUTF(url.c_str());
          jstring jm = env->NewStringUTF(method.c_str());
          jboolean r = env->CallBooleanMethod(
              java_browser_, mid, ju, jm, frame && frame->IsMain());
          env->DeleteLocalRef(ju);
          env->DeleteLocalRef(jm);
          if (r) {
            blocked = true;
            if (blocked_count_) (*blocked_count_)++;
          }
        }
        env->DeleteLocalRef(cls);
      }
    }

    // Report to Java if listener is set.
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) {
        jclass cls = env->GetObjectClass(java_browser_);
        jmethodID mid = env->GetMethodID(cls, "onRequestIntercepted",
                                         "(Ljava/lang/String;Ljava/lang/String;Z)V");
        if (mid) {
          jstring j_url = env->NewStringUTF(url.substr(0, 200).c_str());
          jstring j_method = env->NewStringUTF(method.c_str());
          env->CallVoidMethod(java_browser_, mid, j_url, j_method, blocked);
          env->DeleteLocalRef(j_url);
          env->DeleteLocalRef(j_method);
        }
        env->DeleteLocalRef(cls);
      }
    }

    return blocked ? RV_CANCEL : RV_CONTINUE;
  }

  ~SdkResourceRequestHandler() override {
    if (java_browser_) {
      JNIEnv* env = base::android::AttachCurrentThread();
      if (env) env->DeleteGlobalRef(java_browser_);
    }
  }

  IMPLEMENT_REFCOUNTING(SdkResourceRequestHandler);

 private:
  int* blocked_count_;
  jobject java_browser_ = nullptr;
};

class SdkRequestHandler : public CefRequestHandler {
 public:
  explicit SdkRequestHandler(int* blocked_count)
      : resource_handler_(new SdkResourceRequestHandler(blocked_count)) {}

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
    if (message_router_) {
      message_router_->OnRenderProcessTerminated(browser);
    }
  }

  void SetMessageRouter(CefRefPtr<CefMessageRouterBrowserSide> router) {
    message_router_ = router;
  }

  IMPLEMENT_REFCOUNTING(SdkRequestHandler);

 private:
  CefRefPtr<SdkResourceRequestHandler> resource_handler_;
  CefRefPtr<CefMessageRouterBrowserSide> message_router_;
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
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID method = env->GetMethodID(
        cls, "onJsQuery", "(JLjava/lang/String;Ljava/lang/String;)V");
    if (method) {
      jstring j_req = env->NewStringUTF(request.ToString().c_str());
      jstring j_origin = env->NewStringUTF(origin.c_str());
      env->CallVoidMethod(java_browser_, method, static_cast<jlong>(query_id),
                          j_req, j_origin);
      env->DeleteLocalRef(j_req);
      env->DeleteLocalRef(j_origin);
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

  // Called from nativeRespondPermission once Java decides.
  void Respond(uint64_t prompt_id, bool grant) {
    CefRefPtr<CefPermissionPromptCallback> cb;
    {
      base::AutoLock lock(lock_);
      auto it = pending_.find(prompt_id);
      if (it == pending_.end()) {
        return;
      }
      cb = it->second;
      pending_.erase(it);
    }
    cb->Continue(grant ? CEF_PERMISSION_RESULT_ACCEPT
                       : CEF_PERMISSION_RESULT_DENY);
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
  IMPLEMENT_REFCOUNTING(SdkPermissionHandler);
};

class SdkClient : public CefClient {
 public:
  SdkClient(CefRefPtr<SdkRenderHandler> render_handler,
            CefRefPtr<SdkLifeSpanHandler> life_span_handler,
            int* blocked_count = nullptr,
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
        request_handler_(blocked_count ? new SdkRequestHandler(blocked_count) : nullptr) {
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

  CefRefPtr<SdkPermissionHandler> permission_handler() {
    return permission_handler_;
  }
  CefRefPtr<SdkJSDialogHandler> js_dialog_handler() { return js_dialog_handler_; }
  CefRefPtr<SdkDisplayHandler> display_handler() { return display_handler_; }
  CefRefPtr<SdkLoadHandler> load_handler_ref() { return load_handler_; }
  CefRefPtr<SdkDownloadHandler> download_handler() { return download_handler_; }
  CefRefPtr<SdkFindHandler> find_handler() { return find_handler_; }
  CefriumBridgeHandler* bridge_handler() { return bridge_handler_.get(); }

  IMPLEMENT_REFCOUNTING(SdkClient);

 private:
  CefRefPtr<SdkRenderHandler> render_handler_;
  CefRefPtr<SdkLifeSpanHandler> life_span_handler_;
  CefRefPtr<SdkLoadHandler> load_handler_;
  CefRefPtr<SdkDisplayHandler> display_handler_;
  CefRefPtr<SdkDownloadHandler> download_handler_;
  CefRefPtr<SdkContextMenuHandler> context_menu_handler_;
  CefRefPtr<SdkFindHandler> find_handler_;
  CefRefPtr<SdkJSDialogHandler> js_dialog_handler_;
  CefRefPtr<SdkPermissionHandler> permission_handler_;
  CefRefPtr<SdkRequestHandler> request_handler_;
  // Declared last so the router is destroyed before the handler it references.
  std::unique_ptr<CefriumBridgeHandler> bridge_handler_;
  CefRefPtr<CefMessageRouterBrowserSide> message_router_;
};

// Per-browser data stored as the native pointer in Java.
struct SdkBrowserData {
  CefRefPtr<CefBrowser> browser;
  CefRefPtr<SdkRenderHandler> render_handler;
  CefRefPtr<SdkLifeSpanHandler> life_span_handler;
  CefRefPtr<SdkClient> client;
  std::string pending_url;
  int blocked_count = 0;
  bool adblock_enabled = false;

  // Surface rendering mode (null for OSR mode).
  std::unique_ptr<BrowserSurfaceAndroid> surface_bridge;
  ui::WindowAndroid* window_android = nullptr;  // Owned by Java via prevent-GC ref.
  bool is_surface_mode = false;
};

void SdkLifeSpanHandler::OnAfterCreated(CefRefPtr<CefBrowser> browser) {
  LOGI("OnAfterCreated: browser ready (id=%d, surface_mode=%d)",
       browser->GetIdentifier(), data_ ? data_->is_surface_mode : 0);
  if (data_) {
    data_->browser = browser;

    // In Surface mode, attach the WebContents to the compositor.
    if (data_->is_surface_mode && data_->surface_bridge) {
      auto* browser_impl = static_cast<CefBrowserHostBase*>(
          browser->GetHost().get());
      content::WebContents* web_contents = browser_impl->GetWebContents();
      if (web_contents) {
        data_->surface_bridge->AttachWebContents(web_contents);
        LOGI("Surface mode: WebContents attached to compositor");
      }
    }
  }
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

// --- Cefrium.java native methods ---

extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_Cefrium_nativeInitialize(
    JNIEnv* env, jclass clazz, jstring cache_dir_jstr, jstring data_dir_jstr) {
  const char* cache_dir = env->GetStringUTFChars(cache_dir_jstr, nullptr);
  const char* data_dir = env->GetStringUTFChars(data_dir_jstr, nullptr);

  static const char* argv[] = {"cefrium"};
  CefMainArgs main_args(1, const_cast<char**>(argv));
  // FEATURE-023 Phase 1: the browser-process app is intentionally NOT set here.
  // Passing a CefApp to CefInitialize crashes this port in
  // CefRequestContextImpl::ExecuteWhenBrowserContextInitialized (Phase 2 to
  // resolve). The renderer's CefRenderProcessHandler is driven by the child
  // process's app registered in cef_jni_onload.cc, independent of this.
  int exit_code = CefExecuteProcess(main_args, nullptr, nullptr);
  if (exit_code >= 0) {
    env->ReleaseStringUTFChars(cache_dir_jstr, cache_dir);
    env->ReleaseStringUTFChars(data_dir_jstr, data_dir);
    return exit_code;
  }

  CefSettings settings;
  settings.no_sandbox = true;
  settings.windowless_rendering_enabled = true;
  settings.multi_threaded_message_loop = false;
  settings.log_severity = LOGSEVERITY_INFO;
  CefString(&settings.root_cache_path).FromASCII(cache_dir);

  // Identify as Chrome Android so sites serve full-featured content.
  // CEF IS Chromium — this is accurate identification, not spoofing.
  CefString(&settings.user_agent_product)
      .FromASCII("Chrome/148.0.7778.167 Cefrium/1.0 Mobile");

  std::string locales_dir = std::string(data_dir) + "/locales";
  CefString(&settings.locales_dir_path).FromASCII(locales_dir.c_str());

  bool result = CefInitialize(main_args, settings, nullptr, nullptr);
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
    JNIEnv* env, jclass clazz, jint width, jint height, jfloat scale) {
  auto* data = new SdkBrowserData();
  data->render_handler = new SdkRenderHandler(width, height, scale);
  data->life_span_handler = new SdkLifeSpanHandler();
  data->life_span_handler->SetBrowserData(data);
  data->client = new SdkClient(data->render_handler, data->life_span_handler,
                               &data->blocked_count);
  LOGI("nativeCreate: %dx%d (browser not yet created, adblock active)", width, height);
  {
    base::AutoLock lock(BridgeDataLock());
    LiveBridgeData().insert(reinterpret_cast<jlong>(data));
  }
  return reinterpret_cast<jlong>(data);
}

// Actually create the browser with the URL (must have URL at creation time
// for renderer process to spawn correctly).
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
    browser_settings.background_color = CefColorSetARGB(255, 255, 255, 255);

    data->pending_url = url;
    CefBrowserHost::CreateBrowser(window_info, data->client, CefString(url),
                                  browser_settings, nullptr, nullptr);
    LOGI("CreateBrowser with URL: %s", url);
  } else {
    data->browser->GetMainFrame()->LoadURL(CefString(url));
    LOGI("loadUrl: %s", url);
  }
  env->ReleaseStringUTFChars(url_jstr, url);
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
    data->client->load_handler_ref()->SetJavaBrowser(env, java_browser);
    data->client->find_handler()->SetJavaBrowser(env, java_browser);
    data->client->js_dialog_handler()->SetJavaBrowser(env, java_browser);
    data->client->permission_handler()->SetJavaBrowser(env, java_browser);
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

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeInvalidate(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) {
    data->browser->GetHost()->Invalidate(PET_VIEW);
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

// Ad-blocking: enable/disable and get blocked count.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetAdBlockEnabled(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean enabled) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data) {
    data->adblock_enabled = enabled;
    LOGI("AdBlock %s", enabled ? "ENABLED" : "DISABLED");
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeGetBlockedCount(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  return data ? data->blocked_count : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeClose(
    JNIEnv* env, jobject thiz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data) {
    if (data->browser) {
      data->browser->GetHost()->CloseBrowser(true);
    }
    data->surface_bridge.reset();
    // FEATURE-023 step 4b: stop any in-flight async resolution from touching
    // this data before we delete it.
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
    jobject java_surface, jint width, jint height, jfloat scale) {
  auto* data = new SdkBrowserData();
  data->is_surface_mode = true;

  // Create life span handler to capture the browser reference.
  data->life_span_handler = new SdkLifeSpanHandler();
  data->life_span_handler->SetBrowserData(data);

  // Create client without render handler (Surface mode doesn't use OnPaint).
  // Rendering is handled by ContentViewRenderView on the Java side.
  data->client = new SdkClient(
      nullptr,  // No render handler — ContentViewRenderView handles rendering
      data->life_span_handler,
      &data->blocked_count);

  // Set download directory to app's external files/Download.
  // TODO: get from Java instead of hardcoding.
  data->client->download_handler()->SetDownloadDir("/sdcard/Download");

  LOGI("nativeCreateWithSurface: %dx%d (windowed mode, ContentViewRenderView)", width, height);

  // Create browser in NON-windowless mode.
  // Chromium creates WebContentsViewAndroid + RenderWidgetHostViewAndroid.
  // ContentViewRenderView (Java) manages the compositor and Surface.
  CefWindowInfo window_info;
  // Do NOT call SetAsWindowless — leave default (windowed/Surface mode).

  CefBrowserSettings browser_settings;
  browser_settings.background_color = CefColorSetARGB(255, 255, 255, 255);

  CefBrowserHost::CreateBrowser(window_info, data->client, CefString(""),
                                browser_settings, nullptr, nullptr);

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
    jstring value_j) {
  CefRefPtr<CefCookieManager> mgr = CookieManagerFor(native_ptr);
  if (!mgr) return;
  CefString url = JStr(env, url_j);
  CefCookie cookie;  // zero-initialized (CefStructBase)
  CefString(&cookie.name) = JStr(env, name_j);
  CefString(&cookie.value) = JStr(env, value_j);
  cookie.has_expires = false;  // session cookie; domain/path inferred from url
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
    auto context = data->browser->GetHost()->GetRequestContext();
    if (context) {
      context->SetChromeColorScheme(
          dark ? CEF_COLOR_VARIANT_DARK : CEF_COLOR_VARIANT_LIGHT, 0);
    }
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

// Load EasyList filter rules from a file path.
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_Cefrium_nativeLoadAdBlockList(
    JNIEnv* env, jclass clazz, jstring path_jstr) {
  if (!g_adblock_filter) {
    g_adblock_filter = new AdBlockFilter();
    g_adblock_filter->AddYouTubeAdRules();
  }
  const char* path = env->GetStringUTFChars(path_jstr, nullptr);
  int count = g_adblock_filter->LoadFromFile(path);
  env->ReleaseStringUTFChars(path_jstr, path);
  LOGI("AdBlock: loaded %d rules from %s", count, path);
  return count;
}
