# Quick Start — Cefrium SDK

From zero to rendering a web page in 5 minutes.

## 1. Create an Android project

In Android Studio: File → New → New Project → Empty Activity.
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
    id 'com.cefrium' version '0.1.0'
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
    implementation 'com.cefrium:cefrium-sdk:0.1.0'
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

## 4. Configure AndroidManifest.xml

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".MyApp"
        android:label="My Cefrium App"
        android:extractNativeLibs="true">

        <!-- Required: Chromium renderer processes -->
        <meta-data android:name="org.chromium.content.browser.NUM_SANDBOXED_SERVICES"
            android:value="5"/>
        <service android:name="org.chromium.content.app.SandboxedProcessService0"
            android:process=":sandboxed_process0" android:exported="false"/>
        <service android:name="org.chromium.content.app.SandboxedProcessService1"
            android:process=":sandboxed_process1" android:exported="false"/>
        <service android:name="org.chromium.content.app.SandboxedProcessService2"
            android:process=":sandboxed_process2" android:exported="false"/>
        <service android:name="org.chromium.content.app.SandboxedProcessService3"
            android:process=":sandboxed_process3" android:exported="false"/>
        <service android:name="org.chromium.content.app.SandboxedProcessService4"
            android:process=":sandboxed_process4" android:exported="false"/>
        <meta-data android:name="org.chromium.content.browser.NUM_PRIVILEGED_SERVICES"
            android:value="3"/>
        <service android:name="org.chromium.content.app.PrivilegedProcessService0"
            android:process=":privileged_process0" android:exported="false"/>
        <service android:name="org.chromium.content.app.PrivilegedProcessService1"
            android:process=":privileged_process1" android:exported="false"/>
        <service android:name="org.chromium.content.app.PrivilegedProcessService2"
            android:process=":privileged_process2" android:exported="false"/>

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

## Next steps

- Add navigation: `browser.goBack()`, `browser.goForward()`, `browser.reload()`
- Find in page: `browser.find("text", true, false, false)`
- Dark mode: `browser.setDarkMode(true)`
- Downloads: `browser.setOnDownloadListener(...)`
- Context menu: `browser.setOnContextMenuListener(...)`
- Compose: use `CefriumWebView` from `cefrium-compose`
- See [cefrium-sample](https://codeberg.org/cefrium/cefrium-sample) for complete examples
- See [MIGRATION.md](MIGRATION.md) for WebView migration guide
- See [API docs](https://cefrium.com/docs) for full Javadoc

## Resource files

The Cefrium Gradle Plugin (`id 'com.cefrium'`) handles R.java
generation for Chromium's resource packages automatically.
No manual resource configuration needed.

## ProGuard / R8

The SDK AAR includes ProGuard rules. No additional configuration needed
for release builds.

## Troubleshooting

**App crashes on launch:** Check that `android:name=".MyApp"` is set
in the manifest and that all SandboxedProcessService entries are present.

**White screen:** The browser needs ~2 seconds after `loadUrl` to
connect WebContents to the rendering pipeline. This is normal.

**JDK error:** Use JDK 21 (Android Studio JBR). JDK 22+ breaks Gradle 8.9.
