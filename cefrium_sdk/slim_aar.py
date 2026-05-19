#!/usr/bin/env python3
# Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Post-process cefrium.aar for Gradle compatibility (v3).

Strategy: keep ALL classes and standard resources. Only remove:
1. Non-standard res/ dirs (0_res/, 0_res_overlay/) — AAPT rejects them
2. Non-English locale translations — size reduction
3. Duplicate R.txt entries — prevents AAPT duplicate key errors
4. META-INF/*.bin/*.kotlin_module from classes.jar — merge conflicts
Then inject CEF assets (ICU, paks) and compress.

Previous versions:
- v1 (slim_aar_v1_merge_xml.py): merge 0_res values into standard res.
  Failed: attr duplicates between files impossible to fully resolve.
- v2 (slim_aar_v2_strip_all.py): strip ALL Chrome resources + classes.
  Failed: R$integer ClassNotFound (no R class generated without resources)
  and __builtin_trap (official build stubs fire when classes stripped).
"""

import argparse
import io
import os
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()

    written = set()
    global_attrs = set()  # Track declared attrs globally for dedup
    with zipfile.ZipFile(args.input) as zin:
        with zipfile.ZipFile(args.output, 'w') as zout:
            for item in zin.infolist():
                name = item.filename
                if name.endswith('/'):
                    continue

                # Skip non-standard res/ dirs (0_res, 1_res_chromium_base, etc).
                # Chromium uses numbered prefixes; AAPT only accepts standard names.
                STANDARD_RES = ('values', 'drawable', 'layout', 'mipmap',
                    'color', 'anim', 'animator', 'xml', 'raw', 'font',
                    'menu', 'interpolator', 'transition', 'navigation')
                out_name = name
                if name.startswith('res/'):
                    top = name.split('/')[1]
                    if not any(top.startswith(s) for s in STANDARD_RES):
                        # Non-standard dir (0_res, 1_res_chromium_base, etc).
                        # Remap to standard path if it contains standard subdirs.
                        parts = name.split('/')
                        if len(parts) >= 4:
                            subdir = parts[2]
                            if any(subdir.startswith(s) for s in STANDARD_RES):
                                out_name = 'res/' + '/'.join(parts[2:])
                            else:
                                continue
                        else:
                            continue

                # Skip non-English locale translations.
                if name.startswith('res/values-'):
                    locale = name.split('/')[1]
                    # Keep values-vNN (API level qualifiers).
                    if not (locale.startswith('values-v') and
                            locale[8:].isdigit()):
                        continue

                data = zin.read(name)

                # Deduplicate R.txt entries.
                # Chromium generates duplicate keys (e.g. backgroundColor)
                # which cause AAPT "Duplicate key" errors.
                if name == 'R.txt':
                    lines = data.decode('utf-8').split('\n')
                    seen = set()
                    deduped = []
                    for line in lines:
                        parts = line.strip().split()
                        if len(parts) >= 3:
                            key = (parts[0], parts[1], parts[2])
                            if key in seen:
                                continue
                            seen.add(key)
                        deduped.append(line)
                    data = '\n'.join(deduped).encode('utf-8')

                # Clean classes.jar: remove META-INF and debug files
                # that conflict with consumer deps.
                if name == 'classes.jar':
                    in_jar = zipfile.ZipFile(io.BytesIO(data))
                    out_buf = io.BytesIO()
                    out_jar = zipfile.ZipFile(out_buf, 'w',
                                             zipfile.ZIP_DEFLATED)
                    for ji in in_jar.infolist():
                        if ji.filename.startswith('META-INF/'):
                            continue
                        if ji.filename.endswith(('.bin', '.kotlin_module')):
                            continue
                        out_jar.writestr(ji, in_jar.read(ji.filename))
                    out_jar.close()
                    in_jar.close()
                    data = out_buf.getvalue()

                # Deduplicate <attr> declarations in values XML files.
                # AAPT rejects the same attr declared in multiple files.
                # Inside declare-styleable: keep but strip format=.
                # Outside: remove line entirely.
                if out_name.startswith('res/') and '/values' in out_name and out_name.endswith('.xml'):
                    xml_str = data.decode('utf-8', errors='replace')
                    import re
                    lines = xml_str.split('\n')
                    new_lines = []
                    in_sty = False
                    for line in lines:
                        stripped = line.strip()
                        if '<declare-styleable' in stripped:
                            in_sty = True
                        if '</declare-styleable' in stripped:
                            in_sty = False
                        if re.match(r'\s*<attr\s+[^>]*/>\s*$', line):
                            m = re.search(r'name="([^"]+)"', stripped)
                            if m:
                                aname = m.group(1)
                                if aname in global_attrs:
                                    if in_sty:
                                        # Keep reference but strip format.
                                        line = re.sub(r'\s*format="[^"]*"', '', line)
                                    else:
                                        continue  # Remove outside styleable
                                else:
                                    global_attrs.add(aname)
                        new_lines.append(line)
                    data = '\n'.join(new_lines).encode('utf-8')

                # Skip if name already written (remap collision).
                if out_name in written:
                    continue
                written.add(out_name)
                zout.writestr(out_name, data,
                              compress_type=zipfile.ZIP_DEFLATED,
                              compresslevel=6)

    # Inject CEF assets (ICU data, resource paks, V8 snapshot).
    # These must be uncompressed (STORED) — Chromium reads via mmap.
    build_dir = os.path.dirname(os.path.abspath(args.input))
    assets = {
        'icudtl.dat': 'assets/icudtl.dat',
        'resources.pak': 'assets/resources.pak',
        'chrome_100_percent.pak': 'assets/chrome_100_percent.pak',
        'chrome_200_percent.pak': 'assets/chrome_200_percent.pak',
        'snapshot_blob.bin': 'assets/snapshot_blob_64.bin',
    }
    locale = os.path.join(build_dir, 'locales', 'en-US.pak')
    if os.path.exists(locale):
        assets['locales/en-US.pak'] = 'assets/stored-locales/en-US.pak'

    with zipfile.ZipFile(args.output, 'a') as zout:
        for src, dst in assets.items():
            path = os.path.join(build_dir, src)
            if os.path.exists(path):
                zout.writestr(dst, open(path, 'rb').read(),
                              compress_type=zipfile.ZIP_STORED)

    in_size = os.path.getsize(args.input)
    out_size = os.path.getsize(args.output)
    print(f'AAR: {in_size // (1024*1024)} MB -> {out_size // (1024*1024)} MB '
          f'({100 - out_size * 100 // in_size}% reduction)')


if __name__ == '__main__':
    main()
