#!/usr/bin/env python3
# Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Split the two multi-ABI SDK AARs into the published modules.

  cefrium-sdk-core                     Java, resources, assets (both flavours)
  cefrium-gms                          POM only: the Google libraries
  cefrium-native-<flavour>-<abi>       jni/<abi>/libcef.so only
  cefrium-sdk, cefrium-sdk-libre       POM only: core + gms + that flavour's
                                       natives

An app without Google Play services depends on cefrium-sdk-core and the
cefrium-native-* modules it needs, and leaves cefrium-gms out.

The codecs and libre AARs differ only in libcef.so; anything else that differs
is a build error, and this fails. Apps keep depending on cefrium-sdk or
cefrium-sdk-libre and get the same bytes as before, in pieces they can reuse
across flavours and ABIs.

  modular_artifacts.py build --codecs <aar> [--libre <aar>] --out <dir>

writes <dir>/<artifact>.aar for every module with a file, and
<dir>/coordinates.tsv: artifact, packaging, file (or -), description, and the
dependencies, space separated: MAVEN:other / MAVEN:google = the core's Maven
libraries without / only the Google ones (maven_vendored.py --part), NAME@pom
a POM-only module, any other NAME an AAR module.
"""

import argparse
import io
import os
import struct
import sys
import zipfile

CORE = 'cefrium-sdk-core'
GMS = 'cefrium-gms'
# Read-ahead lists, named after the build-id of the libcef.so they describe:
# they go with that library's module, not with the core.
HOT_RANGES = 'assets/cefrium/hot-ranges/'
FLAVOURS = {'codecs': 'cefrium-sdk', 'libre': 'cefrium-sdk-libre'}


def build_id(elf):
    """The GNU build-id of a little-endian ELF image (32- or 64-bit), in hex."""
    if elf[4] == 2:  # ELFCLASS64
        shoff, = struct.unpack_from('<Q', elf, 0x28)
        shentsize, shnum = struct.unpack_from('<HH', elf, 0x3A)
        section = '<QQ', 0x18
    else:
        shoff, = struct.unpack_from('<I', elf, 0x20)
        shentsize, shnum = struct.unpack_from('<HH', elf, 0x2E)
        section = '<II', 0x10
    for i in range(shnum):
        sh = shoff + i * shentsize
        sh_type, = struct.unpack_from('<I', elf, sh + 4)
        if sh_type != 7:  # SHT_NOTE
            continue
        off, size = struct.unpack_from(section[0], elf, sh + section[1])
        pos = off
        while pos < off + size:
            namesz, descsz, ntype = struct.unpack_from('<III', elf, pos)
            name_end = pos + 12 + ((namesz + 3) & ~3)
            if ntype == 3 and elf[pos + 12:pos + 12 + namesz] == b'GNU\0':
                return elf[name_end:name_end + descsz].hex()
            pos = name_end + ((descsz + 3) & ~3)
    return None


def entries(aar):
    with zipfile.ZipFile(aar) as z:
        return {i.filename: (i, z.read(i.filename)) for i in z.infolist()
                if not i.is_dir()}


def write_zip(path, items):
    with zipfile.ZipFile(path, 'w') as z:
        for info, data in items:
            z.writestr(info, data)


def native_aar(path, flavour, abi, items):
    package = 'com.cefrium.nativelib.%s.%s' % (flavour, abi.replace('-', '_'))
    empty = io.BytesIO()
    zipfile.ZipFile(empty, 'w').close()
    with zipfile.ZipFile(path, 'w') as z:
        z.writestr('AndroidManifest.xml',
                   '<?xml version="1.0" encoding="utf-8"?>\n'
                   '<manifest xmlns:android="http://schemas.android.com/apk/'
                   'res/android" package="%s" />\n' % package)
        z.writestr('classes.jar', empty.getvalue())
        z.writestr('R.txt', '')
        for info, data in items:
            z.writestr(info, data)


def build(args):
    flavours = {'codecs': entries(args.codecs)}
    if args.libre:  # absent on a single-variant dev cut
        flavours['libre'] = entries(args.libre)
    def shared(n):
        return not n.startswith('jni/') and not n.startswith(HOT_RANGES)
    common = {n: v for n, v in flavours['codecs'].items() if shared(n)}
    other = {n: v for n, v in flavours.get('libre', flavours['codecs']).items()
             if shared(n)}
    if common.keys() != other.keys():
        sys.exit('modular_artifacts: the flavours differ outside jni/: %s'
                 % sorted(common.keys() ^ other.keys()))
    differ = [n for n in common if common[n][1] != other[n][1]]
    if differ:
        sys.exit('modular_artifacts: the flavours differ outside jni/: %s'
                 % differ)

    os.makedirs(args.out, exist_ok=True)
    rows = []
    write_zip(os.path.join(args.out, CORE + '.aar'), common.values())
    rows.append((CORE, 'aar', CORE + '.aar',
                 'Cefrium SDK: Java API, resources and engine assets. '
                 'Its native engine comes from a cefrium-native-* module.',
                 'MAVEN:other'))
    rows.append((GMS, 'pom', '-',
                 'Google Play services, Firebase, ML Kit and Play Core '
                 'libraries the engine uses when they are present (push over '
                 'FCM, Cast, WebOTP, fused location, barcode detection, '
                 'on-device AI). Leave it out for an app without Google.',
                 'MAVEN:google'))
    for flavour, aggregate in FLAVOURS.items():
        if flavour not in flavours:
            continue
        items = flavours[flavour]
        abis = sorted({n.split('/')[1] for n in items if n.startswith('jni/')})
        if not abis:
            sys.exit('modular_artifacts: %s has no jni/' % flavour)
        natives = []
        lists = {n: v for n, v in items.items() if n.startswith(HOT_RANGES)}
        for abi in abis:
            art = 'cefrium-native-%s-%s' % (flavour, abi)
            lib = items['jni/%s/libcef.so' % abi][1]
            mine = HOT_RANGES + '%s.txt' % build_id(lib)
            native_aar(os.path.join(args.out, art + '.aar'), flavour, abi,
                       [v for n, v in items.items()
                        if n.startswith('jni/%s/' % abi) or n == mine])
            lists.pop(mine, None)
            rows.append((art, 'aar', art + '.aar',
                         'Cefrium engine (libcef.so), %s, %s.'
                         % (flavour, abi), ''))
            natives.append(art)
        if lists:
            sys.exit('modular_artifacts: %s lists for no library: %s'
                     % (flavour, sorted(lists)))
        rows.append((aggregate, 'pom', '-',
                     'Cefrium SDK: CEF for native Android, %s, every ABI.'
                     % flavour, ' '.join([CORE, GMS + '@pom'] + natives)))
    with open(os.path.join(args.out, 'coordinates.tsv'), 'w') as f:
        for row in rows:
            f.write('\t'.join(row) + '\n')


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='cmd', required=True)
    b = sub.add_parser('build')
    b.add_argument('--codecs', required=True)
    b.add_argument('--libre')
    b.add_argument('--out', required=True)
    args = parser.parse_args()
    build(args)


if __name__ == '__main__':
    main()
