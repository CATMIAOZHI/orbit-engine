# Cefrium SDK -- Feature Guide

Scope: the feature catalog for the Cefrium SDK (CEF ported to native Android).
Start with [QUICKSTART-CEFRIUM.md](QUICKSTART-CEFRIUM.md) to get a page rendering
in a few minutes; this document covers everything beyond that basic setup.
Migrating an existing WebView app? See
[MIGRATION-CEFRIUM.md](MIGRATION-CEFRIUM.md). Full API: [cefrium.com/docs](https://cefrium.com/docs).

## Capability status

What is implemented and validated today. Every entry reads `Done`
unless it says otherwise; the sections after this one show how to use
the ones that need more than calling a method.

| Capability | Status |
|---|---|
| `libcef.so` links for Android arm64 and x86_64 | Done |
| `CefInitialize` + `CreateBrowser` | Done |
| OnPaint off-screen rendering (pixel buffer) | Done |
| On-screen rendering (Bitmap + ImageView) | Done |
| Touch, scroll, pinch-zoom | Done |
| Multi-process (renderer as Android Service) | Done |
| `is_official_build=true` (LTO + R8) | Done |
| CEF C API fully available | Done |
| SDK AAR for Gradle integration | Done |
| Web Speech API (text-to-speech) | Done |
| Scroll-driven CSS animations | Done |
| Mobile viewport (device scale factor) | Done |
| Ad blocking (network-level request interception) | Done |
| User-Agent derived from the engine version (Chrome/NNN Cefrium/1.0) | Done |
| H.264/AAC video (proprietary codecs build) | Done |
| **Video playback (Surface rendering)** | **Done** |
| **Audio output** | **Done** |
| Surface rendering via ContentViewRenderView | Done |
| Context menu (long press - CEF API) | Done |
| Find in page (CefBrowserHost::Find) | Done |
| File dialog (`<input type=file>`) | Done |
| Downloads (CefDownloadHandler) | Done |
| Dark mode (CefRequestContext::SetChromeColorScheme) | Done |
| Unified Callback interface | Done |
| Lifecycle pause/resume | Done |
| Jetpack Compose wrapper (CefriumWebView) | Done |
| Idiomatic Compose API (CefriumView / state hoisting + keep-alive) | Done |
| evaluateJavaScript (CefFrame::ExecuteJavaScript) | Done |
| JavaScript <-> native bridge (window.cefriumQuery, CefMessageRouter) | Done |
| Cookie management (CefCookieManager: get/set/delete/flush) | Done |
| Dynamic WebSettings (runtime User-Agent, JavaScript on/off, zoom) | Done |
| Navigation: loadHtml / postUrl / stopLoading | Done |
| Request interceptor (programmatic block, CefResourceRequestHandler) | Done |
| JS dialogs (alert / confirm / prompt, CefJSDialogHandler) | Done |
| Popups (window.open / target=_blank, OnBeforePopup) | Done |
| Cache control (clearCache) | Done |
| Print to PDF (CefBrowserHost::PrintToPDF, URL/page -> PDF) | Done |
| Web permissions (geolocation; CefPermissionHandler -> app decision) | Done |
| Web Notifications (service-worker showNotification -> Android shade) | Done |
| Per-origin content settings (setContentSetting - pre-grant a permission) | Done |
| WebRTC getUserMedia (camera + mic capture) | Done |
| WebGL + WebGPU (hardware-accelerated) | Done |
| HTML5 fullscreen video (OnFullscreenModeChange) | Done |
| Document Picture-in-Picture (documentPictureInPicture) | Done |
| Video Picture-in-Picture (requestPictureInPicture) | Crash-safe; no float window (no Tab) |
| System media controls (lock-screen / shade, `setMediaSessionEnabled`) | Done |
| Nested scroll interop (browser inside a scrollable parent) | Done |
| arm64 + x86_64 validated on device + emulator | Done |
| Published on Codeberg Packages (Maven) | Done |
| Android Accessibility (TalkBack) | Done |
| Android Autofill (Proton Pass, Google) | Done |
| Widevine DRM (Netflix, Disney+, Spotify) | Done |
| Web Bluetooth / WebUSB / Web Serial device chooser (CefDeviceChooserHandler) | Done (0.5.0) |
| WebAuthn / passkeys (navigator.credentials via Android Credential Manager) | Done (0.5.0) |
| Web Share (navigator.share -> Android share sheet) | Done (0.5.0) |
| Clipboard (navigator.clipboard read/write) | Done (0.5.0) |
| Payment Request / Google Pay (navigator PaymentRequest -> Android payment apps) | Done (0.5.0) |
| Contact Picker (navigator.contacts.select) | Done (0.5.0) |
| File System Access (showOpenFilePicker / showSaveFilePicker / showDirectoryPicker) | Done (0.5.0) |
| File upload (`<input type=file>`) + camera/gallery capture | Done (0.5.0) |
| WebOTP (navigator.credentials OTPCredential - SMS one-time-code autofill) | Done (0.5.0) |
| Web Speech recognition (SpeechRecognition - voice to text) | Done (0.5.0) |
| Screen capture (getDisplayMedia -> MediaProjection) | Done (0.5.0) |
| Web NFC (NDEFReader read/write) | Done (validated on device) |
| Generic Sensors (Accelerometer, Gyroscope, Orientation, Gravity, ...) | Done (0.5.0) |
| MediaRecorder / ImageCapture / WebCodecs / MediaCapabilities | Done (0.5.0) |
| Shape Detection - BarcodeDetector (QR / EAN / Code128 / ...) | Done (0.5.0) |
| WebRTC peer connections + data channels (RTCPeerConnection) | Done (0.5.0) |
| Origin Private File System (navigator.storage.getDirectory) | Done (0.5.0) |
| Idle Detection, Battery, Network Info, Vibration, Gamepad, Cookie Store, Web Locks | Done (0.5.0) |
| Verified: Cefrium exposes essentially the full modern Chromium web platform | Done (0.5.0) |
| Page snapshot to Bitmap (captureBitmap; OSR + Surface) | Done (0.5.0) |
| Proxy configuration (CefRequestContext; setProxy/clearProxy) | Done (0.5.0) |

## Jetpack Compose

Add `com.cefrium:cefrium-compose` and use the idiomatic state-hoisting API:

```kotlin
val state = rememberCefriumViewState("https://example.com")
val navigator = rememberCefriumNavigator()

CefriumView(state = state, modifier = Modifier.fillMaxSize(), navigator = navigator)

if (state.isLoading) LinearProgressIndicator()
Text(state.pageTitle ?: "")
// navigator.navigateBack() / navigateForward() / reload() / loadUrl(...)
// navigator.evaluateJavaScript("document.title = 'hi'")
```

Each `CefriumViewState` owns its browser, so tabs stay alive when inactive
(no reload on switch). The lower-level `CefriumWebView` (callback-based) is also
available.

## JavaScript <-> native bridge

Page JavaScript can call your native code via `window.cefriumQuery` and get an
async response. Register a handler together with the origins allowed to reach
it (Compose: `bridge = CefriumBridge(policy) { ... }` on `CefriumView`; raw:
`browser.setQueryHandler(policy, handler)`). The bridge is off until you
provide a handler.

The policy is required because the bridge is the one path from page content
into your code: without it, any page the browser loads, including a
third-party iframe, could call your handler. A call from an origin the policy
does not allow fails on the page with `CefriumBrowser.QUERY_ERROR_ORIGIN_DENIED`
(-403, `origin not allowed`) and never reaches the handler.

```kotlin
browser.setQueryHandler(QueryOriginPolicy.exactly("https://app.example.com")) {
        queryId, request, origin, callback ->
    callback.success("native reply to: $request")   // or callback.failure(code, msg)
    true                                              // handled
}
```

| Policy | Allows |
|---|---|
| `exactly("https://a.example", ...)` | those origins only |
| `loopback()` | http and https on 127.0.0.1 or localhost, any port |
| `bundledAssets()` | pages under `file:///android_asset/` |
| `anyOrigin()` | everything, including `data:` and `about:`; logs a warning |
| your own lambda | whatever it returns true for; it receives the frame URL |
```js
window.cefriumQuery({
    request: "hello",
    onSuccess: function (r) { console.log(r); },
    onFailure: function (code, msg) {},
});
```

## Web Notifications & Push

A page's **service worker** can post a real Android notification to the system
shade via `ServiceWorkerRegistration.showNotification()`. (The plain
`new Notification(...)` constructor is intentionally disabled on Android by
Chromium - use the service-worker path.)

Requirements:
- Declare `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>`
  and request it at runtime (Android 13+).
- Keep `implementation 'androidx.browser:browser:1.8.0'` (already in the deps
  above) - Chromium's notification path references it.
- The page must be a **secure context** (https, or localhost during dev).

Permission flows through your `PermissionHandler` (the user's grant/deny). To
**pre-grant** notifications for an origin you own (no prompt), use the per-origin
content-settings API:

```java
browser.setContentSetting("https://your-app.example",
    CefriumBrowser.PERMISSION_NOTIFICATIONS, CefriumBrowser.SETTING_ALLOW);
```

The notification and push paths:

- **Web Notifications** (`showNotification`, above) - works out of the box, no
  Firebase.
- **Web Push** (`PushManager.subscribe`) - over FCM by default, or over a
  transport the app installs (`cefrium-unifiedpush`, or a relay through the
  app's own Firebase project). An app without the Google libraries has no
  default transport, so it installs one.
- **Native FCM** - the app's own Firebase project; `firebase-messaging` comes
  in through `cefrium-gms`. Requires a **Play-certified device** (`getToken()`
  hangs on uncertified GMS).

Full walkthrough (with the manifest, Gradle, token retrieval, and sending a test
push): **<https://cefrium.com/guides/push-notifications/>** and the
[push-demo sample](https://codeberg.org/cefrium/cefrium-sample/src/branch/main/push-demo).

## Device choosers: Web Bluetooth / WebUSB / Web Serial (0.5.0+)

When a page calls `navigator.bluetooth.requestDevice()`,
`navigator.usb.requestDevice()` or `navigator.serial.requestPort()` (under a user
gesture, in a secure context), Cefrium asks your app to present the picker -
there is no built-in chooser dialog in this embedding. Without a handler the
request is cancelled (the page promise rejects); it never hangs.

```java
browser.setDeviceChooserHandler(new CefriumBrowser.DeviceChooserHandler() {
    @Override public void onShowDeviceChooser(CefriumBrowser.DeviceChooserSession s) {
        // s.getType() is DEVICE_BLUETOOTH / DEVICE_USB / DEVICE_SERIAL.
        // Show your own UI; keep the session to resolve it.
    }
    @Override public void onDeviceAdded(CefriumBrowser.DeviceChooserSession s,
                                        CefriumBrowser.ChooserDevice d) {
        // A candidate appeared (streams in while a Bluetooth scan runs).
        // d.name (may be empty) and d.id; call s.select(d.id) to pick it.
    }
    @Override public void onDeviceChooserClosed(CefriumBrowser.DeviceChooserSession s) {
        // The engine closed the chooser; dismiss your UI.
    }
    // Optional: onDeviceRemoved(session, deviceId) when a device goes away.
});
```

For Web Bluetooth, declare and request the runtime permissions
`BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (Android 12+). Web Serial has no Android
backend (the chooser opens but stays empty). See the flagship `SurfaceActivity`
for a complete `AlertDialog` picker. Compose: `navigator.setDeviceChooserHandler(...)`.

## WebAuthn / passkeys (0.5.0+)

`navigator.credentials.create()` / `.get()` work end-to-end through the Android
**Credential Manager** (the system passkey UI: fingerprint/PIN, Google Password
Manager, third-party providers). **No app code is required** - the SDK routes
WebAuthn to Credential Manager and declares the needed
`CREDENTIAL_MANAGER_SET_ORIGIN` permission in its manifest (inherited via manifest
merge). Requirements: a **secure context** (https; `localhost` is rejected by
passkey providers for *creation*), **Android 14+**, and a Play-certified device
with a screen lock. On older devices the request falls back gracefully (rejects,
no crash). Validated end-to-end against webauthn.io (register + authenticate).

### IMPORTANT: third-party passkey managers and the privileged-app allowlist

**Creating** a passkey in a third-party manager (Proton Pass, 1Password, Bitwarden,
Dashlane...) works from any app. **Using an existing passkey does not**, unless your
app is on the platform's privileged-browser allowlist:

    https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json

Third-party managers honour that list. When an app that is not on it issues a
cross-origin `navigator.credentials.get()`, the provider is queried, answers
normally, and deliberately returns **no credential** (`TYPE_NO_CREDENTIAL` /
"No credentials available"); the request then falls back to the platform's
cross-device (hybrid/Bluetooth) flow. This is a deliberate anti-impersonation
protection, not an SDK defect: an unlisted app must not be able to ask a password
manager to hand over a user's existing passkey for, say, `google.com`. Listed
browsers (Chrome, Firefox, Edge, Brave, Opera, Vivaldi, Samsung Internet, ...)
are unaffected, which is why the same account works there.

What this means for you:

- Passkey **registration** works out of the box.
- Passkey **sign-in against a third-party manager** requires your app (package name
  + release signing certificate SHA-256) to be added to that allowlist. Google
  Password Manager and platform/device credentials are not affected.
- Plan your auth UX accordingly, and offer a fallback (password, OTP, or a passkey
  stored in Google Password Manager) until/unless your app is listed.

## Payment Request / Google Pay (0.5.0+)

The W3C **Payment Request API** (`new PaymentRequest(...).show()`) works end-to-end
and launches installed **Android payment apps** (Google Pay, bank apps, etc.) - the
same model as Android System WebView. **No app code is required**; the SDK registers
a no-UI payment factory at first navigation and the invoked payment app draws its own
UI. `canMakePayment()` / `hasEnrolledInstrument()` also work.

Requirements:
- A **secure context** (https). `localhost`/http is rejected by Google Pay.
- A matching **installed Android payment app**. Google Pay additionally needs a Google
  account + Google Wallet on the device.
- Only installed Android payment apps are supported (not web/service-worker payment
  handlers) - identical to WebView.

```js
// Minimal Google Pay request (TEST environment shown; no real charge).
const googlePay = {
  supportedMethods: "https://google.com/pay",
  data: {
    environment: "TEST", apiVersion: 2, apiVersionMinor: 0,
    allowedPaymentMethods: [{
      type: "CARD",
      parameters: { allowedAuthMethods: ["PAN_ONLY", "CRYPTOGRAM_3DS"],
                    allowedCardNetworks: ["MASTERCARD", "VISA"] },
      tokenizationSpecification: {
        type: "PAYMENT_GATEWAY",
        parameters: { gateway: "example", gatewayMerchantId: "exampleGatewayMerchantId" }
      }
    }],
    merchantInfo: { merchantName: "Your Store" }
  }
};
const request = new PaymentRequest([googlePay],
    { total: { label: "Total", amount: { currency: "USD", value: "1.00" } } });
const response = await request.show();   // opens the Google Pay sheet
await response.complete("success");
```

For production, replace `environment: "TEST"`, the gateway, and the merchant ID with
your real Google Pay merchant configuration, and serve the page over https. Quick way
to see the live sheet on a device with Google Wallet: open the Chrome team's public
test page `https://rsolomakhin.github.io/pr/gp2-test/` in the browser and tap pay -
the Google Pay sheet ("Pay ... to Example Merchant", TEST) opens. Cefrium enables
PaymentRequest **by default**; System WebView requires an explicit per-WebView opt-in
(`WebSettingsCompat.setPaymentRequestEnabled`) and only on recent WebView versions.

## File uploads & File System Access (0.5.0+)

`<input type=file>`, camera/gallery capture, and the **File System Access API**
(`window.showOpenFilePicker()` / `showSaveFilePicker()` / `showDirectoryPicker()`)
all open the Android document/photo picker and deliver the chosen file(s) to the
page. `showDirectoryPicker()` additionally shows a "let this site view files in
this folder?" permission prompt.

**Required setup:** these flows launch the Android picker with
`startActivityForResult`, so your Activity must forward its result to the browser,
or the picker opens but never returns a file:

```kotlin
// View API
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    browser.onActivityResult(requestCode, resultCode, data)
}

// Compose API -- forward to each visible CefriumViewState
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    state.onActivityResult(requestCode, resultCode, data)
}
```

For reading files you typically also declare `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`
(API 33+) or `READ_EXTERNAL_STORAGE` (API <= 32). The SDK supplies a
`ModalDialogManager` to its window, so tab-modal prompts like the folder-permission
dialog work without app code. The `cefrium-sample` flagship shows the full wiring.

## WebOTP - SMS one-time-code autofill (0.5.0+)

`navigator.credentials.get({ otp: { transport: ["sms"] } })` resolves with the
one-time code from an incoming SMS, via the Android SMS User Consent API (a system
"let [app] read this message?" prompt - **no SMS permission required**). Works with
no app code beyond the `onActivityResult` forwarding above (the consent prompt
returns through it).

Requirements: a **secure context** with a **default port** (https on 443, or
http://localhost on 80) - the WebOTP SMS binding `@host #code` cannot encode a
non-default port. The SMS's last line must be `@<your-origin-host> #<code>` (e.g.
`@example.com #123456`), and it must come from a sender that is not in the user's
contacts. Validated end-to-end on device (page on https origin, SMS from a third
party -> consent prompt -> `OTPCredential.code`).

## Other APIs (0.5.0+)

```java
// Web Share: navigator.share() opens the Android share sheet automatically
// (no app code needed; the hosting Activity is used).

// Proxy (e.g. kiosk/enterprise): route this browser's traffic.
browser.setProxy("myproxy.example:8080");   // fixed server
browser.clearProxy();                        // back to direct

// Snapshot the current page to a Bitmap (OSR next-frame; Surface via PixelCopy).
browser.captureBitmap(bitmap -> { /* use or save bitmap (may be null) */ });

// Clipboard: navigator.clipboard.writeText/readText work; readText prompts via
// your PermissionHandler (CefriumBrowser.PERMISSION_CLIPBOARD).
```

Compose equivalents live on `CefriumNavigator` (`setProxy`, `clearProxy`,
`captureBitmap`, `setDeviceChooserHandler`).

## Web NFC

`new NDEFReader().scan()` / `.write()` read and write NDEF tags. A unique
differentiator: System WebView has no Web NFC. Validated read + write round-trip on
device. To enable it in your app:

1. Declare both permissions in your manifest -- `android.permission.NFC` and
   `android.permission.VIBRATE` (the engine buzzes on tag detection and the
   read/write aborts without it). NFC must be enabled on the device.
2. Grant the NFC web permission, either per-request in your
   `setPermissionHandler` (`PERMISSION_NFC`) or by pre-authorizing the origin:

```java
browser.setContentSetting("https://your.site",
        CefriumBrowser.PERMISSION_NFC, CefriumBrowser.SETTING_ALLOW);
```

See [`NfcTestActivity`](https://codeberg.org/cefrium/cefrium-sample) for a full
read/write example.

## The rest of the web platform (0.5.0+)

Because Cefrium runs the full Chromium engine, the broad modern web platform works
out of the box with no SDK code - verified on device: Web Speech recognition
(`SpeechRecognition`) and synthesis, WebRTC (`RTCPeerConnection` + data
channels), `MediaRecorder`, `ImageCapture`, WebCodecs, `MediaCapabilities`,
`BarcodeDetector` (QR/EAN/Code128/...), WebGL2, WebGPU, OffscreenCanvas, Origin
Private File System (`navigator.storage.getDirectory`), IndexedDB, Cache API, Web
Locks, Cookie Store, Storage Access, Battery, Network Information, Idle Detection,
Vibration (needs `android.permission.VIBRATE`; Android skips it in silent mode),
Gamepad, Pointer Lock, Presentation / Remote Playback, getScreenDetails, Permissions
API, FedCM, AudioContext / AudioWorklet, CompressionStream, BroadcastChannel, and
View Transitions. Not available on Android (platform, not Cefrium): Face/Text
Detector, Handwriting Recognition, Chrome built-in AI (Translator / Language
Detector), Compute Pressure, Local Font Access, registerProtocolHandler.

### Motion sensors: use DeviceMotionEvent, not the Generic Sensor API (known gap)

For device motion and orientation, use the older
`DeviceMotionEvent` / `DeviceOrientationEvent`. They work and deliver real readings.

The **Generic Sensor API** (`Accelerometer`, `Gyroscope`, `GravitySensor`,
`LinearAccelerationSensor`, `AbsoluteOrientationSensor`) is exposed, activates, and
then delivers readings **unreliably**: on a fresh page load none arrived within 75
seconds of a successful `start()`, while a page left open for about an hour did
eventually receive a continuous stream. Treat it as unusable for anything that needs
a reading promptly. Measured on a W90 tablet from a secure `http://localhost` origin,
Cefrium 0.9.2 / Chromium 152:

| What was checked | Result |
|---|---|
| `isSecureContext`, permissions policy | true, `accelerometer` and `gyroscope` allowed |
| `navigator.permissions.query({name:'accelerometer'})` | `granted` |
| `new Accelerometer({frequency:10})`, `.start()` | constructs, returns, no throw |
| `activate` event | fires |
| `sensor.activated` after 2 s | `true` |
| `activate` event | fires at about +350 ms |
| `sensor.hasReading` / `sensor.timestamp` after 2 s, and after 75 s | **`false` / `null`** |
| the same page after roughly an hour open | a continuous `reading` stream did arrive |
| `DeviceMotionEvent` on the same hardware | **works immediately**, real values |

So it is not the origin, the permission, the policy or the device: the gap is in the
Generic Sensor reading pipeline in this embedding, and it is a latency/reliability
failure rather than a total absence. Treat any Generic Sensor class as unusable
until it is fixed.

> Recorded because it cost us: an early 2-second probe window produced the
> conclusion "delivers nothing", which was too strong. A long-lived page disproved
> it. When a reading never arrives, measure the time to first reading explicitly
> before calling the API dead.

A reproducible probe that prints all of the above and beacons it back ships in
`cefrium-sample/kiosk-pos/app/src/main/assets/sensor-probe/`.

## Inline HTML with a real origin: loadHtmlWithBaseUrl (0.5.2+)

Render an HTML string that has a REAL, network-served origin -- so inline embedded
players work, notably an inline YouTube IFrame:

```kotlin
val html = """
  <!doctype html><body style="margin:0;background:#000">
  <iframe width="100%" height="100%" frameborder="0" allow="autoplay; encrypted-media"
    src="https://www.youtube.com/embed/VIDEO_ID?autoplay=1&mute=1&playsinline=1"></iframe>
  </body>
"""
navigator.loadHtmlWithBaseUrl(html, "https://app.example.com")   // your own app origin
```

Cefrium serves the document as a genuine 200 network response at `baseUrl` (not a
`data:` document), which is what the YouTube player requires. **Pass your own app
origin as `baseUrl`** (as any website embedding YouTube does) -- NOT
`https://www.youtube.com` (YouTube rejects same-origin self-embedding with Error
152). This is a Cefrium capability that Android WebView's `loadDataWithBaseURL`
does not have (it always yields a `data:` document, so the embed fails there).
View API: `browser.loadHtmlWithBaseUrl(html, baseUrl)`. Full sample:
`cefrium-sample/loaddata-baseurl`.

## Many pages / lesson lists: reuse or pool, don't keep N alive (0.5.2+)

A Cefrium browser is HEAVY -- a full Chromium engine context, far more memory than a
WebView. Do NOT keep one alive per item the user ever opened (a list of lessons,
articles, tabs) or memory grows without bound. Two correct patterns:

1. **One view, swap content (cheapest).** Keep a single `CefriumView` and change
   page with `navigator.loadUrl(...)` / `loadHtmlWithBaseUrl(...)`. No accumulation;
   the previous page (and its media) is replaced.
2. **Bounded pool (keep the last few warm).** Use `rememberCefriumBrowserPool` to
   keep at most N browsers alive, closing the least-recently-used automatically:

   ```kotlin
   val pool = rememberCefriumBrowserPool(maxAlive = 2)
   val state = pool.stateFor(lesson.id, lesson.url)   // reuses or creates
   CefriumView(state = state)
   // Opening a 3rd lesson closes the least-recently-used browser; memory stays bounded.
   ```

By default a `rememberCefriumViewState` browser is released automatically when its
`CefriumView` leaves composition (e.g. navigating away in a NavHost), so a
one-screen-per-page app does not leak. The pool is for when you want the last few
pages to stay warm (instant back, preserved scroll/video position) without unbounded
growth. To preserve a video's position without keeping the browser warm, save the
timestamp and `seekTo` on return instead. Sample: `cefrium-sample/loaddata-baseurl`
(`PoolActivity`).

Background media: `CefriumView` auto-pauses inline video/audio when the host is
backgrounded (see `backgroundMediaPolicy`); no work required.

## What the engine contacts in the background, and how to turn it off (0.9.3+)

Cefrium embeds a real Chromium engine, and like Chromium it keeps some of its data
current over the network. Audited on device; these are the only two outbound
requests the engine makes that are not your app's own browsing:

| What | Where | Default | Carries |
|---|---|---|---|
| Component updater | `update.googleapis.com` (HTTPS) | **on** | component ids + versions, platform, a random per-component install id |
| Network time | `http://clients2.google.com/time/1/current` | **off since 0.9.3** | nothing identifying |

The defaults differ because the two requests are not comparable. One is a security
feed whose absence degrades the engine silently; the other only improves an error
message. So the engine's data feed stays on and you opt out of it, while the time
query is off and you opt back in. Upstream Chromium has both on; the time query is
the one place Cefrium deliberately diverges from the engine's default.

The component updater is how the engine downloads the certificate revocation list
(CRLSet), Certificate Transparency log metadata, the dangerous-download file-type
policy, TLS error diagnostics, Trust Token key commitments, First-Party Sets and the
subresource filtering ruleset. **It is a security feed, not telemetry.**

**What the engine does NOT do, each one verified rather than assumed:** no usage
statistics or metrics upload (Chromium's UMA/UKM reporting is off and no consent is
recorded), no crash-report upload (the crash reporter is never configured, so it is
never started), no Chromium field-trial/"variations" seed fetch, and no Google Cloud
Messaging (disabled in our build). The SDK also ships without Google API keys, so the
engine's Google account, sync and optimization-guide backends are inert.

### Changing either default

Both controls must be set before the engine starts. It starts the first time
the app uses it (a browser, a profile, a filter list), so `Application.onCreate`
is early enough; `attachBaseContext` works too, and was required before 0.16.0,
when the SDK started the engine from its ContentProvider (the kiosk-pos sample
uses it):

```java
@Override
protected void attachBaseContext(Context base) {
    super.attachBaseContext(base);
    Cefrium.setComponentUpdatesEnabled(false);   // opt OUT of the security data feed
    Cefrium.setNetworkTimeQueriesEnabled(true);  // opt back IN to the time query
}
```

```kotlin
override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)
    Cefrium.setComponentUpdatesEnabled(false)
    Cefrium.setNetworkTimeQueriesEnabled(true)
}
```

Called after initialization they are ignored and log a warning. Read the current
state with `Cefrium.areComponentUpdatesEnabled()` /
`Cefrium.areNetworkTimeQueriesEnabled()`.

Both map onto Chromium's own switches (`--disable-component-update` and
`--disable-features=NetworkTimeServiceQuerying`), so they keep working across engine
updates instead of depending on a patch we have to carry.

### Read this before disabling component updates

**This is a security trade-off, not just a privacy setting.** With component updates
off, every data file listed above freezes at the state baked into the SDK version you
shipped. In particular **certificate revocation data stops being refreshed**, so a
certificate revoked after your build date will still be accepted by your app. The
failure mode is silent: nothing crashes, nothing logs an error, and no user
complains.

If you disable it, you are taking on the job of shipping SDK updates promptly, so
pin the Cefrium version deliberately and follow releases.

Legitimate reasons to disable it:

- an air-gapped or offline deployment, where the requests cannot succeed anyway;
- a kiosk on a metered or strictly whitelisted network;
- a compliance regime that forbids unattended third-party requests.

"I would rather not talk to Google" on a general-purpose, internet-connected app is
**not** a good enough reason on its own: you would be trading real revocation
coverage for a short privacy note.

### Read this before re-enabling the network time query

The query's only job is to let Chromium say "your clock is wrong" instead of showing
a generic expired-certificate error. It carries no security data, certificate
validation does not depend on it, and Android normally syncs the clock via the
carrier or NTP -- which is why 0.9.3 turned it off by default.

Turn it back on if your hardware has no dependable clock: a device with no cellular
modem and no NTP reachability, or a kiosk whose RTC has no battery. There a clock far
enough off to break HTTPS is a real support case, and the precise error message is
worth one request a day.

### For your store listing and privacy policy

If you publish on Google Play you must declare what leaves the device. On the 0.9.3
defaults that is the component updater alone: it shares device/app technical data and
a pseudonymous per-component install id with Google, and no user content. The honest
declaration is short, and
Cefrium's own browser publishes exactly that -- see <https://cefrium.com/privacy/>
for wording you can adapt.
