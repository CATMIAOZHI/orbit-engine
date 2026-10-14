#!/bin/bash
# Predict what an engine bump will cost on the PATCH side, without syncing
# anything and without a build.
#
#   cef/cefrium_sdk/probe-engine-bump.sh <chromium-tag>
#     e.g. probe-engine-bump.sh 156.0.8078.17
#
# Fetches only the files our Android patches target, at that tag, from Gitiles,
# and reports for each patch: applies clean / applies with reduced context /
# conflicts, and for a conflict WHICH FILE and WHETHER IT IS REAL.
#
# WHY A PROBE IS WORTH IT
#
# A bump starts with a `gclient sync` that resets the working tree and downloads
# tens of GB, after which you find out how bad the patches are. This answers that
# question first, in under a minute, over HTTP. It does NOT predict C++/Java API
# breaks -- only the patch set.
#
# TWO TRAPS THIS ENCODES (both caught the hard way)
#
#  1. A patch can target SEVERAL files. Diagnosing it by its first `--- ` line
#     attributes failures to the wrong file: android/renderer_and_browser_init_
#     guards touches four, and the failing hunk was in the second.
#  2. A patch whose file is ALSO touched by a core CEF patch cannot apply to a
#     pristine Chromium tree at all -- its context contains CEF's changes. Those
#     show up as conflicts and are test artifacts, not drift. The ownership check
#     below separates the two, which is the difference between "2 patches to
#     regenerate" and a scary "5".
set -uo pipefail

TAG="${1:?usage: probe-engine-bump.sh <chromium-tag>   e.g. 156.0.8078.17}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT="$(cd "$CEF_DIR/../../.." && pwd)"
PATCHES="$CEF_DIR/patch/patches"
W="$ROOT/tmp/bump-probe-$TAG"
rm -rf "$W"; mkdir -p "$W/tree"

echo "Probing the android patch set against Chromium $TAG"

# Files targeted by the android set. Every `--- ` line, not just the first.
grep -h '^--- ' "$PATCHES"/android/*.patch | grep -v /dev/null \
  | sed 's/^--- //;s/\t.*//' | sort -u > "$W/files.txt"
echo "  distinct target files: $(grep -c . "$W/files.txt")"

gone=0
while IFS= read -r f; do
  out="$W/tree/$f"; mkdir -p "$(dirname "$out")"
  if ! curl -sfL "https://chromium.googlesource.com/chromium/src/+/refs/tags/$TAG/$f?format=TEXT" \
       | base64 -d > "$out" 2>/dev/null || [ ! -s "$out" ]; then
    echo "  RENAMED/DELETED in $TAG: $f"
    gone=$((gone+1))
  fi
done < "$W/files.txt"
[ "$gone" -eq 0 ] && echo "  all target files still exist at $TAG" \
                  || echo "  $gone file(s) gone -- those patches need rework regardless"

( cd "$W/tree" && git init -q && git add -A \
  && git -c user.name=p -c user.email=p@p commit -qm "chromium $TAG subset" ) >/dev/null 2>&1

# A file touched by MORE THAN ONE patch cannot apply to a pristine tree.
shared_file() {
  local f="$1" n
  n=$(grep -l -- "$f" "$PATCHES"/*.patch "$PATCHES"/android/*.patch 2>/dev/null | wc -l)
  [ "$n" -gt 1 ]
}

echo
clean=0; fuzz=0; artifact=0; real=0
for p in "$PATCHES"/android/*.patch; do
  n="$(basename "$p" .patch)"
  if ( cd "$W/tree" && git apply -p0 --check "$p" ) 2>/dev/null; then
    printf "  CLEAN      %s\n" "$n"; clean=$((clean+1)); continue
  fi
  # Which file actually failed, straight from git.
  bad="$( ( cd "$W/tree" && git apply -p0 --check "$p" ) 2>&1 \
          | grep -oE 'patch failed: [^:]+' | sed 's/patch failed: //' | head -1)"
  if shared_file "$bad"; then
    printf "  artifact   %-50s (%s is shared with a core CEF patch)\n" "$n" "${bad##*/}"
    artifact=$((artifact+1)); continue
  fi
  if ( cd "$W/tree" && git apply -p0 --check -C1 "$p" ) 2>/dev/null; then
    printf "  DRIFT-MINOR %-49s %s (applies with reduced context)\n" "$n" "$bad"
    fuzz=$((fuzz+1))
  else
    printf "  DRIFT       %-49s %s\n" "$n" "$bad"
    real=$((real+1))
  fi
done

echo
cat <<EOF
Against Chromium $TAG:
  clean                      : $clean
  minor drift (context only) : $fuzz
  real drift (regenerate)    : $real
  test artifacts (shared file, re-check after CEF's own patches): $artifact

Patch-side cost of the bump is (real drift + minor drift) = $((real + fuzz)).
This says NOTHING about C++/Java/GN API breaks, which are the other half of a
bump and only a build can find. Probe tree kept at $W
EOF
