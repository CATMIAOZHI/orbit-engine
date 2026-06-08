#!/bin/bash
# regen-gen-jni.sh -- regenerate the checked-in org/jni_zero/GEN_JNI.java from a
# build's generated JNI registration.
#
# WHY THIS EXISTS: cef/cefrium_sdk/java/src/org/jni_zero/GEN_JNI.java is a
# GENERATED file that is CHECKED IN on purpose -- it breaks a GN dependency cycle
# (cefrium_jni_registration scans cefrium_jni_java_deps, which depends on
# cefrium_java, which must compile against GEN_JNI). Because it is checked in, it
# MUST be regenerated whenever the Java<->native JNI surface changes -- above all
# on an engine bump. If it goes stale, consumers hit a runtime
# NoSuchMethodError (caller compiled for the new signature, GEN_JNI stub still the
# old one). This happened on the 148->149 bump (getPrimaryAccountInfo(long,int)
# vs (long)) and crashed the flagship.
#
# Usage:  cef/cefrium_sdk/regen-gen-jni.sh [out-dir]
#   out-dir defaults to out/Official_android_arm64. The JNI surface is ABI/codec
#   independent, so any built variant's srcjar is fine.
# Exit:   0 = checked-in file is now in sync (and prints whether it changed);
#         2 = the generated srcjar is missing (build cefrium_jni_registration first).
# Add --check to NOT write, only report drift (exit 1 if stale) -- for a gate.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"          # chromium/src
CHECK=0; [ "${1:-}" = "--check" ] && { CHECK=1; shift; }
OUT="${1:-out/Official_android_arm64}"
SRCJAR="$SRC/$OUT/gen/cef/cefrium_sdk/cefrium_jni_registration.srcjar"
DEST="$SRC/cef/cefrium_sdk/java/src/org/jni_zero/GEN_JNI.java"

[ -f "$SRCJAR" ] || { echo "ERROR: $SRCJAR not found -- build cef/cefrium_sdk:cefrium_jni_registration first."; exit 2; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
unzip -o -q "$SRCJAR" -d "$TMP"
GEN="$(find "$TMP" -name GEN_JNI.java | head -1)"
[ -n "$GEN" ] || { echo "ERROR: no GEN_JNI.java in $SRCJAR"; exit 2; }

if diff -q "$GEN" "$DEST" >/dev/null 2>&1; then
  echo "GEN_JNI.java in sync ($(grep -c 'native ' "$DEST") native methods)."
  exit 0
fi
echo "GEN_JNI.java DRIFT: checked-in $(grep -c 'native ' "$DEST") vs generated $(grep -c 'native ' "$GEN") native methods."
if [ "$CHECK" = 1 ]; then
  echo "STALE -- run 'cef/cefrium_sdk/regen-gen-jni.sh $OUT' and rebuild the AAR."
  exit 1
fi
cp "$GEN" "$DEST"
echo "Updated $DEST from $OUT. Rebuild the AAR so it ships the fresh GEN_JNI."
