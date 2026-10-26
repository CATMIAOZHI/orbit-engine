# Migrating from Android WebView to Cefrium

Scope: this document is for CEFRIUM (CEF ported to native Android) and its SDK, not upstream CEF.

Side-by-side comparison for developers migrating from WebView.

## Setup

### WebView
```java
WebView webView = new WebView(context);
webView.getSettings().setJavaScriptEnabled(true);
layout.addView(webView);
webView.loadUrl("https://example.com");
```

### Cefrium
```java
// In Application.onCreate():
Cefrium.initialize(this);

// In Activity:
CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
layout.addView(browser.getSurfaceContainer());
browser.loadUrl("https://example.com");
```

## Callbacks

### WebView
```java
webView.setWebViewClient(new WebViewClient() {
    @Override
    public void onPageFinished(WebView view, String url) { }
});
webView.setWebChromeClient(new WebChromeClient() {
    @Override
    public void onReceivedTitle(WebView view, String title) { }
});
```

### Cefrium
```java
browser.setCallback(new CefriumBrowser.Callback() {
    public void onUrlChanged(String url) { }
    public void onTitleChanged(String title) { }
    public void onLoadingStateChanged(boolean loading, boolean back, boolean fwd) { }
    public void onFullscreenChanged(boolean fullscreen) { }
    public void onRequestIntercepted(String url, String method, boolean blocked) { }
    public void onFindResult(int activeMatch, int totalMatches) { }
    public void onDownloadUpdated(String name, String path, int pct, boolean done, boolean canceled) { }
    public void onContextMenu(String menuItems, String url) { }
});
```

## Navigation

| Operation | WebView | Cefrium |
|-----------|---------|---------|
| Load URL | `webView.loadUrl(url)` | `browser.loadUrl(url)` |
| Go back | `webView.goBack()` | `browser.goBack()` |
| Go forward | `webView.goForward()` | `browser.goForward()` |
| Can go back | `webView.canGoBack()` | `browser.canGoBack()` |
| Reload | `webView.reload()` | `browser.reload()` |
| Find in page | `webView.findAllAsync(text)` | `browser.find(text, fwd, case, next)` |
| Stop finding | `webView.clearMatches()` | `browser.stopFinding(clear)` |
| Dark mode | WebSettings (API 33+) | `browser.setDarkMode(true)` |

## Request interception

### WebView (limited -- GET only, no POST body)
```java
webView.setWebViewClient(new WebViewClient() {
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view,
            WebResourceRequest request) {
        // Only sees GET requests fully
        return null;
    }
});
```

### Cefrium (full access -- all methods, all headers)
```java
browser.setOnRequestInterceptedListener((url, method, blocked) -> {
    // Every request: GET, POST, PUT, etc.
    // blocked = true if ad blocker matched
    Log.i("Network", method + " " + url + (blocked ? " BLOCKED" : ""));
});
```

## Downloads

### WebView
```java
webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
    // Must implement download yourself
});
```

### Cefrium (automatic, with progress)
```java
browser.setOnDownloadListener((name, path, percent, complete, canceled) -> {
    // Downloads handled automatically to /sdcard/Download/
    if (complete) Log.i("Download", "Saved: " + path);
});
```

## Lifecycle

### WebView
```java
@Override protected void onPause() {
    super.onPause();
    webView.onPause();
}
@Override protected void onResume() {
    super.onResume();
    webView.onResume();
}
@Override protected void onDestroy() {
    webView.destroy();
    super.onDestroy();
}
```

### Cefrium
```java
@Override protected void onPause() {
    super.onPause();
    browser.onPause();
}
@Override protected void onResume() {
    super.onResume();
    browser.onResume();
}
@Override protected void onDestroy() {
    browser.close();
    super.onDestroy();
}
```

## Jetpack Compose

### WebView (requires AndroidView wrapper)
```kotlin
AndroidView(factory = { WebView(it).apply { loadUrl("...") } })
```

### Cefrium
```kotlin
CefriumWebView(
    url = "https://example.com",
    modifier = Modifier.fillMaxSize(),
    onUrlChanged = { url -> },
    onTitleChanged = { title -> },
    onLoadingStateChanged = { loading, _, _ -> }
)
```

## What Cefrium adds over WebView

| Feature | WebView | Cefrium |
|---------|---------|---------|
| Off-screen rendering | No | Yes (OnPaint pixel buffer) |
| Pinned Chromium version | No (system) | Yes (156) |
| Multi-process control | No | Yes |
| Network request interception (full) | Partial (GET) | Yes (all) |
| Ad blocking with filter lists | Via `shouldInterceptRequest`, by URL | Yes: load an EasyList-format list (`Cefrium.loadAdBlockList`); none is built in |
| Find in page API | Yes (findAllAsync) | Yes (CefBrowserHost::Find) |
| Context menu API | Limited | Full (CEF API) |
| File dialog | Yes | Yes |
| Dark mode API | API 33+ | All versions |
| Web Bluetooth / WebUSB / Web Serial | No | Yes (CefDeviceChooserHandler, 0.5.0+) |
| WebAuthn / passkeys (web origin) | No | Yes (Credential Manager, 0.5.0+, Android 14+) |
| Web Share | No (manual intent) | Yes (navigator.share, 0.5.0+) |
| Clipboard read/write | Partial | Yes (navigator.clipboard) |
| Proxy configuration | No | Yes (setProxy / PAC) |

## What WebView has that Cefrium doesn't (yet)

- Smaller size (0 MB vs ~150 MB per ABI)

(Android Accessibility/TalkBack, the Autofill framework, Widevine DRM, and
WebAuthn/passkeys are all supported - see the README "What works" table.)
