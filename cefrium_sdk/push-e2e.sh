#!/bin/bash
# push-e2e.sh -- Web Push end to end on a device, as a gate.
#
#   cef/cefrium_sdk/push-e2e.sh [<sdk.aar>]
#
# Run from chromium/src. Without an argument it tests out/release/cefrium-sdk.aar,
# the bytes a cut publishes. Needs a Play-certified device (the push goes
# through FCM) and python3 with the cryptography module on this host.
#
# WHY THIS EXISTS: the instrumentation suite tests push with a fake InstanceID,
# which cannot reproduce Play services or a process being killed. A subscription
# whose app id was lost when the app was killed passed every single run: the
# failure only shows when a page unsubscribes and subscribes again, run after
# run. So each profile runs three times in a row, and two sites check that one
# unsubscribing leaves the other working.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"
ROOT="$(cd "$SRC/../.." && pwd)"
DEMO="$ROOT/cefrium-sample/push-demo"
AAR="${1:-$SRC/out/release/cefrium-sdk.aar}"
RUNS="${CEFRIUM_PUSH_RUNS:-3}"

[ -f "$AAR" ] || { echo "ERROR: no AAR at $AAR" >&2; exit 1; }
[ -d "$DEMO/e2e" ] || { echo "ERROR: no push-demo at $DEMO" >&2; exit 1; }

export ANDROID_HOME="${ANDROID_HOME:-$SRC/third_party/android_sdk/public}"
export JAVA_HOME="${JAVA_HOME:-$SRC/third_party/jdk/current}"
export ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device" {n++} END {print n+0}')
if [ "$DEVICES" -eq 0 ]; then
  echo "ERROR: no device. Push needs a Play-certified one; there is no dry run." >&2
  exit 1
fi
if [ "$DEVICES" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
  echo "ERROR: $DEVICES devices attached; set ANDROID_SERIAL." >&2
  exit 1
fi
SERIAL="${ANDROID_SERIAL:-$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
python3 -c "import cryptography" 2>/dev/null || {
  echo "ERROR: python3 needs the cryptography module (apt install python3-cryptography)." >&2
  exit 1; }

echo ">>> push-demo against $(basename "$AAR") on $SERIAL"
mkdir -p "$DEMO/app/libs"
cp "$AAR" "$DEMO/app/libs/cefrium-sdk.aar" || exit 1
( cd "$DEMO" && ./gradlew assembleDebug --no-daemon -q ) || exit 1
"$ADB" -s "$SERIAL" install -r "$DEMO/app/build/outputs/apk/debug/app-debug.apk" >/dev/null || exit 1

# Under tmp/ on disk: /tmp is a small tmpfs on the build host.
mkdir -p "$ROOT/tmp"
LOGS="$(mktemp -d "$ROOT/tmp/push-e2e.XXXXXX")"
failed=0
run() {
  local name="$1"; shift
  local log="$LOGS/$name.txt"
  timeout 600 python3 "$@" --serial "$SERIAL" >"$log" 2>&1
  local verdict
  verdict="$(grep -o 'VERDICT: [A-Z]*' "$log" | tail -1)"
  echo "    $name: ${verdict:-NO VERDICT}"
  if [ "$verdict" != "VERDICT: PASS" ]; then
    failed=1
    grep -E '^(FAIL|Traceback)' "$log" | sed 's/^/      /'
    echo "      full log: $log"
  fi
}

for profile in default push; do
  for i in $(seq 1 "$RUNS"); do
    run "$profile-$i" "$DEMO/e2e/webpush-e2e.py" --profile "$profile"
  done
  # An unsubscribe cut short by the process dying must not leave anything the
  # next subscription reuses: it reused the app id, and with a new key it was
  # dead (410 on every message).
  run "$profile-killed" "$DEMO/e2e/webpush-e2e.py" --profile "$profile" --kill-after-unsubscribe
  run "$profile-after-kill" "$DEMO/e2e/webpush-e2e.py" --profile "$profile"
done
run two-sites "$DEMO/e2e/two-sites-e2e.py"

if [ "$failed" -ne 0 ]; then
  echo "FAIL: Web Push end to end. Logs in $LOGS"
  exit 1
fi
rm -rf "$LOGS"
echo "PASS: Web Push end to end ($RUNS runs per profile, two sites)"
