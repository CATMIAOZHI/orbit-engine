#!/bin/bash
# Where does a Cefrium version actually exist? Prints the deterministic truth
# table for one version across both registries and the Codeberg release.
#
#   cef/cefrium_sdk/release-status.sh [version]      (default: Cefrium.VERSION)
#
# WHY THIS EXISTS
#
# Answering "is 0.9.4 on Central?", "does the published AAR carry the right
# engine?", "which old versions are still in the registry?" used to mean hand-
# writing curl and python at the terminal every single release. That is slow,
# it is not reproducible, and the answer depended on who typed it. Worse, the
# question went UNASKED for weeks: Central silently stopped at 0.8.9 while four
# 0.9.x releases were cut, every one of them a 404 for adopters, and nobody
# noticed because nothing printed the table.
#
# Read-only. Safe to run at any time, and cheap enough to run after every cut.
# Exits non-zero if the version is incompletely published, so it can gate a cut.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEFJAVA="$SCRIPT_DIR/java/src/com/cefrium/Cefrium.java"
VERSION="${1:-$(grep -oE 'String VERSION = "[^"]+"' "$CEFJAVA" | grep -oE '"[^"]+"' | tr -d '"')}"
[ -n "$VERSION" ] || { echo "ERROR: no version given and none readable from Cefrium.java" >&2; exit 2; }
ENGINE="$(grep -oE 'CHROMIUM_VERSION = "[0-9.]+"' "$CEFJAVA" | grep -oE '[0-9.]+')"

CB="https://codeberg.org/api/packages/cefrium/maven/com/cefrium"
MC="https://repo1.maven.org/maven2/com/cefrium"
RELAPI="https://codeberg.org/api/v1/repos/cefrium/cef-android/releases/tags"
PKGAPI="https://codeberg.org/api/v1/packages/cefrium"

# Auth only helps the package LISTING; the artifacts themselves are public.
CURL_AUTH_CFG=""
if [ -n "${CEFRIUM_REGISTRY_TOKEN:-}" ] && [ -f "$CEFRIUM_REGISTRY_TOKEN" ]; then
  CURL_AUTH_CFG="$(mktemp "${TMPDIR:-/tmp}/cefrium-status-auth.XXXXXX")"
  chmod 600 "$CURL_AUTH_CFG"
  printf 'header = "Authorization: token %s"\n' "$(cat "$CEFRIUM_REGISTRY_TOKEN")" > "$CURL_AUTH_CFG"
  trap 'rm -f "$CURL_AUTH_CFG"' EXIT
fi
auth() { [ -n "$CURL_AUTH_CFG" ] && printf -- '-K\n%s\n' "$CURL_AUTH_CFG"; }

# artifact:extension. The plugin marker is a packaging=pom coordinate, so asking
# for a .jar there is a guaranteed 404 and says nothing about whether it exists.
ARTIFACTS="cefrium-sdk:pom cefrium-sdk-libre:pom cefrium-sdk-core:aar cefrium-native-codecs-arm64-v8a:aar cefrium-native-codecs-x86_64:aar cefrium-native-codecs-armeabi-v7a:aar cefrium-native-libre-arm64-v8a:aar cefrium-native-libre-x86_64:aar cefrium-native-libre-armeabi-v7a:aar cefrium-compose:aar cefrium-unifiedpush:aar cefrium-gradle-plugin:jar com.cefrium.gradle.plugin:pom"

fail=0
# A single byte is enough to prove the object is served, and avoids pulling
# 150 MB per coordinate just to answer "is it there".
probe() { curl -s -o /dev/null -w "%{http_code}" -r 0-0 $(auth) "$1"; }
mark() { case "$1" in 200|206) echo "yes" ;; 404) echo "NO" ;; *) echo "? $1" ;; esac; }

echo "Cefrium $VERSION (engine $ENGINE)"
echo
printf "%-28s %-12s %-12s\n" "coordinate" "Codeberg" "Central"
printf "%-28s %-12s %-12s\n" "---------------------------" "-----------" "-----------"
for spec in $ARTIFACTS; do
  art="${spec%%:*}"; ext="${spec##*:}"
  cb=$(mark "$(probe "$CB/$art/$VERSION/$art-$VERSION.$ext")")
  mc=$(mark "$(probe "$MC/$art/$VERSION/$art-$VERSION.$ext")")
  printf "%-28s %-12s %-12s\n" "$art" "$cb" "$mc"
  # Central is where adopters pin, so a gap there is the one that costs users.
  [ "$mc" = "yes" ] || fail=1
  [ "$cb" = "yes" ] || fail=1
done

echo
echo "Codeberg release v$VERSION"
rel=$(curl -s $(auth) "$RELAPI/v$VERSION")
# Shell matches, not `printf ... | grep -q`: under pipefail that pipeline
# reports failure on a SUCCESSFUL match once the body exceeds the pipe buffer
# (grep -q exits at the match, printf takes SIGPIPE, pipefail returns its 141).
# The same construct made release-web-verify.sh report an 85 KB page as stale.
# This body grows with every release asset, so it is one asset away from it.
if [[ "$rel" == *'"tag_name"'* ]]; then
  printf '%s' "$rel" | grep -oE '"name":"[^"]+"' | cut -d'"' -f4 | sort -u \
    | grep -vE "^(v$VERSION|Cefrium)" | sed 's/^/  asset: /'
  for want in "SHA256SUMS-$VERSION" "SHA256SUMS-$VERSION.asc" \
              "cefrium-sdk-$VERSION.spdx.json" "cefrium-sdk-$VERSION-NOTICE.txt"; do
    [[ "$rel" == *"\"name\":\"$want\""* ]] \
      || { echo "  MISSING: $want"; fail=1; }
  done
else
  echo "  MISSING: no release for tag v$VERSION"; fail=1
fi

# What the PUBLISHED bytes contain -- not what the local build produced. This is
# the check that distinguishes "we built it right" from "adopters get it right".
echo
echo "Published cefrium-sdk $VERSION contents (the single-file AAR on the release page, not out/)"
TMPAAR="$(mktemp "${TMPDIR:-/tmp}/cefrium-pub.XXXXXX.aar")"
trap 'rm -f "$CURL_AUTH_CFG" "$TMPAAR"' EXIT
if curl -sfL -o "$TMPAAR" "https://codeberg.org/cefrium/cef-android/releases/download/v$VERSION/cefrium-sdk-$VERSION.aar" \
   && [ -s "$TMPAAR" ]; then
  abis=$(unzip -l "$TMPAAR" 2>/dev/null | grep -oE 'jni/[a-z0-9_-]+/libcef\.so' \
         | sed 's|jni/||;s|/libcef.so||' | sort -u | tr '\n' ' ')
  echo "  ABIs: ${abis:-none}"
  case "$abis" in *x86_64*) ;; *) echo "  MISSING x86_64 -- emulator users get UnsatisfiedLinkError"; fail=1 ;; esac
  case "$abis" in *arm64-v8a*) ;; *) echo "  MISSING arm64-v8a"; fail=1 ;; esac
  case "$abis" in *armeabi-v7a*) ;; *) echo "  MISSING armeabi-v7a -- 32-bit ARM devices get no engine"; fail=1 ;; esac
  d="$(mktemp -d)"
  if unzip -o -q "$TMPAAR" jni/arm64-v8a/libcef.so -d "$d" 2>/dev/null; then
    # grep -c, never -q: -q exits early, SIGPIPEs strings, and pipefail then
    # reports a false "stale engine" on a perfectly good artifact.
    hits=$(strings -a "$d/jni/arm64-v8a/libcef.so" 2>/dev/null | grep -cF "$ENGINE" || true)
    [ "${hits:-0}" -gt 0 ] && echo "  engine: carries $ENGINE" \
      || { echo "  engine: does NOT carry $ENGINE"; fail=1; }
  fi
  rm -rf "$d"
else
  echo "  could not download the published AAR"; fail=1
fi

# Everything the retention policy still owns. Scoped to the SDK coordinates, so
# other com.cefrium products are not reported as leftovers.
echo
echo "Registry retention (SDK coordinates only)"
curl -s $(auth) "$PKGAPI?type=maven&limit=100" | python3 -c '
import json, sys, collections
want = {"cefrium-sdk","cefrium-sdk-libre","cefrium-sdk-core",
        "cefrium-native-codecs-arm64-v8a","cefrium-native-codecs-x86_64",
        "cefrium-native-codecs-armeabi-v7a",
        "cefrium-native-libre-arm64-v8a","cefrium-native-libre-x86_64",
        "cefrium-native-libre-armeabi-v7a",
        "cefrium-compose","cefrium-unifiedpush",
        "cefrium-gradle-plugin","com.cefrium.gradle.plugin"}
keep = sys.argv[1]
by = collections.defaultdict(int)
try:
    pkgs = json.load(sys.stdin)
except Exception:
    print("  (could not list packages)"); raise SystemExit(0)
for p in pkgs:
    if p.get("name","").split(":")[-1] in want:
        by[p["version"]] += 1
for v in sorted(by):
    print("  %-8s %d coordinates%s" % (v, by[v], "" if v == keep else "   <- superseded, prunable"))
' "$VERSION"

echo
if [ "$fail" = "0" ]; then
  echo "COMPLETE: $VERSION is fully published on both registries with its release record."
  exit 0
fi
echo "INCOMPLETE: see the NO/MISSING rows above. Central gaps are what 404 for adopters."
exit 1
