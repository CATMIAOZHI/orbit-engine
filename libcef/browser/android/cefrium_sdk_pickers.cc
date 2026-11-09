// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

#include "cef/libcef/browser/android/cefrium_sdk_pickers.h"

#include "cef/libcef/browser/android/cefrium_sdk_client.h"

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
