# Cefrium Compose

Jetpack Compose wrapper for the Cefrium SDK — a `@Composable` that embeds a
full Chromium browser (video, audio, ad blocking, off-screen rendering) on
Android. This is a production SDK component; it lives in the SDK repo and builds
its own `cefrium-compose.aar`. The samples repo only consumes that AAR.

## Usage

Idiomatic state-hoisting API (observe via `CefriumViewState`, navigate via
`CefriumNavigator`):

```kotlin
val state = rememberCefriumViewState("https://example.com")
val navigator = rememberCefriumNavigator()

CefriumView(state = state, modifier = Modifier.fillMaxSize(), navigator = navigator)

if (state.isLoading) LinearProgressIndicator()
Text(state.pageTitle ?: "")
// navigator.navigateBack(), navigator.reload(), navigator.loadUrl(...)
```

Lower-level callback-based API (`CefriumWebView`) is also available for direct
access to every browser event and the `CefriumBrowser` instance:

```kotlin
CefriumWebView(
    url = "https://example.com",
    modifier = Modifier.fillMaxSize(),
    onUrlChanged = { url -> urlState = url },
    onTitleChanged = { title -> titleState = title },
    onBrowserReady = { browser -> /* direct access */ }
)
```

## Features

All Cefrium SDK features available through Compose:
- Video + audio playback
- Ad blocking (EasyList)
- URL/title change callbacks, loading state
- Fullscreen video
- Network request inspection
- Dark mode (via `browser.setDarkMode()`)

## Build

Self-contained Gradle module. The canonical source is in `java/src`.

```bash
export JAVA_HOME=/path/to/android-studio/jbr   # JDK 21
export ANDROID_HOME=/path/to/android-sdk

# Provide the SDK AAR for compileOnly (built by ninja, copied here; gitignored):
cp /path/to/out/.../cefrium-sdk.aar libs/

# Bootstrap the wrapper once (the jar is gitignored, not committed):
gradle wrapper --gradle-version 8.9

./gradlew assembleRelease --no-daemon
# Output: build/outputs/aar/cefrium-compose-release.aar
```

## Integration

Consume the published AAR from Codeberg Packages:

```groovy
dependencies {
    implementation 'com.cefrium:cefrium-sdk:0.1.0'
    implementation 'com.cefrium:cefrium-compose:0.1.0'
    implementation platform('androidx.compose:compose-bom:2024.06.00')
    implementation 'androidx.compose.ui:ui'
}
```

## About

Cefrium's architecture, design constraints, and product direction are
Juan Manuel Méndez Rey's; implementation is AI-assisted and validated
against device behavior. Published as open source for the community to
use and extend.
