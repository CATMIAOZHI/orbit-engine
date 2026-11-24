# Quick Start - Cefrium SDK

Scope: this document is for CEFRIUM (CEF ported to native Android) and its SDK, not upstream CEF.

From zero to rendering a web page in 5 minutes.

> **App size.** Cefrium bundles the Chromium engine, one `libcef.so` per ABI.
> Publish an **Android App Bundle** (or use ABI splits) so each user downloads
> only their architecture. Sizes measured on each release:
> [what your users download](https://cefrium.com/guides/adopting-cefrium/).

> **Build tooling (0.8.0+).** The SDK ships Chromium 158's Java-25 bytecode, so
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

> **Where it's hosted.** Cefrium is published on its own Maven repository,
> **`https://maven.cefrium.com`**: the `maven { ... }` block in both places
> below. `includeGroup 'com.cefrium'` keeps Gradle from asking it for any other
> library. It keeps the last ten releases (0.18.0 onwards today). The
> [Codeberg Maven registry](https://codeberg.org/cefrium/-/packages)
> (`https://codeberg.org/api/packages/cefrium/maven`) mirrors the current
> release and the one before it. If a build fails with
> `Plugin [id: 'com.cefrium', version: 'X'] was not found`, that version is
> outside the window. Older releases stay on the Codeberg Releases page as the
> git tag and a GPG-signed `SHA256SUMS`; full history in `CHANGELOG-CEFRIUM.md`.
>
> **Keeping a version longer.** Versions up to 0.17.1 also resolve from
> `mavenCentral()`. To keep any version beyond the window, proxy the repository
> in your own repository manager (Nexus, Artifactory, Reposilite), as you would
> for any third-party dependency.
>
> **Staying current.** Renovate or Dependabot can open a pull request when a
> release comes out. For Renovate, in `renovate.json`:
>
> ```json
> {
>   "packageRules": [
>     { "matchPackageNames": ["com.cefrium:*"],
>       "registryUrls": ["https://maven.cefrium.com"] }
>   ]
> }
> ```
>
> For Dependabot, in `.github/dependabot.yml`:
>
> ```yaml
> version: 2
> registries:
>   cefrium:
>     type: maven-repository
>     url: https://maven.cefrium.com
> updates:
>   - package-ecosystem: gradle
>     directory: "/"
>     registries: [cefrium]
>     schedule:
>       interval: weekly
> ```
>
> Or follow the release feed:
> [RSS](https://codeberg.org/cefrium/cef-android/releases.rss) /
> [Atom](https://codeberg.org/cefrium/cef-android/releases.atom).
>
> **Support.** Issues are answered as time allows. Cefrium is funded by the
> people who use it: individuals through
> [Liberapay](https://liberapay.com/cefrium/donate), and teams through
> [support plans](https://cefrium.com/commercial/) with response times and
> integration help.

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
        maven {
            url 'https://maven.cefrium.com'
            content { includeGroup 'com.cefrium' }
        }
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url 'https://maven.cefrium.com'
            content { includeGroup 'com.cefrium' }
        }
    }
}
```

**app/build.gradle:**
```groovy
plugins {
    id 'com.android.application'
    id 'com.cefrium' version '0.22.0'
}

android {
    compileSdk 37
    defaultConfig {
        minSdk 29
        targetSdk 34
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
    implementation 'com.cefrium:cefrium-sdk:0.22.0'  // multi-ABI; App Bundle/ABI splits ship one ABI per device
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'androidx.mediarouter:mediarouter:1.7.0'
    implementation 'androidx.core:core:1.16.0'
    implementation 'androidx.browser:browser:1.8.0'
    implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.8.22'
}
```

## 3. No Application class needed

The SDK sets itself up when the app starts (a content provider in its
manifest) and starts the engine the first time a browser is created. There is
no `Cefrium.initialize()` to call and no Application class to write.

If your app already has an Application class, keep it. Android creates it in
every process of the app, including the engine's renderer processes, which
cannot open your files or preferences. Since 0.22.0 the SDK's
`CefriumAppComponentFactory` keeps it out of those processes. If your
manifest sets its own `android:appComponentFactory`, extend
`com.cefrium.CefriumAppComponentFactory`; before 0.22.0, return early from
`onCreate()` when
`Application.getProcessName()` matches `:(sandboxed|privileged)_process<N>`.
Do not compare against the package name: that also skips your own processes.

## 4. Configure AndroidManifest.xml

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:label="My Cefrium App">

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
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
```

The `configChanges` keep the activity, and so the page, through a rotation;
without them Android recreates the activity and the page starts again from its
first URL.

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
You should see `example.com` rendered with full Chromium 158.

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

What does move the number is which ABIs you ship. The AAR carries `arm64-v8a`,
`armeabi-v7a` and `x86_64`; a consumer App Bundle delivers only one. The sizes,
measured on each release, are in
[what your users download](https://cefrium.com/guides/adopting-cefrium/).

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

An Android App Bundle gives the smallest download, because Play delivers only
the architecture each device needs.

## Troubleshooting

**No page at all, not even a local one:** your Application class does
main-process work (files, preferences) and runs in the engine's processes too;
see step 3. **App crashes on launch:** do NOT declare child-process services
or `NUM_SANDBOXED_SERVICES` yourself -- the SDK provides them; a value of your own caps the
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

**The system WebView in the same package:** supported in the same process
since 0.18.0, in either order, and in a separate process (an Activity or
Service declared with `android:process=":something"`).
Cefrium's child services have their own class names since the release after
0.10.2, so WebView's renderer service can no longer be handed to Cefrium. On
0.10.2 and earlier the page stayed blank and logcat showed `cr_ChildProcessConn:
Child process's classpath doesn't match`; update the SDK. Before 0.18.0, in
the SAME process, Cefrium first killed the app at the first `new WebView()`;
see https://cefrium.com/guides/adopting-cefrium/#webview.

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
