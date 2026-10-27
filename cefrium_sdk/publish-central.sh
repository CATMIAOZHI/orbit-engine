#!/bin/bash
# Publish the Cefrium SDK to Maven Central via the Sonatype Central Portal.
#
#   ./publish-central.sh <version> [--dry-run|--publish]
#
# WHY Central (vs the Codeberg Maven registry): Central has no per-host storage
# quota on us and NEVER deletes a published version, so we stop pruning and the
# "pinned version 404" that broke our first external adopter disappears. Consumers
# also get zero-config resolution -- mavenCentral() is already in every build, so
# no `maven { url ... }` line is needed. The source tree stays on Codeberg; a
# published LGPL binary carries nothing sovereignty-sensitive.
#
# Modes:
#   (default)    Assemble + sign the bundle, upload as USER_MANAGED. Central
#                validates it and then WAITS -- nothing is public until you press
#                Publish in the Portal (or re-run with --publish). Safe default:
#                a Central release is immutable, so we never auto-release.
#   --dry-run    Assemble + sign + validate the bundle locally. No network.
#                Set CEFRIUM_SKIP_SIGN=1 to check only the layout (no GPG/hardware signing key).
#   --publish    After Central reports VALIDATED, release it (irreversible).
#   --release <deployment-id>...
#                Release deployments that are ALREADY uploaded and VALIDATED,
#                without re-uploading the bundles (irreversible). Use this when a
#                cut uploaded as USER_MANAGED and you then decide to publish: the
#                alternative, re-running with --publish, would re-upload ~500 MB
#                and leave the first set of deployments orphaned.
#
# One-time prerequisites (NOT done by this script):
#   1. Namespace com.cefrium verified in the Central Portal via a DNS TXT record on
#      cefrium.com (Portal -> Namespaces -> Add -> follow the TXT instructions).
#   2. A Portal user token: Portal -> Account -> Generate User Token. Store the
#      "username:password" pair on ONE line in the file CEFRIUM_CENTRAL_TOKEN
#      points at (chmod 600). Not hardcoded: see your local setup notes.
#   3. The publishing GPG key's PUBLIC half distributed to a keyserver Central
#      checks (keys.openpgp.org): gpg --send-keys <fpr>. Central verifies every .asc.
#   4. hardware signing key PIN primed so batch signing does not hang (your key-priming script).
#
# Per-release prerequisites (built by cut-release.sh, same as publish-maven.sh):
#   - out/release/cefrium-sdk.aar and cefrium-sdk-libre.aar (build + merge-multiabi)
#   - cef/cefrium_compose/build/outputs/aar/cefrium-compose-release.aar
#   - cef/cefrium_sdk/gradle-plugin/build/libs/gradle-plugin.jar

set -euo pipefail

VERSION="${1:?Usage: $0 <version> [--dry-run|--publish]}"
shift || true
MODE="upload"            # upload | dry-run | publish
RELEASE_IDS=()
for arg in "$@"; do
  case "$arg" in
    --dry-run) MODE="dry-run" ;;
    --publish) MODE="publish" ;;
    --release) MODE="release" ;;
    *) if [ "$MODE" = "release" ]; then RELEASE_IDS+=("$arg")
       else echo "Unknown arg: $arg" >&2; exit 2; fi ;;
  esac
done

# --release short-circuits everything else: it needs no artifacts, only the token
# and the deployment ids. DEPLOY_URL was declared for this and never wired up.
if [ "$MODE" = "release" ]; then
  [ "${#RELEASE_IDS[@]}" -gt 0 ] || { echo "usage: $0 <version> --release <deployment-id>..." >&2; exit 2; }
TOKEN_FILE="${CEFRIUM_CENTRAL_TOKEN:?set CEFRIUM_CENTRAL_TOKEN to the file holding your Maven Central token (see your local setup notes)}"
  [ -f "$TOKEN_FILE" ] || { echo "ERROR: $TOKEN_FILE missing" >&2; exit 2; }
  AUTH="Bearer $(tr -d '\n' < "$TOKEN_FILE" | base64 | tr -d '\n')"
  CFG="$(mktemp "${TMPDIR:-/tmp}/cefrium-central-rel.XXXXXX")"; chmod 600 "$CFG"
  printf 'header = "Authorization: %s"\n' "$AUTH" > "$CFG"
  trap 'rm -f "$CFG"' EXIT
  echo ""
  echo "Releasing ${#RELEASE_IDS[@]} VALIDATED deployment(s) to Maven Central -- IRREVERSIBLE."
  rc=0
  for id in "${RELEASE_IDS[@]}"; do
    code="$(curl -s -o /tmp/cefrium-rel-body -w '%{http_code}' -K "$CFG" \
             -X POST "https://central.sonatype.com/api/v1/publisher/deployment/$id")"
    if [ "$code" = "200" ] || [ "$code" = "204" ]; then
      echo "  released $id"
    else
      echo "  FAILED  $id (HTTP $code): $(head -c 300 /tmp/cefrium-rel-body)"
      rc=1
    fi
    rm -f /tmp/cefrium-rel-body
  done
  echo ""
  [ "$rc" = "0" ] && echo "Done. Search propagation to Central can take a few hours." \
                  || echo "One or more releases failed; check https://central.sonatype.com/publishing/deployments"
  exit "$rc"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RELEASE_DIR="$(cd "$SCRIPT_DIR/../../out/release" 2>/dev/null && pwd || true)"
COMPOSE_AAR="$CEF_DIR/cefrium_compose/build/outputs/aar/cefrium-compose-release.aar"
UNIFIEDPUSH_AAR="$CEF_DIR/cefrium_unifiedpush/build/outputs/aar/cefrium-unifiedpush-release.aar"
PLUGIN_JAR="$SCRIPT_DIR/gradle-plugin/build/libs/gradle-plugin.jar"

# Source roots for the -sources.jar of each coordinate.
SDK_SRC="$SCRIPT_DIR/java/src"
COMPOSE_SRC="$CEF_DIR/cefrium_compose/java/src"
UNIFIEDPUSH_SRC="$CEF_DIR/cefrium_unifiedpush/java/src"
PLUGIN_SRC="$SCRIPT_DIR/gradle-plugin/src/main/groovy"
# Existing generated API docs (jarred as -javadoc.jar when present); a minimal
# placeholder pointing at cefrium.com/docs is used when a dir is absent.
WEB_DOCS="$CEF_DIR/../cefrium-web/docs"

# The engine this release embeds, read from the SDK's own constant so the POM can
# never disagree with the artifact. Published as a POM property (machine-readable)
# and appended to the SDK descriptions (human-readable in the Central UI): before
# this, nothing in the published metadata said WHICH Chromium a version carried,
# so an evaluator had to download 250 MB to find out. Deliberately NOT a CPE: the
# Chrome CPE matches at milestone level, so every 154.x scores the same ~122 CVEs
# whether fully patched or not, which would misrepresent the artifact permanently.
CHROMIUM_VERSION="$(grep -oE 'CHROMIUM_VERSION = "[0-9.]+"' \
  "$SCRIPT_DIR/java/src/com/cefrium/Cefrium.java" | grep -oE '[0-9.]+')"
[ -n "$CHROMIUM_VERSION" ] || { echo "ERROR: cannot read CHROMIUM_VERSION" >&2; exit 2; }


TOKEN_FILE="${CEFRIUM_CENTRAL_TOKEN:?set CEFRIUM_CENTRAL_TOKEN to the file holding your Maven Central token (see your local setup notes)}"
GPG_KEY="${CEFRIUM_GPG_KEY:-2A840ED00DE669F4219B8B3302A79E3B82151695}"
UPLOAD_URL="https://central.sonatype.com/api/v1/publisher/upload"
STATUS_URL="https://central.sonatype.com/api/v1/publisher/status"
DEPLOY_URL="https://central.sonatype.com/api/v1/publisher/deployment"

SKIP_SIGN="${CEFRIUM_SKIP_SIGN:-0}"
[ "$MODE" = "dry-run" ] || SKIP_SIGN=0   # a real upload MUST be signed

echo ""
echo "Cefrium -> Maven Central (Sonatype Central Portal)   v$VERSION   [$MODE]"
echo "============================================================================"

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
have_artifact=0
declare -A AAR
for v in cefrium-sdk cefrium-sdk-libre; do
  f="$RELEASE_DIR/$v.aar"
  if [ -n "$RELEASE_DIR" ] && [ -f "$f" ]; then AAR[$v]="$f"; have_artifact=1; fi
done
if [ "$have_artifact" != 1 ]; then
  echo "ERROR: no cefrium-sdk.aar / cefrium-sdk-libre.aar in '${RELEASE_DIR:-<out/release missing>}'." >&2
  echo "       Build them first (build-all-variants.sh -> merge-multiabi.sh)." >&2
  exit 1
fi

if [ "$SKIP_SIGN" != 1 ]; then
  if ! gpg --list-secret-keys "$GPG_KEY" >/dev/null 2>&1; then
    echo "ERROR: GPG secret key $GPG_KEY not available. Set CEFRIUM_GPG_KEY or import it." >&2
    exit 1
  fi
  # Surface a cold hardware signing key (batch sign hangs) early, with a bounded test sign.
  echo "Preflight: test-signing with $GPG_KEY (prime the hardware signing key if this stalls)..."
  if ! printf 'cefrium' | timeout 60 gpg --batch --yes --armor --detach-sign \
        --local-user "$GPG_KEY" -o /dev/null - >/dev/null 2>&1; then
    echo "ERROR: GPG test sign failed or timed out. Prime the hardware signing key" >&2
    echo "       (\your key-priming script) and retry, or use --dry-run CEFRIUM_SKIP_SIGN=1." >&2
    exit 1
  fi
  echo "  test sign OK"
fi

if [ "$MODE" != "dry-run" ]; then
  [ -f "$TOKEN_FILE" ] || { echo "ERROR: token not found at $TOKEN_FILE (username:password)." >&2; exit 1; }
fi

# ---------------------------------------------------------------------------
# Staging tree (Maven repository layout that becomes the upload bundle)
# ---------------------------------------------------------------------------
WORK="$(mktemp -d "${TMPDIR:-/tmp}/cefrium-central.XXXXXX")"
REPO="$WORK/repo"
trap 'rm -rf "$WORK"' EXIT
GROUP_PATH="com/cefrium"

# sign_and_sum <file>: create <file>.asc (unless skipping) + <file>.md5 + <file>.sha1
# Central requires md5 and sha1; the .asc/.md5/.sha1 side files themselves get none.
sign_and_sum() {
  local f="$1"
  if [ "$SKIP_SIGN" != 1 ]; then
    gpg --batch --yes --armor --detach-sign --local-user "$GPG_KEY" -o "$f.asc" "$f"
  fi
  md5sum "$f"  | cut -d' ' -f1 > "$f.md5"
  sha1sum "$f" | cut -d' ' -f1 > "$f.sha1"
}

# place <artifact> <version> <src-file> <ext> [classifier]
# Copies src-file into the Maven layout as <artifact>-<version>[-<classifier>].<ext>,
# then signs+sums. Classifiers use the DASH form (-sources.jar / -javadoc.jar) that
# Maven Central requires -- a dot form (.sources.jar) is not recognized as the
# classifier and fails validation ("Sources/Javadocs not found in entries").
place() {
  local artifact="$1" ver="$2" src="$3" ext="$4" classifier="${5:-}"
  local dir="$REPO/$GROUP_PATH/$artifact/$ver"
  mkdir -p "$dir"
  local name="$artifact-$ver"; [ -n "$classifier" ] && name="$name-$classifier"
  local dest="$dir/$name.$ext"
  cp "$src" "$dest"
  sign_and_sum "$dest"
}

# write_pom <artifact> <packaging> <name> <description> <deps-xml>
write_pom() {
  local artifact="$1" packaging="$2" name="$3" desc="$4" deps="$5"
  local dir="$REPO/$GROUP_PATH/$artifact/$VERSION"
  mkdir -p "$dir"
  local pom="$dir/$artifact-$VERSION.pom"
  cat > "$pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.cefrium</groupId>
  <artifactId>$artifact</artifactId>
  <version>$VERSION</version>
  <packaging>$packaging</packaging>
  <name>$name</name>
  <description>$desc</description>
  <properties>
    <cefrium.chromium.version>$CHROMIUM_VERSION</cefrium.chromium.version>
  </properties>
  <url>https://cefrium.com</url>
  <licenses>
    <license>
      <name>LGPL-3.0-or-later</name>
      <url>https://www.gnu.org/licenses/lgpl-3.0.html</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <developers>
    <developer>
      <id>vejeta</id>
      <name>Juan Manuel Méndez Rey</name>
      <url>https://cefrium.com</url>
    </developer>
  </developers>
  <scm>
    <connection>scm:git:https://codeberg.org/cefrium/cef-android.git</connection>
    <developerConnection>scm:git:ssh://git@codeberg.org/cefrium/cef-android.git</developerConnection>
    <url>https://codeberg.org/cefrium/cef-android</url>
  </scm>
$deps
</project>
EOF
  sign_and_sum "$pom"
}

# make_sources_jar <artifact> <src-dir>
make_sources_jar() {
  local artifact="$1" srcdir="$2"
  local out="$WORK/$artifact-$VERSION-sources.jar"
  if [ -d "$srcdir" ]; then
    ( cd "$srcdir" && zip -q -r "$out" . )
  else
    printf 'See https://cefrium.com for sources.\n' > "$WORK/README-sources.txt"
    ( cd "$WORK" && zip -q "$out" README-sources.txt && rm -f README-sources.txt )
  fi
  place "$artifact" "$VERSION" "$out" "jar" "sources"
}

# make_javadoc_jar <artifact> <docs-dir-or-empty>
# Central requires a signed -javadoc.jar to exist; it does not inspect its depth.
# Full rendered API docs live at cefrium.com/docs, so by default we ship a small
# pointer jar (keeps each deployment lean -- the real AARs are already ~240 MB).
# Opt in to jarring the full generated docs with CEFRIUM_FULL_JAVADOC=1.
make_javadoc_jar() {
  local artifact="$1" docdir="$2"
  local out="$WORK/$artifact-$VERSION-javadoc.jar"
  if [ "${CEFRIUM_FULL_JAVADOC:-0}" = 1 ] && [ -n "$docdir" ] && [ -d "$docdir" ]; then
    ( cd "$docdir" && zip -q -r "$out" . )
  else
    printf '<!doctype html><meta charset=utf-8><title>Cefrium API</title>\n<p>Full API documentation: <a href="https://cefrium.com/docs">cefrium.com/docs</a></p>\n' \
      > "$WORK/index.html"
    ( cd "$WORK" && zip -q "$out" index.html && rm -f index.html )
  fi
  place "$artifact" "$VERSION" "$out" "jar" "javadoc"
}

# ---------------------------------------------------------------------------
# Coordinates
# ---------------------------------------------------------------------------
# pom-deps.txt plus the Maven libraries the AAR does not vendor, read from the
# AAR being published so the POM matches its bytes (maven_vendored.py).
sdk_deps() {
  local libs
  libs="$(python3 "$SCRIPT_DIR/maven_vendored.py" --pom "$1")" || return 1
  printf '  <dependencies>\n%s\n  </dependencies>' "$libs"
}

for v in cefrium-sdk cefrium-sdk-libre; do
  [ -n "${AAR[$v]:-}" ] || { echo "skip $v (not built)"; continue; }
  echo "Staging $v (multi-ABI)..."
  deps="$(sdk_deps "${AAR[$v]}")" || { echo "ERROR: no dependency list in ${AAR[$v]}" >&2; exit 1; }
  write_pom "$v" "aar" "Cefrium SDK" "CEF (Chromium Embedded Framework) for native Android -- multi-ABI. Embeds Chromium $CHROMIUM_VERSION." "$deps"
  place "$v" "$VERSION" "${AAR[$v]}" "aar"
  make_sources_jar "$v" "$SDK_SRC"
  make_javadoc_jar "$v" "$WEB_DOCS"
done

if [ -f "$COMPOSE_AAR" ]; then
  echo "Staging cefrium-compose..."
  COMPOSE_DEPS='  <dependencies>
    <dependency><groupId>com.cefrium</groupId><artifactId>cefrium-sdk</artifactId><version>'"$VERSION"'</version></dependency>
  </dependencies>'
  write_pom "cefrium-compose" "aar" "Cefrium Compose" "Jetpack Compose wrapper for the Cefrium SDK." "$COMPOSE_DEPS"
  place "cefrium-compose" "$VERSION" "$COMPOSE_AAR" "aar"
  make_sources_jar "cefrium-compose" "$COMPOSE_SRC"
  make_javadoc_jar "cefrium-compose" "$WEB_DOCS/compose"
fi

if [ -f "$UNIFIEDPUSH_AAR" ]; then
  echo "Staging cefrium-unifiedpush..."
  UNIFIEDPUSH_DEPS='  <dependencies>
    <dependency><groupId>com.cefrium</groupId><artifactId>cefrium-sdk</artifactId><version>'"$VERSION"'</version></dependency>
    <dependency><groupId>org.unifiedpush.android</groupId><artifactId>connector</artifactId><version>3.3.5</version>
      <exclusions><exclusion><groupId>com.google.crypto.tink</groupId><artifactId>tink</artifactId></exclusion></exclusions></dependency>
    <dependency><groupId>com.google.crypto.tink</groupId><artifactId>tink-android</artifactId><version>1.23.0</version></dependency>
  </dependencies>'
  write_pom "cefrium-unifiedpush" "aar" "Cefrium UnifiedPush" "Web Push through UnifiedPush instead of Firebase Cloud Messaging, for the Cefrium SDK." "$UNIFIEDPUSH_DEPS"
  place "cefrium-unifiedpush" "$VERSION" "$UNIFIEDPUSH_AAR" "aar"
  make_sources_jar "cefrium-unifiedpush" "$UNIFIEDPUSH_SRC"
  make_javadoc_jar "cefrium-unifiedpush" "$WEB_DOCS/unifiedpush"
fi

if [ -f "$PLUGIN_JAR" ]; then
  echo "Staging cefrium-gradle-plugin + marker..."
  write_pom "cefrium-gradle-plugin" "jar" "Cefrium Gradle Plugin" "Generates R.java for Chromium packages so a consumer APK resolves org.chromium.*.R." ""
  place "cefrium-gradle-plugin" "$VERSION" "$PLUGIN_JAR" "jar"
  make_sources_jar "cefrium-gradle-plugin" "$PLUGIN_SRC"
  make_javadoc_jar "cefrium-gradle-plugin" ""
  # Plugin marker (pom-only): lets `plugins { id 'com.cefrium' version 'X' }`
  # resolve from Central. pom packaging is exempt from sources/javadoc.
  MARKER_DEPS='  <dependencies>
    <dependency><groupId>com.cefrium</groupId><artifactId>cefrium-gradle-plugin</artifactId><version>'"$VERSION"'</version></dependency>
  </dependencies>'
  write_pom "com.cefrium.gradle.plugin" "pom" "Cefrium Gradle Plugin Marker" "Gradle plugin marker for com.cefrium." "$MARKER_DEPS"
else
  echo "WARNING: gradle-plugin.jar not found -- skipping plugin (id 'com.cefrium' won't resolve)."
fi

# ---------------------------------------------------------------------------
# Local validation of the staged tree
# ---------------------------------------------------------------------------
echo ""
echo "Staged tree:"
( cd "$REPO" && find . -type f | sort | sed 's/^/  /' )

echo ""
echo "Validating layout..."
prob=0
while IFS= read -r primary; do
  for req in md5 sha1; do
    [ -f "$primary.$req" ] || { echo "  MISSING $primary.$req"; prob=1; }
  done
  if [ "$SKIP_SIGN" != 1 ]; then
    [ -f "$primary.asc" ] || { echo "  MISSING $primary.asc"; prob=1; }
  fi
  # AAR/JAR must be a real ZIP.
  case "$primary" in
    *.aar|*.jar) [ "$(xxd -p -l2 "$primary" 2>/dev/null)" = "504b" ] || { echo "  NOT-A-ZIP $primary"; prob=1; } ;;
  esac
done < <(find "$REPO" -type f ! -name '*.asc' ! -name '*.md5' ! -name '*.sha1')
[ "$prob" = 0 ] && echo "  layout OK" || { echo "Layout validation FAILED." >&2; exit 1; }

# One bundle per coordinate. The two ~240 MB SDK variants ship as independent
# deployments so no single upload approaches the Portal's bundle-size ceiling;
# each coordinate is validated (and released) on its own.
mapfile -t COORD_DIRS < <(find "$REPO/$GROUP_PATH" -mindepth 1 -maxdepth 1 -type d | sort)
declare -A BUNDLES
echo ""
echo "Per-coordinate bundles:"
for d in "${COORD_DIRS[@]}"; do
  art="$(basename "$d")"
  b="$WORK/bundle-$art.zip"
  ( cd "$REPO" && zip -q -r "$b" "$GROUP_PATH/$art" )
  BUNDLES[$art]="$b"
  echo "  $art: $(du -h "$b" | cut -f1)"
done

if [ "$MODE" = "dry-run" ]; then
  for art in "${!BUNDLES[@]}"; do
    cp "${BUNDLES[$art]}" "$RELEASE_DIR/cefrium-central-$art-$VERSION.zip" 2>/dev/null \
      && echo "Dry-run: kept $RELEASE_DIR/cefrium-central-$art-$VERSION.zip"
  done
  echo "Dry-run complete (no upload)."
  [ "$SKIP_SIGN" = 1 ] && echo "NOTE: signatures skipped (CEFRIUM_SKIP_SIGN=1) -- not a publishable bundle."
  exit 0
fi

# ---------------------------------------------------------------------------
# Upload to the Central Portal
# ---------------------------------------------------------------------------
AUTH="Bearer $(tr -d '\n' < "$TOKEN_FILE" | base64 | tr -d '\n')"
CURL_CFG="$(mktemp "${TMPDIR:-/tmp}/cefrium-central-auth.XXXXXX")"; chmod 600 "$CURL_CFG"
printf 'header = "Authorization: %s"\n' "$AUTH" > "$CURL_CFG"
trap 'rm -rf "$WORK"; rm -f "$CURL_CFG"' EXIT

PUB_TYPE="USER_MANAGED"; [ "$MODE" = "publish" ] && PUB_TYPE="AUTOMATIC"

# upload_and_poll <bundle> <deployment-name> -> 0 if VALIDATED/PUBLISHED, else 1
upload_and_poll() {
  local bundle="$1" name="$2" id resp state=""
  # Keep the HTTP status and body: a bare "upload FAILED" once left a release
  # half-published with no way to tell why.
  local body="$WORK/upload-$name.body" code
  code="$(curl -s -o "$body" -w '%{http_code}' -K "$CURL_CFG" -F "bundle=@$bundle" \
        "$UPLOAD_URL?name=$name&publishingType=$PUB_TYPE")"
  if [ "$code" != 201 ]; then
    echo "  $name: upload FAILED (HTTP ${code:-none}): $(head -c 400 "$body" 2>/dev/null)"
    return 1
  fi
  id="$(cat "$body")"
  echo "  $name -> deployment $id"
  local i
  for i in $(seq 1 90); do
    sleep 10
    resp="$(curl -sf -K "$CURL_CFG" -X POST "$STATUS_URL?id=$id" || true)"
    state="$(printf '%s' "$resp" | grep -oE '"deploymentState"[[:space:]]*:[[:space:]]*"[A-Z_]+"' | grep -oE '[A-Z_]+"$' | tr -d '"')"
    case "$state" in
      VALIDATED|PUBLISHING|PUBLISHED) break ;;
      FAILED) echo "  $name: FAILED"; printf '%s\n' "$resp"; return 1 ;;
    esac
  done
  echo "  $name: state=$state (id $id)"
  case "$state" in VALIDATED|PUBLISHING|PUBLISHED) return 0 ;; *) return 1 ;; esac
}

echo ""
echo "Uploading ${#BUNDLES[@]} coordinate(s) to Central ($PUB_TYPE)..."
fail=0
# CEFRIUM_CENTRAL_ONLY="cefrium-sdk cefrium-sdk-libre": upload only these, for
# finishing a release some of whose coordinates are already published.
for art in $(printf '%s\n' "${!BUNDLES[@]}" | sort); do
  if [ -n "${CEFRIUM_CENTRAL_ONLY:-}" ] && [[ " $CEFRIUM_CENTRAL_ONLY " != *" $art "* ]]; then
    echo "  $art: skipped (CEFRIUM_CENTRAL_ONLY)"; continue
  fi
  upload_and_poll "${BUNDLES[$art]}" "cefrium-$art-$VERSION" || fail=1
done

echo ""
if [ "$fail" != 0 ]; then
  echo "One or more coordinates did not validate/release -- see above and"
  echo "  https://central.sonatype.com/publishing/deployments"
  exit 1
fi
if [ "$PUB_TYPE" = "AUTOMATIC" ]; then
  echo "All coordinates released to Central. Search propagation can take a few hours."
else
  echo "All coordinates VALIDATED but NOT released (USER_MANAGED) -- nothing public yet."
  echo "Review and press Publish per deployment in the Portal:"
  echo "  https://central.sonatype.com/publishing/deployments"
  echo "or re-run to release automatically:  $0 $VERSION --publish"
  echo "(A Central release is IMMUTABLE -- verify each deployment first.)"
fi
echo ""
echo "Done."
