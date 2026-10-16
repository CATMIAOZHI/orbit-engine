#!/bin/bash
# release-play.sh -- rebuild every consumer against the freshly published SDK and
# upload the browser apps to Google Play. The `play` phase of cut-release.sh.
#
# WHY A PHASE AND NOT A RUNBOOK STEP: the three things that have to happen after
# an SDK release -- pin the new coordinate, bump the app version, rebuild and
# upload -- were hand-run, and each has already gone wrong on its own. Browser
# 0.1.18 shipped Chromium 149 because `cefriumVersion` was left pinned to 0.5.9,
# and a forgotten versionCode bump is rejected by Play only after the whole AAB
# has uploaded. All three are mechanical, so they belong in a script.
#
# WHAT IT DOES, in order:
#   1. pin     <module>/gradle.properties cefriumVersion=<version>, both apps
#   2. bump    versionCode +1 and versionName patch +1 in each app/build.gradle
#   3. samples rebuild EVERY sample against the new fat AAR -- they are the
#              consumers, so a sample that stops compiling is an SDK API break
#              and must stop the release, not be discovered by a user
#   4. upload  per app: scripts/publish-play.sh <track> <module>, with
#              EXPECT_SDK set so a stale pin fails before anything is uploaded.
#              That script also asks Play whether the versionCode is free
#              BEFORE building, because Play rejects a duplicate only after the
#              whole bundle has been sent.
#   5. record  commit the pin + bump and push, so the repo states what is on
#              Play. After the upload: a failed upload must not leave a commit
#              for something that never shipped.
#
# Backing out a bad release: PLAY-PUBLIC-TRACK-RUNBOOK.md section 5. Short
# version -- a versionCode on Play is permanent and never reusable, so the only
# real options are halting a staged rollout or rolling forward.
#
# Usage:  release-play.sh <version> [track]
#           track = internal (default) | alpha | beta | production
#         CEFRIUM_PLAY_MODULES="cefrium-browser compose-browser"   (override)
#         CEFRIUM_PLAY_SKIP_SAMPLES=1   skip step 3 (only when just re-uploading)
#         CEFRIUM_VERSION_NAME=0.2.0    set the browser versionName explicitly
#         CEFRIUM_YES=1                 pre-authorize the upload gate
#
# NOT AUTOMATED, deliberately -- see PLAY-PUBLIC-TRACK-RUNBOOK.md:
#   - the FIRST AAB of a new app must be uploaded by hand before the API works;
#   - choosing the track, the listing copy and the staged-rollout percentage;
#   - the 12-testers-for-14-days closed-test gate for production access.
set -euo pipefail

VERSION="${1:?usage: release-play.sh <version> [track] [--dry-run]}"
TRACK="${2:-internal}"
# --dry-run does everything up to the upload: pin, bump, rebuild every sample.
# That is the half worth rehearsing -- it is where a broken public API or a
# stale pin shows up -- and it touches nothing outside this machine.
DRY=0
for a in "$@"; do [ "$a" = "--dry-run" ] && DRY=1; done
[ "$TRACK" = "--dry-run" ] && TRACK="internal"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"              # chromium/src
ROOT="$(cd "$SRC/../.." && pwd)"                    # cef-dev
BROWSER_REPO="$ROOT/cefrium-browser"
SAMPLE_REPO="$ROOT/cefrium-sample"
FAT_AAR="$SRC/out/release/cefrium-sdk.aar"
MODULES="${CEFRIUM_PLAY_MODULES:-cefrium-browser compose-browser}"

export JAVA_HOME="${JAVA_HOME:-$SRC/third_party/jdk/current}"
export ANDROID_HOME="${ANDROID_HOME:-$SRC/third_party/android_sdk/public}"

gate() {
  local what="$1"
  if [ "${CEFRIUM_YES:-0}" = "1" ]; then
    echo "[gate] $what -- auto-confirmed (CEFRIUM_YES=1)"; return
  fi
  echo; echo "*** GOES OUT TO USERS: $what"
  if [ ! -t 0 ]; then
    echo "    No terminal on stdin, so this gate cannot be answered here."
    echo "    Re-run from a terminal, or set CEFRIUM_YES=1 to pre-authorize."
    echo "Aborted at: $what"; exit 1
  fi
  printf "    Type the version (%s) to proceed, anything else to abort: " "$VERSION"
  read -r ans
  [ "$ans" = "$VERSION" ] || { echo "Aborted at: $what"; exit 1; }
}

echo "============================================================"
echo "  PLAY: SDK $VERSION -> track '$TRACK'"
echo "============================================================"

[ -d "$BROWSER_REPO" ] || { echo "ERROR: no browser repo at $BROWSER_REPO"; exit 1; }

# ---- 1. pin the SDK coordinate -------------------------------------------
for m in $MODULES; do
  GP="$BROWSER_REPO/$m/gradle.properties"
  [ -f "$GP" ] || { echo "ERROR: $GP not found"; exit 1; }
  perl -0777 -i -pe "s{^cefriumVersion=.*\$}{cefriumVersion=$VERSION}m" "$GP"
  grep -q "^cefriumVersion=$VERSION\$" "$GP" \
    || { echo "ERROR: could not pin cefriumVersion in $GP"; exit 1; }
  echo ">>> $m pinned to cefrium-sdk:$VERSION"
done

# ---- 2. bump the app version ---------------------------------------------
# Play rejects a versionCode that already exists, and it rejects it only after
# the upload, so get it right before spending the bandwidth.
#
# IDEMPOTENT. A re-run after a failed upload must not bump again: that silently
# inflates the versionCode every retry and makes the script unsafe to re-run,
# which is the main thing keeping this phase out of the unattended cut. Each
# module records the SDK version it was last prepared for.
for m in $MODULES; do
  BG="$BROWSER_REPO/$m/app/build.gradle"
  GP="$BROWSER_REPO/$m/gradle.properties"
  [ -f "$BG" ] || { echo "ERROR: $BG not found"; exit 1; }
  if grep -q "^cefriumPreparedFor=$VERSION\$" "$GP" 2>/dev/null; then
    echo ">>> $m already prepared for $VERSION ($(grep -oE '^\s*versionName\s+"[^"]+"' "$BG" | grep -oE '"[^"]+"' | tr -d '"')) -- not bumping again"
    continue
  fi
  OLD_CODE=$(grep -oE '^\s*versionCode\s+[0-9]+' "$BG" | grep -oE '[0-9]+' | head -1)
  OLD_NAME=$(grep -oE '^\s*versionName\s+"[^"]+"' "$BG" | grep -oE '"[^"]+"' | tr -d '"' | head -1)
  [ -n "$OLD_CODE" ] && [ -n "$OLD_NAME" ] \
    || { echo "ERROR: could not read versionCode/versionName from $BG"; exit 1; }
  NEW_CODE=$((OLD_CODE + 1))
  if [ -n "${CEFRIUM_VERSION_NAME:-}" ]; then
    NEW_NAME="$CEFRIUM_VERSION_NAME"
  else
    NEW_NAME="$(echo "$OLD_NAME" | awk -F. '{$NF=$NF+1; print $1"."$2"."$NF}' OFS=.)"
  fi
  perl -0777 -i -pe "s{^(\s*versionCode\s+)[0-9]+\$}{\${1}$NEW_CODE}m" "$BG"
  perl -0777 -i -pe "s{^(\s*versionName\s+\")[^\"]+(\")\$}{\${1}$NEW_NAME\${2}}m" "$BG"
  grep -qE "^\s*versionCode\s+$NEW_CODE\$" "$BG" \
    && grep -qE "^\s*versionName\s+\"$NEW_NAME\"\$" "$BG" \
    || { echo "ERROR: version bump did not take in $BG"; exit 1; }
  # Mark it prepared only AFTER the bump is verified, so a crash in between
  # leaves the module unmarked and the next run redoes it rather than skipping.
  if grep -q "^cefriumPreparedFor=" "$GP" 2>/dev/null; then
    perl -0777 -i -pe "s{^cefriumPreparedFor=.*\$}{cefriumPreparedFor=$VERSION}m" "$GP"
  else
    printf 'cefriumPreparedFor=%s\n' "$VERSION" >> "$GP"
  fi
  echo ">>> $m $OLD_NAME/$OLD_CODE -> $NEW_NAME/$NEW_CODE"
done

# ---- 3. the samples are the API gate -------------------------------------
if [ "${CEFRIUM_PLAY_SKIP_SAMPLES:-0}" != "1" ]; then
  [ -f "$FAT_AAR" ] || { echo "ERROR: no fat AAR at $FAT_AAR -- run the merge phase first"; exit 1; }
  FAILED=""
  for d in "$SAMPLE_REPO"/*/; do
    [ -f "$d/gradlew" ] || continue
    name="$(basename "$d")"
    # Every sample consumes the SDK as app/libs/cefrium-sdk.aar.
    [ -d "$d/app/libs" ] || continue
    cp "$FAT_AAR" "$d/app/libs/cefrium-sdk.aar"
    echo ">>> building sample $name against $VERSION"
    if ( cd "$d" && ./gradlew assembleDebug --no-daemon -q >/dev/null 2>&1 ); then
      echo "    OK"
    else
      echo "    FAILED"
      FAILED="$FAILED $name"
    fi
  done
  if [ -n "$FAILED" ]; then
    echo
    echo "ERROR: samples failed to build against $VERSION:$FAILED"
    echo "       A sample is a consumer. If it no longer compiles, the SDK broke"
    echo "       its public API -- fix that before publishing, do not ship it."
    exit 1
  fi
fi

# ---- 4. build + upload ----------------------------------------------------
if [ "$DRY" = "1" ]; then
  echo
  echo "[dry-run] stopping before the upload. Pins, version bumps and the"
  echo "          sample rebuild above are done and are what a real run would"
  echo "          have produced. To ship:"
  echo "            cef/cefrium_sdk/release-play.sh $VERSION $TRACK"
  exit 0
fi

gate "upload the browser app(s) to the Play '$TRACK' track"
for m in $MODULES; do
  echo
  echo ">>> Play upload: $m -> $TRACK"
  EXPECT_SDK="$VERSION" "$BROWSER_REPO/scripts/publish-play.sh" "$TRACK" "$m"
done

echo
echo "OK: $MODULES on Play '$TRACK', built against cefrium-sdk:$VERSION."

# ---- 5. record what shipped ----------------------------------------------
# The pin and the version bump are the repo's statement of what is on Play, and
# leaving them uncommitted is how the next run reads a versionCode that is
# already uploaded. Committed AFTER the upload, so a failed upload leaves the
# edits in the working tree to retry rather than a commit for something that
# never shipped. Named paths, never `git add -A`: this phase runs in a tree that
# may hold unrelated work.
PATHS=""
for m in $MODULES; do
  for f in "$m/gradle.properties" "$m/app/build.gradle"; do
    [ -f "$BROWSER_REPO/$f" ] && PATHS="$PATHS $f"
  done
done
if [ -n "$PATHS" ] && ! git -C "$BROWSER_REPO" diff --quiet -- $PATHS; then
  echo ">>> recording the pin and version bumps"
  NAMES="$(for m in $MODULES; do
    printf '%s %s/%s, ' "$m" \
      "$(grep -oE '^\s*versionName\s+"[^"]+"' "$BROWSER_REPO/$m/app/build.gradle" | grep -oE '"[^"]+"' | tr -d '"' | head -1)" \
      "$(grep -oE '^\s*versionCode\s+[0-9]+' "$BROWSER_REPO/$m/app/build.gradle" | grep -oE '[0-9]+' | head -1)"
  done | sed 's/, $//')"
  git -C "$BROWSER_REPO" add -- $PATHS
  git -C "$BROWSER_REPO" commit -q -m "Ship on Play against cefrium-sdk $VERSION: $NAMES"
  "$SCRIPT_DIR/push.sh" browser
else
  echo ">>> pin and version bumps already committed -- nothing to record"
fi
