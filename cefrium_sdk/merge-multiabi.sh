#!/usr/bin/env bash
# Merge the per-architecture slim AARs of one codec flavor into a single
# multi-ABI AAR — the form Android consumers expect (AGP packages all ABIs and
# the consumer's APK/App Bundle delivers only the device's ABI).
#
# This is only correct because the V8 snapshot is EMBEDDED in libcef.so
# (build-all-variants.sh sets v8_use_external_startup_data=false). With the
# snapshot embedded, assets/ is effectively architecture-independent, so the two
# per-arch AARs differ only in jni/<abi>/libcef.so and can be combined.
#
# Usage: cef/cefrium_sdk/merge-multiabi.sh [codecs|libre]
#   codecs -> out/release/cefrium-sdk.aar         (default artifact)
#   libre  -> out/release/cefrium-sdk-libre.aar
set -euo pipefail

CODEC="${1:-codecs}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RELEASE_DIR="$(cd "$SCRIPT_DIR/../../out/release" && pwd)"

ARM64="$RELEASE_DIR/cefrium-sdk-arm64-${CODEC}.aar"
X64="$RELEASE_DIR/cefrium-sdk-x64-${CODEC}.aar"
if [ "$CODEC" = "codecs" ]; then
  OUT="$RELEASE_DIR/cefrium-sdk.aar"
else
  OUT="$RELEASE_DIR/cefrium-sdk-${CODEC}.aar"
fi

for f in "$ARM64" "$X64"; do
  [ -f "$f" ] || { echo "ERROR: missing $f (build it with build-all-variants.sh)" >&2; exit 1; }
done

# Sanity: refuse to merge if the snapshot is NOT embedded (external snapshot is
# arch-specific and would be wrong for one ABI in a combined AAR).
if unzip -l "$ARM64" | grep -q "assets/snapshot_blob"; then
  if ! unzip -l "$X64" | grep -q "assets/snapshot_blob"; then
    echo "ERROR: inconsistent snapshot layout between arches" >&2; exit 1
  fi
  echo "NOTE: external snapshot present in assets but IGNORED at runtime"
  echo "      (v8_use_external_startup_data=false embeds it in libcef.so)."
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Base = arm64 AAR (carries classes.jar, assets, R, manifest, jni/arm64-v8a).
cp "$ARM64" "$OUT"
# Add the x86_64 native lib from the x64 AAR.
unzip -o -q "$X64" "jni/x86_64/*" -d "$WORK"
( cd "$WORK" && zip -q -g "$OUT" jni/x86_64/libcef.so )

echo ">>> multi-ABI AAR: $OUT ($(du -h "$OUT" | cut -f1))"
unzip -l "$OUT" | grep -E "jni/.*libcef.so"
