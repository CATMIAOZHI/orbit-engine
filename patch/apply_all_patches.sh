#!/bin/bash
# apply_all_patches.sh — Reconstruct patched Chromium from clean state
#
# This script applies ALL CEF patches to a clean Chromium 148 checkout.
# Run from chromium/src/ directory.
#
# Usage:
#   cd <cef-dev>/chromium/src
#   bash cef/patch/apply_all_patches.sh
#
# Prerequisites:
#   - Clean Chromium checkout at tag 148.0.7778.167
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

echo "=== Step 2: Apply CEF main patches (patcher.py) ==="
export 'target_os=="android"'=1
python3 cef/tools/patcher.py

echo ""
echo "=== Step 3: Apply android patches that need manual handling ==="

# base_test_jni_onload: remove hide_all_but_jni_onload for component target
# In Chromium 148, test_trace_processor is already 'component' type but
# still has configs -= hide_all_but_jni_onload which doesn't exist for components
if git apply --check cef/patch/patches/android/base_test_jni_onload.patch 2>/dev/null; then
    git apply cef/patch/patches/android/base_test_jni_onload.patch
    echo "APPLIED: base_test_jni_onload_v2"
else
    echo "SKIP: base_test_jni_onload_v2 (already applied or conflict)"
fi

# chrome_repack_locales: CEF main patch adds cef_strings without !is_android guard.
# On Android, cef_strings .pak files for all locales are not generated,
# causing 208 GN input errors. Add the guard.
if grep -q 'if (enable_cef)' chrome/chrome_repack_locales.gni 2>/dev/null; then
    sed -i 's/if (enable_cef) {/if (enable_cef \&\& !is_android) {/' chrome/chrome_repack_locales.gni
    echo "APPLIED: chrome_repack_locales is_android guard"
elif grep -q 'enable_cef && !is_android' chrome/chrome_repack_locales.gni 2>/dev/null; then
    echo "SKIP: chrome_repack_locales is_android guard (already applied)"
fi

echo ""
echo "=== Step 4: Apply Cefrium-specific Chromium patches ==="
for patch in password_manager_autofill_guard accessibility_expanded_state_guard; do
    PATCH_FILE="cef/patch/patches/android/${patch}.patch"
    if [ -f "$PATCH_FILE" ]; then
        if git apply --check "$PATCH_FILE" 2>/dev/null; then
            git apply "$PATCH_FILE"
            echo "APPLIED: $patch"
        else
            echo "SKIP: $patch (already applied or conflict)"
        fi
    fi
done

echo ""
echo "=== Step 5: Touch all patched files ==="
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
echo "=== Step 6: Generate build files ==="
"$GN" gen out/Official_android_arm64
echo ""
echo "=== Done. Run: ninja -C out/Official_android_arm64 cef:libcef cef/cefrium_sdk:cefrium_aar ==="
