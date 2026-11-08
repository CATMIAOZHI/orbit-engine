#!/bin/bash
# Build all Cefrium SDK variants.
# Usage: cd chromium/src && cef/cefrium_sdk/build-all-variants.sh [arm64|x64|arm|all] [codecs|libre|all] [--clean]
#
# Examples:
#   cef/cefrium_sdk/build-all-variants.sh              # build all 6 variants (incremental: dev)
#   cef/cefrium_sdk/build-all-variants.sh arm64         # arm64 codecs + libre
#   cef/cefrium_sdk/build-all-variants.sh x64 codecs    # x64 with codecs only
#   cef/cefrium_sdk/build-all-variants.sh all --clean   # fresh out dirs (release reproducibility)
#
# Build modes: incremental by default (reuses out dirs unless the engine changed --
# fast, for DEV). --clean / CEFRIUM_CLEAN=1 forces a from-scratch build for RELEASE
# reproducibility (cut-release sets it by default). See CLAUDE.md "Two flows".
#
# Output: out/release/cefrium-sdk-{arm64,x64,arm}-{codecs,libre}.aar
# (arm = armeabi-v7a, 32-bit ARM)
#
# Must be run from chromium/src/ directory.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$(pwd)"
SLIM_AAR="$SCRIPT_DIR/slim_aar.py"
RELEASE_DIR="$SRC_DIR/out/release"

# Put depot_tools' pinned python (3.11.8) ahead of the system one. Chromium 156
# invokes its own `third_party/cpython3` for gn actions, so this is currently
# belt-and-braces -- but the system python here is 3.14, whose stricter argparse
# aborts Chromium's gyp scripts, and that failure lands hours into a release
# build. A release must not depend on what the invoking shell happened to have
# on PATH.
_DT="$(cd "$SRC_DIR/../.." 2>/dev/null && pwd)/depot_tools/python-bin"
if [ -x "$_DT/python3" ]; then
  export PATH="$_DT:$PATH"
fi

# RAM-aware default for ninja parallelism (~3 GB per CEF/Chromium compile job),
# mirroring the Debian CEF package's debian/rules. Prevents the OOM killer from
# terminating the link/compile (exit 137). An explicit CEFRIUM_NINJA_JOBS wins;
# we never exceed the core count.
if [ -z "${CEFRIUM_NINJA_JOBS:-}" ]; then
  _ram_gb=$(free -g 2>/dev/null | awk '/^Mem:/{print $2}'); _ram_gb=${_ram_gb:-8}
  _cores=$(nproc 2>/dev/null || echo 4)
  _ram_jobs=$(( _ram_gb / 3 )); [ "$_ram_jobs" -lt 1 ] && _ram_jobs=1
  CEFRIUM_NINJA_JOBS=$(( _ram_jobs < _cores ? _ram_jobs : _cores ))
  export CEFRIUM_NINJA_JOBS
  echo ">>> CEFRIUM_NINJA_JOBS auto-set to $CEFRIUM_NINJA_JOBS (RAM ${_ram_gb}GB/3 vs ${_cores} cores)"
fi

# Verify we're in chromium/src/
if [ ! -f ".gn" ]; then
  echo "ERROR: Run this script from chromium/src/"
  echo "  cd chromium/src && cef/cefrium_sdk/build-all-variants.sh"
  exit 1
fi

# Find GN -- try depot_tools relative to src/, then PATH
GN=""
for candidate in "../depot_tools/gn" "../../depot_tools/gn"; do
  if [ -f "$candidate" ]; then
    GN="$(cd "$(dirname "$candidate")" && pwd)/$(basename "$candidate")"
    break
  fi
done
if [ -z "$GN" ]; then
  GN="$(which gn 2>/dev/null || true)"
fi
if [ -z "$GN" ] || [ ! -f "$GN" ]; then
  echo "ERROR: Cannot find gn. Add depot_tools to PATH."
  exit 1
fi
echo "Using GN: $GN"

# Positional args: [arch] [codecs]. Flag: --clean forces fresh out dirs
# (release reproducibility); equivalent to CEFRIUM_CLEAN=1.
ARCH="all"; CODECS="all"; _posn=0
for _arg in "$@"; do
  case "$_arg" in
    --clean) CEFRIUM_CLEAN=1; export CEFRIUM_CLEAN ;;
    *) _posn=$((_posn + 1))
       if [ "$_posn" = 1 ]; then ARCH="$_arg"; elif [ "$_posn" = 2 ]; then CODECS="$_arg"; fi ;;
  esac
done

mkdir -p "$RELEASE_DIR"

# Common GN args
COMMON_ARGS='
  is_official_build = true
  is_debug = false
  is_component_build = false
  clang_use_chrome_plugins = false
  android_static_analysis = "off"
  symbol_level = 0
  blink_symbol_level = 0
  is_clang = true
  enable_jni_multiplexing = false
  use_hashed_jni_names = false
  v8_use_external_startup_data = false
  disable_fieldtrial_testing_config = true
  # A release is a stable channel build. "default" reports Channel::UNKNOWN,
  # and Chromium then hands Web Push subscriptions the FCM staging endpoint
  # (jmt17.google.com) instead of fcm.googleapis.com.
  android_channel = "stable"
'

build_variant() {
  local cpu="$1"
  local codec="$2"
  local target_cpu

  case "$cpu" in
    arm64) target_cpu="arm64" ;;
    arm)   target_cpu="arm" ;;    # armeabi-v7a (FEATURE-085)
    *)     target_cpu="x64" ;;
  esac

  local dir_name="Official_android_${cpu}_${codec}"
  local build_dir="out/$dir_name"
  local out_name="cefrium-sdk-${cpu}-${codec}.aar"

  echo ""
  echo "============================================"
  echo "  Building: $out_name"
  echo "  CPU: $target_cpu  Codecs: $codec"
  echo "============================================"
  echo ""

  # PGO with the profile Chrome ships in the tree, arm64 only (there is none
  # for x64). V8's builtins profile comes from the checkout_pgo_profiles hook.
  local pgo_args='
  chrome_pgo_phase = 0
'
  if [ "$cpu" = "arm64" ]; then
    if [ ! -f "$SRC_DIR/v8/tools/builtins-pgo/profiles/x64.profile" ]; then
      echo "ERROR: V8 builtins PGO profile missing. Set \"checkout_pgo_profiles\": True"
      echo "  in .gclient custom_vars and run gclient runhooks, or from chromium/src:"
      echo "  python3 v8/tools/builtins-pgo/download_profiles.py download --depot-tools <depot_tools>"
      return 1
    fi
    pgo_args='
  chrome_pgo_phase = 2
  pgo_data_path = "//chrome/android/orderfiles/arm64/pgo_profile.arm64.profdata"
'
  elif [ "$cpu" = "arm" ] && [ -f "$SRC_DIR/chrome/android/orderfiles/arm/pgo_profile.arm.profdata" ]; then
    pgo_args='
  chrome_pgo_phase = 2
  pgo_data_path = "//chrome/android/orderfiles/arm/pgo_profile.arm.profdata"
'
  fi

  local codec_args=""
  if [ "$codec" = "codecs" ]; then
    codec_args='
  proprietary_codecs = true
  ffmpeg_branding = "Chrome"
'
  else
    codec_args='
  proprietary_codecs = false
'
  fi

  # Recreate the build dir from scratch if the engine version changed since it was
  # last built here. Stale Java build metadata (build_config.json + turbine graph)
  # survives a BUILD-number bump in a reused out dir and triggers a phantom Java
  # turbine "dependency cycle" (actor -> ... -> actor); only a fresh dir clears it.
  # The stamp auto-detects, so same-version rebuilds stay incremental (fast).
  local engine_stamp
  engine_stamp="$(git -C "$SRC_DIR" describe --tags 2>/dev/null || echo unknown)"
  # Recreate fresh unless the dir exists AND its stamp matches the current engine.
  # A missing stamp (dir predates this mechanism, e.g. a pre-bump 148 dir) counts
  # as a mismatch -> recreate, so stale dirs are NEVER reused without a manual wipe.
  if [ "${CEFRIUM_CLEAN:-0}" = "1" ] && [ -d "$build_dir" ]; then
    # Release builds (CEFRIUM_CLEAN=1 / --clean) wipe unconditionally: a published,
    # immutable artifact must be reproducible from committed source, not from
    # accumulated incremental state (a stale .o ninja can't detect would otherwise
    # ride into the release). Dev builds leave this off and rely on the engine-stamp
    # auto-detect below to stay incremental (fast). See CLAUDE.md "Two flows".
    echo ">>> CEFRIUM_CLEAN=1 -> wiping $dir_name for a clean from-scratch build"
    rm -rf "$build_dir"
  elif [ -d "$build_dir" ] && \
     [ "$(cat "$build_dir/.cefrium_engine" 2>/dev/null)" != "$engine_stamp" ]; then
    echo ">>> engine stamp mismatch/missing for $dir_name ('$(cat "$build_dir/.cefrium_engine" 2>/dev/null)' != '$engine_stamp'); recreating fresh"
    rm -rf "$build_dir"
  fi
  mkdir -p "$build_dir"
  echo "$engine_stamp" > "$build_dir/.cefrium_engine"
  cat > "$build_dir/args.gn" << EOF
# Cefrium SDK -- ${cpu} ${codec}
# Auto-generated by build-all-variants.sh
target_os = "android"
target_cpu = "${target_cpu}"
${COMMON_ARGS}
${pgo_args}
${codec_args}
EOF

  echo ">>> gn gen $dir_name"
  "$GN" gen "$build_dir" || {
    echo "ERROR: gn gen failed for $dir_name"
    return 1
  }

  echo ">>> ninja $dir_name"
  # CEFRIUM_NINJA_JOBS caps ninja parallelism to bound peak memory. The full
  # Chrome-Android build runs many memory-heavy tsc/eslint (node) + clang/link
  # jobs in parallel; at the default -j it can exceed RAM and the OOM killer
  # kills jobs with exit code 137. Set e.g. CEFRIUM_NINJA_JOBS=10 on a 64 GB box.
  # Capture the output so a FAILURE can be analysed instead of scrolled. On an
  # engine bump the interesting part is buried thousands of lines up, and reading
  # it by eye is slow and unrepeatable -- CEF ships an analyser for exactly this.
  blog="$SRC_DIR/../../tmp/build-logs/ninja-$dir_name.log"
  mkdir -p "$(dirname "$blog")"
  # `ninja | tee` must NOT decide success: this script runs under `set -e` with
  # NO `pipefail`, so the pipeline's status would be tee's (always 0) and a
  # failed build would sail through as a pass. Read ninja's own status out of
  # PIPESTATUS, which is correct with or without pipefail.
  # Chromium's pinned ninja, never whatever `ninja` is first on PATH: Debian's
  # 1.13 writes a v7 .ninja_log, the pinned 1.12 a v6 one, and each discards
  # the other's log, so alternating them rebuilt all ~62k steps.
  "$SRC_DIR/third_party/ninja/ninja" ${CEFRIUM_NINJA_JOBS:+-j "$CEFRIUM_NINJA_JOBS"} \
    -C "$build_dir" cef:libcef cef/cefrium_sdk:cefrium_aar 2>&1 | tee "$blog"
  ninja_rc=${PIPESTATUS[0]}
  if [ "$ninja_rc" -ne 0 ]; then
    echo "ERROR: ninja failed for $dir_name (exit $ninja_rc)"
    # Upstream's analyser (cef/tools/claude/, unit-tested) builds an error index
    # with line references and, given both versions, flags API changes between
    # them. Strictly additive: on any problem we still fall back to the raw log.
    analyzer="$SRC_DIR/cef/tools/claude/analyze_build_output.py"
    if [ -f "$analyzer" ]; then
      echo ">>> analysing the failure (upstream analyze_build_output.py)"
      python3 "$analyzer" "$blog" --no-color 2>/dev/null | head -60 \
        || echo "    (analyser could not parse it; full log: $blog)"
    fi
    echo "    full ninja log: $blog"
    return 1
  fi

  # The checked-in GEN_JNI.java is the one build input nothing invalidates, so
  # after an engine bump it goes stale silently: this build succeeds and the AAR
  # crashes on device with NoSuchMethodError. Fail here instead.
  echo ">>> GEN_JNI freshness $dir_name"
  "$SCRIPT_DIR/regen-gen-jni.sh" --check "$build_dir" || {
    echo "ERROR: GEN_JNI is stale for $dir_name."
    echo "       Fix: cef/cefrium_sdk/regen-gen-jni.sh $build_dir"
    return 1
  }

  echo ">>> slim_aar $dir_name"
  python3 "$SLIM_AAR" \
    --input "$build_dir/cefrium-raw.aar" \
    --output "$RELEASE_DIR/$out_name" || {
    echo "ERROR: slim_aar failed for $dir_name"
    return 1
  }

  echo ">>> Done: $RELEASE_DIR/$out_name"
  ls -lh "$RELEASE_DIR/$out_name"
}

# Determine variants
variants=()
if [ "$ARCH" = "all" ] || [ "$ARCH" = "arm64" ]; then
  [ "$CODECS" = "all" ] || [ "$CODECS" = "codecs" ] && variants+=("arm64:codecs")
  [ "$CODECS" = "all" ] || [ "$CODECS" = "libre" ] && variants+=("arm64:libre")
fi
if [ "$ARCH" = "all" ] || [ "$ARCH" = "arm" ]; then
  [ "$CODECS" = "all" ] || [ "$CODECS" = "codecs" ] && variants+=("arm:codecs")
  [ "$CODECS" = "all" ] || [ "$CODECS" = "libre" ] && variants+=("arm:libre")
fi
if [ "$ARCH" = "all" ] || [ "$ARCH" = "x64" ]; then
  [ "$CODECS" = "all" ] || [ "$CODECS" = "codecs" ] && variants+=("x64:codecs")
  [ "$CODECS" = "all" ] || [ "$CODECS" = "libre" ] && variants+=("x64:libre")
fi

echo "Building ${#variants[@]} variants: ${variants[*]}"

for v in "${variants[@]}"; do
  cpu="${v%%:*}"
  codec="${v##*:}"
  build_variant "$cpu" "$codec"
done

echo ""
echo "============================================"
echo "  All variants built!"
echo "============================================"
ls -lh "$RELEASE_DIR/"
