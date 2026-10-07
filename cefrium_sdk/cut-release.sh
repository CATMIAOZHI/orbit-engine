#!/bin/bash
# Orchestrate a full Cefrium release cut. Chains the per-step scripts in order
# and STOPS for explicit confirmation before the two irreversible steps
# (immutable Maven publish, destructive registry prune). See RELEASING-CEFRIUM.md.
#
# Usage:
#   cef/cefrium_sdk/cut-release.sh <version> <prev-version> [--from <phase>] [--notes "<html>"]
# Phases (use --from to resume after a long build):
#   patches build merge compose plugin stats sanity publish central flagship release web prune
#
# Bypass the confirmation gates non-interactively with CEFRIUM_YES=1 (CI only).
#
# PUBLISH ALL FOUR VARIANTS. The default (no CEFRIUM_VARIANTS) is what every
# release ships, and it is not optional:
#   - x86_64 lives INSIDE the multi-ABI cefrium-sdk.aar, so a single-arch cut
#     publishes an artifact that cannot run on an EMULATOR at all -- the consumer
#     gets UnsatisfiedLinkError at runtime, not a build error.
#   - a single-codec cut skips the cefrium-sdk-libre coordinate entirely, so the
#     FOSS audience has nothing to depend on at that version.
#   Maven versions are immutable, so neither gap can be patched afterwards: the
#   only remedy is another release. 0.9.3 shipped arm64+codecs only and cost us
#   developers who test on an emulator -- do not repeat it.
#
# CEFRIUM_VARIANTS=arm64:codecs still exists for a LOCAL throwaway build you do
# NOT publish. If you set it on a cut that reaches the publish phase, you are
# shipping a broken matrix.
set -euo pipefail

VERSION="${1:?usage: cut-release.sh <version> <prev-version> [--from phase] [--notes ...]}"
PREV="${2:?need previous version (the one being superseded)}"; shift 2
# Empty by default so release-web-update.sh derives the /releases note from the
# CHANGELOG-CEFRIUM.md [<version>] section. --notes overrides with explicit text.
FROM="patches"; NOTES=""
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="$2"; shift 2 ;;
    --notes) NOTES="$2"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 1 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"                 # chromium/src
ROOT="$(cd "$SRC/../.." && pwd)"                       # cef-dev
export JAVA_HOME="${JAVA_HOME:-$ROOT/android-studio/android-studio/jbr}"
export ANDROID_HOME="${ANDROID_HOME:-$SRC/third_party/android_sdk/public}"
export PATH="$ROOT/depot_tools:$PATH"
export CEFRIUM_NINJA_JOBS="${CEFRIUM_NINJA_JOBS:-16}"
PHASES="patches build merge compose plugin stats sanity publish central flagship release web prune"
# Variant scope. Default "all" = the 4 published variants (codecs/libre x arm64/x64).
# CEFRIUM_VARIANTS="arm64:codecs" narrows to one variant. The 4-variant build
# relinks libcef.so per variant and is the biggest time sink (~4x), which is why it
# used to be skipped -- but it skips x86_64 (no emulator support) and the whole
# libre coordinate, and Maven immutability means a later top-up is impossible.
# Releases publish all four; narrow only for a build you will not publish.
VARIANTS="${CEFRIUM_VARIANTS:-all}"
V_ARCH="${VARIANTS%%:*}"; V_CODEC="${VARIANTS##*:}"

# phase N is active if its index >= index(FROM)
idx() { local i=0; for p in $PHASES; do [ "$p" = "$1" ] && { echo $i; return; }; i=$((i+1)); done; echo 999; }
FROM_I=$(idx "$FROM")
active() { [ "$(idx "$1")" -ge "$FROM_I" ] && [ "$(idx "$1")" -ge "$FROM_I" ]; }
run()  { [ "$(idx "$1")" -ge "$FROM_I" ]; }
hdr()  { echo; echo "============================================================"; echo "  PHASE: $1"; echo "============================================================"; }

gate() { # human confirmation for an irreversible step
  local what="$1"
  if [ "${CEFRIUM_YES:-0}" = "1" ]; then echo "[gate] $what — auto-confirmed (CEFRIUM_YES=1)"; return; fi
  echo; echo "*** IRREVERSIBLE: $what"
  printf "    Type the version (%s) to proceed, anything else to abort: " "$VERSION"
  read -r ans
  [ "$ans" = "$VERSION" ] || { echo "Aborted at: $what"; exit 1; }
}

cd "$SRC"

if run patches; then hdr "patches + touch"
  # Keep the SDK version constant in lockstep with the release so the built AAR
  # reports the right Cefrium.getVersion(). Idempotent; runs every cut so the
  # constant can never drift stale again (it lagged at 0.5.1 across many releases,
  # while only CHROMIUM_VERSION was being bumped). Touched below with the other
  # sources so ninja recompiles it into the AAR.
  CEFJAVA="cef/cefrium_sdk/java/src/com/cefrium/Cefrium.java"
  perl -0777 -i -pe "s{(public static final String VERSION = \")[^\"]*(\")}{\${1}$VERSION\${2}}" "$CEFJAVA"
  grep -q "String VERSION = \"$VERSION\";" "$CEFJAVA" \
    && echo ">>> Cefrium.VERSION -> $VERSION" \
    || { echo "ERROR: could not set Cefrium.VERSION in $CEFJAVA"; exit 1; }
  touch "$CEFJAVA"
  # Gate: every stored patch must be -p0 / no-prefix (patcher applies with -p0).
  # A git-default a/b-prefixed patch fails on a clean apply -- invisible on an
  # incremental tree. Fail before applying. See cef/PATCHING-CEFRIUM.md.
  cef/cefrium_sdk/check-patch-format.sh
  # apply-patches.py sets the illegal 'target_os=="android"' condition key the
  # shell cannot (see its header); plain patcher.py would skip the Android set.
  python3 cef/cefrium_sdk/apply-patches.py || true   # known-fuzz core patches report "failed" already-applied; benign
  for p in cef/patch/patches/android/*.patch; do
    for f in $(grep '^+++ ' "$p" | grep -v /dev/null | sed 's/^+++ [ab]\///;s/\t.*//'); do
      [ -f "$f" ] && touch "$f"; done; done
  touch cef/libcef/browser/android/cefrium_sdk_jni.cc cef/libcef/common/chrome/chrome_main_delegate_cef.cc
fi

if run build;   then hdr "build 4 variants"
  # Release builds CLEAN by default: a published, immutable artifact must be
  # reproducible from committed source, not from accumulated incremental state.
  # (After an engine bump the dirs are already fresh, so this only adds cost on a
  # same-engine re-cut.) Opt out with CEFRIUM_INCREMENTAL=1 for a fast re-cut when
  # the delta is isolated (e.g. a doc-only or single-file fix you've judged safe).
  [ "${CEFRIUM_INCREMENTAL:-0}" = "1" ] || export CEFRIUM_CLEAN=1
  if [ "$VARIANTS" = "all" ]; then
    cef/cefrium_sdk/build-all-variants.sh all
  else
    echo ">>> single-variant build: $V_ARCH $V_CODEC (CEFRIUM_VARIANTS=$VARIANTS)"
    cef/cefrium_sdk/build-all-variants.sh "$V_ARCH" "$V_CODEC"
  fi
  # Gate: the checked-in GEN_JNI must match the build's freshly generated one,
  # else consumers hit a runtime NoSuchMethodError (the 0.4.0 root cause -- a
  # stale GEN_JNI not regenerated on the engine bump). Fail early. Only when the
  # arm64-codecs dir was built (it is, for "all" and the default dev variant).
  [ -d out/Official_android_arm64_codecs ] && \
    cef/cefrium_sdk/regen-gen-jni.sh --check out/Official_android_arm64_codecs
fi
if run merge;   then hdr "merge multi-ABI"
  if [ "$VARIANTS" = "all" ]; then
    cef/cefrium_sdk/merge-multiabi.sh codecs; cef/cefrium_sdk/merge-multiabi.sh libre
  else
    # Single ABI: nothing to fuse -- the slimmed per-variant AAR IS the published
    # cefrium-sdk. Remove any stale libre so it is skipped by publish/release.
    cp "out/release/cefrium-sdk-${V_ARCH}-${V_CODEC}.aar" out/release/cefrium-sdk.aar
    rm -f out/release/cefrium-sdk-libre.aar
    echo ">>> single-variant: cefrium-sdk.aar = ${V_ARCH}-${V_CODEC}; libre skipped"
  fi
fi
if run compose; then hdr "compose AAR"
  mkdir -p cef/cefrium_compose/libs   # gitignored compileOnly dep dir; may be absent on a fresh tree
  cp out/release/cefrium-sdk.aar cef/cefrium_compose/libs/
  # gradle-wrapper.jar is gitignored in cefrium_compose; restore from a sample
  # module (committed there) if absent, else ./gradlew can't bootstrap.
  WJ=cef/cefrium_compose/gradle/wrapper/gradle-wrapper.jar
  [ -f "$WJ" ] || cp "$ROOT/cefrium-sample/push-demo/gradle/wrapper/gradle-wrapper.jar" "$WJ"
  ( cd cef/cefrium_compose && ./gradlew assembleRelease --no-daemon )
fi
if run plugin;  then hdr "plugin jar check"
  jar=cef/cefrium_sdk/gradle-plugin/build/libs/gradle-plugin.jar
  unzip -p "$jar" 'com/cefrium/CefriumPlugin*.class' | strings | grep -q gms.common \
    && echo "plugin jar current" || echo "WARN: plugin jar may be stale — rebuild it"
fi
if run stats;   then hdr "patch stats"; cef/cefrium_sdk/update-patch-stats.sh || true; fi
if run sanity;  then hdr "local sanity (push-demo on the fresh AAR)"
  cp out/release/cefrium-sdk.aar "$ROOT/cefrium-sample/push-demo/app/libs/cefrium-sdk.aar"
  ( cd "$ROOT/cefrium-sample/push-demo" && ./gradlew clean assembleDebug --no-daemon )
  echo "Sanity green — the fresh AAR is consumable (compile-time)."

  # Runtime FEATURE gate (the 0.4.0 lesson): compile-time checks miss runtime
  # feature regressions -- 0.4.0 shipped a 149 flagship crash + a content-setting
  # break. Run the instrumented suite on a connected device against the fresh AAR
  # BEFORE the IMMUTABLE publish. A release MUST be runtime-validated on device.
  ADB="$ANDROID_HOME/platform-tools/adb"; [ -x "$ADB" ] || ADB=adb
  [ "$("$ADB" devices 2>/dev/null | grep -c 'device$')" -ge 1 ] || {
    echo "FAIL: no device connected -- the runtime feature gate requires one."; exit 1; }
  IT=cef/cefrium_instrumentation_test
  mkdir -p "$IT/app/libs"
  cp out/release/cefrium-sdk.aar "$IT/app/libs/cefrium-sdk.aar"
  cp cef/cefrium_compose/build/outputs/aar/cefrium-compose-release.aar "$IT/app/libs/cefrium-compose.aar"
  WJI="$IT/gradle/wrapper/gradle-wrapper.jar"   # gitignored; restore from a sample
  [ -f "$WJI" ] || { mkdir -p "$IT/gradle/wrapper"; cp "$ROOT/cefrium-sample/push-demo/gradle/wrapper/gradle-wrapper.jar" "$WJI"; }
  [ -f "$IT/gradlew" ] || cp "$ROOT/cefrium-sample/push-demo/gradlew" "$IT/gradlew"
  chmod +x "$IT/gradlew"
  ( cd "$IT" && ./gradlew :app:connectedDebugAndroidTest --no-daemon )
  echo "Runtime feature gate green -- instrumented suite passed on device."

  # Sample launch-smoke gate: build + launch EVERY cefrium-sample on device
  # against the fresh AAR; fail if any crashes. Samples exercise the SDK in app
  # configs the instrumented suite does not (compose, kiosk, push). A sample
  # crashing on a new engine must block the publish (the 0.4.0 flagship lesson).
  cef/cefrium_sdk/smoke-samples.sh

  # Close-leak regression gate (testing/profiling plan, Phase 4/7). Build the
  # benchmark harness against the fresh AAR and drive 30 pool-evicting navigations;
  # then assert the engine's DETERMINISTIC counters (closedBrowserCount grew, live +
  # frame counts bounded). This is the right signal for the 0.5.2 close-leak class
  # (CloseHostWindow/WindowDestroyed) -- that bug was renderer/frame COUNT growth
  # with PSS flat, so PSS-slope is too noisy to gate on (leak-slope.sh is advisory).
  # Best-effort: skips with a WARN if the harness is absent. See cefrium-sample/
  # benchmark-suite/ and cefrium.com/benchmarks/.
  BS="$ROOT/cefrium-sample/benchmark-suite"
  if [ -d "$BS/cefrium-bench" ]; then
    mkdir -p "$BS/cefrium-bench/app/libs"
    cp out/release/cefrium-sdk.aar "$BS/cefrium-bench/app/libs/cefrium-sdk.aar"
    cp cef/cefrium_compose/build/outputs/aar/cefrium-compose-release.aar "$BS/cefrium-bench/app/libs/cefrium-compose.aar"
    ( cd "$BS/cefrium-bench" && ./gradlew :app:assembleDebug --no-daemon )
    SER="$("$ADB" devices | awk '/device$/{print $1; exit}')"
    "$ADB" -s "$SER" install -r "$BS/cefrium-bench/app/build/outputs/apk/debug/app-debug.apk"
    "$BS/leak-check.sh" -s "$SER" -n 30 -r "$VERSION" \
      || { echo "FAIL: close-leak gate -- evicted browsers not closing / counts unbounded. Blocking publish."; exit 1; }
    echo "Close-leak gate green -- evictions complete and frame/browser counts bounded."
  else
    echo "WARN: benchmark-suite/cefrium-bench not found -- skipping close-leak gate."
  fi
fi

if run publish; then hdr "publish to Maven (IMMUTABLE)"
  # Refuse to publish an incomplete variant matrix. This is a hard stop, not a
  # warning, because the damage is invisible at publish time and unfixable after:
  # the version is immutable, x86_64 ships INSIDE cefrium-sdk.aar (so emulator
  # users get a runtime UnsatisfiedLinkError, not a build error), and a
  # codecs-only cut leaves the libre coordinate missing entirely. 0.9.3 went out
  # arm64+codecs and cost us developers who test on emulators.
  if [ "$VARIANTS" != "all" ]; then
    echo
    echo "============================================================"
    echo "  REFUSING TO PUBLISH A PARTIAL VARIANT MATRIX"
    echo "============================================================"
    echo "  CEFRIUM_VARIANTS=$VARIANTS builds one variant; a release needs all four."
    echo "  Missing from this cut:"
    [ "$V_ARCH"  = "arm64"  ] && echo "    - x86_64  -> the published AAR cannot run on an emulator"
    [ "$V_CODEC" = "codecs" ] && echo "    - libre   -> com.cefrium:cefrium-sdk-libre:$VERSION would not exist"
    echo "  Maven versions are immutable, so this cannot be topped up later."
    echo
    echo "  Re-run without CEFRIUM_VARIANTS (all four), or, if you really intend to"
    echo "  publish a partial matrix, set CEFRIUM_ALLOW_PARTIAL=1."
    echo
    [ "${CEFRIUM_ALLOW_PARTIAL:-0}" = "1" ] || exit 1
    echo "  CEFRIUM_ALLOW_PARTIAL=1 -- proceeding against the policy."
  fi
  gate "publish $VERSION to the Codeberg Maven registry (cannot be overwritten)"
  # Partial matrix (only with CEFRIUM_ALLOW_PARTIAL): only cefrium-sdk exists.
  [ "$VARIANTS" = "all" ] || export CEFRIUM_SMOKE_VARIANTS="cefrium-sdk"
  cef/cefrium_sdk/publish-maven.sh "$VERSION"   # uploads + smoke-tests the coordinate(s)
fi

if run central; then hdr "publish to Maven Central (the channel adopters pin)"
  # THIS PHASE EXISTS BECAUSE IT WAS MISSED FOR FOUR RELEASES. Central is what
  # external adopters resolve from -- the Codeberg registry is latest-only and
  # cannot be pinned, which is what we tell integrators. Publishing to Central
  # used to be a separate manual publish-central.sh that simply stopped being
  # run after 0.8.9 (2026-09-18): 0.9.0 through 0.9.3 went to Codeberg only, so
  # every 0.9.x is a 404 on Central and no adopter ever received those fixes.
  # Bug reports went quiet three days after that last Central delivery, and it
  # read as "we got more stable". Never make this a step someone must remember.
  #
  # Not gated: the default mode uploads and VALIDATES as USER_MANAGED, so
  # nothing becomes public here. Pressing Publish in the Portal (or re-running
  # with --publish) is the human checkpoint, and a Central release is immutable.
  if [ ! -f "$HOME/.publish-token" ]; then
    echo "ERROR: ~/.publish-token is missing, so this release would reach Codeberg"
    echo "       but NOT Maven Central -- which is the channel adopters pin to."
    echo "       Create a Portal user token (Portal -> Account -> Generate User"
    echo "       Token), store 'username:password' on one line, chmod 600, re-run:"
    echo "         cef/cefrium_sdk/cut-release.sh $VERSION $PREV --from central"
    echo "       Skipping deliberately? CEFRIUM_SKIP_CENTRAL=1."
    [ "${CEFRIUM_SKIP_CENTRAL:-0}" = "1" ] || exit 1
  fi
  if [ "${CEFRIUM_SKIP_CENTRAL:-0}" = "1" ]; then
    echo ">>> CEFRIUM_SKIP_CENTRAL=1 -- NOT publishing to Central."
    echo "    External adopters will not receive $VERSION. Codeberg is latest-only"
    echo "    and unpinnable, so this version is effectively internal."
  else
    cef/cefrium_sdk/publish-central.sh "$VERSION"
  fi
fi

if run flagship; then hdr "flagship APK on $VERSION"
  # The browser lives in its own repo (cefrium/cefrium-browser). It defaults to
  # the Maven registry, but we build the flagship in LOCAL mode against the
  # freshly built AARs: reproducible, and independent of registry propagation.
  BROWSER="$ROOT/cefrium-browser/cefrium-browser"
  mkdir -p "$BROWSER/app/libs"
  cp out/release/cefrium-sdk.aar "$BROWSER/app/libs/cefrium-sdk.aar"
  cp cef/cefrium_compose/build/outputs/aar/cefrium-compose-release.aar "$BROWSER/app/libs/cefrium-compose.aar"
  ( cd "$BROWSER" && ./gradlew clean assembleDebug -PcefriumSource=local --no-daemon )
fi
if run release; then hdr "Codeberg release (tag + GPG-signed SHA256SUMS + assets)"
  cef/cefrium_sdk/release-codeberg.sh "$VERSION" \
    "$ROOT/cefrium-browser/cefrium-browser/app/build/outputs/apk/debug/app-debug.apk"
fi
if run web;     then hdr "update cefrium.com pages + committed repo docs"
  cef/cefrium_sdk/release-web-update.sh "$VERSION" "$PREV" "$(date +%F)" "$NOTES"
  # Bump the committed cef/ consumer docs too (QUICKSTART-CEFRIUM.md, README coordinates +
  # download URLs). release-web-update.sh only touches the cefrium.com web tree, so
  # without this the repo docs go stale (they sat at 0.3.1 through 0.4.1).
  cef/cefrium_sdk/release-bump-docs.sh "$VERSION" "$PREV"
  echo "Review + commit the web repo (quickstart, releases -> push pages) AND the"
  echo "cef repo (QUICKSTART-CEFRIUM.md, README -> push android-port)."
fi
if run prune;   then hdr "prune previous versions (DESTRUCTIVE, latest-only)"
  gate "prune every registry version except $VERSION"
  cef/cefrium_sdk/prune-old-versions.sh "$VERSION" --yes
fi

echo; echo "Release $VERSION cut. Remaining manual: commit/push web + cef, regenerate Javadoc/Dokka if API changed, remove any 'releasing shortly' notices."
