#!/bin/bash
# verify-reproducible.sh -- compare a published libcef.so with one built here.
#
# Usage: cef/cefrium_sdk/verify-reproducible.sh <version> <out-dir> [codecs|libre]
#   <version>  a published SDK version, e.g. 0.22.0
#   <out-dir>  a clean build of that version's tag, e.g. out/Official_android_arm64_codecs
#              (its args.gn gives the ABI)
# Run from chromium/src. Exit 0 when the two libcef.so are identical.
#
# Two clean builds of one cef commit give the same libcef.so byte for byte
# (measured 2026-11-23, arm64 codecs, same out dir). The library carries the
# cef commit it was built from (CEF_COMMIT_HASH), and this script prints it:
# build exactly that commit. Any other, even one that only touches a script,
# changes the bytes.
set -euo pipefail
VERSION="${1:?usage: verify-reproducible.sh <version> <out-dir> [codecs|libre]}"
OUT="${2:?usage: verify-reproducible.sh <version> <out-dir> [codecs|libre]}"
FLAVOUR="${3:-codecs}"
MAVEN="${CEFRIUM_R2_URL:-https://maven.cefrium.com}/com/cefrium"
READELF=third_party/llvm-build/Release+Asserts/bin/llvm-readelf

[ -f "$OUT/libcef.so" ] || { echo "FAIL: no $OUT/libcef.so"; exit 1; }
cpu="$(sed -n 's/^target_cpu *= *"\(.*\)"/\1/p' "$OUT/args.gn")"
case "$cpu" in
  arm64) ABI=arm64-v8a ;;
  arm) ABI=armeabi-v7a ;;
  x64) ABI=x86_64 ;;
  *) echo "FAIL: target_cpu \"$cpu\" in $OUT/args.gn"; exit 1 ;;
esac

WORK="$(mktemp -d "${TMPDIR:-/tmp}/cefrium-repro.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
ART="cefrium-native-$FLAVOUR-$ABI"
URL="$MAVEN/$ART/$VERSION/$ART-$VERSION.aar"
curl -sfL -o "$WORK/native.aar" "$URL" || { echo "FAIL: cannot fetch $URL"; exit 1; }
unzip -q -o "$WORK/native.aar" "jni/$ABI/libcef.so" -d "$WORK" \
  || { echo "FAIL: no jni/$ABI/libcef.so in $ART-$VERSION.aar"; exit 1; }
PUB="$WORK/jni/$ABI/libcef.so"

# The library embeds the cef commit it was built from (CEF_COMMIT_HASH): of its
# 40-hex strings, the one that is a commit of this cef repository. Build that
# commit; the release tag can be later (the cut commits doc figures after it
# builds).
built_from=""
for h in $(grep -a -o -E '\b[0-9a-f]{40}\b' "$PUB" | sort -u); do
  if git -C cef cat-file -e "$h^{commit}" 2>/dev/null; then built_from="$h"; break; fi
done
echo "published library built from cef ${built_from:-<a commit not in this repository: fetch the tags>}"

pub_sha="$(sha256sum "$PUB" | cut -d' ' -f1)"
own_sha="$(sha256sum "$OUT/libcef.so" | cut -d' ' -f1)"
echo "published $ART $VERSION: $pub_sha"
echo "built     $OUT/libcef.so: $own_sha"
if [ "$pub_sha" = "$own_sha" ]; then
  echo "OK: identical"
  exit 0
fi
echo "DIFFERENT (sizes $(stat -c %s "$PUB") and $(stat -c %s "$OUT/libcef.so"))"
for f in "$PUB" "$OUT/libcef.so"; do
  echo "  build id $("$READELF" -n "$f" | sed -n 's/.*Build ID: //p')  $f"
done
# The cef commit this build used, as it embedded it.
commit="$(sed -n 's/^#define CEF_COMMIT_HASH "\(.*\)"/\1/p' \
  "$OUT/gen/cef/include/cef_version.h" 2>/dev/null || true)"
if [ -n "$commit" ]; then
  if grep -a -q "$commit" "$PUB"; then
    echo "  the published library was built from cef $commit too"
  else
    echo "  the published library was NOT built from cef $commit (this build's commit):"
    echo "  build cef ${built_from:-the commit it names}"
  fi
fi
exit 1
