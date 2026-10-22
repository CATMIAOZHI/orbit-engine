# Quick Start - Cefrium SDK

Scope: this document is for CEFRIUM (CEF ported to native Android) and its SDK, not upstream CEF.

From zero to rendering a web page in 5 minutes.

> **App size.** Cefrium bundles the Chromium engine (~150 MB native per ABI).
> The dependency AAR is multi-ABI (~240 MB); publish an **Android App Bundle**
> (or use ABI splits) so each user downloads only their architecture (~150 MB).

> **Build tooling (0.8.0+).** The SDK ships Chromium 156's Java-25 bytecode, so
> your app must build with **AGP 9.4+**, **Gradle 9.7.1+**, **JDK 25**, and
> **compileSdk 37**. JDK 25 is a hard floor, not a preference: Chromium 152 and
> newer compile their own Java at `--release 25`, so nearly every class in the
> AAR is class-file 69 and only a few dozen of them are ours -- lowering our own
> compiler target would not move the floor. The `com.cefrium` Gradle plugin is
> built for Java 17 and applies on an older JDK -- it is NOT the cause of a
> class-69 error, which is the wrong diagnosis three integrators reached. On AGP 9 use built-in Kotlin (do not apply
> `org.jetbrains.kotlin.android`; for Compose apply
> `org.jetbrains.kotlin.plugin.compose`), and enable legacy native-lib packaging
> (`packaging { jniLibs { useLegacyPackaging = true } }`) since Chromium reads
> its `.so` files from disk. (0.7.x and earlier used AGP 8.6+/Gradle 8.9/JDK 21.)

> **Existing multi-module app?** Gradle resolves a single AGP version for the
> whole build, so you cannot keep an older module on AGP 8.x and add Cefrium as
> a sibling subproject on AGP 9.4. Give the Cefrium module its **own standalone
> Gradle build** (its own wrapper on 9.7.1+ and `settings.gradle`); it can still
> share sources with your other modules via relative `sourceSets` paths. See
> "Adding Cefrium to one module of an existing multi-module app" in
> Troubleshooting for the exact error and the `kotlin.srcDir` gotcha.

> **Where it's hosted (two sources).** Cefrium is published to **Maven Central**
> (recommended -- `mavenCentral()` already in every build resolves it with no extra
> repository line, and every version stays available permanently) and mirrored on
> the **Codeberg Maven registry** (the `maven { url ... }` line in the settings
> below; latest version only). Either works. If you use Central you can drop the
> Codeberg `maven { url }` lines entirely. If a build fails with `Plugin [id:
> 'com.cefrium', version: 'X'] was not found` against the Codeberg mirror, that
> pinned version was pruned -- either bump to the current version or switch to
> Maven Central, which keeps it. Full version history: the Codeberg Releases page
> and `CHANGELOG-CEFRIUM.md`.
>
> **Pinning a fixed version in production? Depend on it via Maven Central.** Central
> is immutable and retains every version forever, so a pinned coordinate
> (`com.cefrium:cefrium-sdk:X.Y.Z` resolved through `mavenCentral()`) never
> disappears. The Codeberg mirror keeps only the latest version to stay within its
> donated-storage limits, so pinning an older version there will break at the next
> release. Track-latest builds can use either source; pinned/reproducible builds
> should use Central.

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
    id 'com.cefrium' version '0.12.0'
}

android {
    compileSdk 37
    defaultConfig {
        minSdk 29
        targetSdk 34
    }
    // Chromium assets must be uncompressed
    androidResources {
        noCompress 'dat', 'pak', 'bin'
    }
    // Chromium reads its native libraries from disk (AGP 9 requires this in the
    // build script rather than android:extractNativeLibs in the manifest).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation 'com.cefrium:cefrium-sdk:0.12.0'  // multi-ABI; App Bundle/ABI splits ship one ABI per device
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
You should see `example.com` rendered with full Chromium 156.

## Beyond the basics -- full feature guide

The steps above render a page. Everything else the SDK does -- Jetpack Compose,
the JavaScript <-> native bridge, Web Notifications & Push, device choosers
(Bluetooth / USB / Serial), WebAuthn / passkeys, Payment Request, file uploads,
WebOTP, Web NFC, inline HTML with a real origin, browser pooling, and the rest of
the web platform -- lives in **[FEATURES-CEFRIUM.md](FEATURES-CEFRIUM.md)**.

## Next steps

- Add navigation: `browser.goBack()`, `browser.goForward()`, `browser.reload()`
- Run JavaScript: `browser.evaluateJavaScript("...")`
- JS<->native bridge: `browser.setQueryHandler(policy, handler)` + `window.cefriumQuery`;
  the origin policy is required (see FEATURES-CEFRIUM.md)
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

## ProGuard, R8 and APK size

The SDK AAR includes ProGuard rules, so release builds need no extra
configuration: Cefrium's `proguard.txt` already carries the keep rules for
Chromium's JNI and reflection use, and R8 strips the unused Java classes.

What does move the number is which ABIs you ship. The AAR is multi-ABI (~240 MB,
`arm64-v8a` and `x86_64`); a consumer APK or App Bundle carries only one, so the
per-architecture footprint is ~150 MB.

```groovy
// app/build.gradle
android {
    buildTypes {
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'),
                          'proguard-rules.pro'
        }
    }

    // Ship only the architecture your users need
    splits {
        abi {
            enable true
            reset()
            include 'arm64-v8a'  // or 'x86_64' for emulator-only
            universalApk false
        }
    }
}
```

| Configuration | Approximate APK size |
|--------------|---------------------|
| Debug (single arch) | ~250 MB |
| Release + R8 (single arch) | ~150 MB |
| Release + R8 + ABI split | ~150 MB per arch |
| AAB (Play Store) | ~100 MB download |

An Android App Bundle gives the smallest download, because Play delivers only
the architecture each device needs.

## Troubleshooting

**App crashes on launch:** Check that `android:name=".MyApp"` is set in the
manifest. Do NOT declare child-process services or `NUM_SANDBOXED_SERVICES`
yourself -- the SDK provides them; a value of your own caps the
render-process pool and crashes multi-browser apps (see step 4).

**Crash opening many pages ("isolated not needed"):** Your app manifest declares
its own child-process services with a lower `NUM_SANDBOXED_SERVICES` (5, say),
capping the pool. Remove that block; the merged APK manifest should read
`NUM_SANDBOXED_SERVICES=40`. See the
[Memory & process model guide](https://cefrium.com/guides/memory-and-processes/).

**White screen for the first two seconds:** fixed. Earlier releases connected
the rendering pipeline on a fixed 2 s timer after the first `loadUrl`, and
until then a page could neither paint nor accept input. The connection now
happens as soon as the native browser exists. If you still see a white screen,
it is a page or network problem, not the engine warming up.

**The system WebView in the same package:** supported in a separate
process (an Activity or Service declared with `android:process=":something"`).
Cefrium's child services have their own class names since the release after
0.10.2, so WebView's renderer service can no longer be handed to Cefrium. On
0.10.2 and earlier the page stayed blank and logcat showed `cr_ChildProcessConn:
Child process's classpath doesn't match`; update the SDK. WebView and Cefrium
in the SAME process are not supported: both are Chromium, and their native
libraries and Java classes collide.

**JDK error:** For 0.8.0+ use **JDK 25** (Gradle 9.7.1 / AGP 9.4). A `cannot
access CefriumBrowser` or `Unsupported class file major version 69` error means
javac is older than 25 and cannot read the SDK's Java-25 bytecode. The Gradle
plugin is not the cause: it is compiled for Java 17 on purpose. Why the floor
cannot move is explained at the top of this file.
(0.7.x and earlier used JDK 21 / Gradle 8.9.)

**Adding Cefrium to one module of an existing multi-module app:** AGP 9.4+ is a
whole-*build* requirement, not a per-module one -- Gradle resolves one version per
plugin id for the entire invocation. If your app already has another Android
module on an older AGP (a primary WebView/GeckoView module you don't want to
touch yet, say), that module declaring `id 'com.android.application'` without an
explicit version inherits whatever the Cefrium module pinned, and pinning a
different version per module fails fast and unambiguously:
`Error resolving plugin [id: 'com.android.application', version: '9.4.0'] > ...
already on the classpath with a different version (X.Y.Z)`. There is no partial
workaround. Give the Cefrium module its **own standalone Gradle build** (own
wrapper pinned to 9.7.1+, own `settings.gradle.kts`) instead of a subproject of
your existing multi-module build; it can still share source via relative
`sourceSets` paths into your other module's tree. (First external adopter to hit
this: a host app with an isolated Cefrium module alongside an older primary
module on AGP 8.x.)

**Shared/reused Kotlin sources going "unresolved" with AGP 9's built-in Kotlin:**
If your module pulls in Kotlin sources from elsewhere via
`sourceSets["main"].java.srcDir("../other-module/src/main/java")` (a common
pattern for a small Cefrium-only module reusing a larger app's shared code), that
alone is **not enough** under AGP 9's built-in Kotlin. The classic
`org.jetbrains.kotlin.android` plugin used to feed Android's `java.srcDirs` to
the Kotlin compiler too; AGP 9's built-in Kotlin support does not do this
automatically. Symptom: dozens of "Unresolved reference" errors for symbols that
are clearly defined *somewhere*, with zero errors reported in the files that
actually define them (because those files were silently never compiled). Fix:
add the same path via `kotlin.srcDir(...)` as well, e.g.:
```kotlin
sourceSets {
    getByName("main") {
        java.srcDir("../app/src/main/java")
        kotlin.srcDir("../app/src/main/java")   // required too, or those files are dropped
    }
}
```
