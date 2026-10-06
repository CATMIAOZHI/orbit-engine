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
- **Engine: Chromium 150.0.7871.114 -> 151.0.7922.71** (CEF 7871 -> 7922). Tracks the
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

---

Earlier versions (0.1.0 - 0.3.2) predate this file; their history is in the git
tags and Codeberg releases. Backfill here if needed.
