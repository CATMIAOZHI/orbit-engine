#!/bin/bash
# check-patch-format.sh -- enforce the CEF patch storage format.
#
# CEF's patcher.py applies patches with `git apply -p0` (cef/tools/git_util.py),
# which strips NO path prefix. So every patch under cef/patch/patches/ must use
# bare paths (`--- chrome/...`, `+++ chrome/...`, `diff --git chrome/x chrome/x`)
# and NOT git's default `a/`/`b/` prefix. A prefixed patch fails to apply on a
# clean tree with "No such file or directory" -- a failure that is invisible on
# an incremental tree (it "works" only because the change is already there).
#
# This guard fails if any patch carries an a/ or b/ prefix, so the regression
# cannot reach a release. See cef/PATCHING-CEFRIUM.md.
#
# Usage:  cef/cefrium_sdk/check-patch-format.sh
# Exit:   0 = all patches are -p0 / no-prefix; 1 = at least one is prefixed.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"          # chromium/src
PATCHDIR="$SRC/cef/patch/patches"
[ -d "$PATCHDIR" ] || { echo "FAIL: no patch dir at $PATCHDIR"; exit 1; }

bad=0
while IFS= read -r p; do
  # A prefixed patch has '--- a/' / '+++ b/' header lines, or a
  # 'diff --git a/... b/...' line. Bare-path patches have none of these.
  if grep -qE '^(--- a/|\+\+\+ b/|diff --git a/[^ ]+ b/)' "$p"; then
    echo "  PREFIXED (must be -p0 / no-prefix): ${p#$SRC/}"
    bad=$((bad+1))
  fi
done < <(find "$PATCHDIR" -name '*.patch' | sort)

if [ "$bad" -gt 0 ]; then
  echo "FAIL: $bad patch(es) use git's a/b prefix. Regenerate with"
  echo "      'git diff --no-prefix'. See cef/PATCHING-CEFRIUM.md."
  exit 1
fi
echo "All patches are -p0 / no-prefix format."
