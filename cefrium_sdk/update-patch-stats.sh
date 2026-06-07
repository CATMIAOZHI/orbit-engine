#!/bin/bash
# Recompute the Android Chromium patch count + changed-line total and update the
# figures cited in the "Fully auditable" copy on the landing page and the SDK
# README. Run this as part of cutting a release so those numbers never go stale
# (mirrors regenerate-icons.sh: derive from source, never hand-maintain).
#
# Usage:
#   cef/cefrium_sdk/update-patch-stats.sh           # update README + landing in place
#   cef/cefrium_sdk/update-patch-stats.sh --check   # report only; exit 1 if stale (CI)
#
# The landing lives in a sibling checkout of the pages repo; override its path
# with CEFRIUM_WEB=/path/to/cefrium-web if the layout differs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"                 # chromium/src/cef
PATCH_DIR="$CEF_ROOT/patch/patches/android"
README="$CEF_ROOT/README.md"
LANDING="${CEFRIUM_WEB:-$CEF_ROOT/../../../cefrium-web}/index.html"

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

# Count = the Android-port Chromium patches the consumer can audit.
count=$(find "$PATCH_DIR" -maxdepth 1 -name '*.patch' | wc -l | tr -d ' ')
# Changed lines = diff body +/- lines, excluding the ---/+++ file headers.
lines=$(cat "$PATCH_DIR"/*.patch | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' | wc -l | tr -d ' ')
# Round to the nearest 50 for the "~N lines" wording.
approx=$(( (lines + 25) / 50 * 50 ))

echo "Android patches: $count   changed diff lines: $lines (cite as ~$approx)"

# Patterns the docs use (keep these in sync with the prose if it is reworded):
#   landing: "<N> Chromium patches (~<M> lines of changes)"
#   README:  "All <N> Chromium patches are auditable"
landing_re='([0-9]+) Chromium patches \(~([0-9]+) lines of changes\)'
readme_re='All ([0-9]+) Chromium patches'

stale=0
report() { # file, perl-regex, current-extractor
  local file="$1"
  [ -f "$file" ] || { echo "  (skip, not found: $file)"; return; }
}

update_file() { # file, perl-substitution, label
  local file="$1" subst="$2" label="$3"
  [ -f "$file" ] || { echo "  WARN: $label not found ($file)"; return; }
  local before; before=$(cat "$file")
  cp "$file" "$file.bak"                                  # backup first (hard rule)
  perl -0777 -i -pe "$subst" "$file"
  if [ "$before" = "$(cat "$file")" ]; then
    echo "  $label: already current"
    rm -f "$file.bak"
  else
    echo "  $label: UPDATED (backup at $(basename "$file").bak)"
  fi
}

if [ "$CHECK" -eq 1 ]; then
  cur_landing=$(grep -oE "$landing_re" "$LANDING" 2>/dev/null | head -1 || true)
  cur_readme=$(grep -oE "$readme_re" "$README" 2>/dev/null | head -1 || true)
  want_landing="$count Chromium patches (~$approx lines of changes)"
  want_readme="All $count Chromium patches"
  [ "$cur_landing" = "$want_landing" ] || { echo "  STALE landing: '$cur_landing' != '$want_landing'"; stale=1; }
  [ "$cur_readme" = "$want_readme" ] || { echo "  STALE README: '$cur_readme' != '$want_readme'"; stale=1; }
  [ "$stale" -eq 0 ] && echo "  figures current" || exit 1
  exit 0
fi

update_file "$LANDING" \
  "s/$landing_re/$count Chromium patches (~$approx lines of changes)/g" \
  "landing"
update_file "$README" \
  "s/$readme_re/All $count Chromium patches/g" \
  "README"

# Verify the new figures are present (fail loud if a rename broke the anchor).
grep -qE "$count Chromium patches \(~$approx lines of changes\)" "$LANDING" 2>/dev/null \
  && echo "  verified landing -> $count / ~$approx" \
  || echo "  WARN: landing anchor not matched — prose reworded? update landing_re"
grep -qE "All $count Chromium patches" "$README" \
  && echo "  verified README -> $count" \
  || echo "  WARN: README anchor not matched — prose reworded? update readme_re"
