# Cefrium Compose

Jetpack Compose wrapper for the Cefrium SDK. Provides `CefriumWebView` —
a `@Composable` that embeds a full Chromium browser with video, audio,
and ad blocking.

## Usage

```kotlin
CefriumWebView(
    url = "https://example.com",
    modifier = Modifier.fillMaxSize(),
    onUrlChanged = { url -> urlState = url },
    onTitleChanged = { title -> titleState = title },
    onLoadingStateChanged = { loading, _, _ -> isLoading = loading },
    onFullscreenChanged = { fs -> /* handle fullscreen */ },
    onBrowserReady = { browser -> /* direct access */ }
)
```

## Features

All Cefrium SDK features available through Compose:
- Video + audio playback
- Ad blocking (EasyList)
- URL/title change callbacks
- Loading state
- Fullscreen video
- Network request inspection
- Dark mode (via `browser.setDarkMode()`)

## Integration

Copy `CefriumWebView.kt` to your project, or add as a module:

```groovy
dependencies {
    implementation files('libs/cefrium-sdk.aar')
    implementation 'androidx.compose.ui:ui:...'
}
```

## About

Developed with significant AI/LLM assistance.
Published as open source for the community to evaluate, use, and extend.
