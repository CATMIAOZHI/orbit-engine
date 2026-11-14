// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// JNI bridge for Cefrium SDK Java API (com.cefrium.*).
// Implements native methods declared in Cefrium.java and CefriumBrowser.java.

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
#include "cef/libcef/browser/android/cefrium_sdk_pickers.h"
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


using cefrium_sdk::BridgeDataLock;
using cefrium_sdk::JStr;
using cefrium_sdk::LiveBridgeData;
using cefrium_sdk::RunWhenCreated;

namespace cefrium_sdk {

// base::NoDestructor avoids exit-time destructors (Chromium global-object
// rule).
base::Lock& BridgeDataLock() {
  static base::NoDestructor<base::Lock> lock;
  return *lock;
}
std::set<jlong>& LiveBridgeData() {
  static base::NoDestructor<std::set<jlong>> data;
  return *data;
}

}  // namespace cefrium_sdk

namespace {

// FEATURE-057: incognito (off-the-record) request context, SHARED by all
// incognito browsers so they see one common session, ISOLATED from the global
// (persistent) context used by normal tabs. Created with an empty cache_path ->
// fully in-memory (no cookies/history/cache on disk). Dropped when the last
// incognito browser closes, so the session is cleared and the next incognito
// tab starts fresh (clear-on-close, like Chrome/Firefox). Touched only on the
// CEF UI thread (browser create in nativeCreateWithSurface, release in
// OnBeforeClose), so no extra lock is needed.
CefRefPtr<CefRequestContext>& IncognitoContext() {
  static base::NoDestructor<CefRefPtr<CefRequestContext>> ctx;
  return *ctx;
}

// Profile preferences set from Java (proxy, WebRTC IP policy), by profile:
// "" is the default profile, kIncognitoKey the incognito one, anything else a
// named profile. A context initializes asynchronously, and the incognito one
// is recreated for every private session, so what the app set is kept here and
// applied when a context becomes ready; CEF creates no browser before that.
// Touched only on the CEF UI thread.
constexpr char kIncognitoKey[] = "\x01incognito";
using PrefMap = std::map<std::string, CefRefPtr<CefValue>>;
std::map<std::string, PrefMap>& ProfilePrefs() {
  static base::NoDestructor<std::map<std::string, PrefMap>> prefs;
  return *prefs;
}
std::set<std::string>& ReadyProfiles() {
  static base::NoDestructor<std::set<std::string>> ready;
  return *ready;
}

// The settings the incognito profile takes from the default one unless it has
// its own, as Chrome's incognito does. Our incognito context does not read the
// default profile's preferences by itself, so without this a proxy set on the
// default profile left private browsers connecting directly.
constexpr const char* kInheritedByIncognito[] = {"proxy",
                                                 "webrtc.ip_handling_policy"};

bool ApplyProfilePref(CefRefPtr<CefRequestContext> context,
                      const std::string& name,
                      CefRefPtr<CefValue> value) {
  CefString error;
  if (!context->SetPreference(name, value, error)) {
    LOG(ERROR) << "Cefrium: preference " << name << ": " << error.ToString();
    return false;
  }
  return true;
}

class ProfilePrefsApplier : public CefRequestContextHandler {
 public:
  explicit ProfilePrefsApplier(std::string key) : key_(std::move(key)) {}

  void OnRequestContextInitialized(
      CefRefPtr<CefRequestContext> context) override {
    ReadyProfiles().insert(key_);
    const PrefMap& own = ProfilePrefs()[key_];
    if (key_ == kIncognitoKey) {
      // Includes what the default profile saved in an earlier run.
      CefRefPtr<CefRequestContext> global =
          CefRequestContext::GetGlobalContext();
      for (const char* name : kInheritedByIncognito) {
        if (!own.count(name)) {
          if (CefRefPtr<CefValue> value = global->GetPreference(name)) {
            ApplyProfilePref(context, name, value);
          }
        }
      }
    }
    for (const auto& [name, value] : own) {
      ApplyProfilePref(context, name, value);
    }
    const bool incognito = key_ == kIncognitoKey;
    cefrium_sdk::ApplyContentServers(context, incognito,
                                     incognito ? std::string() : key_);
  }

  IMPLEMENT_REFCOUNTING(ProfilePrefsApplier);

 private:
  const std::string key_;
};
int& IncognitoBrowserCount() {
  static int count = 0;
  return count;
}
// Get-or-create the shared incognito context and register one more user.
CefRefPtr<CefRequestContext> AcquireIncognitoContext() {
  if (!IncognitoContext()) {
    CefRequestContextSettings settings;
    // Empty cache_path -> isolated, in-memory "incognito" context.
    ReadyProfiles().erase(kIncognitoKey);
    IncognitoContext() = CefRequestContext::CreateContext(
        settings, new ProfilePrefsApplier(kIncognitoKey));
  }
  ++IncognitoBrowserCount();
  return IncognitoContext();
}
// Drop one user; when none remain, release the context to clear the session.
void ReleaseIncognitoContext() {
  if (IncognitoBrowserCount() > 0 && --IncognitoBrowserCount() == 0) {
    IncognitoContext() = nullptr;
  }
}

// FEATURE-071: the engine's data root (CefSettings.root_cache_path), set by
// nativeInitialize. Named profiles live in <root>/profile-<name>: the Chrome
// runtime only creates a disk profile whose directory is an immediate child of
// the root, and silently falls back to an in-memory one otherwise.
std::string& DataRoot() {
  static base::NoDestructor<std::string> root;
  return *root;
}

// FEATURE-071: one persistent context per named profile, created on first use
// and kept for the life of the process (CEF does not close a context's
// databases when its last browser goes away). Java validates the name.
// Touched only on the CEF UI thread, like IncognitoContext.
std::map<std::string, CefRefPtr<CefRequestContext>>& ProfileContexts() {
  static base::NoDestructor<std::map<std::string, CefRefPtr<CefRequestContext>>>
      contexts;
  return *contexts;
}
CefRefPtr<CefRequestContext> ProfileContext(const std::string& name) {
  CefRefPtr<CefRequestContext>& ctx = ProfileContexts()[name];
  if (!ctx) {
    CefRequestContextSettings settings;
    CefString(&settings.cache_path) = DataRoot() + "/profile-" + name;
    ctx = CefRequestContext::CreateContext(settings,
                                           new ProfilePrefsApplier(name));
  }
  return ctx;
}

}  // namespace

namespace cefrium {

CefRefPtr<CefRequestContext> ContextForProfile(bool incognito,
                                               const std::string& name) {
  if (incognito) {
    return IncognitoContext();
  }
  return name.empty() ? CefRequestContext::GetGlobalContext()
                      : ProfileContext(name);
}

}  // namespace cefrium

// Document-start scripts, as Android WebView's addDocumentStartJavaScript:
// components/js_injection's host hands each script to every frame of the
// WebContents as the frame is created, over the frame's own channel, so the
// renderer has it before the frame's first document commits (cross-process
// iframes and new renderer processes included). One host per WebContents,
// gone with it.
class DocumentScripts
    : public content::WebContentsUserData<DocumentScripts> {
 public:
  js_injection::JsCommunicationHost& host() { return host_; }

 private:
  explicit DocumentScripts(content::WebContents* web_contents)
      : content::WebContentsUserData<DocumentScripts>(*web_contents),
        host_(web_contents) {}
  friend WebContentsUserData;
  WEB_CONTENTS_USER_DATA_KEY_DECL();

  js_injection::JsCommunicationHost host_;
};
WEB_CONTENTS_USER_DATA_KEY_IMPL(DocumentScripts);

namespace cefrium_sdk {

void RunWhenCreated(SdkBrowserData* data,
                    base::OnceCallback<void(CefRefPtr<CefBrowser>)> call) {
  if (data->browser && data->browser->GetHost() && !data->hold_calls) {
    std::move(call).Run(data->browser);
  } else {
    data->pending_calls.push_back(std::move(call));
  }
}

CefString JStr(JNIEnv* env, jstring j) {
  CefString out;
  if (j) {
    const char* s = env->GetStringUTFChars(j, nullptr);
    out = s;
    env->ReleaseStringUTFChars(j, s);
  }
  return out;
}

}  // namespace cefrium_sdk

namespace {

// FEATURE-071: the request context a new browser of |data| is created with:
// incognito, a named profile, or nullptr for the global context. Takes the
// incognito reference that OnBeforeClose gives back.
CefRefPtr<CefRequestContext> ContextFor(SdkBrowserData* data) {
  if (data->is_incognito) {
    // One reference per browser, even if both lazy OSR paths run before the
    // browser exists.
    if (data->holds_incognito) {
      return IncognitoContext();
    }
    data->holds_incognito = true;
    return AcquireIncognitoContext();
  }
  if (!data->profile.empty()) {
    return ProfileContext(data->profile);
  }
  return nullptr;
}
}  // namespace

// Rebuild a navigation stack captured by nativeSerializeNavigationState onto
// |wc|'s controller (must be a fresh WebContents -- no committed navigation) and
// load the selected entry, so back/forward works without re-fetching pages.
void ApplyNavigationState(content::WebContents* wc,
                          const std::vector<uint8_t>& bytes) {
  if (!wc || bytes.empty()) {
    return;
  }
  base::Pickle pickle = base::Pickle::WithData(bytes);
  base::PickleIterator it(pickle);
  int current = 0, count = 0;
  if (!it.ReadInt(&current) || !it.ReadInt(&count) || count <= 0) {
    return;
  }
  content::BrowserContext* browser_context = wc->GetBrowserContext();
  std::unique_ptr<content::NavigationEntryRestoreContext> restore_context =
      content::NavigationEntryRestoreContext::Create();
  std::vector<std::unique_ptr<content::NavigationEntry>> entries;
  entries.reserve(count);
  for (int i = 0; i < count; ++i) {
    std::string url_str, page_state_str;
    std::u16string title;
    if (!it.ReadString(&url_str) || !it.ReadString16(&title) ||
        !it.ReadString(&page_state_str)) {
      return;
    }
    std::unique_ptr<content::NavigationEntry> entry =
        content::NavigationController::CreateNavigationEntry(
            GURL(url_str), content::Referrer(), /*initiator_origin=*/std::nullopt,
            /*initiator_base_url=*/std::nullopt, ui::PAGE_TRANSITION_RELOAD,
            /*is_renderer_initiated=*/false, /*extra_headers=*/std::string(),
            browser_context, /*blob_url_loader_factory=*/nullptr);
    if (!page_state_str.empty()) {
      entry->SetPageState(blink::PageState::CreateFromEncodedData(page_state_str),
                          restore_context.get());
    }
    entry->SetTitle(title);
    entries.push_back(std::move(entry));
  }
  if (current < 0 || current >= count) {
    current = count - 1;
  }
  // Cancel any pending initial navigation so Restore() runs on an empty
  // controller (it requires no committed/pending entry). Do NOT load here: the
  // restored page is loaded later, from connectWebContentsInternal via
  // nativeLoadRestoredIfNecessary(), once the on-screen surface and the
  // pull-to-refresh overscroll handler are set up. Loading before that setup
  // navigates while the OverscrollControllerAndroid has no handler, and the
  // RenderFrameHost swap on commit crashes on the render-per-browser process
  // model (RWHVA::HideInternal -> OverscrollControllerAndroid::Disable).
  content::NavigationController& controller = wc->GetController();
  wc->Stop();
  controller.DiscardNonCommittedEntries();
  controller.Restore(current, content::RestoreType::kRestored, &entries);
}

void SdkLifeSpanHandler::OnAfterCreated(CefRefPtr<CefBrowser> browser) {
  LOGI("OnAfterCreated: browser ready (id=%d, surface_mode=%d)",
       browser->GetIdentifier(), data_ ? data_->is_surface_mode : 0);
  if (data_) {
    data_->browser = browser;
    data_->hold_calls = data_->popup_pending;
    data_->popup_pending = false;
    if (data_->close_when_created) {
      browser->GetHost()->CloseBrowser(true);
      return;
    }
    if (!data_->hold_calls) {
      auto pending_calls = std::move(data_->pending_calls);
      for (auto& call : pending_calls) {
        std::move(call).Run(browser);
      }
    }

    // In Surface mode, attach the WebContents to the compositor.
    if (data_->is_surface_mode && data_->surface_bridge) {
      auto* browser_impl = static_cast<CefBrowserHostBase*>(
          browser->GetHost().get());
      content::WebContents* web_contents =
          browser_impl ? browser_impl->GetWebContents() : nullptr;
      if (web_contents) {
        data_->surface_bridge->AttachWebContents(web_contents);
        LOGI("Surface mode: WebContents attached to compositor");
      }
    }

    // Apply a navigation stack that arrived before the browser existed (tab
    // restore): the browser was created at about:blank so the controller is at
    // its initial entry -- Restore replaces it and loads the selected page.
    if (!data_->pending_nav_state.empty()) {
      auto* host =
          static_cast<CefBrowserHostBase*>(browser->GetHost().get());
      ApplyNavigationState(host ? host->GetWebContents() : nullptr,
                           data_->pending_nav_state);
      data_->pending_nav_state.clear();
    }
  }

  // The WebContents exists from here on. Tell Java, so a surface-mode browser
  // connects it to its WindowAndroid now instead of after a fixed delay: until
  // that connection no renderer of this browser receives BeginFrames, so it
  // cannot paint, cannot end paint holding, and Blink suppresses its input.
  browser_created_ = true;
  NotifyJavaBrowserCreated();
}

void SdkLifeSpanHandler::NotifyJavaBrowserCreated() {
  if (!browser_created_ || !java_browser_ || created_notified_) {
    return;
  }
  created_notified_ = true;
  JNIEnv* env = base::android::AttachCurrentThread();
  if (!env) {
    return;
  }
  jclass cls = env->GetObjectClass(java_browser_);
  jmethodID mid = env->GetMethodID(cls, "onNativeBrowserCreated", "()V");
  if (mid) {
    env->CallVoidMethod(java_browser_, mid);
  } else {
    env->ExceptionClear();
  }
  env->DeleteLocalRef(cls);
}

bool SdkLifeSpanHandler::OnBeforePopup(
    CefRefPtr<CefBrowser> browser,
    CefRefPtr<CefFrame> frame,
    int popup_id,
    const CefString& target_url,
    const CefString& target_frame_name,
    WindowOpenDisposition target_disposition,
    bool user_gesture,
    const CefPopupFeatures& popupFeatures,
    CefWindowInfo& windowInfo,
    CefRefPtr<CefClient>& client,
    CefBrowserSettings& settings,
    CefRefPtr<CefDictionaryValue>& extra_info,
    bool* no_javascript_access) {
  // FEATURE-086: a browser the app made for this popup (same mode and
  // profile), not created yet. CEF creates it with its client, so the page
  // keeps window.opener.
  JNIEnv* env = base::android::AttachCurrentThread();
  jlong popup_ptr = 0;
  if (java_browser_ && env) {
    jclass cls = env->GetObjectClass(java_browser_);
    jmethodID mid =
        env->GetMethodID(cls, "onCreateWindow", "(Ljava/lang/String;Z)J");
    if (mid) {
      jstring ju = env->NewStringUTF(target_url.ToString().c_str());
      popup_ptr = env->CallLongMethod(java_browser_, mid, ju,
                                      user_gesture ? JNI_TRUE : JNI_FALSE);
      env->DeleteLocalRef(ju);
    } else {
      env->ExceptionClear();
    }
    env->DeleteLocalRef(cls);
  }
  bool live = false;
  if (popup_ptr) {
    base::AutoLock lock(BridgeDataLock());
    live = LiveBridgeData().count(popup_ptr) > 0;
  }
  auto* popup = live ? reinterpret_cast<SdkBrowserData*>(popup_ptr) : nullptr;
  if (popup && !popup->browser && !popup->popup_pending) {
    popup->popup_pending = true;
    ContextFor(popup);  // takes the incognito reference OnBeforeClose returns
    client = popup->client;
    windowInfo = CefWindowInfo();
    if (!popup->is_surface_mode) {
      windowInfo.SetAsWindowless(0);
      settings.background_color = popup->background_color;
    }
    pending_popups_[popup_id] = popup;
    return false;
  }

  bool handled = false;
  if (java_browser_) {
    if (env) {
      jclass cls = env->GetObjectClass(java_browser_);
      jmethodID mid = env->GetMethodID(cls, "onOpenPopup",
                                       "(Ljava/lang/String;Z)Z");
      if (mid) {
        jstring ju = env->NewStringUTF(target_url.ToString().c_str());
        handled = env->CallBooleanMethod(java_browser_, mid, ju,
                                         user_gesture ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(ju);
      }
      env->DeleteLocalRef(cls);
    }
  }
  if (!handled && browser && browser->GetMainFrame() &&
      !target_url.empty()) {
    browser->GetMainFrame()->LoadURL(target_url);
  }
return true;  // no separate popup window
}

void SdkLifeSpanHandler::OnBeforePopupAborted(CefRefPtr<CefBrowser> browser,
                                              int popup_id) {
  auto it = pending_popups_.find(popup_id);
  if (it == pending_popups_.end()) {
    return;
  }
  SdkBrowserData* popup = it->second;
  pending_popups_.erase(it);
  bool live;
  {
    base::AutoLock lock(BridgeDataLock());
    live = LiveBridgeData().count(reinterpret_cast<jlong>(popup)) > 0;
  }
  if (live && !popup->browser) {
    popup->life_span_handler->Discard();
  }
}

// Binds the Java browser right after creation to the handlers whose events do
// not depend on the embedder registering a listener (nativeSetDisplayCallback)
// first: the created notification, and proxy errors, which are reported per
// profile.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeBindJavaBrowser(JNIEnv* env,
                                                      jclass clazz,
                                                      jlong native_ptr,
                                                      jobject java_browser) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) {
    return;
  }
  if (data->life_span_handler) {
    data->life_span_handler->SetJavaBrowser(env, java_browser);
  }
  if (data->client) {
    data->client->identity_handler()->SetJavaBrowser(env, java_browser);
  }
  if (data->client && data->client->request_handler() &&
      data->client->request_handler()->resource_request_handler()) {
    data->client->request_handler()->resource_request_handler()->SetJavaBrowser(
        env, java_browser);
  }
}

// Out-of-line: needs the full SdkBrowserData definition (member access on data_).
void SdkLifeSpanHandler::OnBeforeClose(CefRefPtr<CefBrowser> browser) {
  extern std::atomic<int> g_closed_browser_count;
  g_closed_browser_count.fetch_add(1, std::memory_order_relaxed);
  if (message_router_) {
    message_router_->OnBeforeClose(browser);
  }
  Discard();
}

void SdkLifeSpanHandler::Discard() {
  if (!data_) {
    return;
  }
  // FEATURE-057: last incognito tab closing drops the shared OTR context, which
  // clears its in-memory cookies/history/cache (clear-on-close).
  if (data_->holds_incognito) {
    ReleaseIncognitoContext();
  }
  // The browser has now actually closed -- safe to tear down. (Doing this in
  // nativeClose, before the async close completed, orphaned the WebContents.)
  data_->surface_bridge.reset();
  data_->devtools_registration = nullptr;
  data_->devtools_observer = nullptr;
  {
    base::AutoLock lock(BridgeDataLock());
    LiveBridgeData().erase(reinterpret_cast<jlong>(data_));
  }
  // Tell Java to destroy its ContentViewRenderView + WindowAndroid now.
  if (java_browser_) {
    JNIEnv* env = base::android::AttachCurrentThread();
    if (env) {
      jclass cls = env->GetObjectClass(java_browser_);
      jmethodID mid = env->GetMethodID(cls, "onNativeBrowserClosed", "()V");
      if (mid) {
        env->CallVoidMethod(java_browser_, mid);
      }
      env->DeleteLocalRef(cls);
    }
  }
  // Defer the delete: we are inside data_->life_span_handler's own method, so
  // freeing data_ (which owns this handler) now could drop the last ref mid-call.
  SdkBrowserData* d = data_;
  data_ = nullptr;
  CefPostTask(TID_UI, CefCreateClosureTask(base::BindOnce(
                          [](SdkBrowserData* p) { delete p; }, d)));
}

void SdkLoadHandler::OnLoadEnd(CefRefPtr<CefBrowser> browser,
                               CefRefPtr<CefFrame> frame,
                               int httpStatusCode) {
  LOGI("OnLoadEnd: status %d, isMain=%d", httpStatusCode, frame->IsMain());
  if (frame->IsMain()) {
    browser->GetHost()->Invalidate(PET_VIEW);
  }
}

// FEATURE: Web Share (navigator.share). A DocumentService-scoped implementation
// of blink::mojom::ShareService that routes the share to the embedding app's
// Android share sheet via the SdkClient. Replaces the default Android binding
// (ForwardToJavaWebContents), whose Chrome Java share machinery is absent here.
class CefShareServiceImpl
    : public content::DocumentService<blink::mojom::ShareService> {
 public:
  static void Create(
      content::RenderFrameHost* render_frame_host,
      mojo::PendingReceiver<blink::mojom::ShareService> receiver) {
    // Owns itself; destroyed when the document goes away or the pipe closes.
    new CefShareServiceImpl(*render_frame_host, std::move(receiver));
  }

  // blink::mojom::ShareService:
  void Share(const std::string& title,
             const std::string& text,
             const GURL& url,
             std::vector<blink::mojom::SharedFilePtr> files,
             ShareCallback callback) override {
    // File sharing is not supported yet; title/text/url cover navigator.share's
    // common case.
    CefRefPtr<CefBrowserHostBase> browser =
        CefBrowserHostBase::GetBrowserForHost(&render_frame_host());
    SdkClient* client =
        browser ? static_cast<SdkClient*>(browser->GetClient().get()) : nullptr;
    const bool ok =
        client && client->FireWebShare(title, text, url.is_valid() ? url.spec()
                                                                    : std::string());
    std::move(callback).Run(ok ? blink::mojom::ShareError::OK
                               : blink::mojom::ShareError::INTERNAL_ERROR);
  }

 private:
  CefShareServiceImpl(
      content::RenderFrameHost& render_frame_host,
      mojo::PendingReceiver<blink::mojom::ShareService> receiver)
      : DocumentService(render_frame_host, std::move(receiver)) {}
};

namespace cefrium {
void BindShareService(
    content::RenderFrameHost* frame,
    mojo::PendingReceiver<blink::mojom::ShareService> receiver) {
  CefShareServiceImpl::Create(frame, std::move(receiver));
}
}  // namespace cefrium

// --- Cefrium.java native methods ---

extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_Cefrium_nativeInitialize(
    JNIEnv* env, jclass clazz, jstring cache_dir_jstr, jstring data_dir_jstr,
    jstring download_dir_jstr) {
  const char* cache_dir = env->GetStringUTFChars(cache_dir_jstr, nullptr);
  const char* data_dir = env->GetStringUTFChars(data_dir_jstr, nullptr);
  DefaultDownloadDir() =
      base::android::ConvertJavaStringToUTF8(env, download_dir_jstr);

  static const char* argv[] = {"cefrium"};
  CefMainArgs main_args(1, const_cast<char**>(argv));
  // No browser-process app: browser-side handlers live on the SdkClient, and
  // the renderer's app is registered in cef_jni_onload.cc.
  int exit_code = CefExecuteProcess(main_args, nullptr, nullptr);
  if (exit_code >= 0) {
    env->ReleaseStringUTFChars(cache_dir_jstr, cache_dir);
    env->ReleaseStringUTFChars(data_dir_jstr, data_dir);
    return exit_code;
  }

  CefSettings settings;
  // no_sandbox stays UNSET. On Android it does not change child routing --
  // renderers always land in an isolated SandboxedProcessService -- it only
  // stops renderer_main from installing Chromium's seccomp-BPF policy, which
  // left every child on the Android zygote baseline filter alone.
  settings.windowless_rendering_enabled = true;
  settings.multi_threaded_message_loop = false;
  settings.log_severity = LOGSEVERITY_INFO;
  CefString(&settings.root_cache_path).FromASCII(cache_dir);
  DataRoot() = cache_dir;

  // Identify as Chrome Android so sites serve full-featured content. CEF IS
  // Chromium -- accurate identification, not spoofing.
  //
  // The version comes from CEF's build constants, NOT version_info. Since
  // Chromium 156 (upstream 7cb5ec9c41e7f) version_info::GetVersionNumber() on
  // Android returns apk_info::package_version_name(), i.e. the HOST APP's
  // android:versionName -- so every consumer advertised its own app version as
  // the Chrome version. A sample at versionName "0.1.0" shipped
  // "Chrome/0.1.0". The engine version is a build property; read it as one.
  std::string ua_product =
      "Chrome/" MAKE_STRING(CHROME_VERSION_MAJOR) "." MAKE_STRING(
          CHROME_VERSION_MINOR) "." MAKE_STRING(CHROME_VERSION_BUILD) "." MAKE_STRING(CHROME_VERSION_PATCH)
      " Cefrium/1.0 Mobile";
  CefString(&settings.user_agent_product).FromASCII(ua_product.c_str());

  std::string locales_dir = std::string(data_dir) + "/locales";
  CefString(&settings.locales_dir_path).FromASCII(locales_dir.c_str());

  bool result = CefInitialize(main_args, settings, nullptr, nullptr);
  if (result) {
    cef_android::SetPickerHost(SdkPickerHost::Get());
    cefrium_sdk::RegisterScriptedPrint();
  }
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
    JNIEnv* env, jclass clazz, jint width, jint height, jfloat scale,
    jboolean incognito, jstring profile) {
  auto* data = new SdkBrowserData();
  data->is_incognito = (incognito == JNI_TRUE);
  data->profile = JStr(env, profile).ToString();
  data->render_handler = new SdkRenderHandler(width, height, scale);
  data->life_span_handler = new SdkLifeSpanHandler();
  data->life_span_handler->SetBrowserData(data);
  data->client = new SdkClient(data->render_handler, data->life_span_handler,
                               /*with_request_handler=*/true);
  data->client->load_handler_ref()->SetBrowserData(data);
  data->client->request_handler()->resource_request_handler()->SetProfile(
      data->is_incognito, data->profile);
  LOGI("nativeCreate: %dx%d (browser not yet created, adblock active)", width, height);
  {
    base::AutoLock lock(BridgeDataLock());
    LiveBridgeData().insert(reinterpret_cast<jlong>(data));
  }
  return reinterpret_cast<jlong>(data);
}

// Actually create the browser with the URL (must have URL at creation time
// for renderer process to spawn correctly).
// Sets the background of an off-screen browser that does not exist yet, or of
// a surface browser at any time (opaque or fully transparent). Returns false
// when an off-screen browser already exists.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetBackgroundColor(JNIEnv* env,
                                                         jclass clazz,
                                                         jlong native_ptr,
                                                         jint argb) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) {
    return JNI_FALSE;
  }
  if (data->is_surface_mode) {
    const SkColor color = SkColorGetA(argb) == SK_AlphaTRANSPARENT
                              ? SK_ColorTRANSPARENT
                              : static_cast<SkColor>(argb);
    auto apply = [](SkColor c, CefRefPtr<CefBrowser> b) {
      static_cast<CefBrowserHostBase*>(b->GetHost().get())
          ->SetBackgroundColor(c);
    };
    if (data->browser) {
      apply(color, data->browser);
    } else {
      RunWhenCreated(data, base::BindOnce(apply, color));
    }
    return JNI_TRUE;
  }
  if (data->browser) {
    return JNI_FALSE;
  }
  data->background_color = static_cast<cef_color_t>(argb);
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeLoadUrl(
    JNIEnv* env, jobject thiz, jlong native_ptr, jstring url_jstr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) return;
  const char* url = env->GetStringUTFChars(url_jstr, nullptr);

  if (!data->browser && data->popup_pending) {
    RunWhenCreated(data, base::BindOnce(
                             [](std::string u, CefRefPtr<CefBrowser> b) {
                               b->GetMainFrame()->LoadURL(u);
                             },
                             std::string(url)));
  } else if (!data->browser) {
    // First loadUrl — create the browser with this URL.
    CefWindowInfo window_info;
    window_info.SetAsWindowless(0);

    CefBrowserSettings browser_settings;
    browser_settings.background_color = data->background_color;

    CefBrowserHost::CreateBrowserSync(window_info, data->client, CefString(url),
                                      browser_settings, nullptr,
                                      ContextFor(data));
    LOGI("CreateBrowser with URL: %s", url);
  } else {
    data->browser->GetMainFrame()->LoadURL(CefString(url));
    LOGI("loadUrl: %s", url);
  }
  env->ReleaseStringUTFChars(url_jstr, url);
}

// FEATURE-092: loadUrl(url, headers), WebView's loadUrl(String, Map). The
// headers go on the main resource only, as LoadURLParams.extra_headers does
// for WebView. CefFrame::LoadRequest cannot do this: it goes through the
// renderer and fails cross-origin (cef_frame.h). |headers| alternates names
// and values; Java has rejected CR and LF in them.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeLoadUrlWithHeaders(
    JNIEnv* env, jobject thiz, jlong native_ptr, jstring url_jstr,
    jobjectArray headers_jarr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) return;
  std::string extra;
  const jsize n = headers_jarr ? env->GetArrayLength(headers_jarr) : 0;
  for (jsize i = 0; i + 1 < n; i += 2) {
    auto name = static_cast<jstring>(env->GetObjectArrayElement(headers_jarr, i));
    auto value =
        static_cast<jstring>(env->GetObjectArrayElement(headers_jarr, i + 1));
    extra += cefrium_sdk::JStr(env, name).ToString() + ": " +
             cefrium_sdk::JStr(env, value).ToString() + "\r\n";
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(value);
  }
  std::string url = cefrium_sdk::JStr(env, url_jstr).ToString();
  if (!data->browser && !data->popup_pending) {
    // An off-screen browser is created by its first load; create it empty and
    // load once it exists.
    CefWindowInfo window_info;
    window_info.SetAsWindowless(0);
    CefBrowserSettings browser_settings;
    browser_settings.background_color = data->background_color;
    CefBrowserHost::CreateBrowserSync(window_info, data->client, CefString(),
                                      browser_settings, nullptr,
                                      ContextFor(data));
  }
  RunWhenCreated(data, base::BindOnce(
                           [](std::string u, std::string h,
                              CefRefPtr<CefBrowser> b) {
                             static_cast<CefFrameHostImpl*>(
                                 b->GetMainFrame().get())
                                 ->LoadURLWithExtras(
                                     u, content::Referrer(),
                                     CefFrameHostImpl::kPageTransitionExplicit,
                                     h);
                           },
                           url, extra));
}

// Reap render processes left orphaned (0 RenderFrameHosts) after a browser closes
// -- e.g. when CefriumBrowserPool evicts an LRU browser. CloseBrowser destroys the
// WebContents but Chromium may keep the child process around; on process-per-browser
// devices (HyperOS / Android 16) the sandboxed_process count then climbs ~1 per
// eviction (PSS stays flat). Only truly unreferenced, non-spare processes are shut
// down (FastShutdownIfPossible is a no-op when unsafe), so this never kills a live
// renderer; on shared-renderer devices it simply finds nothing to reap.
static void ReapUnusedRenderersOnUI() {
  CEF_REQUIRE_UIT();
  int found = 0, reaped = 0;
  for (auto it = content::RenderProcessHost::AllHostsIterator(); !it.IsAtEnd();
       it.Advance()) {
    content::RenderProcessHost* host = it.GetCurrentValue();
    if (host && host->IsInitializedAndNotDead() && !host->IsSpare() &&
        host->GetRenderFrameHostCount() == 0) {
      found++;
      // Defensive only: the real teardown happens when the browser actually
      // closes (CloseHostWindow -> WindowDestroyed -> DestroyBrowser destroys
      // the WebContents and releases all its render processes, including
      // cross-origin subframes). This just nudges any process Chromium still
      // keeps as a warm reuse candidate after its frames are gone. Use the
      // SAFE default FastShutdownIfPossible(): it is a no-op when the process
      // still has keep-alive / pending-reuse refs, so it can never kill a
      // process a live browser is about to reuse.
      if (host->FastShutdownIfPossible()) {
        reaped++;
      }
    }
  }
  LOGI("ReapUnusedRenderers: %d zero-frame render process(es), shut down %d", found,
       reaped);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeReapUnusedRenderers(JNIEnv*, jclass) {
  // Delay so a just-closed browser's RenderFrameHosts finish tearing down (its
  // process then reports 0 frames and becomes reapable).
  CefPostDelayedTask(
      TID_UI, CefCreateClosureTask(base::BindOnce(&ReapUnusedRenderersOnUI)), 1500);
}

// Exact count of live RENDERER processes (RenderProcessHost only -- utility,
// network, audio, storage and GPU use separate host types and are NOT counted, so
// this sidesteps the `ps | grep sandboxed_process` ambiguity). Excludes the spare.
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeActiveRenderProcessCount(JNIEnv*, jclass) {
  if (!CEF_CURRENTLY_ON_UIT()) {
    return -1;  // RenderProcessHost is UI-thread-only; call on the main thread.
  }
  jint n = 0;
  for (auto it = content::RenderProcessHost::AllHostsIterator(); !it.IsAtEnd();
       it.Advance()) {
    content::RenderProcessHost* host = it.GetCurrentValue();
    if (host && host->IsInitializedAndNotDead() && !host->IsSpare()) {
      n++;
    }
  }
  return n;
}

// --- Diagnostic counters (rich API for the pool / renderer-lifetime debugging) ---
// Cumulative count of CEF browsers whose OnBeforeClose has fired (i.e. that actually
// finished closing). If this does NOT grow as a pool evicts browsers, CloseBrowser
// is not completing -- the WebContents (and its renderers) leak.
std::atomic<int> g_closed_browser_count{0};

// Render processes (non-spare, initialized) that currently host ZERO frames -- i.e.
// reuse zombies the reap targets. High while the pool runs = orphaned-but-not-killed.
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeZeroFrameRenderProcessCount(JNIEnv*, jclass) {
  if (!CEF_CURRENTLY_ON_UIT()) return -1;
  jint n = 0;
  for (auto it = content::RenderProcessHost::AllHostsIterator(); !it.IsAtEnd();
       it.Advance()) {
    content::RenderProcessHost* host = it.GetCurrentValue();
    if (host && host->IsInitializedAndNotDead() && !host->IsSpare() &&
        host->GetRenderFrameHostCount() == 0) {
      n++;
    }
  }
  return n;
}

// Total live RenderFrameHosts across all renderer processes -- the real "working
// frame" count. If this grows ~+2/lesson and never drops, the evicted browsers'
// frames (host page + cross-origin iframe) are NOT being destroyed (close() bug).
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeTotalRenderFrameHostCount(JNIEnv*, jclass) {
  if (!CEF_CURRENTLY_ON_UIT()) return -1;
  jint n = 0;
  for (auto it = content::RenderProcessHost::AllHostsIterator(); !it.IsAtEnd();
       it.Advance()) {
    content::RenderProcessHost* host = it.GetCurrentValue();
    if (host && host->IsInitializedAndNotDead()) {
      n += host->GetRenderFrameHostCount();
    }
  }
  return n;
}

// Cumulative count of browsers that finished closing (OnBeforeClose fired).
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeClosedBrowserCount(JNIEnv*, jclass) {
  return g_closed_browser_count.load(std::memory_order_relaxed);
}

// Live (not-yet-closed) CefriumBrowser native instances.
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeLiveBrowserCount(JNIEnv*, jclass) {
  base::AutoLock lock(BridgeDataLock());
  return static_cast<jint>(LiveBridgeData().size());
}

// Load an HTML/data string with an explicit base URL (origin + referrer),
// mirroring Android WebView.loadDataWithBaseURL(). Gives string-loaded content a
// real (non-opaque) origin so embeds that validate their parent origin (e.g. the
// YouTube IFrame player) work. See CefBrowserHostBase::LoadMainFrameDataWithBaseURL.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeLoadDataWithBaseUrl(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring base_url_j,
    jstring data_j, jstring mime_j, jstring charset_j, jstring history_j) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) return;
  // Self-contained jstring -> std::string (the file's JStr() helper is defined
  // later in an anonymous namespace; this function precedes it).
  auto to_str = [env](jstring j) -> std::string {
    if (!j) return std::string();
    const char* s = env->GetStringUTFChars(j, nullptr);
    std::string out(s ? s : "");
    if (s) env->ReleaseStringUTFChars(j, s);
    return out;
  };
  const std::string base_url = to_str(base_url_j);
  const std::string html = to_str(data_j);
  const std::string mime = to_str(mime_j);
  const std::string charset = to_str(charset_j);
  const std::string history = to_str(history_j);

  // approach (A): serve the HTML from a genuine network origin == base_url so the
  // committed document is network-served (embeds like the YouTube IFrame player
  // play; a data: document is rejected client-side -> Error 152). Register the
  // HTML under the normalized base_url; the navigation there is intercepted by
  // SdkResourceRequestHandler::GetResourceHandler and served as a real 200.
  GURL gurl(base_url);
  if (!gurl.is_valid()) {
    LOGI("loadDataWithBaseUrl: invalid base_url '%s' -- ignored",
         base_url.c_str());
    return;
  }
  const std::string served_url = gurl.spec();
  const std::string ctype =
      (mime.empty() ? std::string("text/html") : mime) +
      "; charset=" + (charset.empty() ? std::string("utf-8") : charset);
  RegisterPendingDataDoc(served_url, html, ctype);

  if (!data->browser && data->popup_pending) {
    RunWhenCreated(data, base::BindOnce(
                             [](std::string u, CefRefPtr<CefBrowser> b) {
                               b->GetMainFrame()->LoadURL(u);
                             },
                             served_url));
  } else if (!data->browser) {
    // First load — create the browser directly at the served URL; the renderer
    // spawns and the navigation is intercepted to deliver our HTML.
    CefWindowInfo window_info;
    window_info.SetAsWindowless(0);
    CefBrowserSettings browser_settings;
    browser_settings.background_color = data->background_color;
    CefBrowserHost::CreateBrowserSync(window_info, data->client,
                                      CefString(served_url), browser_settings,
                                      nullptr, ContextFor(data));
    LOGI("CreateBrowser (data-with-base served origin=%s)", served_url.c_str());
  } else {
    data->browser->GetMainFrame()->LoadURL(served_url);
    LOGI("loadDataWithBaseUrl served origin=%s", served_url.c_str());
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetRenderHandler(
    JNIEnv* env, jclass clazz, jlong native_ptr, jobject java_browser) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->render_handler) {
    data->render_handler->SetJavaBrowser(env, java_browser);
  }
  // Also connect the display handler for URL change callbacks.
  if (data && data->client) {
    data->client->display_handler()->SetJavaBrowser(env, java_browser);
    // FEATURE-066: the OSR text-selection quick menu (RunQuickMenu) up-calls
    // Java via the context-menu handler's own java_browser. OSR consumers use
    // setRenderHandler (this path) and may never call nativeSetDisplayCallback,
    // so wire it here too or the quick menu silently no-ops (java_browser null).
    data->client->context_menu_handler()->SetJavaBrowser(env, java_browser);
    // FEATURE-062 follow-up: the client's own java_browser drives the
    // focused-node process-message up-call (OnProcessMessageReceived). In OSR
    // mode nativeSetDisplayCallback may never run, so set it here too (idempotent).
    data->client->SetJavaBrowser(env, java_browser);
  }
}

// Initialize Android Autofill for form auto-completion.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeInitAutofill(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  content::WebContents* wc = nullptr;
  auto* browser_host = static_cast<CefBrowserHostBase*>(
      data->browser->GetHost().get());
  if (browser_host) {
    wc = browser_host->GetWebContents();
  }
  if (!wc) return;
  // Use CefriumAutofillClient instead of Chrome's AndroidAutofillClient.
  // Chrome's version requires PasswordManager infrastructure not
  // available in CEF. Our version just initializes the keyboard
  // suppressor for the AutofillProvider.
  cef::CefAutofillClientAndroid::CreateForWebContents(wc);
  LOGI("CefriumAutofillClient created for WebContents");
}

// Connect display handler for URL/title change callbacks (Surface mode).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetDisplayCallback(
    JNIEnv* env, jclass clazz, jlong native_ptr, jobject java_browser) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client) {
    data->client->display_handler()->SetJavaBrowser(env, java_browser);
    data->client->context_menu_handler()->SetJavaBrowser(env, java_browser);
    data->client->load_handler_ref()->SetJavaBrowser(env, java_browser);
    data->client->find_handler()->SetJavaBrowser(env, java_browser);
    data->client->js_dialog_handler()->SetJavaBrowser(env, java_browser);
    data->client->permission_handler()->SetJavaBrowser(env, java_browser);
    data->client->device_chooser_handler()->SetJavaBrowser(env, java_browser);
    // FEATURE-063 parity handlers.
    data->client->keyboard_handler()->SetJavaBrowser(env, java_browser);
    data->client->focus_handler()->SetJavaBrowser(env, java_browser);
    data->client->drag_handler()->SetJavaBrowser(env, java_browser);
    data->client->audio_handler()->SetJavaBrowser(env, java_browser);
    data->client->frame_handler()->SetJavaBrowser(env, java_browser);
    data->client->command_handler()->SetJavaBrowser(env, java_browser);
    data->client->dialog_handler()->SetJavaBrowser(env, java_browser);
    data->client->SetJavaBrowser(env, java_browser);  // for Web Share
    if (data->client->request_handler()) {
      data->client->request_handler()->SetJavaBrowser(env, java_browser);
    }
    if (data->life_span_handler) {
      data->life_span_handler->SetJavaBrowser(env, java_browser);
    }
    data->client->download_handler()->SetJavaBrowser(env, java_browser);
    // JS bridge (FEATURE-023): deliver window.cefriumQuery to Java.
    if (data->client->bridge_handler()) {
      data->client->bridge_handler()->SetJavaBrowser(env, java_browser);
    }
    {
      auto* cmh = static_cast<SdkContextMenuHandler*>(
          data->client->GetContextMenuHandler().get());
      if (cmh) cmh->SetJavaBrowser(env, java_browser);
    }
    if (data->client->GetRequestHandler()) {
      auto* rh = static_cast<SdkRequestHandler*>(
          data->client->GetRequestHandler().get());
      if (rh->resource_request_handler()) {
        rh->resource_request_handler()->SetJavaBrowser(env, java_browser);
      }
    }
  }
}


// Sets a preference of a profile's request context: now if the context is
// ready, otherwise when it is (see ProfilePrefs). Must run on the UI thread.
// Returns false if the context rejected it.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_CefriumProfile_nativeSetPreference(JNIEnv* env,
                                                    jclass,
                                                    jboolean incognito,
                                                    jstring profile_j,
                                                    jstring name_j,
                                                    jstring json_j) {
  if (!CefCurrentlyOn(TID_UI)) {
    LOG(ERROR) << "Cefrium: profile preferences are set on the main thread";
    return JNI_FALSE;
  }
  const std::string profile = JStr(env, profile_j).ToString();
  const std::string key = incognito == JNI_TRUE ? kIncognitoKey : profile;
  const std::string name = JStr(env, name_j).ToString();
  CefRefPtr<CefValue> value =
      CefParseJSON(JStr(env, json_j), JSON_PARSER_RFC);
  if (!value) {
    return JNI_FALSE;
  }
  CefRefPtr<CefRequestContext> context;
  if (incognito == JNI_TRUE) {
    context = IncognitoContext();
  } else if (profile.empty()) {
    context = CefRequestContext::GetGlobalContext();
    ReadyProfiles().insert(key);
  } else {
    context = ProfileContext(profile);
  }
  if (context && ReadyProfiles().count(key) &&
      !ApplyProfilePref(context, name, value)) {
    return JNI_FALSE;
  }
  ProfilePrefs()[key][name] = value;
  // An open incognito session follows the default profile's change unless it
  // set this itself.
  if (key.empty() && IncognitoContext() && ReadyProfiles().count(kIncognitoKey) &&
      !ProfilePrefs()[kIncognitoKey].count(name) &&
      std::count(std::begin(kInheritedByIncognito),
                 std::end(kInheritedByIncognito), name)) {
    ApplyProfilePref(IncognitoContext(), name, value);
  }
  return JNI_TRUE;
}

// Drops the incognito profile's own proxy and WebRTC settings, so it follows
// the default profile's again.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumProfile_nativeFollowDefaultProfile(JNIEnv* env,
                                                           jclass) {
  if (!CefCurrentlyOn(TID_UI)) {
    LOG(ERROR) << "Cefrium: profile preferences are set on the main thread";
    return;
  }
  PrefMap& own = ProfilePrefs()[kIncognitoKey];
  CefRefPtr<CefRequestContext> global = CefRequestContext::GetGlobalContext();
  for (const char* name : kInheritedByIncognito) {
    own.erase(name);
    if (IncognitoContext() && ReadyProfiles().count(kIncognitoKey)) {
      if (CefRefPtr<CefValue> value = global->GetPreference(name)) {
        ApplyProfilePref(IncognitoContext(), name, value);
      }
    }
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetSize(JNIEnv* env,
                                              jclass clazz,
                                              jlong native_ptr,
                                              jint width,
                                              jint height) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->render_handler) {
    return;
  }
  data->render_handler->SetSize(width, height);
  // Before the first load there is no browser yet; it starts at the new size.
  if (data->browser) {
    // The screen rect GetScreenInfo reports is the view's, so it changed too.
    data->browser->GetHost()->NotifyScreenInfoChanged();
    data->browser->GetHost()->WasResized();
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

// FEATURE-063: enable/disable the raw PCM audio tap (opt-in; redirects audio).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetAudioCaptureEnabled(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean enabled) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->client) {
    data->client->SetAudioCaptureEnabled(enabled == JNI_TRUE);
  }
}

// Issue #10: give or take keyboard focus.
//
// An off-screen (OSR) browser has no platform widget, so nothing ever delivers
// focus to it and its document stays unfocused forever. That is not cosmetic:
// Blink gates several APIs on document focus BEFORE any permission check, so
// they fail in a way that looks like a permission bug. The reported case is
// navigator.clipboard.readText(), which rejects with
//   NotAllowedError: "Document is not focused."
// in clipboard_promise.cc, without the permission handler ever being consulted.
//
// CefBrowserHost::SetFocus is the public CEF API for this and is what the
// desktop ports drive from their platform focus events; on-screen Android
// browsers get it from the View, OSR browsers had no way to receive it at all.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetFocus(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean focus) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data) {
    RunWhenCreated(data, base::BindOnce(
                             [](bool f, CefRefPtr<CefBrowser> b) {
                               b->GetHost()->SetFocus(f);
                             },
                             focus == JNI_TRUE));
  }
}

// FEATURE-063: programmatically exit HTML fullscreen (e.g. back button).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeExitFullscreen(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean will_cause_resize) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) {
    data->browser->GetHost()->ExitFullscreen(will_cause_resize == JNI_TRUE);
  }
}

// FEATURE-063: history list with titles. Returns String[] of
// "<isCurrent 0/1>\t<url>\t<title>", oldest-first.
namespace {
class NavEntryCollector : public CefNavigationEntryVisitor {
 public:
  bool Visit(CefRefPtr<CefNavigationEntry> entry,
             bool current, int index, int total) override {
    std::string row = current ? "1\t" : "0\t";
    row += entry->GetURL().ToString();
    row += "\t";
    row += entry->GetTitle().ToString();
    entries_.push_back(row);
    return true;
  }
  std::vector<std::string> entries_;
  IMPLEMENT_REFCOUNTING(NavEntryCollector);
};
}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_cefrium_CefriumBrowser_nativeGetNavigationEntries(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  jclass str_cls = env->FindClass("java/lang/String");
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) {
    return env->NewObjectArray(0, str_cls, nullptr);
  }
  CefRefPtr<NavEntryCollector> visitor(new NavEntryCollector());
  data->browser->GetHost()->GetNavigationEntries(visitor, /*current_only=*/false);
  jobjectArray arr = env->NewObjectArray(
      static_cast<jsize>(visitor->entries_.size()), str_cls, nullptr);
  for (size_t i = 0; i < visitor->entries_.size(); ++i) {
    jstring s = env->NewStringUTF(visitor->entries_[i].c_str());
    env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
    env->DeleteLocalRef(s);
  }
  return arr;
}

// FEATURE-063: fetch an image (e.g. favicon) to PNG bytes, delivered to Java
// onImageDownloaded(url, byte[]). byte[] is null on failure.
namespace {
class ImageDownloadCb : public CefDownloadImageCallback {
 public:
  explicit ImageDownloadCb(jobject java_browser_global)
      : java_browser_(java_browser_global) {}
  void OnDownloadImageFinished(const CefString& image_url,
                               int http_status_code,
                               CefRefPtr<CefImage> image) override {
    JNIEnv* env = base::android::AttachCurrentThread();
    if (env && java_browser_) {
      jbyteArray jbytes = nullptr;
      if (image) {
        int w = 0, h = 0;
        CefRefPtr<CefBinaryValue> png =
            image->GetAsPNG(1.0f, true, w, h);
        if (png) {
          size_t sz = png->GetSize();
          jbytes = env->NewByteArray(static_cast<jsize>(sz));
          std::vector<uint8_t> buf(sz);
          png->GetData(buf.data(), sz, 0);
          env->SetByteArrayRegion(jbytes, 0, static_cast<jsize>(sz),
                                  reinterpret_cast<const jbyte*>(buf.data()));
        }
      }
      jclass cls = env->GetObjectClass(java_browser_);
      jmethodID mid = env->GetMethodID(cls, "onImageDownloaded",
                                       "(Ljava/lang/String;[B)V");
      if (mid) {
        jstring j = env->NewStringUTF(image_url.ToString().c_str());
        env->CallVoidMethod(java_browser_, mid, j, jbytes);
        env->DeleteLocalRef(j);
      }
      if (jbytes) env->DeleteLocalRef(jbytes);
      env->DeleteLocalRef(cls);
      env->DeleteGlobalRef(java_browser_);
      java_browser_ = nullptr;
    }
  }
  jobject java_browser_;
  IMPLEMENT_REFCOUNTING(ImageDownloadCb);
};
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeDownloadImage(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring url_jstr,
    jobject java_browser, jint max_size) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser || !url_jstr || !java_browser) return;
  const char* url = env->GetStringUTFChars(url_jstr, nullptr);
  if (url) {
    jobject global = env->NewGlobalRef(java_browser);
    data->browser->GetHost()->DownloadImage(
        CefString(url), /*is_favicon=*/true, static_cast<uint32_t>(max_size),
        /*bypass_cache=*/false, new ImageDownloadCb(global));
    env->ReleaseStringUTFChars(url_jstr, url);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeClose(
    JNIEnv* env, jobject thiz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) {
    return;
  }
  if (data->browser) {
    // Only INITIATE the close. The surface reset, LiveBridgeData erase, Java view
    // destruction and `delete data` are all deferred to OnBeforeClose, which fires
    // once the close actually completes (CloseHostWindow -> WindowDestroyed ->
    // DestroyBrowser). Doing them here, before the async close completes, would
    // tear down the Java window out from under the still-closing WebContents.
    // See SdkLifeSpanHandler::OnBeforeClose and the Android platform delegate.
    data->browser->GetHost()->CloseBrowser(true);
  } else if (data->popup_pending) {
    // CEF is creating it: close it once it exists (or the abort discards it).
    data->close_when_created = true;
  } else {
    // Browser creation failed. The browser is created with CreateBrowserSync,
    // so a created browser always has data->browser set by the time Java can
    // call close(); without one, OnAfterCreated will never run and no close
    // will fire, so clean up synchronously here.
    {
      base::AutoLock lock(BridgeDataLock());
      LiveBridgeData().erase(native_ptr);
    }
    delete data;
  }
}

// --- Surface rendering mode ---

extern "C" JNIEXPORT jlong JNICALL
Java_com_cefrium_CefriumBrowser_nativeCreateWithSurface(
    JNIEnv* env, jclass clazz, jobject java_window_android,
    jobject java_surface, jint width, jint height, jfloat scale,
    jboolean incognito, jstring profile, jboolean for_popup) {
  auto* data = new SdkBrowserData();
  data->is_surface_mode = true;
  data->is_incognito = (incognito == JNI_TRUE);
  data->profile = JStr(env, profile).ToString();

  // Create life span handler to capture the browser reference.
  data->life_span_handler = new SdkLifeSpanHandler();
  data->life_span_handler->SetBrowserData(data);

  // Create client without render handler (Surface mode doesn't use OnPaint).
  // Rendering is handled by ContentViewRenderView on the Java side.
  data->client = new SdkClient(
      nullptr,  // No render handler — ContentViewRenderView handles rendering
      data->life_span_handler,
      /*with_request_handler=*/true);
  data->client->load_handler_ref()->SetBrowserData(data);
  data->client->request_handler()->resource_request_handler()->SetProfile(
      data->is_incognito, data->profile);

  LOGI("nativeCreateWithSurface: %dx%d (windowed mode, ContentViewRenderView)", width, height);

  // Create browser in NON-windowless mode.
  // Chromium creates WebContentsViewAndroid + RenderWidgetHostViewAndroid.
  // ContentViewRenderView (Java) manages the compositor and Surface.
  CefWindowInfo window_info;
  // Do NOT call SetAsWindowless — leave default (windowed/Surface mode).

  CefBrowserSettings browser_settings;
  browser_settings.background_color = CefColorSetARGB(255, 255, 255, 255);

  // Incognito tabs share one off-the-record context (isolated + in-memory), a
  // named profile has its own persistent one, and the rest use the global.
  // A popup's browser is created by CEF from its opener's OnBeforePopup.
  if (!for_popup) {
    CefBrowserHost::CreateBrowserSync(window_info, data->client, CefString(""),
                                      browser_settings, nullptr,
                                      ContextFor(data));
  }

  {
    base::AutoLock lock(BridgeDataLock());
    LiveBridgeData().insert(reinterpret_cast<jlong>(data));
  }
  return reinterpret_cast<jlong>(data);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeUpdateSurface(
    JNIEnv* env, jclass clazz, jlong native_ptr,
    jobject java_surface, jint width, jint height) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->surface_bridge) {
    auto j_surface = base::android::ScopedJavaLocalRef<jobject>::Adopt(env, java_surface);
    data->surface_bridge->SurfaceChanged(env, j_surface, width, height);
    j_surface.Release();
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeDetachSurface(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->surface_bridge) {
    data->surface_bridge->SurfaceDestroyed();
  }
}

// Return the Java WebContents object for this browser.
// Used by Surface rendering to pass WebContents to ContentViewRenderView.
extern "C" JNIEXPORT jobject JNICALL
Java_com_cefrium_CefriumBrowser_nativeGetWebContents(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return nullptr;

  auto* browser_impl = static_cast<CefBrowserHostBase*>(
      data->browser->GetHost().get());
  if (!browser_impl) return nullptr;
  content::WebContents* web_contents = browser_impl->GetWebContents();
  if (!web_contents) return nullptr;

  return web_contents->GetJavaWebContents().Release();
}

// Navigation methods.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeGoBack(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) data->browser->GoBack();
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeGoForward(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) data->browser->GoForward();
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeReload(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) data->browser->Reload();
}

// Serialize the full back/forward navigation stack (URL + blink PageState +
// current index per entry) for session restore -- Chrome-style tab restore, so
// a restored tab can go back, not just show its current page. Null if empty.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_cefrium_CefriumBrowser_nativeSerializeNavigationState(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return nullptr;
  auto* host = static_cast<CefBrowserHostBase*>(data->browser->GetHost().get());
  content::WebContents* wc = host ? host->GetWebContents() : nullptr;
  if (!wc) return nullptr;
  content::NavigationController& controller = wc->GetController();
  const int count = controller.GetEntryCount();
  if (count <= 0) return nullptr;
  base::Pickle pickle;
  pickle.WriteInt(controller.GetCurrentEntryIndex());
  pickle.WriteInt(count);
  for (int i = 0; i < count; ++i) {
    content::NavigationEntry* entry = controller.GetEntryAtIndex(i);
    pickle.WriteString(entry->GetURL().spec());
    pickle.WriteString16(entry->GetTitleForDisplay());
    pickle.WriteString(entry->GetPageState().ToEncodedData());
  }
  return base::android::ToJavaByteArray(
             env, reinterpret_cast<const uint8_t*>(pickle.data()), pickle.size())
      .Release();
}

// Restore a stack captured by nativeSerializeNavigationState onto a FRESH
// browser (empty controller): rebuild the entries and Restore() so back/forward
// works without re-fetching each page. Must run before the first navigation.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeRestoreNavigationState(
    JNIEnv* env, jclass clazz, jlong native_ptr, jbyteArray blob) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !blob) return;
  const jsize len = env->GetArrayLength(blob);
  if (len <= 0) return;
  std::vector<uint8_t> bytes(static_cast<size_t>(len));
  env->GetByteArrayRegion(blob, 0, len, reinterpret_cast<jbyte*>(bytes.data()));
  if (data->browser) {
    // Browser already exists: apply now (caller guarantees a fresh controller).
    auto* host = static_cast<CefBrowserHostBase*>(data->browser->GetHost().get());
    ApplyNavigationState(host ? host->GetWebContents() : nullptr, bytes);
    return;
  }
  // Browser not created yet: the surface browser (createWithSurface) is still
  // being created asynchronously. Just stash the stack -- its OnAfterCreated
  // applies it. Do NOT create a second (windowless) browser here.
  data->pending_nav_state = std::move(bytes);
}

// Trigger the deferred load of a restored navigation stack. Called by the SDK
// from connectWebContentsInternal after the on-screen surface and the
// pull-to-refresh overscroll handler are set up. No-op if nothing needs loading.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeLoadRestoredIfNecessary(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) {
    return;
  }
  auto* host = static_cast<CefBrowserHostBase*>(data->browser->GetHost().get());
  content::WebContents* wc = host ? host->GetWebContents() : nullptr;
  if (wc) {
    wc->GetController().LoadIfNecessary();
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeEvaluateJavaScript(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring script_jstr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefRefPtr<CefFrame> frame = data->browser->GetMainFrame();
  if (!frame) return;
  const char* script = env->GetStringUTFChars(script_jstr, nullptr);
  // Fire-and-forget execution in the main frame; no return value.
  frame->ExecuteJavaScript(CefString(script), frame->GetURL(), 0);
  env->ReleaseStringUTFChars(script_jstr, script);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeEvaluateJavaScriptInFocusedFrame(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring script_jstr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefRefPtr<CefFrame> frame = data->browser->GetFocusedFrame();
  if (!frame) frame = data->browser->GetMainFrame();
  if (!frame) return;
  const char* script = env->GetStringUTFChars(script_jstr, nullptr);
  frame->ExecuteJavaScript(CefString(script), frame->GetURL(), 0);
  env->ReleaseStringUTFChars(script_jstr, script);
}

// ───────────────────── Dynamic WebSettings (FEATURE-025) ─────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetUserAgentOverride(
    JNIEnv* env, jclass, jlong native_ptr, jstring ua_j) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) return;
  RunWhenCreated(data, base::BindOnce(
                           [](std::string user_agent,
                              CefRefPtr<CefBrowser> browser) {
                             static_cast<CefBrowserHostBase*>(
                                 browser->GetHost().get())
                                 ->SetUserAgentOverride(user_agent);
                           },
                           JStr(env, ua_j).ToString()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetJavaScriptEnabled(
    JNIEnv* env, jclass, jlong native_ptr, jboolean enabled) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) return;
  RunWhenCreated(data, base::BindOnce(
                           [](bool enabled, CefRefPtr<CefBrowser> browser) {
                             static_cast<CefBrowserHostBase*>(
                                 browser->GetHost().get())
                                 ->SetJavaScriptEnabled(enabled);
                           },
                           enabled == JNI_TRUE));
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetZoomLevel(
    JNIEnv* env, jclass, jlong native_ptr, jdouble zoom) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data) {
    RunWhenCreated(data, base::BindOnce(
                             [](double z, CefRefPtr<CefBrowser> b) {
                               b->GetHost()->SetZoomLevel(z);
                             },
                             zoom));
  }
}

// ───────────────────── Navigation extras (FEATURE-026) ─────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativePostUrl(
    JNIEnv* env, jclass, jlong native_ptr, jstring url_j, jbyteArray data_j) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser || !data->browser->GetMainFrame()) return;
  CefRefPtr<CefRequest> request = CefRequest::Create();
  request->SetURL(JStr(env, url_j));
  request->SetMethod("POST");
  if (data_j) {
    jsize len = env->GetArrayLength(data_j);
    jbyte* bytes = env->GetByteArrayElements(data_j, nullptr);
    CefRefPtr<CefPostData> post_data = CefPostData::Create();
    CefRefPtr<CefPostDataElement> element = CefPostDataElement::Create();
    element->SetToBytes(static_cast<size_t>(len), bytes);
    post_data->AddElement(element);
    request->SetPostData(post_data);
    env->ReleaseByteArrayElements(data_j, bytes, JNI_ABORT);
  }
  data->browser->GetMainFrame()->LoadRequest(request);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeStopLoading(
    JNIEnv* env, jclass, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) data->browser->StopLoad();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_CefriumBrowser_nativeCanGoBack(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) return data->browser->CanGoBack();
  return false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_CefriumBrowser_nativeCanGoForward(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data && data->browser) return data->browser->CanGoForward();
  return false;
}

// FEATURE-092: WebView's goBackOrForward / canGoBackOrForward. CEF has no
// call for it (CefBrowser::GoBack/GoForward step by one), so it goes to the
// NavigationController; an upstream candidate as CefBrowser::GoToOffset.
// |go| false only asks.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_CefriumBrowser_nativeGoToOffset(JNIEnv* env,
                                                 jclass clazz,
                                                 jlong native_ptr,
                                                 jint steps,
                                                 jboolean go) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return JNI_FALSE;
  auto* host = static_cast<CefBrowserHostBase*>(data->browser->GetHost().get());
  content::WebContents* wc = host ? host->GetWebContents() : nullptr;
  if (!wc || !wc->GetController().CanGoToOffset(steps)) return JNI_FALSE;
  if (go == JNI_TRUE) wc->GetController().GoToOffset(steps);
  return JNI_TRUE;
}

// Dark mode: prefers-color-scheme for this browser.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetDarkMode(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean dark) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (data) {
    // SetChromeColorScheme (ThemeService) is a no-op on Android; drive the web
    // content's prefers-color-scheme directly via a per-browser WebPreferences
    // override instead.
    RunWhenCreated(data, base::BindOnce(
                             [](bool d, CefRefPtr<CefBrowser> b) {
                               static_cast<CefBrowserHostBase*>(
                                   b->GetHost().get())
                                   ->SetPreferredColorSchemeDark(d);
                             },
                             dark == JNI_TRUE));
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSetMediaPlaybackRequiresUserGesture(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean required) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) {
    return;
  }
  RunWhenCreated(data, base::BindOnce(
                           [](bool required, CefRefPtr<CefBrowser> browser) {
                             static_cast<CefBrowserHostBase*>(
                                 browser->GetHost().get())
                                 ->SetMediaPlaybackRequiresUserGesture(required);
                           },
                           required == JNI_TRUE));
}

namespace {

// Registers script |id| of |data| with the browser's JsCommunicationHost.
bool AddDocumentScript(SdkBrowserData* data,
                       int id,
                       std::u16string script,
                       std::vector<std::string> rules,
                       CefRefPtr<CefBrowser> browser) {
  content::WebContents* wc =
      static_cast<CefBrowserHostBase*>(browser->GetHost().get())
          ->GetWebContents();
  if (!wc) {
    return false;
  }
  DocumentScripts::CreateForWebContents(wc);
  auto result =
      DocumentScripts::FromWebContents(wc)->host().AddPersistentJavaScript(
          std::move(script),
          js_injection::mojom::DocumentInjectionTime::kDocumentStart, rules,
          /*world_identifier=*/0);
  if (!result.script_id) {
    return false;
  }
  data->script_ids[id] = *result.script_id;
  return true;
}

void RemoveDocumentScript(SdkBrowserData* data,
                          int id,
                          CefRefPtr<CefBrowser> browser) {
  auto it = data->script_ids.find(id);
  if (it == data->script_ids.end()) {
    return;
  }
  const int host_id = it->second;
  data->script_ids.erase(it);
  content::WebContents* wc =
      static_cast<CefBrowserHostBase*>(browser->GetHost().get())
          ->GetWebContents();
  if (auto* scripts = wc ? DocumentScripts::FromWebContents(wc) : nullptr) {
    scripts->host().RemovePersistentJavaScript(host_id);
  }
}

}  // namespace

// Returns our id for the script (> 0), or -(n + 1) when rule n is invalid.
extern "C" JNIEXPORT jint JNICALL
Java_com_cefrium_CefriumBrowser_nativeAddDocumentStartJavaScript(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring script_j,
    jobjectArray rules_j) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) {
    return 0;
  }
  std::vector<std::string> rules;
  origin_matcher::OriginMatcher matcher;
  const jsize n = env->GetArrayLength(rules_j);
  for (jsize i = 0; i < n; ++i) {
    auto rule_j = static_cast<jstring>(env->GetObjectArrayElement(rules_j, i));
    std::string rule = JStr(env, rule_j).ToString();
    env->DeleteLocalRef(rule_j);
    if (!matcher.AddRuleFromString(rule)) {
      return -(i + 1);
    }
    rules.push_back(std::move(rule));
  }
  const int id = data->next_script_id++;
  auto add = base::BindOnce(&AddDocumentScript, data, id,
                            JStr(env, script_j).ToString16(), std::move(rules));
  if (data->browser && data->browser->GetHost()) {
    return std::move(add).Run(data->browser) ? id : 0;
  }
  RunWhenCreated(data, base::BindOnce(base::IgnoreResult(std::move(add))));
  return id;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeRemoveDocumentStartJavaScript(
    JNIEnv* env, jclass clazz, jlong native_ptr, jint id) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data) {
    return;
  }
  // In call order: a script removed before the browser exists is added and
  // removed again before anything loads.
  RunWhenCreated(data, base::BindOnce(&RemoveDocumentScript, data, id));
}

// Chrome's secure DNS settings, which apply to the whole browser. The templates
// are checked as Chrome checks them; secure mode needs at least one.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_cefrium_Cefrium_nativeSetSecureDns(JNIEnv* env,
                                            jclass clazz,
                                            jstring mode_j,
                                            jstring templates_j) {
  const std::string mode = JStr(env, mode_j).ToString();
  const std::string templates = JStr(env, templates_j).ToString();
  if (mode != "off" && mode != "automatic" && mode != "secure") {
    return JNI_FALSE;
  }
  if (!templates.empty() &&
      !net::DnsOverHttpsConfig::FromString(templates).has_value()) {
    return JNI_FALSE;
  }
  if (mode == "secure" && templates.empty()) {
    return JNI_FALSE;
  }
  CefRefPtr<CefPreferenceManager> prefs =
      CefPreferenceManager::GetGlobalPreferenceManager();
  CefString error;
  // Each change applies at once, so the order matters: the servers must not
  // meet the previous mode. Turning it off sets the mode first; otherwise the
  // servers are in place before the mode that uses them.
  auto templates_pref = std::make_pair("dns_over_https.templates", templates);
  auto mode_pref = std::make_pair("dns_over_https.mode", mode);
  const bool off = mode == "off";
  for (const auto& [name, value] : {off ? mode_pref : templates_pref,
                                    off ? templates_pref : mode_pref}) {
    CefRefPtr<CefValue> v = CefValue::Create();
    v->SetString(value);
    if (!prefs->SetPreference(name, v, error)) {
      LOG(ERROR) << "setSecureDns: " << name << ": " << error.ToString();
      return JNI_FALSE;
    }
  }
  return JNI_TRUE;
}
