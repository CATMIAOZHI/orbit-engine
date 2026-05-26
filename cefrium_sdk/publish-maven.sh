#!/bin/bash
# Publish Cefrium SDK variants to Codeberg Packages (Maven registry).
# Usage: ./publish-maven.sh <version>
#
# Example: ./publish-maven.sh 0.1.0
#
# Prerequisites:
#   - Token in ~/.registry-token
#   - AARs in out/release/ (built by build-all-variants.sh)
#   - cefrium-compose AAR built separately

set -e

VERSION="${1:?Usage: $0 <version>}"
TOKEN_FILE="$HOME/.registry-token"
RELEASE_DIR="$(cd "$(dirname "$0")/../../../out/release" && pwd)"
COMPOSE_AAR="$(cd "$(dirname "$0")/../../.." && pwd)/../cefrium-sample/cefrium-compose/lib/build/outputs/aar/lib-release.aar"
BASE_URL="https://codeberg.org/api/packages/cefrium/maven"

if [ ! -f "$TOKEN_FILE" ]; then
  echo "ERROR: Token not found at $TOKEN_FILE"
  echo "Create one at: Codeberg → Settings → Applications → Generate Token (package scope)"
  exit 1
fi

TOKEN=$(cat "$TOKEN_FILE")

upload() {
  local artifact="$1"
  local file="$2"
  local ext="${file##*.}"
  local url="$BASE_URL/com/cefrium/$artifact/$VERSION/$artifact-$VERSION.$ext"

  echo "  Uploading $artifact-$VERSION.$ext ($(du -h "$file" | cut -f1))"
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X PUT \
    -H "Authorization: token $TOKEN" \
    -F "file=@$file" \
    "$url")

  if [ "$status" = "201" ]; then
    echo "    OK (201 Created)"
  elif [ "$status" = "409" ]; then
    echo "    Already exists (409 Conflict) — skipping"
  else
    echo "    FAILED (HTTP $status)"
    return 1
  fi
}

generate_pom() {
  local artifact="$1"
  local desc="$2"
  local deps="$3"
  local pom_file="/tmp/cefrium-publish-$artifact-$VERSION.pom"

  cat > "$pom_file" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.cefrium</groupId>
  <artifactId>$artifact</artifactId>
  <version>$VERSION</version>
  <packaging>aar</packaging>
  <name>$artifact</name>
  <description>$desc</description>
  <url>https://cefrium.com</url>
  <licenses>
    <license>
      <name>LGPL-3.0-or-later</name>
      <url>https://www.gnu.org/licenses/lgpl-3.0.html</url>
    </license>
  </licenses>
  <scm>
    <url>https://codeberg.org/cefrium/cef-android</url>
  </scm>
$deps
</project>
EOF
  echo "$pom_file"
}

echo ""
echo "Publishing Cefrium SDK v$VERSION to Codeberg Packages"
echo "======================================================"
echo ""

# Publish each AAR variant found in release dir
for aar in "$RELEASE_DIR"/cefrium-sdk-*.aar; do
  [ -f "$aar" ] || continue
  basename=$(basename "$aar" .aar)
  # cefrium-sdk-arm64-codecs → artifact = cefrium-sdk-arm64-codecs
  # But for Maven, we use classifier instead
  # Main artifact: cefrium-sdk, classifier: arm64-codecs
  artifact="${basename%-*-*}"  # cefrium-sdk
  classifier="${basename#cefrium-sdk-}"  # arm64-codecs

  echo "Publishing $basename..."
  pom=$(generate_pom "$basename" \
    "Cefrium SDK ($classifier) — CEF for Android" "")
  upload "$basename" "$pom"
  upload "$basename" "$aar"
  rm -f "$pom"
  echo ""
done

# Publish cefrium-compose if available
if [ -f "$COMPOSE_AAR" ]; then
  echo "Publishing cefrium-compose..."
  deps='  <dependencies>
    <dependency>
      <groupId>com.cefrium</groupId>
      <artifactId>cefrium-sdk</artifactId>
      <version>'"$VERSION"'</version>
    </dependency>
  </dependencies>'
  pom=$(generate_pom "cefrium-compose" \
    "Jetpack Compose wrapper for Cefrium SDK" "$deps")
  upload "cefrium-compose" "$pom"
  upload "cefrium-compose" "$COMPOSE_AAR"
  rm -f "$pom"
  echo ""
fi

echo "======================================================"
echo "Done! Packages at: https://codeberg.org/cefrium/-/packages"
echo ""
echo "Usage in build.gradle:"
echo '  maven { url "https://codeberg.org/api/packages/cefrium/maven" }'
echo "  implementation 'com.cefrium:cefrium-sdk-arm64-codecs:$VERSION'"
echo ""
