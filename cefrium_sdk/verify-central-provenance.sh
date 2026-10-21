#!/bin/bash
# verify-central-provenance.sh <version> -- does Maven Central serve the bytes
# we signed?
#
# The signed SHA256SUMS on the Codeberg release is the statement of what a
# release contains. release-codeberg.sh checks the Codeberg registry against
# it; nothing checked Central, which is where most consumers resolve from.
# This was done by hand once (0.10.2, cefrium-sdk digest 6692767e...).
#
# It uses only what any consumer can fetch, never local build output:
#   1. SHA256SUMS-<version> and its .asc from the public Codeberg release;
#   2. the signature must be good AND made by the release key below;
#   3. every .aar listed is downloaded from repo1.maven.org and must match.
# Central can take a while to serve a fresh release, so a 404 is retried for up
# to CEFRIUM_CENTRAL_WAIT seconds (default 2700) before it counts as missing.
#
# Exit 0 only if every listed AAR matches. Needs curl, gpg with the release
# public key imported, sha256sum.

set -u
VERSION="${1:?usage: verify-central-provenance.sh <version>}"
RELEASE_KEY="2A840ED00DE669F4219B8B3302A79E3B82151695"
REL_URL="https://codeberg.org/cefrium/cef-android/releases/download/v$VERSION"
CENTRAL="https://repo1.maven.org/maven2/com/cefrium"
WAIT="${CEFRIUM_CENTRAL_WAIT:-2700}"

WORK="$(mktemp -d "${CEFRIUM_SCRATCH:-${TMPDIR:-/tmp}}/provenance.XXXXXX")" || exit 1
trap 'rm -rf "$WORK"' EXIT
SUMS="SHA256SUMS-$VERSION"

fetch() {  # url dest -> 0 on HTTP 200
  local code
  code=$(curl -sL -o "$2" -w '%{http_code}' "$1")
  [ "$code" = "200" ]
}

fetch "$REL_URL/$SUMS" "$WORK/$SUMS" \
  || { echo "FAIL: $SUMS not on the Codeberg release v$VERSION"; exit 1; }
fetch "$REL_URL/$SUMS.asc" "$WORK/$SUMS.asc" \
  || { echo "FAIL: $SUMS.asc not on the Codeberg release v$VERSION"; exit 1; }

# VALIDSIG's last field is the primary key fingerprint: the signature must be
# good and come from the release key, not from any key in the keyring.
status=$(gpg --batch --status-fd 1 --verify "$WORK/$SUMS.asc" "$WORK/$SUMS" 2>/dev/null)
if ! grep -q "^\[GNUPG:\] VALIDSIG .* $RELEASE_KEY\$" <<<"$status"; then
  echo "FAIL: $SUMS is not validly signed by the release key $RELEASE_KEY"
  exit 1
fi
echo "OK:   $SUMS signed by the release key"

aars=$(awk '/\.aar$/ {print $2}' "$WORK/$SUMS")
[ -n "$aars" ] || { echo "FAIL: $SUMS lists no .aar"; exit 1; }

failed=0
for f in $aars; do
  art="${f%-$VERSION.aar}"
  want=$(awk -v f="$f" '$2 == f {print $1}' "$WORK/$SUMS")
  url="$CENTRAL/$art/$VERSION/$f"
  waited=0
  until fetch "$url" "$WORK/$f"; do
    if [ "$waited" -ge "$WAIT" ]; then
      echo "FAIL: $f not served by Central after ${WAIT}s ($url)"
      failed=1
      continue 2
    fi
    sleep 60; waited=$((waited + 60))
  done
  got=$(sha256sum "$WORK/$f" | cut -d' ' -f1)
  if [ "$got" = "$want" ]; then
    echo "OK:   $f on Central == signed ($got)"
  else
    echo "FAIL: $f on Central is $got, the signed manifest says $want"
    failed=1
  fi
done
exit $failed
