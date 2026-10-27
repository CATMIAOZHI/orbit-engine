#!/bin/bash
# smoke-samples.sh -- build + on-device launch-smoke EVERY cefrium-sample against
# the freshly built SDK AAR, and FAIL if any sample crashes. A release gate: a
# sample that crashes on a new engine (e.g. the 0.4.0 149 flagship crash) must be
# caught BEFORE the immutable Maven/Codeberg publish, not after.
#
# For each sample: stage the fresh AARs into its libs/ -> assembleDebug -> install
# -> pre-grant runtime perms -> launch -> assert no FATAL/NoSuchMethodError/
# tombstone/SIGSEGV in logcat within ~12s.
#
# Usage:  cef/cefrium_sdk/smoke-samples.sh
# Env:    JAVA_HOME (JDK 21), ANDROID_HOME. Needs a connected device.
# Exit:   0 = all samples build + launch clean; 1 = a sample crashed/failed to
#         build, or no device.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"          # chromium/src
ROOT="$(cd "$SRC/../.." && pwd)"                # cef-dev
# CEFRIUM_SDK_AAR / CEFRIUM_COMPOSE_AAR test other bytes than the last build.
SDK_AAR="${CEFRIUM_SDK_AAR:-$SRC/out/release/cefrium-sdk.aar}"      # multi-ABI (post-merge); fallback below
[ -f "$SDK_AAR" ] || SDK_AAR="$SRC/out/Official_android_arm64/cefrium-sdk.aar"
COMPOSE_AAR="${CEFRIUM_COMPOSE_AAR:-$SRC/cef/cefrium_compose/build/outputs/aar/cefrium-compose-release.aar}"
UNIFIEDPUSH_AAR="${CEFRIUM_UNIFIEDPUSH_AAR:-$SRC/cef/cefrium_unifiedpush/build/outputs/aar/cefrium-unifiedpush-release.aar}"
: "${ANDROID_HOME:?set ANDROID_HOME}"; : "${JAVA_HOME:?set JAVA_HOME}"
ADB="$ANDROID_HOME/platform-tools/adb"
AAPT="$(ls "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | head -1)"
[ -f "$SDK_AAR" ] || { echo "FAIL: no SDK AAR at $SDK_AAR -- build it first."; exit 1; }
N="$("$ADB" devices 2>/dev/null | grep -c 'device$')"
[ "$N" -ge 1 ] || { echo "FAIL: no device connected."; exit 1; }
# Two devices without ANDROID_SERIAL: adb refuses and Gradle picks one, so a
# result could come from a device nobody chose. Make the choice explicit.
[ "$N" -eq 1 ] || [ -n "${ANDROID_SERIAL:-}" ] || { echo "FAIL: $N devices attached; set ANDROID_SERIAL."; exit 1; }

# --registry <version>: smoke the PUBLISHED artifact instead of the local build.
#
# This answers a different question from the pre-publish gate. The default mode
# proves "what we just built works"; --registry proves "what an adopter actually
# resolves works", which is not the same claim and has been wrong before -- 0.9.3
# published an arm64-only fat AAR, so every emulator user got an
# UnsatisfiedLinkError at runtime while every local build was green.
#
# The browsers resolve from the registry natively (that is their default, so the
# local-AAR staging below is skipped and cefriumVersion is pinned). The
# file-based samples consume an AAR from libs/, so the published AAR is
# downloaded and staged there -- same bytes, delivered by hand.
REGISTRY_VERSION=""
[ "${1:-}" = "--registry" ] && { REGISTRY_VERSION="${2:?--registry needs a version}"; shift 2; }

SAMPLES="cefrium-browser compose-browser java-sample modern-web-showcase push-demo kiosk-pos kiosk-automotive kiosk-automotive-v2 kiosk-signage loaddata-baseurl osr-hud firebase-coexistence nested-scroll kotlin-arrow-sample profiles network-privacy document-start"
# Samples that write files/verdict.txt: launching without a crash is not enough
# for them, the verdict must read PASS.
VERDICT_SAMPLES="firebase-coexistence nested-scroll profiles network-privacy document-start"
LOGDIR="$ROOT/tmp/smoke-samples"
mkdir -p "$LOGDIR"

if [ -n "$REGISTRY_VERSION" ]; then
  echo "=== REGISTRY MODE: smoking published cefrium-sdk $REGISTRY_VERSION ==="
  PUB="$ROOT/tmp/registry-smoke/$REGISTRY_VERSION"
  mkdir -p "$PUB"
  for art in cefrium-sdk cefrium-compose; do
    f="$PUB/$art.aar"
    if [ ! -s "$f" ]; then
      url="https://repo1.maven.org/maven2/com/cefrium/$art/$REGISTRY_VERSION/$art-$REGISTRY_VERSION.aar"
      echo "  fetching $art $REGISTRY_VERSION from Maven Central"
      curl -sfL -o "$f" "$url" || { echo "FAIL: $art $REGISTRY_VERSION is not on Central ($url)"; exit 1; }
    fi
    echo "  $art: $(du -h "$f" | cut -f1)"
  done
  # Prove the published bytes before installing anything built from them: a
  # missing x86_64 is invisible until an emulator runs it.
  abis="$(unzip -l "$PUB/cefrium-sdk.aar" | grep -oE 'jni/[a-z0-9_-]+/libcef\.so' \
          | sed 's|jni/||;s|/libcef.so||' | sort -u | tr '\n' ' ')"
  echo "  published ABIs: ${abis:-none}"
  case "$abis" in *arm64-v8a*) ;; *) echo "FAIL: published AAR has no arm64-v8a"; exit 1 ;; esac
  case "$abis" in *x86_64*)    ;; *) echo "FAIL: published AAR has no x86_64 -- emulator users get UnsatisfiedLinkError"; exit 1 ;; esac
  # cefrium-unifiedpush exists from 0.15.0 on; without it unifiedpush-demo
  # fails to build, which is the loud answer for a release that should have it.
  up="https://repo1.maven.org/maven2/com/cefrium/cefrium-unifiedpush/$REGISTRY_VERSION/cefrium-unifiedpush-$REGISTRY_VERSION.aar"
  [ -s "$PUB/cefrium-unifiedpush.aar" ] || curl -sfL -o "$PUB/cefrium-unifiedpush.aar" "$up" \
    || { rm -f "$PUB/cefrium-unifiedpush.aar"; echo "  cefrium-unifiedpush: not on Central for $REGISTRY_VERSION"; }
  SDK_AAR="$PUB/cefrium-sdk.aar"
  COMPOSE_AAR="$PUB/cefrium-compose.aar"
  UNIFIEDPUSH_AAR="$PUB/cefrium-unifiedpush.aar"
fi
# The two browsers live in their own repo (cefrium/cefrium-browser); the rest are
# in cefrium-sample.
is_browser() { case "$1" in cefrium-browser|compose-browser) return 0 ;; *) return 1 ;; esac; }
fails=0
echo "=== sample launch-smoke (engine: $(git -C "$SRC" describe --tags 2>/dev/null)) ==="
for s in $SAMPLES; do
  if is_browser "$s"; then d="$ROOT/cefrium-browser/$s"; else d="$ROOT/cefrium-sample/$s"; fi
  [ -d "$d" ] || { echo "  $s: SKIP (missing)"; continue; }
  GA=""
  if is_browser "$s" && [ -n "$REGISTRY_VERSION" ]; then
    # Registry mode: resolve exactly what an adopter resolves. No local staging,
    # no -PcefriumSource=local -- just pin the version and let Gradle fetch it.
    GA="-PcefriumVersion=$REGISTRY_VERSION"
  elif is_browser "$s"; then
    # Browser repo defaults to the registry, but this gate runs BEFORE publish --
    # the new version is not in the registry yet, so build against the fresh
    # local AARs (-PcefriumSource=local).
    mkdir -p "$d/app/libs"
    cp "$SDK_AAR" "$d/app/libs/cefrium-sdk.aar"
    [ -f "$COMPOSE_AAR" ] && cp "$COMPOSE_AAR" "$d/app/libs/cefrium-compose.aar"
    GA="-PcefriumSource=local"
  else
    # Stage by what the build file asks for: libs/ is gitignored, so a fresh
    # clone has no AAR there to replace.
    gf="$(ls "$d"/app/build.gradle "$d"/app/build.gradle.kts 2>/dev/null | head -1)"
    mkdir -p "$d/app/libs"
    grep -q 'libs/cefrium-sdk.aar' "$gf" && cp "$SDK_AAR" "$d/app/libs/cefrium-sdk.aar"
    grep -q 'libs/cefrium-compose.aar' "$gf" && [ -f "$COMPOSE_AAR" ] && cp "$COMPOSE_AAR" "$d/app/libs/cefrium-compose.aar"
    grep -q 'libs/cefrium-unifiedpush.aar' "$gf" && [ -f "$UNIFIEDPUSH_AAR" ] && cp "$UNIFIEDPUSH_AAR" "$d/app/libs/cefrium-unifiedpush.aar"
  fi
  # clean: the AAR under test replaces one built earlier, and AGP's incremental
  # merge fails on entries the old AAR had ("Unknown file: ...properties").
  if ! ( cd "$d" && ./gradlew clean :app:assembleDebug $GA --no-daemon -q ) >"$LOGDIR/$s.log" 2>&1; then
    echo "  $s: BUILD FAIL ($LOGDIR/$s.log)"; fails=$((fails+1)); continue
  fi
  apk="$(find "$d/app/build/outputs/apk/debug" -name '*.apk' 2>/dev/null | head -1)"
  [ -n "$apk" ] || { echo "  $s: no APK"; fails=$((fails+1)); continue; }
  pkg="$("$AAPT" dump badging "$apk" 2>/dev/null | grep -oP "package: name='\K[^']+")"
  act="$("$AAPT" dump badging "$apk" 2>/dev/null | grep -oP "launchable-activity: name='\K[^']+")"
  "$ADB" install -r "$apk" >/dev/null 2>&1
  for p in POST_NOTIFICATIONS CAMERA RECORD_AUDIO ACCESS_FINE_LOCATION; do "$ADB" shell pm grant "$pkg" android.permission.$p >/dev/null 2>&1; done
  "$ADB" shell am force-stop "$pkg" >/dev/null 2>&1; "$ADB" logcat -c >/dev/null 2>&1
  # Release cached memory from the PREVIOUS heavy sample so this one starts with
  # headroom. The W90 (3.86 GB) reaps sandboxed Chromium processes slowly, so
  # without this a later sample can SIGSEGV under memory pressure -- a device
  # artifact, not a code regression (the samples run clean in isolation).
  "$ADB" shell am kill-all >/dev/null 2>&1
  sleep 5
  # Count MAIN-process (not :sandboxed / :privileged) crash deaths BEFORE launch.
  # exit-info is cumulative, so we compare before/after to catch only THIS run's
  # crash. reason 4/5/6 = CRASH / APP CRASH(NATIVE) / ANR. Grepping logcat for
  # SIGSEGV/tombstone alone false-positives on Chromium's benign crashpad +
  # sandboxed-renderer reaping (confirmed: cefrium-browser flagged with no real
  # main-process crash in exit-info).
  before="$("$ADB" shell dumpsys activity exit-info "$pkg" 2>/dev/null | grep -cE "process=$pkg reason=(4|5|6) ")"
  "$ADB" shell run-as "$pkg" rm -f files/verdict.txt >/dev/null 2>&1
  "$ADB" shell am start -n "$pkg/$act" >/dev/null 2>&1
  sleep 12
  verdict=""
  case " $VERDICT_SAMPLES " in *" $s "*)
    # The W90 takes 15-20 s to spawn a renderer; give the verdict a minute.
    for _ in $(seq 1 24); do
      verdict="$("$ADB" shell run-as "$pkg" cat files/verdict.txt 2>/dev/null | tr -d '\r')"
      [ -n "$verdict" ] && break
      sleep 2.5
    done
    verdict="${verdict:-NONE (no verdict written in 72 s)}" ;;
  esac
  after="$("$ADB" shell dumpsys activity exit-info "$pkg" 2>/dev/null | grep -cE "process=$pkg reason=(4|5|6) ")"
  # Also catch a Java FATAL surfacing in logcat (cleared per-sample above).
  fatal="$("$ADB" logcat -d 2>/dev/null | grep -icE 'FATAL EXCEPTION|NoSuchMethodError')"
  "$ADB" shell am force-stop "$pkg" >/dev/null 2>&1
  if [ "${after:-0}" -gt "${before:-0}" ] || [ "${fatal:-0}" -gt 0 ]; then
    sig="$("$ADB" logcat -d 2>/dev/null | grep -E 'FATAL|NoSuchMethod' | head -1 | tr -s ' ' | cut -c1-90)"
    echo "  $s ($pkg): CRASH -- ${sig:-main-process reason=4/5/6 (native/ANR) in exit-info}"
    # KEEP THE EVIDENCE. This used to print one 90-character line and nothing
    # else, so by the time anyone looked the logcat ring had rotated and the
    # cause was gone -- a gate that blocks a release without saying why costs
    # more than it saves. Dump the whole crash block to a file and name it.
    CRASHLOG="$ROOT/tmp/smoke-crash-$s-$(date +%Y%m%d-%H%M%S).log"
    mkdir -p "$(dirname "$CRASHLOG")"
    {
      echo "=== $s ($pkg) -- crash captured by smoke-samples.sh ==="
      echo "--- exit-info ---"
      "$ADB" shell dumpsys activity exit-info "$pkg" 2>/dev/null | head -20
      echo "--- crash buffer ---"
      "$ADB" logcat -b crash -d 2>/dev/null | tail -80
      echo "--- main log, last 200 lines ---"
      "$ADB" logcat -d 2>/dev/null | tail -200
    } > "$CRASHLOG" 2>&1
    echo "      evidence: $CRASHLOG"
    fails=$((fails+1))
  elif [ -n "$verdict" ] && [ "${verdict#PASS}" = "$verdict" ]; then
    echo "  $s ($pkg): VERDICT FAIL -- $verdict"
    fails=$((fails+1))
  else
    echo "  $s ($pkg): OK${verdict:+ -- $verdict}"
  fi
  # About 1.6 GB of build output per sample; thirteen of them filled the disk
  # in the middle of a cut. The next run builds clean anyway.
  rm -rf "$d/app/build"
done
echo "=== $((fails)) failure(s) ==="
[ "$fails" -eq 0 ] || { echo "Sample smoke FAILED -- do not publish."; exit 1; }
echo "All samples build + launch clean on device."
