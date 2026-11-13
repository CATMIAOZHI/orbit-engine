#!/bin/bash
# dev-iterate.sh -- engine change -> validated on the device, in one command.
#
# WHY THIS EXISTS: this is the loop you actually run while fixing something.
# CLAUDE.md told everyone to use `cefrium-sample/test-build.sh` for it; that
# script did not exist, so the seven steps got chained by hand instead -- eight
# times in one evening during the 0.10.0 work, each slightly differently. The
# variations are where the mistakes live: forgetting to re-slim after a rebuild
# and testing yesterday's AAR, forgetting `pm clear` and reading a verdict that
# came from a remembered permission, or declaring a run failed because the
# result file was read before it was written.
#
# Usage:
#   cef/cefrium_sdk/dev-iterate.sh [options]
#     --activity <Name>   launch com.cefrium.sample/.<Name> and report its verdict
#     --clean-profile     pm clear + re-grant first (REQUIRED for permission
#                         tests: a decided permission is remembered and the
#                         handler is never consulted on a second run)
#     --skip-build        reuse the AAR already in the out dir (Java-only edits
#                         to the SAMPLE need no engine rebuild)
#     --variant <dir>     out dir (default Official_android_arm64_codecs)
#     --sample <dir>      sample project (default java-sample)
#     --jobs <n>          ninja -j (default 18)
#
# Exit: 0 only if every step ran AND, with --activity, the verdict says PASS.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"
ROOT="$(cd "$SRC/../.." && pwd)"

VARIANT="Official_android_arm64_codecs"
SAMPLE="java-sample"
ACTIVITY=""
CLEAN_PROFILE=0
SKIP_BUILD=0
JOBS=18

while [ $# -gt 0 ]; do
  case "$1" in
    --activity) ACTIVITY="$2"; shift 2 ;;
    --clean-profile) CLEAN_PROFILE=1; shift ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --variant) VARIANT="$2"; shift 2 ;;
    --sample) SAMPLE="$2"; shift 2 ;;
    --jobs) JOBS="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

export JAVA_HOME="${JAVA_HOME:-$SRC/third_party/jdk/current}"
export ANDROID_HOME="${ANDROID_HOME:-$SRC/third_party/android_sdk/public}"

# Put depot_tools' pinned python (3.11.8) ahead of the system one. Chromium 156
# invokes its own `third_party/cpython3` for gn actions, so this is currently
# belt-and-braces -- but the system python here is 3.14, whose stricter argparse
# aborts Chromium's gyp scripts, and that failure lands HOURS into a build. The
# cost of the guard is one PATH entry; the cost of not having it was a dead
# build once already. Keep it independent of whatever the ambient shell has.
if [ -x "$ROOT/depot_tools/python-bin/python3" ]; then
  export PATH="$ROOT/depot_tools/python-bin:$PATH"
fi
ADB="$ANDROID_HOME/platform-tools/adb"
OUT="$SRC/out/$VARIANT"
PROJ="$ROOT/cefrium-sample/$SAMPLE"
PKG="com.cefrium.sample"

say() { echo; echo ">>> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

[ -d "$OUT" ] || die "no out dir $OUT (run build-all-variants.sh first)"
[ -d "$PROJ" ] || die "no sample project $PROJ"
"$ADB" get-state >/dev/null 2>&1 || die "no device attached"

# ---- 1-2. engine + AAR ---------------------------------------------------
if [ "$SKIP_BUILD" = "0" ]; then
  say "ninja -j $JOBS ($VARIANT)"
  # Chromium's pinned ninja (see build-all-variants.sh for why not PATH's).
  ( cd "$SRC" && third_party/ninja/ninja -j "$JOBS" -C "out/$VARIANT" cef:libcef cef/cefrium_sdk:cefrium_aar )

  say "slim the AAR"
  ( cd "$SRC" && python3 cef/cefrium_sdk/slim_aar.py \
      --input "out/$VARIANT/cefrium-raw.aar" \
      --output "out/$VARIANT/cefrium-sdk.aar" )
fi
[ -f "$OUT/cefrium-sdk.aar" ] || die "no slim AAR at $OUT/cefrium-sdk.aar"
# Show an API change now rather than at the cut (which fails on it).
python3 "$SCRIPT_DIR/api-dump.py" --check --warn "$OUT/cefrium-sdk.aar" \
  "$SCRIPT_DIR/api/cefrium-sdk.api" | tail -40

# ---- 3. stage it where the sample consumes it ----------------------------
# Every sample takes the SDK as app/libs/cefrium-sdk.aar. Copying is the step
# most easily forgotten after a rebuild, and forgetting it means testing the
# previous engine while believing you tested the new one.
say "stage the AAR into $SAMPLE"
mkdir -p "$PROJ/app/libs"
cp "$OUT/cefrium-sdk.aar" "$PROJ/app/libs/cefrium-sdk.aar"
ls -l --time-style=+%H:%M "$PROJ/app/libs/cefrium-sdk.aar" | sed 's/^/    /'

# ---- 4-5. build + install ------------------------------------------------
say "gradle assembleDebug"
( cd "$PROJ" && ./gradlew assembleDebug --no-daemon -q 2>&1 \
    | grep -vE "^WARNING|^Note:|^$" || true )
APK="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || die "no APK at $APK"

say "install"
"$ADB" install -r "$APK" | tail -1

[ -n "$ACTIVITY" ] || { say "done (no --activity given, nothing launched)"; exit 0; }

# ---- 6. run it -----------------------------------------------------------
if [ "$CLEAN_PROFILE" = "1" ]; then
  say "pm clear + re-grant (fresh profile)"
  "$ADB" shell pm clear "$PKG" >/dev/null
  # Re-grant EVERY runtime permission the app declares, read back from the
  # device so the list maintains itself. Granting only POST_NOTIFICATIONS (what
  # this did) silently breaks any test whose subject is gated on another one:
  # with location revoked, a geolocation request is denied by
  # IsLocationAccessPossible before it reaches the code under test, and the run
  # reports a pass for a path that never executed.
  GRANTED=0
  for p in $("$ADB" shell dumpsys package "$PKG" 2>/dev/null \
             | grep -oE 'android\.permission\.[A-Z_]+' | sort -u); do
    # Non-runtime permissions make pm grant fail; that is expected, not an error.
    if "$ADB" shell pm grant "$PKG" "$p" >/dev/null 2>&1; then
      GRANTED=$((GRANTED+1))
    fi
  done
  say "re-granted $GRANTED runtime permission(s)"
else
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
fi

# Snapshot the verdict files so we can tell a FRESH one from a leftover. A
# stale result file read as a pass is the quietest way for this loop to lie.
BEFORE="$("$ADB" shell run-as "$PKG" ls -l files/ 2>/dev/null | grep "_result.txt" || true)"

say "launch .$ACTIVITY"
"$ADB" shell am start -n "$PKG/.$ACTIVITY" >/dev/null

VERDICT=""
for i in $(seq 1 20); do
  sleep 4
  AFTER="$("$ADB" shell run-as "$PKG" ls -l files/ 2>/dev/null | grep "_result.txt" || true)"
  if [ -n "$AFTER" ] && [ "$AFTER" != "$BEFORE" ]; then
    FILE="$(echo "$AFTER" | awk '{print $NF}' | head -1)"
    VERDICT="$("$ADB" shell run-as "$PKG" cat "files/$FILE" 2>/dev/null)"
    break
  fi
done

echo
if [ -z "$VERDICT" ]; then
  echo "NO VERDICT after 80s. The activity wrote no result file."
  echo "Check whether it died rather than assuming it hung:"
  echo "    adb shell dumpsys activity exit-info $PKG | grep -E 'reason=|process='"
  echo "    adb logcat -b crash -d | grep -E 'FATAL|Caused by' | tail"
  echo "  reason 4/5/6 = a real crash; 10 = your own force-stop;"
  echo "  13 subreason 17 = normal sandboxed-renderer reaping, NOT a crash."
  exit 1
fi

echo "$VERDICT"
echo
case "$VERDICT" in
  PASS*) say "PASS"; exit 0 ;;
  *)     say "FAIL -- the verdict above is the evidence, not the build output"; exit 1 ;;
esac
