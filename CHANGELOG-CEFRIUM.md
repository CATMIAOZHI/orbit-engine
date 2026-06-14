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

## [0.5.3] -- 2026-06-14

Chromium 149.0.7827.102.

### Fixed
- `loadHtmlWithBaseUrl` / `loadDataWithBaseUrl` now render an HTML string as a
  GENUINE network response at `baseUrl`, not a `data:` document. Inline embedded
  players that require a network-served parent -- notably the YouTube IFrame player
  -- now PLAY; previously they failed with Error 152 (the `data:` document is
  rejected client-side, the same way Android WebView's `loadDataWithBaseURL` fails).
  Validated on device: a YouTube IFrame plays inline via
  `loadHtmlWithBaseUrl(html, "https://your-app-origin")`.
  - Solution: register the HTML under `baseUrl` and serve it as a real 200 response
    on navigation (one-shot `CefResourceRequestHandler` intercept); all other
    requests hit the network. The committed document then has a true network origin
    == `baseUrl`. This is something CEF can do that WebView cannot.
  - API note: pass YOUR OWN app origin as `baseUrl` (as any website embedding
    YouTube does), NOT `https://www.youtube.com` -- YouTube rejects same-origin
    self-embedding with 152.
  - This corrects the 0.5.2 note that called the 152 a WebView-equivalent
    limitation: it is fixable, and is fixed here.

## [0.5.2] -- 2026-06-14

Chromium 149.0.7827.102.

### Added
- `CefriumBrowser.setCookie(url, name, value, secure, httpOnly, sameSite)` and the
  Compose `CefriumNavigator` parity overload. Sets a cookie with explicit
  `Secure` / `HttpOnly` / `SameSite` attributes (`SAME_SITE_NONE`/`LAX`/`STRICT`/
  `UNSPECIFIED`), required for a cookie a cross-site/embedded context must send --
  Chromium rejects `SameSite=None` without `Secure`. The existing 3-arg `setCookie`
  is unchanged.
  - Solution: `nativeSetCookie` now carries the three attributes into `CefCookie`
    (`secure`, `httponly`, `same_site` -> `cef_cookie_same_site_t`).

### Notes
- Inline YouTube IFrame embeds via `loadHtmlWithBaseUrl` load YouTube's real player
  and pass its origin check, but YouTube then applies a SERVER-SIDE integrity check
  that rejects locally-injected documents and shows Error 152 for all videos -- the
  same limitation Android WebView's `loadDataWithBaseURL` has. To embed YouTube
  inline, serve the embed HTML from a real HTTP(S)/localhost origin and use
  `loadUrl()`. `loadHtmlWithBaseUrl` is for documents whose own scripts need a real
  origin, not for third-party players that verify the document was network-served.

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
