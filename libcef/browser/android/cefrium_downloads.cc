// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// The downloads of a CefriumProfile: every download of its DownloadManager,
// including the ones Chromium restored from the profile's history, and
// updates for them whether or not a browser is still open. CEF's
// CefDownloadHandler only sees downloads it can map to a live browser.

#include <jni.h>

#include <algorithm>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/functional/bind.h"
#include "base/functional/callback.h"
#include "base/no_destructor.h"
#include "base/scoped_observation.h"
#include "cef/libcef/browser/android/cefrium_profiles.h"
#include "cef/libcef/browser/request_context_impl.h"
#include "chrome/browser/download/download_core_service.h"
#include "chrome/browser/download/download_core_service_factory.h"
#include "chrome/browser/profiles/profile.h"
#include "components/download/public/common/download_item.h"
#include "content/public/browser/download_manager.h"

namespace {

// What Java gets for one download, in the order CefriumProfile.onDownloads
// reads it: 8 numbers and 4 strings.
void Pack(download::DownloadItem* item, std::vector<jlong>* numbers,
          std::vector<std::string>* strings) {
  numbers->push_back(item->GetState());
  numbers->push_back(item->GetLastReason());
  numbers->push_back(item->GetReceivedBytes());
  numbers->push_back(item->GetTotalBytes());
  numbers->push_back(item->GetStartTime().InMillisecondsSinceUnixEpoch());
  numbers->push_back(item->GetEndTime().is_null()
                         ? 0
                         : item->GetEndTime().InMillisecondsSinceUnixEpoch());
  numbers->push_back(item->IsPaused());
  numbers->push_back(item->CanResume());
  strings->push_back(item->GetGuid());
  strings->push_back(item->GetURL().spec());
  strings->push_back(item->GetTargetFilePath().value());
  strings->push_back(item->GetMimeType());
}

// Calls CefriumProfile.onDownloads(requestId, incognito, profile, numbers,
// strings): requestId 0 is an update from the observer, any other the answer
// to a getDownloads request.
void Deliver(jlong request_id, bool incognito, const std::string& profile,
             const std::vector<download::DownloadItem*>& items) {
  std::vector<jlong> numbers;
  std::vector<std::string> strings;
  for (download::DownloadItem* item : items) {
    Pack(item, &numbers, &strings);
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  jclass clazz = env->FindClass("com/cefrium/CefriumProfile");
  if (!clazz) {
    env->ExceptionClear();
    return;
  }
  jmethodID method = env->GetStaticMethodID(
      clazz, "onDownloads", "(JZLjava/lang/String;[J[Ljava/lang/String;)V");
  if (!method) {
    env->ExceptionClear();
    env->DeleteLocalRef(clazz);
    return;
  }
  jstring j_profile = env->NewStringUTF(profile.c_str());
  jlongArray j_numbers = env->NewLongArray(static_cast<jsize>(numbers.size()));
  env->SetLongArrayRegion(j_numbers, 0, static_cast<jsize>(numbers.size()),
                          numbers.data());
  jobjectArray j_strings =
      env->NewObjectArray(static_cast<jsize>(strings.size()),
                          env->FindClass("java/lang/String"), nullptr);
  for (size_t i = 0; i < strings.size(); ++i) {
    jstring s = env->NewStringUTF(strings[i].c_str());
    env->SetObjectArrayElement(j_strings, static_cast<jsize>(i), s);
    env->DeleteLocalRef(s);
  }
  env->CallStaticVoidMethod(clazz, method, request_id,
                            static_cast<jboolean>(incognito), j_profile,
                            j_numbers, j_strings);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  env->DeleteLocalRef(j_strings);
  env->DeleteLocalRef(j_numbers);
  env->DeleteLocalRef(j_profile);
  env->DeleteLocalRef(clazz);
}

// One per profile that Java has asked about. Watches the profile's
// DownloadManager and every download in it, holds getDownloads requests until
// the manager has loaded the history, and forwards updates once Java observes.
class ProfileDownloads : public content::DownloadManager::Observer,
                         public download::DownloadItem::Observer {
 public:
  static ProfileDownloads* For(bool incognito, const std::string& name) {
    auto key = std::make_pair(incognito, name);
    auto& all = All();
    auto it = all.find(key);
    if (it != all.end()) {
      return it->second.get();
    }
    CefRefPtr<CefRequestContext> context =
        cefrium::ContextForProfile(incognito, name);
    Profile* profile =
        context ? CefRequestContextImpl::GetProfile(context) : nullptr;
    if (!profile) {
      return nullptr;
    }
    // Loads the profile's download history into its manager, as Android's
    // download list does: with kDeferredDownloadHistoryLoading the history is
    // otherwise only read once a download starts.
    DownloadCoreService* service =
        DownloadCoreServiceFactory::GetForBrowserContext(profile);
    service->InitializeHistory(DownloadCoreService::DownloadHistoryLoadTrigger::
                                   kOfflineContentProvider);
    auto owned = std::make_unique<ProfileDownloads>(
        incognito, name, profile->GetDownloadManager());
    ProfileDownloads* raw = owned.get();
    all[key] = std::move(owned);
    return raw;
  }

  ProfileDownloads(bool incognito,
                   std::string name,
                   content::DownloadManager* manager)
      : incognito_(incognito), name_(std::move(name)), manager_(manager) {
    manager_observation_.Observe(manager_);
    std::vector<raw_ptr<download::DownloadItem, VectorExperimental>> items;
    manager_->GetAllDownloads(&items);
    for (download::DownloadItem* item : items) {
      item->AddObserver(this);
    }
  }

  ~ProfileDownloads() override { StopObservingItems(); }

  void List(jlong request_id) {
    if (!manager_->IsManagerInitialized()) {
      pending_.push_back(request_id);
      return;
    }
    Deliver(request_id, incognito_, name_, Items());
  }

  void SetObserving(bool observing) { observing_ = observing; }

  download::DownloadItem* Get(const std::string& guid) {
    return manager_->GetDownloadByGuid(guid);
  }

 private:
  using Key = std::pair<bool, std::string>;
  static std::map<Key, std::unique_ptr<ProfileDownloads>>& All() {
    static base::NoDestructor<std::map<Key, std::unique_ptr<ProfileDownloads>>>
        all;
    return *all;
  }

  std::vector<download::DownloadItem*> Items() {
    std::vector<raw_ptr<download::DownloadItem, VectorExperimental>> items;
    manager_->GetAllDownloads(&items);
    std::vector<download::DownloadItem*> out;
    for (download::DownloadItem* item : items) {
      if (!item->IsTemporary() && !item->IsTransient()) {
        out.push_back(item);
      }
    }
    std::stable_sort(out.begin(), out.end(), [](auto* a, auto* b) {
      return a->GetStartTime() < b->GetStartTime();
    });
    return out;
  }

  void Notify(download::DownloadItem* item) {
    if (observing_ && !item->IsTemporary() && !item->IsTransient()) {
      Deliver(0, incognito_, name_, {item});
    }
  }

  void StopObservingItems() {
    if (!manager_) {
      return;
    }
    std::vector<raw_ptr<download::DownloadItem, VectorExperimental>> items;
    manager_->GetAllDownloads(&items);
    for (download::DownloadItem* item : items) {
      item->RemoveObserver(this);
    }
  }

  // content::DownloadManager::Observer
  void OnDownloadCreated(content::DownloadManager* manager,
                         download::DownloadItem* item) override {
    item->AddObserver(this);
    Notify(item);
  }

  void OnManagerInitialized() override {
    std::vector<jlong> pending;
    pending.swap(pending_);
    for (jlong request_id : pending) {
      Deliver(request_id, incognito_, name_, Items());
    }
  }

  void ManagerGoingDown(content::DownloadManager* manager) override {
    StopObservingItems();
    manager_observation_.Reset();
    manager_ = nullptr;
    All().erase(Key(incognito_, name_));  // Deletes this; return at once.
  }

  // download::DownloadItem::Observer
  void OnDownloadUpdated(download::DownloadItem* item) override {
    Notify(item);
  }

  void OnDownloadDestroyed(download::DownloadItem* item) override {
    item->RemoveObserver(this);
  }

  const bool incognito_;
  const std::string name_;
  raw_ptr<content::DownloadManager> manager_;
  base::ScopedObservation<content::DownloadManager,
                          content::DownloadManager::Observer>
      manager_observation_{this};
  std::vector<jlong> pending_;
  bool observing_ = false;
};

ProfileDownloads* ForJava(JNIEnv* env, jboolean incognito, jstring profile) {
  return ProfileDownloads::For(
      incognito == JNI_TRUE,
      base::android::ConvertJavaStringToUTF8(env, profile));
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumProfile_nativeGetDownloads(JNIEnv* env,
                                                   jclass clazz,
                                                   jboolean incognito,
                                                   jstring profile,
                                                   jlong request_id) {
  if (ProfileDownloads* downloads = ForJava(env, incognito, profile)) {
    downloads->List(request_id);
  } else {
    Deliver(request_id, incognito == JNI_TRUE,
            base::android::ConvertJavaStringToUTF8(env, profile), {});
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_CefriumProfile_nativeObserveDownloads(JNIEnv* env,
                                                       jclass clazz,
                                                       jboolean incognito,
                                                       jstring profile,
                                                       jboolean observe) {
  ProfileDownloads* downloads = ForJava(env, incognito, profile);
  if (!downloads) {
    return JNI_FALSE;
  }
  downloads->SetObserving(observe == JNI_TRUE);
  return JNI_TRUE;
}

// action: 0 cancel, 1 pause, 2 resume, 3 remove from the list.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_CefriumProfile_nativeControlDownload(JNIEnv* env,
                                                      jclass clazz,
                                                      jboolean incognito,
                                                      jstring profile,
                                                      jstring guid,
                                                      jint action) {
  ProfileDownloads* downloads = ForJava(env, incognito, profile);
  download::DownloadItem* item =
      downloads
          ? downloads->Get(base::android::ConvertJavaStringToUTF8(env, guid))
          : nullptr;
  if (!item) {
    return JNI_FALSE;
  }
  switch (action) {
    case 0:
      item->Cancel(/*user_cancel=*/true);
      return JNI_TRUE;
    case 1:
      item->Pause();
      return JNI_TRUE;
    case 2:
      if (!item->CanResume()) {
        return JNI_FALSE;
      }
      item->Resume(/*user_resume=*/true);
      return JNI_TRUE;
    case 3:
      item->Remove();
      return JNI_TRUE;
  }
  return JNI_FALSE;
}
