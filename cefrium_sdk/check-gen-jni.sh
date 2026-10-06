#!/bin/bash
# Verify the checked-in GEN_JNI.java still matches what jni_zero generates for
# the current engine. Run after a build, from chromium/src.
#
# WHY THIS EXISTS
#
# cef/cefrium_sdk/java/src/org/jni_zero/GEN_JNI.java is a GENERATED file that is
# checked into the repo (it has to be: it is a source of cefrium_java, which is
# in turn one of the java_targets the generator reads, so consuming the generated
# srcjar directly would be a GN cycle).
#
# That makes it the one build input nothing invalidates. After an engine bump it
# is silently stale: everything compiles, the AAR packages, the release gate
# passes -- and then the first JNI call to a method the new engine added dies at
# runtime with NoSuchMethodError. On 152 -> 154 the drift was 107 methods added
# and 70 removed, and the app crashed on its first child-process launch
# (SysUtils.hasLargeProcessCountSupport). The test APK did NOT catch it, because
# it builds its own registration from the generated srcjar; only a consumer of
# the AAR hits it.
#
# So: compare the two and fail loudly, turning a device-only crash into a build
# error with the fix in the message.
#
# Usage:  cd chromium/src && cef/cefrium_sdk/check-gen-jni.sh [out-dir]
set -uo pipefail

OUT="${1:-out/Official_android_arm64_codecs}"
SRC="$(pwd)"
CHECKED_IN="cef/cefrium_sdk/java/src/org/jni_zero/GEN_JNI.java"
SRCJAR="$OUT/gen/cef/cefrium_sdk/cefrium_jni_registration.srcjar"

[ -f "$CHECKED_IN" ] || { echo "ERROR: $CHECKED_IN not found."; exit 2; }
if [ ! -f "$SRCJAR" ]; then
  echo "ERROR: $SRCJAR not found -- build cef/cefrium_sdk:cefrium_aar first."
  exit 2
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
( cd "$TMP" && unzip -o -q "$SRC/$SRCJAR" ) || { echo "ERROR: cannot read $SRCJAR"; exit 2; }
GENERATED="$TMP/org/jni_zero/GEN_JNI.java"
[ -f "$GENERATED" ] || { echo "ERROR: no GEN_JNI.java inside $SRCJAR"; exit 2; }

# Compare the METHOD SETS, not the file bytes: the generator reorders and the
# hashed-name comments churn, so a byte diff would cry wolf on every build.
names() { grep -oE '[a-zA-Z_][a-zA-Z_0-9]*\(' "$1" | tr -d '(' | sort -u; }
added=$(comm -13 <(names "$CHECKED_IN") <(names "$GENERATED") | grep -c .)
removed=$(comm -23 <(names "$CHECKED_IN") <(names "$GENERATED") | grep -c .)

if [ "$added" = "0" ] && [ "$removed" = "0" ]; then
  echo "GEN_JNI check: OK (method sets identical)"
  exit 0
fi

echo
echo "============================================================"
echo "  GEN_JNI IS STALE -- this would crash on device, not here"
echo "============================================================"
echo "  checked in: $CHECKED_IN"
echo "  generated:  $SRCJAR"
echo "  $added method(s) the engine has and the checked-in copy lacks"
echo "  $removed method(s) the checked-in copy has and the engine dropped"
echo
echo "  The missing ones throw NoSuchMethodError the first time Java calls"
echo "  them. Nothing in the build or the release gate catches that; only a"
echo "  consumer of the AAR running on a device does."
echo
if [ "$added" -gt 0 ]; then
  echo "  First few the engine added:"
  comm -13 <(names "$CHECKED_IN") <(names "$GENERATED") | head -8 | sed 's/^/    /'
  echo
fi
echo "  Fix:"
echo "    cd $SRC"
echo "    unzip -o -q $SRCJAR -d /tmp/genjni"
echo "    cp /tmp/genjni/org/jni_zero/GEN_JNI.java $CHECKED_IN"
echo "    ninja -C $OUT cef/cefrium_sdk:cefrium_aar"
echo "    # then re-run this check: the set should converge in one pass"
echo
exit 1
