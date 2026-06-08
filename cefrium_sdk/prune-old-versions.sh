#!/bin/bash
# Enforce the latest-only retention policy: delete every Cefrium Maven version
# from the Codeberg registry EXCEPT the one to keep. Superseded versions are
# preserved as the git tag + the GPG-signed SHA256SUMS on their Codeberg release
# (not as registry binaries) — see /releases and RELEASING.md.
#
# Usage: cef/cefrium_sdk/prune-old-versions.sh <keep-version> [--yes]
#   Without --yes (or CEFRIUM_YES=1) it prints what it WOULD delete and stops.
set -euo pipefail

KEEP="${1:?usage: prune-old-versions.sh <keep-version> [--yes]}"
YES=0
[ "${2:-}" = "--yes" ] && YES=1
[ "${CEFRIUM_YES:-0}" = "1" ] && YES=1
TOKEN="$(cat "$HOME/.registry-token")"
API="https://codeberg.org/api/v1/packages/cefrium"

# All Maven coordinates the publish step creates.
COORDS="cefrium-sdk cefrium-sdk-libre cefrium-compose cefrium-gradle-plugin com.cefrium.gradle.plugin"

# Distinct published versions.
versions=$(curl -s "$API?type=maven&limit=100" -H "Authorization: token $TOKEN" \
            | grep -oE '"version":"[^"]*"' | cut -d'"' -f4 | sort -u)
to_prune=$(echo "$versions" | grep -vx "$KEEP" || true)

if [ -z "$to_prune" ]; then
  echo "Nothing to prune — registry already serves only versions == $KEEP."
  echo "Present: $(echo "$versions" | tr '\n' ' ')"
  exit 0
fi

echo "Keep:  $KEEP"
echo "Prune: $(echo "$to_prune" | tr '\n' ' ')"
echo "Coordinates per version: $COORDS"
if [ "$YES" -ne 1 ]; then
  echo
  echo "DRY RUN — destructive. Re-run with --yes (or CEFRIUM_YES=1) to delete."
  exit 0
fi

for v in $to_prune; do
  for art in $COORDS; do
    enc="com.cefrium%3A$art"
    code=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$API/maven/$enc/$v" \
            -H "Authorization: token $TOKEN")
    case "$code" in
      204) echo "  deleted $art $v" ;;
      404) ;;  # coordinate didn't exist for this version — fine
      *)   echo "  WARN: delete $art $v -> HTTP $code" ;;
    esac
  done
done

remaining=$(curl -s "$API?type=maven&limit=100" -H "Authorization: token $TOKEN" \
             | grep -oE '"version":"[^"]*"' | cut -d'"' -f4 | sort -u | tr '\n' ' ')
echo "Done. Registry now serves: $remaining"
