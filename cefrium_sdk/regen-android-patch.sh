#!/bin/bash
# regen-android-patch.sh -- regenerate one Android patch that failed to apply
# after an engine bump, against the right base even when another patch
# changes the same file.
#
#   cef/cefrium_sdk/regen-android-patch.sh start  <name>   # run from chromium/src
#   ... adapt each <file>.rej by hand to the new code, delete the .rej ...
#   cef/cefrium_sdk/regen-android-patch.sh finish <name>
#
# start snapshots every file android/<name>.patch touches (the tree with all
# other patches applied) into tmp/engine-bump/base/<name>/, then applies the
# patch with --reject and prints the rejected hunks. finish rewrites the patch
# as the diff from those snapshots (patch_against_base.py) and runs
# check-patch-format.sh. Regenerate in patch.cfg order: a snapshot is only the
# right base if every earlier patch touching that file is already applied.
# verify-patch-order.py then proves a clean reapply.
set -u
MODE="${1:?usage: regen-android-patch.sh start|finish <name>}"
NAME="${2:?usage: regen-android-patch.sh start|finish <name>}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"
ROOT="$(cd "$SRC/../.." && pwd)"
BASE="$ROOT/tmp/engine-bump/base/$NAME"
cd "$SRC" || exit 1
P="cef/patch/patches/android/$NAME.patch"
[ -f "$P" ] || { echo "no $P"; exit 1; }
FILES="$(grep -oP '^\+\+\+ \K\S+' "$P" | sort -u)"

case "$MODE" in
  start)
    rm -rf "${BASE:?}"; mkdir -p "$BASE"
    for f in $FILES; do
      mkdir -p "$BASE/$(dirname "$f")"; cp "$f" "$BASE/$f"
    done
    git apply -p0 --reject --verbose "$P" 2>&1 \
      | grep -E "Rejected|applied cleanly|succeeded|error: patch failed"
    for f in $FILES; do
      [ -f "$f.rej" ] && { echo "== $f.rej"; cat "$f.rej"; }
    done
    exit 0 ;;
  finish)
    args=()
    for f in $FILES; do
      [ -f "$f.rej" ] && { echo "UNRESOLVED: $f.rej"; exit 1; }
      rm -f "$f.orig"
      [ -f "$BASE/$f" ] || { echo "no snapshot for $f: run start first"; exit 1; }
      args+=("$f=$BASE/$f")
    done
    python3 "$SCRIPT_DIR/patch_against_base.py" "android/$NAME" "${args[@]}" || exit 1
    "$SCRIPT_DIR/check-patch-format.sh" ;;
  *) echo "usage: regen-android-patch.sh start|finish <name>"; exit 1 ;;
esac
