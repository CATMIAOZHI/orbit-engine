# Quick Start - Cefrium SDK

Scope: this document is for CEFRIUM (CEF ported to native Android) and its SDK, not upstream CEF.

From zero to rendering a web page in 5 minutes.

> **App size.** Cefrium bundles the Chromium engine (~150 MB native per ABI).
> The dependency AAR is multi-ABI (~240 MB); publish an **Android App Bundle**
> (or use ABI splits) so each user downloads only their architecture (~150 MB).

## 1. Create an Android project

In Android Studio: File -> New -> New Project -> Empty Activity.
- Package: `com.example.myapp`
- Min SDK: 29
- Language: Java or Kotlin

## 2. Add Cefrium dependency

**settings.gradle:**
```groovy
pluginManagement {
    repositories {
        maven { url 'https://codeberg.org/api/packages/cefrium/maven' }
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://codeberg.org/api/packages/cefrium/maven' }
    }
}
```

**app/build.gradle:**
```groovy
plugins {
    id 'com.android.application'
    id 'com.cefrium' version '0.6.0'
}

android {
    compileSdk 35
    defaultConfig {
        minSdk 29
        targetSdk 34
    }
    // Chromium assets must be uncompressed
    androidResources {
        noCompress 'dat', 'pak', 'bin'
    }
}

dependencies {
    implementation 'com.cefrium:cefrium-sdk:0.6.0'  // multi-ABI; App Bundle/ABI splits ship one ABI per device
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'androidx.mediarouter:mediarouter:1.7.0'
    implementation 'androidx.core:core:1.16.0'
    implementation 'androidx.browser:browser:1.8.0'
    implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.8.22'
}
```

## 3. Create the Application class

Cefrium needs initialization before any browser can be created.
This boilerplate is required because CEF manages its own process
model (multi-process with renderer as Android Service).

**MyApp.java:**
```java
package com.example.myapp;

import android.app.Application;
import android.util.Log;
import com.cefrium.Cefrium;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.ContextUtils;
import org.chromium.base.PathUtils;
import org.chromium.base.library_loader.LibraryLoader;
import org.chromium.base.library_loader.LibraryProcessType;
import org.chromium.ui.base.ResourceBundle;

public class MyApp extends Application {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        boolean isBrowser = !ContextUtils.getProcessName().contains(":");
        ContextUtils.initApplicationContext(this);
        ResourceBundle.setAvailablePakLocales(new String[]{"en-US"});
        LibraryLoader.getInstance().setLibraryProcessType(
            isBrowser ? LibraryProcessType.PROCESS_BROWSER
                      : LibraryProcessType.PROCESS_CHILD);
        if (isBrowser) {
            PathUtils.setPrivateDataDirectorySuffix("cefrium");
            ApplicationStatus.initialize(this);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        boolean isBrowser = !ContextUtils.getProcessName().contains(":");
        if (isBrowser) {
            boolean ok = Cefrium.initialize(this);
            Log.i("MyApp", "Cefrium: " + (ok ? "OK" : "FAILED"));
        }
    }
}
```

> **Do not simplify this boilerplate - each line prevents a hard crash:**
> - Pass `this`, **not** `getApplicationContext()`, to
>   `initApplicationContext()`. `getApplicationContext()` is `null` inside
>   `attachBaseContext()` -> `AssertionError` (`appContext != null`).
> - `setLibraryProcessType(...)` must be called for the **browser** process too
>   (`PROCESS_BROWSER`), not only child processes. Skipping it -> `AssertionError`
>   in `LibraryLoader` (`PROCESS_UNINITIALIZED`) when `Cefrium.initialize` loads
>   the native library.
> - All of the above must run in `attachBaseContext()` (before `onCreate()`),
>   and `Cefrium.initialize()` only in the browser process.
>
> Kotlin is identical (see the `compose-browser` / `cefrium-browser` apps in the
> cefrium/cefrium-browser repo).

## 4. Configure AndroidManifest.xml

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".MyApp"
        android:label="My Cefrium App"
        android:extractNativeLibs="true">

        <!-- Do NOT declare Chromium child-process services here. The SDK AAR
             provides them (40 sandboxed + 3 privileged) and merges them into your
             app. Declaring your own with a lower NUM_SANDBOXED_SERVICES OVERRIDES
             the AAR and caps the render-process pool, which crashes multi-browser /
             iframe-heavy apps on process-per-browser devices ("isolated not
             needed"). If you copied such a block from an older guide, remove it;
             the merged APK manifest should read NUM_SANDBOXED_SERVICES=40. -->

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
```

## 5. Create the Activity

**MainActivity.java:**
```java
package com.example.myapp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import com.cefrium.CefriumBrowser;

public class MainActivity extends Activity {
    private CefriumBrowser browser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        browser = CefriumBrowser.createWithSurface(this);
        layout.addView(browser.getSurfaceContainer(),
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        setContentView(layout);

        browser.setCallback(new CefriumBrowser.Callback() {
            @Override
            public void onTitleChanged(String title) {
                setTitle(title);
            }
        });

        browser.loadUrl("https://example.com");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (browser != null) browser.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (browser != null) browser.onResume();
    }

    @Override
    protected void onDestroy() {
        if (browser != null) browser.close();
        super.onDestroy();
    }
}
```

## 6. Run

Build and run on an arm64 device (most modern Android phones).
You should see `example.com` rendered with full Chromium 148.

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
async response. Register a handler (Compose: `onQuery` on `CefriumView`; raw:
`browser.setQueryHandler(...)`). The bridge is off until you provide a handler.

```kotlin
browser.setQueryHandler { queryId, request, origin, callback ->
    // origin = URL of the calling frame - reject untrusted pages/iframes.
    callback.success("native reply to: $request")   // or callback.failure(code, msg)
    true                                              // handled
}
```
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

There are three notification/push paths, and the choice matters:

- **Web Notifications** (`showNotification`, above) - works out of the box, no
  Firebase.
- **Web Push** (`PushManager.subscribe`) - the W3C API is present and registers
  with FCM, but **production delivery is gated by Google to Chrome's identity**
  (a structural limit for every Chromium fork: the subscription lands on the
  `preprod` channel). Standards-correct, not deliverable in production without
  your own push service.
- **Native FCM** - the production-capable path, using your own Firebase project.
  Because the SDK AAR **vendors** the Firebase classes, you do **not** add
  `firebase-messaging` (it would duplicate classes); you declare the firebase
  manifest entries yourself. Requires a **Play-certified device**
  (`getToken()` hangs on uncertified GMS). Validated end-to-end.

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
(`SpeechRecognition`) and synthesis, Generic Sensors (Accelerometer / Gyroscope /
Orientation / Gravity / LinearAcceleration), WebRTC (`RTCPeerConnection` + data
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

## Next steps

- Add navigation: `browser.goBack()`, `browser.goForward()`, `browser.reload()`
- Run JavaScript: `browser.evaluateJavaScript("...")`
- JS<->native bridge: `browser.setQueryHandler(...)` + `window.cefriumQuery`
- Find in page: `browser.find("text", true, false, false)`
- Dark mode: `browser.setDarkMode(true)`
- Downloads: `browser.setOnDownloadListener(...)`
- Context menu: `browser.setOnContextMenuListener(...)`
- Renderer died (show a reload UI): `browser.setOnRenderProcessTerminatedListener(...)`
  (Compose: observe `CefriumViewState.rendererTermination`)
- Compose: `CefriumView` / `CefriumWebView` from `cefrium-compose`
- See [cefrium-sample](https://codeberg.org/cefrium/cefrium-sample) for complete examples
- See [MIGRATION-CEFRIUM.md](MIGRATION-CEFRIUM.md) for WebView migration guide
- See [API docs](https://cefrium.com/docs) for full Javadoc

## Resource files

The Cefrium Gradle Plugin (`id 'com.cefrium'`) handles R.java
generation for Chromium's resource packages automatically.
No manual resource configuration needed.

## ProGuard / R8

The SDK AAR includes ProGuard rules. No additional configuration needed
for release builds.

## Troubleshooting

**App crashes on launch:** Check that `android:name=".MyApp"` is set in the
manifest. Do NOT declare your own `SandboxedProcessService` entries -- the SDK
provides them; declaring your own caps the render-process pool and crashes
multi-browser apps (see step 4).

**Crash opening many pages ("isolated not needed"):** Your app manifest declares
its own `SandboxedProcessService0..N` with `NUM_SANDBOXED_SERVICES=5`, capping the
pool. Remove that block; the merged APK manifest should read
`NUM_SANDBOXED_SERVICES=40`. See the
[Memory & process model guide](https://cefrium.com/guides/memory-and-processes/).

**White screen:** The browser needs ~2 seconds after `loadUrl` to
connect WebContents to the rendering pipeline. This is normal.

**JDK error:** Use JDK 21 (Android Studio JBR). JDK 22+ breaks Gradle 8.9.
