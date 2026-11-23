#!/usr/bin/env bash
# Merge the per-architecture slim AARs of one codec flavor into a single
# multi-ABI AAR — the form Android consumers expect (AGP packages all ABIs and
# the consumer's APK/App Bundle delivers only the device's ABI).
#
# This is only correct because the V8 snapshot is EMBEDDED in libcef.so
# (build-all-variants.sh sets v8_use_external_startup_data=false). With the
# snapshot embedded, assets/ is architecture-independent, so the per-arch AARs
# (arm64-v8a, x86_64, armeabi-v7a) differ only in jni/<abi>/libcef.so and can
# be combined. The merge checks that.
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
ARM="$RELEASE_DIR/cefrium-sdk-arm-${CODEC}.aar"
if [ "$CODEC" = "codecs" ]; then
  OUT="$RELEASE_DIR/cefrium-sdk.aar"
else
  OUT="$RELEASE_DIR/cefrium-sdk-${CODEC}.aar"
fi

for f in "$ARM64" "$X64" "$ARM"; do
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

# Everything outside jni/ must be the same bytes on every architecture, or
# taking it from arm64 is wrong for the others.
python3 - "$ARM64" "$X64" "$ARM" <<'PY'
import sys, zipfile
def rest(p):
    z = zipfile.ZipFile(p)
    # Read-ahead lists are named after the library they describe, so each
    # architecture may carry its own; all of them go into the merged AAR.
    return {n: z.read(n) for n in z.namelist()
            if not n.startswith('jni/') and not n.startswith('assets/cefrium/hot-ranges/')}
base = rest(sys.argv[1])
for other in sys.argv[2:]:
    o = rest(other)
    diff = sorted(base.keys() ^ o.keys()) + [n for n in base.keys() & o.keys() if base[n] != o[n]]
    if diff:
        sys.exit('ERROR: %s differs from arm64 outside jni/: %s' % (other, diff[:10]))
PY

# Base = arm64 AAR (carries classes.jar, assets, R, manifest, jni/arm64-v8a),
# plus the other architectures' libcef.so.
cp "$ARM64" "$OUT"
TZ=UTC unzip -o -q "$X64" "jni/x86_64/*" -d "$WORK"
TZ=UTC unzip -o -q "$ARM" "jni/armeabi-v7a/*" -d "$WORK"
for aar in "$X64" "$ARM"; do
  TZ=UTC unzip -o -q "$aar" "assets/cefrium/hot-ranges/*" -d "$WORK" 2>/dev/null || true
done
# -X and UTC: the entries keep the fixed dates unzip restored, nothing else.
( cd "$WORK" && TZ=UTC zip -q -X -g -r -D "$OUT" jni $( [ -d assets ] && echo assets ) )

echo ">>> multi-ABI AAR: $OUT ($(du -h "$OUT" | cut -f1))"
unzip -l "$OUT" | grep -E "jni/.*libcef.so"
