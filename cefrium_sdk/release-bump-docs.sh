#!/bin/bash
# release-bump-docs.sh <new-version> <prev-version>
#
# Bump the cefrium version references in the COMMITTED cef/ consumer docs
# (QUICKSTART.md, README.md): Gradle coordinates, the plugin `version`, and the
# registry download URLs. release-web-update.sh already bumps the cefrium.com web
# quickstart; this covers the in-repo docs, which otherwise go stale (they sat at
# 0.3.1 through 0.4.0 and 0.4.1 because nothing bumped them automatically).
#
# Targeted: only cefrium coordinates are rewritten, so unrelated versions
# (androidx, gms, ...) are untouched. NEVER sed -i (CLAUDE.md) -- uses perl -0777.
#
# Usage:  cef/cefrium_sdk/release-bump-docs.sh <new-version> <prev-version>
# Exit:   0 = bumped (or already current); 1 = stale prev refs remain after bump.
set -euo pipefail
NEW="${1:?usage: release-bump-docs.sh <new-version> <prev-version>}"
PREV="${2:?need previous version}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF="$(cd "$SCRIPT_DIR/.." && pwd)"          # cef/

for f in "$CEF/QUICKSTART.md" "$CEF/README.md"; do
  [ -f "$f" ] || { echo "  skip (missing): ${f#"$CEF"/}"; continue; }
  perl -0777 -pi -e "
    # Gradle coordinate: com.cefrium:cefrium-{sdk,compose,gradle-plugin}:<ver>
    s{(com\\.cefrium:cefrium-(?:sdk|compose|gradle-plugin):)\\Q$PREV\\E}{\${1}$NEW}g;
    # Plugin block, both DSLs: id 'com.cefrium' version '<ver>' (Groovy) and
    # id(\"com.cefrium\") version \"<ver>\" (Kotlin, with parens).
    s{(id\\s*\\(?\\s*['\"]com\\.cefrium['\"]\\s*\\)?\\s+version\\s+['\"])\\Q$PREV\\E}{\${1}$NEW}g;
    # Registry download URL path: /com/cefrium/cefrium-{sdk,compose}/<ver>/
    s{(/com/cefrium/cefrium-(?:sdk|compose)/)\\Q$PREV\\E/}{\${1}$NEW/}g;
    # Download file name: cefrium-{sdk,compose}-<ver>.aar
    s{(cefrium-(?:sdk|compose)-)\\Q$PREV\\E(\\.aar)}{\${1}$NEW\${2}}g;
  " "$f"
  echo "  bumped cefrium refs $PREV -> $NEW in ${f#"$CEF"/}"
done

# Sanity: no cefrium coordinate/url should still carry the previous version.
stale="$(grep -rnE "com\.cefrium:cefrium-(sdk|compose|gradle-plugin):$PREV|cefrium-(sdk|compose)-$PREV\.aar|/cefrium-(sdk|compose)/$PREV/" \
  "$CEF/QUICKSTART.md" "$CEF/README.md" 2>/dev/null || true)"
if [ -n "$stale" ]; then
  echo "FAIL: stale $PREV cefrium refs remain after bump:"; echo "$stale" | sed 's/^/    /'; exit 1
fi
echo "Consumer docs (QUICKSTART.md, README.md) at cefrium $NEW."
