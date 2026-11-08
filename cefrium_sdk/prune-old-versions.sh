#!/bin/bash
# Enforce the latest-only retention policy: delete every Cefrium Maven version
# from the Codeberg registry EXCEPT the one to keep. Superseded versions are
# preserved as the git tag + the GPG-signed SHA256SUMS on their Codeberg release
# (not as registry binaries) -- see /releases and RELEASING-CEFRIUM.md.
#
# Usage: cef/cefrium_sdk/prune-old-versions.sh <keep-version> [--yes]
#   Without --yes (or CEFRIUM_YES=1) it prints what it WOULD delete and stops.
set -euo pipefail

KEEP="${1:?usage: prune-old-versions.sh <keep-version> [--yes]}"
YES=0
[ "${2:-}" = "--yes" ] && YES=1
[ "${CEFRIUM_YES:-0}" = "1" ] && YES=1
TOKEN="$(cat "${CEFRIUM_REGISTRY_TOKEN:?set CEFRIUM_REGISTRY_TOKEN to the file holding your registry token (see your local setup notes)}")"
# Keep the token OUT of argv (`ps`): pass the auth header via a 0600 curl config
# file (`curl -K`) instead of putting it on the command line. Removed on exit.
CURL_AUTH_CFG="$(mktemp "${TMPDIR:-/tmp}/cefrium-curl-auth.XXXXXX")"
chmod 600 "$CURL_AUTH_CFG"
printf 'header = "Authorization: token %s"\n' "$TOKEN" > "$CURL_AUTH_CFG"
trap 'rm -f "$CURL_AUTH_CFG"' EXIT
unset TOKEN
API="https://codeberg.org/api/v1/packages/cefrium"

# All Maven coordinates the publish step creates. This list is also the SCOPE of
# the retention policy: nothing outside it is ever considered for deletion.
COORDS="cefrium-sdk cefrium-sdk-libre cefrium-sdk-core cefrium-native-codecs-arm64-v8a cefrium-native-codecs-x86_64 cefrium-native-libre-arm64-v8a cefrium-native-libre-x86_64 cefrium-compose cefrium-unifiedpush cefrium-gradle-plugin com.cefrium.gradle.plugin"

# Distinct published versions OF THOSE COORDINATES.
#
# Enumerating every Maven version in the cefrium org and subtracting $KEEP (what
# this did before) conflates separate products that version independently: it
# listed com.cefrium:exam-lockdown 0.1.0 for deletion simply because the SDK had
# moved on to 0.9.4. Nothing was actually destroyed only because exam-lockdown is
# absent from COORDS, so the DELETE calls 404'd -- which also meant 0.1.0 showed
# up as "to prune" on every single cut and never went away. Scope the version
# list to the coordinates this policy owns, and other products stop being
# visible to it at all.
versions=$(curl -s "$API?type=maven&limit=100" -K "$CURL_AUTH_CFG" \
            | python3 -c '
import json, sys
want = set(sys.argv[1].split())
seen = set()
for p in json.load(sys.stdin):
    # Package names come back either bare ("cefrium-sdk") or group-qualified
    # ("com.cefrium:cefrium-sdk"); match on the artifact id either way.
    name = p.get("name", "")
    if name.split(":")[-1] in want or name in want:
        seen.add(p["version"])
for v in sorted(seen):
    print(v)
' "$COORDS")
to_prune=$(echo "$versions" | grep -vx "$KEEP" || true)

if [ -z "$to_prune" ]; then
  echo "Nothing to prune -- registry already serves only versions == $KEEP."
  echo "Present: $(echo "$versions" | tr '\n' ' ')"
  exit 0
fi

echo "Keep:  $KEEP"
echo "Prune: $(echo "$to_prune" | tr '\n' ' ')"
echo "Coordinates per version: $COORDS"

# INTERLOCK: never delete the old versions until the replacement is provably
# being SERVED. A latest-only registry has no fallback, so pruning while $KEEP
# is missing or half-published leaves consumers with nothing resolvable, and
# Maven immutability means the deleted versions cannot be put back. Both SDK
# coordinates are checked because the libre one has been silently absent before
# (0.9.3), which is exactly the state in which pruning would be unrecoverable.
echo "interlock: confirming $KEEP is served before deleting anything"
REG="https://codeberg.org/api/packages/cefrium/maven/com/cefrium"
for art in cefrium-sdk cefrium-sdk-libre; do
  code=$(curl -s -o /dev/null -w "%{http_code}" -r 0-0 \
          "$REG/$art/$KEEP/$art-$KEEP.aar" -K "$CURL_AUTH_CFG")
  case "$code" in
    200|206) echo "  ok: $art $KEEP is served (HTTP $code)" ;;
    *) echo "  ABORT: $art $KEEP is NOT served (HTTP $code)."
       echo "  Refusing to prune: the replacement must be resolvable first."
       exit 1 ;;
  esac
done

# PRESERVATION: the policy is not "delete the old binaries", it is "keep the
# record and drop the binaries". The record is the git tag plus the GPG-signed
# SHA256SUMS on that version's Codeberg release. Enforce it here instead of
# trusting the docs: a version with no signed record would be erased with no
# way to verify afterwards what its bytes were.
echo "preservation: confirming each pruned version keeps a signed record"
RELAPI="https://codeberg.org/api/v1/repos/cefrium/cef-android/releases/tags"
unpreserved=""
for v in $to_prune; do
  rel=$(curl -s "$RELAPI/v$v" -K "$CURL_AUTH_CFG")
  sums=$(printf '%s' "$rel" | grep -cE "\"name\":\"SHA256SUMS-$v\"" || true)
  asc=$(printf '%s' "$rel"  | grep -cE "\"name\":\"SHA256SUMS-$v\.asc\"" || true)
  if [ "$sums" -ge 1 ] && [ "$asc" -ge 1 ]; then
    echo "  ok: v$v has a GPG-signed SHA256SUMS on its release"
  else
    echo "  MISSING: v$v has no signed SHA256SUMS (sums=$sums sig=$asc)"
    unpreserved="$unpreserved $v"
  fi
done
if [ -n "$unpreserved" ]; then
  echo "  ABORT: refusing to delete version(s) with no signed record:$unpreserved"
  echo "  Publish their SHA256SUMS first, or exclude them deliberately."
  exit 1
fi

if [ "$YES" -ne 1 ]; then
  echo
  echo "DRY RUN -- destructive. Re-run with --yes (or CEFRIUM_YES=1) to delete."
  exit 0
fi

for v in $to_prune; do
  for art in $COORDS; do
    enc="com.cefrium%3A$art"
    code=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$API/maven/$enc/$v" \
            -K "$CURL_AUTH_CFG")
    case "$code" in
      204) echo "  deleted $art $v" ;;
      404) ;;  # coordinate didn't exist for this version -- fine
      *)   echo "  WARN: delete $art $v -> HTTP $code" ;;
    esac
  done
done

# Scoped like the version list above: an unscoped query here reports other
# com.cefrium products (e.g. exam-lockdown 0.1.0) as if they were SDK versions
# this policy had failed to clean up.
remaining=$(curl -s "$API?type=maven&limit=100" -K "$CURL_AUTH_CFG" \
             | python3 -c '
import json, sys
want = set(sys.argv[1].split())
seen = {p["version"] for p in json.load(sys.stdin)
        if p.get("name","").split(":")[-1] in want}
print(" ".join(sorted(seen)))
' "$COORDS")
echo "Done. Registry now serves (SDK coordinates): $remaining"
