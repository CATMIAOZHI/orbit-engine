#!/bin/bash
# Retention on Codeberg, run by the cut's `prune` phase after a release:
#
#   registry  Maven versions of the SDK coordinates. Keeps the newest
#             CEFRIUM_KEEP_VERSIONS (default 2) up to <keep-version>, so a pinned
#             build has one release to move. 1 = latest only.
#   releases  Binaries attached to cef-android releases (the browser APK, AARs).
#             Keeps them on the newest CEFRIUM_KEEP_RELEASE_BINARIES releases
#             (default: CEFRIUM_KEEP_VERSIONS). Older releases keep their record:
#             SHA256SUMS + .asc, SBOM, NOTICE.
#
# A version is never deleted unless <keep-version> is served and the version
# has a GPG-signed SHA256SUMS on its release. Versions above <keep-version> are
# never touched. Older versions remain as the git tag and that signed record
# (and, up to 0.17.1, on Maven Central) -- see /releases and RELEASING-CEFRIUM.md.
#
# Usage: cef/cefrium_sdk/prune-old-versions.sh <keep-version> [--yes]
#   Without --yes (or CEFRIUM_YES=1) it prints the plan and deletes nothing.
set -euo pipefail

KEEP="${1:?usage: prune-old-versions.sh <keep-version> [--yes]}"
YES=0
[ "${2:-}" = "--yes" ] && YES=1
[ "${CEFRIUM_YES:-0}" = "1" ] && YES=1
N="${CEFRIUM_KEEP_VERSIONS:-2}"
NB="${CEFRIUM_KEEP_RELEASE_BINARIES:-$N}"
for v in "$N" "$NB"; do
  case "$v" in ''|*[!0-9]*|0) echo "ERROR: CEFRIUM_KEEP_VERSIONS and CEFRIUM_KEEP_RELEASE_BINARIES must be >= 1"; exit 1 ;; esac
done

TOKEN="$(cat "${CEFRIUM_REGISTRY_TOKEN:?set CEFRIUM_REGISTRY_TOKEN to the file holding your registry token (see your local setup notes)}")"
# Keep the token OUT of argv (`ps`): pass the auth header via a 0600 curl config
# file (`curl -K`) instead of putting it on the command line. Removed on exit.
CURL_AUTH_CFG="$(mktemp "${TMPDIR:-/tmp}/cefrium-curl-auth.XXXXXX")"
RELJSON="$(mktemp "${TMPDIR:-/tmp}/cefrium-releases.XXXXXX")"
chmod 600 "$CURL_AUTH_CFG"
printf 'header = "Authorization: token %s"\n' "$TOKEN" > "$CURL_AUTH_CFG"
trap 'rm -f "$CURL_AUTH_CFG" "$RELJSON"' EXIT
unset TOKEN
API="https://codeberg.org/api/v1/packages/cefrium"
REPOAPI="https://codeberg.org/api/v1/repos/cefrium/cef-android"
REG="https://codeberg.org/api/packages/cefrium/maven/com/cefrium"

# All Maven coordinates the publish step creates. This list is also the SCOPE of
# the registry policy: nothing outside it is ever considered for deletion (other
# com.cefrium products version independently).
COORDS="cefrium-sdk cefrium-sdk-libre cefrium-sdk-core cefrium-gms cefrium-native-codecs-arm64-v8a cefrium-native-codecs-x86_64 cefrium-native-codecs-armeabi-v7a cefrium-native-libre-arm64-v8a cefrium-native-libre-x86_64 cefrium-native-libre-armeabi-v7a cefrium-compose cefrium-unifiedpush cefrium-gradle-plugin com.cefrium.gradle.plugin"

# ---- plan: registry ---------------------------------------------------------
versions=$(curl -s "$API?type=maven&limit=100" -K "$CURL_AUTH_CFG" \
            | python3 -c '
import json, sys
want = set(sys.argv[1].split())
seen = set()
for p in json.load(sys.stdin):
    # Names come back bare ("cefrium-sdk") or group-qualified ("com.cefrium:...").
    if p.get("name", "").split(":")[-1] in want:
        seen.add(p["version"])
print("\n".join(sorted(seen)))
' "$COORDS")
# The N newest versions up to and including KEEP; anything above KEEP is kept.
all=$( { echo "$KEEP"; echo "$versions"; } | grep . | sort -uV)
keep_set=$(echo "$all" | awk -v k="$KEEP" '{print} $0==k{exit}' | tail -n "$N")
above=$(echo "$all" | awk -v k="$KEEP" 'f{print} $0==k{f=1}')
to_prune=$(echo "$versions" | grep . | grep -vxF -f <(printf '%s\n' $keep_set $above) || true)

# ---- plan: release binaries -------------------------------------------------
echo "[" > "$RELJSON"; page=1; first=1
while :; do
  body=$(curl -s "$REPOAPI/releases?limit=50&page=$page" -K "$CURL_AUTH_CFG")
  [ "$body" = "[]" ] || [ -z "$body" ] && break
  [ "$first" = 1 ] || echo "," >> "$RELJSON"
  printf '%s' "${body:1:${#body}-2}" >> "$RELJSON"; first=0; page=$((page + 1))
done
echo "]" >> "$RELJSON"
# One line per binary to delete: "<release id> <asset id> <bytes> <tag> <name>".
# Releases without a signed SHA256SUMS are reported, not touched.
asset_plan=$(python3 - "$RELJSON" "$KEEP" "$NB" <<'PY'
import json, re, sys
rels = json.load(open(sys.argv[1])); keep = sys.argv[2]; nb = int(sys.argv[3])
vt = lambda v: tuple(int(x) for x in v.split("."))
record = re.compile(r"^SHA256SUMS-.*|.*\.spdx\.json$|.*-NOTICE\.txt$")
sdk = [r for r in rels if re.fullmatch(r"v\d+\.\d+\.\d+", r["tag_name"])]
upto = sorted((r for r in sdk if vt(r["tag_name"][1:]) <= vt(keep)),
              key=lambda r: vt(r["tag_name"][1:]))
kept = {r["tag_name"] for r in upto[-nb:]}
for r in sdk:
    t = r["tag_name"]
    if vt(t[1:]) > vt(keep):
        continue
    names = {a["name"] for a in r.get("assets", [])}
    signed = f"SHA256SUMS-{t[1:]}" in names and f"SHA256SUMS-{t[1:]}.asc" in names
    for a in r.get("assets", []):
        if record.match(a["name"]):
            continue
        # SDK binaries (AARs) follow the retention; anything else (the browser
        # APK attached before 0.19.0) is not for SDK users and goes everywhere.
        if t in kept and a["name"].endswith(".aar"):
            continue
        if not signed:
            print(f"UNSIGNED {t} {a['name']}", file=sys.stderr)
            continue
        print(r["id"], a["id"], a["size"], t, a["name"])
PY
)
asset_bytes=$(echo "$asset_plan" | awk 'NF{s+=$3} END{print s+0}')

echo "Registry: keep $(echo $keep_set $above) (CEFRIUM_KEEP_VERSIONS=$N); prune: ${to_prune:-none}" | tr '\n' ' '; echo
echo "Release binaries: AARs on the newest $NB release(s) up to v$KEEP (CEFRIUM_KEEP_RELEASE_BINARIES), no non-SDK files; delete $(echo "$asset_plan" | grep -c . || true) file(s), $((asset_bytes / 1048576)) MiB"
echo "$asset_plan" | awk 'NF{printf "  %-9s %6d MiB  %s\n", $4, $3/1048576, $5}'

# Codeberg carries what SDK users need. Anything else in the account is shown on
# every run, never deleted here: it may be another product's.
curl -s "$API?limit=100" -K "$CURL_AUTH_CFG" | python3 -c '
import json, sys
want = set(sys.argv[1].split())
for p in json.load(sys.stdin):
    if p.get("name", "").split(":")[-1] not in want:
        print("  not SDK, not touched: %s %s %s (delete in Codeberg -> Packages if unused)"
              % (p["type"], p["name"], p["version"]))
' "$COORDS"

if [ -z "$to_prune" ] && [ -z "$asset_plan" ]; then
  echo "Nothing to prune."
  exit 0
fi

# ---- interlock --------------------------------------------------------------
# Never delete anything until the replacement is provably SERVED: deleted Maven
# versions cannot be put back. Both SDK POMs, and the SDK's binary: the core
# module from 0.19.0 on (the SDK coordinates became POM-only), the AAR before.
echo "interlock: confirming $KEEP is served before deleting anything"
served() { case "$(curl -s -o /dev/null -w "%{http_code}" -r 0-0 "$REG/$1/$KEEP/$1-$KEEP.$2" -K "$CURL_AUTH_CFG")" in 200|206) return 0 ;; *) return 1 ;; esac; }
for art in cefrium-sdk cefrium-sdk-libre; do
  served "$art" pom && echo "  ok: $art $KEEP POM is served" \
    || { echo "  ABORT: $art $KEEP is NOT served. Refusing to prune."; exit 1; }
done
if served cefrium-sdk-core aar || served cefrium-sdk aar; then
  echo "  ok: the $KEEP SDK binary is served"
else
  echo "  ABORT: no SDK binary for $KEEP is served. Refusing to prune."; exit 1
fi

# ---- preservation -----------------------------------------------------------
# The policy is "keep the record, drop the binaries": the git tag plus the
# GPG-signed SHA256SUMS on that version's release.
unpreserved=""
for v in $to_prune; do
  rel=$(curl -s "$REPOAPI/releases/tags/v$v" -K "$CURL_AUTH_CFG")
  if [[ "$rel" == *"\"name\":\"SHA256SUMS-$v\""* && "$rel" == *"\"name\":\"SHA256SUMS-$v.asc\""* ]]; then
    echo "  ok: v$v has a GPG-signed SHA256SUMS on its release"
  else
    echo "  MISSING: v$v has no signed SHA256SUMS"; unpreserved="$unpreserved $v"
  fi
done
if [ -n "$unpreserved" ]; then
  echo "  ABORT: refusing to delete version(s) with no signed record:$unpreserved"
  exit 1
fi

if [ "$YES" -ne 1 ]; then
  echo
  echo "DRY RUN -- destructive. Re-run with --yes (or CEFRIUM_YES=1) to delete."
  exit 0
fi

# ---- delete -----------------------------------------------------------------
fail=0
for v in $to_prune; do
  for art in $COORDS; do
    code=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
            "$API/maven/com.cefrium%3A$art/$v" -K "$CURL_AUTH_CFG")
    case "$code" in
      204) echo "  deleted $art $v" ;;
      404) ;;  # coordinate did not exist for this version
      *)   echo "  WARN: delete $art $v -> HTTP $code"; fail=1 ;;
    esac
  done
done
while read -r rid aid size tag name; do
  [ -n "$rid" ] || continue
  code=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
          "$REPOAPI/releases/$rid/assets/$aid" -K "$CURL_AUTH_CFG")
  case "$code" in
    204) echo "  deleted $tag/$name" ;;
    *)   echo "  WARN: delete $tag/$name -> HTTP $code"; fail=1 ;;
  esac
done <<< "$asset_plan"

remaining=$(curl -s "$API?type=maven&limit=100" -K "$CURL_AUTH_CFG" \
             | python3 -c '
import json, sys
want = set(sys.argv[1].split())
print(" ".join(sorted({p["version"] for p in json.load(sys.stdin)
                       if p.get("name","").split(":")[-1] in want})))
' "$COORDS")
echo "Done. Registry now serves (SDK coordinates): $remaining"
exit "$fail"
