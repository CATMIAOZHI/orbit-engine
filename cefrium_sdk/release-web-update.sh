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
  [ -n "$NOTES" ] || NOTES="New release."
  echo "  notes: derived from CHANGELOG-CEFRIUM.md [$NEW] ($(printf %s "$NOTES" | wc -c) chars)"
else
  NOTES="New release."
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
    echo "  WARN: $label: anchor did not match — file restored, edit manually."
    mv "$file.bak" "$file"
  fi
}

echo "Web update: $PREV -> $NEW (CEF $BRANCH, Chromium $CHROMIUM, $DATE)"

# 1. Quickstart — bump the cefrium plugin + dependency coordinates.
apply "$QS" "s{(com\\.cefrium['\"]\\)? version ['\"])$PREV(['\"])}{\${1}$NEW\${2}}g; s{(com\\.cefrium:cefrium-(?:sdk|compose)[^:\"]*:)$PREV}{\${1}$NEW}g" \
  "$NEW" "quickstart version"

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
  echo "  releases: v$NEW entry already present (re-cut) — skipping insert"
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
    echo "  WARN: releases: insert anchor did not match — file restored, edit manually."; mv "$REL.bak" "$REL"
  fi
fi

echo "Done. Review: git -C $WEB diff -- quickstart/index.html releases/index.html"
