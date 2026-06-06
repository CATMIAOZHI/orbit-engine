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
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RELEASE_DIR="$(cd "$SCRIPT_DIR/../../out/release" && pwd)"
# Compose AAR is built by the self-contained module at cef/cefrium_compose
# (relocated out of cefrium-sample; see CLAUDE.md).
COMPOSE_AAR="$(cd "$(dirname "$0")/.." && pwd)/cefrium_compose/build/outputs/aar/cefrium-compose-release.aar"
BASE_URL="https://codeberg.org/api/packages/cefrium/maven"

if [ ! -f "$TOKEN_FILE" ]; then
  echo "ERROR: Token not found at $TOKEN_FILE"
  echo "Create one at: Codeberg → Settings → Applications → Generate Token (package scope)"
  exit 1
fi

TOKEN=$(cat "$TOKEN_FILE")

# DEP-8-style integrity gate: re-fetch a just-published artifact and confirm it is
# what a consumer will actually download — not a multipart envelope, an HTML error
# page, or an empty body. AAR/JAR must start with the ZIP magic "PK"; POM/XML must
# begin with a real XML element. This is what would have caught the multipart-upload
# corruption immediately instead of after a consumer's build failed.
verify_artifact() {
  local url="$1" ext="$2"
  local tmp; tmp=$(mktemp)
  curl -s -H "Authorization: token $TOKEN" -r 0-255 "$url" -o "$tmp"
  local ok=1
  case "$ext" in
    aar|jar)
      [ "$(xxd -p -l2 "$tmp" 2>/dev/null)" = "504b" ] || ok=0 ;;   # ZIP magic: PK
    pom|xml|module)
      # A multipart envelope starts with "--"; an error page with HTML. Valid Maven
      # XML contains one of these elements in its prolog.
      head -c2 "$tmp" | grep -q -- '--' && ok=0
      grep -qE '<\?xml|<project|<metadata|<modelVersion' "$tmp" || ok=0 ;;
  esac
  rm -f "$tmp"
  if [ "$ok" = 1 ]; then
    echo "    verified: $ext integrity OK"
    return 0
  fi
  echo "    *** VERIFY FAILED: published $url is not a valid $ext"
  echo "    *** (corrupt upload — e.g. multipart envelope baked in; aborting release)"
  return 1
}

upload() {
  local artifact="$1"
  local file="$2"
  local ext="${file##*.}"
  local url="$BASE_URL/com/cefrium/$artifact/$VERSION/$artifact-$VERSION.$ext"

  echo "  Uploading $artifact-$VERSION.$ext ($(du -h "$file" | cut -f1))"
  # IMPORTANT: the Codeberg Maven API expects the RAW file body on a PUT.
  # NEVER use `-F`/`--form` here: that sends multipart/form-data, which bakes the
  # MIME envelope (boundary + headers) into the stored artifact and corrupts every
  # POM/AAR/JAR. Use --upload-file (raw PUT body). See verify_artifact() below.
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: token $TOKEN" \
    --upload-file "$file" \
    "$url")

  if [ "$status" = "201" ]; then
    echo "    OK (201 Created)"
    verify_artifact "$url" "$ext" || exit 1
  elif [ "$status" = "409" ]; then
    echo "    Already exists (409 Conflict) — skipping upload, verifying existing"
    verify_artifact "$url" "$ext" || exit 1
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

# Publish the MULTI-ABI SDK artifacts. The per-arch cefrium-sdk-<arch>-<codec>.aar
# are build intermediates; merge-multiabi.sh combines each codec flavor's arches
# into one multi-ABI AAR (cefrium-sdk.aar = codecs default, cefrium-sdk-libre.aar).
# AGP packages all ABIs from the AAR and the consumer's APK/App Bundle delivers
# only the device's ABI — so a single coordinate works on arm64 and x86_64.
published_any=0
for artifact in cefrium-sdk cefrium-sdk-libre; do
  aar="$RELEASE_DIR/$artifact.aar"
  if [ ! -f "$aar" ]; then
    echo "skip $artifact (not built)"
    continue
  fi
  echo "Publishing $artifact (multi-ABI)..."
  pom=$(generate_pom "$artifact" "Cefrium SDK — CEF for Android (multi-ABI)" "")
  upload "$artifact" "$pom"
  upload "$artifact" "$aar"
  rm -f "$pom"
  published_any=1
  echo ""
done
if [ "$published_any" != "1" ]; then
  echo "ERROR: no multi-ABI cefrium-sdk.aar / cefrium-sdk-libre.aar in '$RELEASE_DIR'." >&2
  echo "       Run build-all-variants.sh then merge-multiabi.sh first." >&2
  exit 1
fi

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

# Publish Gradle Plugin
PLUGIN_JAR="$SCRIPT_DIR/gradle-plugin/build/libs/gradle-plugin.jar"
if [ -f "$PLUGIN_JAR" ]; then
  echo "Publishing cefrium-gradle-plugin..."
  pom=$(generate_pom "cefrium-gradle-plugin" \
    "Cefrium Gradle Plugin — generates R.java for Chromium packages" "")
  upload "cefrium-gradle-plugin" "$pom"
  upload "cefrium-gradle-plugin" "$PLUGIN_JAR"
  rm -f "$pom"

  # Plugin marker POM (enables `plugins { id 'com.cefrium' version 'X' }`)
  MARKER_POM="/tmp/cefrium-publish-marker-$VERSION.pom"
  cat > "$MARKER_POM" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.cefrium</groupId>
  <artifactId>com.cefrium.gradle.plugin</artifactId>
  <version>$VERSION</version>
  <packaging>pom</packaging>
  <dependencies>
    <dependency>
      <groupId>com.cefrium</groupId>
      <artifactId>cefrium-gradle-plugin</artifactId>
      <version>$VERSION</version>
    </dependency>
  </dependencies>
</project>
EOF
  upload "com.cefrium.gradle.plugin" "$MARKER_POM"
  rm -f "$MARKER_POM"
  echo ""
else
  echo "WARNING: Gradle Plugin JAR not found at $PLUGIN_JAR — skipping"
  echo ""
fi

echo "======================================================"
echo "Uploaded. Packages at: https://codeberg.org/cefrium/-/packages"
echo ""

# Final DEP-8-style gate: a release is not "done" until a throwaway consumer
# project builds against it FROM THE REGISTRY. This catches corruption (e.g. the
# multipart-upload bug) and API mismatches that per-artifact byte checks and the
# local-AAR sample builds cannot. Skip with CEFRIUM_SKIP_SMOKE_TEST=1 (e.g. when
# Gradle/Android SDK are unavailable on the publishing host).
if [ "${CEFRIUM_SKIP_SMOKE_TEST:-0}" = "1" ]; then
  echo "Smoke test skipped (CEFRIUM_SKIP_SMOKE_TEST=1). Run it before announcing:"
  echo "  $SCRIPT_DIR/test-published-release.sh $VERSION"
else
  echo "Running consumer smoke test (registry-only build)..."
  "$SCRIPT_DIR/test-published-release.sh" "$VERSION"
fi
echo ""
echo "Done — $VERSION published AND verified consumable."
echo ""
echo "Usage in build.gradle:"
echo '  maven { url "https://codeberg.org/api/packages/cefrium/maven" }'
echo "  id 'com.cefrium' version '$VERSION'"
echo "  implementation 'com.cefrium:cefrium-sdk:$VERSION'"
echo ""
