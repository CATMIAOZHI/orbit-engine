#!/bin/bash
# Orchestrate a full Cefrium release cut. Chains the per-step scripts in order
# and STOPS for explicit confirmation before the two irreversible steps
# (immutable Maven publish, destructive registry prune). See RELEASING.md.
#
# Usage:
#   cef/cefrium_sdk/cut-release.sh <version> <prev-version> [--from <phase>] [--notes "<html>"]
# Phases (use --from to resume after a long build):
#   patches build merge compose plugin stats sanity publish flagship release web prune
#
# Bypass the confirmation gates non-interactively with CEFRIUM_YES=1 (CI only).
set -euo pipefail

VERSION="${1:?usage: cut-release.sh <version> <prev-version> [--from phase] [--notes ...]}"
PREV="${2:?need previous version (the one being superseded)}"; shift 2
FROM="patches"; NOTES="New release."
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
PHASES="patches build merge compose plugin stats sanity publish flagship release web prune"

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
  export 'target_os=="android"'=1
  python3 cef/tools/patcher.py || true   # 3 big patches report "failed" already-applied; benign
  for p in cef/patch/patches/android/*.patch; do
    for f in $(grep '^+++ ' "$p" | grep -v /dev/null | sed 's/^+++ [ab]\///;s/\t.*//'); do
      [ -f "$f" ] && touch "$f"; done; done
  touch cef/libcef/browser/android/cefrium_sdk_jni.cc cef/libcef/common/chrome/chrome_main_delegate_cef.cc
fi

if run build;   then hdr "build 4 variants"; cef/cefrium_sdk/build-all-variants.sh all; fi
if run merge;   then hdr "merge multi-ABI"; cef/cefrium_sdk/merge-multiabi.sh codecs; cef/cefrium_sdk/merge-multiabi.sh libre; fi
if run compose; then hdr "compose AAR"
  cp out/release/cefrium-sdk.aar cef/cefrium_compose/libs/
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
  echo "Sanity green — the fresh AAR is consumable."
fi

if run publish; then hdr "publish to Maven (IMMUTABLE)"
  gate "publish $VERSION to the Codeberg Maven registry (cannot be overwritten)"
  cef/cefrium_sdk/publish-maven.sh "$VERSION"   # uploads + smoke-tests BOTH coordinates
fi

if run flagship; then hdr "flagship APK on $VERSION"
  cp out/release/cefrium-sdk.aar "$ROOT/cefrium-sample/cefrium-browser/app/libs/cefrium-sdk.aar"
  ( cd "$ROOT/cefrium-sample/cefrium-browser" && ./gradlew clean assembleDebug --no-daemon )
fi
if run release; then hdr "Codeberg release (tag + GPG-signed SHA256SUMS + assets)"
  cef/cefrium_sdk/release-codeberg.sh "$VERSION" \
    "$ROOT/cefrium-sample/cefrium-browser/app/build/outputs/apk/debug/app-debug.apk"
fi
if run web;     then hdr "update cefrium.com pages"
  cef/cefrium_sdk/release-web-update.sh "$VERSION" "$PREV" "$(date +%F)" "$NOTES"
  echo "Review + commit the web repo (quickstart, releases) and push pages."
fi
if run prune;   then hdr "prune previous versions (DESTRUCTIVE, latest-only)"
  gate "prune every registry version except $VERSION"
  cef/cefrium_sdk/prune-old-versions.sh "$VERSION" --yes
fi

echo; echo "Release $VERSION cut. Remaining manual: commit/push web + cef, regenerate Javadoc/Dokka if API changed, remove any 'releasing shortly' notices."
