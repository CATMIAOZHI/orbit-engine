# Cefrium Instrumentation Test Harness

The authoritative on-device instrumented test host for the Cefrium SDK. It lives
in the SDK repo (not the samples) so a clone of `cef-android` is self-verifying
and CI can gate releases on it. It covers both the **Java SDK** (`com.cefrium`)
and the **Compose wrapper** (`com.cefrium.compose`).

**26 test classes, 58 `@Test` methods**, and a release cannot be cut without
them: `cut-release.sh` runs this suite as its `suite` phase, before anything
becomes immutable.

| Area | Classes |
|------|---------|
| Core SDK | `CefriumSdkTest` (init, browser creation, navigation, unified Callback), `CefriumHardeningTest` (double-close, use-after-close, dark mode, edge cases), `CefriumBridgeTest` (`window.cefriumQuery` round trip + unhandled-query failure) |
| Navigation / settings | `CefriumNavigationTest` (loadHtml/postUrl/stop/back-forward), `CefriumSettingsTest` (runtime UA / JS / zoom), `CefriumCookieTest`, `CefriumInterceptorTest` (request block/redirect) |
| Web platform | `CefriumPermissionTest` (geolocation/camera/mic via `CefPermissionHandler`), `CefriumPdfTest` (print-to-PDF), `CefriumMediaTest` + `CefriumMediaSessionTest` (playback + lock-screen controls), `CefriumPopupTest`, `CefriumJsDialogTest`, `CefriumNestedScrollTest` |
| Security | `CefriumSslErrorTest`, `CefriumHttpAuthTest` |

When a feature ships, its test joins this suite (e.g. F6 Web Notifications /
`setContentSetting` and the Compose parity API - find / dark mode / media session
/ fullscreen - are covered alongside `CefriumPermissionTest` and the Compose
tests). The Compose layer is exercised through the public
`CefriumView` / `CefriumViewState` / `CefriumNavigator` API end-to-end.

This is a self-contained Gradle module (like `cefrium_compose`), not part of the
GN/ninja build. It consumes the built AARs from `app/libs/` (gitignored).

## Run

One command, from `chromium/src`. It stages the artifacts, runs the suite, and
reads the result files rather than trusting Gradle's exit status:

```bash
cef/cefrium_sdk/run-instrumentation-suite.sh            # what a cut runs
cef/cefrium_sdk/run-instrumentation-suite.sh 0.10.2     # a published version
```

It fails on a failure, on a skip, and on a case that took 0.000s, because a
suite that cannot tell "nothing happened" from "nothing ran" signs empty
passes. That is not hypothetical here: a result file once read tests=46
failures=0 with every duration zero.

By hand, if you are iterating on one class:

```bash
export JAVA_HOME=../../third_party/jdk/current       # JDK 25 is a hard floor
export ANDROID_HOME=../../third_party/android_sdk/public

# 1. Stage the freshly built artifacts under test (libs/ is gitignored):
cp ../../out/<dir>/cefrium-sdk.aar app/libs/cefrium-sdk.aar
cp ../cefrium_compose/build/outputs/aar/cefrium-compose-release.aar \
   app/libs/cefrium-compose.aar

# 2. Run the whole suite on a connected device:
./gradlew connectedDebugAndroidTest --no-daemon

# Single class:
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cefrium.itest.sdk.CefriumBridgeTest

# A whole package, which is the only way to run a SUBSET larger than one class:
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.cefrium.itest.sdk
```

**A comma-separated `class` list silently runs only the first one.** Through
this Gradle and AGP path, `...class=A,B` runs A, reports BUILD SUCCESSFUL and
never mentions B. That reads as "A and B both pass" and it is the empty-pass
trap in a different costume: the result file is the only place the truth shows,
as a test count lower than the number of classes asked for. Use `package=` for
a subset, or one class per run.

## Notes

- `app/src/main` provides only the test scaffolding: `TestHostApplication`
  (CEF process init) and `HostActivity` (a bare Activity for SDK tests to attach
  a browser surface to). No UI, no demo.
- `app/src/main/res/values/cefrium_compat.xml` (+ `cefrium_integers.xml`,
  `baseline_colors.xml`) define the stub resources the SDK AAR references - the
  same compat shim every Cefrium consumer app uses.
