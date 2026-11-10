#!/bin/bash
# Publish one release to the static Maven repository on Cloudflare R2
# (served as https://maven.cefrium.com), and keep its retention.
#
# Usage:
#   cef/cefrium_sdk/publish-r2.sh <version> [<maven-dir>]   upload + verify
#   cef/cefrium_sdk/publish-r2.sh --prune [--yes]           retention
#
# <maven-dir> is a Maven layout as publish-maven.sh writes it with
# CEFRIUM_MAVEN_DIR (the cut's `stage` phase: out/release/staged-maven).
#
# Upload adds .md5/.sha1/.sha256/.sha512 to every file, merges
# maven-metadata.xml per coordinate, and sets Cache-Control: versioned files
# never change (one year, immutable); maven-metadata.xml changes every release
# (five minutes). A version already on the remote is refused: Maven versions
# are immutable here as on any registry.
#
# Environment:
#   CEFRIUM_R2_REMOTE        rclone remote:bucket (default cefrium-r2:cefrium-maven).
#                            Credentials live in the local rclone config only.
#   CEFRIUM_R2_URL           public base URL (default https://maven.cefrium.com).
#                            Verification fetches every file through it;
#                            "none" stops after the byte check on the bucket.
#   CEFRIUM_R2_KEEP_VERSIONS versions kept by --prune (default 10).
#   CEFRIUM_SKIP_SMOKE_TEST=1 skip the consumer build against CEFRIUM_R2_URL.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REMOTE="${CEFRIUM_R2_REMOTE:-cefrium-r2:cefrium-maven}"
URL="${CEFRIUM_R2_URL:-https://maven.cefrium.com}"
KEEP="${CEFRIUM_R2_KEEP_VERSIONS:-10}"
GROUP_PATH="com/cefrium"
CC_IMMUTABLE="Cache-Control: public, max-age=31536000, immutable"
CC_METADATA="Cache-Control: public, max-age=300"

command -v rclone >/dev/null || { echo "ERROR: rclone not installed"; exit 1; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/cefrium-r2.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

sums() {  # sums <file>: write the four checksum side files
  local f="$1"
  md5sum    "$f" | cut -d' ' -f1 > "$f.md5"
  sha1sum   "$f" | cut -d' ' -f1 > "$f.sha1"
  sha256sum "$f" | cut -d' ' -f1 > "$f.sha256"
  sha512sum "$f" | cut -d' ' -f1 > "$f.sha512"
}

# write_metadata <out> <artifactId> <version>...: versions in release order.
write_metadata() {
  local out="$1" art="$2"; shift 2
  local last="${*: -1}"
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<metadata>'
    echo '  <groupId>com.cefrium</groupId>'
    echo "  <artifactId>$art</artifactId>"
    echo '  <versioning>'
    echo "    <latest>$last</latest>"
    echo "    <release>$last</release>"
    echo '    <versions>'
    for v in "$@"; do echo "      <version>$v</version>"; done
    echo '    </versions>'
    echo "    <lastUpdated>$(date -u +%Y%m%d%H%M%S)</lastUpdated>"
    echo '  </versioning>'
    echo '</metadata>'
  } > "$out"
}

remote_versions() {  # remote_versions <artifactId>: version dirs on the remote, sorted
  rclone lsf --dirs-only "$REMOTE/$GROUP_PATH/$1/" 2>/dev/null | tr -d / | sort -V
}

coords_of() {  # coordinates present on the remote
  rclone lsf --dirs-only "$REMOTE/$GROUP_PATH/" 2>/dev/null | tr -d /
}

# publish_metadata <artifactId>: rebuild maven-metadata.xml from the version dirs.
publish_metadata() {
  local art="$1" d="$WORK/meta/$1"
  mapfile -t vs < <(remote_versions "$art")
  mkdir -p "$d"
  if [ "${#vs[@]}" -eq 0 ]; then
    rclone delete "$REMOTE/$GROUP_PATH/$art/" --include 'maven-metadata.xml*'
    return
  fi
  write_metadata "$d/maven-metadata.xml" "$art" "${vs[@]}"
  sums "$d/maven-metadata.xml"
  rclone copy "$d" "$REMOTE/$GROUP_PATH/$art/" --header-upload "$CC_METADATA"
}

if [ "${1:-}" = "--prune" ]; then
  YES=0; [ "${2:-}" = "--yes" ] && YES=1; [ "${CEFRIUM_YES:-0}" = "1" ] && YES=1
  case "$KEEP" in ''|*[!0-9]*|0) echo "ERROR: CEFRIUM_R2_KEEP_VERSIONS must be >= 1"; exit 1 ;; esac
  mapfile -t all < <(remote_versions cefrium-sdk)
  n=${#all[@]}
  if [ "$n" -le "$KEEP" ]; then echo ">>> $n version(s) on R2, keeping $KEEP: nothing to prune."; exit 0; fi
  drop=("${all[@]:0:$((n - KEEP))}")
  echo ">>> R2 keeps the newest $KEEP of $n: dropping ${drop[*]}"
  [ "$YES" = 1 ] || { echo "    (plan only; --yes or CEFRIUM_YES=1 to delete)"; exit 0; }
  for art in $(coords_of); do
    for v in "${drop[@]}"; do rclone purge "$REMOTE/$GROUP_PATH/$art/$v" 2>/dev/null || true; done
    publish_metadata "$art"
  done
  echo ">>> pruned."
  exit 0
fi

VERSION="${1:?usage: publish-r2.sh <version> [<maven-dir>] | --prune [--yes]}"
SRC="${2:-$PWD/out/release/staged-maven}"
[ -d "$SRC/$GROUP_PATH" ] || { echo "ERROR: no Maven layout at $SRC/$GROUP_PATH"; exit 1; }

# Stage a copy with checksums; the source dir stays as the cut wrote it.
STAGE="$WORK/stage"
mkdir -p "$STAGE"
arts=()
for d in "$SRC/$GROUP_PATH"/*/"$VERSION"; do
  [ -d "$d" ] || continue
  art="$(basename "$(dirname "$d")")"
  arts+=("$art")
  mkdir -p "$STAGE/$art/$VERSION"
  find "$d" -maxdepth 1 -type f ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' \
    -exec cp {} "$STAGE/$art/$VERSION/" \;
  for f in "$STAGE/$art/$VERSION"/*; do
    case "$f" in *.md5|*.sha1|*.sha256|*.sha512|*.asc) ;; *) sums "$f" ;; esac
  done
done
[ "${#arts[@]}" -gt 0 ] || { echo "ERROR: no coordinate has version $VERSION under $SRC"; exit 1; }
echo ">>> $VERSION: ${#arts[@]} coordinates, $(du -sh "$STAGE" | cut -f1)"

for art in "${arts[@]}"; do
  if rclone lsf "$REMOTE/$GROUP_PATH/$art/$VERSION/" 2>/dev/null | grep -q .; then
    echo "ERROR: $art $VERSION is already on R2; versions are immutable."
    exit 1
  fi
done

for art in "${arts[@]}"; do
  echo "  upload $art/$VERSION"
  rclone copy "$STAGE/$art/$VERSION" "$REMOTE/$GROUP_PATH/$art/$VERSION" \
    --header-upload "$CC_IMMUTABLE" --checksum
done

# Byte check against the bucket before the metadata makes the version visible.
for art in "${arts[@]}"; do
  rclone check "$STAGE/$art/$VERSION" "$REMOTE/$GROUP_PATH/$art/$VERSION" --one-way --download -q || {
    echo "ERROR: $art $VERSION on R2 differs from the staged files."; exit 1; }
done
for art in "${arts[@]}"; do publish_metadata "$art"; done
echo ">>> uploaded and byte-checked on R2."

if [ "$URL" = "none" ]; then  # remote-only check (no public URL yet)
  echo ">>> CEFRIUM_R2_URL=none: public URL not verified."; exit 0
fi
# Through the public URL: every file, sha256 against the staged copy.
bad=0
while IFS= read -r f; do
  rel="${f#"$STAGE"/}"
  got="$(curl -sfL "$URL/$GROUP_PATH/$rel" | sha256sum | cut -d' ' -f1)" || got=""
  [ "$got" = "$(cut -d' ' -f1 < "$f.sha256")" ] || { echo "  FAIL $URL/$GROUP_PATH/$rel"; bad=1; }
done < <(find "$STAGE" -type f ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512')
[ "$bad" = 0 ] || { echo "ERROR: $URL does not serve $VERSION as uploaded."; exit 1; }
echo ">>> $URL serves every $VERSION file as uploaded."

if [ "${CEFRIUM_SKIP_SMOKE_TEST:-0}" != "1" ]; then
  for art in cefrium-sdk cefrium-sdk-libre; do
    echo ">>> consumer build of $art from $URL"
    CEFRIUM_MAVEN_URL="$URL" ARTIFACT="$art" "$SCRIPT_DIR/test-published-release.sh" "$VERSION"
  done
fi
echo ">>> $VERSION published on R2."
