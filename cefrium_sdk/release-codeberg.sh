#!/bin/bash
# Codeberg release for a published Cefrium version: tag, GPG-signed SHA256SUMS,
# release object, and assets. Idempotent — re-running skips what already exists.
#
# Usage: cef/cefrium_sdk/release-codeberg.sh <version> [flagship.apk]
# Inputs:
#   - Merged AARs in out/release/{cefrium-sdk,cefrium-sdk-libre}.aar + the compose AAR
#   - ~/.registry-token (repo scope)
#   - GPG key on the hardware signing key (signs SHA256SUMS; prompts for PIN/touch)
set -euo pipefail

VERSION="${1:?usage: release-codeberg.sh <version> [flagship.apk]}"
FLAGSHIP="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"               # chromium/src
CEF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"                  # chromium/src/cef
REL_DIR="$SRC_DIR/out/release"
COMPOSE_AAR="$CEF_DIR/cefrium_compose/build/outputs/aar/cefrium-compose-release.aar"
GPG_KEY="2A840ED00DE669F4219B8B3302A79E3B82151695"
API="https://codeberg.org/api/v1/repos/cefrium/cef-android"
TOKEN="$(cat "$HOME/.registry-token")"
# Keep the token OUT of argv (`ps`): pass the auth header via a 0600 curl config
# file (`curl -K`) instead of putting it on the command line. Removed on exit.
CURL_AUTH_CFG="$(mktemp "${TMPDIR:-/tmp}/cefrium-curl-auth.XXXXXX")"
chmod 600 "$CURL_AUTH_CFG"
printf 'header = "Authorization: token %s"\n' "$TOKEN" > "$CURL_AUTH_CFG"
trap 'rm -f "$CURL_AUTH_CFG"' EXIT
unset TOKEN
TAG="v$VERSION"

say() { echo ">>> $*"; }

# 1. Tag (annotated) + push, if not already on the remote.
cd "$CEF_DIR"
[ "$(git rev-parse --abbrev-ref HEAD)" = "android-port" ] || {
  echo "ERROR: cef repo not on android-port (on $(git rev-parse --abbrev-ref HEAD))"; exit 1; }
if git rev-parse "$TAG" >/dev/null 2>&1; then
  say "tag $TAG exists locally"
else
  say "creating tag $TAG"
  git -c user.name="Juan Manuel Méndez Rey" -c user.email="juan.mendezr@proton.me" \
    tag -a "$TAG" -m "Cefrium $VERSION"
fi
git remote -v | grep -q "cefrium/cef-android" || { echo "ERROR: codeberg remote not found"; exit 1; }
git push codeberg "$TAG" 2>&1 | grep -vi "already exists\|up-to-date" || true

# 2. Versioned AAR copies + SHA256SUMS + GPG signature.
cd "$REL_DIR"
cp -f cefrium-sdk.aar "cefrium-sdk-$VERSION.aar"
cp -f cefrium-sdk-libre.aar "cefrium-sdk-libre-$VERSION.aar"
[ -f "$COMPOSE_AAR" ] && cp -f "$COMPOSE_AAR" "cefrium-compose-$VERSION.aar"
SUMS="SHA256SUMS-$VERSION"
say "generating $SUMS"
sha256sum "cefrium-sdk-$VERSION.aar" "cefrium-sdk-libre-$VERSION.aar" \
  $( [ -f "cefrium-compose-$VERSION.aar" ] && echo "cefrium-compose-$VERSION.aar" ) > "$SUMS"
say "GPG-signing $SUMS (hardware signing key may prompt for PIN/touch)"
gpg --batch --yes --armor --local-user "$GPG_KEY" \
    --detach-sign --output "$SUMS.asc" "$SUMS"
gpg --verify "$SUMS.asc" "$SUMS" 2>&1 | grep -i "Good signature" \
  || { echo "ERROR: signature verification failed"; exit 1; }

# 3. Cross-check the archived AAR hashes against the live registry artifacts.
for art in cefrium-sdk cefrium-sdk-libre; do
  reg=$(curl -s "https://codeberg.org/api/packages/cefrium/maven/com/cefrium/$art/$VERSION/$art-$VERSION.aar" | sha256sum | cut -d' ' -f1)
  loc=$(sha256sum "$art-$VERSION.aar" | cut -d' ' -f1)
  [ "$reg" = "$loc" ] && say "$art: archived == registry ($loc)" \
    || { echo "ERROR: $art archived bytes != registry"; exit 1; }
done

# 4. Create the release object (skip if it exists), capture its id.
say "ensuring release $TAG exists"
rid=$(curl -s "$API/releases/tags/$TAG" -K "$CURL_AUTH_CFG" \
        | grep -oE '"id":[0-9]+' | head -1 | cut -d: -f2 || true)
if [ -z "$rid" ]; then
  body="Cefrium $VERSION. Artifacts on the Codeberg Maven registry (com.cefrium:cefrium-sdk:$VERSION and -libre). SHA256SUMS (GPG-signed) attached; the listed AAR hashes are byte-identical to the registry artifacts. https://cefrium.com/releases"
  rid=$(curl -s -X POST "$API/releases" -K "$CURL_AUTH_CFG" \
    -H "Content-Type: application/json" \
    -d "{\"tag_name\":\"$TAG\",\"name\":\"Cefrium $VERSION\",\"body\":\"$body\"}" \
    | grep -oE '"id":[0-9]+' | head -1 | cut -d: -f2)
fi
[ -n "$rid" ] || { echo "ERROR: could not create/find release $TAG"; exit 1; }
say "release id $rid"

# 5. Upload assets (skip any that already exist).
upload_asset() {
  local file="$1" name; name="$(basename "$file")"
  local existing
  existing=$(curl -s "$API/releases/$rid/assets" -K "$CURL_AUTH_CFG" \
              | grep -oE "\"name\":\"$name\"" | head -1 || true)
  if [ -n "$existing" ]; then say "asset $name already present"; return; fi
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API/releases/$rid/assets?name=$name" \
          -K "$CURL_AUTH_CFG" -F "attachment=@$file")
  [ "$code" = "201" ] && say "uploaded $name" || { echo "ERROR: upload $name -> HTTP $code"; exit 1; }
}
upload_asset "$REL_DIR/$SUMS"
upload_asset "$REL_DIR/$SUMS.asc"
if [ -n "$FLAGSHIP" ] && [ -f "$FLAGSHIP" ]; then
  cp -f "$FLAGSHIP" "$REL_DIR/cefrium-browser-$VERSION-debug.apk"
  upload_asset "$REL_DIR/cefrium-browser-$VERSION-debug.apk"
fi
say "Codeberg release $TAG ready: https://codeberg.org/cefrium/cef-android/releases/tag/$TAG"
