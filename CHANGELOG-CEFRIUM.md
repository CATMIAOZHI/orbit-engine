# Cefrium Changelog (Android port + SDK)

SCOPE: this is the changelog for CEFRIUM -- the Chromium Embedded Framework ported
to native Android, and its Android SDK (`com.cefrium:*`). It is NOT the upstream CEF
changelog. For upstream CEF release notes see the upstream project
(https://bitbucket.org/chromiumembedded/cef). The `-CEFRIUM` suffix mirrors
`LICENSE-CEFRIUM.md` to keep Cefrium-specific docs distinct from CEF's own.

Versioning: Cefrium version numbers (0.x.y) are INDEPENDENT of CEF/Chromium version
numbers; the Chromium engine version is noted under each release. See VERSIONING-CEFRIUM.md.

This file records, per release, what changed for SDK consumers and the FINAL
solution behind each notable item (with a one-line decision note where the "why"
matters). Format follows Keep a Changelog; versions are immutable on the Codeberg
Maven registry. Deep rationale, rejected approaches, and root-cause analysis live
in the internal FEATURE-NNN.md working docs (not published).

## [Unreleased]

### Added
- **`CefriumNavigationClient.onBeforeNavigate`**, WebView's
  `shouldOverrideUrlLoading`: decide on the UI thread whether a navigation the
  page starts (a link, a form, a script, an iframe, a server redirect) goes
  on; return true to cancel it, for example to open a link in another app.
  Not called for `loadUrl` or back/forward. `CefriumNavigationRequest` gives
  the URL, method, frame, redirect, gesture and headers.
- **`loadUrl(url, headers)`**, WebView's `loadUrl(String, Map)`: the headers
  go with the page's own request, not with its resources.
- **`CefriumResourceInterceptor`** (`setResourceInterceptor`), WebView's
  `shouldInterceptRequest` and more: `shouldBlockRequest` cancels on the
  network thread; `shouldInterceptRequest` answers any request (any URL,
  method, headers and body) from a background thread, or returns null for the
  network, with Range and `HEAD` handled. `ResourceRequest.getType()` returns
  a `CefriumResourceType`.
- **`CefriumChromeClient`** (`setChromeClient`), WebView's `WebChromeClient`;
  first, permissions: `onPermissionRequest` gets a `CefriumPermissionRequest`
  (origin, a set of `CefriumPermission`, `grant()`/`deny()` from any thread)
  and `onPermissionRequestCanceled`. `setContentSetting(origin,
  CefriumPermission, CefriumContentSetting)`. The engine's permission bits
  are mapped to Cefrium's own values, so an engine update can no longer
  renumber them (as NFC was in 0.20.0).
- The two clients carry the page's events, typed and on the UI thread, with
  the browser as first parameter (one client can serve several browsers):
  `CefriumChromeClient` gets the title, favicon URLs, fullscreen, console
  messages (`CefriumConsoleMessage` with a `Level`), link status, camera and
  microphone use and find results; `CefriumNavigationClient` gets the URL,
  load start, loading state, progress (0-100), load errors
  (`CefriumLoadError`, net code) and the renderer's end
  (`CefriumRendererTerminationStatus`), unresponsive and responsive.
- JavaScript dialogs on `CefriumChromeClient`: `onJsDialog` (typed
  `CefriumJsDialogType`) and `onBeforeUnloadDialog`. The callback can be
  answered from any thread, and only the first answer counts (a second call
  used to reach the engine again).
- File inputs on `CefriumChromeClient`: `onShowFileChooser` with
  `CefriumFileChooserParams` (typed `Mode`, accept types, `createIntent()`
  for the system picker, `parseResult()`) and a `CefriumFileChooserCallback`
  answerable from any thread. Returning false keeps the SDK's own chooser.
- The context menu on `CefriumChromeClient.onContextMenu`: a
  `CefriumContextMenu` with the link, image, page, selection, media type,
  position and its entries (`Item.execute()`, `cancel()`, any thread), so no
  engine id or "id|label" string reaches the app.
- Keys on `CefriumChromeClient`: `shouldOverrideKeyEvent` (before the page;
  true keeps the key from it) and `onUnhandledKeyEvent` (keys the page did
  not cancel), as WebView's, with an Android `KeyEvent` (key code, action,
  meta state) instead of the engine's codes.
- Certificate errors, HTTP authentication and client certificates on
  `CefriumNavigationClient`: `onSslError` gets a `CefriumSslError` with the
  net error code and the server's certificate chain (`X509Certificate`), so
  an app can pin; `onHttpAuthRequest`; `onSelectClientCertificate`. Every
  callback answers from any thread, the first answer counts.
- The public API is checked at every release against a committed baseline
  (`cefrium_sdk/api/`), so an API change cannot ship unnoticed.

### Deprecated
- `RequestInterceptor` and `setRequestInterceptor` (use
  `CefriumResourceInterceptor`); `ResourceRequest.resourceType` and its
  `TYPE_` constants, which are the engine's numbers (use `getType()`).
  Removed at 1.0.
- `PermissionHandler`, `PermissionCallback`, `setPermissionHandler`, the
  `PERMISSION_` and `SETTING_` constants and `setContentSetting(String, int,
  int)`: use `CefriumChromeClient`, `CefriumPermission` and
  `CefriumContentSetting`. Removed at 1.0.
- The `setOnUrlChanged`, `setOnTitleChanged`, `setOnLoadingStateChanged`,
  `setOnFullscreenChanged`, `setOnFindResult` and
  `setOnRenderProcessTerminated` listeners, `CefriumDisplayHandler`,
  `CefriumLoadHandler` and the `TERMINATION_` constants: use the two
  clients. They keep working until 1.0, alongside the clients.
- `JsDialogHandler`, `setJsDialogHandler` and the `JS_DIALOG_` constants: use
  `CefriumChromeClient.onJsDialog`.
- `CefriumDialogHandler` and `setDialogHandler`: use
  `CefriumChromeClient.onShowFileChooser`.
- `OnContextMenuListener`, `setOnContextMenuListener` and
  `contextMenuCommand`: use `CefriumChromeClient.onContextMenu`.
- `CefriumKeyboardHandler` and `setKeyboardHandler`: use
  `shouldOverrideKeyEvent` and `onUnhandledKeyEvent`.
- `SslErrorHandler`, `HttpAuthHandler`, their setters and
  `CefriumRequestHandler.onSelectClientCertificate`: use
  `CefriumNavigationClient`.

### Changed
- **`onRequestIntercepted` is called on the UI thread** (`Callback` and
  `OnRequestInterceptedListener`), like the rest of `Callback`, with the
  request's whole URL. It ran on the network thread, so updating a view from
  it threw, and URLs were cut at 200 characters.

### Fixed
- An `HttpAuthHandler` was called on the network thread, though documented
  as the UI thread; it is called on the UI thread now.
- "Copy link" and "Copy image URL" in an off-screen browser with no activity
  copied nothing; they use the application's clipboard now. A context menu
  nobody shows is closed instead of left pending.
- **`setContentSetting` with `PERMISSION_CLIPBOARD` set the origin's
  notifications instead**: allowing the clipboard allowed notifications. It
  now sets the clipboard; an unknown permission or value throws
  `IllegalArgumentException` instead of setting notifications.
- Touch injected into a surface browser (`sendTouchDown`/`Move`/`Up`,
  `sendTap`) reaches the page as a finger: `pointerType` was empty.
- Javadoc: the units of injected touch and mouse input are now stated per
  mode. Late startup setters throw (the docs said they log a warning);
  `reload()` revalidates the cache (the docs said it ignores it);
  `setDarkMode` can be called right after creation.

## [0.20.0] - 2026-11-12

Engine: **Chromium 156.0.8078.88 / CEF branch 8078** (was 156.0.8078.80; Chrome's
security fixes of 2026-11-06).

### Added

- App content on an https origin the app owns, answered from Java with no
  socket: `CefriumProfile.registerContentServer(origin, server)`. Covers every
  browser of the profile, workers and service workers. Ready-made servers for
  assets, files and resources (`CefriumContentServers`) and path prefixes
  (`CefriumPathContentServer`), the counterparts of `WebViewAssetLoader`.
  Range requests and HEAD are answered for the server; POST bodies and request
  headers reach it; `withResponseHeaders` adds headers such as COOP/COEP for
  cross-origin isolation. MIGRATION-CEFRIUM.md has the WebView mapping.

### Changed

- `CefriumBrowser.setBackgroundColor` works on surface browsers, at any time;
  it threw `IllegalStateException` there before. The colour shows where the
  page draws no background and before the first frame (no white flash), and it
  stays across navigations. `0x00000000` makes the background transparent, so
  a page can be drawn over a game engine's `SurfaceView`. Off-screen browsers
  are unchanged.

### Fixed

- `<input type=file>` without a `CefriumDialogHandler` was always cancelled.
  A surface browser now shows the engine's chooser, as Chrome does (files,
  camera, filtered by the input's accept list); an off-screen browser shows
  the system picker on its dialog activity (`setDialogActivity`). Issue #22.
- A surface browser's intents and runtime permission requests (the chooser,
  the camera permission before it) never came back unless the app forwarded
  them, and permission results could not be forwarded at all. An AndroidX
  activity now delivers both through its result registry; another activity
  forwards `onActivityResult` and the new
  `CefriumBrowser.onRequestPermissionsResult`.
- A window from `onCreateWindow` sent the default User-Agent instead of its
  opener's `setUserAgentOverride`, and Google sign-in answered 500 when the
  two differed. It now keeps the opener's override from its first request.
  The docs now point to `setUserAgentOverride` instead of a
  `--user-agent-product` switch.
- A file a `CefriumDialogHandler` returned as a content URI reached the page
  named after the URI's last segment (a Downloads id is a number, with no
  extension); it now gets the provider's display name.
- **Off-screen: taps and clicks reach out-of-process iframes.** Input went
  to the page only, so an iframe in a process of its own never saw it:
  Google's "Sign in with Google" button (always isolated) did nothing
  off-screen. Issue #20.
- `setFocus` and `setDarkMode` called before the engine browser existed were
  dropped: on a window in `PopupHandler.onCreateWindow` (a sign-in page shown
  there took no typing), and on an off-screen browser before its first load.
  They now apply once it exists; a window's, at its first navigation.

## [0.19.0] - 2026-11-09

### Added
- **Popups that keep their opener.** `PopupHandler.onCreateWindow(popup,
  url, gesture)` gives a window the page opens a browser of its own, in the
  opener's mode and profile; the page keeps `window.opener`, so sign-in and
  payment popups can send their result back. `onCloseWindow` reports the
  popup's `window.close()`. Without it a popup still loads in the same
  browser and loses its opener (issue #20).
  In Compose: `CefriumView(onCreateWindow = ..., onCloseWindow = ...)` and
  `CefriumWindow(popup)` to show the window.
- **FedCM sign-in** (`navigator.credentials.get({identity})`, used by
  "Sign in with" prompts): the account chooser is shown, as a default dialog
  on the browser's activity or by `CefriumBrowser.setIdentityHandler`. It
  failed with `NetworkError` before (issue #20). New experimental CEF API:
  `CefIdentityRequestHandler`, `CefClient::GetIdentityRequestHandler`.
- **Printing.** `window.print()` opens Android's print dialog on the
  browser's activity (it did nothing), or goes to `setPrintHandler`.
  `createPrintDocumentAdapter(name)`, as in WebView, and `print()` give
  Android's print framework the page, printed by the engine with its print
  styles at the paper size and orientation chosen. `printToPdf` takes a paper
  size and orientation.
- **Text selection toolbar.** `setTextSelectionActionModeCallback` adds the
  app's own actions to the selection toolbar of a surface browser, next to
  copy, paste and share; `getSelectedText()` reads the selection.
- `CefDisplay` works on Android (it returned nothing): displays, scale factor
  and pixel conversion, through `display::Screen`.
- CEF's C/C++ API has Android platform types (`cef_types_android.h`,
  `cef_android.h`) instead of borrowing the Linux ones: window, cursor and
  event handles are opaque pointers, with no X11 types.

### Changed
- **Published on the Codeberg Maven registry only.** Maven Central stops at
  0.17.1: its publishing limits need a paid subscription for this project.
  Add `maven { url 'https://codeberg.org/api/packages/cefrium/maven' }` to
  `pluginManagement` and `dependencyResolutionManagement`. The registry now
  keeps this release and the one before it (it kept only the latest), so a
  pinned version has one release to move.
- The NFC permission type and the device chooser type are experimental CEF
  API (`CEF_API_ADDED(CEF_EXPERIMENTAL)`); unguarded, they changed the API
  hash of every versioned API.
- **The SDK is published as modules.** `cefrium-sdk-core` holds the Java API,
  resources and assets; `cefrium-native-codecs-<abi>` and
  `cefrium-native-libre-<abi>` each hold one `libcef.so`. `cefrium-sdk` and
  `cefrium-sdk-libre` stay the coordinates to depend on: they bring the core
  and that flavour's engine for every ABI, so a Maven build changes nothing.
  The single-file AARs are on the Codeberg release page, for apps that add the
  SDK as a file.
- The Gradle plugin stops a build that packages arm64-v8a or x86_64 without
  that ABI's `libcef.so`, which would fail at start on those devices.
  With Play Feature Delivery the engine can come from a dynamic feature
  module: the app may then package none, and the plugin applied to that
  module checks it there.
- **32-bit ARM (armeabi-v7a).** Cefrium now runs on 32-bit ARM phones, which
  are common in the low end on Android 10 and later. The engine is
  `cefrium-native-<flavour>-armeabi-v7a`. It comes with `cefrium-sdk` and
  `cefrium-sdk-libre` and is in the single-file AARs. Google Play delivers
  each device only its own ABI.
- On a device or emulator ABI with no engine in the APK (a 32-bit x86
  emulator image, for example), `Cefrium.initialize()` throws an
  `IllegalStateException` that names the ABI, instead of "the engine failed
  to start".
- The settings the engine reads at startup (`setDataDirectory`,
  `setNetLogFile`, `setComponentUpdatesEnabled`, `setGpuProcessEnabled`,
  `setLibraryPrefetchEnabled`, `setNetworkTimeQueriesEnabled`,
  `setDrmPreprovisioningAtStartupEnabled`) throw `IllegalStateException` when
  called with another value after the engine started. They were ignored with
  a log line. The value already in effect is still accepted, so setting them
  in every `onCreate` keeps working.
- **Call `CefriumBrowser` on the main thread.** Every method now throws
  `IllegalStateException` on another thread, as Android WebView does, except
  `executeDevToolsMethod`, which posts itself. Off the main thread a call
  raced the engine: `onPause`, `onResume`, `suspendMedia`, `resumeMedia` and
  `stopMediaSession` crashed the app, and others could.
- A method called after `close()` does nothing and logs a warning, instead of
  reaching the engine while the browser closes.
- `CefriumProfile` methods that return a result throw `IllegalStateException`
  off the main thread (`setDownloadObserver` crashed there; the proxy, WebRTC
  and ad-block settings returned false). `getDownloads`, like `listSiteData`,
  can be called from any thread and answers on the main thread.
- Compose: `CefriumNavigator` and `CefriumBrowserPool` throw off the main
  thread. A navigator call made before `CefriumView` has a browser now runs
  once it does, instead of being dropped; input calls still need a page.
- `setUserAgentOverride` and `setJavaScriptEnabled` are now browser settings,
  as in Android WebView, instead of DevTools emulation. The User-Agent holds
  for every later navigation, including the ones a page starts.

### Fixed
- `onTitleChanged` reports each title of a page once. CEF reported it again
  when loading stopped, so an app that acted on a title acted twice.
- Configuration called right after the off-screen constructor, before the
  browser exists, applies from the first page: `setUserAgentOverride`,
  `setJavaScriptEnabled` and `setContentSetting` were dropped without a
  word, and `executeDevToolsMethod` failed with "the browser does not exist
  yet". DevTools calls made then run once the first page has committed.

## [0.18.0] - 2026-11-07

### Added
- `CefriumBrowser.executeDevToolsMethod(method, paramsJson, callback)` and
  `setDevToolsEventListener`: the Chrome DevTools Protocol in process, with
  results and events, no remote debugging port. Built on CEF's
  `ExecuteDevToolsMethod` and `AddDevToolsMessageObserver`.
- `CefriumBrowser.evaluateJavaScript(script, ValueCallback<String>)` returns
  the script's value as JSON, like `WebView.evaluateJavascript`.
- `Cefrium.setDrmPreprovisioningAtStartupEnabled(boolean)`.
- `Cefrium.setRemoteDebuggingEnabled(boolean)`: Chrome's DevTools over adb
  (`chrome://inspect`), like `WebView.setWebContentsDebuggingEnabled`. Off by
  default.

### Changed
- **Update the `com.cefrium` Gradle plugin together with the SDK.** The
  engine's assets are renamed (`icudtl.dat+com.cefrium+` and so on), and the
  0.18.0 plugin keeps the new names uncompressed. With an older plugin,
  `Cefrium.initialize()` throws an `IllegalStateException` that says so. The
  `noCompress 'dat', 'pak', 'bin'` block in an app's build is no longer
  needed.
- `addDocumentStartJavaScript` throws instead of failing silently or crashing:
  `IllegalArgumentException` for an empty rule set (it matches no origin; pass
  `"*"` for every origin), `IllegalStateException` off the main thread or
  after `close()`. Both of those used to kill the app in native code (#18).
- The engine is built as a stable channel release. Web Push subscriptions
  now point at `fcm.googleapis.com`; before, every subscription got FCM's
  staging endpoint (`jmt17.google.com`), because the build reported an
  unknown channel. An existing subscription reports the new endpoint from
  `getSubscription()`; a server keeps the one it stored until the page sends
  it again.
- The engine no longer asks Google's provisioning service for Widevine origin
  IDs a minute after start. It provisions when a page first uses Encrypted
  Media Extensions, so only apps that play protected media contact it.
  `setDrmPreprovisioningAtStartupEnabled(true)` restores the old behaviour.

### Fixed
- Apps using the SDK got `READ_PHONE_STATE`, `READ_EXTERNAL_STORAGE` and
  `WRITE_EXTERNAL_STORAGE` added to their manifest. The SDK's manifest had no
  `targetSdkVersion`, so the manifest merger took it for a library older than
  API 4 and added the permissions such a library implies. Apps that declared
  them for themselves keep them.
- With media controls on (`setMediaSessionEnabled(true)`), an app that had
  removed `FOREGROUND_SERVICE` from its manifest (or, on Android 14 and newer,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`) was killed a few seconds after a page
  started sound. The sound now plays without the system controls.
- Web notifications were never shown in an app that adds the SDK AAR as a
  file: `showNotification()` resolved, but the engine failed on a class from
  `androidx.browser`, which only the Maven POM declared. The AAR now carries
  the POM's androidx list and the Gradle plugin adds it, as it already did for
  the other libraries.
- A `window.cefriumQuery` call made while a page or iframe was loading could
  fail with error -403 although the policy allowed the page: the call can
  reach the app before the browser has seen the page commit, and was judged
  as coming from the previous document. It is now judged once the commit
  arrives.
- Unsubscribing from Web Push left the subscription's message route behind
  in the app's preferences.
- A Web Push subscription could be dead from the start (410 on every
  message): when the app died while a page was unsubscribing, the
  subscription's id outlived it, and the next subscription reused it. With
  another `applicationServerKey`, that one never received anything. The id
  is now removed first, and the subscription only once that is on disk.
- The startup prefetch check catches a missing platform method too, so a
  device whose system lacks one starts without the prefetch instead of
  stopping at launch.
- A request interceptor (and ad-block site exceptions) could see an empty
  `documentUrl`, or the previous page's, for a page's first subresources:
  they can start before the browser has seen the page commit. The document
  is now checked against the origin the request came from, and reported as
  that origin when they differ.
- The Gradle plugin stops a compile on a JDK older than 25 with a message
  that names the JDK and links the toolchain guide, instead of javac's
  "class file has wrong version 69.0" among "cannot find symbol" errors.
- Android WebView and Cefrium now run in one process in either order. With
  Cefrium first, the first `new WebView()` killed the app: the engine hooks
  `FindClass` on the main thread (Chromium does it for the Cardboard SDK) and
  the hook answered WebView's class lookups with the engine's own classes. It
  now asks the caller's class loader first. With WebView first, an older
  WebView (74 in our test) added its own `icudtl.dat` to the app's assets and
  Cefrium read it and stopped at start; the engine's assets now carry a
  suffix, as WebView's do. The `webview-same-process` sample runs each order.
- Using Cefrium from another process of the app (`android:process`) threw
  "Cefrium is not set up in this process". The message now names the process,
  says the engine runs in the main process only and links the guide.


## [0.17.1] - 2026-11-05

### Security
- **Engine updated to Chromium 156.0.8078.80** (from 156.0.8078.73), the
  newest build on CEF branch 8078, with the fixes Chrome released since
  0.17.0. Same CEF branch; no API change.

### Fixed
- Apps crashed at launch on Android 15 with `NoSuchMethodError:
  ApplicationStartInfo.getStartComponent()` (issue #17). The startup
  read-ahead added in 0.17.0 called an Android 16 method on 15. On 15 it now
  uses the start reason, so the read-ahead still runs for an Activity start.
  Thanks to Kairlec for the report and the analysis.

### Known issues
- A Web Push subscription made after unsubscribing can be dead at FCM (410
  on every message) when the app died while the page was unsubscribing and
  the page then subscribes with another `applicationServerKey`. We first
  withdrew this note as a fault in our test; the test had a second fault, but
  this one is in the SDK. Fixed in 0.18.0.

## [0.17.0] - 2026-10-29

### Security
- **Engine updated to Chromium 156.0.8078.73** (from 156.0.8078.17), the
  version Debian ships, with the security fixes Chrome released for 156 since
  0.16.0. Same CEF branch (8078); no API change.

### Added
- Off-screen browsers report text-selection handles whole:
  `CefriumRenderHandler.onTouchHandleChanged(TouchHandle)` gives each handle's
  id, orientation, position, opacity and whether to draw it, and
  `TouchHandle.SIZE` is the area a touch must start in to drag it. Draw the
  handles and forward touches with `sendTouchEvent`; a drag that starts on a
  handle moves that end of the selection. The OSR IME sample draws them.
- `RequestInterceptor.shouldBlockRequest(ResourceRequest)` gives the
  request's resource type, the document that made it and the page in the
  main frame. Override it instead of the three-argument form, which it calls
  by default; an app no longer has to track the top page itself.
- `QueryHandler.onQuery` has a form with `isMainFrame`, and Compose has
  `CefriumBridge.withFrames`, so a handler whose policy admits iframes
  (`withSubframes()`) can tell them from the page.
- Guide: "Public API, not engine internals", the calls that replace the
  Chromium internals apps reach for.

### Fixed
- `onTouchHandleStateChanged(orientation, x, y, enabled)` reported every
  handle as hidden once it moved, and could not say which handle it was: the
  engine sends one field per update and the others arrived as zero. It now
  carries the handle's whole state; it is deprecated for
  `onTouchHandleChanged`.

### Changed
- The startup read-ahead of libcef.so reads, on arm64, only the parts of the
  file a cold start uses, in the order it uses them, from a list each release
  records on a device and ships in the AAR under the library's build-id; any
  other library is read whole, as before. When Android starts the process to
  show an Activity (Android 15+), the read begins at process start instead of
  with the engine; a process started for a push message or a job still reads
  nothing. Cold start to the first page, against 0.16.0: 200 ms sooner on a
  phone with slow storage, 50-65 ms on one with fast storage; warm unchanged.
- `setLibraryPrefetchEnabled(false)` before the engine starts also stops a
  read already under way, so turning it off in `Application.onCreate` still
  works now that the read can begin before it.
- `setNetLogFile` documents that the file is browsing history.

## [0.16.0] - 2026-10-28

### Privacy
The engine no longer contacts Google on its own. Earlier versions did, in
three ways our network audit had missed:
- every profile asked accounts.google.com which Google accounts its cookies
  held, for a sync metric (Gaia's ListAccounts);
- Chrome's AI Mode asked www.google.com whether the user was eligible,
  sending the app's language and country;
- the engine kept a connection open to www.google.com, the default search
  engine, for omnibox searches, renewed on a timer.

0.16.0 does none of these: Google sign-in is off on Android, as CEF intends,
and AI Mode and the search engine preconnect are disabled. Updating is all it
takes. With a proxy set, all three went through it. Each release now checks,
with the engine's own NetLog, that the engine contacts nothing but the
documented component updates.

### Upgrading from 0.15.0
The public API only grows: an app built against 0.15.0 builds and links
against this release unchanged. Three things may need a change:
- An app that blocks YouTube ads calls `Cefrium.loadYouTubeAdRules()`: the
  rules are no longer added with the first filter list.
- An app that declares its own `android:zygotePreloadName` keeps it with
  `tools:replace="android:zygotePreloadName"`, since the SDK now declares one.
- `onFocusedNodeChanged` reports `text` for an `<input>` without a `type`,
  and every type in lower case; code that compared it with `"INPUT"` needs
  the new value.

### Added
- `Cefrium.setNetLogFile(file)` records the engine's network activity in
  Chromium's NetLog format: each request, the proxy chosen for it, DNS lookups
  and every socket with its address and bytes. Cookies and credentials are
  left out. The network-privacy sample uses it to check that nothing goes
  around a proxy.
- `CefriumBrowser.evaluateJavaScriptInFocusedFrame(script)` runs a script in
  the frame that has focus. `evaluateJavaScript` runs in the main frame, whose
  `document.activeElement` is the iframe when the focused field is inside
  one, so an app that writes a field's value from its own text field could
  not reach a field in a frame (issue #9).
- `CefriumBrowser.setMediaPlaybackRequiresUserGesture(false)` lets a page
  start sound on its own, as the WebView setting of the same name. By default
  an unmuted `autoplay` or `play()` without a tap is rejected with
  `NotAllowedError` (issue #16).
- `CefriumBrowser.setAdBlockEnabled` turns ad blocking off or on per browser.
- `Cefrium.downloadAndLoadAdBlockList(context, url, callback)` for any
  EasyList-format list, with a result callback; `downloadAndLoadEasyList`
  gains a callback overload.
- Filter lists now honour `@@` exception rules, `@@||site^$document` (no
  blocking on that site) and the options third-party, domain= and the
  resource types.

- `CefriumProfile.setProxy` / `clearProxy` and
  `setWebRtcIpHandlingPolicy`: the proxy and WebRTC policy of a profile,
  which every browser of it uses. They can be set before any browser of the
  profile exists. `CefriumProfile.WEBRTC_DISABLE_NON_PROXIED_UDP` keeps WebRTC
  from revealing the device's address around a proxy.
- `CefriumProfile.incognito().followDefaultProfile()` drops the incognito
  profile's own proxy and WebRTC settings.
- `CefriumProfile.getDownloads(callback)`: every download of the profile,
  including earlier runs' (the engine keeps them in the profile's history),
  each with `pause()`, `resume()`, `cancel()` and `remove()`. A download the
  app was closed in the middle of comes back interrupted and resumes from
  where it stopped if the server allows it.
  `CefriumProfile.setDownloadObserver` reports every change to a download of
  the profile, whichever browser started it and whether or not it is still
  open.
- `CefriumProfile.setAdBlockExceptions(sites)`: sites where the profile's
  browsers block nothing, by host (subdomains included) or by origin, as a
  `@@||site^$document` rule would. Incognito has its own.
- `CefriumProfile.setProxyErrorListener`: told when a request of the
  profile's browsers fails because of the proxy (unreachable, refused, asks
  for credentials, a SOCKS or PAC failure), with the browser, the URL and the
  error, so an app can tell its user that Tor or its VPN is down.
- `Cefrium.setSecureDns(mode, templates)`: Chrome's secure DNS (DNS over
  HTTPS) for every browser of the app, off, automatic or secure with the
  app's own servers. In secure mode names that the servers cannot resolve do
  not resolve at all.
- `DownloadItem.isInterrupted()`: the download stopped on an error, which
  `getInterruptReason()` names. Before, a failed download looked like one
  still in progress.
- `CefriumBrowser.addDocumentStartJavaScript(script, allowedOriginRules)`
  runs a script in every matching frame before the page's own scripts, as
  the WebView method of the same name. It is kept across navigations and
  removed with the returned handler.

- Faster cold start: the engine's library is read ahead on a background
  thread while it loads, so the first page runs 155-340 ms sooner (about 17%)
  when `libcef.so` is not in the page cache, measured on two phones. A warm
  start is not slowed: the thread stops at once when the file is cached.
  `Cefrium.setLibraryPrefetchEnabled(false)` turns it off.
- arm64 builds use the PGO profile Chrome ships: `libcef.so` is 15 MB
  smaller, and a cold start 50-100 ms faster on the slower of the two phones.
- Renderers fork from an app zygote that has `libcef.so` loaded, as in
  Chrome, and share the relocated library: 4-8 MB less memory per renderer
  on a W90. An app with its own `android:zygotePreloadName` keeps it with
  `tools:replace`.
- The engine starts the first time the app uses it (a browser, a profile, a
  filter list) instead of at every process start. A process woken for a
  push message, a job or a broadcast that opens no browser no longer loads
  it: on a W90 it starts in 85 ms with 20 MB instead of 550 ms with 160 MB.
  A cold start to the first page is unchanged. `Cefrium.initialize(context)`
  still starts it ahead, and the settings that must precede it
  (`setComponentUpdatesEnabled` and the like) now also work from
  `Application.onCreate`.

- `Cefrium.getRendererServices()`: which renderer services the engine
  launches on this device (native javaless ones on Android 17+, or the Java
  ones) and how many the app declares, so a build can be checked without
  reading engine internals.

### Changed
- The engine no longer turns on Chromium's field-trial testing config, the
  experiments Chrome tries before shipping them: Chrome for Android never
  applies it, and on Android it enabled 598 features Chrome does not. The
  engine now behaves as Chrome's release does.
- The YouTube ad heuristics are no longer added with the first list: call
  `Cefrium.loadYouTubeAdRules()` to keep them. They are also limited to
  YouTube's own hosts; as bare URL substrings they matched any site.
- A rule with an option the filter cannot apply (`$popup`, `$csp`, ...) is
  skipped. It used to be applied to every request.
- `||host/path` rules match the start of the path, as Adblock Plus defines
  them, not any substring.
- Loading the same list again replaces its rules instead of adding them twice.
- A main-frame navigation is never blocked by a list.
- Licence headers: Cefrium's own sources carry the SPDX dual-licence header,
  and `REUSE.toml` covers Compose, UnifiedPush, the relay and the tests. The
  Android platform code and the Chromium patches stay under CEF's BSD
  licence, since they are meant for upstream.

### Fixed
- The engine no longer contacts Google on its own. Every profile asked
  accounts.google.com for its signed-in accounts (`ListAccounts`, for a sync
  metric), Chrome's AI Mode checked eligibility with www.google.com, sending
  the locale and country, and the engine preconnected to the default search
  engine (www.google.com) on a timer. Google sign-in is now off by default on
  Android, as CEF intends on desktop, and AI Mode and the search engine
  preconnect are disabled.
- Web Push subscriptions no longer break when Android kills the app in the
  middle of a subscribe or unsubscribe. A subscription's record was written
  to disk on a timer: a new subscription could lose it, and its messages were
  dropped and the site unsubscribed; or an old one kept it, and the next
  subscription reused an ID already deleted on the push service, so it never
  received anything. Both steps now continue only once the record is on disk
  (patch `android/push_lost_subscriptions`).
- Opening a named profile after a download had started killed the app
  (Safe Browsing kept one download telemetry service for all profiles and
  replaced it while it was in use). Patch `android/multi_profile`.
- Closing a browser canceled the downloads it had started, as if the user
  had: its network requests were aborted with it. A request whose response
  is already on its way now finishes, so a download continues, as in Chrome.
- Downloads restored from the profile's history at startup were canceled
  for having no browser, which deleted the partial file of one a restart had
  interrupted. A resumed download also keeps writing its own file; resumed
  with no browser it was saved again, from the start, in the public
  Downloads folder.
- A download the app allows without a path (`callback.allow(null)`, or no
  download handler) is saved in the app's own Downloads directory,
  `getExternalFilesDir(DIRECTORY_DOWNLOADS)`. It went to `/sdcard/Download`
  on-screen and to the filesystem root off-screen, where the app may not
  write, so it failed.
- Off-screen browsers fling: a quick swipe keeps scrolling after the finger
  lifts. Before, the fling never advanced, so the page stopped under the
  finger and the first tap after any scroll was taken as the one that stops
  the fling and was lost, e.g. on a field reached by scrolling (issue #9).
  Patch `android/osr_fling_scheduler`.
- `onFocusedNodeChanged` reports `text` for an `<input>` without a `type`,
  and lower-case names throughout, as documented. It reported `INPUT`.
- A `CefApp` given to `CefInitialize` no longer crashes the browser process:
  its `CefBrowserProcessHandler` gets `OnContextInitialized` as on other
  platforms.
- `CefBrowserHost::CreateBrowser`, `CreateBrowserSync` and
  `GetBrowserByIdentifier` are CEF's own on Android instead of stand-ins:
  `CreateBrowser` waits for the profile and creates the browser
  asynchronously, and `GetBrowserByIdentifier` no longer returns null.
- A Web Push message that started the app could be dropped, and its
  subscription removed, when it arrived before Android's notification
  channels had been read: the permission looked unset. Messages now wait for
  the content settings once (patch `android/push_lost_subscriptions`).
- An app that declares `android:zygotePreloadName` no longer has every
  renderer crash at start: the engine took the app zygote for the browser
  process and the renderers forked from it had no entry point.
- A proxy set on the default profile now covers private browsers too, as in
  Chrome: the incognito profile ignored it and connected directly. It follows
  the default profile's proxy and WebRTC policy unless given its own.
- `CefriumBrowser.setProxy` right after creating a browser returned false and
  did nothing, because the browser did not exist yet; it now sets the proxy of
  the browser's profile and applies before the first navigation. It always
  applied to the whole profile, which the documentation now says.
- The ad-block list download no longer bypasses the engine's proxy: it goes
  through the engine's network stack, so it follows `setProxy` instead of
  connecting directly. A download that fails, or returns something that is
  not a filter list, no longer replaces the last good copy.
- Ad blocking: the filter was read on the network thread while a list was
  loading on another, and the blocked counter lived in memory freed when the
  browser closed. Both are now safe.
- A request that no Java listener observes no longer calls into Java.

## [0.15.0] - 2026-10-26

### Added
- Web Push without Firebase. `CefriumPushTransport` lets an app serve its
  pages' push subscriptions with another push service: the page gets the
  transport's endpoint, and the transport hands each message, still
  encrypted for the page, to `CefriumPush.deliverMessage`. The new
  `com.cefrium:cefrium-unifiedpush` library is one for UnifiedPush:
  `CefriumUnifiedPush.install(this)` in `Application.onCreate()` and
  subscriptions go through the distributor the user chose (ntfy, NextPush),
  so Web Push works on devices without Google Play services. Sample:
  `cefrium-sample/unifiedpush-demo`.
- Web Push through the app's own Firebase project. `CefriumRelayTransport`
  sends subscriptions to `cefrium-relay` (in `cefrium_relay/`), a stateless
  Web Push service the app developer runs: each endpoint carries the
  subscription sealed to the relay's key, the relay checks VAPID and forwards
  the message through FCM, and `CefriumPush.handleMessage` delivers it. This
  replaces the Chromium push channel Google provides for testing.
- A push without a payload sent through a transport is delivered as one.
- `CefriumBrowser.setSize(width, height)` resizes an off-screen browser; it
  kept its constructor size before.
- `CefriumPickerHandler` (`setPickerHandler`) shows the `<select>`, date/time
  and colour pickers of an off-screen browser, which has no window for
  Chromium's dialogs. `setDialogActivity(activity)` gives it default ones,
  and default JS dialogs, instead.

### Fixed
- Off-screen: `<video>` showed no frames (H.264 also stalled after the first
  frame). The view never registered its frame sink, so the video's could not
  be embedded; the same gap kept frame tokens from returning, which the text
  selection fix of 0.9.2 had worked around. Issue #16.
- Off-screen: entering fullscreen from the media controls crashed the
  browser process in the screen orientation lock. Issue #16.
- Off-screen: `<select>` and the date/time inputs did nothing when tapped.
  Issue #16.
- `<input type=color>` did nothing, in any mode; it now opens Chromium's
  colour picker.
- The SDK AAR lost 1311 Android resource entries when it was slimmed (a
  name defined for night mode dropped its day definition), among them
  colours Chromium's colour picker needs.

## [0.14.0] - 2026-10-25

Engine: **Chromium 156.0.8078.17 / CEF branch 8078** (unchanged from 0.13.0).

### Fixed
- The engine no longer keeps its persistent data in the app's cache
  directory. Cookies, history, local storage, IndexedDB, service workers and
  autofill all lived under `getCacheDir()`, which Android may clear when
  storage runs low, and with it every signed-in session. They now live in the
  app's private `app_cefrium` directory. On the first start the existing data
  is moved there once, so users keep their sessions; if the move fails it is
  undone and retried on the next start.
- The engine no longer crashes at startup on Android 17. There Chromium runs
  renderers as native services with no Java runtime, which the SDK did not
  declare, and the first page load threw `NameNotFoundException` for
  `NativeOnlySandboxedProcessService0`. They are now supported: libcef.so
  exports their entry point and initializes CEF in them, so the renderer
  bridge works there too. Their declarations need `compileSdk 37`, so the
  `com.cefrium` Gradle plugin adds them when the app compiles against 37 or
  later; an app below that keeps compiling and gets the Java renderers.
- Data written in the last seconds before the app goes to the background is
  no longer lost if Android then kills the process. The engine writes
  preferences, cookies and storage in batches, and nothing flushed them when
  the app was backgrounded; site permissions, a fresh login cookie or a push
  subscription could vanish. Every loaded profile is now flushed when the last
  activity stops, as Chrome does.

### Added
- `Cefrium.setDataDirectory(File)` chooses where that data lives. Call it
  before the first browser is created.
- Named profiles: `CefriumProfile.get("work")` keeps its own cookies,
  storage and cache in `profile-<name>` under the data directory, and several
  can be open at once. Pass one to `createWithSurface(activity, profile)`, the
  off-screen constructor, or `rememberCefriumViewState(profile = ...)`.
  `CefriumProfile.delete(name)` refuses while a browser of the profile is open
  and, once the profile has been used in this run, deletes it on the next
  start.
- Per-site data: `CefriumProfile.listSiteData(callback)` reports each site
  with its storage size and cookie count, the figures Chrome's per-site
  storage screen shows, and `clearSiteData(site, done)` removes that site's
  cookies, local and session storage, IndexedDB, Cache Storage, service
  workers and the other per-site stores. The HTTP cache is not attributed per
  site by Chromium and is in neither.
- Off-screen browsers can be incognito:
  `new CefriumBrowser(width, height, scale, CefriumProfile.incognito())`.
- Web Push delivery: an app passes its Firebase messages to
  `CefriumPush.handleMessage(context, from, extras)`, which hands the Web Push
  ones to the engine and returns false for the app's own. An app without a
  `FirebaseMessagingService` of its own enables `CefriumPushService` in its
  manifest instead. Each message wakes the service worker of the profile that
  subscribed, and starts the app if it is not running; if that profile is not
  open, the message is kept until it is. Messages travel on FCM's
  pre-production Web Push channel, the one every Chromium build that is not
  Google Chrome gets.

## [0.13.0] - 2026-10-23

Engine: **Chromium 156.0.8078.17 / CEF branch 8078** (unchanged from 0.12.0).

### Changed
- `window.cefriumQuery` answers the main frame only, whatever the origin
  policy. An allowed page can be framed by any site, and its iframe used to
  reach the handler from inside that site. `QueryOriginPolicy.withSubframes()`
  opts a policy into subframes, and even then a subframe is admitted only when
  the policy allows its main frame too. The check runs in the browser process
  on the frame tree, not on anything the page reports.

### Migration
- If your pages call the bridge from an iframe, add `.withSubframes()` to the
  policy, and make sure the policy also allows the page that holds the iframe.
  `QueryHandler.onQuery` keeps its signature.

## [0.12.0] - 2026-10-22

Engine: **Chromium 156.0.8078.17 / CEF branch 8078** (unchanged from 0.11.0).

### Added
- A ready-to-paste attribution notice in the README and at
  cefrium.com/policy, with what the LGPL requires and what is optional.

### Changed
- The SDK no longer carries its own copy of libraries published on Maven.
  11,391 classes from 49 artifacts (Guava, Material, protobuf-javalite, Google
  Play services, Firebase, ML Kit, Lottie, Play Core) are now dependencies in
  the POM, at the versions Chromium builds with. An app that also uses one of
  them no longer fails with `Duplicate class`, and Firebase finds its
  components.
- The list comes from Chromium's own prebuilts (`maven-deps.txt`, generated by
  `maven_vendored.py`), and the build fails if it goes stale after an engine
  bump. The AAR carries the same list, and the `com.cefrium` Gradle plugin adds
  it for apps that use the AAR as a file.
- The AAR no longer carries copies of those libraries' resources either (884
  files, 4,146 values entries identical to the libraries' own). A copy pinned
  to Chromium's version could override a newer one the app chose. Entries
  Chromium changes stay.

### Migration
- Remove `exclude group: 'com.google.guava'` and `exclude group:
  'com.squareup.okio'` added for Cefrium. They now remove libraries the engine
  needs at run time.
- Remove Firebase manifest entries copied from the push sample
  (`ComponentDiscoveryService`, `FirebaseInstanceIdReceiver`,
  `FirebaseMessagingService`) and add `firebase-messaging` as usual.

### Fixed
- An app that enables R8 now gets it. The AAR's consumer rules kept every
  `org.chromium` class and carried Chrome's own APK policy, so R8 optimized,
  obfuscated and shrank about 10% of an app, below the 25% Google Play asks
  for. Measured on the browser app: 10% before, 65% after in all three. The
  policy also removed the app's own `Log.v/d/i` calls and declared
  `String.format` and `Throwable.addSuppressed` side-effect free in app code.
- A minified app no longer dies at startup with `AbstractMethodError` on
  devices that ship the Jetpack window extensions (a W90 tablet is one). The POM now declares
  `androidx.window.extensions.core:core`, which R8 needs to compile a
  Chromium callback.
- The release gate builds and runs a minified app, so a missing keep rule
  blocks the release.

## [0.11.0] - 2026-10-21

Engine: **Chromium 156.0.8078.17 / CEF branch 8078** (unchanged from 0.10.2).

API change: `setQueryHandler` now takes a `QueryOriginPolicy`, and the
Compose `CefriumView` takes a `CefriumBridge` instead of `onQuery` (migration
under Changed). New: `Cefrium.setGpuProcessEnabled` and
`CefriumBrowser.setBackgroundColor` for transparent off-screen rendering.
Everything else is a fix to what the SDK already shipped.

### Security

- **A bridge query is judged by the document that sent it.** A page could
  send `window.cefriumQuery` calls through an iframe's initial empty
  document right after pointing the iframe at an allowed origin. The browser
  handled many of them after the iframe had committed the allowed page, and
  judged them as that page: measured 152 of 200 reaching the handler under
  the allowed identity. Every renderer-to-browser process message now carries
  the origin of the document that sent it, stamped at send time. The bridge
  denies a query whose sender is not the document the frame holds when the
  query is handled. Same-origin pages, `srcdoc`, bundled assets and opaque
  documents are unaffected: their stamp equals the committed origin. The check
  runs before the policy, so it applies under `anyOrigin()` as well.

- **Renderers now fork from a hardened app zygote**, reaching seccomp parity
  with the system WebView: `NoNewPrivs 1` with 4 filters on both, measured
  side by side on one tablet. Earlier releases read 2. Declared with
  `android:useAppZygote="true"` on the first sandboxed service, as upstream
  Chrome declares it. Held back once because input stopped reaching a windowed
  renderer with it on; see the fix below, which was the actual cause.

### Fixed

- **The OSR frame byte order was documented wrong.** `onPaint` and
  `onPopupPaint` deliver RGBA (premultiplied), not BGRA as the javadoc and
  README said. An integrator who swapped red and blue to follow the docs got
  the colours inverted: a red element drew blue. Code that copies the buffer
  straight into an ARGB_8888 `Bitmap` or a `GL_RGBA` texture was already
  right. `CefriumOsrTransparencyTest` now asserts the byte order.
- **The system WebView and Cefrium work in one package**, in separate
  processes. Both bound `org.chromium.content.app.SandboxedProcessService0`,
  and Android keys an external isolated service by the calling package, class
  and instance. Measured with WebView started first: Cefrium was handed
  WebView's renderer process and its page never painted (`Child process's
  classpath doesn't match`). Both start orders are now tested. Cefrium's child services now have their own class names,
  `com.cefrium.ChildServices$Sandboxed0..39` and `$Privileged0..2`, which
  Chromium reads from new manifest meta-data (patch
  `android/child_service_names`). The SDK manifest declares everything. If your
  app's manifest declared Chromium's service classes itself, remove that block:
  it is unused now, and its `NUM_SANDBOXED_SERVICES` would still cap the pool.
  One process with both engines stays unsupported.
- **A surface-mode browser no longer waits a fixed 2 seconds to join its
  window.** `CefriumBrowser` connected its WebContents to the WindowAndroid on
  a 2000 ms timer after the first `loadUrl`. Until that connection no renderer
  of the browser receives BeginFrames, so a page could neither paint nor leave
  Chromium's first-paint holding, and Blink suppresses every non-move input
  event meanwhile: a click or tap in that window was dropped. With renderers
  that start fast (the app zygote) the two input tests landed in that gap and
  failed; with slow process launches the delay hid behind them. The connection
  now happens as soon as the native browser exists, notified from
  `OnAfterCreated`. Every browser opens about 2 s sooner; the on-device suite
  runs in 54 s instead of 93.
  Decision: the Java reference the notification needs is bound at creation,
  not when the embedder registers its first listener, so an app that sets no
  listener still connects.
- **Client hints report the engine version in every app**, not only in apps
  that let the SDK do the process setup. An app whose own `Application` ran
  the Chromium plumbing (the pattern documented before 0.10.2) skipped the
  engine-version declaration, so `Sec-CH-UA` and `navigator.userAgentData`
  carried the app's `versionName`: measured brand `1`, `uaFullVersion` `1.0`
  in an app at `versionName "1.0"`. The declaration now runs on every
  initialization path, once, before the engine first reads it.
  `CefriumEngineIdentityTest` asserts the User-Agent, the brand and
  `uaFullVersion` against `Cefrium.getChromiumVersion()`.
- **Closing the browser that is on screen no longer kills the app.** After
  `close()`, the browser's views stayed in the container, and the next layout
  (a rotation, the keyboard, any resize) called `setSize` on the destroyed
  WebContents: `IllegalStateException: Native WebContents already destroyed`,
  uncaught, on the main thread. The views now leave the container before
  anything is destroyed, the order Chromium's own content_shell uses.
- **`setPullToRefreshEnabled` takes effect whenever it is called**, and can be
  toggled. It used to bind its handler only if called before the WebContents
  connected; with the connect now at creation, an app enabling it after its
  first navigation (as the browser app does) would have lost the feature.

### Added

- **`CefriumBrowser.setBackgroundColor(int argb)`: transparent off-screen
  rendering.** Pass `0x00000000` before the first load of an off-screen browser,
  and page areas the page leaves transparent arrive in `onPaint` with alpha 0,
  for a HUD or overlay drawn over other content. Every pixel used to arrive
  opaque, whatever the page did. The default stays opaque white. Called after
  the first load, or on a surface browser, it throws `IllegalStateException`
  rather than doing nothing.
- **`Cefrium.setGpuProcessEnabled(boolean)`**, to run the GPU in its own
  process instead of inside the browser process. Off by default, which is what
  the SDK has always done, except that until now it was not a decision: the
  `--in-process-gpu` switch had been there since the first SDK commit with no
  reason recorded. GL command buffers and the vendor's graphics driver
  therefore share a process with the embedder's code and data, which is a
  weaker boundary than desktop Chrome, where the GPU is its own process.
  Enabling it moves the GPU into `:privileged_process0`. Measured on a
  1920x1200 kiosk tablet showing one page, total PSS across the app's
  processes: 591 MB in-process against 715 MB out, about 21% more, plus one
  more process for Android's low-memory killer to choose from. The on-device
  suite is green in both modes.
  Decision: opt-in rather than default, because most of this SDK's deployments
  are dedicated hardware with 4 GB or less, and a 21% memory increase there is
  a real regression. The security argument wins when the device has memory to
  spare and the content is not fully under the embedder's control.

### Changed

- **`window.cefriumQuery` only reaches your handler from origins you allow.**
  `setQueryHandler(handler)` is replaced by
  `setQueryHandler(QueryOriginPolicy policy, QueryHandler handler)`, and the
  Compose `CefriumView` parameter `onQuery` is replaced by
  `bridge = CefriumBridge(policy) { request, origin, callback -> ... }`. Both
  forms fail to COMPILE without a policy; an app that forgets it does not
  crash at launch instead. A call from an
  origin the policy does not allow fails on the page with
  `QUERY_ERROR_ORIGIN_DENIED` (-403, `origin not allowed`) and never reaches the
  handler. Policies: `exactly(...)`, `loopback()`, `bundledAssets()`,
  `anyOrigin()` (logs a warning), or your own predicate over the frame URL.
  Migration: add the policy argument. `anyOrigin()` keeps the old behaviour
  exactly, and is the wrong choice for anything that loads pages you do not
  control.
  Decision: the policy is required at compile time, not defaulted, because the
  bridge is the one path from page content into application code. The handler
  always received the calling frame's URL and the javadoc said to check it;
  nothing made anyone. Our own browser app was the proof: its bridge exists
  for an observer injected into YouTube, and any site could call it.
  A frame whose committed origin is opaque is denied before any policy but
  `anyOrigin()` runs, even at an allowed URL: an `<iframe sandbox>` or a page
  served with `Content-Security-Policy: sandbox` is content its embedder chose
  not to trust, and the URL alone would have let it through.

### Fixed

- **Documented where the engine settings must actually be called from.**
  `setComponentUpdatesEnabled`, `setNetworkTimeQueriesEnabled` and the new
  `setGpuProcessEnabled` all said "before `initialize()`", which is true and
  not enough: the SDK initializes from a `ContentProvider`, and Android creates
  providers after `Application.attachBaseContext` but before
  `Application.onCreate`. A setter called from `onCreate` is already too late,
  logs a warning and does nothing. The javadoc now names
  `attachBaseContext`, and the kiosk-pos sample shows the shape. This affected
  the network opt-outs shipped in 0.9.3, so an embedder who disabled component
  updates from `onCreate` was not getting what they asked for.

## [0.10.2] - 2026-10-20

Engine: **Chromium 156.0.8078.17 / CEF branch 8078** (unchanged from 0.10.1).

No API change. Both items below are fixes to what the SDK already shipped.

### Security

- **Renderer processes now install Chromium's seccomp-BPF policy.** Every
  Cefrium entry point set `--no-sandbox` (six sites: the SDK, the JNI bridge and
  the test APK). On Android that switch never changed where a child process
  runs. Renderers always land in an `isolatedProcess` `SandboxedProcessService`,
  under a per-process isolated UID and SELinux `isolated_app`, and the platform
  grants that whatever the command line says. What the switch did remove is the
  layer inside the renderer: it stops `renderer_main` from calling
  `EnableSandbox()`, so the process kept only the Android zygote's baseline
  filter. Measured on a device from `/proc/<renderer pid>/status`, 0.10.1 then
  0.10.2: `NoNewPrivs` 0 -> 1, `Seccomp_filters` 1 -> 2.
  Note for anyone auditing this themselves: the `Seccomp:` field proves nothing
  on Android, because every app process inherits the zygote filter. The filter
  count and `NoNewPrivs` are the signal.
  Decision: removed rather than made configurable. A sandbox switch that an
  embedder can turn off is a sandbox that ships off.
  Found by a third-party audit of 0.9.4
  (`linkalls/velocity-browser` PR #9), which reported the three visible sites.

### Fixed

- **The User-Agent reported the host app's version as the Chrome version.** An
  app whose `android:versionName` was `0.1.0` sent `Chrome/0.1.0`, and the
  client hints agreed with it (`Sec-CH-UA` brand `Chromium` version `"0"`,
  `uaFullVersion` `0.1.0`). Chromium 156 changed
  `version_info::GetVersionNumber()` on Android to return the host APK's
  `versionName` (upstream `7cb5ec9c41e7f`), and both the UA and the hints read
  it. Sites that branch on the Chrome version therefore served fallback content.
  The fix has two layers, because the client hints do not go through
  `CefSettings.user_agent_product`: the UA token now comes from CEF's build
  constants, and `Cefrium.setupProcess()` declares the engine version to
  `ApkInfo.setBrowserPackageInfo()`, which is the mechanism Chromium provides
  for an engine whose host APK is not the engine. Measured after the fix, with
  the host app still at `versionName 0.1.0`: `Chrome/156.0.8078.17`, brand
  version `"156"`, `uaFullVersion 156.0.8078.17`.
  Only `versionName` is replaced. `packageName`, `applicationInfo` and
  `versionCode` stay the host's real values, because child processes receive
  them and check them against their own `sourceDir`.
  The `Cefrium/1.0` token is unchanged and does not track the SDK version. See
  VERSIONING-CEFRIUM.md.
- **A change to the SDK's `AndroidManifest.xml` did not rebuild the AAR.**
  Chromium's `dist_aar` template passes `android_manifest` to the packaging
  script as an argument but never adds it to `inputs`, while `native_libraries`
  and `proguard_configs` on the same template do. ninja therefore never learned
  the manifest was an input, and a manifest edit could silently not ship. Fixed
  in the Chromium tree by patch `android/dist_aar_manifest_input`, one line,
  matching the `native_libraries` branch above it.
  Decision: fixed upstream-style rather than worked around by deleting the AAR,
  and parked for submission. The bug affects anyone who ships an AAR from a
  Chromium tree.

## [0.10.1] - 2026-10-16

Engine: **Chromium 156.0.8078.17 / CEF branch 8078** (unchanged from 0.10.0).

### Fixed

- **Off-screen browsers no longer crash when a page that was granted
  geolocation is reloaded** (issue #12). Chromium's
  `permissions::ShouldRepromptUserForPermissions()` dereferenced
  `web_contents->GetNativeView()->GetWindowAndroid()` while null-checking the
  `WebContents` and the `WindowAndroid` but not the native view in between --
  and an off-screen `WebContents` has none, so the browser process died with a
  SIGSEGV. Patch `android/permissions_reprompt_osr_null_view`; the guard uses
  the same idiom two other functions in that file already use.
  Decision: fixed in Chromium rather than worked around in the SDK, and parked
  for upstream submission, because the null check belongs where the
  dereference is.
  It took two visits to show, which is why it read as intermittent: the call
  sits behind `status == GRANTED`, so the first request short-circuits past it
  and only a later one reaches the crash.
  **The same function serves camera and microphone**, so a second
  `getUserMedia()` after a grant crashed identically; that is fixed by the same
  guard.

## [0.10.0] - 2026-10-15

Engine: **Chromium 156.0.8078.17 / CEF branch 8078** (was 154.0.8037.98 / 8037).

### Added

- **`CefriumBrowser.setFocus(boolean)`**, with `CefriumNavigator.setFocus` for
  the Compose API. Off-screen browsers have no Android `View`, so nothing could
  focus them; this is the only way for an OSR embedder to give the document
  keyboard focus. See the Fixed entry below for why it matters beyond typing.
- **`CefriumBrowser.sendKeyEvent(android.view.KeyEvent)`.** The existing
  five-argument overload mirrors `CefBrowserHost::SendKeyEvent` and so takes a
  *Windows* virtual-key code, which is not something an Android embedder has.
  The new overload maps the Android key code with Chromium's own conversion
  table, maps `metaState` to CEF modifiers, and emits the sequence CEF expects
  (`RAWKEYDOWN` then `CHAR` for a printable key, `KEYUP` on release). The old
  overload stays.
- Sample `OsrFocusKeyActivity` in `java-sample`, which drives both APIs on an OSR
  browser and asserts the page's `document.hasFocus()`, clipboard focus gate and
  DOM `key`/`code`/`keyCode`.

### Fixed

- **One browser's teardown stopped the media foreground service of another that
  was still playing.** The service is app-wide, a media session is per browser,
  and `hide()` called `stopService()` unconditionally -- so in a host with
  several browsers, anything that destroyed a WebContents (a plain reload, or
  restoring a discarded tab) took the service down while a *different* browser
  was audible: no lock-screen or shade controls, and the process became
  low-memory-killable while playing background audio. The service is now
  refcounted over the set of sessions that want it; only the last one stops it,
  and the notification is handed to a session that is still active rather than
  dropped. Measured on device before and after with
  `dumpsys activity services` against a started AAudio stream.

- **OSR documents were never focused, which broke every focus-gated web API.**
  Nothing in the SDK called `CefBrowserHost::SetFocus` in any configuration, so
  `document.hasFocus()` stayed false for a browser's whole lifetime and Blink
  rejected the async clipboard with `NotAllowedError: Document is not focused.`
  That gate runs *before* any permission check, so a `PermissionHandler` was
  never consulted and the failure looked like a permission bug. On-screen
  browsers now take focus from `CefriumSurfaceContainer.onWindowFocusChanged`
  (seeded from `hasWindowFocus()`, because the view is attached before the native
  browser exists and the focus edge has usually already passed); OSR embedders
  call `setFocus` themselves.

### Changed

- **`PERMISSION_NFC` is now `1 << 30`** (was `1 << 29`): upstream CEF 8078 took
  `1 << 29` for its own `AMBIENT_LOGIN` permission type. Code that uses the
  constant only needs recompiling; code that hardcoded the numeric value must be
  updated.

### Notes

- Geolocation permission requests still do not reach `PermissionHandler`, and
  that is not a Cefrium bug: `GeolocationPermissionContextAndroid` intercepts
  before the web layer and denies unless the *app* holds `ACCESS_FINE_LOCATION`
  and the system location setting is on. The AAR deliberately declares no
  location permission -- a library must not force one on its consumers. Now
  documented on `PermissionHandler`.

## [0.9.4] - 2026-10-07

Engine: **Chromium 154.0.8037.98 / CEF branch 8037** -- unchanged from 0.9.3.
This release exists to fix *delivery*, not the engine.

### Fixed

- **Back on Maven Central, and with every ABI again.** 0.9.0 through 0.9.3 were
  published to the Codeberg registry only, so they were a 404 on Maven Central --
  which is where adopters resolve from, since the Codeberg mirror keeps only the
  latest version and cannot be pinned. Central had served nothing since 0.8.9. On
  top of that, 0.9.3 was cut for `arm64-v8a` alone, so it could not run on an
  **x86_64 emulator** and published no `cefrium-sdk-libre` coordinate at all.
  0.9.4 restores the full matrix (codecs/libre x arm64/x86_64) on both channels.

  **If you were on 0.8.9**, this release carries everything from the four
  versions you never received: controllable downloads, `content://` URLs and APK
  asset loading (0.9.0), Smart Text selection via the platform TextClassifier
  (0.9.2), the OSR copy/paste and WebAuthn third-party-provider fixes (0.9.2,
  0.9.3), the Chromium 152 -> 154 engine bump, and the engine network controls
  below (0.9.3). Each is detailed in its own section.

- Two guards so neither failure can recur silently. `cut-release.sh` now refuses
  at the publish phase when the variant matrix is incomplete, naming what would
  be missing, and it has a `central` phase that fails when the Central token is
  absent rather than quietly shipping to Codeberg only. The second one matters
  because Maven versions are immutable: a release that misses a channel or an ABI
  cannot be topped up afterwards.

- Consumer docs no longer advertise a version the recommended repository does not
  serve, and no longer promise `x86_64` for a release that lacks it.

## [0.9.3] - 2026-10-06

Engine: **Chromium 154.0.8037.98 / CEF branch 8037** (was 152.0.7977.82 / 7977).

### Changed

- **Engine bumped two milestones.** CEF has no 153 branch, so this moves 152 ->
  154 in one step. Debian's chromium is on 154.0.8037.92 and the CEF branch pins
  .98, so version parity (the mechanism by which this port inherits CVE fixes)
  is satisfied with room to spare. Nine upstream API changes had to be adapted,
  the notable ones for consumers being an OSR surface-embedding change and a new
  `InterceptNavigationDelegateClient.isTabInPopup()` that the SDK now implements
  -- answering false, because no Cefrium browser is ever a popup window, and
  answering true would have silently disabled the external-scheme hand-off
  (intent://, market://, tel://) shipped in 0.8.8.

- **Behaviour change -- the engine no longer queries Google's time service by
  default.** See the entry below; this is the one place Cefrium deliberately
  diverges from Chromium's own default.

- The engine now registers only the ten component-updater components this
  embedding actually uses, instead of inheriting Chrome's full list of about
  twenty. Validated on device: the ten dropped ones no longer register, every
  kept one still arrives, and certificate revocation data still updates. See
  the FEATURE-067 entries below.

### Fixed

- A stale checked-in JNI registration would have crashed any consumer of this
  release on its first child-process launch (`NoSuchMethodError` on
  `SysUtils.hasLargeProcessCountSupport`, new in 154). 107 JNI methods were
  added and 70 removed since 152. The release gate already checked for this;
  the build now checks too, so a dev build fails on drift rather than producing
  an AAR that only breaks on a device.

### Also in this release

- The `com.cefrium` Gradle plugin is now compiled to **JVM 17 bytecode**
  (class-file 61) instead of inheriting the build JDK's class-file 69. The plugin
  is plain Groovy against the Gradle API and needs nothing newer; shipping it as
  Java-25 bytecode forced every consumer onto JDK 25 merely to *load* the plugin
  during Gradle's configuration phase, which is where the confusing
  `Unsupported class file major version 69` first appears. 17 is the floor Gradle 9
  and AGP 9 already require. **This does not change the SDK's requirements**:
  `cefrium-sdk` still ships Java-25 bytecode and still needs JDK 25 / Gradle 9.7.1 /
  AGP 9.4 / compileSdk 37.

- Passkeys: route a modal `get()` carrying `allowCredentials` to the Android
  Credential Manager instead of Play Services. Chromium routes such requests
  straight to Play Services ("non-discoverable allowlist"), which is a Chrome+GMS
  assumption: for an embedder the passkeys live in a third-party provider that GMS
  knows nothing about, so Play Services finds no match and the request falls
  through to the GMSCore hybrid flow (the "check Bluetooth" error page) without
  ever offering the provider. (patch android/webauthn_3pp_get_credman_routing)
- Passkeys: declare the caller origin on Credential Manager requests. Chromium
  applies `setOrigin()` only from a `CredManRequestDecorator` that just Chrome
  registers via ServiceLoader, so an embedder never declared it; CredMan then
  treated the request as first-party from the app's own package and providers
  looked for credentials belonging to the app rather than the page's relying
  party. Affects both get and create.
  (patch android/webauthn_credman_embedder_origin)
  NOTE: with both fixes the request now reaches third-party providers correctly,
  but releasing an EXISTING passkey additionally requires the app to be on the
  platform's privileged-browser allowlist -- see FEATURES-CEFRIUM.md.
- Fix passkey / WebAuthn not offering third-party providers (Proton Pass, etc.)
  in the browser. The per-WebContents CHROME_3PP_ENABLED WebAuthn mode (required
  for the CredMan path's security checks to pass) was set inside the Autofill
  try/catch; AutofillProvider construction throws on some OEM ROMs (MIUI/Xiaomi,
  some tablets), which silently left WebAuthn in the global BROWSER mode so
  CredMan (and thus Proton Pass) was never invoked. Decoupled into its own
  try/catch. Validated on device: webauthn.io registration now shows the Proton
  Pass sheet. (FEATURE-064 follow-up)

- **Behaviour change -- the engine no longer queries Google's time service by
  default.** Chromium enables `NetworkTimeServiceQuerying` on Android, which makes
  roughly one request a day to `http://clients2.google.com/time/1/current`. Its only
  purpose is to notice a badly set device clock so that a certificate error can be
  explained as a clock problem rather than an expired certificate; it carries no
  identifier and no security data, and Android devices normally get the clock from
  the carrier or NTP. The SDK now applies
  `--disable-features=NetworkTimeServiceQuerying` by default, so that call is gone.
  Certificate validation is unaffected. Restore upstream behaviour with
  `Cefrium.setNetworkTimeQueriesEnabled(true)` before the first browser is created
  -- worth doing if your hardware has no dependable clock source (no cellular modem
  and no NTP reachability, or an RTC with no battery), where a clock far enough off
  to break HTTPS is a real support case. Decision note: implemented as the upstream
  switch rather than by patching the feature's default, so an embedder can still
  choose and the behaviour survives engine bumps without a patch to carry.
  (FEATURE-067)

- New API to control what the engine does on the network in the background, with
  the two requests given opposite defaults on purpose:
  `Cefrium.setComponentUpdatesEnabled(boolean)` /
  `areComponentUpdatesEnabled()` and
  `Cefrium.setNetworkTimeQueriesEnabled(boolean)` /
  `areNetworkTimeQueriesEnabled()`. Both must be called before the engine
  initializes; afterwards they are ignored and log a warning. Component updates stay
  **on** by default because they are a security feed, not telemetry -- the channel
  that delivers certificate revocation (CRLSet), Certificate Transparency metadata,
  the dangerous-download file-type policy, TLS error diagnostics, Trust Token key
  commitments, First-Party Sets and the subresource filtering ruleset. Disabling
  them freezes all of that at the SDK version you shipped, and the failure mode is
  silent, so it is an opt-out intended for air-gapped, metered-network and
  compliance-constrained deployments that accept pinning their security data to a
  release. Both are Chromium's own switches, and setting `disable-features` merges
  with any value already on the command line instead of overwriting it. See
  FEATURES-CEFRIUM.md and <https://cefrium.com/guides/network-behaviour/>.
  (FEATURE-067)

## [0.9.2] - 2026-09-24

- Smart Text selection: the windowed selection toolbar now uses the platform
  TextClassifier, adding entity actions (phone->call, address->map, URL->open),
  matching WebView. (FEATURE-065 follow-up)
- OSR text-selection copy/cut/paste quick menu (windowless mode). Long-pressing
  text in an off-screen browser now selects a word and shows the quick menu via
  CefriumRenderHandler.onQuickMenu (Cut/Copy/Paste) + QuickMenuCallback; CEF
  performs the edit. Two fixes: (1) on Android the OSR view's render-frame
  metadata (selection bounds, scroll offset) is now delivered via
  OnRenderFrameMetadataChangedBeforeActivation -- the After-Activation callback
  never fires for a windowless view on Android (no on-screen Viz compositor to
  ACK frame tokens), so the touch-selection controller never activated; this also
  fixes OnScrollOffsetChanged in OSR. (2) the SDK now wires the context-menu
  handler's Java bridge in the OSR (setRenderHandler) path, so RunQuickMenu
  actually reaches Java. Validated on device. (FEATURE-066)
- OSR focused-node value + placeholder (issue #9 follow-up). New
  `CefriumRenderHandler.onFocusedNodeChanged(editable, value, placeholder, type)`
  delivers the focused editable field's current value, placeholder and type
  (read from the DOM in the renderer). Pairs with `onVirtualKeyboardRequested`
  so an OSR consumer can populate a native input overlay that mirrors the web
  field. SDK-only. (FEATURE-062)
- Text-selection floating toolbar in windowed mode. Long-pressing/selecting text
  now shows the Android floating ActionMode (Select All / Copy / Paste / Share /
  Web Search / process-text). Content gates the toolbar on an installed
  ActionMode callback; Cefrium now installs one (CefriumActionModeCallback),
  mirroring Chrome/WebView. SDK-only. (FEATURE-065)
- Passkey / WebAuthn via the Android Credential Manager. `navigator.credentials
  .create/get({publicKey})` now reaches the Android Fido2 / Credential Manager,
  so credential providers (e.g. Proton Pass) are offered. Cefrium stubs Chrome's
  interface registrar and re-adds interfaces per-embedder; the WebAuthn
  Authenticator mojo factory was missing (payments was already re-added) --
  CefAuthenticatorInterfaceRegistrar now binds it. SDK-only. (FEATURE-064)

## [0.9.1] -- 2026-09-22

Chromium **152.0.7977.82** (CEF branch **7977**).

- OSR IME + virtual keyboard (issue #9). Off-screen (OSR) browsers now drive the
  Android soft keyboard: `CefriumRenderHandler.onVirtualKeyboardRequested(int)`
  fires when an editable node gains/loses focus (hide only for
  `TEXT_INPUT_MODE_NONE`, show otherwise). New IME input path on
  `CefriumBrowser`: `imeSetComposition`, `imeCommitText`,
  `imeFinishComposingText`, `imeCancelComposition`, plus `sendKeyEvent` (with
  `KEY_*` and `TEXT_INPUT_MODE_*` constants) for backspace/enter/arrows. Wire a
  custom `InputConnection` to these from the View that hosts the OSR frames (see
  the OsrImeActivity sample). Windowed/Surface mode (incl. the Compose wrapper)
  is unaffected -- it already uses Android's native IME. SDK-only, no CEF patch.

- CEF public-API parity batch. Exposes the previously-unwired CEF handlers and
  CefBrowserHost methods to the Java SDK, so consumers get WebView/CEF parity in
  one release instead of a drip of requests:
  - Display: `CefriumDisplayHandler` -- favicon URLs, JS console messages,
    fractional loading progress, status text, camera/mic in-use change.
  - Load: `CefriumLoadHandler` -- onLoadStart, onLoadError (net error + failed URL).
  - Request: `CefriumRequestHandler` -- open-URL-from-tab, hung-renderer (ANR)
    un/responsive, render-view-ready, document-available, client-certificate
    selection, and response-side redirect/response/load-complete inspection.
  - Dialogs: file chooser (`CefriumDialogHandler`, `<input type=file>` via
    ACTION_GET_CONTENT), beforeunload ("Leave site?"), JS-dialog lifecycle.
  - New handlers: `CefriumKeyboardHandler` (intercept keys), `CefriumFocusHandler`,
    `CefriumDragHandler`, `CefriumFrameHandler` (frame lifecycle),
    `CefriumCommandHandler`, and `CefriumAudioHandler` (raw PCM tap -- OPT-IN via
    setAudioHandler; enabling it redirects page audio).
  - OSR: HTML `<select>` dropdowns now render off-screen (onPopupPaint/Show/Size);
    plus IME composition-rect, text-selection and scroll-offset callbacks.
  - CefBrowserHost: `exitFullscreen()`, `startDownload(url)`,
    `getNavigationEntries()` (history with titles), `downloadImage(url,...)`,
    plus LifeSpan DoClose and Permission prompt-dismiss.
  - Sample: `ParityActivity`. SDK-only, no CEF patch. (Response-body streaming
    filter and per-cookie access filter intentionally deferred -- perf-sensitive
    hot paths that need their own design.)

## [0.9.0] -- 2026-09-19

Chromium **152.0.7977.82** (CEF branch **7977**). Controllable downloads,
content:// URLs, and APK asset loading.

### Added
- **Open `content://` URLs (Android content-provider URIs) in a tab.** A
  FileProvider URI -- e.g. a downloaded file, or a file shared into the app --
  now loads and renders (text/HTML/PDF/image). Previously
  `net::ERR_UNKNOWN_URL_SCHEME`. (Also enables opening a download inside the
  browser.)
- **Load APK-bundled web content: `file:///android_asset/...` and
  `file:///android_res/...`.** Ship an offline web app (HTML/CSS/JS) inside your
  APK and load it directly, matching Android WebView. Served from the host app's
  AssetManager/Resources; top-level navigation and subresources both work.
- **Compose: `CefriumViewState` download support.** A `downloads` list (per-id,
  live), an `onDownloadEvent` hook (for notifications), an `onBeforeDownload`
  hook (choose where to save, e.g. a SAF picker), and
  `cancelDownload`/`pauseDownload`/`resumeDownload(id)`.
- `CefriumBrowser.cancelDownload/pauseDownload/resumeDownload(id)` -- control a
  download by id (e.g. from a notification action) without holding the item.
- **Controllable download API (`CefriumDownloadHandler`).** The previous
  `OnDownloadListener` could only observe a download after it had already
  started -- an accidental tap on a download link could not be stopped except
  by killing the app. The new handler mirrors CEF's `CefDownloadHandler`:
  - `setDownloadHandler(CefriumDownloadHandler)` -- register a handler.
  - `onBeforeDownload(DownloadItem, BeforeDownloadCallback)` -- a download is
    about to start; decide **asynchronously** whether to allow it. Call
    `callback.allow(path)` (pass `null` for the suggested path) to download, or
    `callback.cancel()` to block it. Nothing is written until you respond, so
    you may show a confirmation dialog first.
  - `onDownloadUpdated(DownloadItem)` -- progress updates; `DownloadItem`
    exposes `cancel()` / `pause()` / `resume()` plus rich getters
    (id, url, suggested name, mime, full path, total/received bytes, percent,
    current speed, complete/canceled/paused, interrupt reason).
  The legacy `setOnDownloadListener` still works (detect-only) and, when no
  `CefriumDownloadHandler` is registered, downloads auto-start as before, so
  this is fully backward compatible.

### Fixed
- **Minified (R8) consumer builds no longer crash at startup with
  `AbstractMethodError: Consumer.accept(Object)`.** R8 stripped `accept(Object)`
  from Chromium's `WindowLayoutInfoListener` lambda (implements
  `androidx.window.extensions.core...Consumer`). The SDK now ships a consumer
  keep rule for the SAM method and declares `androidx.window.extensions.core:core`
  as a `provided` (compile-only) POM dependency so the consumer's R8 can resolve
  the interface -- no consumer workaround needed. Validated with a minified
  release build.
- **Multiple downloads from one page no longer silently blocked (this is what made
  `data:` / `blob:` downloads appear "unavailable").** Chromium's
  `DownloadRequestLimiter` allows the first automatic download from a page and
  then, with no permission-prompt UI attached (CEF attaches none), silently
  blocked every later one -- and blocked ALL but the first download from an
  opaque-origin page (`data:`/`blob:` pages, or content shown via `loadHtml` /
  `loadDataWithBaseURL`). The limiter now defers to the embedder's
  `CanDownload` when there is no prompt UI, so every download reaches the
  download handler and the app decides. Root cause was NOT a `data:`/`blob:`
  scheme filter (none exists in Chromium's download path); single downloads of
  those schemes already worked. Validated on device: 3 sequential `data:`
  downloads now all complete (previously only the first did).

## [0.8.9] -- 2026-09-18

Chromium **152.0.7977.82** (CEF branch **7977**). Off-screen rendering crash fix.

### Fixed
- **No longer crashes in off-screen (OSR) mode during single-page-app navigation.**
  On a same-document navigation (e.g. an SPA route change on a sign-in page), the
  browser process could crash (`SIGSEGV`, null deref) inside Chromium's
  back/forward navigation-transition screenshot code, which dereferenced the
  view's native Android view without checking it -- and an off-screen view has
  none (`GetNativeView()` is null in OSR). The interceptor now fails safely when
  there is no native view, matching the function's existing guards. (Codeberg
  browser issue: OSR SIGSEGV on `accounts.google.com`.)

## [0.8.8] -- 2026-09-18

Chromium **152.0.7977.82** (CEF branch **7977**). External URL scheme hand-off.

### Added
- **External / app URL schemes are handed off to Android.** When a page links to
  a non-web scheme -- an app deep link (`baiduboxapp://`, `weixin://`, `intent://`,
  `market://`) or a well-known external protocol (`tel:`, `mailto:`, `geo:`,
  `sms:`) -- the browser now fires an Android `Intent` to open the target app
  instead of showing "This site can't be reached". Parsing and security hardening
  (intent:// sanitization, `S.browser_fallback_url`, user-gesture / redirect
  rules) come from Chromium's own `components/external_intents`, installed as a
  navigation throttle on the browser's WebContents. `http`/`https` still load in
  the browser; an unknown scheme with no installed app fails gracefully (no crash,
  no error page). The SDK declares the `<queries>` needed for Android 11+ package
  visibility so consumers need no manifest changes. (Codeberg browser issue: URL
  scheme #6.)

## [0.8.7] -- 2026-09-17

Chromium **152.0.7977.82** (CEF branch **7977**). Fixes a browser crash when
opening file-download links.

### Fixed
- **No longer crashes when a page opens a file-download link.** A top-level
  navigation that turns into a download re-issues its request with the same
  `request_id`, which collided with the still-tracked navigation request in the
  request-interception layer. The colliding entry was freed and then used
  (use-after-free), crashing the browser process (`SIGSEGV` in
  `InterceptedRequest::Restart`). The interceptor now drops the duplicate instead
  of restarting a freed request; the download proceeds normally. Custom URL
  schemes that hit the same path are covered by the same fix. (Codeberg browser
  issues: file-download crash and URL-scheme crash.)

## [0.8.6] -- 2026-09-16

Chromium **152.0.7977.82** (CEF branch **7977**). Fixes dark mode on Android.

### Fixed
- **`setDarkMode()` now affects the page's `prefers-color-scheme`.** On Android the
  chrome ThemeService is not wired up, so `setDarkMode` was silently a no-op; the
  browser now drives the web content's preferred color scheme directly.
  `window.matchMedia('(prefers-color-scheme: dark)')` reflects it and CSS dark media
  queries take effect, in both windowless (OSR) and SurfaceView browsers. (Codeberg
  issue #4.)

## [0.8.5] -- 2026-09-12

Chromium **152.0.7977.82** (CEF branch **7977**). Stability: fixes an intermittent
native crash during process startup.

### Fixed
- **Intermittent (~50%) startup crash on some devices.** A re-entrant recursion in
  the PartitionAlloc lock-metrics subsampler (its first-use RNG seed allocates
  under a contended lock, whose acquisition timer re-enters the sampler before the
  seed completes) could overflow the stack and crash the process at startup. Guards
  the sampler against re-entrancy. No API changes.

## [0.8.4] -- 2026-09-12

Chromium **152.0.7977.82** (CEF branch **7977**). Adds a touch-automation API.

### Added
- **Touch automation API.** `CefriumBrowser` (and the Compose `CefriumNavigator`)
  gain `sendTap`, `sendTouchDown` / `sendTouchMove` / `sendTouchUp`, and `sendSwipe`
  (coordinates are browser-viewport pixels). In the SurfaceView browser these drive
  the full gesture pipeline -- a tap synthesizes a click, a swipe scrolls/flings; in
  windowless (OSR) mode they use the CEF touch path. Useful for automation and
  testing. See the `InputAutomationActivity` sample and cefrium.com/docs.

## [0.8.3] -- 2026-09-11

Chromium **152.0.7977.82** (CEF branch **7977**). Makes the mouse API work in the
SurfaceView browser (the flagship browser's rendering mode).

### Fixed
- **Mouse API now works in windowed / SurfaceView (ContentViewRenderView) mode.**
  In 0.8.2 the mouse API only reached the renderer in windowless (OSR) mode; on a
  `createWithSurface` browser the events were silently dropped. They are now
  forwarded through the Android render widget host, so `sendMouseMove` /
  `sendLeftClick` / `sendRightClick` / etc. work in both rendering modes.

## [0.8.2] -- 2026-09-11

Chromium **152.0.7977.82** (CEF branch **7977**). Adds a mouse-event API.

### Added
- **Mouse event API.** `CefriumBrowser` (and the Compose `CefriumNavigator`) gain
  `sendMouseMove` / `sendMouseLeave` / `sendMouseClick` / `sendLeftClick` /
  `sendRightClick` / `sendDoubleClick` / `sendMouseDown` / `sendMouseUp` /
  `sendMouseWheel`, with button and modifier parameters, mirroring the desktop CEF
  mouse surface. Coordinates are browser-viewport pixels. Validated on device.

### Fixed
- **Android OSR input delivery.** Injected pointer events were dropped before
  reaching the renderer on the Android off-screen (windowless) path because it has
  no DelegatedFrameHost: pointer events are now dispatched directly, the
  paint-holding input hold is released on frame delivery, and the native cursor
  handle is null-guarded (it was crashing once input started flowing).

## [0.8.1] -- 2026-09-09

Chromium **152.0.7977.82** (CEF branch **7977**). Same-branch security patch bump
for CVE parity with Debian's chromium 152.0.7977.82, plus distribution on Maven
Central.

### Added
- **Now published to Maven Central** (`com.cefrium:*`), alongside the existing
  Codeberg Maven registry. `mavenCentral()` -- already present in every Android
  build -- resolves the SDK, the Compose wrapper, and the `com.cefrium` Gradle
  plugin with no extra repository line, and every version stays available
  permanently (Central never deletes a release). The Codeberg registry remains as
  a mirror (latest version only). See QUICKSTART-CEFRIUM.md.

### Changed
- **Engine: Chromium 152.0.7977.75 -> 152.0.7977.82** (same CEF branch 7977). A
  same-branch Chromium security roll-up tracking the Debian Chromium release for
  CVE parity. `Cefrium.CHROMIUM_VERSION` now reports 152.0.7977.82. No API changes.
- **Internal accessors marked `@RestrictTo`.** `CefriumBrowser`'s package-private
  `getWebContents()` / `connectWebContentsInternal()` now carry
  `@RestrictTo(LIBRARY)`, so code reaching them from outside the library (e.g. a
  same-package accessor in a consumer module) gets a lint warning at compile time.
  These were never part of the public API; no functional change.

## [0.8.0] -- 2026-09-05

Chromium **152.0.7977.75** (CEF branch **7977**). Cross-branch engine bump
(151 -> 152) for CVE parity with Debian, a new pinch-to-zoom control, and a raised
consumer build-toolchain floor.

### Added
- **Pinch-to-zoom control: `setPinchToZoomEnabled(boolean)` / `isPinchToZoomEnabled()`**
  on `CefriumBrowser`, and `setPinchToZoomEnabled(boolean)` on the Compose
  `CefriumNavigator`. Lets an app disable (or re-enable) page/pinch zoom for
  arbitrary content -- requested by an SDK user who needed a fixed-scale view.
  Validated on device.

### Changed
- **Engine: Chromium 151.0.7922.137 -> 152.0.7977.75** (CEF 7922 -> 7977). A
  cross-branch bump tracking the Debian Chromium security release; the port was
  rebased onto CEF 7977. `Cefrium.CHROMIUM_VERSION` now reports 152.0.7977.75.
- **BREAKING -- consumer build toolchain.** The SDK now ships Chromium 152's
  **Java-25 (class-file major 69) bytecode**, so a consumer app must build with
  **AGP 9.4+**, **Gradle 9.7.1+**, **JDK 25**, and **compileSdk 37**. On AGP 9 use
  built-in Kotlin (drop `org.jetbrains.kotlin.android`; for Compose apply
  `org.jetbrains.kotlin.plugin.compose`), enable
  `packaging { jniLibs { useLegacyPackaging = true } }`, and do not exclude
  `org.jspecify`. This floor is imposed by Chromium 152 (it compiles its Java with
  `--release 25`) and is not optional. QUICKSTART-CEFRIUM.md Troubleshooting has the
  exact migration and the multi-module-app caveat (Gradle resolves one AGP version
  per build). (0.7.x used AGP 8.6+/Gradle 8.9/JDK 21.)

## [0.7.1] -- 2026-08-17

Chromium **151.0.7922.137** (CEF branch **7922**). WebSocket regression fix + a
same-branch security patch bump for CVE parity with Debian's chromium
151.0.7922.137-1~deb13u1.

### Fixed
- **WebSockets to a local/private address no longer stall** (cef-android issue #1).
  Chromium 151 enables Local Network Access (LNA) checks -- including for WebSockets --
  by default. An LNA-triggering WebSocket (a page connecting to localhost / a LAN
  address) gates the connection on a permission decision via
  `OnLocalNetworkAccessPermissionRequired`; Cefrium has no LNA prompt UI wired, so the
  handshake stalled indefinitely (reported as extreme WebSocket latency after the
  0.6.x -> 0.7.0 engine bump). Cefrium now disables the LNA feature, matching Android
  WebView which disables the same feature for the same reason. Validated on device:
  before, the WS handshake never completed; after, it connects immediately and
  round-trips 50 messages in ~100 ms. Embedders that want LNA enforcement can gate it
  via `CefPermissionHandler`.

### Changed
- **Engine: Chromium 151.0.7922.71 -> 151.0.7922.137** (same CEF branch 7922). A
  same-branch Chromium security roll-up; tracks the Debian Chromium release for CVE
  parity. `Cefrium.CHROMIUM_VERSION` now reports 151.0.7922.137. No API changes.

## [0.7.0] -- 2026-08-03

Chromium **151.0.7922.71** (CEF branch **7922**). Engine bump for CVE parity with
Debian's chromium 151.0.7922.71-1~deb13u1.

### Changed
- **Engine: **Chromium 150.0.7871.114 -> 151.0.7922.71** (CEF 7871 -> 7922). Tracks the
  Debian Chromium security release. `Cefrium.CHROMIUM_VERSION` now reports 151.0.7922.71.
  The 150->151 cross-branch bump rebased the Android port onto CEF 7922; the API surface
  is unchanged for SDK consumers. Internal adaptations: `OverscrollRefreshHandler.release`
  now takes an `@OverscrollActivationStatus int`; `MediaSession.suspend()/resume()` take a
  `SuspendType`; three Android Chromium patches regenerated for 151 (permission-prompt
  embedder seam, accessibility LinkageError guards, password-manager loadDataWithBaseURL
  no-kill -- the last adapted to 151's new `may_kill_renderer` gate).

## [0.6.5] -- 2026-08-03

Chromium 150.0.7871.114 (CEF branch 7871). SDK feature; no engine change.

### Added
- **`CefriumBrowser.setMediaSessionActivity(PendingIntent)`** (and the Compose
  `CefriumNavigator.setMediaSessionActivity`) lets a multi-tab host make the media
  notification / lock-screen control open the SPECIFIC tab that is playing, not just the
  app. Each browser's `MediaSessionCompat` now carries a settable session activity
  (`setSessionActivity` + the notification `contentIntent`), overriding the default
  launcher intent added in 0.6.4. A host gives each tab a `PendingIntent` that re-targets
  that tab (e.g. an extra carrying the tab id); the tap then routes to it. Passing `null`
  restores the default launcher intent. Validated on device: tapping a background tab's
  media control brings the app forward and switches to that exact tab.

## [0.6.4] -- 2026-07-31

Chromium 150.0.7871.114 (CEF branch 7871). SDK bugfix; no engine change.

### Fixed
- **Tapping the media notification / lock-screen control now opens this app** (the tab
  playing the media) instead of another app. `CefriumMediaSession` now sets a
  `contentIntent` on the notification and `setSessionActivity` on the
  `MediaSessionCompat` -- the host app's own launcher intent (SINGLE_TOP so it resumes).
  Without them the session had no tap target, so when another media session was active
  (e.g. Spotify), Android routed the control's tap to that other app even though the
  notification showed the Cefrium tab.

## [0.6.3] -- 2026-07-21

Chromium 150.0.7871.114 (CEF branch 7871). SDK bugfix; no engine change.

### Fixed
- **Media notification / lock-screen progress bar now advances.** `CefriumMediaSession`
  now observes Chromium's `mediaSessionPositionChanged` and pushes the media duration
  (`METADATA_KEY_DURATION`) plus the playback position + speed into the
  `MediaSessionCompat` `PlaybackState`, so Android renders and ticks the seekbar.
  Previously only the play/pause button worked -- the position was
  `PLAYBACK_POSITION_UNKNOWN` and no duration was set, so the bar had no range and never
  moved. Validated on device: seekbar advances at the correct scale for a playing video.

## [0.6.2] -- 2026-07-12

Chromium 150.0.7871.114 (CEF branch 7871). Security patch bump; no SDK API change.

### Changed
- **Engine updated to Chromium 150.0.7871.114** (from 150.0.7871.46), a same-branch
  security patch bump tracking Debian's 150.0.7871.114-1~deb13u1 upload for CVE parity.
  The 150.0.7871.114 stable roll-up fixes 27 vulnerabilities, including two CRITICAL
  use-after-free flaws -- CVE-2026-15112 (Ozone) and CVE-2026-15129 (Views) -- plus
  high-severity memory-safety fixes across V8, ANGLE, WebRTC, Codecs, Autofill, Payments,
  Input, DOM and Forms. All 142 CEF + Android-port patches re-apply cleanly on the new
  tree (same CEF branch 7871 -- no rebase or patch regeneration needed).
  CVEs: CVE-2026-15107, CVE-2026-15108, CVE-2026-15109, CVE-2026-15110, CVE-2026-15111,
  CVE-2026-15112, CVE-2026-15113, CVE-2026-15114, CVE-2026-15115, CVE-2026-15116,
  CVE-2026-15117, CVE-2026-15118, CVE-2026-15119, CVE-2026-15120, CVE-2026-15121,
  CVE-2026-15122, CVE-2026-15123, CVE-2026-15124, CVE-2026-15125, CVE-2026-15126,
  CVE-2026-15127, CVE-2026-15128, CVE-2026-15129, CVE-2026-15130, CVE-2026-15131,
  CVE-2026-15132, CVE-2026-15133.

### Fixed
- **`isPlayingAudio()` now uses only the actual-playback signal**, not the `is_suspended`
  flag (which flickers mid-playback for media documents and made the getter spuriously
  read false for an audible tab). Now `!mPlayingIds.isEmpty()`.

## [0.6.1] -- 2026-07-08

Chromium 150.0.7871.46 (CEF branch 7871). Java-only additive change; no engine rebuild.

### Added
- **`CefriumBrowser.isPlayingAudio()`** and Compose **`CefriumNavigator.isPlayingAudio()`** --
  whether the browser currently has audible media playing (at least one audio media session
  that is not paused). Backed by the existing media-session playback observer
  (`CefriumMediaSession`). Lets a multi-tab host avoid discarding/closing a tab that is
  actively playing sound, so background music/video is never cut by memory-pressure tab
  discarding. `WebContents` exposes no Java `isCurrentlyAudible()`, so the media-session
  observer is the reachable signal (and is exactly the set of tabs that warrant protection,
  since background audio depends on the media-session foreground service anyway).

## [0.6.0] -- 2026-07-05

Chromium 150.0.7871.46 (CEF branch 7871).

### Changed
- **Engine updated to Chromium 150.0.7871.46** (from 149.0.7827.102), tracking
  Debian's security upload for CVE parity (includes the CVE-2026-13789 V8 fix and the
  rest of the 150 security roll-up). The Android port was rebased onto CEF branch 7871
  and adapted to the Chromium 150 API: permission-prompt embedder seam regenerated,
  Android autofill client `HideAutofillSuggestions` -> `HideSuggestions`,
  `RenderWidgetHostView::Show()` -> `ShowWithVisibility()`, non-final R via
  `app_as_shared_lib`, and the payments Java `service_impl_java` dep. No SDK API
  changes for consumers -- a drop-in engine/security refresh.

## [0.5.9] -- 2026-07-03

Chromium 149.0.7827.102.

### Fixed
- **FedCM crash on federated sign-in pages.** The Gradle plugin now generates the R
  class for `org.chromium.chrome.browser.ui.android.webid`; without it, a page that
  invoked the FedCM account chooser (`AccountSelectionBridge.getBrandIconIdealSize`)
  threw `NoClassDefFoundError webid/R$dimen` and crashed the browser process. Only
  reproduced on CERTIFIED devices (where FedCM runs). Validated on Redmi/HyperOS.

### Added
- **Incognito (private) browsing.** `CefriumBrowser.createWithSurface(activity,
  incognito)` + `isIncognito()`; Compose `CefriumViewState(incognito = true)` /
  `rememberCefriumViewState(url, incognito)`. Incognito browsers share one
  off-the-record request context (isolated from normal tabs, in-memory) that is
  cleared when the last incognito browser closes. Validated on device: cookie
  isolation normal<->incognito and clear-on-close.

## [0.5.8] -- 2026-07-03

Chromium 149.0.7827.102.

### Fixed
- Tab/session restore now loads the page instead of showing a blank tab (or crashing on
  some devices). `CefriumViewState(navigationState = ...)` restore deferred its load to the
  wrong moment: `Restore()` ran while the initial about:blank navigation was still pending
  (so nothing loaded -> blank), and forcing the load in `OnAfterCreated` navigated before the
  on-screen surface + overscroll handler existed, crashing the overscroll controller on the
  RenderFrameHost swap. Now `restoreNavigationState` rebuilds the stack without loading and
  schedules the surface connect (which restored tabs previously never did -- only `loadUrl`
  did); the load is triggered once, from `connectWebContentsInternal`, after the surface and
  handler are set up. Validated on a Redmi/W90 (Android 16, arm64) and rooted API-33/36
  emulators, single- and multi-tab.

## [0.5.7] -- 2026-07-03

Chromium 149.0.7827.102.

### Added
- Native pull-to-refresh. `CefriumBrowser.setPullToRefreshEnabled(true)` (Compose:
  `CefriumNavigator.setPullToRefreshEnabled`) makes an overscroll-down at the top of a
  page reload it. Call it before the page loads; the SDK binds the overscroll handler
  exactly once, when the WebContents connects -- re-setting it on a live WebContents
  crashes (stale native `OverscrollControllerAndroid` pointer). Validated on device.

## [0.5.6] -- 2026-07-02

Chromium 149.0.7827.102.

### Added
- Tab back/forward history restore across process death. `CefriumBrowser.serializeNavigationState()`
  captures a tab's full back/forward stack (per-entry URL + `PageState` + current index) as a
  `byte[]`; construct a Compose `CefriumViewState(navigationState = bytes)` to rebuild the stack
  on relaunch instead of just reloading the last URL. The native side serializes via
  `NavigationController` and restores by creating the browser at `about:blank`, then calling
  `Restore()` in `OnAfterCreated`. Validated on device (A->B->C, kill, relaunch -> back walks
  C->B->A; no `Restore` DCHECK).

## [0.5.5] -- 2026-07-02

Chromium 149.0.7827.102.

### Fixed
- Reliable background audio. The media foreground service is now driven by actual WebContents
  playback state rather than the media session's `is_controllable` bit, so audio/video keeps
  playing when the app is backgrounded or the screen is off (Brave-style). `startForeground` is
  guarded against the Android 12+ "cannot start a foreground service from the background"
  exception.

## [0.5.4] -- 2026-07-02

Chromium 149.0.7827.102.

### Added
- `CefriumBrowser.stopMediaSession()` (Compose: `CefriumNavigator.stopMediaSession()`) clears the
  media session and dismisses its notification; it reaches cross-origin iframes too.

### Fixed
- Media notification now appears for media documents (a bare `.mp3`/`.mp4` URL) and for pages that
  wire `navigator.mediaSession` after load. The `MediaSessionImpl` Java accessor used
  `GetIfExists()`, so a Java observer attached at page-load time bound to a null session and the
  notification never showed; it now uses `Get()` (create-or-return) so the observer binds before
  playback starts. (Upstreamable Chromium fix.)

## [0.5.3] -- 2026-06-24

Chromium 149.0.7827.102.

### Added
- Renderer-termination callback. `CefriumBrowser.setOnRenderProcessTerminatedListener(
  (status, errorCode) -> ...)` fires when a renderer process for the browser dies --
  e.g. the OEM low-memory killer reclaims it -- so you can show a reload affordance
  instead of a silent blank page. `status` is one of the `CefriumBrowser.TERMINATION_*`
  constants (`ABNORMAL`/`KILLED`/`CRASHED`/`OOM`). Compose: observe the hoisted
  `CefriumViewState.rendererTermination` (a `RendererTermination(status, errorCode)`
  with a `RendererStatus` enum; cleared automatically on the next load), or pass
  `onRenderProcessTerminated` to `CefriumWebView`. Validated on a Redmi/HyperOS device
  (real OEM low-memory kill) and a rooted emulator.
- `CefriumBrowser.PERMISSION_NFC` for Web NFC permission. Grant it in your
  `setPermissionHandler`, or pre-authorize an origin with
  `setContentSetting(url, PERMISSION_NFC, SETTING_ALLOW)`.

### Fixed
- Web NFC (`NDEFReader` read/write) now works end-to-end -- previously the API was
  present but every scan/write was denied or hung. Three engine fixes: (1) CEF mapped
  the NFC permission request to `CEF_PERMISSION_TYPE_NONE` (ungrantable) -> added
  `CEF_PERMISSION_TYPE_NFC`; (2) `NfcBlocklist` read a variations field-trial param
  whose JNI is absent in CEF and threw, aborting every read/write -> guarded; (3) the
  consumer also needs `android.permission.VIBRATE` (the engine buzzes on tag
  detection). Validated read + write round-trip on a Redmi Note 13 Pro / HyperOS.
- Renderer terminations reported by Android as `OOM_PROTECTED` / `EVICTED_FOR_MEMORY`
  (the common OEM low-memory-kill path) now reach `OnRenderProcessTerminated` as
  `TS_PROCESS_OOM`. The upstream handler mapped only desktop termination statuses and
  silently dropped these, so the callback never fired for the most common Android
  renderer death. (Upstreamable libcef fix.)
- Surface-mode crash on close. Closing a Surface-mode browser shortly after a load
  could crash (`SIGSEGV` in `nativeGetWebContents`): the deferred WebContents-connect
  callback fired after the native browser was torn down and dereferenced a stale
  pointer. The callback now short-circuits once the browser is closed, and the native
  accessor guards a null host. (Surfaced by the per-browser process model on
  process-per-browser OEMs.)

## [0.5.2] -- 2026-06-14

Chromium 149.0.7827.102.

### Added
- `rememberCefriumBrowserPool(maxAlive)` (Compose) -- a bounded LRU of browsers for
  lesson/article lists: keeps at most `maxAlive` alive and CLOSES the
  least-recently-used on eviction, so memory stays bounded instead of one heavy
  renderer per item ever opened. On eviction it also reaps the orphaned render
  CHILD-PROCESS (`CefriumBrowser.reapUnusedRenderers()` ->
  `RenderProcessHost::FastShutdownIfPossible` on 0-frame, non-spare processes; no-op
  when unsafe -> never kills a live renderer), bounding the OS sandboxed-process
  count on process-per-browser devices (HyperOS/Android 16). Diagnostic:
  `CefriumBrowser.activeRenderProcessCount()` returns the exact live renderer count
  (RenderProcessHost only -- excludes utility/network/GPU). Validated: pool size and
  the renderer count both stay at `maxAlive`; on HyperOS the sandboxed-process count
  is bounded (drops on eviction/idle) with video playing across 13 lessons, PSS flat.
- Inline-media lifecycle API. `CefriumView` now auto-wires to the host lifecycle and
  PAUSES inline media (video/audio) when the host is backgrounded (HOME/recents),
  resuming on return -- zero work for the consumer (default
  `backgroundMediaPolicy = PAUSE_ON_STOP`). Other policies: `PAUSE_ON_PAUSE`,
  `MUTE_ON_STOP`, `NONE` (keep playing, e.g. background audio); or pass
  `onHostLifecycle` for full control. Granular controls on `CefriumBrowser` /
  `CefriumNavigator`: `suspendMedia()`/`resumeMedia()`, `setAudioMuted(boolean)`,
  `setPageVisible(boolean)`, `stopAllMedia()`, and the `onPause()`/`onResume()`
  convenience. Fixes inline audio continuing to play in the background.
  - Solution: browser-process media control (MediaSession suspend/resume == the
    audio-focus-loss path) + `WebContents.setAudioMuted` -- works even after the
    renderer JS is suspended, unlike a queued `evaluateJavaScript("pause()")`.
    Validated on device: started media players 1 -> 0 on HOME, 0 -> 1 on resume.
- `CefriumBrowser.setCookie(url, name, value, secure, httpOnly, sameSite)` and the
  Compose `CefriumNavigator` parity overload. Sets a cookie with explicit
  `Secure` / `HttpOnly` / `SameSite` attributes (`SAME_SITE_NONE`/`LAX`/`STRICT`/
  `UNSPECIFIED`), required for a cookie a cross-site/embedded context must send --
  Chromium rejects `SameSite=None` without `Secure`. The existing 3-arg `setCookie`
  is unchanged.
  - Solution: `nativeSetCookie` now carries the three attributes into `CefCookie`
    (`secure`, `httponly`, `same_site` -> `cef_cookie_same_site_t`).

### Fixed
- `loadHtmlWithBaseUrl` / `loadDataWithBaseUrl` now render the HTML string as a
  GENUINE network response at `baseUrl`, not a `data:` document. Inline embedded
  players that require a network-served parent -- notably the YouTube IFrame player
  -- now PLAY; previously they failed with Error 152 (a `data:` document is rejected
  client-side, the same way Android WebView's `loadDataWithBaseURL` fails -- this is
  something CEF can do that WebView cannot). Validated on device: a YouTube IFrame
  plays inline via `loadHtmlWithBaseUrl(html, "https://your-app-origin")`.
  - Solution: register the HTML under `baseUrl` and serve it as a real 200 response
    on navigation (one-shot `CefResourceRequestHandler` intercept); every other
    request hits the network. The committed document then has a true network origin
    == `baseUrl`.
  - API note: pass YOUR OWN app origin as `baseUrl` (as any website embedding
    YouTube does), NOT `https://www.youtube.com` -- YouTube rejects same-origin
    self-embedding with 152.

## [0.5.1] -- 2026-06-13

Chromium 149.0.7827.102.

### Added
- `CefriumBrowser.loadHtmlWithBaseUrl(html, baseUrl)` and the full
  `loadDataWithBaseUrl(baseUrl, data, mimeType, encoding, historyUrl)` (plus Compose
  `CefriumNavigator` parity). Loads an HTML string with a real origin/referrer,
  mirroring Android WebView's `loadDataWithBaseURL`. Fixes embeds that
  postMessage-validate their parent origin (e.g. the YouTube IFrame player, which
  fails with Error 153 from an opaque `data:` origin).
  - Solution: new `CefBrowserHostBase::LoadMainFrameDataWithBaseURL` does a content
    `LOAD_TYPE_DATA` navigation (`base_url_for_data_url` + `data_url_as_string`),
    mirroring `content_shell`. Internal libcef method now (the public
    `CefBrowserHost` API + translator regen is a separate upstream patch).

### Fixed
- Startup crash on Xiaomi MIUI/HyperOS (e.g. Redmi Note 13 Pro, Android 16): an OEM
  resource resolver threw `Resources.NotFoundException` during JNI init, aborting
  the process before any UI.
  - Solution: guard `DeviceFormFactor.detectScreenWidthBucket` and fall back to the
    phone bucket. Upstream-bound Chromium CL (general robustness, not CEF-specific).
- Native FCM `FirebaseMessaging.getToken()` `NoClassDefFoundError` for
  `androidx/datastore/.../PreferencesKeys` on some devices.
  - Solution: declare `androidx.datastore:datastore-preferences` in the SDK POM so
    consumers get it transitively (the vendored Firebase token path needs it).

### Packaging
- Stripped the orphaned `okio` classes from the AAR (`slim_aar.py`). okio had 0
  references in the AAR (its androidx users were already stripped), so it was dead
  weight that collided with any consumer pulling okio-jvm (e.g. via datastore).
  - Decision: strip it (same consumer-provides model already used for
    androidx/kotlin/kotlinx), NOT a POM `<exclusion>` -- Gradle Module Metadata
    bypasses POM exclusions for okio. Consumers now get a single okio-jvm.
- Release tooling (`publish-maven.sh`) hardened: verify re-fetches retry on a
  transient empty body; uploads retry through Codeberg's post-delete teardown
  (HTTP 400/000) but fail fast on a permanent content-validation 400 (e.g. a
  malformed POM).

## [0.5.0] -- 2026-06-10

Chromium 149.0.7827.102.

### Added
- Device chooser UI host for WebUSB / Web Bluetooth / Web Serial in the
  libcef-android layer (delegates inherited from the chrome runtime).
- Compose API parity for the SDK surface (Compose `CefriumNavigator` /
  `CefriumBrowser` wrappers), published as `com.cefrium:cefrium-compose`.
- Documentation pass: device chooser across QUICKSTART + web + Javadoc.

## [0.4.1] -- 2026-06-09

Chromium 149.0.7827.102 -- security respin.

### Fixed
- Picked up the Chromium 149.0.7827.102 security fixes, including CVE-2026-11645
  (V8 0-day exploited in the wild). Same-branch parity bump from .53.

### Changed
- User-Agent derived from the engine version; removed the logo name from the UA.
- Registry hygiene: superseded 0.3.2 and 0.4.0 pruned from the Maven registry
  (latest-only retention; older versions remain as GPG-signed Codeberg releases).

## [0.4.0] -- 2026-06-08

Chromium 149.0.7827.53 (CEF branch 7827).

### Changed
- Engine bump 148 -> 149 for CVE parity with Debian Chromium. 20 Android
  patches; one AAR carries arm64-v8a and x86_64.

## [0.3.2] -- 2026-06-08

Chromium 148.0.7778.167 (CEF branch 7778).

### Added
- Web Notifications (service-worker `showNotification`) in the Android shade.
- Native FCM push (bring your own Firebase).
- Per-origin permissions via `setContentSetting` (View and Compose).

## [0.3.1] -- 2026-06-06

### Added
- Maven-consumable fat AAR (resources and manifest included).
- Zero-config init, no Application boilerplate.
- SSL certificate-error and HTTP-auth handlers (WebView parity).

## [0.3.0] -- 2026-06-05

### Added
- Web permissions, getUserMedia, system media controls, print to PDF, cookie
  and dynamic-settings APIs.

## [0.2.1] -- 2026-06-04

### Changed
- First multi-ABI artifact (arm64-v8a and x86_64 in one AAR).

## [0.2.0] -- 2026-06-04

### Changed
- Per-architecture artifacts; superseded the same day by 0.2.1.

## [0.1.0] -- 2026-05-26

Chromium 148.0.7778.167 (CEF branch 7778). Early validation release.
