#!/bin/bash
# release-web-verify.sh -- is cefrium.com actually consistent with <version>?
#
# WHY THIS EXISTS: updating a page and verifying it updated are different
# claims, and we have already shipped the gap between them -- the published
# Javadoc sat at 0.9.2 while 0.9.4 was the release, and nobody noticed because
# the generator had "run". Anchored regex updates fail soft by design (an
# anchor stops matching when prose is reworded), which is right for the UPDATE
# and useless unless something then checks the result.
#
# It verifies the POST-CONDITIONS, not the steps:
#   1. every page that carries a version carries THIS one;
#   2. no page still advertises the superseded version as current;
#   3. the API docs were regenerated for this version (both Java and Kotlin);
#   4. every locally-referenced asset exists -- this is the only deterministic
#      form of "the icon and the Cefrium graphic are not broken";
#   5. the download links in /releases resolve (HEAD 200).
#
# Usage:  release-web-verify.sh <version> [prev-version] [--no-net]
# Exit:   0 = consistent; 1 = at least one check failed (all are reported).
set -euo pipefail

NEW="${1:?usage: release-web-verify.sh <version> [prev-version] [--no-net]}"
PREV="${2:-}"
NONET=0
for a in "$@"; do [ "$a" = "--no-net" ] && NONET=1; done
[ "${PREV:-}" = "--no-net" ] && PREV=""

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WEB="${CEFRIUM_WEB:-$CEF_DIR/../../../cefrium-web}"
[ -d "$WEB" ] || { echo "ERROR: no web tree at $WEB" >&2; exit 1; }
cd "$WEB"

FAIL=0
ok()   { echo "  OK    $*"; }
bad()  { echo "  FAIL  $*"; FAIL=1; }

echo "Verifying cefrium.com against $NEW${PREV:+ (superseding $PREV)}"

# ---- 1. pages that must show the new version ----------------------------
echo
echo "version coverage"
check_has() { # file, label, expected-count-at-least
  local f="$1" label="$2" min="${3:-1}"
  [ -f "$f" ] || { bad "$label: $f missing"; return; }
  local n; n="$(grep -cF "$NEW" "$f" || true)"
  [ "$n" -ge "$min" ] && ok "$label ($n occurrence(s))" \
                      || bad "$label: expected >= $min occurrence(s) of $NEW, found $n"
}
check_has quickstart/index.html "quickstart coordinates" 4
check_has upstream/index.html   "upstream page" 1

# The avatar badge states the Chromium MAJOR. It must match the engine this
# release actually ships, or the most visible number on the site is a lie --
# it read 154 while 156 was published.
ENGINE="$(grep -oE 'CHROMIUM_VERSION = "[^"]*"' \
  "$SCRIPT_DIR/../cefrium_sdk/java/src/com/cefrium/Cefrium.java" 2>/dev/null | cut -d'"' -f2)"
MAJOR="${ENGINE%%.*}"
if [ -z "$MAJOR" ]; then
  bad "could not read CHROMIUM_VERSION to check the avatar badge"
elif grep -qE "font-size=\"72\"[^>]*>$MAJOR</text>" avatar.svg 2>/dev/null; then
  ok "avatar badge says Chromium $MAJOR"
else
  bad "avatar badge does not say Chromium $MAJOR (engine is $ENGINE)"
fi

# "Does the string appear somewhere" is a vacuous test on a page that lists
# every past version. Assert the two things that actually matter: this release
# is the TOP row, and the recommendation table points at it. The table is the
# one line on the site that tells an adopter what to depend on, and after
# 0.10.0 shipped it still said 0.9.4.
TOP="$(grep -oE 'id="v[0-9.]+"' releases/index.html | head -1 | tr -d '"' | sed 's/id=v//')"
[ "$TOP" = "$NEW" ] && ok "releases: newest row is $NEW" \
                    || bad "releases: newest row is ${TOP:-none}, expected $NEW"
if grep -qE "<td><strong>$NEW</strong></td>" releases/index.html; then
  ok "releases: recommendation table points at $NEW"
else
  bad "releases: the 'Which version, and where' table does not recommend $NEW"
fi

# ---- 2. nothing still presents the OLD version as current ---------------
# The /releases page legitimately keeps every past version in its history, so
# it is excluded; everywhere else a leftover PREV is a stale page.
if [ -n "$PREV" ]; then
  echo
  echo "no stale '$PREV' outside the releases history"
  STALE="$(grep -rlF "$PREV" --include=*.html . 2>/dev/null \
            | grep -vE "^\./releases/|^\./docs/" || true)"
  if [ -z "$STALE" ]; then
    ok "no page still advertises $PREV"
  else
    for f in $STALE; do bad "still mentions $PREV: $f"; done
  fi
fi

# ---- 3. the API docs were regenerated -----------------------------------
echo
echo "API docs"
if [ -d docs ]; then
  if grep -rqF "$NEW" docs/index.html 2>/dev/null || grep -rqlF "$NEW" docs/*.html 2>/dev/null; then
    ok "Java docs carry $NEW"
  else
    bad "Java docs do not mention $NEW -- regen-docs.sh did not run, or ran and failed silently"
  fi
  if [ -n "$PREV" ] && grep -rqF "Cefrium $PREV" docs 2>/dev/null; then
    bad "Java docs still carry the header 'Cefrium $PREV'"
  fi
else
  bad "no docs/ directory"
fi
if [ -d docs/compose ]; then
  COUNT="$(find docs/compose -name '*.html' | wc -l)"
  [ "$COUNT" -gt 10 ] && ok "Compose docs present ($COUNT pages)" \
                      || bad "Compose docs look empty ($COUNT pages)"
else
  bad "no docs/compose directory"
fi

# ---- 4. every locally-referenced asset exists ---------------------------
# A missing favicon.svg or avatar.svg is exactly "the icon and the graphic are
# broken on the site", and it is invisible in the HTML diff.
echo
echo "asset integrity (icon, graphic, css, images)"
MISSING=0
while IFS= read -r line; do
  f="${line#*|}"; src="${line%%|*}"
  case "$f" in
    http*|//*|data:*|\#*|mailto:*) continue ;;
  esac
  rel="${f%%\#*}"; rel="${rel%%\?*}"
  [ -z "$rel" ] && continue
  case "$rel" in
    /*) disk=".$rel" ;;
    *)  disk="$(dirname "$src")/$rel" ;;
  esac
  if [ ! -e "$disk" ]; then
    bad "missing asset $rel (referenced by $src)"
    MISSING=$((MISSING+1))
  fi
done < <(grep -rhoE '(src|href)="[^"]+\.(png|svg|jpg|jpeg|webp|ico|css|js)"' \
           --include=*.html . 2>/dev/null \
         | sed 's/^[a-z]*="//;s/"$//' \
         | sort -u \
         | while read -r a; do
             s="$(grep -rlF "$a" --include=*.html . 2>/dev/null | head -1)"
             echo "${s:-.}|$a"
           done)
[ "$MISSING" = "0" ] && ok "every referenced local asset exists"

# ---- 5. the download links resolve --------------------------------------
if [ "$NONET" = "0" ]; then
  echo
  echo "download links in /releases"
  URLS="$(grep -oE 'https://[^"]+(\.aar|\.apk|\.jar|SHA256SUMS[^"]*)' releases/index.html 2>/dev/null \
            | grep -F "$NEW" | sort -u | head -8 || true)"
  if [ -z "$URLS" ]; then
    echo "  --    no $NEW download URLs on the releases page (nothing to check)"
  else
    for u in $URLS; do
      code="$(curl -s -o /dev/null -w "%{http_code}" -L --max-time 25 -r 0-0 "$u" || echo 000)"
      case "$code" in
        200|206) ok "$code  $u" ;;
        *)       bad "$code  $u" ;;
      esac
    done
  fi
else
  echo
  echo "  (--no-net: skipped the download-link check)"
fi

# ---- 6. the LIVE site, not just this disk -------------------------------
# Everything above reads the local checkout. That is not what an adopter sees:
# the pages still have to be committed, pushed and served. This script once
# reported "consistent" while cefrium.com was still serving the previous
# release, which is the only failure mode that actually reaches a user.
if [ "$NONET" = "0" ]; then
  echo
  echo "live site (cefrium.com)"
  CB="$(date +%s)"
  live_badge="$(curl -s --max-time 25 "https://cefrium.com/avatar.svg?cb=$CB" \
                | grep -oE 'font-size="72"[^>]*>[0-9]+</text>' | grep -oE '>[0-9]+<' | tr -d '><')"
  [ "$live_badge" = "$MAJOR" ] && ok "live avatar badge says Chromium $MAJOR" \
                               || bad "live avatar badge says ${live_badge:-nothing}, expected $MAJOR"
  for page in releases quickstart; do
    body="$(curl -s --max-time 25 "https://cefrium.com/$page/?cb=$CB" || true)"
    if [ -z "$body" ]; then
      bad "could not fetch https://cefrium.com/$page/"
    elif printf '%s' "$body" | grep -qF "$NEW"; then
      ok "https://cefrium.com/$page/ serves $NEW"
    else
      bad "https://cefrium.com/$page/ does NOT serve $NEW yet (pushed? Pages rebuilt?)"
    fi
  done
  live_top="$(curl -s --max-time 25 "https://cefrium.com/releases/?cb=$CB" \
              | grep -oE 'id="v[0-9.]+"' | head -1 | tr -d '"' | sed 's/id=v//')"
  [ "$live_top" = "$NEW" ] && ok "live releases: newest row is $NEW" \
                           || bad "live releases: newest row is ${live_top:-none}, expected $NEW"
fi

echo
if [ "$FAIL" = "0" ]; then
  echo "cefrium.com is consistent with $NEW."
else
  echo "cefrium.com is NOT consistent with $NEW -- see the FAIL lines above."
fi
exit "$FAIL"
