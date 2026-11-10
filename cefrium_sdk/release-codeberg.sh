#!/bin/bash
# Codeberg release for a published Cefrium version: tag, GPG-signed SHA256SUMS,
# release object, and assets. Idempotent -- re-running skips what already exists.
#
# Usage: cef/cefrium_sdk/release-codeberg.sh <version>
# The release carries what an SDK user needs: the signed SHA256SUMS, the
# single-file AARs (for apps that add the SDK as a file), SBOM and NOTICE.
# The browser apps ship through Play, not here.
# Inputs:
#   - Merged AARs in out/release/{cefrium-sdk,cefrium-sdk-libre}.aar + the compose AAR
#   - CEFRIUM_REGISTRY_TOKEN -> a file holding a repo-scope token
#   - GPG key on the hardware signing key (signs SHA256SUMS; prompts for PIN/touch)
set -euo pipefail

VERSION="${1:?usage: release-codeberg.sh <version>}"
[ -z "${2:-}" ] || echo ">>> ignoring $2: a release carries SDK artifacts only"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"               # chromium/src
CEF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"                  # chromium/src/cef
REL_DIR="$SRC_DIR/out/release"
COMPOSE_AAR="$CEF_DIR/cefrium_compose/build/outputs/aar/cefrium-compose-release.aar"
UNIFIEDPUSH_AAR="$CEF_DIR/cefrium_unifiedpush/build/outputs/aar/cefrium-unifiedpush-release.aar"
GPG_KEY="2A840ED00DE669F4219B8B3302A79E3B82151695"
# Read the engine out of the tree, never hardcode it: the release notes are the
# first place an adopter looks for what Chromium a version embeds.
ENGINE="$(grep -oE 'CHROMIUM_VERSION = "[0-9.]+"' "$SCRIPT_DIR/java/src/com/cefrium/Cefrium.java" | grep -oE '[0-9.]+')"
[ -n "$ENGINE" ] || { echo "ERROR: cannot read CHROMIUM_VERSION from Cefrium.java"; exit 2; }
API="https://codeberg.org/api/v1/repos/cefrium/cef-android"
TOKEN="$(cat "${CEFRIUM_REGISTRY_TOKEN:?set CEFRIUM_REGISTRY_TOKEN to the file holding your registry token (see your local setup notes)}")"
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
# cefrium-sdk-libre is absent on a single-variant (dev) cut -- include it only if built.
cd "$REL_DIR"
cp -f cefrium-sdk.aar "cefrium-sdk-$VERSION.aar"
SUM_FILES="cefrium-sdk-$VERSION.aar"
if [ -f cefrium-sdk-libre.aar ]; then
  cp -f cefrium-sdk-libre.aar "cefrium-sdk-libre-$VERSION.aar"
  SUM_FILES="$SUM_FILES cefrium-sdk-libre-$VERSION.aar"
fi
# The modules Maven serves, under their Maven names, so the signed SUMS attest
# the bytes an app resolves (verify-central-provenance.sh checks them).
if [ -f modular/coordinates.tsv ]; then
  while IFS=$'\t' read -r art packaging file desc deps; do
    [ "$file" = "-" ] && continue
    cp -f "modular/$file" "$art-$VERSION.aar"
    SUM_FILES="$SUM_FILES $art-$VERSION.aar"
  done < modular/coordinates.tsv
fi
[ -f "$COMPOSE_AAR" ] && { cp -f "$COMPOSE_AAR" "cefrium-compose-$VERSION.aar"; SUM_FILES="$SUM_FILES cefrium-compose-$VERSION.aar"; }
[ -f "$UNIFIEDPUSH_AAR" ] && { cp -f "$UNIFIEDPUSH_AAR" "cefrium-unifiedpush-$VERSION.aar"; SUM_FILES="$SUM_FILES cefrium-unifiedpush-$VERSION.aar"; }
# The supply-chain disclosure goes INSIDE the signed manifest. An SBOM that
# states which engine a release embeds is only worth as much as its integrity:
# unattested, anyone can serve a different one. (Covered from 0.9.5 on; the
# 0.9.4 assets predate this and are attested only by the release itself.)
for extra in "cefrium-sdk-$VERSION.spdx.json" "cefrium-sdk-$VERSION-NOTICE.txt"; do
  [ -f "$extra" ] && SUM_FILES="$SUM_FILES $extra"
done
SUMS="SHA256SUMS-$VERSION"
say "generating $SUMS"
sha256sum $SUM_FILES > "$SUMS"
say "GPG-signing $SUMS (hardware signing key may prompt for PIN/touch)"
gpg --batch --yes --armor --local-user "$GPG_KEY" \
    --detach-sign --output "$SUMS.asc" "$SUMS"
gpg --verify "$SUMS.asc" "$SUMS" 2>&1 | grep -i "Good signature" \
  || { echo "ERROR: signature verification failed"; exit 1; }

# 3. The signed bytes are the bytes the registry serves. From 0.19.0 the SDK
# coordinates are POM-only, so compare the modules; before, the single AARs.
if [ -f modular/coordinates.tsv ]; then
  CHECK=$(awk -F'\t' '$3 != "-" {print $1}' modular/coordinates.tsv)
else
  CHECK="cefrium-sdk $( [ -f cefrium-sdk-libre.aar ] && echo cefrium-sdk-libre )"
fi
for art in $CHECK; do
  reg=$(curl -sfL "https://codeberg.org/api/packages/cefrium/maven/com/cefrium/$art/$VERSION/$art-$VERSION.aar" | sha256sum | cut -d' ' -f1)
  loc=$(sha256sum "$art-$VERSION.aar" | cut -d' ' -f1)
  [ "$reg" = "$loc" ] && say "$art: signed == registry ($loc)" \
    || { echo "ERROR: $art signed bytes != registry"; exit 1; }
done

# 4. Create the release object (skip if it exists), capture its id.
say "ensuring release $TAG exists"
rid=$(curl -s "$API/releases/tags/$TAG" -K "$CURL_AUTH_CFG" \
        | grep -oE '"id":[0-9]+' | head -1 | cut -d: -f2 || true)
if [ -z "$rid" ]; then
  # The support line goes out with every release on purpose: the release (and
  # its RSS/Atom feed) is when adopters see what they get.
  body="Cefrium $VERSION (Chromium $ENGINE). Artifacts on https://maven.cefrium.com, mirrored on the Codeberg Maven registry (com.cefrium:cefrium-sdk:$VERSION and -libre). SHA256SUMS (GPG-signed) attached and covers every listed asset; the AAR hashes in it are byte-identical to the registry artifacts. SBOM and third-party NOTICE attached. https://cefrium.com/releases\\n\\nCefrium is funded by the people who use it: individuals through Liberapay (https://liberapay.com/cefrium/donate), and teams through support plans (https://cefrium.com/commercial/) with response times and a security-response commitment."
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
# The single-file AARs, for apps that add the SDK as a file. Maven serves the
# SDK as modules (core + one native per flavour and ABI), so this is where the
# whole AAR lives.
for fat in "cefrium-sdk-$VERSION.aar" "cefrium-sdk-libre-$VERSION.aar"; do
  [ -f "$REL_DIR/$fat" ] && upload_asset "$REL_DIR/$fat"
done
# Supply-chain disclosure. The SBOM names the embedded engine version (one
# package, no CPE -- see generate-sbom.sh for why), and the NOTICE lists every
# bundled third-party licence, which is the one real finding against us in
# Sonatype's policy view. Attached here rather than to the Maven coordinates:
# scanners auto-ingest Maven classifiers, and a versionless component list makes
# them invent findings.
for extra in "cefrium-sdk-$VERSION.spdx.json" "cefrium-sdk-$VERSION-NOTICE.txt"; do
  if [ -f "$REL_DIR/$extra" ]; then upload_asset "$REL_DIR/$extra"
  else say "skip $extra (not generated)"; fi
done
say "Codeberg release $TAG ready: https://codeberg.org/cefrium/cef-android/releases/tag/$TAG"
