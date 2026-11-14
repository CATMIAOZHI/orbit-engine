// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// What a browser and a profile store: cookies (FEATURE-024), the HTTP cache
// (FEATURE-030) and per-site data (FEATURE-071).

#include <jni.h>

#include <string>
#include <vector>

#include "base/android/jni_android.h"
#include "base/android/jni_array.h"
#include "base/android/jni_string.h"
#include "base/barrier_closure.h"
#include "base/time/time.h"
#include "cef/include/cef_cookie.h"
#include "cef/include/cef_request_context.h"
#include "cef/include/cef_task.h"
#include "cef/include/wrapper/cef_closure_task.h"
#include "cef/libcef/browser/android/cefrium_profiles.h"
#include "cef/libcef/browser/android/cefrium_sdk_internal.h"
#include "cef/libcef/browser/request_context_impl.h"
#include "cef/libcef/browser/thread_util.h"
#include "chrome/browser/browsing_data/chrome_browsing_data_model_delegate.h"
#include "chrome/browser/profiles/profile.h"
#include "components/browsing_data/content/browsing_data_model.h"
#include "content/public/browser/browser_context.h"
#include "third_party/abseil-cpp/absl/functional/overload.h"
#include "url/origin.h"

using cefrium_sdk::JStr;

// Cookies (FEATURE-024).

namespace {

// Cookie manager for a browser's own request context (per-browser store).
CefRefPtr<CefCookieManager> CookieManagerFor(jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser || !data->browser->GetHost()) {
    return nullptr;
  }
  CefRefPtr<CefRequestContext> ctx =
      data->browser->GetHost()->GetRequestContext();
  return ctx ? ctx->GetCookieManager(nullptr) : nullptr;
}

// Delivers visited cookies to Java CefriumBrowser.onCookiesVisited (TID_UI).
// Owns the global ref; releases it after the call.
void DeliverCookiesOnUI(jobject java_browser,
                        jlong req_id,
                        std::vector<std::string> names,
                        std::vector<std::string> values,
                        std::vector<std::string> domains,
                        std::vector<std::string> paths,
                        std::vector<uint8_t> secure,
                        std::vector<uint8_t> httponly) {
  JNIEnv* env = base::android::AttachCurrentThread();
  const int n = static_cast<int>(names.size());
  jclass str_cls = env->FindClass("java/lang/String");
  auto mk = [&](const std::vector<std::string>& v) -> jobjectArray {
    jobjectArray a = env->NewObjectArray(n, str_cls, nullptr);
    for (int i = 0; i < n; ++i) {
      jstring s = env->NewStringUTF(v[i].c_str());
      env->SetObjectArrayElement(a, i, s);
      env->DeleteLocalRef(s);
    }
    return a;
  };
  jobjectArray jn = mk(names), jv = mk(values), jd = mk(domains), jp = mk(paths);
  jbooleanArray js = env->NewBooleanArray(n), jh = env->NewBooleanArray(n);
  if (n > 0) {
    env->SetBooleanArrayRegion(js, 0, n,
        reinterpret_cast<const jboolean*>(secure.data()));
    env->SetBooleanArrayRegion(jh, 0, n,
        reinterpret_cast<const jboolean*>(httponly.data()));
  }
  jclass cls = env->GetObjectClass(java_browser);
  jmethodID m = env->GetMethodID(cls, "onCookiesVisited",
      "(J[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;"
      "[Ljava/lang/String;[Z[Z)V");
  if (m) {
    env->CallVoidMethod(java_browser, m, req_id, jn, jv, jd, jp, js, jh);
  }
  env->DeleteGlobalRef(java_browser);
}

// Accumulates cookies during a visit and delivers them when CEF releases it.
class SdkCookieVisitor : public CefCookieVisitor {
 public:
  SdkCookieVisitor(jobject java_browser_global, jlong req_id)
      : java_browser_(java_browser_global), req_id_(req_id) {}

  bool Visit(const CefCookie& cookie, int, int, bool&) override {
    names_.push_back(CefString(&cookie.name).ToString());
    values_.push_back(CefString(&cookie.value).ToString());
    domains_.push_back(CefString(&cookie.domain).ToString());
    paths_.push_back(CefString(&cookie.path).ToString());
    secure_.push_back(cookie.secure ? 1 : 0);
    httponly_.push_back(cookie.httponly ? 1 : 0);
    return true;
  }

  ~SdkCookieVisitor() override {
    CefPostTask(TID_UI,
                base::BindOnce(&DeliverCookiesOnUI, java_browser_, req_id_,
                               std::move(names_), std::move(values_),
                               std::move(domains_), std::move(paths_),
                               std::move(secure_), std::move(httponly_)));
  }

 private:
  jobject java_browser_;
  jlong req_id_;
  std::vector<std::string> names_, values_, domains_, paths_;
  std::vector<uint8_t> secure_, httponly_;
  IMPLEMENT_REFCOUNTING(SdkCookieVisitor);
};

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetCookie(
    JNIEnv* env, jclass, jlong native_ptr, jstring url_j, jstring name_j,
    jstring value_j, jboolean secure, jboolean http_only, jint same_site) {
  CefRefPtr<CefCookieManager> mgr = CookieManagerFor(native_ptr);
  if (!mgr) return;
  CefString url = JStr(env, url_j);
  CefCookie cookie;  // zero-initialized (CefStructBase)
  CefString(&cookie.name) = JStr(env, name_j);
  CefString(&cookie.value) = JStr(env, value_j);
  cookie.has_expires = false;  // session cookie; domain/path inferred from url
  cookie.secure = secure ? 1 : 0;
  cookie.httponly = http_only ? 1 : 0;
  // same_site matches CefriumBrowser.SAME_SITE_* == cef_cookie_same_site_t
  // (0 unspecified, 1 no-restriction/None, 2 Lax, 3 Strict). None requires Secure.
  cookie.same_site = static_cast<cef_cookie_same_site_t>(same_site);
  mgr->SetCookie(url, cookie, nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeGetCookies(
    JNIEnv* env, jclass, jlong native_ptr, jobject browser_j, jstring url_j,
    jlong req_id) {
  jobject global = env->NewGlobalRef(browser_j);
  CefRefPtr<CefCookieManager> mgr = CookieManagerFor(native_ptr);
  if (!mgr) {
    // Deliver an empty result so the Java callback is invoked and removed.
    CefPostTask(TID_UI, base::BindOnce(&DeliverCookiesOnUI, global, req_id,
                                       std::vector<std::string>(),
                                       std::vector<std::string>(),
                                       std::vector<std::string>(),
                                       std::vector<std::string>(),
                                       std::vector<uint8_t>(),
                                       std::vector<uint8_t>()));
    return;
  }
  CefRefPtr<SdkCookieVisitor> visitor = new SdkCookieVisitor(global, req_id);
  mgr->VisitUrlCookies(JStr(env, url_j), /*includeHttpOnly=*/true, visitor);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeDeleteCookies(
    JNIEnv* env, jclass, jlong native_ptr, jstring url_j, jstring name_j) {
  CefRefPtr<CefCookieManager> mgr = CookieManagerFor(native_ptr);
  if (mgr) mgr->DeleteCookies(JStr(env, url_j), JStr(env, name_j), nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeFlushCookies(
    JNIEnv* env, jclass, jlong native_ptr) {
  CefRefPtr<CefCookieManager> mgr = CookieManagerFor(native_ptr);
  if (mgr) mgr->FlushStore(nullptr);
}

// FEATURE-030: clear the HTTP cache via the DevTools protocol.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeClearCache(
    JNIEnv* env, jclass, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser && data->browser->GetHost()) {
    data->browser->GetHost()->ExecuteDevToolsMethod(
        1, "Network.clearBrowserCache", nullptr);
  }
}

// FEATURE-071: per-site data of a profile, through Chromium's
// BrowsingDataModel (what Chrome's per-site storage screen uses). Listing
// reports each data owner (host or origin) with its storage size and cookie
// count; clearing removes everything the model knows for that owner. The HTTP
// cache is not attributed per site by Chromium, so it is in neither.
namespace {

// The context for (incognito, profile): null when incognito has no open
// browser, i.e. no data.
CefRefPtr<CefRequestContext> SiteDataContext(JNIEnv* env, jboolean incognito,
                                             jstring profile) {
  return cefrium::ContextForProfile(incognito == JNI_TRUE,
                                    JStr(env, profile).ToString());
}

std::string OwnerString(const BrowsingDataModel::DataOwner& owner) {
  return std::visit(
      absl::Overload{[](const std::string& host) { return host; },
                     [](const url::Origin& origin) { return origin.Serialize(); }},
      owner);
}

void BuildSiteDataModel(
    CefRefPtr<CefRequestContext> context,
    base::OnceCallback<void(std::unique_ptr<BrowsingDataModel>)> done) {
  Profile* profile = CefRequestContextImpl::GetProfile(context);
  BrowsingDataModel::BuildFromDisk(
      profile, ChromeBrowsingDataModelDelegate::CreateForProfile(profile),
      std::move(done));
}

// Calls CefriumProfile.onSiteDataResult(requestId, owners, bytes, cookies) on
// the UI thread; owners is null when the request failed.
void DeliverSiteData(jclass clazz, jlong request_id,
                     const std::vector<std::string>* owners,
                     const std::vector<int64_t>* bytes,
                     const std::vector<int64_t>* cookies) {
  JNIEnv* env = base::android::AttachCurrentThread();
  jmethodID method = env->GetStaticMethodID(clazz, "onSiteDataResult",
                                            "(J[Ljava/lang/String;[J[J)V");
  if (!method) {
    env->ExceptionClear();
    env->DeleteGlobalRef(clazz);
    return;
  }
  jobjectArray j_owners = nullptr;
  jlongArray j_bytes = nullptr;
  jlongArray j_cookies = nullptr;
  if (owners) {
    const jsize n = static_cast<jsize>(owners->size());
    j_owners = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    j_bytes = env->NewLongArray(n);
    j_cookies = env->NewLongArray(n);
    for (jsize i = 0; i < n; ++i) {
      jstring o = env->NewStringUTF((*owners)[i].c_str());
      env->SetObjectArrayElement(j_owners, i, o);
      env->DeleteLocalRef(o);
    }
    env->SetLongArrayRegion(j_bytes, 0, n,
                            reinterpret_cast<const jlong*>(bytes->data()));
    env->SetLongArrayRegion(j_cookies, 0, n,
                            reinterpret_cast<const jlong*>(cookies->data()));
  }
  env->CallStaticVoidMethod(clazz, method, request_id, j_owners, j_bytes,
                            j_cookies);
  if (j_owners) env->DeleteLocalRef(j_owners);
  if (j_bytes) env->DeleteLocalRef(j_bytes);
  if (j_cookies) env->DeleteLocalRef(j_cookies);
  env->DeleteGlobalRef(clazz);
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumProfile_nativeListSiteData(
    JNIEnv* env, jclass clazz, jboolean incognito, jstring profile,
    jlong request_id) {
  jclass global = static_cast<jclass>(env->NewGlobalRef(clazz));
  CefRefPtr<CefRequestContext> context =
      SiteDataContext(env, incognito, profile);
  if (!context) {
    std::vector<std::string> none;
    std::vector<int64_t> zero;
    DeliverSiteData(global, request_id, &none, &zero, &zero);
    return;
  }
  BuildSiteDataModel(
      context,
      base::BindOnce(
          [](jclass clazz, jlong request_id,
             std::unique_ptr<BrowsingDataModel> model) {
            // One row per owner: a model entry is per (owner, storage key).
            std::map<std::string, std::pair<int64_t, int64_t>> rows;
            for (const auto& entry : *model) {
              auto& row = rows[OwnerString(*entry.data_owner)];
              row.first += entry.data_details->storage_size;
              row.second += entry.data_details->cookie_count;
            }
            std::vector<std::string> owners;
            std::vector<int64_t> bytes;
            std::vector<int64_t> cookies;
            for (const auto& [owner, sizes] : rows) {
              owners.push_back(owner);
              bytes.push_back(sizes.first);
              cookies.push_back(sizes.second);
            }
            DeliverSiteData(clazz, request_id, &owners, &bytes, &cookies);
          },
          global, request_id));
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumProfile_nativeClearSiteData(
    JNIEnv* env, jclass clazz, jboolean incognito, jstring profile,
    jstring site, jlong request_id) {
  jclass global = static_cast<jclass>(env->NewGlobalRef(clazz));
  CefRefPtr<CefRequestContext> context =
      SiteDataContext(env, incognito, profile);
  const std::string wanted = JStr(env, site).ToString();
  if (!context) {
    DeliverSiteData(global, request_id, nullptr, nullptr, nullptr);
    return;
  }
  BuildSiteDataModel(
      context,
      base::BindOnce(
          [](jclass clazz, jlong request_id, std::string wanted,
             std::unique_ptr<BrowsingDataModel> model) {
            // The site as listed (host or origin), or an origin URL, which
            // also matches the entries Chromium groups under its host.
            const GURL url(wanted);
            const std::string host =
                url.is_valid() ? std::string(url.host()) : std::string();
            std::vector<BrowsingDataModel::DataOwner> matches;
            std::set<std::string> seen;
            for (const auto& entry : *model) {
              const std::string owner = OwnerString(*entry.data_owner);
              if ((owner == wanted || (!host.empty() &&
                                       (owner == host ||
                                        owner == url::Origin::Create(url).Serialize()))) &&
                  seen.insert(owner).second) {
                matches.push_back(*entry.data_owner);
              }
            }
            BrowsingDataModel* raw = model.get();
            auto finish = base::BindOnce(
                [](jclass clazz, jlong request_id,
                   std::unique_ptr<BrowsingDataModel>, size_t count) {
                  std::vector<std::string> cleared(count);
                  std::vector<int64_t> zero(count, 0);
                  DeliverSiteData(clazz, request_id, &cleared, &zero, &zero);
                },
                clazz, request_id, std::move(model), matches.size());
            if (matches.empty()) {
              std::move(finish).Run();
              return;
            }
            base::RepeatingClosure barrier =
                base::BarrierClosure(matches.size(), std::move(finish));
            for (const auto& owner : matches) {
              raw->RemoveBrowsingData(owner, barrier);
            }
          },
          global, request_id, wanted));
}

// FEATURE-092: CefriumCookieManager, the cookies of a profile (its request
// context), as android.webkit.CookieManager but asynchronous and per
// profile. Results go to CefriumCookieManager's static on*() methods on the
// UI thread, by request id. Expiry crosses JNI as Unix milliseconds (0 =
// session) and SameSite as a CefriumCookie.SameSite ordinal, mapped by name.
namespace {

CefRefPtr<CefCookieManager> ProfileCookies(JNIEnv* env,
                                           jboolean incognito,
                                           jstring profile) {
  CefRefPtr<CefRequestContext> context = cefrium::ContextForProfile(
      incognito == JNI_TRUE, JStr(env, profile).ToString());
  return context ? context->GetCookieManager(nullptr) : nullptr;
}

int64_t UnixMillisOf(const cef_basetime_t& t) {
  return base::Time(CefBaseTime(t)).InMillisecondsSinceUnixEpoch();
}

int SameSiteOrdinal(cef_cookie_same_site_t s) {
  switch (s) {
    case CEF_COOKIE_SAME_SITE_NO_RESTRICTION:
      return 1;
    case CEF_COOKIE_SAME_SITE_LAX_MODE:
      return 2;
    case CEF_COOKIE_SAME_SITE_STRICT_MODE:
      return 3;
    case CEF_COOKIE_SAME_SITE_UNSPECIFIED:
    case CEF_COOKIE_SAME_SITE_NUM_VALUES:
      return 0;
  }
  return 0;
}

cef_cookie_same_site_t SameSiteOf(jint ordinal) {
  switch (ordinal) {
    case 1:
      return CEF_COOKIE_SAME_SITE_NO_RESTRICTION;
    case 2:
      return CEF_COOKIE_SAME_SITE_LAX_MODE;
    case 3:
      return CEF_COOKIE_SAME_SITE_STRICT_MODE;
    default:
      return CEF_COOKIE_SAME_SITE_UNSPECIFIED;
  }
}

// Calls a static void method of the CefriumCookieManager class |clazz|
// (global ref, released here) with a request id and one more argument.
void CallStatic(jclass clazz, const char* name, const char* sig, jlong id,
                jlong arg) {
  JNIEnv* env = base::android::AttachCurrentThread();
  jmethodID m = env->GetStaticMethodID(clazz, name, sig);
  if (m) {
    if (sig[2] == 'Z') {  // "(JZ)V"; otherwise "(JI)V"
      env->CallStaticVoidMethod(clazz, m, id, static_cast<jboolean>(arg != 0));
    } else {
      env->CallStaticVoidMethod(clazz, m, id, static_cast<jint>(arg));
    }
  }
  env->ExceptionClear();
  env->DeleteGlobalRef(clazz);
}

class ProfileSetCookieCallback : public CefSetCookieCallback {
 public:
  ProfileSetCookieCallback(jclass clazz, jlong id) : clazz_(clazz), id_(id) {}
  void OnComplete(bool success) override {
    CallStatic(clazz_, "onSetCookieDone", "(JZ)V", id_, success ? 1 : 0);
  }

 private:
  jclass clazz_;
  jlong id_;
  IMPLEMENT_REFCOUNTING(ProfileSetCookieCallback);
};

class ProfileDeleteCookiesCallback : public CefDeleteCookiesCallback {
 public:
  ProfileDeleteCookiesCallback(jclass clazz, jlong id)
      : clazz_(clazz), id_(id) {}
  void OnComplete(int num_deleted) override {
    CallStatic(clazz_, "onCookiesDeleted", "(JI)V", id_, num_deleted);
  }

 private:
  jclass clazz_;
  jlong id_;
  IMPLEMENT_REFCOUNTING(ProfileDeleteCookiesCallback);
};

class ProfileFlushCallback : public CefCompletionCallback {
 public:
  ProfileFlushCallback(jclass clazz, jlong id) : clazz_(clazz), id_(id) {}
  void OnComplete() override {
    CallStatic(clazz_, "onFlushed", "(JZ)V", id_, 1);
  }

 private:
  jclass clazz_;
  jlong id_;
  IMPLEMENT_REFCOUNTING(ProfileFlushCallback);
};

struct ProfileCookieRows {
  std::vector<std::string> names, values, domains, paths;
  std::vector<jboolean> secure, httponly;
  std::vector<jlong> expires;
  std::vector<jint> same_site;
};

void DeliverProfileCookies(jclass clazz, jlong id, ProfileCookieRows rows) {
  JNIEnv* env = base::android::AttachCurrentThread();
  const jsize n = static_cast<jsize>(rows.names.size());
  auto strings = [env](const std::vector<std::string>& v) {
    return base::android::ToJavaArrayOfStrings(env, v);
  };
  auto jn = strings(rows.names), jv = strings(rows.values),
       jd = strings(rows.domains), jp = strings(rows.paths);
  jbooleanArray js = env->NewBooleanArray(n), jh = env->NewBooleanArray(n);
  jlongArray je = env->NewLongArray(n);
  jintArray jss = env->NewIntArray(n);
  if (n > 0) {
    env->SetBooleanArrayRegion(js, 0, n, rows.secure.data());
    env->SetBooleanArrayRegion(jh, 0, n, rows.httponly.data());
    env->SetLongArrayRegion(je, 0, n, rows.expires.data());
    env->SetIntArrayRegion(jss, 0, n, rows.same_site.data());
  }
  jmethodID m = env->GetStaticMethodID(
      clazz, "onCookies",
      "(J[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;"
      "[Ljava/lang/String;[Z[Z[J[I)V");
  if (m) {
    env->CallStaticVoidMethod(clazz, m, id, jn.obj(), jv.obj(), jd.obj(),
                              jp.obj(), js, jh, je, jss);
  }
  env->ExceptionClear();
  env->DeleteLocalRef(js);
  env->DeleteLocalRef(jh);
  env->DeleteLocalRef(je);
  env->DeleteLocalRef(jss);
  env->DeleteGlobalRef(clazz);
}

class ProfileCookieVisitor : public CefCookieVisitor {
 public:
  ProfileCookieVisitor(jclass clazz, jlong id) : clazz_(clazz), id_(id) {}
  bool Visit(const CefCookie& c, int, int, bool&) override {
    rows_.names.push_back(CefString(&c.name).ToString());
    rows_.values.push_back(CefString(&c.value).ToString());
    rows_.domains.push_back(CefString(&c.domain).ToString());
    rows_.paths.push_back(CefString(&c.path).ToString());
    rows_.secure.push_back(c.secure ? JNI_TRUE : JNI_FALSE);
    rows_.httponly.push_back(c.httponly ? JNI_TRUE : JNI_FALSE);
    rows_.expires.push_back(c.has_expires ? UnixMillisOf(c.expires) : 0);
    rows_.same_site.push_back(SameSiteOrdinal(c.same_site));
    return true;
  }
  // CEF releases the visitor when the visit is over, also when there was
  // nothing to visit.
  ~ProfileCookieVisitor() override {
    CefPostTask(TID_UI, base::BindOnce(&DeliverProfileCookies, clazz_, id_,
                                       std::move(rows_)));
  }

 private:
  jclass clazz_;
  jlong id_;
  ProfileCookieRows rows_;
  IMPLEMENT_REFCOUNTING(ProfileCookieVisitor);
};

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumCookieManager_nativeSetCookie(
    JNIEnv* env, jclass clazz, jboolean incognito, jstring profile,
    jstring url, jstring name, jstring value, jstring domain, jstring path,
    jboolean secure, jboolean http_only, jint same_site, jlong expires_ms,
    jlong id) {
  jclass global = static_cast<jclass>(env->NewGlobalRef(clazz));
  CefRefPtr<CefCookieManager> mgr = ProfileCookies(env, incognito, profile);
  if (!mgr) {
    CallStatic(global, "onSetCookieDone", "(JZ)V", id, 0);
    return;
  }
  CefCookie cookie;
  CefString(&cookie.name) = JStr(env, name);
  CefString(&cookie.value) = JStr(env, value);
  CefString(&cookie.domain) = JStr(env, domain);
  CefString(&cookie.path) = JStr(env, path);
  cookie.secure = secure ? 1 : 0;
  cookie.httponly = http_only ? 1 : 0;
  cookie.same_site = SameSiteOf(same_site);
  if (expires_ms != 0) {
    cookie.has_expires = 1;
    cookie.expires =
        CefBaseTime(base::Time::FromMillisecondsSinceUnixEpoch(expires_ms));
  }
  if (!mgr->SetCookie(JStr(env, url), cookie,
                      new ProfileSetCookieCallback(global, id))) {
    // Rejected before it was tried (an invalid URL): no callback follows.
    CallStatic(global, "onSetCookieDone", "(JZ)V", id, 0);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumCookieManager_nativeGetCookies(JNIEnv* env,
                                                      jclass clazz,
                                                      jboolean incognito,
                                                      jstring profile,
                                                      jstring url,
                                                      jlong id) {
  jclass global = static_cast<jclass>(env->NewGlobalRef(clazz));
  CefRefPtr<CefCookieManager> mgr = ProfileCookies(env, incognito, profile);
  CefRefPtr<ProfileCookieVisitor> visitor =
      new ProfileCookieVisitor(global, id);
  if (!mgr) {
    return;  // the visitor delivers an empty list when it is released
  }
  CefString u = JStr(env, url);
  if (u.empty()) {
    mgr->VisitAllCookies(visitor);
  } else {
    mgr->VisitUrlCookies(u, /*includeHttpOnly=*/true, visitor);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumCookieManager_nativeDeleteCookies(JNIEnv* env,
                                                         jclass clazz,
                                                         jboolean incognito,
                                                         jstring profile,
                                                         jstring url,
                                                         jstring name,
                                                         jlong id) {
  jclass global = static_cast<jclass>(env->NewGlobalRef(clazz));
  CefRefPtr<CefCookieManager> mgr = ProfileCookies(env, incognito, profile);
  if (!mgr || !mgr->DeleteCookies(JStr(env, url), JStr(env, name),
                                  new ProfileDeleteCookiesCallback(global, id))) {
    CallStatic(global, "onCookiesDeleted", "(JI)V", id, 0);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumCookieManager_nativeFlush(JNIEnv* env,
                                                 jclass clazz,
                                                 jboolean incognito,
                                                 jstring profile,
                                                 jlong id) {
  jclass global = static_cast<jclass>(env->NewGlobalRef(clazz));
  CefRefPtr<CefCookieManager> mgr = ProfileCookies(env, incognito, profile);
  if (!mgr ||
      !mgr->FlushStore(new ProfileFlushCallback(global, id))) {
    CallStatic(global, "onFlushed", "(JZ)V", id, 0);
  }
}
