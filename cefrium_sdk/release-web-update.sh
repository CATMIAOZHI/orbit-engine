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

# 2. Releases — insert the new release block (metadata row + full-width notes row)
# right after the "add a release" comment. Skip if a row for this version already
# exists (a same-version re-cut), so we don't insert a duplicate row.
if [ -f "$REL" ] && grep -qF "tag/v$NEW\">$NEW</a>" "$REL"; then
  echo "  releases new row: v$NEW already present (re-cut) — skipping insert"
else
  NEWROW="      <tr class=\"rel\">\n        <td class=\"ver\"><a href=\"https://codeberg.org/cefrium/cef-android/releases/tag/v$NEW\">$NEW</a></td>\n        <td class=\"mono\">$BRANCH</td>\n        <td class=\"mono\">$CHROMIUM</td>\n        <td class=\"date\">$DATE</td>\n        <td class=\"art\"><a href=\"https://codeberg.org/cefrium/-/packages/maven/com.cefrium:cefrium-sdk/$NEW\">com.cefrium:cefrium-sdk:$NEW</a></td>\n      </tr>\n      <tr class=\"notes\"><td colspan=\"5\">$NOTES</td></tr>"
  apply "$REL" "s{(for the notes\\. -->\\n)}{\${1}$NEWROW\\n}" \
    "tag/v$NEW" "releases new row"
fi

# 3. Mark the previous version superseded: turn its metadata-row artifact cell into
# "superseded" AND append the supersede note to its full-width notes row (the <tr
# class=\"notes\"> immediately below the metadata row). One dotall substitution.
apply "$REL" "s{(<tr class=\"rel\">\\s*<td class=\"ver\"><a href=\"[^\"]*tag/v$PREV\">$PREV</a></td>.*?)<td class=\"art\">.*?</td>(\\s*</tr>\\s*<tr class=\"notes\"><td colspan=\"5\">)(.*?)(</td></tr>)}{\${1}<td class=\"art gone\">superseded</td>\${2}\${3} Superseded by $NEW; registry artifact pruned and not retained as a binary — the source tag is the reference.\${4}}s" \
  "art gone" "releases supersede $PREV"

echo "Done. Review: git -C $WEB diff -- quickstart/index.html releases/index.html"
