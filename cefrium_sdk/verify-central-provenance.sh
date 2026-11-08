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
#   3. every .aar listed is downloaded from repo1.maven.org and must match;
#   4. one engine everywhere (SC-rc-17): the POM property
#      cefrium.chromium.version of every artifact, the Chromium package in the
#      signed SPDX SBOM, Cefrium.CHROMIUM_VERSION in each SDK AAR's classes,
#      and the Chrome/<version> literal in each AAR's libcef.so.
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
# cefrium-sdk and cefrium-sdk-libre are POMs on Maven: their single-file AARs
# are release assets. Every other AAR is a module Central serves.
served_by() {
  case "$1" in
    cefrium-sdk|cefrium-sdk-libre) echo "$REL_URL/$2" ;;
    *) echo "$CENTRAL/$1/$VERSION/$2" ;;
  esac
}
for f in $aars; do
  art="${f%-$VERSION.aar}"
  want=$(awk -v f="$f" '$2 == f {print $1}' "$WORK/$SUMS")
  url="$(served_by "$art" "$f")"
  waited=0
  until fetch "$url" "$WORK/$f"; do
    if [ "$waited" -ge "$WAIT" ]; then
      echo "FAIL: $f not served after ${WAIT}s ($url)"
      failed=1
      continue 2
    fi
    sleep 60; waited=$((waited + 60))
  done
  got=$(sha256sum "$WORK/$f" | cut -d' ' -f1)
  if [ "$got" = "$want" ]; then
    echo "OK:   $f as served == signed ($got)"
  else
    echo "FAIL: $f as served is $got, the signed manifest says $want"
    failed=1
  fi
done
[ "$failed" = 0 ] || exit 1

# 4. One engine version everywhere.
engine=""
note_version() {  # what value -> fails on the first disagreement
  if [ -z "$2" ]; then
    echo "FAIL: $1 names no engine version"; failed=1
  elif [ -z "$engine" ]; then
    engine="$2"; echo "OK:   $1 = $2"
  elif [ "$2" = "$engine" ]; then
    echo "OK:   $1 = $2"
  else
    echo "FAIL: $1 = $2, but the first source said $engine"; failed=1
  fi
}
for f in $aars; do
  art="${f%-$VERSION.aar}"
  fetch "$CENTRAL/$art/$VERSION/$art-$VERSION.pom" "$WORK/$art.pom" \
    || { echo "FAIL: $art-$VERSION.pom not on Central"; failed=1; continue; }
  note_version "$art POM cefrium.chromium.version" \
    "$(sed -n 's:.*<cefrium.chromium.version>\([^<]*\)<.*:\1:p' "$WORK/$art.pom" | head -1)"
done
sbom="cefrium-sdk-$VERSION.spdx.json"
if fetch "$REL_URL/$sbom" "$WORK/$sbom"; then
  want=$(awk -v f="$sbom" '$2 == f {print $1}' "$WORK/$SUMS")
  got=$(sha256sum "$WORK/$sbom" | cut -d' ' -f1)
  if [ -n "$want" ] && [ "$got" = "$want" ]; then
    note_version "signed SBOM Chromium package" "$(python3 -I -c '
import json, sys
for p in json.load(open(sys.argv[1])).get("packages", []):
    if p.get("name", "").startswith("Chromium"):
        print(p.get("versionInfo", "")); break
' "$WORK/$sbom")"
  else
    echo "FAIL: $sbom is not covered by the signed $SUMS"; failed=1
  fi
else
  echo "FAIL: $sbom not on the Codeberg release"; failed=1
fi
for f in $aars; do
  # The core and the single-file AARs carry the Java constant; the native
  # modules and the single-file AARs carry libcef.so. Compose carries neither.
  ( cd "$WORK" && unzip -o -q "$f" classes.jar -d "x-$f" 2>/dev/null )
  if unzip -l "$WORK/x-$f/classes.jar" 2>/dev/null | grep -q "com/cefrium/Cefrium.class"; then
    const=$(unzip -p "$WORK/x-$f/classes.jar" com/cefrium/Cefrium.class 2>/dev/null \
            | grep -aoE '[0-9]{3}\.0\.[0-9]{4,}\.[0-9]+' | head -1)
    note_version "$f Cefrium.CHROMIUM_VERSION" "$const"
  fi
  for so in $(unzip -l "$WORK/$f" | awk '/libcef\.so$/ {print $4}'); do
    lit=$(unzip -p "$WORK/$f" "$so" | grep -aoE 'Chrome/[0-9]{3}\.0\.[0-9]{4,}\.[0-9]+' | head -1)
    note_version "$f $so User-Agent literal" "${lit#Chrome/}"
  done
done
exit $failed
