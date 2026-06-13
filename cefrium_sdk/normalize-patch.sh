#!/bin/bash
# normalize-patch.sh -- transform a git-diff patch into CEF's -p0 / no-prefix
# storage format, in place.
#
# The proven way to CREATE a patch (a hard-won lesson from the Debian CEF
# package) is: copy the original file into a git workdir, edit it, and run
# `git diff`. That technique is reliable and stays. But `git diff` emits git's
# default `a/`/`b/` path prefixes, while CEF's patcher applies with `git apply
# -p0` (no prefix). This script is the TRANSFORM step between the two: it strips
# the a/ and b/ prefixes from the diff headers so the patch applies under -p0.
#
# So the workflow is:  create with `git diff`  ->  normalize-patch.sh  ->  register.
# (`git diff --no-prefix` does both at once; this script makes the plain-`git diff`
# habit safe, and repairs any already-prefixed patch.)
#
# Idempotent: a patch already in -p0 format is left unchanged. See cef/PATCHING-CEFRIUM.md.
#
# Usage:  cef/cefrium_sdk/normalize-patch.sh <patch> [<patch> ...]
set -uo pipefail
[ $# -ge 1 ] || { echo "usage: normalize-patch.sh <patch> [<patch> ...]"; exit 2; }

for f in "$@"; do
  [ -f "$f" ] || { echo "  SKIP (not a file): $f"; continue; }
  python3 - "$f" <<'PY'
import re, sys
f = sys.argv[1]
lines = open(f, encoding='utf-8').read().splitlines(keepends=True)
out, changed = [], False
for ln in lines:
    o = ln
    if ln.startswith('diff --git a/'):
        ln = re.sub(r'^diff --git a/(\S+) b/(\S+)', r'diff --git \1 \2', ln)
    elif ln.startswith('--- a/'):
        ln = '--- ' + ln[6:]
    elif ln.startswith('+++ b/'):
        ln = '+++ ' + ln[6:]
    if ln != o:
        changed = True
    out.append(ln)
if changed:
    open(f, 'w', encoding='utf-8').write(''.join(out))
    print(f"  normalized to -p0: {f}")
else:
    print(f"  already -p0: {f}")
PY
done
