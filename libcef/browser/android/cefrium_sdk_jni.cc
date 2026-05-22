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

#include <android/log.h>
#include "base/android/jni_android.h"

#define TAG "Cefrium"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// Per-browser render handler that forwards OnPaint to Java.
class SdkRenderHandler : public CefRenderHandler {
 public:
  SdkRenderHandler(int width, int height)
      : width_(width), height_(height) {}

  void SetJavaBrowser(JNIEnv* env, jobject java_browser) {
    java_browser_ = env->NewGlobalRef(java_browser);
  }

  void GetViewRect(CefRefPtr<CefBrowser> browser, CefRect& rect) override {
    rect = CefRect(0, 0, width_, height_);
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

// Minimal client that connects handlers.
class SdkClient : public CefClient {
 public:
  SdkClient(CefRefPtr<SdkRenderHandler> render_handler,
            CefRefPtr<SdkLifeSpanHandler> life_span_handler)
      : render_handler_(render_handler),
        life_span_handler_(life_span_handler),
        load_handler_(new SdkLoadHandler()) {}

  CefRefPtr<CefRenderHandler> GetRenderHandler() override {
    return render_handler_;
  }

  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override {
    return life_span_handler_;
  }

  CefRefPtr<CefLoadHandler> GetLoadHandler() override {
    return load_handler_;
  }

  IMPLEMENT_REFCOUNTING(SdkClient);

 private:
  CefRefPtr<SdkRenderHandler> render_handler_;
  CefRefPtr<SdkLifeSpanHandler> life_span_handler_;
  CefRefPtr<SdkLoadHandler> load_handler_;
};

// Per-browser data stored as the native pointer in Java.
struct SdkBrowserData {
  CefRefPtr<CefBrowser> browser;
  CefRefPtr<SdkRenderHandler> render_handler;
  CefRefPtr<SdkLifeSpanHandler> life_span_handler;
  CefRefPtr<SdkClient> client;
  std::string pending_url;  // URL to load once browser is ready
};

void SdkLifeSpanHandler::OnAfterCreated(CefRefPtr<CefBrowser> browser) {
  LOGI("OnAfterCreated: browser ready (id=%d)", browser->GetIdentifier());
  if (data_) {
    data_->browser = browser;
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
    JNIEnv* env, jclass clazz, jint width, jint height) {
  auto* data = new SdkBrowserData();
  data->render_handler = new SdkRenderHandler(width, height);
  data->life_span_handler = new SdkLifeSpanHandler();
  data->life_span_handler->SetBrowserData(data);
  data->client = new SdkClient(data->render_handler, data->life_span_handler);
  LOGI("nativeCreate: %dx%d (browser not yet created)", width, height);
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

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeClose(
    JNIEnv* env, jobject thiz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data) {
    if (data->browser) {
      data->browser->GetHost()->CloseBrowser(true);
    }
    delete data;
  }
}
