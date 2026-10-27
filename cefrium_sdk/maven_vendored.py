#!/usr/bin/env python3
# Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
"""Find which classes in the SDK's classes.jar come from Maven artifacts.

dist_aar flattens every Java dependency into one classes.jar. A class that
also exists on Maven (Guava, Material, GMS, Firebase, ...) then collides with
the consumer's own copy: "Duplicate class" at build time. slim_aar.py strips
those classes and the SDK POM declares the artifacts instead, so Gradle
resolves one copy.

Ownership is computed from Chromium's own prebuilts, the bytes it compiled
against, never from a hand-written package list. maven-deps.txt records the
result; a Chromium bump that changes it shows up as a diff of that file.

    maven_vendored.py --jar classes.jar --write maven-deps.txt
    maven_vendored.py --jar classes.jar --check maven-deps.txt
    maven_vendored.py --pom cefrium-sdk.aar   # <dependency> lines for its POM
"""

import argparse
import glob
import io
import os
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

_SRC = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
_PREBUILTS = 'third_party/android_deps/autorolled/cipd/libs'
_METADATA = 'third_party/android_deps/autorolled/committed/libs'
DEPS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                         'maven-deps.txt')
# Inside the AAR, for consumers that add it as a file and so never read the POM.
# The com.cefrium Gradle plugin adds these as runtimeOnly dependencies.
AAR_ENTRY = 'cefrium-maven-deps.txt'
# The androidx libraries the POM declares besides these (compile scope).
POM_DEPS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             'pom-deps.txt')
# Not vendored and not owned by a vendored class, but R8 needs it to compile a
# Chromium lambda the device calls back (see proguard.txt).
EXTRA_RUNTIME = ('androidx.window.extensions.core:core:1.0.0',)


def _coordinate(name, src):
    readme = os.path.join(src, _METADATA, name, 'README.chromium')
    if not os.path.exists(readme):
        return None
    m = re.search(r'(?m)^URL:\s*\S*/maven2/(\S+)/([^/\s]+)/([^/\s]+)/[^/\s]+$',
                  open(readme, encoding='utf-8', errors='replace').read())
    if not m:
        return None
    return '%s:%s:%s' % (m.group(1).replace('/', '.'), m.group(2), m.group(3))


def _class_names(path):
    z = zipfile.ZipFile(path)
    if not path.endswith('.aar'):
        return z.namelist()
    names = []
    for inner in z.namelist():
        if inner.endswith('.jar'):
            names += zipfile.ZipFile(io.BytesIO(z.read(inner))).namelist()
    return names


def ownership(names, src=_SRC):
    """Returns {coordinate: set(entries)} for the given jar entry names.

    Classes and Java resources alike: GMS ships a <artifact>.properties in
    its jar, and a second copy fails the consumer's mergeJavaResource.
    """
    wanted = {n for n in names if not n.endswith('/')}
    owned = {}
    for d in sorted(glob.glob(os.path.join(src, _PREBUILTS, '*'))):
        coord = None
        for f in glob.glob(d + '/*.aar') + glob.glob(d + '/*.jar'):
            hits = wanted.intersection(_class_names(f))
            # module-info.class is not a real class and every modular jar has one.
            hits.discard('module-info.class')
            if not hits:
                continue
            coord = coord or _coordinate(os.path.basename(d), src)
            if not coord:
                sys.exit('maven_vendored: no Maven coordinate for %s' % d)
            owned.setdefault(coord, set()).update(hits)
    return owned


def canonical(elem):
    """A values element as a comparable string: attributes sorted, whitespace
    around text ignored, children in order."""
    attrs = ' '.join('%s=%s' % kv for kv in sorted(elem.attrib.items()))
    inner = ''.join(canonical(c) for c in elem)
    return '<%s %s>%s%s</>' % (elem.tag, attrs, (elem.text or '').strip(), inner)


def owned_resources(src=_SRC, path=DEPS_FILE):
    """Resources the declared artifacts ship, as they ship them.

    Returns (files, values): {res path: bytes} for files outside values/, and
    {(values dir, tag, name): set(canonical)} for values entries. The AAR's
    copy of a library resource can override a newer version the app picked,
    so slim_aar.py drops the copies that are identical to these.
    """
    deps = set(read_deps(path))
    files, values = {}, {}
    for d in sorted(glob.glob(os.path.join(src, _PREBUILTS, '*'))):
        if _coordinate(os.path.basename(d), src) not in deps:
            continue
        for f in glob.glob(d + '/*.aar'):
            z = zipfile.ZipFile(f)
            for n in z.namelist():
                if not n.startswith('res/') or n.endswith('/'):
                    continue
                if '/values' not in n:
                    files[n] = z.read(n)
                    continue
                if not n.endswith('.xml'):
                    continue
                folder = os.path.dirname(n)
                for e in ET.fromstring(z.read(n)):
                    name = e.get('name')
                    if name:
                        values.setdefault((folder, e.tag, name), set()).add(canonical(e))
    return files, values


def read_deps(path=DEPS_FILE):
    if path.endswith('.aar'):
        with zipfile.ZipFile(path) as z:
            if AAR_ENTRY not in z.namelist():
                sys.exit('maven_vendored: %s has no %s' % (path, AAR_ENTRY))
            text = z.read(AAR_ENTRY).decode('utf-8')
    else:
        text = open(path, encoding='utf-8').read()
    return [l.strip() for l in text.splitlines()
            if l.strip() and not l.startswith('#')]


def check(owned, path=DEPS_FILE):
    """Exits with a diff unless `path` lists exactly the owning artifacts."""
    want, have = set(read_deps(path)), set(owned)
    if want != have:
        msg = ['maven_vendored: %s is out of date for this Chromium.' % path]
        msg += ['  + %s (%d classes)' % (c, len(owned[c])) for c in sorted(have - want)]
        msg += ['  - %s' % c for c in sorted(want - have)]
        msg.append('Regenerate: maven_vendored.py --jar <raw classes.jar> --write')
        sys.exit('\n'.join(msg))


def runtime_dependencies(path=DEPS_FILE):
    if path.endswith('.aar'):
        return read_deps(path)  # the AAR entry already carries EXTRA_RUNTIME
    return list(EXTRA_RUNTIME) + read_deps(path)


def pom_dependencies(path=DEPS_FILE, indent='    '):
    out = []
    for c in read_deps(POM_DEPS_FILE):
        g, a, v = c.split(':')
        out.append('%s<dependency><groupId>%s</groupId><artifactId>%s</artifactId>'
                   '<version>%s</version></dependency>' % (indent, g, a, v))
    # runtime: the app does not compile against these, its R8 and APK need them.
    for c in runtime_dependencies(path):
        g, a, v = c.split(':')
        out.append('%s<dependency><groupId>%s</groupId><artifactId>%s</artifactId>'
                   '<version>%s</version><scope>runtime</scope></dependency>'
                   % (indent, g, a, v))
    return '\n'.join(out)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--jar')
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument('--write', nargs='?', const=DEPS_FILE)
    g.add_argument('--check', nargs='?', const=DEPS_FILE)
    g.add_argument('--pom', nargs='?', const=DEPS_FILE)
    args = p.parse_args()
    if args.pom:
        print(pom_dependencies(args.pom))
        return
    if not args.jar:
        p.error('--jar is required with --write and --check')
    owned = ownership(zipfile.ZipFile(args.jar).namelist())
    if args.check:
        check(owned, args.check)
        print('OK: %d Maven artifacts, %d classes' %
              (len(owned), sum(len(v) for v in owned.values())))
        return
    with open(args.write, 'w', encoding='utf-8') as f:
        f.write('# Maven artifacts whose classes the SDK AAR does NOT vendor: the POM\n'
                '# declares them instead. Generated by maven_vendored.py from\n'
                "# Chromium's prebuilts; slim_aar.py fails if it is out of date.\n")
        for c in sorted(owned):
            f.write(c + '\n')
    print('wrote %d artifacts to %s' % (len(owned), args.write))


if __name__ == '__main__':
    main()
