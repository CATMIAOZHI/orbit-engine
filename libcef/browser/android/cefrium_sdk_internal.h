// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// What the JNI sources of the SDK (cefrium_sdk_*.cc) share: the per-browser
// data behind CefriumBrowser's native pointer, and their common helpers.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_INTERNAL_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_INTERNAL_H_

#include <jni.h>

#include <map>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "base/functional/callback.h"
#include "cef/include/cef_browser.h"
#include "cef/include/cef_registration.h"
#include "cef/libcef/browser/android/browser_surface_android.h"
#include "ui/android/window_android.h"

class SdkClient;
class SdkDevToolsObserver;
class SdkLifeSpanHandler;
class SdkRenderHandler;

// Per-browser data stored as the native pointer in Java.
struct SdkBrowserData {
  CefRefPtr<CefBrowser> browser;
  // FEATURE-082: created on the first DevTools call from Java; releasing the
  // registration removes the observer.
  CefRefPtr<SdkDevToolsObserver> devtools_observer;
  CefRefPtr<CefRegistration> devtools_registration;
  // Background of an off-screen browser, applied when it is created at the
  // first load. A fully transparent colour makes transparent page areas come
  // back with alpha 0 in OnPaint (measured on 156).
  cef_color_t background_color = CefColorSetARGB(255, 255, 255, 255);
  CefRefPtr<SdkRenderHandler> render_handler;
  CefRefPtr<SdkLifeSpanHandler> life_span_handler;
  CefRefPtr<SdkClient> client;
  // Before the browser exists (the off-screen constructor creates it at the
  // first load, the surface one asynchronously) calls wait in three places,
  // each drained at its own moment:
  //  - pending_calls: configuration, run in call order in OnAfterCreated,
  //    before anything navigates;
  //  - pending_nav_state: a restored navigation stack, applied after them;
  //  - pending_devtools: DevTools methods, run at the first main-frame
  //    commit so the first navigation does not abort them.
  //
  // Navigation stack to Restore() once the browser exists (session/tab restore):
  // the browser is created at about:blank so the renderer spawns, then this is
  // applied in OnAfterCreated before any real navigation.
  std::vector<uint8_t> pending_nav_state;
  std::vector<base::OnceCallback<void(CefRefPtr<CefBrowser>)>> pending_calls;
  std::vector<base::OnceCallback<void(CefRefPtr<CefBrowser>)>>
      pending_devtools;
  // addDocumentStartJavaScript: our ids for the scripts the
  // JsCommunicationHost holds (ours -> its).
  std::map<int, int> script_ids;
  int next_script_id = 1;

  // Surface rendering mode (null for OSR mode).
  std::unique_ptr<BrowserSurfaceAndroid> surface_bridge;
  ui::WindowAndroid* window_android = nullptr;  // Owned by Java via prevent-GC ref.
  bool is_surface_mode = false;
  // FEATURE-057: created with the shared off-the-record context (private tab).
  bool is_incognito = false;
  // FEATURE-071: named profile; empty = the global (default) context.
  std::string profile;
  // Whether this browser holds an incognito reference to release on close.
  bool holds_incognito = false;
  // A popup: OnBeforePopup of its opener gave CEF this client, so CEF creates
  // the browser and a load must not. close_when_created: closed by the app
  // before CEF created it.
  bool popup_pending = false;
  bool close_when_created = false;
};

namespace cefrium_sdk {

// The UTF-8 contents of |j|, empty for null.
CefString JStr(JNIEnv* env, jstring j);

// Runs |call| now if the browser exists, otherwise in OnAfterCreated, in
// call order.
void RunWhenCreated(SdkBrowserData* data,
                    base::OnceCallback<void(CefRefPtr<CefBrowser>)> call);

}  // namespace cefrium_sdk

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEFRIUM_SDK_INTERNAL_H_
