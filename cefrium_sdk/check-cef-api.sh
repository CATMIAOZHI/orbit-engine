#!/bin/bash
# Is the committed CEF C API consistent? Two checks on a clean export of HEAD:
#
#   1. tools/translator.py writes nothing: the generated C API (include/capi,
#      libcef_dll/cpptoc + ctocpp, wrapper) matches the C++ headers.
#   2. tools/version_manager.py -c: the API hashes in cef_api_versions.json
#      match the headers for every API version. An API addition without its
#      CEF_API_ADDED guard changes the hash of versions that never had it.
#
# Both drifted unnoticed before: the generated files were 6 headers behind
# (fixed by hand in this tree), and the hashes failed for 13300 and 15600 from
# two unguarded enum additions.
#
# Runs on an export, never in the tree: the translator writes in place and a
# build may be running. Read-only for chromium/src (it is symlinked in).
#
# Usage: cef/cefrium_sdk/check-cef-api.sh [<commit>]     (default HEAD)
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="$(cd "$CEF/.." && pwd)"
ROOT="$(cd "$SRC/../.." && pwd)"
REV="${1:-HEAD}"
WORK="$ROOT/tmp/cef-api-check"

rm -rf "$ROOT/tmp/cef-api-check"
mkdir -p "$WORK/src/cef" "$WORK/pristine"
git -C "$CEF" archive "$REV" | tar -x -C "$WORK/src/cef"
git -C "$CEF" archive "$REV" | tar -x -C "$WORK/pristine"
# version_manager parses headers with Chromium's clang and reads a few Chromium
# files (sandbox mojom, chrome/VERSION).
for e in "$SRC"/*; do
  n="$(basename "$e")"
  case "$n" in cef|out) ;; *) ln -s "$e" "$WORK/src/$n" ;; esac
done

cd "$WORK/src/cef"
fail=0
echo "== translator ($REV)"
python3 tools/translator.py --root-dir . >"$WORK/translator.log" 2>&1 || {
  echo "FAIL: translator.py errored (see $WORK/translator.log)"; fail=1; }
# VERSION.stamp carries the git description, which an export does not have.
changed="$(diff -rq "$WORK/pristine" "$WORK/src/cef" | grep -v 'VERSION.stamp' || true)"
if [ -n "$changed" ]; then
  echo "FAIL: the generated C API is out of date with the headers:"
  echo "$changed" | sed 's/^/    /'
  echo "    Fix: python3 tools/translator.py --root-dir . (in cef/), commit."
  fail=1
else
  echo "OK: generated C API matches the headers"
fi

echo "== API hashes ($REV)"
if out="$(python3 tools/version_manager.py -c 2>&1)"; then
  echo "OK: $(echo "$out" | grep -E 'hashes checked' | tail -1)"
else
  echo "$out" | grep -E 'ERROR|checked' | sed 's/^/    /'
  echo "FAIL: API hashes do not match. Guard API additions with"
  echo "      CEF_API_ADDED(CEF_EXPERIMENTAL) or added=experimental."
  fail=1
fi

cd "$ROOT"
rm -rf "$ROOT/tmp/cef-api-check"
exit "$fail"
