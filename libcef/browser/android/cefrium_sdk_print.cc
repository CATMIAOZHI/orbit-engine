// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// Printing: PrintToPDF (FEATURE-031) and window.print() (FEATURE-087).

#include "cef/libcef/browser/android/cefrium_sdk_client.h"
#include "cef/libcef/browser/browser_host_base.h"
#include "chrome/browser/printing/print_view_manager_basic.h"

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

// window.print() of a CefriumBrowser: CefriumBrowser.onScriptedPrint decides
// (the app's PrintHandler, or the system print dialog). False when the page is
// not in a CefriumBrowser, or Java did not take it.
bool OnScriptedPrint(content::WebContents* web_contents) {
  JNIEnv* env = base::android::AttachCurrentThread();
  jobject java_browser = nullptr;
  {
    base::AutoLock lock(cefrium_sdk::BridgeDataLock());
    for (jlong ptr : cefrium_sdk::LiveBridgeData()) {
      auto* data = reinterpret_cast<SdkBrowserData*>(ptr);
      if (!data->browser || !data->life_span_handler) {
        continue;
      }
      auto* host =
          static_cast<CefBrowserHostBase*>(data->browser->GetHost().get());
      if (host && host->GetWebContents() == web_contents) {
        java_browser = data->life_span_handler->java_browser();
        break;
      }
    }
  }
  if (!java_browser || !env) {
    return false;
  }
  jclass cls = env->GetObjectClass(java_browser);
  jmethodID mid = env->GetMethodID(cls, "onScriptedPrint", "()Z");
  bool handled = false;
  if (mid) {
    handled = env->CallBooleanMethod(java_browser, mid);
  } else {
    env->ExceptionClear();
  }
  env->DeleteLocalRef(cls);
  return handled;
}

namespace cefrium_sdk {

void RegisterScriptedPrint() {
  printing::PrintViewManagerBasic::SetEmbedderScriptedPrintFunction(
      &OnScriptedPrint);
}

}  // namespace cefrium_sdk

// The page at |paper_width| x |paper_height| inches (portrait sizes; 0 = the
// default, Letter), landscape if asked.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativePrintToPdf(
    JNIEnv* env, jclass, jlong native_ptr, jobject browser_j, jstring path_j,
    jlong req_id, jdouble paper_width, jdouble paper_height,
    jboolean landscape) {
  jobject global = env->NewGlobalRef(browser_j);
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  CefRefPtr<SdkPdfCallback> cb = new SdkPdfCallback(global, req_id);
  if (!data || !data->browser || !data->browser->GetHost()) {
    cb->OnPdfPrintFinished(CefString(), false);  // deliver failure
    return;
  }
  CefPdfPrintSettings settings;  // zero-initialized defaults
  settings.paper_width = paper_width;
  settings.paper_height = paper_height;
  settings.landscape = landscape == JNI_TRUE;
  data->browser->GetHost()->PrintToPDF(cefrium_sdk::JStr(env, path_j), settings, cb);
}
