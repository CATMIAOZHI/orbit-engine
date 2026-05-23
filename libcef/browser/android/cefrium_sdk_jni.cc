// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// JNI bridge for Cefrium SDK Java API (com.cefrium.*).
// Implements native methods declared in Cefrium.java and CefriumBrowser.java.

#include <jni.h>
#include <string>

#include "cef/include/cef_app.h"
#include "cef/include/cef_browser.h"
#include "cef/include/cef_client.h"
#include "cef/include/cef_life_span_handler.h"
#include "cef/include/cef_load_handler.h"
#include "cef/include/cef_render_handler.h"
#include "cef/include/cef_request_handler.h"
#include "cef/include/cef_resource_request_handler.h"

#include <android/log.h>
#include "base/android/jni_android.h"
#include "cef/libcef/browser/android/browser_surface_android.h"
#include "cef/libcef/browser/browser_host_base.h"
#include "ui/android/window_android.h"

#define TAG "Cefrium"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

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

  IMPLEMENT_REFCOUNTING(SdkLifeSpanHandler);

 private:
  struct SdkBrowserData* data_ = nullptr;
};

// Load handler that triggers Invalidate on load completion.
// Without this, the off-screen compositor never generates a frame.
class SdkLoadHandler : public CefLoadHandler {
 public:
  void OnLoadEnd(CefRefPtr<CefBrowser> browser,
                 CefRefPtr<CefFrame> frame,
                 int httpStatusCode) override {
    LOGI("OnLoadEnd: status %d, isMain=%d", httpStatusCode, frame->IsMain());
    if (frame->IsMain()) {
      browser->GetHost()->Invalidate(PET_VIEW);
    }
  }
  IMPLEMENT_REFCOUNTING(SdkLoadHandler);
};

// Ad-blocking request handler. Blocks requests to known ad/tracking domains.
class SdkResourceRequestHandler : public CefResourceRequestHandler {
 public:
  explicit SdkResourceRequestHandler(int* blocked_count)
      : blocked_count_(blocked_count) {}

  CefResourceRequestHandler::ReturnValue OnBeforeResourceLoad(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefFrame> frame,
      CefRefPtr<CefRequest> request,
      CefRefPtr<CefCallback> callback) override {
    std::string url = request->GetURL().ToString();

    // Check against blocklist
    static const char* blocklist[] = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "facebook.com/tr", "facebook.net/signals",
        "analytics.", "pagead2.", "adserver.", "ads.", "tracking.",
        "scorecardresearch.com", "quantserve.com", "outbrain.com",
        "taboola.com", "amazon-adsystem.com", "adsrvr.org",
        "criteo.com", "moatads.com", "pubmatic.com", "rubiconproject.com",
        "bidswitch.net", "casalemedia.com", "openx.net",
        nullptr
    };

    for (int i = 0; blocklist[i]; i++) {
      if (url.find(blocklist[i]) != std::string::npos) {
        if (blocked_count_) (*blocked_count_)++;
        LOGI("BLOCKED: %s", url.substr(0, 80).c_str());
        return RV_CANCEL;
      }
    }
    return RV_CONTINUE;
  }

  IMPLEMENT_REFCOUNTING(SdkResourceRequestHandler);

 private:
  int* blocked_count_;
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

  IMPLEMENT_REFCOUNTING(SdkRequestHandler);

 private:
  CefRefPtr<SdkResourceRequestHandler> resource_handler_;
};

// Minimal client that connects handlers.
class SdkClient : public CefClient {
 public:
  SdkClient(CefRefPtr<SdkRenderHandler> render_handler,
            CefRefPtr<SdkLifeSpanHandler> life_span_handler,
            int* blocked_count = nullptr)
      : render_handler_(render_handler),
        life_span_handler_(life_span_handler),
        load_handler_(new SdkLoadHandler()),
        request_handler_(blocked_count ? new SdkRequestHandler(blocked_count) : nullptr) {}

  CefRefPtr<CefRenderHandler> GetRenderHandler() override {
    return render_handler_;
  }

  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override {
    return life_span_handler_;
  }

  CefRefPtr<CefLoadHandler> GetLoadHandler() override {
    return load_handler_;
  }

  CefRefPtr<CefRequestHandler> GetRequestHandler() override {
    return request_handler_;
  }

  IMPLEMENT_REFCOUNTING(SdkClient);

 private:
  CefRefPtr<SdkRenderHandler> render_handler_;
  CefRefPtr<SdkLifeSpanHandler> life_span_handler_;
  CefRefPtr<SdkLoadHandler> load_handler_;
  CefRefPtr<SdkRequestHandler> request_handler_;
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

// --- Cefrium.java native methods ---

extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_Cefrium_nativeInitialize(
    JNIEnv* env, jclass clazz, jstring cache_dir_jstr, jstring data_dir_jstr) {
  const char* cache_dir = env->GetStringUTFChars(cache_dir_jstr, nullptr);
  const char* data_dir = env->GetStringUTFChars(data_dir_jstr, nullptr);

  static const char* argv[] = {"cefrium"};
  CefMainArgs main_args(1, const_cast<char**>(argv));
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

  // Get the native WindowAndroid from the Java object.
  base::android::ScopedJavaLocalRef<jobject> j_window =
      base::android::ScopedJavaLocalRef<jobject>::Adopt(env, java_window_android);
  ui::WindowAndroid* window =
      ui::WindowAndroid::FromJavaWindowAndroid(j_window);
  // Prevent the local ref from being released — Java still owns it.
  j_window.Release();
  if (!window) {
    LOGI("nativeCreateWithSurface: WindowAndroid is null!");
    delete data;
    return 0;
  }
  data->window_android = window;

  // Create the Surface bridge (compositor).
  data->surface_bridge = std::make_unique<BrowserSurfaceAndroid>(window);

  // Set the Surface immediately.
  auto j_surface = base::android::ScopedJavaLocalRef<jobject>::Adopt(env, java_surface);
  data->surface_bridge->SurfaceChanged(env, j_surface, width, height);
  j_surface.Release();

  // Create life span handler to capture the browser reference.
  data->life_span_handler = new SdkLifeSpanHandler();
  data->life_span_handler->SetBrowserData(data);

  // Create client without render handler (Surface mode doesn't use OnPaint).
  data->client = new SdkClient(
      nullptr,  // No render handler for Surface mode
      data->life_span_handler,
      &data->blocked_count);

  LOGI("nativeCreateWithSurface: %dx%d scale=%.2f (Surface mode)", width,
       height, scale);

  // Create browser in NON-windowless mode.
  // Chromium creates WebContentsViewAndroid + RenderWidgetHostViewAndroid
  // instead of OSR variants. Video, audio, and GPU compositing all work.
  CefWindowInfo window_info;
  // Do NOT call SetAsWindowless — leave default (windowed/Surface mode).

  CefBrowserSettings browser_settings;
  browser_settings.background_color = CefColorSetARGB(255, 255, 255, 255);

  CefBrowserHost::CreateBrowser(window_info, data->client, CefString(""),
                                browser_settings, nullptr, nullptr);

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
