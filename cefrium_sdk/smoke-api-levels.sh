#!/bin/bash
# smoke-api-levels.sh -- launch java-sample on an x86_64 emulator for every
# Android version the SDK supports (minSdk 29 to the newest), and FAIL if the
# app dies or never shows its Activity on any of them.
#
# 0.17.0 crashed at launch on Android 15 only (issue #17): the release devices
# ran Android 16, so every on-device gate passed. A real-device suite proves
# behaviour; this proves each OS version at least starts the app.
#
# Usage:  cef/cefrium_sdk/smoke-api-levels.sh [<sdk.aar>] [--levels "29 30 ..."]
#   <sdk.aar> defaults to out/release/cefrium-sdk.aar (must hold x86_64).
# Each level gets its own AVD (cefrium-smoke-api<N>), booted with -wipe-data
# so an exit record can only come from this run, and stopped through its own
# console (adb -s emulator-<port> emu kill) -- never by name or pattern.
# Exit:   0 = every level launched clean; 1 = a level failed or could not boot.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"                 # chromium/src
ROOT="$(cd "$SRC/../.." && pwd)"                       # cef-dev
export JAVA_HOME="${JAVA_HOME:-$SRC/third_party/jdk/current}"
export ANDROID_HOME="${ANDROID_HOME:-$SRC/third_party/android_sdk/public}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"
AVDM="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

AAR="$SRC/out/release/cefrium-sdk.aar"
LEVELS="29 30 31 33 34 35 36 37"
while [ $# -gt 0 ]; do
  case "$1" in
    --levels) LEVELS="$2"; shift 2 ;;
    *) AAR="$1"; shift ;;
  esac
done
[ -f "$AAR" ] || { echo "FAIL: no AAR at $AAR"; exit 1; }
# Not `unzip -l | grep -q`: under pipefail grep's early exit fails the pipe.
unzip -l "$AAR" 'jni/x86_64/libcef.so' > /dev/null 2>&1 \
  || { echo "FAIL: $AAR has no x86_64 libcef.so (the emulators are x86_64)"; exit 1; }

PKG=com.cefrium.sample
WORK="$ROOT/tmp/smoke-api-levels"
rm -rf "$WORK"; mkdir -p "$WORK"
# Build a copy of java-sample, so the repo's app/libs is left alone. The copy
# sits two levels under cef-dev, as the sample does, for its includeBuild path.
rsync -a --exclude build --exclude .gradle --exclude 'app/libs/*.aar' \
  "$ROOT/cefrium-sample/java-sample/" "$WORK/java-sample/"
cp "$AAR" "$WORK/java-sample/app/libs/cefrium-sdk.aar"
echo ">>> build java-sample against $(basename "$AAR")"
( cd "$WORK/java-sample" && ./gradlew -q assembleDebug --no-daemon ) > "$WORK/gradle.log" 2>&1 \
  || { echo "FAIL: java-sample build"; tail -20 "$WORK/gradle.log"; exit 1; }
APK="$WORK/java-sample/app/build/outputs/apk/debug/app-debug.apk"

image_for() {  # an installed x86_64 image for an API level, google_apis first
  local d t
  for d in "$ANDROID_HOME/system-images/android-$1.0" "$ANDROID_HOME/system-images/android-$1"; do
    for t in google_apis google_apis_playstore default; do
      [ -d "$d/$t/x86_64" ] && { echo "system-images;$(basename "$d");$t;x86_64"; return; }
    done
  done
}

port=5600
fails=0
summary=""
for L in $LEVELS; do
  port=$((port + 2)); SER="emulator-$port"; AVD="cefrium-smoke-api$L"
  img="$(image_for "$L")"
  if [ -z "$img" ]; then
    echo "FAIL api$L: no x86_64 image (sdkmanager \"system-images;android-$L;google_apis;x86_64\")"
    fails=$((fails + 1)); summary+=" api$L:NOIMAGE"; continue
  fi
  if "$ADB" devices | grep -q "^$SER"; then
    echo "FAIL api$L: $SER is already in use by someone else; not touching it"
    fails=$((fails + 1)); summary+=" api$L:PORT"; continue
  fi
  [ -d "$HOME/.android/avd/$AVD.avd" ] || echo no | "$AVDM" create avd -n "$AVD" -k "$img" -d pixel_6 >/dev/null 2>&1
  CFG="$HOME/.android/avd/$AVD.avd/config.ini"
  [ -f "$CFG" ] || { echo "FAIL api$L: could not create $AVD"; fails=$((fails + 1)); summary+=" api$L:AVD"; continue; }
  python3 - "$CFG" <<'EOF'
import sys
p = sys.argv[1]; lines = open(p).read().splitlines()
want = {'hw.ramSize': '4096', 'disk.dataPartition.size': '8G', 'hw.cpu.ncore': '4'}
lines = [l for l in lines if l.split('=')[0] not in want] + [f'{k}={v}' for k, v in want.items()]
open(p, 'w').write('\n'.join(lines) + '\n')
EOF
  echo ">>> api$L: boot $AVD on $SER"
  "$EMU" -avd "$AVD" -port "$port" -no-window -no-audio -no-snapshot -wipe-data \
      -no-boot-anim -gpu swiftshader_indirect > "$WORK/emu-api$L.log" 2>&1 &
  EPID=$!
  booted=0
  for _ in $(seq 1 120); do
    [ "$("$ADB" -s "$SER" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { booted=1; break; }
    kill -0 "$EPID" 2>/dev/null || break
    sleep 3
  done
  verdict=""
  if [ "$booted" != 1 ]; then
    verdict="NOBOOT"
  elif ! "$ADB" -s "$SER" install -r "$APK" > "$WORK/install-api$L.log" 2>&1; then
    verdict="NOINSTALL"
  else
    "$ADB" -s "$SER" logcat -c
    "$ADB" -s "$SER" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    # A renderer on a software-GPU emulator takes a while; a crash at startup
    # (the case this exists for) happens within the first second.
    sleep 30
    "$ADB" -s "$SER" logcat -d > "$WORK/logcat-api$L.txt" 2>&1
    "$ADB" -s "$SER" shell dumpsys activity exit-info "$PKG" > "$WORK/exitinfo-api$L.txt" 2>&1
    top="$("$ADB" -s "$SER" shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity')"
    if grep -qE 'FATAL EXCEPTION|Fatal signal' "$WORK/logcat-api$L.txt"; then
      verdict="CRASH"
    elif grep -qE 'reason=(4|5|6) ' "$WORK/exitinfo-api$L.txt"; then
      verdict="CRASH(exit-info)"
    elif [[ "$top" != *"$PKG/"* ]]; then
      verdict="NOTSHOWN"
    else
      verdict="OK"
    fi
  fi
  "$ADB" -s "$SER" emu kill >/dev/null 2>&1
  for _ in $(seq 1 30); do kill -0 "$EPID" 2>/dev/null || break; sleep 1; done
  # Still up: it is the emulator this loop started, so its own PID is safe.
  kill -0 "$EPID" 2>/dev/null && kill "$EPID"
  # -wipe-data recreates these on every boot; left behind they add up to
  # ~10 GB across the levels and the next level cannot create its userdata.
  rm -f "$HOME/.android/avd/$AVD.avd/"{userdata-qemu.img.qcow2,cache.img.qcow2,encryptionkey.img.qcow2}
  if [ "$verdict" = NOBOOT ] && grep -q 'Not enough space' "$WORK/emu-api$L.log"; then
    verdict="NOBOOT(disk full: $(grep -o 'Available: [0-9.]* MB' "$WORK/emu-api$L.log"))"
  fi
  if [ "$verdict" = OK ]; then
    echo "OK   api$L"
  else
    echo "FAIL api$L: $verdict (logs in $WORK)"
    grep -m1 -A4 -E 'FATAL EXCEPTION|Fatal signal' "$WORK/logcat-api$L.txt" 2>/dev/null | sed 's/^/    /'
    fails=$((fails + 1))
  fi
  summary+=" api$L:$verdict"
done
echo "summary:$summary"
[ "$fails" -eq 0 ] || { echo "FAIL: $fails API level(s) did not launch clean"; exit 1; }
echo "All API levels launched clean."
