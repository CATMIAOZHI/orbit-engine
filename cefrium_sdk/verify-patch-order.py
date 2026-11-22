#!/usr/bin/env python3
# Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Prove a clean reapply without resetting chromium/src.

  verify-patch-order.py <android-name> ...        (run from chromium/src)

For every file the named android patches touch: the pristine file (git HEAD),
then every patch in patch.cfg order that touches it (core and android),
applied in a scratch dir. The result must equal the working tree file. A
patch regenerated against the wrong base (a later patch's lines in its
context) fails here, as it would on the next clean apply. Exit 1 on any
failure or difference.
"""
import ast, os, re, shutil, subprocess, sys, tempfile
names = sys.argv[1:]
cfg = ast.literal_eval(open('cef/patch/patch.cfg').read().split('patches =', 1)[1])
order = [e['name'] for e in cfg]
def files_of(name):
    t = open('cef/patch/patches/%s.patch' % name).read()
    return set(re.findall(r'^\+\+\+ (\S+)', t, re.M))
touch = {n: files_of(n) for n in order}
targets = set().union(*(touch['android/' + n] for n in names))
bad = 0
for f in sorted(targets):
    d = tempfile.mkdtemp()
    os.makedirs(os.path.join(d, os.path.dirname(f)), exist_ok=True)
    with open(os.path.join(d, f), 'w') as fh:
        fh.write(subprocess.run(['git', 'show', 'HEAD:' + f], capture_output=True, text=True).stdout)
    used = [n for n in order if f in touch[n]]
    for n in used:
        r = subprocess.run(['git', 'apply', '-p0', '--include=' + f,
                            os.path.abspath('cef/patch/patches/%s.patch' % n)],
                           cwd=d, capture_output=True, text=True)
        if r.returncode:
            print('FAIL %s: %s does not apply after %s\n  %s' % (f, n, used[:used.index(n)], r.stderr.strip()[:300]))
            bad += 1; break
    else:
        same = open(os.path.join(d, f)).read() == open(f).read()
        print('%s %s  (%s)' % ('OK  ' if same else 'DIFF', f, ' -> '.join(used)))
        bad += 0 if same else 1
    shutil.rmtree(d)
sys.exit(1 if bad else 0)
