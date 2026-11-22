#!/usr/bin/env python3
# Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Resolve a patch/patch.cfg conflict while rebasing android-port.

The result is upstream's entries (index stage 2, the branch being rebased
onto) without any android/ entry, then the replayed commit's (stage 3)
android/ entries in its order. Entries are the top-level '  {' ... '  }'
blocks, comments included. Run from cef/ with the conflict in the index.

Fails, changing nothing, when the replayed commit changes one of upstream's
own entries: taking upstream's would drop that change, so it is resolved by
hand.
"""

import re
import subprocess
import sys

PATH = 'patch/patch.cfg'
ENTRY = re.compile(r'^  \{\n.*?^ {2,3}\},?\n', re.S | re.M)


def stage(n):
    return subprocess.run(['git', 'show', ':%d:%s' % (n, PATH)],
                          capture_output=True, text=True, check=True).stdout


def split(text):
    blocks = list(ENTRY.finditer(text))
    if not blocks:
        sys.exit('rebase_patchcfg: no entries found')
    return (text[:blocks[0].start()], [b.group(0) for b in blocks],
            text[blocks[-1].end():])


def norm(block):
    lines = block.rstrip('\n').split('\n')
    lines[-1] = '  }'  # closing brace, whatever its indent or comma
    return '\n'.join(l.rstrip() for l in lines) + '\n'


def android(block):
    return "'name': 'android/" in block


def main():
    base, head, commit = stage(1), stage(2), stage(3)
    if ([norm(b) for b in split(base)[1] if not android(b)] !=
            [norm(b) for b in split(commit)[1] if not android(b)]):
        sys.exit('rebase_patchcfg: the commit changes a non-android entry; '
                 'resolve by hand')
    pre, head_entries, post = split(head)
    entries = ([norm(b) for b in head_entries if not android(b)] +
               [norm(b) for b in split(commit)[1] if android(b)])
    body = ''.join(e.rstrip('\n') + (',\n' if i < len(entries) - 1 else '\n')
                   for i, e in enumerate(entries))
    with open(PATH, 'w') as f:
        f.write(pre + body + post)
    print('%d upstream + %d android entries' %
          (sum(not android(b) for b in head_entries),
           sum(android(b) for b in split(commit)[1])))


if __name__ == '__main__':
    main()
