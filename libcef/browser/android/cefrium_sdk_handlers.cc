// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

#include "cef/libcef/browser/android/cefrium_sdk_handlers.h"

#include "cef/libcef/browser/android/cefrium_sdk_client.h"

std::string& DefaultDownloadDir() {
  static base::NoDestructor<std::string> dir;
  return *dir;
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

// Context menu command — called from Java when user selects an option.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeContextMenuCommand(
    JNIEnv* env, jclass clazz, jlong native_ptr, jint command_id) {
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
