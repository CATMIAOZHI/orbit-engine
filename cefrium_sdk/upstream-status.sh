#!/bin/bash
# upstream-status.sh -- is there a new CEF release to move Cefrium onto, and
# which kind of bump is it? One deterministic answer instead of reading the
# CEF builds page by eye.
#
#   cef/cefrium_sdk/upstream-status.sh [--probe]
#   CEFRIUM_PINNED=156.0.8078.96 cef/cefrium_sdk/upstream-status.sh   # what if
#
# THE RULE: Cefrium follows CEF's "Current Stable Build (Preferred)", the newest
# stable entry of https://cef-builds.spotifycdn.com/index.json. When CEF moves
# its Preferred Stable to a new branch, the old branch gets no more updates, so
# staying on it means shipping without security fixes.
#
# It prints what the tree pins (Cefrium.CHROMIUM_VERSION), what the newest
# published SDK embeds (the cefrium.chromium.version of its POM), CEF's
# Preferred Stable, and the newest stable build on the pinned branch, then the
# verdict and the exact next command. --probe also prices the patch side of
# that bump (probe-engine-bump.sh, no sync).
#
# Exit: 0 = on CEF's Preferred Stable; 10 = a newer build on the same branch
# (patch-level bump); 20 = Preferred Stable is on a newer branch (branch bump);
# 2 = could not tell (network, format).
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"                 # chromium/src
INDEX_URL="https://cef-builds.spotifycdn.com/index.json"
MAVEN="${CEFRIUM_MAVEN_URL:-https://maven.cefrium.com}"
MIRROR="https://codeberg.org/api/packages/cefrium/maven"
CURL=(curl -fsS -m 20)
PROBE=0
[ "${1:-}" = "--probe" ] && PROBE=1

# CEFRIUM_PINNED=<chromium version> asks "what if the tree were on X".
pinned="${CEFRIUM_PINNED:-$(grep -oP 'CHROMIUM_VERSION = "\K[0-9.]+' \
  "$SCRIPT_DIR/java/src/com/cefrium/Cefrium.java")}"
[ -n "$pinned" ] || { echo "ERROR: no CHROMIUM_VERSION in Cefrium.java" >&2; exit 2; }

# The registry, or its Codeberg mirror when the registry does not answer.
latest_sdk="" published="" from=""
for repo in "$MAVEN" "$MIRROR"; do
  latest_sdk="$("${CURL[@]}" "$repo/com/cefrium/cefrium-sdk/maven-metadata.xml" 2>/dev/null \
    | grep -oP '<latest>\K[^<]+' | head -1)"
  [ -n "$latest_sdk" ] || continue
  published="$("${CURL[@]}" "$repo/com/cefrium/cefrium-sdk/$latest_sdk/cefrium-sdk-$latest_sdk.pom" \
    2>/dev/null | grep -oP '<cefrium.chromium.version>\K[^<]+' | head -1)"
  from="$repo"
  break
done

index="$("${CURL[@]}" "$INDEX_URL")" || { echo "ERROR: cannot fetch $INDEX_URL" >&2; exit 2; }

read -r preferred_cef preferred same_cef same verdict <<<"$(python3 -I -c '
import json, sys
pinned = sys.argv[1]
data = json.load(sys.stdin)
# Every platform lists the same releases; linux64 is always present.
stable = [v for v in data["linux64"]["versions"] if v.get("channel") == "stable"]
if not stable:
    sys.exit("no stable entries")
key = lambda v: tuple(int(x) for x in v["chromium_version"].split("."))
preferred = stable[0]                      # the page lists newest first
branch = pinned.split(".")[2]
same = [v for v in stable if v["chromium_version"].split(".")[2] == branch]
same = max(same, key=key) if same else None
pv = key(preferred)
if pv[2] != int(branch):
    verdict = "branch" if pv > tuple(int(x) for x in pinned.split(".")) else "current"
elif pv > tuple(int(x) for x in pinned.split(".")):
    verdict = "patch"
else:
    verdict = "current"
print(preferred["cef_version"], preferred["chromium_version"],
      same["cef_version"] if same else "-", same["chromium_version"] if same else "-",
      verdict)
' "$pinned" <<<"$index")" || { echo "ERROR: could not read $INDEX_URL" >&2; exit 2; }

branch="$(echo "$pinned" | cut -d. -f3)"
pbranch="$(echo "$preferred" | cut -d. -f3)"
echo "Tree pins            Chromium $pinned (CEF branch $branch)"
echo "Newest published SDK ${latest_sdk:-?} embeds Chromium ${published:-?}${from:+ ($from)}"
[ "$from" = "$MIRROR" ] && echo "                     WARNING: $MAVEN did not answer; read from the mirror"
echo "CEF Preferred Stable $preferred_cef (Chromium $preferred, branch $pbranch)"
echo "Newest on branch $branch $same_cef (Chromium $same)"
echo
case "$verdict" in
  current)
    echo "VERDICT: the tree is on CEF's Preferred Stable. No engine bump."
    [ -n "$published" ] && [ "$published" != "$pinned" ] && \
      echo "         The published SDK ($published) is behind the tree: the next cut ships $pinned."
    rc=0 ;;
  patch)
    echo "VERDICT: patch-level bump, same CEF branch $branch: $pinned -> $preferred."
    echo "         Next: cef/cefrium_sdk/engine-bump.sh $preferred $branch"
    rc=10 ;;
  branch)
    echo "VERDICT: branch bump. CEF's Preferred Stable is branch $pbranch; branch $branch"
    echo "         gets no more updates. $pinned -> $preferred."
    echo "         Next: cef/cefrium_sdk/engine-bump.sh $preferred $pbranch"
    rc=20 ;;
esac
if [ "$PROBE" = 1 ] && [ "$rc" != 0 ]; then
  echo
  ( cd "$SRC" && "$SCRIPT_DIR/probe-engine-bump.sh" "$preferred" ) | tail -12
fi
exit "$rc"
