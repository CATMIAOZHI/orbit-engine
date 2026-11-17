#!/usr/bin/env python3
# Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Fill the generated parts of cefrium.com/components/ from the release.

  components-page.py --aar <cefrium-sdk.aar> --notice <NOTICE.txt> \\
      --resolved <resolved.txt> --page <web>/components/index.html [--check]

Rewrites what sits between <!-- gen:NAME --> and <!-- /gen:NAME --> markers:

  deps-core, deps-gms   one table row per Maven dependency of cefrium-sdk-core
                        and of cefrium-gms, with what it is for
                        (components.json)
  deps-resolved         every other library Gradle resolves for cefrium-sdk
                        (components-resolve.sh), with what pulls it in, and
                        whether it comes only with cefrium-gms
  native                every third-party component of the engine, from the
                        release NOTICE
  counts                the numbers the prose quotes

Fails if the POM has a dependency components.json does not explain, or
components.json explains one the POM no longer has: an engine bump that adds
a library has to say what it is for. --check exits 1 if the page differs.
"""

import argparse
import html
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import maven_vendored  # noqa: E402

PURPOSES = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        'components.json')


def dependencies(aar):
    deps = maven_vendored.read_deps(maven_vendored.POM_DEPS_FILE)
    deps += maven_vendored.runtime_dependencies(aar)
    return deps


def notice_components(path):
    names, lines = set(), open(path, encoding='utf-8').read().splitlines()
    for i, line in enumerate(lines[:-1]):
        if line == '-' * 20 and lines[i + 1].startswith('License notice for '):
            names.add(lines[i + 1][len('License notice for '):].strip())
    if not names:
        sys.exit('components-page: no "License notice for" entries in %s' % path)
    return sorted(names, key=str.lower)


def read_resolved(path):
    libs, core = {}, set()
    for line in open(path, encoding='utf-8'):
        ga, version, parents, part = line.rstrip('\n').split('\t')
        libs[ga] = (version, [p for p in parents.split(',') if p])
        if part == 'core':
            core.add(ga)
    if not libs:
        sys.exit('components-page: %s is empty' % path)
    return libs, core


def resolved_rows(libs, direct, core):
    out = []
    for ga in sorted(libs):
        if ga in direct:
            continue
        version, parents = libs[ga]
        shown = ', '.join(p.split(':')[1] for p in parents[:3])
        if len(parents) > 3:
            shown += ' and %d more' % (len(parents) - 3)
        out.append('<tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>'
                   % (ga, version, html.escape(shown),
                      'core' if ga in core else 'cefrium-gms only'))
    return '\n'.join(out)


def rows(deps, purposes):
    out = []
    for c in sorted(deps):
        g, a, v = c.split(':')
        out.append('<tr><td><code>%s:%s</code></td><td>%s</td><td>%s</td></tr>'
                   % (g, a, v, html.escape(purposes[g + ':' + a])))
    return '\n'.join(out)


def blocks(aar, notice, resolved):
    purposes = {k: v for k, v in json.load(open(PURPOSES)).items()
                if not k.startswith('_')}
    deps = dependencies(aar)
    have = {':'.join(c.split(':')[:2]) for c in deps}
    missing, extra = sorted(have - purposes.keys()), sorted(purposes.keys() - have)
    if missing or extra:
        sys.exit('components-page: components.json is out of date for this '
                 'release.\n  explain: %s\n  remove: %s' % (missing, extra))
    gms = [c for c in deps if maven_vendored.is_google(c)]
    core = [c for c in deps if not maven_vendored.is_google(c)]
    native = notice_components(notice)
    libs, reach = read_resolved(resolved)
    missing = sorted(have - libs.keys())
    if missing:
        sys.exit('components-page: %s lacks direct dependencies %s: resolved '
                 'for another release?' % (resolved, missing))
    core_total = len([ga for ga in libs if ga in reach])
    counts = ('<span id="n-deps">%d</span> Maven libraries: '
              '<span id="n-core">%d</span> in <code>cefrium-sdk-core</code> and '
              '<span id="n-gms">%d</span> in <code>cefrium-gms</code>. With what '
              'they require in turn, Gradle resolves <span id="n-all">%d</span>; '
              'an app on <code>cefrium-sdk-core</code> alone gets '
              '<span id="n-all-core">%d</span> of them. The engine itself builds '
              'in <span id="n-native">%d</span> third-party components.'
              % (len(deps), len(core), len(gms), len(libs), core_total,
                 len(native)))
    return {
        'counts': counts,
        'deps-core': rows(core, purposes),
        'deps-gms': rows(gms, purposes),
        'deps-resolved': resolved_rows(libs, have, reach),
        'native': '\n'.join('<li>%s</li>' % html.escape(n) for n in native),
    }


def fill(page, generated):
    text = open(page, encoding='utf-8').read()
    for name, body in generated.items():
        pat = re.compile(r'(<!-- gen:%s -->\n)(?:.*?\n)?(<!-- /gen:%s -->)'
                         % (re.escape(name), re.escape(name)), re.S)
        if not pat.search(text):
            sys.exit('components-page: %s has no gen:%s markers' % (page, name))
        text = pat.sub(lambda m: m.group(1) + body + '\n' + m.group(2), text)
    return text


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--aar', required=True)
    p.add_argument('--notice', required=True)
    p.add_argument('--resolved', required=True)
    p.add_argument('--page', required=True)
    p.add_argument('--check', action='store_true')
    args = p.parse_args()
    new = fill(args.page, blocks(args.aar, args.notice, args.resolved))
    old = open(args.page, encoding='utf-8').read()
    if args.check:
        if new != old:
            sys.exit('components-page: %s is stale; run without --check'
                     % args.page)
        print('OK: %s matches the release' % args.page)
        return
    if new != old:
        with open(args.page, 'w', encoding='utf-8') as f:
            f.write(new)
    print('wrote %s' % args.page)


if __name__ == '__main__':
    main()
