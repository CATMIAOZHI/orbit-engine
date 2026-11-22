#!/usr/bin/env python3
# Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Rewrite one patch as the diff from given bases to the working tree.

  patch_against_base.py <name> [<file>=<base> ...]      (run from chromium/src)

<name> is as patch.cfg lists it (android/foo). One section per file the patch
touches: from <base> when given, else from `git show HEAD:<file>`. A file that
another patch also changes needs its base: the file with every EARLIER patch
in patch.cfg applied and this one not. patch_updater.py --resave diffs against
HEAD only, so for such a file it absorbs the other patch's hunks
(check-patch-format.sh catches that). Output is -p0, no prefix.
"""

import os
import re
import subprocess
import sys
import tempfile


def main():
    name = sys.argv[1]
    bases = dict(a.split('=', 1) for a in sys.argv[2:])
    patch = os.path.join('cef/patch/patches', name + '.patch')
    files = list(dict.fromkeys(
        re.findall(r'^\+\+\+ (\S+)', open(patch).read(), re.M)))
    if not files:
        sys.exit('patch_against_base: no files in %s' % patch)
    unknown = set(bases) - set(files)
    if unknown:
        sys.exit('patch_against_base: %s not in %s' % (sorted(unknown), patch))
    out = []
    for f in files:
        base = bases.get(f)
        if base is None:
            fd, base = tempfile.mkstemp()
            with os.fdopen(fd, 'w') as fh:
                fh.write(subprocess.run(['git', 'show', 'HEAD:' + f],
                                        capture_output=True, text=True,
                                        check=True).stdout)
        diff = subprocess.run(['diff', '-u', '--label', f, '--label', f, base, f],
                              capture_output=True, text=True).stdout
        if not diff:
            sys.exit('patch_against_base: no change in %s' % f)
        old, new = (subprocess.run(['git', 'hash-object', p], capture_output=True,
                                   text=True, check=True).stdout.strip()[:7]
                    for p in (base, f))
        out.append('diff --git %s %s\nindex %s..%s 100644\n%s' % (f, f, old, new, diff))
    with open(patch, 'w') as fh:
        fh.write(''.join(out))
    print('%s: %d file(s), %d hunk(s)' % (patch, len(files),
                                         ''.join(out).count('\n@@')))


if __name__ == '__main__':
    main()
