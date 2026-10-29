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

# avatar.png is the raster twin used where an SVG is not accepted, and no page
# references it -- so the asset check below cannot see it and it would rot
# unwatched. release-web-update.sh renders it from the SVG; assert that it did,
# by rendering again and comparing pixels.
if [ ! -f avatar.png ]; then
  bad "avatar.png is missing (release-web-update.sh renders it from avatar.svg)"
elif ! command -v rsvg-convert >/dev/null 2>&1; then
  bad "rsvg-convert not found, so avatar.png cannot be checked against avatar.svg"
else
  _png="$(mktemp --suffix=.png)"
  rsvg-convert -w 512 -h 512 -o "$_png" avatar.svg 2>/dev/null
  if cmp -s "$_png" avatar.png; then
    ok "avatar.png matches a fresh render of avatar.svg"
  else
    bad "avatar.png differs from avatar.svg -- re-run release-web-update.sh"
  fi
  rm -f "$_png"
fi

# The /upstream "divergence" panel. Four numbers an evaluator checks in a minute,
# and all four were stale (engine 154 and 33 patches while 156/34 shipped) because
# nothing derived them. release-web-update.sh derives them now; this asserts it ran.
if [ -f upstream/index.html ]; then
  WANT_P=$(find "$SCRIPT_DIR/../patch/patches/android" -maxdepth 1 -name '*.patch' | wc -l | tr -d ' ')
  GOT_E=$(grep -oE '<div class="n">[^<]*</div><div class="k">Chromium tracked' upstream/index.html | grep -oE '>[0-9.]+<' | tr -d '><')
  GOT_P=$(grep -oE '<div class="n">[^<]*</div><div class="k">patches carried' upstream/index.html | grep -oE '>[0-9]+<' | tr -d '><')
  [ "$GOT_E" = "$ENGINE" ] && ok "upstream panel: engine $ENGINE" \
                           || bad "upstream panel says engine ${GOT_E:-none}, expected $ENGINE"
  [ "$GOT_P" = "$WANT_P" ] && ok "upstream panel: $WANT_P patches" \
                           || bad "upstream panel says ${GOT_P:-none} patches, expected $WANT_P"

  # The panel was not the only hand-maintained figure on that page: the count
  # appears twice in prose and the per-area breakdown has to add up to the
  # files-touched number. An evaluator reading two different counts on one page
  # learns not to trust either.
  WANT_F=$("$SCRIPT_DIR/patch-area-breakdown.sh" --files)
  GOT_F=$(grep -oE '<div class="n">[^<]*</div><div class="k">Chromium files touched' upstream/index.html | grep -oE '>[0-9]+<' | tr -d '><')
  [ "$GOT_F" = "$WANT_F" ] && ok "upstream panel: $WANT_F files touched" \
                           || bad "upstream panel says ${GOT_F:-none} files touched, expected $WANT_F"
  grep -qF "the $WANT_P above are the ones Cefrium adds" upstream/index.html \
    && ok "upstream prose: the note says $WANT_P" \
    || bad "upstream prose: the note does not say $WANT_P patches"
  grep -qF "Most of the $WANT_P patches are therefore not features" upstream/index.html \
    && ok "upstream prose: the guards paragraph says $WANT_P" \
    || bad "upstream prose: the guards paragraph does not say $WANT_P patches"
  WANT_AREAS=$("$SCRIPT_DIR/patch-area-breakdown.sh")
  grep -qF "$WANT_AREAS" upstream/index.html \
    && ok "upstream prose: the per-area breakdown matches the patch set" \
    || bad "upstream prose: per-area breakdown is stale, expected: $WANT_AREAS"
fi

# Pages that state the engine Cefrium ships RIGHT NOW must state this one.
# This is an explicit list, not a sweep, because most engine numbers on the site
# are historical and correct: /benchmarks reports what was measured on 0.5.2
# (Chromium 149), /upstream says which Chromium milestone absorbed a patch, and
# /releases names the engine of every past release. Bumping any of those would
# turn a true statement into a false one. A sweep flagged all three on its first
# run -- and a gate that reports known non-problems is one people switch off.
# Add a page here when it makes a live claim about the current engine.
ENGINE_CLAIM_PAGES="modern-web/index.html"
if [ -n "$MAJOR" ]; then
  for f in $ENGINE_CLAIM_PAGES; do
    [ -f "$f" ] || { bad "engine claim: $f missing"; continue; }
    WRONG="$(grep -ohE "Chromium (<b>)?1[0-9][0-9]\b" "$f" | grep -oE '1[0-9][0-9]' \
             | sort -u | grep -v "^$MAJOR$" | tr '\n' ' ' || true)"
    [ -z "$WRONG" ] && ok "$f claims Chromium $MAJOR" \
                    || bad "$f still claims Chromium $WRONG(engine is $MAJOR)"
  done
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
# The page keeps the newest five; the rest of the history is the changelog.
N="$(grep -c '<details class="rel"' releases/index.html || true)"
[ "$N" -le 5 ] && ok "releases: $N entries (history in the changelog)" \
               || bad "releases: $N entries, trim to 5 (trim-releases-page.py)"
grep -qF 'CHANGELOG-CEFRIUM.md' releases/index.html \
  && ok "releases: links the changelog" || bad "releases: no link to the changelog"

# ---- 2. nothing still presents the OLD version as current ---------------
# The /releases page legitimately keeps every past version in its history, so
# it is excluded; everywhere else a leftover PREV is a stale page.
if [ -n "$PREV" ]; then
  echo
  echo "no stale '$PREV' outside the releases history"
  # A guide may date a feature ("since 0.14.0", "Before 0.14.0 the SDK...",
  # "Downloads (0.14.0)", "0.14.0 or later"): that stays true after the bump,
  # so those mentions are not stale. "This guide covers 0.14.0." is.
  P="$(printf %s "$PREV" | sed 's/\./\\./g')"
  STALE="$(grep -rF "$PREV" --include=*.html . 2>/dev/null \
            | grep -vE "^\./releases/|^\./docs/" \
            | grep -viE "(since|before|from|until|start of) $P|\($P\)|$P or later" \
            | cut -d: -f1 | sort -u || true)"
  if [ -z "$STALE" ]; then
    ok "no page still advertises $PREV"
  else
    for f in $STALE; do bad "still mentions $PREV: $f"; done
  fi
fi

# ---- 2b. the adopting guide's figures are this release's AAR -------------
AAR="$CEF_DIR/../out/release/cefrium-sdk.aar"
echo
if [ -f "$AAR" ]; then
  "$SCRIPT_DIR/update-web-aar-figures.py" "$NEW" "$AAR" --check \
    && ok "adopting guide: figures measured on the $NEW AAR" \
    || bad "adopting guide: figures do not match the $NEW AAR"
else
  bad "adopting guide: $AAR missing, cannot check its figures"
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
  # Pages serves with cache-control max-age=600 and more than one edge, so for
  # the first minutes after a push two fetches of the SAME url can disagree.
  # Retry before calling it a failure, and name propagation as the likely
  # cause -- a gate that cries "the site is stale" 30 seconds after a correct
  # push trains you to ignore it.
  live_get() { # url-path, needle -> 0 if found within the retries
    local path="$1" needle="$2" i body
    for i in 1 2 3 4 5 6; do
      body="$(curl -s --max-time 25 -H 'Cache-Control: no-cache' -H 'Pragma: no-cache' \
              "https://cefrium.com/$path?cb=$(date +%s)-$i" || true)"
      # A shell match, NOT `printf ... | grep -q`. Under `set -o pipefail` that
      # pipeline reports FAILURE on a successful match of a large page: grep -q
      # exits the instant it matches, printf then takes SIGPIPE, and pipefail
      # propagates printf's 141. It only bites above the pipe buffer, so it
      # passed on /quickstart (small) and reported /releases (85 KB) as stale
      # for as long as this check existed -- a gate crying wolf about a page
      # that was correct all along.
      [[ "$body" == *"$needle"* ]] && return 0
      sleep 10
    done
    return 1
  }

  # Was the local tree actually pushed? That is what separates "the deploy has
  # not propagated yet" (cache max-age is 600s, and edges disagree meanwhile --
  # not a release defect) from "you never pushed" (which is one). Only the
  # second fails the gate; treating propagation lag as failure is how a gate
  # earns a reputation for crying wolf.
  UNPUSHED="$(git -C "$WEB" rev-list --count '@{u}..HEAD' 2>/dev/null || echo 0)"
  live_note() { # message
    if [ "${UNPUSHED:-0}" -gt 0 ]; then
      bad "$1 -- and the web repo has $UNPUSHED unpushed commit(s): push it"
    else
      echo "  WARN  $1 -- local is correct and pushed, so this is Pages"
      echo "        propagation (cache max-age 600). Re-check in a few minutes."
    fi
  }
  CB="$(date +%s)"
  if live_get "avatar.svg" ">$MAJOR</text>"; then
    ok "live avatar badge says Chromium $MAJOR"
  else
    live_note "live avatar badge is not Chromium $MAJOR yet"
  fi
  for page in releases quickstart; do
    if live_get "$page/" "$NEW"; then
      ok "https://cefrium.com/$page/ serves $NEW"
    else
      live_note "https://cefrium.com/$page/ does not serve $NEW yet"
    fi
  done
  if live_get "releases/" "id=\"v$NEW\""; then
    ok "live releases: $NEW has a row"
  else
    live_note "live releases has no row for $NEW yet"
  fi
fi

echo
echo "shared top bar and footer"
if "$SCRIPT_DIR/site-chrome.py" . --check; then ok "every content page"; else bad "pages differ"; fi

echo
if [ "$FAIL" = "0" ]; then
  echo "cefrium.com is consistent with $NEW."
else
  echo "cefrium.com is NOT consistent with $NEW -- see the FAIL lines above."
fi
exit "$FAIL"
