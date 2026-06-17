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
- Renderer-termination callback. `CefriumBrowser.setOnRenderProcessTerminatedListener(
  (status, errorCode) -> ...)` fires when a renderer process for the browser dies --
  e.g. the OEM low-memory killer reclaims it -- so you can show a reload affordance
  instead of a silent blank page. `status` is one of the `CefriumBrowser.TERMINATION_*`
  constants (`ABNORMAL`/`KILLED`/`CRASHED`/`OOM`). Compose: observe the hoisted
  `CefriumViewState.rendererTermination` (a `RendererTermination(status, errorCode)`
  with a `RendererStatus` enum; cleared automatically on the next load), or pass
  `onRenderProcessTerminated` to `CefriumWebView`. Validated on a Redmi/HyperOS device
  (real OEM low-memory kill) and a rooted emulator.

### Fixed
- Renderer terminations reported by Android as `OOM_PROTECTED` / `EVICTED_FOR_MEMORY`
  (the common OEM low-memory-kill path) now reach `OnRenderProcessTerminated` as
  `TS_PROCESS_OOM`. The upstream handler mapped only desktop termination statuses and
  silently dropped these, so the callback never fired for the most common Android
  renderer death. (Upstreamable libcef fix.)

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
