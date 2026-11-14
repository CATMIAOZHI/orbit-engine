#!/usr/bin/env python3
"""The public API of a Cefrium AAR, as a text file to diff.

  api-dump.py <aar> <api-file>            write the dump
  api-dump.py --check <aar> <api-file>    fail (exit 1) if the AAR's API is
                                          not exactly the committed file
  api-dump.py --check --warn ...          print the diff, exit 0

The dump lists every class under com.cefrium (except com.cefrium.internal)
that an app can reach -- the class and all its enclosing classes public --
with its public and protected members and their constant values, one line
each, read with the hermetic JDK's javap. Members marked @Deprecated or
@CefriumExperimental carry that tag, so deprecating or un-deprecating shows
up as a diff too.

Before 1.0 the rule is exact: any API change fails --check until the
regenerated file is committed with the change (that commit is the approval).

It also lists, as "leak:" lines, public classes not named Cefrium* outside
com.cefrium.compose, and public members whose signature names an
org.chromium / org.jni_zero / gen. type. Those lines are part of the dump, so
a new one fails --check like any other change; FEATURE-092 removes them.
"""

import difflib
import os
import re
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.abspath(os.path.join(HERE, '..', '..'))
JAVAP = os.path.join(SRC, 'third_party', 'jdk', 'current', 'bin', 'javap')

INTERNAL_TYPES = re.compile(r'\b(org\.chromium\.|org\.jni_zero\.|gen\.)')
EXPERIMENTAL = ('com/cefrium/CefriumExperimental', 'com.cefrium.CefriumExperimental')


def javap(jar, classes, *flags):
  out = subprocess.run([JAVAP, '-cp', jar, *flags, *classes],
                       check=True, capture_output=True, text=True).stdout
  return out


def split_classes(text, start):
  """Splits javap output into per-class chunks, each starting at |start|."""
  chunks, cur = [], None
  for line in text.splitlines():
    if line.startswith(start):
      cur = []
      chunks.append(cur)
    elif cur is not None:
      cur.append(line)
  return chunks


def header(chunk):
  for line in chunk:
    if line and not line.startswith(' ') and line.rstrip().endswith('{'):
      return line.rstrip()[:-1].strip()
  return None


def class_name(head):
  m = re.search(r'\b(?:class|interface|enum|@interface)\s+([\w.$]+)', head)
  return m.group(1) if m else None


def strip_value(sig):
  return re.sub(r' = .*;$', ';', sig)


def verbose_name(chunk_v):
  for line in chunk_v:
    m = re.match(r'^  this_class: .*// (\S+)$', line)
    if m:
      return m.group(1).replace('/', '.')
  return None


def tags(chunk_v):
  """Members (signature without constant value) -> set of tags, from -v."""
  out, member, cls = {}, None, set()
  after_body = False  # past the class's closing brace: its own attributes
  for line in chunk_v:
    if after_body:
      if line.startswith('Deprecated: true'):
        cls.add('deprecated')
      if any(e in line for e in EXPERIMENTAL):
        cls.add('experimental')
      continue
    if line == '}':
      after_body = True
      member = None
      continue
    if re.match(r'^  \S.*;$', line):
      member = line.strip()
      out[member] = set()
    elif line.startswith('    ') and member is not None:
      if 'Deprecated: true' in line:
        out[member].add('deprecated')
      if any(e in line for e in EXPERIMENTAL):
        out[member].add('experimental')
      if line.lstrip().startswith('flags:') and 'ACC_SYNTHETIC' in line:
        out[member].add('synthetic')
    elif not line.startswith(' '):
      member = None
      if line.startswith('Deprecated: true'):
        cls.add('deprecated')
      if any(e in line for e in EXPERIMENTAL):
        cls.add('experimental')
  return out, cls


def manifest_components(aar):
  with zipfile.ZipFile(aar) as z:
    xml = z.read('AndroidManifest.xml').decode('utf-8', 'replace')
  pkg = re.search(r'package="([^"]+)"', xml)
  out = set()
  for tag, name in re.findall(
      r'<(activity|service|receiver|provider)\b[^>]*?android:name="([^"]+)"', xml,
      re.S):
    if name.startswith('.') and pkg:
      name = pkg.group(1) + name
    out.add(name)
  return out


def dump(aar):
  lines = []
  with tempfile.TemporaryDirectory() as d:
    with zipfile.ZipFile(aar) as z:
      z.extract('classes.jar', d)
    jar = os.path.join(d, 'classes.jar')
    with zipfile.ZipFile(jar) as z:
      names = sorted(n[:-6].replace('/', '.') for n in z.namelist()
                     if n.startswith('com/cefrium/') and n.endswith('.class')
                     and not n.startswith('com/cefrium/internal/'))
    plain = split_classes(javap(jar, names, '-protected', '-constants'),
                          'Compiled from')
    verbose = split_classes(javap(jar, names, '-protected', '-v'), 'Classfile ')

    heads = {}
    for chunk in plain:
      h = header(chunk)
      if h:
        heads[class_name(h)] = (h, chunk)
    public = {n for n, (h, _) in heads.items() if h.startswith('public ')}

    def reachable(name):
      parts = name.split('$')
      return all('$'.join(parts[:i + 1]) in public for i in range(len(parts)))

    vtags = {}
    for chunk in verbose:
      n = verbose_name(chunk)
      if n:
        vtags[n] = tags(chunk)
    components = manifest_components(aar)

    for name in sorted(heads):
      if not reachable(name):
        continue
      h, chunk = heads[name]
      member_tags, class_tags = vtags.get(name, ({}, set()))
      prefix = ''.join('[%s] ' % t for t in sorted(class_tags))
      lines.append(prefix + h)
      simple = name.rsplit('.', 1)[1].split('$')[0]
      # A manifest component must be public and extend the engine's service
      # classes; it is declared by the AAR, not called by apps.
      component = name in components
      if (not component and not simple.startswith('Cefrium')
          and not name.startswith('com.cefrium.compose.')):
        lines.append('  leak: public class not named Cefrium*')
      if not component and INTERNAL_TYPES.search(h):
        lines.append('  leak: extends or implements an engine-internal type')
      members = []
      for line in chunk:
        if re.match(r'^  \S.*;$', line):
          sig = line.strip()
          t = member_tags.get(strip_value(sig), set())
          # Not API: compiler-generated members, Kotlin `internal` (public in
          # bytecode with a module suffix) and the Compose compiler's $stable.
          if ('synthetic' in t or re.search(r'\$cefrium_compose\b', sig)
              or re.search(r'\$stable;$', sig)):
            continue
          # The release and engine version change every release by design.
          if name == 'com.cefrium.Cefrium' and re.match(
              r'public static final java\.lang\.String (CHROMIUM_)?VERSION = ', sig):
            sig = strip_value(sig)
          members.append(''.join('[%s] ' % x for x in sorted(t)) + sig)
          if not component and INTERNAL_TYPES.search(sig):
            members.append('  leak: names an engine-internal type')
      lines.extend('  ' + m for m in members)
      lines.append('')
  return '\n'.join(lines)


def main(argv):
  check = '--check' in argv
  warn = '--warn' in argv
  args = [a for a in argv if not a.startswith('--')]
  if len(args) != 2:
    sys.exit(__doc__)
  aar, api = args
  text = dump(aar)
  if not check:
    with open(api, 'w') as f:
      f.write(text)
    print('wrote %s (%d lines)' % (api, text.count('\n') + 1))
    return 0
  try:
    with open(api) as f:
      want = f.read()
  except FileNotFoundError:
    want = ''
  if want == text:
    print('OK: the API of %s matches %s' % (os.path.basename(aar), api))
    return 0
  diff = list(difflib.unified_diff(want.splitlines(), text.splitlines(),
                                   api, os.path.basename(aar), lineterm='', n=1))
  print('\n'.join(diff))
  added = sum(1 for l in diff if l.startswith('+') and not l.startswith('+++'))
  removed = sum(1 for l in diff if l.startswith('-') and not l.startswith('---'))
  print('%s: the public API changed (+%d -%d lines). If intended, regenerate '
        '%s and commit it with the change and a CHANGELOG entry.'
        % ('WARN' if warn else 'FAIL', added, removed, api))
  return 0 if warn else 1


if __name__ == '__main__':
  sys.exit(main(sys.argv[1:]))
