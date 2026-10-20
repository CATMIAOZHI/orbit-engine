#!/bin/bash
# Update the cefrium.com pages for a new release: insert the /releases row, bump
# the quickstart version, and mark the previous version superseded (registry
# pruned). Backup-first + anchored regex + verify; if an anchor no longer matches
# (prose reworded), it WARNS and leaves that file untouched rather than corrupt it.
#
# Usage: release-web-update.sh <new-version> <prev-version> [YYYY-MM-DD] [notes-html]
#   CEFRIUM_WEB=/path overrides the web repo (default: sibling of the source tree).
set -euo pipefail

NEW="${1:?usage: release-web-update.sh <new-version> <prev-version> [date] [notes]}"
PREV="${2:?need previous version}"
DATE="${3:-$(date +%F)}"
NOTES_ARG="${4:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WEB="${CEFRIUM_WEB:-$CEF_DIR/../../../cefrium-web}"
QS="$WEB/quickstart/index.html"
REL="$WEB/releases/index.html"

# Release notes: an explicit arg 4 wins; otherwise derive them from the
# CHANGELOG-CEFRIUM.md "## [<NEW>]" section so the /releases note is never the empty
# "New release." placeholder. Flattens the top-level bullets under each ### section
# into one HTML cell (skips the indented Solution:/Decision: sub-bullets and the
# Chromium version line), strips markdown backticks, and HTML-escapes & < >.
CHANGELOG="$CEF_DIR/CHANGELOG-CEFRIUM.md"
if [ -n "$NOTES_ARG" ]; then
  NOTES="$NOTES_ARG"
elif [ -f "$CHANGELOG" ]; then
  NOTES="$(awk -v ver="$NEW" '
    function flush(){ if(btext!=""){ printf "%s%s",(nbull++?"; ":""),btext; btext="" } }
    $0 ~ "^## \\[" ver "\\]" {inblk=1; next}
    inblk && /^## \[/ {exit}
    !inblk {next}
    /^### / {flush(); skip=0; lbl=$0; sub(/^### /,"",lbl); printf "%s%s: ",(started?". ":""),lbl; started=1; nbull=0; next}
    /^- / {flush(); skip=0; btext=$0; sub(/^- /,"",btext); next}
    /^  - / {flush(); skip=1; next}
    /^[[:space:]]*$/ {flush(); skip=0; next}
    /^  +[^ ]/ { if(!skip && btext!=""){c=$0; sub(/^ +/,"",c); btext=btext" "c} next }
    END {flush()}
  ' "$CHANGELOG" | sed 's/`//g; s/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g; s/\.\. /. /g; s/\.;/;/g')"
  if [ -n "$NOTES" ]; then
    echo "  notes: derived from CHANGELOG-CEFRIUM.md [$NEW] ($(printf %s "$NOTES" | wc -c) chars)"
  else
    # This used to WARN and fall back to "New release.", which means this step
    # could not fail: the sentence scrolls past in a long cut and the release
    # row ships with no description. Same shape as the `|| true` that let the
    # README sit six Chromium milestones behind. There IS an explicit escape
    # hatch -- pass the notes as arg 4 -- so refusing costs nothing.
    echo "ERROR: CHANGELOG-CEFRIUM.md has no '## [$NEW]' section." >&2
    echo "       The /releases note is derived from it, so there is nothing to" >&2
    echo "       publish. Write the [$NEW] entry, or pass the notes as arg 4." >&2
    exit 1
  fi
else
  echo "ERROR: CHANGELOG-CEFRIUM.md not found at $CHANGELOG." >&2
  echo "       Pass the release notes as arg 4 to publish without it." >&2
  exit 1
fi

# CEF branch + Chromium version from the SDK source of truth.
CHROMIUM=$(grep -oE 'CHROMIUM_VERSION = "[^"]*"' "$CEF_DIR/cefrium_sdk/java/src/com/cefrium/Cefrium.java" | cut -d'"' -f2)
BRANCH=$(echo "$CHROMIUM" | cut -d. -f3)

# Apply a perl substitution with backup + a post-condition check. If the file is
# unchanged AND the expected marker is absent, warn and restore.
apply() { # file, perl-expr, must-contain-after, label
  local file="$1" expr="$2" want="$3" label="$4"
  [ -f "$file" ] || { echo "  WARN: $label: $file not found"; return; }
  cp "$file" "$file.bak"
  perl -0777 -i -pe "$expr" "$file"
  if grep -qF "$want" "$file"; then
    if cmp -s "$file" "$file.bak"; then echo "  $label: already current"; else echo "  $label: updated"; fi
    rm -f "$file.bak"
  else
    echo "  WARN: $label: anchor did not match -- file restored, edit manually."
    mv "$file.bak" "$file"
  fi
}

echo "Web update: $PREV -> $NEW (CEF $BRANCH, Chromium $CHROMIUM, $DATE)"

# 1. Quickstart -- bump the cefrium plugin + dependency coordinates.
apply "$QS" "s{(com\\.cefrium['\"]\\)? version ['\"])[0-9]+\\.[0-9]+\\.[0-9]+(['\"])}{\${1}$NEW\${2}}g; s{(com\\.cefrium:cefrium-(?:sdk|compose)[^:\"]*:)[0-9]+\\.[0-9]+\\.[0-9]+}{\${1}$NEW}g" \
  "$NEW" "quickstart version"

# 1. The site avatar carries the Chromium MAJOR as a badge. It is the most
# visible version statement on the whole site and it was hand-edited, so it
# aged silently: the live badge still read 154 after 156 shipped. Derived from
# Cefrium.CHROMIUM_VERSION like everything else, so it cannot disagree with the
# engine the release actually carries.
MAJOR="$(echo "$CHROMIUM" | cut -d. -f1)"
apply "$WEB/avatar.svg" \
  "s{(font-size=\"72\"[^>]*>)[0-9]+(</text>)}{\${1}$MAJOR\${2}}" \
  ">$MAJOR<" "avatar badge (Chromium major)"

# 1b. The PNG twin of that avatar, for Codeberg's profile picture and anywhere
# an SVG is not accepted. It carries the same badge, so it is RENDERED from the
# SVG here rather than exported by hand: a hand-exported copy is a live figure
# with no owner, and it would go stale at the next engine bump in silence --
# which is the whole defect this script exists to close. Hard failure if the
# renderer is missing (apt install librsvg2-bin): a WARN here would ship the
# project's own face one engine behind.
if ! command -v rsvg-convert >/dev/null 2>&1; then
  echo "ERROR: rsvg-convert not found, so avatar.png cannot be rendered from" >&2
  echo "       avatar.svg. Install librsvg2-bin and re-run this step." >&2
  exit 1
fi
rsvg-convert -w 512 -h 512 -o "$WEB/avatar.png" "$WEB/avatar.svg" || {
  echo "ERROR: could not render $WEB/avatar.png from avatar.svg." >&2; exit 1; }
echo "  avatar.png: rendered from avatar.svg at 512x512"

# 1a. The "Which version, and where" table on /releases -- the one line on the
# whole site that tells an adopter what to depend on. No script touched it, so
# after 0.10.0 shipped it still recommended 0.9.4 and still explained a Central
# gap from months earlier. A stale row here is worse than a stale page: it is
# actively wrong advice.
apply "$REL" \
  "s{(<td><strong>)[0-9]+\\.[0-9]+\\.[0-9]+(</strong></td>\\s*\\n\\s*<td>Chromium )[0-9.]+(<br><span class=\"note\">CEF branch )[0-9]+}{\${1}$NEW\${2}$CHROMIUM\${3}$BRANCH}s" \
  "<strong>$NEW</strong>" "releases recommendation table"

# 1b. Upstream page -- it states which SDK the patch counts are for. No script
# touched it, so it silently aged: it still said 0.9.4 while 0.10.0 shipped.
apply "$WEB/upstream/index.html" \
  "s{(Counts for Cefrium SDK )[0-9]+\\.[0-9]+\\.[0-9]+}{\${1}$NEW}g" \
  "$NEW" "upstream page version"

# 1b-bis. The /upstream page's "divergence" panel: four hand-maintained numbers
# that an evaluator can check in one minute, and all four had gone stale -- it
# claimed engine 154.0.8037.98 and 33 patches while 156.0.8078.17 and 34 were
# published. update-doc-figures.py owns the repo docs' figures and nothing else,
# so nothing owned these. Derive all four from the patches themselves.
UP="$WEB/upstream/index.html"
if [ -f "$UP" ]; then
  APD="$CEF_DIR/patch/patches/android"
  P_COUNT=$(find "$APD" -maxdepth 1 -name '*.patch' | wc -l | tr -d ' ')
  P_FILES=$(grep -h '^--- ' "$APD"/*.patch | grep -v '/dev/null' \
            | sed 's/^--- //; s/\t.*//' | sort -u | wc -l | tr -d ' ')
  P_ADD=$(cat "$APD"/*.patch | grep -E '^\+' | grep -v '^+++' | wc -l | tr -d ' ')
  P_DEL=$(cat "$APD"/*.patch | grep -E '^-' | grep -v '^---' | wc -l | tr -d ' ')
  echo "  upstream panel: engine $CHROMIUM, $P_COUNT patches, $P_FILES files, +$P_ADD/-$P_DEL lines"
  apply "$UP" \
    "s{(<div class=\"n\">)[^<]*(</div><div class=\"k\">Chromium tracked)}{\${1}$CHROMIUM\${2}}" \
    ">$CHROMIUM<" "upstream panel: engine"
  apply "$UP" \
    "s{(<div class=\"n\">)[^<]*(</div><div class=\"k\">patches carried)}{\${1}$P_COUNT\${2}}" \
    ">$P_COUNT<" "upstream panel: patch count"
  apply "$UP" \
    "s{(<div class=\"n\">)[^<]*(</div><div class=\"k\">Chromium files touched)}{\${1}$P_FILES\${2}}" \
    ">$P_FILES<" "upstream panel: files touched"
  apply "$UP" \
    "s{(<div class=\"n\">)[^<]*(</div><div class=\"k\">lines changed)}{\${1}+$P_ADD / -$P_DEL\${2}}" \
    "+$P_ADD / -$P_DEL" "upstream panel: lines changed"

  # The same page states the count twice more in PROSE and lists a per-area file
  # breakdown, and all three were stale as well: "33 patches" twice, and
  # "components (11)" after that area had reached 12. Fixing only the panel
  # leaves an evaluator reading two different counts on one page, so derive
  # these from the same patches. The breakdown also has to add up to the
  # files-touched number in the panel, which is why one script generates it and
  # release-web-verify.sh asserts the published string.
  apply "$UP" \
    "s{(the )\\d+( above are the ones Cefrium adds)}{\${1}$P_COUNT\${2}}" \
    "the $P_COUNT above are the ones Cefrium adds" "upstream prose: count in the note"
  apply "$UP" \
    "s{(Most of the )\\d+( patches are therefore not features)}{\${1}$P_COUNT\${2}}" \
    "Most of the $P_COUNT patches are therefore not features" "upstream prose: count in the guards paragraph"
  AREAS="$("$SCRIPT_DIR/patch-area-breakdown.sh")"
  apply "$UP" \
    "s{(the areas touched are)\\s.*?(\\. Every patch is in the)}{\${1}\n  $AREAS\${2}}s" \
    "$AREAS" "upstream prose: per-area file breakdown"
fi

# 1c. The modern-web demo page names the Chromium major four times, in prose, in
# a parallax layer and in a view-transition label. Hand-maintained, so it sat at
# 154 with 156 published -- on the one page whose entire argument is "you ship a
# newer engine than the System WebView".
# Anchored on what makes each one an engine number -- the word "Chromium" or
# the layer div -- so no bare three-digit figure elsewhere on the page can be
# caught by it, and nothing has to name the OUTGOING major.
apply "$WEB/modern-web/index.html" \
  "s{(Chromium\\s*(?:<b>)?)1[0-9][0-9]\\b}{\${1}$MAJOR}g; s{(<div class=\"layer\">)1[0-9][0-9](</div>)}{\${1}$MAJOR\${2}}g" \
  "Chromium <b>$MAJOR</b>" "modern-web page (Chromium major)"

# 2-3. Releases page (native <details> accordion layout, redesigned 2026-06). Skip
# the whole insert on a same-version re-cut. Otherwise: demote the current latest
# entry (drop `open`, flip its Maven status to superseded), then insert the new
# entry built from the CHANGELOG [NEW] ### sections (one <div class="cat"> per
# section, one <li> per top-level bullet; backticks -> <code>, & < > escaped).
# apply() can't carry the multi-line block, so this runs inline with backup + an
# id="v$NEW" post-check, and restores on a missed anchor.
if [ ! -f "$REL" ]; then
  echo "  WARN: releases: $REL not found"
elif grep -qF "id=\"v$NEW\"" "$REL"; then
  echo "  releases: v$NEW entry already present (re-cut) -- skipping insert"
else
  CATS="$(awk -v ver="$NEW" '
    function esc(s){ gsub(/&/,"\\&amp;",s); gsub(/</,"\\&lt;",s); gsub(/>/,"\\&gt;",s); return s }
    function code(s,  out){ out=""
      while(match(s,/`[^`]*`/)){ out=out substr(s,1,RSTART-1) "<code>" substr(s,RSTART+1,RLENGTH-2) "</code>"; s=substr(s,RSTART+RLENGTH) }
      return out s }
    function strong(s,  out){ out=""
      while(match(s,/\*\*[^*]+\*\*/)){ out=out substr(s,1,RSTART-1) "<strong>" substr(s,RSTART+2,RLENGTH-4) "</strong>"; s=substr(s,RSTART+RLENGTH) }
      return out s }
    function flush(){ if(btext!=""){ printf "            <li>%s</li>\n", code(strong(esc(btext))); btext="" } }
    function closecat(){ if(incat){ flush(); print "          </ul>"; print "        </div>"; incat=0 } }
    $0 ~ "^## \\[" ver "\\]" {inblk=1; next}
    inblk && /^## \[/ {exit}
    !inblk {next}
    /^### / {closecat(); lbl=$0; sub(/^### /,"",lbl); printf "        <div class=\"cat\">\n          <span class=\"lbl\">%s</span>\n          <ul>\n", lbl; incat=1; next}
    /^- / {flush(); btext=$0; sub(/^- /,"",btext); next}
    /^  - / {flush(); next}
    /^[[:space:]]*$/ {flush(); next}
    /^  +[^ ]/ { if(btext!=""){c=$0; sub(/^ +/,"",c); btext=btext" "c} next }
    END {closecat()}
  ' "$CHANGELOG")"
  [ -n "$CATS" ] || CATS="        <div class=\"cat\"><span class=\"lbl\">Notes</span>
          <ul><li>$NOTES</li></ul>
        </div>"

  # Security chip: CVE IDs fixed in this release's engine, auto-extracted from the
  # CHANGELOG [NEW] section (same source of truth as the notes). Emerald "Fixes
  # CVE-..." chip in the summary; empty when the release fixes no CVE.
  CVES=$(awk -v v="$NEW" '$0 ~ "^## \\[" v "\\]"{f=1;next} f&&/^## \[/{exit} f' "$CHANGELOG" 2>/dev/null \
          | { grep -oE 'CVE-[0-9]{4}-[0-9]+' || true; } | sort -u)
  CVE_CHIP=""
  if [ -n "$CVES" ]; then
    cve_first=$(printf '%s\n' "$CVES" | head -1); cve_n=$(printf '%s\n' "$CVES" | grep -c .)
    cve_label="Fixes $cve_first"; [ "$cve_n" -gt 1 ] && cve_label="Fixes $cve_first +$((cve_n-1))"
    CVE_CHIP=$'\n        '"<span class=\"chip cve\" title=\"CVE(s) fixed in this release's Chromium engine: $(printf '%s' "$CVES" | tr '\n' ' ')\">$cve_label</span>"
    echo "  cve chip: $cve_label ($cve_n CVE(s))"
  fi

  NEWBLOCK="    <details class=\"rel\" id=\"v$NEW\" open>
      <summary>
        <span class=\"v\">$NEW</span>
        <span class=\"chip\">CEF $BRANCH</span>
        <span class=\"chip\">Chromium $CHROMIUM</span>
        <span class=\"chip\">$DATE</span>$CVE_CHIP
        <span class=\"status live\">Maven</span>
      </summary>
      <div class=\"body\">
$CATS
        <div class=\"relinks\">
          <a href=\"https://codeberg.org/cefrium/cef-android/releases/tag/v$NEW\">Source tag v$NEW</a>
          <span><code>com.cefrium:cefrium-sdk:$NEW</code></span>
        </div>
      </div>
    </details>"

  cp "$REL" "$REL.bak"
  # Demote the previous latest entry: drop `open`, and flip its "Maven" status to
  # "superseded" (the registry keeps latest-only). Both target v$PREV specifically.
  perl -0777 -i -pe "s{(<details class=\"rel\" id=\"v\\Q$PREV\\E\")\\s+open>}{\${1}>}" "$REL"
  perl -0777 -i -pe "s{(id=\"v\\Q$PREV\\E\".*?<span class=\"status) live\">Maven(</span>)}{\${1}\">superseded\${2}}s" "$REL"
  # Insert the new entry right after the rel-list container opens (env var + /e so
  # the multi-line block needs no escaping).
  NEWBLOCK="$NEWBLOCK" perl -0777 -i -pe 's{(<div class="rel-list">\n)}{$1 . "\n" . $ENV{NEWBLOCK} . "\n"}e' "$REL"
  if grep -qF "id=\"v$NEW\"" "$REL"; then
    echo "  releases: inserted v$NEW entry (prev v$PREV -> superseded)"; rm -f "$REL.bak"
  else
    echo "  WARN: releases: insert anchor did not match -- file restored, edit manually."; mv "$REL.bak" "$REL"
  fi
fi

echo "Done. Review: git -C $WEB diff -- quickstart/index.html releases/index.html"
