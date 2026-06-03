# Cefrium Instrumentation Test Harness

The authoritative on-device instrumented test host for the Cefrium SDK. It lives
in the SDK repo (not the samples) so a clone of `cef-android` is self-verifying
and CI can gate releases on it. Covers both layers:

- **Java SDK** (`com.cefrium`) — `CefriumSdkTest` (init, browser creation,
  navigation, unified Callback), `CefriumHardeningTest` (edge cases: double
  close, use-after-close, dark mode, etc.), `CefriumBridgeTest` (the FEATURE-023
  `window.cefriumQuery` round trip + unhandled-query failure).
- **Compose wrapper** (`com.cefrium.compose`) — `CefriumComposeViewTest`:
  `CefriumViewState` tab keep-alive, and `CefriumNavigator` evaluateJavaScript
  driving the `onQuery` bridge end-to-end through the public Compose API.

This is a self-contained Gradle module (like `cefrium_compose`), not part of the
GN/ninja build. It consumes the built AARs from `app/libs/` (gitignored).

## Run

```bash
export JAVA_HOME=/path/to/android-studio/jbr        # JDK 21
export ANDROID_HOME=/path/to/android-sdk

# 1. Stage the freshly built artifacts under test (libs/ is gitignored):
cp ../../out/<dir>/cefrium-sdk.aar app/libs/cefrium-sdk.aar
cp ../cefrium_compose/build/outputs/aar/cefrium-compose-release.aar \
   app/libs/cefrium-compose.aar

# 2. Run the whole suite on a connected device:
./gradlew connectedDebugAndroidTest --no-daemon

# Single class:
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cefrium.itest.sdk.CefriumBridgeTest
```

## Notes

- `app/src/main` provides only the test scaffolding: `TestHostApplication`
  (CEF process init) and `HostActivity` (a bare Activity for SDK tests to attach
  a browser surface to). No UI, no demo.
- `app/src/main/res/values/cefrium_compat.xml` (+ `cefrium_integers.xml`,
  `baseline_colors.xml`) define the stub resources the SDK AAR references — the
  same compat shim every Cefrium consumer app uses.
