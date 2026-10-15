#!/bin/bash
# why-died.sh -- did the app actually die, and of what? Read-only.
#
# WHY THIS EXISTS: "the app crashed" is the most frequent wrong conclusion in
# this project. The W90 takes 15-20 s to spawn a renderer on a session restore,
# so a `pidof` or a screencap taken before that reads as a crash; a whole
# tab-restore "crash" investigation was that measurement artifact. And normal
# sandboxed-renderer reaping (reason 13 subreason 17) looks alarming in
# exit-info while being completely routine. The rules for telling these apart
# were prose in CLAUDE.md, which means they were re-applied from memory, badly.
#
# Usage:  cef/cefrium_sdk/why-died.sh [package] [--lib <out-dir>]
#           package  default com.cefrium.sample
#           --lib    out dir whose lib.unstripped/libcef.so symbolizes a native
#                    crash (default Official_android_arm64_codecs)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"
PKG="com.cefrium.sample"
OUTDIR="Official_android_arm64_codecs"

while [ $# -gt 0 ]; do
  case "$1" in
    --lib) OUTDIR="$2"; shift 2 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) PKG="$1"; shift ;;
  esac
done

ADB="${ANDROID_HOME:-$SRC/third_party/android_sdk/public}/platform-tools/adb"
"$ADB" get-state >/dev/null 2>&1 || { echo "ERROR: no device attached" >&2; exit 1; }

echo "=============================================================="
echo " $PKG -- is it alive, and did it die?"
echo "=============================================================="

echo
echo "-- alive right now?"
PS="$("$ADB" shell ps -A -o PID,NAME 2>/dev/null | grep "$PKG" || true)"
if [ -n "$PS" ]; then
  echo "$PS" | sed 's/^/   /'
else
  echo "   no process. NOTE: this alone proves nothing within ~20 s of a launch"
  echo "   or a session restore -- the renderer spawn is slow on the W90."
fi
echo "-- focused window"
"$ADB" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | sed 's/^/   /'

echo
echo "-- exit-info (the authoritative record)"
INFO="$("$ADB" shell dumpsys activity exit-info "$PKG" 2>/dev/null || true)"
if [ -z "$INFO" ]; then
  echo "   (none -- the process has never died on this boot)"
else
  echo "$INFO" | grep -E "timestamp=|reason=" | head -12 | sed 's/^/   /'
fi

# Classify. Only 4/5/6 on the MAIN process is a crash; the rest are routine and
# are regularly misread as failures.
MAIN_CRASH="$(printf '%s' "$INFO" | grep -E "process=$PKG " -A0 -B1 | grep -cE "reason=(4|5|6) " || true)"
echo
echo "-- verdict"
if printf '%s' "$INFO" | grep -qE "reason=(4|5|6) "; then
  echo "   REAL CRASH: reason 4 (NATIVE) / 5 (CRASH) / 6 (ANR) is present."
else
  echo "   NO crash reason (4/5/6) recorded."
  printf '%s' "$INFO" | grep -qE "reason=10 " && \
    echo "   reason 10 = USER REQUESTED -- that is a force-stop, probably yours."
  printf '%s' "$INFO" | grep -qE "subreason=17 " && \
    echo "   subreason 17 = ISOLATED NOT NEEDED -- normal renderer reaping, NOT a crash."
fi

echo
echo "-- java crash (logcat crash buffer)"
JAVA="$("$ADB" shell logcat -b crash -d 2>/dev/null | grep -E "FATAL EXCEPTION|^.*Caused by|Process: $PKG" | tail -6 || true)"
[ -n "$JAVA" ] && echo "$JAVA" | sed 's/^/   /' || echo "   (nothing in the crash buffer)"

echo
echo "-- native crash (dropbox)"
NATIVE="$("$ADB" shell dumpsys dropbox --print 2>/dev/null | grep -A4 "libcef.so" | tail -12 || true)"
if [ -n "$NATIVE" ]; then
  echo "$NATIVE" | sed 's/^/   /'
  LIB="$SRC/out/$OUTDIR/lib.unstripped/libcef.so"
  echo
  if [ -f "$LIB" ]; then
    echo "   symbolize an offset from the frames above with:"
    echo "     llvm-addr2line -f -C -e $LIB <offset>"
  else
    echo "   (no $LIB to symbolize with -- build that variant first)"
  fi
else
  echo "   (no libcef.so backtrace in dropbox)"
fi

echo
echo "Reminder: app-level Log.i/Log.e are SUPPRESSED on the W90. Absence of app"
echo "logs is not evidence; native LOG(ERROR) and dumpsys are."
