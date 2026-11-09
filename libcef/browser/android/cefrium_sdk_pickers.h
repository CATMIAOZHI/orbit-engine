// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// Issue #16: the pickers of an off-screen browser (select, date/time,
// colour), shown through the app.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_PICKERS_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_PICKERS_H_

#include <cinttypes>
#include <fstream>
#include <iterator>
#include <jni.h>
#include <map>
#include <optional>
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
#include "base/functional/callback.h"
#include "base/memory/weak_ptr.h"
#include "base/no_destructor.h"
#include "base/strings/string_util.h"
#include "base/synchronization/lock.h"
#include "cef/include/cef_request_handler.h"
#include "cef/include/cef_task.h"
#include "cef/include/wrapper/cef_closure_task.h"
#include "cef/include/wrapper/cef_message_router.h"
#include "cef/libcef/browser/android/cefrium_bridge_constants.h"
#include "cef/libcef/browser/android/cefrium_profiles.h"
#include "cef/libcef/browser/android/cefrium_sdk_devtools.h"
#include "cef/libcef/browser/android/cefrium_sdk_identity.h"
#include "cef/libcef/browser/android/cefrium_sdk_internal.h"
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
#include "url/gurl.h"
#include "cef/libcef/browser/request_context_impl.h"
#include "chrome/browser/android/devtools_server.h"
#include "chrome/browser/profiles/profile.h"
#include "url/origin.h"
#include "cef/libcef/browser/android/cefrium_sdk_client.h"

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

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_PICKERS_H_
