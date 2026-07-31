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
