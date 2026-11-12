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
| Run JavaScript | `webView.evaluateJavascript(js, cb)` | `browser.evaluateJavaScript(js)` |
| Script before the page's own | `WebViewCompat.addDocumentStartJavaScript(webView, js, rules)` | `browser.addDocumentStartJavaScript(js, rules)` |
| Autoplay with sound | `settings.setMediaPlaybackRequiresUserGesture(false)` | `browser.setMediaPlaybackRequiresUserGesture(false)` |

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

## App content: `WebViewAssetLoader`

Content shipped in the APK (a web app, a game, offline pages) needs an https
origin: storage, service workers and secure-context APIs depend on it.
`file://` gives none of that, and a localhost server can be reached by any app
on the device. WebView answers with `WebViewAssetLoader`; Cefrium serves the
origin from Java, with no socket, for every browser of a profile.

### WebView
```java
WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/res/", new WebViewAssetLoader.ResourcesPathHandler(context))
        .build();
webView.setWebViewClient(new WebViewClientCompat() {
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view,
            WebResourceRequest request) {
        return loader.shouldInterceptRequest(request.getUrl());
    }
});
webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
```

### Cefrium
```java
CefriumProfile.getDefault().registerContentServer(
        CefriumContentServers.WEBVIEW_ASSET_LOADER_ORIGIN,
        new CefriumPathContentServer.Builder()
                .addPath("/assets/", CefriumContentServers.assets(context, ""))
                .addPath("/res/", CefriumContentServers.resources(context))
                .build());
browser.loadUrl("https://appassets.androidplatform.net/assets/index.html");
```

The URLs stay the same. What changes:

| | WebViewAssetLoader | Cefrium |
|---|---|---|
| Scope | One WebView (its client) | A profile: every browser, worker and service worker |
| Service worker script and its fetches | Need a `ServiceWorkerClient` too | Served, nothing more to do |
| Range requests (video seek) | No | Yes, for content with a known length |
| HEAD, POST body, request headers | No body | Yes (`CefriumContentRequest`) |
| Response headers | Set by hand per response | Per response, or for all (`withResponseHeaders`) |
| Own content (database, decryption) | `PathHandler` | Any `CefriumContentServer`; runs on a background thread, may block |
| Files directory | `InternalStoragePathHandler` | `CefriumContentServers.files(dir)` |

Any host works: `https://app.local` is as good. WebView's stored data
(localStorage, IndexedDB) does not move to Cefrium; only the URLs stay.

Games and WebAssembly threads need cross-origin isolation:

```java
server.withResponseHeaders(Map.of(
        "Cross-Origin-Opener-Policy", "same-origin",
        "Cross-Origin-Embedder-Policy", "require-corp"));
```

Range requests need the length, which Android gives only for assets stored
uncompressed: add the extensions to `androidResources { noCompress += [...] }`
(media types are uncompressed by default).

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
    // Saved in the app's own Downloads directory unless the app picks a path
    if (complete) Log.i("Download", "Saved: " + path);
});
```

Downloads belong to the profile, as in Chrome: they continue after the browser
closes and are kept across runs. `CefriumProfile.getDownloads` lists them and
resumes one the app was closed in the middle of.

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
| App content on an https origin, no socket | `WebViewAssetLoader`, per WebView | `CefriumProfile.registerContentServer`, per profile, service workers and Range included |
| Ad blocking with filter lists | Via `shouldInterceptRequest`, by URL | Yes: load an EasyList-format list (`Cefrium.loadAdBlockList`); none is built in. Sites exempted per profile (`CefriumProfile.setAdBlockExceptions`) |
| Find in page API | Yes (findAllAsync) | Yes (CefBrowserHost::Find) |
| Context menu API | Limited | Full (CEF API) |
| File chooser (`<input type=file>`) | Only if the app implements `onShowFileChooser` | Built in (the engine's, as Chrome's); `CefriumDialogHandler` for one of the app's own |
| Dark mode API | API 33+ | All versions |
| Web Bluetooth / WebUSB / Web Serial | No | Yes (CefDeviceChooserHandler, 0.5.0+) |
| WebAuthn / passkeys (web origin) | No | Yes (Credential Manager, 0.5.0+, Android 14+) |
| Web Share | No (manual intent) | Yes (navigator.share, 0.5.0+) |
| Clipboard read/write | Partial | Yes (navigator.clipboard) |
| Proxy configuration | App-wide (`ProxyController`) | Per profile (`CefriumProfile.setProxy`), WebRTC policy too; failures reported (`setProxyErrorListener`) |
| Secure DNS (DNS over HTTPS) | No | Yes (`Cefrium.setSecureDns`) |
| Downloads kept across runs, resumable | No (the app downloads) | Yes (`CefriumProfile.getDownloads`) |

## What WebView has that Cefrium doesn't (yet)

- Smaller size (0 MB vs ~150 MB per ABI)

(Android Accessibility/TalkBack, the Autofill framework, Widevine DRM, and
WebAuthn/passkeys are all supported - see the README "What works" table.)
