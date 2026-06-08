#!/usr/bin/env bash
# test-published-release.sh — DEP-8-style consumer smoke test for a PUBLISHED
# Cefrium release.
#
# Objective (like a Debian autopkgtest): exercise the artifacts exactly as a
# downstream consumer does — resolve the Gradle plugin and the SDK dependency
# FROM THE CODEBERG MAVEN REGISTRY (no local AARs) and build a throwaway app that
# follows cefrium.com/quickstart verbatim. If the published artifacts are corrupt,
# missing, or unconsumable, this build fails — which is the whole point.
#
# This is what would have caught the multipart-envelope upload corruption (every
# POM/AAR stored wrapped in a MIME body) before it reached users: the project's
# own build, the validation harness, and the test APK all use a LOCAL AAR
# (files('libs/...')), so they never touched the registry.
#
# Usage:   ./test-published-release.sh <version>      e.g. 0.3.0
# Env:     JAVA_HOME   JDK 21 (Android Studio JBR)
#          ANDROID_HOME  Android SDK
#          GRADLE      gradle/gradlew binary, Gradle 8.9-compatible (default: gradle)
# Exit:    0 = consumable; non-zero = release is broken (do not announce).
set -euo pipefail

VERSION="${1:?usage: test-published-release.sh <version>}"
# Which SDK coordinate to exercise. Both are published and distinct, and the FOSS
# audience goes straight to the libre one, so the release gate must cover both:
#   ARTIFACT=cefrium-sdk        (default, full codecs)
#   ARTIFACT=cefrium-sdk-libre  (royalty-free)
ARTIFACT="${ARTIFACT:-cefrium-sdk}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# No system gradle is installed on the build host. Honor an explicit GRADLE, else
# `gradle` on PATH, else fall back to a committed sample-module gradlew wrapper
# (gradle 8.9). gradlew builds the `-p` project using its own bundled wrapper.
if [ -z "${GRADLE:-}" ]; then
  if command -v gradle >/dev/null 2>&1; then
    GRADLE=gradle
  else
    for gw in "$SCRIPT_DIR"/../../../../cefrium-sample/*/gradlew; do
      [ -x "$gw" ] && { GRADLE="$gw"; break; }
    done
  fi
fi
: "${GRADLE:?no gradle found: install gradle, set GRADLE, or keep a cefrium-sample/*/gradlew}"
REPO="https://codeberg.org/api/packages/cefrium/maven"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 21 (Android Studio JBR)}"
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
echo "==> Consumer smoke test for $ARTIFACT $VERSION (registry-only) in $WORK"

mkdir -p "$WORK/app/src/main/java/com/example/cefriumtest"
echo "sdk.dir=$ANDROID_HOME" > "$WORK/local.properties"
# android.useAndroidX: AS sets it in every new project; the SDK pulls androidx
# transitively. jvmargs: dexing the ~237 MB SDK AAR OOMs Gradle's default 512 MB heap.
printf 'android.useAndroidX=true\norg.gradle.jvmargs=-Xmx4g\n' > "$WORK/gradle.properties"

# settings.gradle.kts — resolve ONLY from the published registry (+ google/central
# for the transitive androidx deps), exactly like the quickstart.
cat > "$WORK/settings.gradle.kts" <<EOF
pluginManagement {
    repositories {
        maven("$REPO")
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("$REPO")
    }
}
rootProject.name = "cefrium-consumer-test"
include(":app")
EOF

cat > "$WORK/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
EOF

# app/build.gradle.kts — the quickstart's plugin + dependency block, against the
# published $VERSION. (The android{} block and kotlin plugin are what an Android
# Studio project already provides.)
cat > "$WORK/app/build.gradle.kts" <<EOF
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.cefrium") version "$VERSION"
}
android {
    namespace = "com.example.cefriumtest"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.cefriumtest"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    // A real Android Studio project sets these; the throwaway must too, or
    // Java (1.8) and Kotlin (JDK default) JVM targets mismatch and the Kotlin
    // task fails before it even touches the SDK.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// The SDK bundles Chromium's Guava ListenableFuture; androidx pulls the
// com.google.guava:listenablefuture placeholder -> duplicate class. Standard fix.
configurations.all {
    exclude(group = "com.google.guava", module = "listenablefuture")
}
dependencies {
    implementation("com.cefrium:$ARTIFACT:$VERSION")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.browser:browser:1.8.0")
}
EOF

cat > "$WORK/app/src/main/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:name=".CefriumApp" android:label="CefriumTest">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

# Minimal app that touches the public API surface (Application init + a browser),
# so the test also fails if the published classes don't match the quickstart.
cat > "$WORK/app/src/main/java/com/example/cefriumtest/CefriumApp.kt" <<'EOF'
package com.example.cefriumtest
import android.app.Application
import android.content.Context
import com.cefrium.Cefrium
import org.chromium.base.ApplicationStatus
import org.chromium.base.ContextUtils
import org.chromium.base.PathUtils
import org.chromium.base.library_loader.LibraryLoader
import org.chromium.base.library_loader.LibraryProcessType
import org.chromium.ui.base.ResourceBundle
class CefriumApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val isBrowser = !ContextUtils.getProcessName().contains(":")
        ContextUtils.initApplicationContext(this)
        ResourceBundle.setAvailablePakLocales(arrayOf("en-US"))
        LibraryLoader.getInstance().setLibraryProcessType(
            if (isBrowser) LibraryProcessType.PROCESS_BROWSER
            else LibraryProcessType.PROCESS_CHILD)
        if (isBrowser) {
            PathUtils.setPrivateDataDirectorySuffix("cefrium")
            ApplicationStatus.initialize(this)
        }
    }
    override fun onCreate() {
        super.onCreate()
        if (!ContextUtils.getProcessName().contains(":")) Cefrium.initialize(this)
    }
}
EOF

cat > "$WORK/app/src/main/java/com/example/cefriumtest/MainActivity.kt" <<'EOF'
package com.example.cefriumtest
import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import com.cefrium.CefriumBrowser
class MainActivity : Activity() {
    private var browser: CefriumBrowser? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        setContentView(root)
        val b = CefriumBrowser.createWithSurface(this)
        browser = b
        root.addView(b.surfaceContainer)
        b.loadUrl("https://cefrium.com/modern-web/")
    }
    override fun onDestroy() {
        super.onDestroy()
        browser?.close()
        browser = null
    }
}
EOF

echo "==> Building the consumer app against the published registry artifacts..."
if "$GRADLE" -p "$WORK" :app:assembleDebug --no-daemon --console=plain; then
  echo "==> PASS: $ARTIFACT $VERSION resolves and assembles from the Codeberg Maven registry."
else
  echo "==> FAIL: $ARTIFACT $VERSION is NOT consumable from the registry (corrupt/missing artifacts, or API mismatch)."
  echo "    Do not announce this release until it builds here."
  exit 1
fi
