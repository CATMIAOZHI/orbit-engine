#!/bin/bash
# smoke-samples.sh -- build + on-device launch-smoke EVERY cefrium-sample against
# the freshly built SDK AAR, and FAIL if any sample crashes. A release gate: a
# sample that crashes on a new engine (e.g. the 0.4.0 149 flagship crash) must be
# caught BEFORE the immutable Maven/Codeberg publish, not after.
#
# For each sample: stage the fresh AARs into its libs/ -> assembleDebug -> install
# -> pre-grant runtime perms -> launch -> assert no FATAL/NoSuchMethodError/
# tombstone/SIGSEGV in logcat within ~12s.
#
# Usage:  cef/cefrium_sdk/smoke-samples.sh
# Env:    JAVA_HOME (JDK 21), ANDROID_HOME. Needs a connected device.
# Exit:   0 = all samples build + launch clean; 1 = a sample crashed/failed to
#         build, or no device.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"          # chromium/src
ROOT="$(cd "$SRC/../.." && pwd)"                # cef-dev
SDK_AAR="$SRC/out/release/cefrium-sdk.aar"      # multi-ABI (post-merge); fallback below
[ -f "$SDK_AAR" ] || SDK_AAR="$SRC/out/Official_android_arm64/cefrium-sdk.aar"
COMPOSE_AAR="$SRC/cef/cefrium_compose/build/outputs/aar/cefrium-compose-release.aar"
: "${ANDROID_HOME:?set ANDROID_HOME}"; : "${JAVA_HOME:?set JAVA_HOME}"
ADB="$ANDROID_HOME/platform-tools/adb"
AAPT="$(ls "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | head -1)"
[ -f "$SDK_AAR" ] || { echo "FAIL: no SDK AAR at $SDK_AAR -- build it first."; exit 1; }
[ "$("$ADB" devices 2>/dev/null | grep -c 'device$')" -ge 1 ] || { echo "FAIL: no device connected."; exit 1; }

SAMPLES="cefrium-browser compose-browser java-sample modern-web-showcase push-demo kiosk-pos kiosk-automotive"
# The two browsers live in their own repo (cefrium/cefrium-browser); the rest are
# in cefrium-sample.
is_browser() { case "$1" in cefrium-browser|compose-browser) return 0 ;; *) return 1 ;; esac; }
fails=0
echo "=== sample launch-smoke (engine: $(git -C "$SRC" describe --tags 2>/dev/null)) ==="
for s in $SAMPLES; do
  if is_browser "$s"; then d="$ROOT/cefrium-browser/$s"; else d="$ROOT/cefrium-sample/$s"; fi
  [ -d "$d" ] || { echo "  $s: SKIP (missing)"; continue; }
  GA=""
  if is_browser "$s"; then
    # Browser repo defaults to the registry, but this gate runs BEFORE publish --
    # the new version is not in the registry yet, so build against the fresh
    # local AARs (-PcefriumSource=local).
    mkdir -p "$d/app/libs"
    cp "$SDK_AAR" "$d/app/libs/cefrium-sdk.aar"
    [ -f "$COMPOSE_AAR" ] && cp "$COMPOSE_AAR" "$d/app/libs/cefrium-compose.aar"
    GA="-PcefriumSource=local"
  else
    for libs in $(find "$d" -type d -name libs 2>/dev/null); do
      [ -f "$libs/cefrium-sdk.aar" ] && cp "$SDK_AAR" "$libs/cefrium-sdk.aar"
      [ -f "$libs/cefrium-compose.aar" ] && [ -f "$COMPOSE_AAR" ] && cp "$COMPOSE_AAR" "$libs/cefrium-compose.aar"
    done
  fi
  if ! ( cd "$d" && ./gradlew :app:assembleDebug $GA --no-daemon -q ) >"/tmp/smoke-$s.log" 2>&1; then
    echo "  $s: BUILD FAIL (/tmp/smoke-$s.log)"; fails=$((fails+1)); continue
  fi
  apk="$(find "$d/app/build/outputs/apk/debug" -name '*.apk' 2>/dev/null | head -1)"
  [ -n "$apk" ] || { echo "  $s: no APK"; fails=$((fails+1)); continue; }
  pkg="$("$AAPT" dump badging "$apk" 2>/dev/null | grep -oP "package: name='\K[^']+")"
  act="$("$AAPT" dump badging "$apk" 2>/dev/null | grep -oP "launchable-activity: name='\K[^']+")"
  "$ADB" install -r "$apk" >/dev/null 2>&1
  for p in POST_NOTIFICATIONS CAMERA RECORD_AUDIO ACCESS_FINE_LOCATION; do "$ADB" shell pm grant "$pkg" android.permission.$p >/dev/null 2>&1; done
  "$ADB" shell am force-stop "$pkg" >/dev/null 2>&1; "$ADB" logcat -c >/dev/null 2>&1
  "$ADB" shell am start -n "$pkg/$act" >/dev/null 2>&1
  sleep 12
  crash="$("$ADB" logcat -d 2>/dev/null | grep -icE 'FATAL EXCEPTION|NoSuchMethodError|tombstone|SIGSEGV')"
  "$ADB" shell am force-stop "$pkg" >/dev/null 2>&1
  if [ "$crash" -gt 0 ]; then
    echo "  $s ($pkg): CRASH -- $("$ADB" logcat -d 2>/dev/null | grep -E 'FATAL|NoSuchMethod' | head -1 | tr -s ' ' | cut -c1-100)"
    fails=$((fails+1))
  else
    echo "  $s ($pkg): OK"
  fi
done
echo "=== $((fails)) failure(s) ==="
[ "$fails" -eq 0 ] || { echo "Sample smoke FAILED -- do not publish."; exit 1; }
echo "All samples build + launch clean on device."
