// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

#include "cef/libcef/browser/android/cefrium_sdk_dialogs.h"

#include "cef/libcef/browser/android/cefrium_sdk_client.h"

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
  if (!data) {
    return;
  }
  cefrium_sdk::RunWhenCreated(
      data, base::BindOnce(
                [](std::string url, int permission, int value,
                   CefRefPtr<CefBrowser> browser) {
                  CefRefPtr<CefRequestContext> context =
                      browser->GetHost()->GetRequestContext();
                  if (!context) {
                    return;
                  }
                  context->SetContentSetting(
                      CefString(url), CefString(),
                      SdkPermissionToContentType(permission),
                      SdkIntToSettingValue(value));
                },
                cefrium_sdk::JStr(env, url_jstr).ToString(), static_cast<int>(permission),
                static_cast<int>(value)));
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

// FEATURE-028: Java resolves a JS dialog (alert/confirm/prompt).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeJsDialogResult(
    JNIEnv* env, jclass, jlong native_ptr, jlong token, jboolean success,
    jstring input_j) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client && data->client->js_dialog_handler()) {
    data->client->js_dialog_handler()->Resolve(token, success == JNI_TRUE,
                                               cefrium_sdk::JStr(env, input_j));
  }
}
