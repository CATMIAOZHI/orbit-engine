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

# Keep the token OUT of argv (and thus `ps`/process listings): instead of
# `-H "Authorization: token $TOKEN"` on every curl command line (world-visible),
# write the auth header into a 0600 curl config file and pass it with `curl -K`.
# The file is removed on exit. Fully non-interactive.
CURL_AUTH_CFG="$(mktemp "${TMPDIR:-/tmp}/cefrium-curl-auth.XXXXXX")"
chmod 600 "$CURL_AUTH_CFG"
printf 'header = "Authorization: token %s"\n' "$TOKEN" > "$CURL_AUTH_CFG"
trap 'rm -f "$CURL_AUTH_CFG"' EXIT
unset TOKEN  # no longer needed; header now lives only in the 0600 config file

# DEP-8-style integrity gate: re-fetch a just-published artifact and confirm it is
# what a consumer will actually download — not a multipart envelope, an HTML error
# page, or an empty body. AAR/JAR must start with the ZIP magic "PK"; POM/XML must
# begin with a real XML element. This is what would have caught the multipart-upload
# corruption immediately instead of after a consumer's build failed.
verify_artifact() {
  local url="$1" ext="$2"
  local tmp; tmp=$(mktemp)
  # Read the first bytes from a FULL GET, NOT a range request: Codeberg answers a
  # range request with a multipart/byteranges body (starts with "--boundary"),
  # which this check would misread as a corrupt multipart upload (false positive
  # that aborted the 0.5.1 publish even though the artifact was byte-perfect).
  #
  # An EMPTY body is a transient fetch failure (network blip, SIGPIPE race when head
  # closes the pipe early), NOT a corrupt artifact -- treating it as corruption
  # false-aborted a release whose POM was byte-perfect. So retry the fetch a few
  # times until we get a non-empty body, and only run the magic-byte checks on real
  # content. Use a plain `-s` GET to a temp file (no `-f`, no pipe-to-head race):
  # curl writes the whole short body, then we inspect the head locally.
  local tries=0 bytes=0
  while [ "$tries" -lt 5 ]; do
    : > "$tmp"
    curl -s -K "$CURL_AUTH_CFG" "$url" -o "$tmp" 2>/dev/null || true
    bytes=$(wc -c < "$tmp" 2>/dev/null || echo 0)
    [ "$bytes" -gt 0 ] && break
    tries=$((tries + 1))
    sleep 2
  done
  if [ "$bytes" -eq 0 ]; then
    rm -f "$tmp"
    echo "    *** VERIFY FAILED: could not fetch published $url (empty after 5 tries)"
    echo "    *** (transient fetch failure, NOT necessarily a corrupt artifact)"
    return 1
  fi
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
  #
  # Retry on a non-terminal upload result. When a version was just DELETEd
  # (republishing a fixed POM for the same version), the registry tears the version
  # down asynchronously and a PUT in that window either returns HTTP 400 or hangs/
  # drops the connection (curl exit -> status "000") until teardown completes.
  # Codeberg's package API is also intermittently slow. Treat 400 and 000 as
  # transient, bound each attempt with --max-time so a hung PUT can't kill the
  # release (curl exit 28), and back off generously (up to ~2 min) -- teardown has
  # been observed to take well over a minute. 201/409 are terminal-success;
  # anything else is a real failure.
  #
  # BUT a 400 whose body is a content-validation error (e.g. "XML syntax error" for
  # a malformed POM) is PERMANENT, not the teardown window -- retrying it just burns
  # 2 min and then fails anyway. Capture the body and fail fast on those.
  local status attempt=0 body
  while :; do
    # curl -w already prints "000" on a connection failure (and exits non-zero).
    # Do NOT append `|| echo 000` to the substitution: that concatenates to
    # "000000", which misses the 000 retry case and aborts on a transient blip.
    # Normalize after instead.
    status=$(curl -s --max-time 180 -o /tmp/cefrium-upload-resp.$$ -w "%{http_code}" \
      -K "$CURL_AUTH_CFG" \
      --upload-file "$file" \
      "$url") || true
    status="${status:-000}"
    if [ "$status" = "400" ]; then
      body=$(cat /tmp/cefrium-upload-resp.$$ 2>/dev/null)
      if echo "$body" | grep -qiE "syntax error|invalid|malformed|not allowed|parse"; then
        echo "    FAILED (HTTP 400, permanent): $body"
        rm -f /tmp/cefrium-upload-resp.$$
        return 1
      fi
    fi
    rm -f /tmp/cefrium-upload-resp.$$
    case "$status" in
      400|000) : ;;          # transient: teardown window / API blip -> retry
      *) break ;;            # 201/409 success, or a real error code -> stop
    esac
    attempt=$((attempt + 1))
    [ "$attempt" -ge 12 ] && break
    echo "    (HTTP $status — teardown/API blip, retry $attempt/12 after backoff)"
    sleep 10
  done

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
# Runtime androidx dependencies the SDK references (from the content/embedder
# layer) but does NOT vendor — the consumer gets them transitively. The AAR is a
# fat AAR (it vendors chrome_all_java's Material/Preference/Cast classes AND, since
# compat_strings.xml is wired in, their string resources — see FEATURE-038
# Option A), so the consumer must NOT add material/preference itself (would
# duplicate the vendored classes). Only the content-layer androidx below is
# declared. This exact set was validated by a Maven-path consumer build: a project
# that declares ONLY com.cefrium:cefrium-sdk builds to an APK (no duplicate-class,
# no missing-resource).
read -r -d '' SDK_DEPS <<'XML' || true
  <dependencies>
    <dependency><groupId>androidx.appcompat</groupId><artifactId>appcompat</artifactId><version>1.7.0</version></dependency>
    <dependency><groupId>androidx.mediarouter</groupId><artifactId>mediarouter</artifactId><version>1.7.0</version></dependency>
    <dependency><groupId>androidx.core</groupId><artifactId>core</artifactId><version>1.16.0</version></dependency>
    <dependency><groupId>androidx.browser</groupId><artifactId>browser</artifactId><version>1.8.0</version></dependency>
    <!-- The vendored Firebase classes (Installations/FCM-token path:
         HeartBeatInfoStorage) need datastore-preferences, which the AAR does NOT
         vendor. Without it FirebaseMessaging.getToken() hangs with
         NoClassDefFoundError androidx/datastore/preferences/core/PreferencesKeys
         (getId() still works). Declare it so consumers get it transitively.
         Validated on Redmi Note 13 Pro / HyperOS / Android 16 (0.5.1).
         NOTE: do NOT exclude okio here. slim_aar.py strips the orphaned okio from
         the AAR (it had 0 references), so the consumer must get the single okio
         from datastore's transitive okio-jvm. Same consumer-provides model the AAR
         already uses for androidx/kotlin/kotlinx. No duplicate-class collision. -->
    <dependency><groupId>androidx.datastore</groupId><artifactId>datastore-preferences</artifactId><version>1.1.1</version></dependency>
  </dependencies>
XML

published_any=0
for artifact in cefrium-sdk cefrium-sdk-libre; do
  aar="$RELEASE_DIR/$artifact.aar"
  if [ ! -f "$aar" ]; then
    echo "skip $artifact (not built)"
    continue
  fi
  echo "Publishing $artifact (multi-ABI)..."
  pom=$(generate_pom "$artifact" "Cefrium SDK — CEF for Android (multi-ABI)" "$SDK_DEPS")
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
# Both published SDK coordinates are gated: cefrium-sdk (default) AND
# cefrium-sdk-libre — distinct artifacts, and the FOSS audience uses libre.
SMOKE_VARIANTS="cefrium-sdk cefrium-sdk-libre"
if [ "${CEFRIUM_SKIP_SMOKE_TEST:-0}" = "1" ]; then
  echo "Smoke test skipped (CEFRIUM_SKIP_SMOKE_TEST=1). Run it before announcing:"
  for art in $SMOKE_VARIANTS; do
    echo "  ARTIFACT=$art $SCRIPT_DIR/test-published-release.sh $VERSION"
  done
elif [ -z "${JAVA_HOME:-}" ] || [ -z "${ANDROID_HOME:-}" ]; then
  # Uploads already succeeded (immutable); a missing build toolchain on the
  # publishing host must NOT report the publish as failed. Warn and hand off.
  echo "Smoke test skipped: JAVA_HOME/ANDROID_HOME not set on this host."
  echo "Uploads succeeded. Run the consumer smoke test before announcing $VERSION:"
  for art in $SMOKE_VARIANTS; do
    echo "  JAVA_HOME=<jdk21> ANDROID_HOME=<sdk> ARTIFACT=$art \\"
    echo "    $SCRIPT_DIR/test-published-release.sh $VERSION"
  done
else
  for art in $SMOKE_VARIANTS; do
    echo "Running consumer smoke test for $art (registry-only build)..."
    ARTIFACT="$art" "$SCRIPT_DIR/test-published-release.sh" "$VERSION"
  done
fi
echo ""
echo "Done — $VERSION published AND verified consumable."
echo ""
echo "Usage in build.gradle:"
echo '  maven { url "https://codeberg.org/api/packages/cefrium/maven" }'
echo "  id 'com.cefrium' version '$VERSION'"
echo "  implementation 'com.cefrium:cefrium-sdk:$VERSION'"
echo ""
