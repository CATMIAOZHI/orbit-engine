#!/bin/bash
# Recompute the Android Chromium patch count + changed-line total and update the
# figure cited in the "Fully auditable" copy of the SDK README. Run this as part
# of cutting a release so the number never goes stale (mirrors
# regenerate-icons.sh: derive from source, never hand-maintain).
#
# Usage:
#   cef/cefrium_sdk/update-patch-stats.sh           # update the README in place
#   cef/cefrium_sdk/update-patch-stats.sh --check   # report only; exit 1 if stale (CI)
#
# SCOPE: the README only. This script used to rewrite the cefrium.com landing
# page too, and that copy was dropped in a redesign, so the landing half had
# been warning and failing --check ever since. The site's patch figures live on
# /upstream/ now, where release-web-update.sh derives the panel, both prose
# counts and the per-area breakdown, and release-web-verify.sh asserts them. One
# owner per page: two scripts editing one file is how the figures disagreed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"                 # chromium/src/cef
PATCH_DIR="$CEF_ROOT/patch/patches/android"
README="$CEF_ROOT/README.md"

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

# Count = the Android-port Chromium patches the consumer can audit.
count=$(find "$PATCH_DIR" -maxdepth 1 -name '*.patch' | wc -l | tr -d ' ')
# Changed lines = diff body +/- lines, excluding the ---/+++ file headers.
lines=$(cat "$PATCH_DIR"/*.patch | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' | wc -l | tr -d ' ')
# Round to the nearest 50 for the "~N lines" wording.
approx=$(( (lines + 25) / 50 * 50 ))

echo "Android patches: $count   changed diff lines: $lines (cite as ~$approx)"

# The pattern the README uses: "All <N> Chromium patches". Keep it in sync if
# the prose is reworded -- the verification below fails loud when it is not.
readme_re='All ([0-9]+) Chromium patches'
want_readme="All $count Chromium patches"

if [ "$CHECK" -eq 1 ]; then
  cur_readme=$(grep -oE "$readme_re" "$README" 2>/dev/null | head -1 || true)
  if [ "$cur_readme" = "$want_readme" ]; then echo "  figures current"; exit 0; fi
  echo "  STALE README: '$cur_readme' != '$want_readme'"
  exit 1
fi

[ -f "$README" ] || { echo "  WARN: README not found ($README)"; exit 0; }
before=$(cat "$README")
cp "$README" "$README.bak"                               # backup first (hard rule)
perl -0777 -i -pe "s/$readme_re/$want_readme/g" "$README"
if [ "$before" = "$(cat "$README")" ]; then
  echo "  README: already current"
else
  echo "  README: UPDATED"
fi

# Verify the new figure is present (fail loud if a rename broke the anchor),
# then drop the backup. The .bak exists for the no-sed hard rule, and it is
# wanted in exactly one case: the anchor did not match and the original is what
# you need back. Kept unconditionally it is untracked residue that shows up in
# every push for weeks, which is what README.md.bak did.
if grep -qF "$want_readme" "$README"; then
  echo "  verified README -> $count"
  rm -f "$README.bak"
else
  echo "  WARN: README anchor not matched -- prose reworded? update readme_re"
  echo "        original kept at $(basename "$README").bak"
fi
