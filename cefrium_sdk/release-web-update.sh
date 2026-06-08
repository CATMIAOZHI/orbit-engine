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
NOTES="${4:-New release.}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WEB="${CEFRIUM_WEB:-$CEF_DIR/../../../cefrium-web}"
QS="$WEB/quickstart/index.html"
REL="$WEB/releases/index.html"

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

# 2. Releases — insert the new row right after the "add a release" comment.
NEWROW="      <tr>\n        <td class=\"ver\"><a href=\"https://codeberg.org/cefrium/cef-android/releases/tag/v$NEW\">$NEW</a></td>\n        <td class=\"mono\">$BRANCH</td>\n        <td class=\"mono\">$CHROMIUM</td>\n        <td class=\"date\">$DATE</td>\n        <td class=\"art\"><a href=\"https://codeberg.org/cefrium/-/packages/maven/com.cefrium:cefrium-sdk/$NEW\">com.cefrium:cefrium-sdk:$NEW</a></td>\n        <td class=\"notes\">$NOTES</td>\n      </tr>"
apply "$REL" "s{(one short note\\. -->\\n)}{\${1}$NEWROW\\n}" \
  "tag/v$NEW" "releases new row"

# 3. Mark the previous version superseded (drop its dead registry link + note).
apply "$REL" "s{(<td class=\"ver\"><a href=\"[^\"]*tag/v$PREV\">$PREV</a></td>.*?)<td class=\"art\">.*?</td>\\s*<td class=\"notes\">(.*?)</td>}{\${1}<td class=\"art gone\">superseded</td>\n        <td class=\"notes\">\${2} Superseded by $NEW; registry artifact pruned and not retained as a binary — the source tag is the reference.</td>}s" \
  "art gone" "releases supersede $PREV"

echo "Done. Review: git -C $WEB diff -- quickstart/index.html releases/index.html"
