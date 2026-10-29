#!/bin/bash
# check-patch-format.sh -- enforce the CEF patch storage format.
#
# CEF's patcher.py applies patches with `git apply -p0` (cef/tools/git_util.py),
# which strips NO path prefix. So every patch under cef/patch/patches/ must use
# bare paths (`--- chrome/...`, `+++ chrome/...`, `diff --git chrome/x chrome/x`)
# and NOT git's default `a/`/`b/` prefix. A prefixed patch fails to apply on a
# clean tree with "No such file or directory" -- a failure that is invisible on
# an incremental tree (it "works" only because the change is already there).
#
# This guard fails if any patch carries an a/ or b/ prefix, so the regression
# cannot reach a release. See cef/PATCHING-CEFRIUM.md.
#
# Usage:  cef/cefrium_sdk/check-patch-format.sh
# Exit:   0 = all patches are -p0 / no-prefix; 1 = at least one is prefixed.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"          # chromium/src
PATCHDIR="$SRC/cef/patch/patches"
[ -d "$PATCHDIR" ] || { echo "FAIL: no patch dir at $PATCHDIR"; exit 1; }

bad=0
while IFS= read -r p; do
  # A prefixed patch has '--- a/' / '+++ b/' header lines, or a
  # 'diff --git a/... b/...' line. Bare-path patches have none of these.
  if grep -qE '^(--- a/|\+\+\+ b/|diff --git a/[^ ]+ b/)' "$p"; then
    echo "  PREFIXED (must be -p0 / no-prefix): ${p#$SRC/}"
    bad=$((bad+1))
  fi
done < <(find "$PATCHDIR" -name '*.patch' | sort)

if [ "$bad" -gt 0 ]; then
  echo "FAIL: $bad patch(es) use git's a/b prefix. Regenerate with"
  echo "      'git diff --no-prefix'. See cef/PATCHING-CEFRIUM.md."
  exit 1
fi
echo "All patches are -p0 / no-prefix format."

# Where two patches touch the same file, a `patch_updater.py --resave` of one
# diffs the file against pristine Chromium and absorbs the other's hunks; the
# result no longer applies after the other patch on a clean tree (0.16.0's
# multi_profile swallowed chrome_browser_profiles and push_transport, seen only
# at the next sync). Fail if a patch adds lines another patch adds to the same
# file.
python3 - "$PATCHDIR" <<'PY' || exit 1
import os, re, sys
added = {}  # file -> {patch: set of distinctive added lines}
root = sys.argv[1]
for d, _, fs in os.walk(root):
    for f in fs:
        if not f.endswith('.patch'):
            continue
        name = os.path.relpath(os.path.join(d, f), root)[:-6]
        cur = None
        for line in open(os.path.join(d, f), errors='replace'):
            m = re.match(r'diff --git (\S+) ', line)
            if m:
                cur = added.setdefault(m.group(1), {}).setdefault(name, set())
            elif cur is not None and line.startswith('+') and not line.startswith('+++'):
                t = line[1:].strip()
                if len(t) > 12 and not t.startswith(('//', '#endif', '#else', '}')):
                    cur.add(t)
bad = 0
for path, by in sorted(added.items()):
    names = sorted(by)
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            common = by[a] & by[b]
            if len(common) >= 3:
                print(f"  ABSORBED: {a} and {b} both add {len(common)} lines to {path}, e.g.")
                print(f"            {sorted(common)[0][:90]}")
                bad += 1
if bad:
    print(f"FAIL: {bad} file(s) carry another patch's hunks. Restore the patch from")
    print("      before the resave, or use resave-patches.sh (it skips shared files).")
    sys.exit(1)
print("No patch carries another patch's hunks.")
PY
