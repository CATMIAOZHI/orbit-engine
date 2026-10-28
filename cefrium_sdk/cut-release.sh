#!/bin/bash
# Orchestrate a full Cefrium release cut. Chains the per-step scripts in order
# and STOPS for explicit confirmation before the irreversible publish steps
# (Maven versions are immutable). See RELEASING-CEFRIUM.md.
#
# Usage:
#   cef/cefrium_sdk/cut-release.sh <version> <prev-version> [--from <phase>] [--notes "<html>"]
# Phases (use --from to resume after a long build):
#   patches build hotranges merge compose plugin stats sanity publish central flagship release web prune
#
# Bypass the confirmation gates non-interactively with CEFRIUM_YES=1 (CI only);
# a cut launched as a background job has no stdin and MUST set it, or it dies at
# the first gate. The final prune is retention policy and needs no gate: it is
# unattended and interlocked on $VERSION being served (CEFRIUM_NO_PRUNE=1 skips).
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
# JDK 25 (Chromium's Temurin): the AAR is class-69 bytecode that JBR 21 cannot read.
export JAVA_HOME="${JAVA_HOME:-$SRC/third_party/jdk/current}"
[ -x "$JAVA_HOME/bin/java" ] || { echo "ERROR: no JDK at JAVA_HOME=$JAVA_HOME"; exit 1; }
export ANDROID_HOME="${ANDROID_HOME:-$SRC/third_party/android_sdk/public}"
export PATH="$ROOT/depot_tools:$PATH"
export CEFRIUM_NINJA_JOBS="${CEFRIUM_NINJA_JOBS:-16}"
PHASES="patches build hotranges merge compose plugin stats sanity suite publish central flagship release provenance web prune play"
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
# One line per phase, and the outcome at exit: wait for the process, then read
# the last line, instead of watching the log.
STATUS="$ROOT/tmp/cut-${VERSION:-unknown}.status"
mkdir -p "$ROOT/tmp"
CURRENT_PHASE="start"
hdr()  { echo; echo "============================================================"; echo "  PHASE: $1"; echo "============================================================";
         CURRENT_PHASE="$1"; echo "$(date +%F_%T) PHASE $1" >> "$STATUS"; }
trap 'rc=$?; if [ $rc -eq 0 ]; then echo "$(date +%F_%T) CUT OK" >> "$STATUS"; else echo "$(date +%F_%T) CUT FAILED rc=$rc in: $CURRENT_PHASE" >> "$STATUS"; fi' EXIT

gate() { # human confirmation for an irreversible step
  local what="$1"
  if [ "${CEFRIUM_YES:-0}" = "1" ]; then echo "[gate] $what -- auto-confirmed (CEFRIUM_YES=1)"; return; fi
  echo; echo "*** IRREVERSIBLE: $what"
  # A cut is normally launched as a background job with no stdin, where `read`
  # hits EOF immediately and dies under errexit at a half-finished release. Say
  # so instead of failing cryptically, and name the way through.
  if [ ! -t 0 ]; then
    echo "    No terminal on stdin, so this gate cannot be answered here."
    echo "    Re-run this phase from a terminal, or set CEFRIUM_YES=1 to"
    echo "    pre-authorize the irreversible steps for this cut."
    echo "Aborted at: $what"
    exit 1
  fi
  printf "    Type the version (%s) to proceed, anything else to abort: " "$VERSION"
  read -r ans
  [ "$ans" = "$VERSION" ] || { echo "Aborted at: $what"; exit 1; }
}

cd "$SRC"

# Local and untracked: export lines naming the credential files (paths, never
# the tokens). Loaded only for what the environment does not already set.
CEFRIUM_RELEASE_ENV="${CEFRIUM_RELEASE_ENV:-$HOME/.config/cefrium/release.env}"
[ -f "$CEFRIUM_RELEASE_ENV" ] && . "$CEFRIUM_RELEASE_ENV"

# Credential preflight. The token PATHS are no longer hardcoded (a public repo
# should not say where this machine keeps its credentials), so they come from
# the environment -- and a missing variable must surface now, not six hours in
# at the publish phase with four variants already built. Only checked for the
# phases that actually need them.
if run publish || run central || run release || run prune; then
  MISSING=""
  for v in CEFRIUM_REGISTRY_TOKEN CEFRIUM_CENTRAL_TOKEN; do
    eval "p=\${$v:-}"
    [ -n "$p" ] && [ -f "$p" ] || MISSING="$MISSING $v"
  done
  if [ -n "$MISSING" ]; then
    echo "ERROR: missing credential(s):$MISSING"
    echo "       Each must name a readable file holding the token. Export them"
    echo "       before the cut; the real paths are in your local setup notes,"
    echo "       deliberately not in this repository."
    exit 1
  fi
  echo ">>> credentials: both token files present"
fi

# The signature comes hours in, at the release phase: find a key that cannot
# sign (card not primed) now. A detached test signature fails fast and never
# spends a PIN retry.
if run release; then
  t="$(mktemp -p "$ROOT/tmp" gpgcheck.XXXXXX)"
  if gpg --batch --yes --detach-sign -o "$t.sig" "$t" >/dev/null 2>&1; then
    echo ">>> signing key: signs"
  else
    rm -f "$t" "$t.sig"
    echo "ERROR: the release signing key cannot sign. Prime the hardware key in a"
    echo "       terminal that can show a pinentry prompt, then run the cut again."
    exit 1
  fi
  rm -f "$t" "$t.sig"
fi

# Over a VPN the Central upload of the SDK bundles failed half way (HTTP 524)
# and left a version partly published. Refuse before anything is built.
if run central && [ "${CEFRIUM_ALLOW_VPN:-0}" != 1 ]; then
  VPN_IF="$(ip -o link show up 2>/dev/null | awk -F': ' '$2 ~ /^(tun|wg|tap|ppp|proton)/ {print $2}' | tr '\n' ' ')"
  if [ -n "$VPN_IF" ]; then
    echo "ERROR: a VPN interface is up ($VPN_IF). Disconnect it for the Central"
    echo "       upload, or set CEFRIUM_ALLOW_VPN=1 to go ahead anyway."
    exit 1
  fi
fi

# ---- release-pinned sources -------------------------------------------------
# Tracked files in the cef repo that a cut REWRITES. Every one of them has to
# end up committed, and that is not housekeeping: the Codeberg release tags
# HEAD, Maven versions are immutable, and a published artifact must be
# reproducible from committed source by someone who is not us.
#
# 0.10.1 shipped with Cefrium.VERSION still reading 0.10.0 in git. The pin was
# written to the working tree and never committed, and the cut's own final
# push.sh printed "uncommitted changes -- these are NOT pushed" as a note that
# is easy to read past. So the cut now commits each pin at the phase that
# writes it, and refuses to reach an irreversible step with any of them dirty.
#
# Add a path here the moment a phase starts rewriting one.
PINNED="cefrium_sdk/java/src/com/cefrium/Cefrium.java
CHANGELOG-CEFRIUM.md
README.md
QUICKSTART-CEFRIUM.md
MIGRATION-CEFRIUM.md
BUILD-GUIDE-CEFRIUM.md
RELEASING-CEFRIUM.md"

cefgit() { git -C "$SRC/cef" "$@"; }

# Commit the named pinned paths if this cut changed them. A no-op on a clean
# tree, so --from re-runs stay safe. Named paths only, never `git add -A`: the
# cut runs in a tree that also holds local scratch.
pin_commit() { # pin_commit "<subject>" <path-relative-to-cef>...
  local msg="$1"; shift
  local have=() p
  for p in "$@"; do [ -e "$SRC/cef/$p" ] && have+=("$p"); done
  [ ${#have[@]} -gt 0 ] || return 0
  if cefgit diff --quiet -- "${have[@]}" && cefgit diff --cached --quiet -- "${have[@]}"; then
    return 0
  fi
  cefgit add -- "${have[@]}"
  cefgit commit -q -m "$msg" || { echo "ERROR: could not commit ${have[*]} in $SRC/cef"; exit 1; }
  echo ">>> pinned source committed: $(cefgit log -1 --format=%h) $msg"
}

pins_dirty() { cefgit status --porcelain -- $PINNED 2>/dev/null || true; }

# Hard stop before anything irreversible. Generic on purpose: it catches the
# next file someone starts rewriting, not only the one that bit us.
assert_pins_committed() { # assert_pins_committed "<what is about to happen>"
  local dirty; dirty="$(pins_dirty)"
  if [ -z "$dirty" ]; then echo ">>> release-pinned sources: all committed"; return 0; fi
  echo
  echo "ERROR: a release-pinned source is modified but not committed, so"
  echo "       $1 would record source that exists only on this disk."
  echo "$dirty" | sed 's/^/    /'
  echo
  echo "       Commit each one in $SRC/cef under its own subject, then resume:"
  echo "         cef/cefrium_sdk/cut-release.sh $VERSION $PREV --from <phase>"
  exit 1
}

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
  # Refuse to swallow unrelated work into the release commit. If the only
  # change to Cefrium.java is the version line, it is ours to commit. If there
  # is more, the maintainer has in-flight API work in that file, and it has to
  # land under its own subject before a cut, not inside "Cefrium <version>".
  other=$(cefgit diff -U0 -- cefrium_sdk/java/src/com/cefrium/Cefrium.java \
          | awk '/^[-+]/ && !/^(\+\+\+|---)/ && !/String VERSION = /' | wc -l)
  if [ "$other" -gt 0 ]; then
    echo "ERROR: Cefrium.java carries $other changed line(s) besides the version pin."
    echo "       Commit that work under its own subject first, then re-run the cut:"
    cefgit diff --stat -- cefrium_sdk/java/src/com/cefrium/Cefrium.java | sed 's/^/    /'
    exit 1
  fi
  # Commit the pin NOW, before anything is built from it. The build has to come
  # from committed source, and the release phase tags HEAD.
  pin_commit "Cefrium $VERSION" cefrium_sdk/java/src/com/cefrium/Cefrium.java
  # The changelog is hand-written before a cut and used to be hand-committed
  # too, which is the same hazard one file over. Its [Unreleased] section
  # becomes this release (a re-run finds [$VERSION] already there).
  CL="cef/CHANGELOG-CEFRIUM.md"
  if ! grep -q "^## \[$VERSION\]" "$CL"; then
    grep -q '^## \[Unreleased\]' "$CL" \
      || { echo "ERROR: $CL has neither [$VERSION] nor [Unreleased]"; exit 1; }
    perl -i -pe "s{^## \[Unreleased\]\$}{## [$VERSION] - $(date +%F)}" "$CL"
    echo ">>> changelog: [Unreleased] -> [$VERSION]"
  fi
  pin_commit "Changelog for $VERSION" CHANGELOG-CEFRIUM.md
  # Gate: every stored patch must be -p0 / no-prefix (patcher applies with -p0).
  # A git-default a/b-prefixed patch fails on a clean apply -- invisible on an
  # incremental tree. Fail before applying. See cef/PATCHING-CEFRIUM.md.
  cef/cefrium_sdk/check-patch-format.sh
  # apply-patches.py sets the illegal 'target_os=="android"' condition key the
  # shell cannot (see its header); plain patcher.py would skip the Android set.
  python3 cef/cefrium_sdk/apply-patches.py || true   # known-fuzz core patches report "failed" already-applied; benign
  # That `|| true` is unavoidable -- apply-patches.py reports "failed to apply"
  # for an already-applied patch and for a genuinely missing one alike -- but it
  # leaves the QUESTION unanswered, and the answer decides whether this build is
  # correct. The 0.10.2 cut printed "ERROR: 3 patches failed to apply. Your build
  # will not be correct." and built anyway; all three were already applied, and
  # nothing in the cut knew that. Judge it by CONTENT, which is the one test that
  # survived everything else (see check-patches-applied.sh).
  cef/cefrium_sdk/check-patches-applied.sh || {
    echo "ERROR: patch content is MISSING from the tree -- refusing to build a"
    echo "       release from it. The lines above name the patch and the file."
    exit 1; }
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
if run hotranges; then hdr "startup read-ahead list (arm64, on the device)"
  # The SDK reads the parts of libcef.so a cold start used, in that order; the
  # offsets belong to one link, so each arm64 variant gets its own list,
  # recorded from its own AAR on the device. Records on ANDROID_SERIAL.
  for codec in codecs libre; do
    aar="out/release/cefrium-sdk-arm64-${codec}.aar"
    [ -f "$aar" ] || continue
    cef/cefrium_sdk/record-hot-ranges.sh "$aar" --inject \
      || { echo "ERROR: could not record the read-ahead list for $aar"; exit 1; }
  done
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
if run merge; then
  # Assert the BYTES of what is about to be published, not the build log. Three
  # separate staleness bugs in one day each passed the log and failed on a
  # device. Runs here, before compose/publish, so a wrong artifact never reaches
  # an immutable registry. (The plugin/APK checks it also does are no-ops until
  # those exist; the publish-side call below covers them.)
  cef/cefrium_sdk/verify-release-artifacts.sh "$VERSION" || {
    echo "ERROR: release artifacts do not match $VERSION -- refusing to continue."; exit 1; }
fi

if run merge; then
  # Supply-chain artifacts, generated from the same build the release ships.
  cef/cefrium_sdk/generate-sbom.sh "$VERSION" || {
    echo "ERROR: could not generate the SBOM/NOTICE for $VERSION."; exit 1; }
fi

if run compose; then hdr "compose + unifiedpush AARs"
  mkdir -p cef/cefrium_compose/libs   # gitignored compileOnly dep dir; may be absent on a fresh tree
  cp out/release/cefrium-sdk.aar cef/cefrium_compose/libs/
  # gradle-wrapper.jar is gitignored in cefrium_compose; restore from a sample
  # module (committed there) if absent, else ./gradlew can't bootstrap.
  WJ=cef/cefrium_compose/gradle/wrapper/gradle-wrapper.jar
  [ -f "$WJ" ] || cp "$ROOT/cefrium-sample/push-demo/gradle/wrapper/gradle-wrapper.jar" "$WJ"
  ( cd cef/cefrium_compose && ./gradlew assembleRelease --no-daemon )
  mkdir -p cef/cefrium_unifiedpush/libs
  cp out/release/cefrium-sdk.aar cef/cefrium_unifiedpush/libs/
  WJ=cef/cefrium_unifiedpush/gradle/wrapper/gradle-wrapper.jar
  [ -f "$WJ" ] || cp "$ROOT/cefrium-sample/push-demo/gradle/wrapper/gradle-wrapper.jar" "$WJ"
  ( cd cef/cefrium_unifiedpush && ./gradlew assembleRelease --no-daemon )
fi
if run plugin;  then hdr "plugin jar check"
  jar=cef/cefrium_sdk/gradle-plugin/build/libs/gradle-plugin.jar
  src=cef/cefrium_sdk/gradle-plugin/src/main/groovy/com/cefrium/CefriumPlugin.groovy
  [ -f "$jar" ] || { echo "FAIL: no plugin jar at $jar"; exit 1; }

  # Check the property consumers actually depend on, not a string inside the
  # class. Three evaluators reported "Unsupported class file major version 69"
  # and blamed this plugin; it has been class 61 since 49cea91c4, which is what
  # lets `plugins { id 'com.cefrium' }` be applied on a JDK 17 Gradle instead of
  # dragging every integrator onto JDK 25. Nothing guarded that, so a build-JDK
  # change could silently take it away again. 61 = Java 17.
  CLS="$(mktemp -d)"; unzip -qo "$jar" 'com/cefrium/CefriumPlugin.class' -d "$CLS"
  maj=$(od -An -tu1 -j6 -N2 "$CLS/com/cefrium/CefriumPlugin.class" | awk '{print $1*256+$2}')
  rm -rf "$CLS"
  if [ "${maj:-0}" -gt 61 ]; then
    echo "FAIL: plugin bytecode is class-file major $maj (> 61 = Java 17)."
    echo "      That forces every consumer onto a newer JDK just to APPLY the"
    echo "      plugin. Restore sourceCompatibility/targetCompatibility = 17 in"
    echo "      cefrium_sdk/gradle-plugin/build.gradle and rebuild the jar."
    exit 1
  fi
  echo "plugin bytecode OK (class-file major $maj, applies on JDK 17+)"

  # Staleness by timestamp, not by grepping for a symbol. Nothing in the release
  # rebuilds this jar, so the same file is republished under each new version
  # (see ROADMAP "Adoption friction" 1a). Rebuilding it here needs a Gradle
  # wrapper this module does not have -- borrow one from a sample, the way the
  # instrumentation test does -- so for now this only reports the gap.
  if [ -f "$src" ] && [ "$src" -nt "$jar" ]; then
    echo "WARN: $src is NEWER than the jar -- the jar does not match this source."
  fi
fi
if run stats;   then hdr "consumer-doc figures"
  # One owner for every figure README.md, QUICKSTART-CEFRIUM.md and
  # MIGRATION-CEFRIUM.md state: patch counts, engine, CEF branch, SDK version,
  # render-process slots, and the
  # Android/Gradle/AGP floors. Each is derived from the file that defines it --
  # the patch dir, Cefrium.java, the SDK manifest, the compose module's Gradle
  # build -- so a doc cannot disagree with the SDK it documents.
  #
  # It exits non-zero on a figure that is stale or whose sentence was reworded,
  # and that MUST stop the cut. This call used to end in `|| true`, so a cut
  # could not fail here: the README sat six Chromium milestones behind under its
  # own "Security updates" heading, four sentences said 21 patches against 34 on
  # disk, and the quickstart promised the reader "full Chromium 152".
  #
  # This phase runs AFTER `patches` pinned Cefrium.VERSION, so the SDK version in
  # the docs is derived here too -- that is why there is no release-bump-docs.sh
  # call in the web phase any more.
  cef/cefrium_sdk/update-doc-figures.py
  pin_commit "Update the derived doc figures for $VERSION" \
    README.md QUICKSTART-CEFRIUM.md MIGRATION-CEFRIUM.md BUILD-GUIDE-CEFRIUM.md \
    RELEASING-CEFRIUM.md
  # Assert what is COMMITTED, not what was written. pin_commit is a no-op on an
  # already-clean tree, so on a --from stats re-run this is the only line that
  # proves the docs in git carry this release's figures.
  cef/cefrium_sdk/update-doc-figures.py --check
  # Cefrium's own sources carry the dual-licence header; CEF-derived code
  # (libcef/, patch/) keeps CEF's BSD header and is not checked here.
  cef/cefrium_sdk/license-headers.py --check
fi
if run sanity;  then hdr "local sanity (push-demo on the fresh AAR)"
  cp out/release/cefrium-sdk.aar "$ROOT/cefrium-sample/push-demo/app/libs/cefrium-sdk.aar"
  ( cd "$ROOT/cefrium-sample/push-demo" && ./gradlew clean assembleDebug --no-daemon )
  echo "Sanity green -- the fresh AAR is consumable (compile-time)."

  # Runtime FEATURE gate (the 0.4.0 lesson): compile-time checks miss runtime
  # feature regressions -- 0.4.0 shipped a 149 flagship crash + a content-setting
  # break. Run the instrumented suite on a connected device against the fresh AAR
  # BEFORE the IMMUTABLE publish. A release MUST be runtime-validated on device.
  ADB="$ANDROID_HOME/platform-tools/adb"; [ -x "$ADB" ] || ADB=adb
  [ "$("$ADB" devices 2>/dev/null | grep -c 'device$')" -ge 1 ] || {
    echo "FAIL: no device connected -- the runtime feature gate requires one."; exit 1; }
  # The instrumented suite runs once, in the suite phase, through its script
  # (timeout, clean build, every declared test must run). It used to run here
  # too, inline and without those checks.

  # Sample launch-smoke gate: build + launch EVERY cefrium-sample on device
  # against the fresh AAR; fail if any crashes. Samples exercise the SDK in app
  # configs the instrumented suite does not (compose, kiosk, push). A sample
  # crashing on a new engine must block the publish (the 0.4.0 flagship lesson).
  cef/cefrium_sdk/smoke-samples.sh

  # Minified gate: build the browser with R8 on against the fresh AAR, fail
  # below Play's 25% in any R8 category, and run it on the device. A missing
  # consumer keep only shows at run time.
  cef/cefrium_sdk/smoke-minified.sh

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
    # The chosen device, not whichever adb lists first.
    SER="${ANDROID_SERIAL:-$("$ADB" devices | awk '/device$/{print $1; exit}')}"
    "$ADB" -s "$SER" install -r "$BS/cefrium-bench/app/build/outputs/apk/debug/app-debug.apk"
    "$BS/leak-check.sh" -s "$SER" -n 30 -r "$VERSION" \
      || { echo "FAIL: close-leak gate -- evicted browsers not closing / counts unbounded. Blocking publish."; exit 1; }
    echo "Close-leak gate green -- evictions complete and frame/browser counts bounded."
  else
    echo "WARN: benchmark-suite/cefrium-bench not found -- skipping close-leak gate."
  fi
fi

if run suite; then hdr "instrumentation suite on device"
  # The last gate before anything becomes immutable, and the only one that uses
  # the SDK the way a consumer does. The sanity phase above launches one sample
  # and asks whether it came up; this asks whether the API still behaves.
  #
  # It is here because a cut used to ship on compile success plus that one
  # launch. The gap is not theoretical: a process-model change passed the
  # sandbox battery with touch input dead, and only this suite noticed, because
  # the battery measured isolation and filters and never tapped anything.
  #
  # No version argument: the versioned copies are made later, by
  # release-codeberg.sh. The unversioned AARs here are the bytes
  # verify-release-artifacts.sh asserted in the merge phase.
  cef/cefrium_sdk/run-instrumentation-suite.sh || {
    echo "ERROR: the instrumentation suite did not pass. Nothing is published."
    echo "       Re-run it alone: cef/cefrium_sdk/run-instrumentation-suite.sh"
    exit 1; }
  # Same suite on the harness minified with R8, as a consumer ships it: the
  # SDK's consumer rules must keep everything native code and reflection reach.
  cef/cefrium_sdk/run-instrumentation-suite.sh --minified || {
    echo "ERROR: the minified instrumentation suite did not pass. Nothing is published."
    echo "       Re-run it alone: cef/cefrium_sdk/run-instrumentation-suite.sh --minified"
    exit 1; }
  # Web Push through FCM, which the suite can only fake: repeated subscribe /
  # unsubscribe per profile, and two sites.
  cef/cefrium_sdk/push-e2e.sh || {
    echo "ERROR: Web Push end to end did not pass. Nothing is published."
    echo "       Re-run it alone: cef/cefrium_sdk/push-e2e.sh"
    exit 1; }
fi

if run publish; then hdr "publish to Maven (IMMUTABLE)"
  # Immutable means there is no second chance to make the bytes match the
  # source. Check before, not after.
  assert_pins_committed "publishing $VERSION to Maven"
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
  # Released automatically (--publish), to match Codeberg. Asymmetry was the
  # wrong call: the Codeberg publish above is already live and immutable after a
  # single confirmation, so making Central alone wait on a Portal click bought no
  # safety -- it just meant adopters did not get the release, which is the exact
  # failure this phase exists to prevent (0.9.0-0.9.3 never reached Central).
  # The gate on the Codeberg publish is the human checkpoint for both.
  # A Central release is immutable. To upload without releasing, run
  # publish-central.sh <ver> by hand, then release later with --release <id>...
  if [ -z "${CEFRIUM_CENTRAL_TOKEN:-}" ] || [ ! -f "$CEFRIUM_CENTRAL_TOKEN" ]; then
    echo "ERROR: CEFRIUM_CENTRAL_TOKEN is unset or its file is missing, so this"
    echo "       release would reach Codeberg"
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
    cef/cefrium_sdk/publish-central.sh "$VERSION" --publish
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
  # The APK is a release asset, so assert it carries this engine and both ABIs.
  # `clean` above should make that certain, but Gradle has reported
  # "up-to-date" on a replaced AAR before and shipped the previous engine.
  cef/cefrium_sdk/verify-release-artifacts.sh "$VERSION" \
    --apk "$BROWSER/app/build/outputs/apk/debug/app-debug.apk" || {
    echo "ERROR: the flagship APK does not match $VERSION -- refusing to continue."; exit 1; }
fi
if run release; then hdr "Codeberg release (tag + GPG-signed SHA256SUMS + assets)"
  # The tag is annotated at HEAD, so a dirty pin would tag the previous
  # version's source under this version's name.
  assert_pins_committed "tagging $VERSION at HEAD"
  cef/cefrium_sdk/release-codeberg.sh "$VERSION" \
    "$ROOT/cefrium-browser/cefrium-browser/app/build/outputs/apk/debug/app-debug.apk"
fi
if run provenance; then hdr "Maven Central serves the signed bytes"
  # Before the site announces the release: the signed SHA256SUMS is what a
  # release claims to contain, and Central is where most consumers resolve it.
  # Checked by hand once (0.10.2); now on every cut. Waits for propagation.
  CEFRIUM_SCRATCH="$ROOT/tmp" cef/cefrium_sdk/verify-central-provenance.sh "$VERSION" || {
    echo
    echo "ERROR: Central does not serve the signed bytes for $VERSION (see above)."
    echo "If Central was only slow, re-run: --from provenance"
    exit 1
  }
fi
if run web;     then hdr "update cefrium.com pages + committed repo docs"
  cef/cefrium_sdk/release-web-update.sh "$VERSION" "$PREV" "$(date +%F)" "$NOTES"
  # The committed cef/ docs (README, QUICKSTART, MIGRATION) were rewritten in the
  # `stats` phase, which derives their coordinates from Cefrium.VERSION. Assert
  # it here rather than rewrite again: a cut resumed with --from publish never
  # ran `stats`, and this is the last point before the site claims a version the
  # repo docs do not. They sat at 0.3.1 through 0.4.1 when nothing checked.
  cef/cefrium_sdk/update-doc-figures.py --check

  # Regenerate the API reference, and REFUSE to continue if it is incomplete.
  # Both generators fail silently (javadoc leaves the old tree when it aborts;
  # Dokka keeps a stale page set), so regen-docs.sh asserts that every public
  # symbol in the source has a generated page. Skipping this is how the site
  # came to serve "Cefrium 0.9.2" Javadoc during 0.9.4, with the 0.9.3 network
  # opt-out API missing from the reference entirely.
  if ! cef/cefrium_sdk/regen-docs.sh "$VERSION"; then
    echo
    echo "ERROR: the regenerated API docs are incomplete (see above)."
    echo "Not publishing docs for $VERSION. Fix, then: --from web"
    exit 1
  fi

  # Commit + push both doc repos, each as its own verified step. This used to be
  # a printed reminder and two hand-typed cycles per release.
  if [ "${CEFRIUM_NO_DOC_PUSH:-0}" = "1" ]; then
    echo "CEFRIUM_NO_DOC_PUSH=1 -- docs regenerated but NOT pushed; publish with:"
    echo "  cef/cefrium_sdk/release-publish-docs.sh $VERSION"
  else
    cef/cefrium_sdk/release-publish-docs.sh "$VERSION" || {
      echo "ERROR: docs did not publish; re-run: cef/cefrium_sdk/release-publish-docs.sh $VERSION"
      exit 1
    }
  fi
fi
PRUNE_STATE="not reached"
if run web; then
  # ORDER MATTERS, and it was wrong. This block used to sit AFTER prune and
  # play, though PHASES declares web before both. The prune phase exits 1 when
  # the release is incomplete, so on the 0.10.2 cut -- where "incomplete" only
  # meant Central had not propagated yet, six minutes as it turned out -- the
  # exit took this block with it. The source of a published release went
  # unpushed: the tag was on Codeberg and the branch was seven commits behind.
  # Pushing the source cannot depend on a cleanup step succeeding.
  #
  # Updating a page and verifying it updated are different claims, and the gap
  # between them already shipped: the published Javadoc sat at 0.9.2 while
  # 0.9.4 was the release. The anchored regex updates above fail SOFT on
  # purpose (a reworded anchor must not corrupt a page), so something has to
  # check the post-condition.
  hdr "verify cefrium.com matches $VERSION"
  if cef/cefrium_sdk/release-web-verify.sh "$VERSION" "$PREV"; then
    WEB_VERIFY_STATE="consistent"
  else
    WEB_VERIFY_STATE="INCONSISTENT -- see the FAIL lines; fix the pages, then: cef/cefrium_sdk/release-web-verify.sh $VERSION $PREV"
  fi

  # The web + docs trees are committed and pushed by release-publish-docs.sh in
  # the web phase. The cef repo is not, and a published release whose source is
  # only on this disk is not reproducible by anyone. Fast-forward only: a
  # diverged branch means something happened that a release script must not
  # paper over, so report it and let a human run push.sh --force.
  hdr "push the cef repo"
  if cef/cefrium_sdk/push.sh cef; then
    CEF_PUSH_STATE="pushed"
  else
    CEF_PUSH_STATE="FAILED -- run: cef/cefrium_sdk/push.sh cef [--force]"
  fi
  # push.sh moves COMMITS, so a pin left in the working tree survives a green
  # push and reaches nobody. Report it in the summary rather than failing a cut
  # that is otherwise complete, because the remedy is one commit.
  LEFTOVER="$(pins_dirty)"
  if [ -z "$LEFTOVER" ]; then
    PIN_STATE="all committed and pushed"
  else
    PIN_STATE="STILL UNCOMMITTED -- $(echo "$LEFTOVER" | awk '{print $2}' | tr '\n' ' ')"
  fi
fi

if run prune;   then hdr "prune previous versions (latest-only retention)"
  # Final verdict on the release as ADOPTERS see it: both registries, the
  # release record, and the engine/ABIs inside the PUBLISHED bytes. This used to
  # be typed out by hand as curl one-liners after a cut, which is exactly why
  # Central being stuck at 0.8.9 went unnoticed across four releases. If the new
  # version is not fully published, do not touch the old ones -- they are the
  # only thing consumers can still resolve.
  if ! cef/cefrium_sdk/release-status.sh "$VERSION"; then
    echo
    echo "Release $VERSION is INCOMPLETE (see above). Skipping the prune: the"
    echo "superseded versions stay up so consumers keep something resolvable."
    PRUNE_STATE="skipped -- $VERSION incomplete, fix publication first"
    echo "Registry prune: $PRUNE_STATE"
    exit 1
  fi
  # This used to sit behind an interactive gate, which meant it NEVER ran: a cut
  # is launched as a background job, the gate's `read` hit EOF, and the phase
  # died at the very end of an otherwise complete release. The registry still
  # served a stale 0.1.0 row months later as a result.
  #
  # So it runs unattended now. Retention is settled policy, not a per-release
  # decision (superseded versions live on as the git tag + GPG-signed
  # SHA256SUMS on their Codeberg release), and the protection that actually
  # matters is not a keystroke but the interlock inside prune-old-versions.sh:
  # nothing is deleted unless BOTH SDK coordinates of $VERSION are provably
  # being served. Set CEFRIUM_NO_PRUNE=1 to keep the old versions on purpose.
  if [ "${CEFRIUM_NO_PRUNE:-0}" = "1" ]; then
    echo "skipped: CEFRIUM_NO_PRUNE=1 (old versions kept in the registry)"
    PRUNE_STATE="skipped on request"
  elif cef/cefrium_sdk/prune-old-versions.sh "$VERSION" --yes; then
    PRUNE_STATE="done"
  else
    # A failed interlock is not a failed release -- the release is published and
    # fine, only the cleanup did not happen. Report it loudly rather than
    # aborting, so it cannot pass as success the way it did before.
    PRUNE_STATE="FAILED -- rerun: cef/cefrium_sdk/prune-old-versions.sh $VERSION --yes"
  fi
fi

if run play;    then hdr "Google Play (rebuild consumers, upload the apps)"
  # LAST, and after `publish`/`central` on purpose: the apps resolve the SDK
  # from the registry, so uploading before the coordinate is served would ship
  # an app built against the previous engine. Skipped with CEFRIUM_NO_PLAY=1
  # for a cut that is not meant to reach users (a re-cut, an SDK-only release).
  if [ "${CEFRIUM_NO_PLAY:-0}" = "1" ]; then
    echo "skipped: CEFRIUM_NO_PLAY=1"
    PLAY_STATE="skipped on request"
  elif cef/cefrium_sdk/release-play.sh "$VERSION" "${CEFRIUM_PLAY_TRACK:-internal}"; then
    PLAY_STATE="uploaded to '${CEFRIUM_PLAY_TRACK:-internal}'"
  else
    # Same reasoning as the prune: the SDK release itself is published and
    # fine, only the app rollout did not happen. Say so instead of implying
    # the whole cut failed.
    PLAY_STATE="FAILED -- rerun: cef/cefrium_sdk/release-play.sh $VERSION ${CEFRIUM_PLAY_TRACK:-internal}"
  fi
fi

echo; echo "Release $VERSION cut."
echo "Registry prune: $PRUNE_STATE"
echo "Google Play:    ${PLAY_STATE:-not run}"
echo "cef repo push:  ${CEF_PUSH_STATE:-not run}"
echo "pinned sources: ${PIN_STATE:-not checked}"
echo "cefrium.com:    ${WEB_VERIFY_STATE:-not checked}"
echo
echo "Remaining manual: remove any 'releasing shortly' notices."
echo "(Docs ARE regenerated and published by the web phase -- this line used to"
echo " say otherwise, and a stale list of manual steps teaches you to ignore it.)"
