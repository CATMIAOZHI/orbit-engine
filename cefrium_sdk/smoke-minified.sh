#!/bin/bash
# smoke-minified.sh -- build the browser app with R8 on, the way a consumer turns
# it on, against the freshly built SDK AAR; FAIL unless every R8 category clears
# Google Play's 25% bar and the minified app runs on a device.
#
# Why: until 0.11.1 the SDK shipped a blanket -keep of org.chromium.** (R8 near
# 10% in every category, below Play's bar) and nothing ever built a minified app.
# A missing keep does not fail the build: it fails at run time, from JNI or from
# a lambda the device calls back (AbstractMethodError at startup was measured).
# So this lane builds, measures, installs and runs.
#
# Steps: stage the AARs into cefrium-browser -> bundleRelease + assembleRelease
# with -PcefriumSource=local -PcefriumR8Smoke -> read R8's own stats from the
# bundle (BUNDLE-METADATA/com.android.tools/r8.json) -> install the .r8test build
# (never the Play app) -> launch and load local pages through adb reverse
# (hermetic, no external site) -> assert the process lives and logcat's crash
# buffer is empty -> uninstall.
#
# Usage:  cef/cefrium_sdk/smoke-minified.sh
# Env:    JAVA_HOME (JDK 25), ANDROID_HOME. One connected device, or ANDROID_SERIAL.
# Exit:   0 = R8 >= 25% in all three categories and the app ran clean; 1 otherwise.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"          # chromium/src
ROOT="$(cd "$SRC/../.." && pwd)"                # cef-dev
SDK_AAR="${CEFRIUM_SDK_AAR:-$SRC/out/release/cefrium-sdk.aar}"
COMPOSE_AAR="${CEFRIUM_COMPOSE_AAR:-$SRC/cef/cefrium_compose/build/outputs/aar/cefrium-compose-release.aar}"
APP="$ROOT/cefrium-browser/cefrium-browser"
PKG="com.cefrium.browser.r8test"
MIN_PCT=25
: "${ANDROID_HOME:?set ANDROID_HOME}"; : "${JAVA_HOME:?set JAVA_HOME}"
ADB="$ANDROID_HOME/platform-tools/adb"

fail() { echo "FAIL: $*"; exit 1; }
[ -f "$SDK_AAR" ] || fail "no SDK AAR at $SDK_AAR"
[ -f "$COMPOSE_AAR" ] || fail "no Compose AAR at $COMPOSE_AAR"
[ "$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l)" -ge 1 ] || fail "no device"

mkdir -p "$APP/app/libs"
cp "$SDK_AAR" "$APP/app/libs/cefrium-sdk.aar" || fail "staging the SDK AAR"
cp "$COMPOSE_AAR" "$APP/app/libs/cefrium-compose.aar" || fail "staging the Compose AAR"

echo ">>> minified build (R8 on, local AARs)"
( cd "$APP" && ./gradlew bundleRelease assembleRelease -PcefriumSource=local \
    -PcefriumR8Smoke=1 --no-daemon -q ) || fail "the minified build failed (R8 missing classes or rules)"

AAB="$(ls "$APP"/app/build/outputs/bundle/release/*.aab | head -1)"
APK="$(ls "$APP"/app/build/outputs/apk/release/*.apk | head -1)"
unzip -p "$AAB" BUNDLE-METADATA/com.android.tools/r8.json > /dev/null 2>&1 \
  || fail "no R8 metadata in $AAB"
unzip -p "$AAB" BUNDLE-METADATA/com.android.tools/r8.json | python3 -I -c "
import json, sys
s = json.load(sys.stdin)['stats']
cats = {'optimization': 100 - s['noOptimizationPercentage'],
        'obfuscation': 100 - s['noObfuscationPercentage'],
        'shrinking': 100 - s['noShrinkingPercentage']}
print('    R8: ' + '  '.join('%s %.1f%%' % kv for kv in cats.items()))
low = [k for k, v in cats.items() if v < $MIN_PCT]
if low:
    sys.exit('R8 below $MIN_PCT%% in: ' + ', '.join(low))
" || fail "R8 percentages below Google Play's bar"

echo ">>> run the minified app on the device"
"$ADB" install -r "$APK" > /dev/null || fail "install of $PKG"
PAGES="$(mktemp -d "${CEFRIUM_SCRATCH:-${TMPDIR:-/tmp}}/minified-pages.XXXXXX")" || fail "scratch dir"
cleanup() {
  [ -n "${SERVER_PID:-}" ] && kill "$SERVER_PID" 2>/dev/null
  "$ADB" reverse --remove tcp:8765 > /dev/null 2>&1
  "$ADB" uninstall "$PKG" > /dev/null 2>&1
  rm -rf "$PAGES"
}
trap cleanup EXIT
for n in 1 2 3; do
  cat > "$PAGES/p$n.html" <<EOF
<!doctype html><html><head><title>p$n</title></head><body>
<h1>minified page $n</h1><input id=t value=type><canvas id=c width=300 height=150></canvas>
<script>
var c=document.getElementById('c').getContext('2d');c.fillStyle='#08c';c.fillRect(0,0,300,150);
fetch('p$n.html').then(function(r){return r.text();}).then(function(){document.title='ok$n';});
localStorage.setItem('k','v');new Promise(function(r){setTimeout(r,50);});
</script></body></html>
EOF
done
python3 -I -m http.server 8765 --bind 127.0.0.1 --directory "$PAGES" > /dev/null 2>&1 &
SERVER_PID=$!
"$ADB" reverse tcp:8765 tcp:8765 > /dev/null || fail "adb reverse"
"$ADB" logcat -b crash -c
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 20
for n in 1 2 3; do
  "$ADB" shell "am start -a android.intent.action.VIEW -d 'http://127.0.0.1:8765/p$n.html' -p $PKG" > /dev/null 2>&1
  sleep 8
done
[ -n "$("$ADB" shell pidof "$PKG" | tr -d '\r')" ] || fail "the minified app is not running after the page loads"
CRASHES="$("$ADB" logcat -b crash -d | grep -cE 'FATAL|Fatal signal|AbstractMethodError|NoSuchMethodError|ClassNotFoundException')"
[ "$CRASHES" = 0 ] || { "$ADB" logcat -b crash -d | grep -E 'FATAL|Caused by' | head -6; fail "$CRASHES crash line(s) in the minified app"; }
echo "PASS: minified build clears ${MIN_PCT}% in every R8 category and runs clean on the device."
