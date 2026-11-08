// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

#include "cef/libcef/browser/android/cefrium_sdk_devtools.h"

#include <memory>
#include <string>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "base/no_destructor.h"
#include "cef/include/cef_parser.h"
#include "cef/include/cef_values.h"
#include "cef/libcef/browser/android/cefrium_sdk_internal.h"
#include "cef/libcef/browser/thread_util.h"
#include "chrome/browser/android/devtools_server.h"

using cefrium_sdk::JStr;

namespace {

constexpr int kFirstAppDevToolsId = 1000;

}  // namespace

SdkDevToolsObserver::SdkDevToolsObserver(jobject java_browser_global)
    : java_browser_(java_browser_global) {}

SdkDevToolsObserver::~SdkDevToolsObserver() {
  JNIEnv* env = base::android::AttachCurrentThread();
  if (env) {
    env->DeleteGlobalRef(java_browser_);
  }
}

void SdkDevToolsObserver::OnDevToolsMethodResult(CefRefPtr<CefBrowser> browser,
                                                 int message_id,
                                                 bool success,
                                                 const void* result,
                                                 size_t result_size) {
  if (message_id < kFirstAppDevToolsId) {
    return;
  }
  Call("onDevToolsResult", "(IZLjava/lang/String;)V", message_id, success,
       std::string_view(static_cast<const char*>(result), result_size));
}

void SdkDevToolsObserver::OnDevToolsEvent(CefRefPtr<CefBrowser> browser,
                                          const CefString& method,
                                          const void* params,
                                          size_t params_size) {
  if (!events_enabled_) {
    return;
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  jclass cls = env->GetObjectClass(java_browser_);
  jmethodID mid = env->GetMethodID(
      cls, "onDevToolsEvent", "(Ljava/lang/String;Ljava/lang/String;)V");
  if (mid) {
    auto jm = base::android::ConvertUTF8ToJavaString(env, method.ToString());
    auto jp = base::android::ConvertUTF8ToJavaString(
        env, std::string_view(static_cast<const char*>(params), params_size));
    env->CallVoidMethod(java_browser_, mid, jm.obj(), jp.obj());
  }
  env->DeleteLocalRef(cls);
}

void SdkDevToolsObserver::Call(const char* name,
                               const char* signature,
                               int id,
                               bool success,
                               std::string_view json) {
  JNIEnv* env = base::android::AttachCurrentThread();
  jclass cls = env->GetObjectClass(java_browser_);
  jmethodID mid = env->GetMethodID(cls, name, signature);
  if (mid) {
    auto jj = base::android::ConvertUTF8ToJavaString(env, json);
    env->CallVoidMethod(java_browser_, mid, static_cast<jint>(id),
                        success ? JNI_TRUE : JNI_FALSE, jj.obj());
  }
  env->DeleteLocalRef(cls);
}

void RunPendingDevTools(SdkBrowserData* data, CefRefPtr<CefBrowser> browser) {
  auto calls = std::move(data->pending_devtools);
  for (auto& call : calls) {
    std::move(call).Run(browser);
  }
}

namespace {

// The DevTools observer of |data|, registered on first use. Null when the
// browser does not exist (yet). UI thread.
SdkDevToolsObserver* DevToolsObserverFor(JNIEnv* env,
                                         SdkBrowserData* data,
                                         jobject browser_j) {
  if (!CEF_CURRENTLY_ON_UIT() || !data || !data->browser ||
      !data->browser->GetHost()) {
    return nullptr;
  }
  if (!data->devtools_observer) {
    data->devtools_observer =
        new SdkDevToolsObserver(env->NewGlobalRef(browser_j));
    data->devtools_registration =
        data->browser->GetHost()->AddDevToolsMessageObserver(
            data->devtools_observer);
  }
  return data->devtools_observer.get();
}

int ExecuteDevTools(JNIEnv* env,
                    SdkBrowserData* data,
                    jobject browser_j,
                    int id,
                    const std::string& method,
                    CefRefPtr<CefDictionaryValue> params,
                    bool events) {
  SdkDevToolsObserver* observer = DevToolsObserverFor(env, data, browser_j);
  if (!observer) {
    return 1;
  }
  observer->set_events_enabled(events);
  return data->browser->GetHost()->ExecuteDevToolsMethod(id, method, params) ==
                 id
             ? 0
             : 3;
}
}  // namespace

// Returns 0 when submitted (the result then reaches onDevToolsResult), 1 when
// the browser is closed, 2 for params that are not a JSON object, 3 when CEF
// did not accept the call. Before the browser exists the call waits for it; if
// it then fails, onDevToolsResult reports it.
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeExecuteDevToolsMethod(
    JNIEnv* env, jclass, jlong native_ptr, jobject browser_j, jint id,
    jstring method_j, jstring params_j, jboolean events) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !CEF_CURRENTLY_ON_UIT()) {
    return 1;
  }
  CefRefPtr<CefDictionaryValue> params;
  if (params_j) {
    CefRefPtr<CefValue> value =
        CefParseJSON(JStr(env, params_j), JSON_PARSER_RFC);
    if (!value || value->GetType() != VTYPE_DICTIONARY) {
      return 2;
    }
    params = value->GetDictionary();
  }
  const std::string method = JStr(env, method_j).ToString();
  if (data->browser && data->browser->GetHost()) {
    return ExecuteDevTools(env, data, browser_j, id, method, params, events);
  }
  data->pending_devtools.push_back(base::BindOnce(
      [](SdkBrowserData* data,
         base::android::ScopedJavaGlobalRef<jobject> java_browser, int id,
         std::string method, CefRefPtr<CefDictionaryValue> params,
         bool events, CefRefPtr<CefBrowser> browser) {
        JNIEnv* env = base::android::AttachCurrentThread();
        if (ExecuteDevTools(env, data, java_browser.obj(), id, method, params,
                            events) == 0) {
          return;
        }
        jclass cls = env->GetObjectClass(java_browser.obj());
        jmethodID mid = env->GetMethodID(cls, "onDevToolsResult",
                                         "(IZLjava/lang/String;)V");
        if (mid) {
          base::android::ScopedJavaLocalRef<jstring> json =
              base::android::ConvertUTF8ToJavaString(
                  env, "{\"message\":\"the method was not accepted\"}");
          env->CallVoidMethod(java_browser.obj(), mid, id, JNI_FALSE,
                              json.obj());
        } else {
          env->ExceptionClear();
        }
        env->DeleteLocalRef(cls);
      },
      data,
      base::android::ScopedJavaGlobalRef<jobject>(
          env, base::android::ScopedJavaLocalRef<jobject>::Adopt(
                   env, env->NewLocalRef(browser_j))),
      id,
      method, params, events == JNI_TRUE));
  return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetDevToolsEventsEnabled(
    JNIEnv* env, jclass, jlong native_ptr, jobject browser_j,
    jboolean enabled) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (SdkDevToolsObserver* observer =
          DevToolsObserverFor(env, data, browser_j)) {
    observer->set_events_enabled(enabled);
  }
}

// FEATURE-082: Chrome's own remote debugging server on the abstract socket
// <prefix>_devtools_remote, as Chrome and WebView run it. UI thread.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_Cefrium_nativeSetRemoteDebuggingEnabled(JNIEnv* env,
                                                         jclass clazz,
                                                         jboolean enabled,
                                                         jstring prefix_j) {
  static base::NoDestructor<std::unique_ptr<DevToolsServer>> server;
  if (!enabled) {
    if (*server) {
      (*server)->Stop();
    }
    return;
  }
  if (!*server) {
    *server = std::make_unique<DevToolsServer>(JStr(env, prefix_j).ToString());
  }
  (*server)->Start(/*allow_debug_permission=*/false);
}
