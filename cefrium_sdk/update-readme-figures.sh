#!/bin/bash
# Derive every figure cef/README.md states about the port, and rewrite the README
# so it states them. Called by the release (cut-release.sh, phase `stats`) and by
# the engine bump (engine-bump.sh, phase `pin`). Nothing here is hand-maintained.
#
# Usage:
#   cef/cefrium_sdk/update-readme-figures.sh           # rewrite the README
#   cef/cefrium_sdk/update-readme-figures.sh --check    # assert only, exit 1 if wrong (CI)
#
# Two sources, both already in the tree:
#   patch count = the files in patch/patches/android/
#   engine      = Cefrium.CHROMIUM_VERSION, which is also what
#                 release-web-update.sh derives the site from, so the README
#                 cannot disagree with cefrium.com about the engine.
#
# SCOPE: README.md, figures only. The SDK version strings in the same file
# (Gradle coordinates, download URLs) belong to release-bump-docs.sh and the
# anchors below do not touch them. The site's figures belong to
# release-web-update.sh, asserted by release-web-verify.sh. This script used to
# rewrite the cefrium.com landing page too; that copy was dropped in a redesign
# and the landing half then failed for months without anyone noticing.
#
# EVERY figure is declared in the table below, and each one is checked twice:
# the anchor must still match somewhere, and EVERY occurrence must carry the
# derived value. A reworded sentence FAILS this script. It must, because the
# quiet skip is the defect the file exists to stop: the count sentence had an
# owner, the four "N Android-specific" sentences had none, so the README said 21
# while the site said 34 -- and the engine line, under the "Security updates"
# heading an evaluator reads, sat six Chromium milestones behind.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"                 # chromium/src/cef
PATCH_DIR="$CEF_ROOT/patch/patches/android"
README="$CEF_ROOT/README.md"
CEFJAVA="$CEF_ROOT/cefrium_sdk/java/src/com/cefrium/Cefrium.java"
COMPAT="$CEF_ROOT/CHROMIUM_BUILD_COMPATIBILITY.txt"

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

[ -f "$README" ]  || { echo "FAIL: README not found ($README)"; exit 1; }
[ -f "$CEFJAVA" ] || { echo "FAIL: Cefrium.java not found ($CEFJAVA)"; exit 1; }
[ -d "$PATCH_DIR" ] || { echo "FAIL: patch dir not found ($PATCH_DIR)"; exit 1; }

# ---- derive -----------------------------------------------------------------
# Count = the Android-port Chromium patches a consumer can audit.
count=$(find "$PATCH_DIR" -maxdepth 1 -name '*.patch' | wc -l | tr -d ' ')
[ "$count" -gt 0 ] || { echo "FAIL: no .patch files in $PATCH_DIR"; exit 1; }
# Changed lines = diff body +/- lines, excluding the ---/+++ file headers. Not
# stated in the README; printed because the release notes and /upstream cite it.
lines=$(cat "$PATCH_DIR"/*.patch | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' | wc -l | tr -d ' ')
approx=$(( (lines + 25) / 50 * 50 ))

ctag=$(grep -oE 'CHROMIUM_VERSION = "[^"]*"' "$CEFJAVA" | cut -d'"' -f2 || true)
case "$ctag" in
  [0-9]*.[0-9]*.[0-9]*.[0-9]*) ;;
  *) echo "FAIL: Cefrium.CHROMIUM_VERSION reads '$ctag', not a Chromium tag"; exit 1;;
esac
branch=$(echo "$ctag" | cut -d. -f3)       # CEF branch = Chromium build number
milestone=$(echo "$ctag" | cut -d. -f1)

# engine-bump.sh pins .gclient, CHROMIUM_BUILD_COMPATIBILITY.txt and Cefrium.java
# together. If the two committed ones disagree, the README would state a version
# the build does not use, so stop instead of writing either of them.
if [ -f "$COMPAT" ] && ! grep -qF "refs/tags/$ctag" "$COMPAT"; then
  echo "FAIL: Cefrium.CHROMIUM_VERSION ($ctag) disagrees with $(basename "$COMPAT"):"
  grep -oE 'refs/tags/[0-9.]+' "$COMPAT" | sed 's/^/        /'
  echo "      Re-run the engine pin; do not edit either file by hand."
  exit 1
fi

echo "derived: $count Android patches, $lines changed diff lines (cite as ~$approx),"
echo "         engine $ctag (Chromium $milestone, CEF branch $branch)"

# ---- the figures the README states ------------------------------------------
# fig <extended-regex matching the WHOLE figure> <what it must read> <where>
RE=(); WANT=(); WHERE=()
fig() { RE+=("$1"); WANT+=("$2"); WHERE+=("$3"); }

fig 'All ([0-9]+) Chromium patches'      "All $count Chromium patches"   'patch count, "About Cefrium"'
fig '([0-9]+) Android-specific'          "$count Android-specific"       'patch count, architecture + sources + tree + methodology'
fig 'currently `([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)`'         "currently \`$ctag\`"           'engine, "Sources" + "Building from source"'
fig 'Currently pinned to ([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)' "Currently pinned to $ctag"     'engine, "Security updates"'
fig 'Fork of CEF branch ([0-9]+), tracking Chromium ([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)' \
                                         "Fork of CEF branch $branch, tracking Chromium $ctag" \
                                                                         'engine + branch, "Relationship to upstream CEF"'
fig 'Chromium ([0-9]+) -> branch ([0-9]+)' \
                                         "Chromium $milestone -> branch $branch" \
                                                                         'branch-number rule, "Sources"'

# Print one line per figure. Returns non-zero if any is missing or stale.
# `|| true` on every grep: a no-match exits 1, and under pipefail that would
# abort the script instead of reporting the figure as the problem it is.
report() {
  local i n distinct bad=0
  for i in "${!RE[@]}"; do
    n=$( { grep -oE "${RE[$i]}" "$README" || true; } | wc -l | tr -d ' ')
    distinct=$( { grep -oE "${RE[$i]}" "$README" || true; } | sort -u | tr '\n' '|' | sed 's/|$//')
    if [ "$n" -eq 0 ]; then
      echo "  MISSING  ${WHERE[$i]}"
      echo "           nothing matches /${RE[$i]}/ -- the prose moved"
      bad=1
    elif [ "$distinct" != "${WANT[$i]}" ]; then
      echo "  STALE    ${WHERE[$i]}"
      echo "           reads '$distinct', must read '${WANT[$i]}'"
      bad=1
    else
      echo "  OK       ${WHERE[$i]} (${n}x)"
    fi
  done
  return $bad
}

if [ "$CHECK" -eq 1 ]; then
  if report; then echo "  README figures current"; exit 0; fi
  echo
  echo "Rewrite them: cef/cefrium_sdk/$(basename "$0")"
  echo "A MISSING figure needs the prose or the table fixed first -- a rewrite"
  echo "cannot find a sentence that is no longer there."
  exit 1
fi

# ---- rewrite ----------------------------------------------------------------
cp "$README" "$README.bak"                               # never sed -i (hard rule)
for i in "${!RE[@]}"; do
  # Regex and replacement go through the environment: both contain characters
  # (backticks, parens, $) that would be reinterpreted if pasted into the perl
  # program text. The replacement is a plain string, never re-evaluated.
  RE_I="${RE[$i]}" WANT_I="${WANT[$i]}" perl -0777 -i \
    -pe 'BEGIN{ $r = qr/$ENV{RE_I}/; $w = $ENV{WANT_I} } s/$r/$w/g' "$README"
done

if report; then
  if cmp -s "$README" "$README.bak"; then echo "  README: already current"
  else                                    echo "  README: UPDATED"; fi
  rm -f "$README.bak"                     # kept only when it is the thing you need
  exit 0
fi

echo
echo "FAIL: a README figure is still wrong after the rewrite."
echo "      Fix the fig() table in cef/cefrium_sdk/$(basename "$0") -- do not"
echo "      hand-edit the README, or the next release overwrites it."
echo "      The README before this run is at $README.bak"
exit 1
