#!/bin/bash
# apply_all_patches.sh -- Reconstruct patched Chromium from clean state
#
# This script applies ALL CEF patches (core + Android-conditional) to a clean
# Chromium checkout. Run from chromium/src/ directory.
#
# Usage:
#   cd <your-checkout>/chromium/src
#   bash cef/patch/apply_all_patches.sh
#
# Prerequisites:
#   - Clean Chromium checkout at tag 149.0.7827.53
#   - CEF source in src/cef/ (branch android-port)
#   - depot_tools in PATH or at ../depot_tools/

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CEF_DIR="$SRC_DIR/cef"
GN="${SRC_DIR}/../depot_tools/gn"

cd "$SRC_DIR"

echo "=== Step 1: Verify clean Chromium state ==="
DIRTY=$(git diff --stat HEAD 2>/dev/null | tail -1)
if [ -n "$DIRTY" ]; then
    echo "WARNING: Chromium tree has uncommitted changes."
    echo "$DIRTY"
    read -p "Restore to clean state? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        git checkout -- .
        echo "Restored to clean state."
    else
        echo "Aborting. Clean the tree first with: git checkout -- ."
        exit 1
    fi
fi

echo "=== Step 2: Apply ALL CEF patches (core + Android-conditional) ==="
# Use the wrapper, not `export 'target_os=="android"'=1; python3 cef/tools/patcher.py`.
# That illegal env name is mangled by the shell and the Android patches are
# silently skipped. apply-patches.py sets the condition key correctly and runs
# patcher.py in-process. Every Android patch is registered in patch.cfg with
# condition target_os=="android" -- there is NO separate git-apply or sed step.
python3 cef/cefrium_sdk/apply-patches.py

echo ""
echo "=== Step 3: Touch all patched files ==="
TOUCHED=0
for patch in cef/patch/patches/*.patch cef/patch/patches/android/*.patch; do
    [ -f "$patch" ] || continue
    for f in $(grep "^--- a/" "$patch" 2>/dev/null | sed 's/^--- a\///'); do
        if [ -f "$f" ]; then
            touch "$f"
            TOUCHED=$((TOUCHED + 1))
        fi
    done
done
echo "Touched $TOUCHED files"

echo ""
echo "=== Step 4: Generate build files ==="
# Recreate the out dir fresh if the engine version changed since it was last built
# here. Stale Java build metadata (build_config.json + turbine graph) survives a
# BUILD-number bump in a reused out dir and triggers a phantom Java turbine
# "dependency cycle"; only a fresh dir clears it. Same-version re-applies stay
# incremental.
ENGINE_STAMP="$(git -C "$SRC_DIR" describe --tags 2>/dev/null || echo unknown)"
# Recreate fresh unless the dir exists AND its stamp matches. A missing stamp (dir
# predates this mechanism / pre-bump 148 dir) counts as mismatch -> recreate, so a
# stale dir is NEVER reused without a manual wipe.
if [ -d out/Official_android_arm64 ] && \
   [ "$(cat out/Official_android_arm64/.cefrium_engine 2>/dev/null)" != "$ENGINE_STAMP" ]; then
    echo "Engine stamp mismatch/missing -> recreating out/Official_android_arm64 fresh (clears stale turbine cycle)"
    rm -rf out/Official_android_arm64
fi
mkdir -p out/Official_android_arm64
echo "$ENGINE_STAMP" > out/Official_android_arm64/.cefrium_engine
"$GN" gen out/Official_android_arm64
echo ""
echo "=== Done. Run: ninja -C out/Official_android_arm64 cef:libcef cef/cefrium_sdk:cefrium_aar ==="
