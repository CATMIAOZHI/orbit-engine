#!/bin/bash
# suite-on-emulator.sh -- the full instrumentation suite on an x86_64 emulator
# of one Android version (default 29, the SDK's minSdk), <runs> times in a row.
#
# The release devices run Android 16; minSdk 29 is the oldest version Cefrium
# supports, and its suite once failed in three places none of them showed
# (notification permission grants, readelf, a PKCS12 fixture). This makes the
# oldest version a gate, not a one-off check.
#
# Usage:  cef/cefrium_sdk/suite-on-emulator.sh [<sdk.aar>] [--level 29] [--runs 1]
#   <sdk.aar> defaults to out/release/cefrium-sdk.aar (must hold x86_64).
# Boots cefrium-smoke-api<level> (created by smoke-api-levels.sh) with
# -wipe-data on its own port and stops it through its console (adb emu kill),
# never by name or pattern.
# Exit:   0 = every run passed; 1 = a run failed or the emulator did not boot.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"                 # chromium/src
ROOT="$(cd "$SRC/../.." && pwd)"                       # cef-dev
export JAVA_HOME="${JAVA_HOME:-$SRC/third_party/jdk/current}"
export ANDROID_HOME="${ANDROID_HOME:-$SRC/third_party/android_sdk/public}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"

AAR="$SRC/out/release/cefrium-sdk.aar"
LEVEL=29
RUNS=1
while [ $# -gt 0 ]; do
  case "$1" in
    --level) LEVEL="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    *) AAR="$1"; shift ;;
  esac
done
[ -f "$AAR" ] || { echo "FAIL: no AAR at $AAR"; exit 1; }
# Not `unzip -l | grep -q`: under pipefail grep's early exit fails the pipe.
unzip -l "$AAR" 'jni/x86_64/libcef.so' > /dev/null 2>&1 \
  || { echo "FAIL: $AAR has no x86_64 libcef.so (the emulator is x86_64)"; exit 1; }
AVD="cefrium-smoke-api$LEVEL"
[ -d "$HOME/.android/avd/$AVD.avd" ] \
  || { echo "FAIL: no AVD $AVD; run smoke-api-levels.sh --levels $LEVEL once"; exit 1; }

WORK="$ROOT/tmp/suite-on-emulator"
mkdir -p "$WORK"
port=5640
SER="emulator-$port"
if "$ADB" devices | grep -q "^$SER"; then
  echo "FAIL: $SER is already in use by someone else; not touching it"; exit 1
fi
echo ">>> boot $AVD on $SER"
"$EMU" -avd "$AVD" -port "$port" -no-window -no-audio -no-snapshot -wipe-data \
    -no-boot-anim -gpu swiftshader_indirect > "$WORK/emu.log" 2>&1 &
EPID=$!
stop() {
  "$ADB" -s "$SER" emu kill >/dev/null 2>&1
  for _ in $(seq 1 30); do kill -0 "$EPID" 2>/dev/null || break; sleep 1; done
  # Still up: it is the emulator this script started, so its own PID is safe.
  kill -0 "$EPID" 2>/dev/null && kill "$EPID"
}
trap stop EXIT
booted=0
for _ in $(seq 1 120); do
  [ "$("$ADB" -s "$SER" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { booted=1; break; }
  kill -0 "$EPID" 2>/dev/null || break
  sleep 3
done
[ "$booted" = 1 ] || { echo "FAIL: $AVD did not boot (see $WORK/emu.log)"; exit 1; }

fails=0
for run in $(seq 1 "$RUNS"); do
  echo ">>> api$LEVEL suite run $run/$RUNS"
  ANDROID_SERIAL="$SER" CEFRIUM_SDK_AAR="$AAR" \
    "$SCRIPT_DIR/run-instrumentation-suite.sh" > "$WORK/suite-$run.log" 2>&1
  rc=$?
  tail -2 "$WORK/suite-$run.log"
  # The next run overwrites the results: keep each run's, and name what failed.
  rm -rf "$WORK/results-$run"
  cp -r "$SRC/cef/cefrium_instrumentation_test/app/build/outputs/androidTest-results/connected" \
      "$WORK/results-$run" 2>/dev/null
  python3 - "$WORK/results-$run" <<'PY'
import glob, sys, xml.etree.ElementTree as E
for f in glob.glob(sys.argv[1] + '/**/*.xml', recursive=True):
    for tc in E.parse(f).getroot().iter('testcase'):
        x = tc.find('failure')
        if x is not None:
            print('    FAILED %s.%s: %s' % (tc.get('classname').split('.')[-1], tc.get('name'),
                                         (x.get('message') or '').split('\n')[0][:200]))
PY
  [ "$rc" = 0 ] || fails=$((fails + 1))
done
[ "$fails" = 0 ] || { echo "FAIL: $fails of $RUNS api$LEVEL suite run(s) failed (logs in $WORK)"; exit 1; }
echo "PASS: $RUNS api$LEVEL suite run(s)"
