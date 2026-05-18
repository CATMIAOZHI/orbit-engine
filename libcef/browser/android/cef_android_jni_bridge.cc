// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// JNI bridge for Android test APK. Compiled into libcef.so (not a separate
// shared library) to share static globals with the rest of Chromium code.

#include <jni.h>
#include <string>

#include <stdio.h>
#include "cef/include/cef_app.h"
#include "cef/include/cef_command_line.h"
#include "cef/include/cef_browser.h"
#include "cef/include/cef_client.h"
#include "cef/include/cef_life_span_handler.h"
#include "cef/include/cef_load_handler.h"
#include "cef/include/cef_render_handler.h"

#include <android/log.h>

#define TAG "Cefrium"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool g_cef_initialized = false;

// Minimal CefRenderHandler for off-screen rendering (required for windowless).
static bool g_load_complete = false;
static bool g_frame_captured = false;
static char g_png_output_path[512] = {};

class TestRenderHandler : public CefRenderHandler {
 public:
  void GetViewRect(CefRefPtr<CefBrowser> browser, CefRect& rect) override {
    rect = CefRect(0, 0, 800, 600);
  }
  void OnPaint(CefRefPtr<CefBrowser> browser,
               PaintElementType type,
               const RectList& dirtyRects,
               const void* buffer,
               int width,
               int height) override {
    if (!g_load_complete || g_frame_captured || width == 0 || height == 0)
      return;
    g_frame_captured = true;

    LOGI("OnPaint: %dx%d frame received, saving BMP", width, height);

    // Write as BMP (no external deps needed). BGRA → BMP is trivial.
    FILE* f = fopen(g_png_output_path, "wb");
    if (f) {
      int row_size = width * 4;
      int data_size = row_size * height;
      int file_size = 54 + data_size;

      // BMP header
      uint8_t hdr[54] = {};
      hdr[0] = 'B'; hdr[1] = 'M';
      hdr[2] = file_size; hdr[3] = file_size >> 8;
      hdr[4] = file_size >> 16; hdr[5] = file_size >> 24;
      hdr[10] = 54;  // data offset
      hdr[14] = 40;  // DIB header size
      hdr[18] = width; hdr[19] = width >> 8;
      hdr[22] = height; hdr[23] = height >> 8;
      hdr[26] = 1;   // planes
      hdr[28] = 32;  // bpp
      fwrite(hdr, 1, 54, f);

      // BMP stores rows bottom-to-top
      const uint8_t* pixels = static_cast<const uint8_t*>(buffer);
      for (int y = height - 1; y >= 0; y--) {
        fwrite(pixels + y * row_size, 1, row_size, f);
      }
      fclose(f);
      LOGI("Frame saved to %s (%dx%d, %d bytes)", g_png_output_path,
           width, height, file_size);
    } else {
      LOGE("Failed to open %s for writing", g_png_output_path);
    }
  }
  IMPLEMENT_REFCOUNTING(TestRenderHandler);
};

// Minimal CefLoadHandler to detect OnLoadEnd.
class TestLoadHandler : public CefLoadHandler {
 public:
  void OnLoadEnd(CefRefPtr<CefBrowser> browser,
                 CefRefPtr<CefFrame> frame,
                 int httpStatusCode) override {
    if (frame->IsMain()) {
      LOGI("LOAD COMPLETE — OnLoadEnd fired (status %d)", httpStatusCode);
      g_load_complete = true;
      // Force a paint by invalidating the view.
      browser->GetHost()->Invalidate(PET_VIEW);
    }
  }
  IMPLEMENT_REFCOUNTING(TestLoadHandler);
};

// Minimal CefClient that connects the handlers.
class TestClient : public CefClient {
 public:
  TestClient()
      : render_handler_(new TestRenderHandler()),
        load_handler_(new TestLoadHandler()) {}

  CefRefPtr<CefRenderHandler> GetRenderHandler() override {
    return render_handler_;
  }
  CefRefPtr<CefLoadHandler> GetLoadHandler() override {
    return load_handler_;
  }

 private:
  CefRefPtr<TestRenderHandler> render_handler_;
  CefRefPtr<TestLoadHandler> load_handler_;
  IMPLEMENT_REFCOUNTING(TestClient);
};

extern "C" __attribute__((visibility("default"))) JNIEXPORT jint JNICALL
Java_org_cefrium_test_CefriumTestActivity_nativeInitCef(
    JNIEnv* env,
    jobject /* this */,
    jstring cache_dir_jstr,
    jstring data_dir_jstr) {
  LOGI("nativeInitCef called");

  const char* cache_dir = env->GetStringUTFChars(cache_dir_jstr, nullptr);
  const char* data_dir = env->GetStringUTFChars(data_dir_jstr, nullptr);

  LOGI("cache_dir: %s", cache_dir);
  LOGI("data_dir: %s", data_dir);

  snprintf(g_png_output_path, sizeof(g_png_output_path),
           "%s/cef_frame.png", data_dir);

  // Multi-process mode: renderer launched as Android Service.
  // --in-process-gpu keeps GPU in browser process (emulator compat).
  // --no-sandbox required until sandbox policy is configured for CEF.
  static const char* argv[] = {"cefrium", "--in-process-gpu", "--no-sandbox"};
  CefMainArgs main_args(3, const_cast<char**>(argv));
  int exit_code = CefExecuteProcess(main_args, nullptr, nullptr);
  if (exit_code >= 0) {
    LOGE("CefExecuteProcess returned %d", exit_code);
    env->ReleaseStringUTFChars(cache_dir_jstr, cache_dir);
    env->ReleaseStringUTFChars(data_dir_jstr, data_dir);
    return exit_code;
  }
  LOGI("CefExecuteProcess returned %d, continuing as browser", exit_code);

  CefSettings settings;
  settings.no_sandbox = true;
  settings.windowless_rendering_enabled = true;
  settings.multi_threaded_message_loop = false;
  settings.log_severity = LOGSEVERITY_INFO;

  CefString(&settings.root_cache_path).FromASCII(cache_dir);

  std::string locales_dir = std::string(data_dir) + "/locales";
  CefString(&settings.locales_dir_path).FromASCII(locales_dir.c_str());

  LOGI("Calling CefInitialize...");
  bool result = CefInitialize(main_args, settings, nullptr, nullptr);

  env->ReleaseStringUTFChars(cache_dir_jstr, cache_dir);
  env->ReleaseStringUTFChars(data_dir_jstr, data_dir);

  if (result) {
    LOGI("CefInitialize SUCCESS");
    g_cef_initialized = true;
    return 0;
  } else {
    LOGE("CefInitialize FAILED");
    return 1;
  }
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_org_cefrium_test_CefriumTestActivity_nativeCreateBrowser(
    JNIEnv* env,
    jclass /* clazz */,
    jstring url_jstr) {
  if (!g_cef_initialized) return;

  const char* url = env->GetStringUTFChars(url_jstr, nullptr);
  LOGI("Creating browser with URL: %s", url);

  CefWindowInfo window_info;
  window_info.SetAsWindowless(0);

  CefBrowserSettings browser_settings;
  CefRefPtr<TestClient> client = new TestClient();

  CefBrowserHost::CreateBrowser(window_info, client, CefString(url),
                                browser_settings, nullptr, nullptr);
  LOGI("CreateBrowser called (async)");

  env->ReleaseStringUTFChars(url_jstr, url);
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_org_cefrium_test_CefriumTestActivity_nativeDoMessageLoopWork(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  if (g_cef_initialized) {
    CefDoMessageLoopWork();
  }
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_org_cefrium_test_CefriumTestActivity_nativeShutdownCef(
    JNIEnv* /* env */,
    jobject /* this */) {
  if (g_cef_initialized) {
    LOGI("Calling CefShutdown...");
    CefShutdown();
    g_cef_initialized = false;
    LOGI("CefShutdown complete");
  }
}
