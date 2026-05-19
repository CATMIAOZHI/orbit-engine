#!/usr/bin/env python3
# Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Post-process cefrium.aar for Gradle compatibility.

Strategy: strip ALL Chromium resources and third-party classes.
CEF doesn't use Chrome UI — the resources are baggage from chrome_all_java.
Only keep: classes.jar (Chromium classes), jni/ (libcef.so), manifest, proguard.

The cefrium_resources target provides the few resources CEF actually needs
(baseline_colors.xml, compat_strings.xml) via the dist_aar deps.
"""

import argparse
import io
import os
import re
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()

    # Third-party class prefixes to strip from classes.jar.
    # These are provided by the consumer's own deps.
    STRIP_CLASS_PREFIXES = (
        'androidx/',
        'android/support/',
        'com/google/',
        'kotlin/',
        'kotlinx/',
        'org/intellij/',
        'org/jetbrains/',
    )

    with zipfile.ZipFile(args.input) as zin:
        with zipfile.ZipFile(args.output, 'w') as zout:
            for item in zin.infolist():
                name = item.filename

                # Skip directory entries.
                if name.endswith('/'):
                    continue

                # Skip ALL Chromium resources — CEF doesn't use Chrome UI.
                # Our own resources (baseline_colors, compat_strings) are
                # included via the cefrium_resources dep in dist_aar.
                if name.startswith('res/') and not name.startswith('res/values/'):
                    # Keep res/values/ (has our compat resources merged in)
                    continue

                # For res/values/, only keep files from our cefrium_resources
                # (small files) and skip the large Chrome theme files.
                if name.startswith('res/values/'):
                    # Our resources are baseline_colors and compat_strings.
                    # Chrome's are values_NNN.xml, attrs_NNN.xml, etc.
                    # Keep ONLY our files + R.txt-required minimal entries.
                    data = zin.read(name)
                    size = len(data)
                    # Our resource files are small (<5KB each).
                    # Chrome theme files are large (>10KB).
                    if size > 10000:
                        continue
                    zout.writestr(name, data,
                                  compress_type=zipfile.ZIP_DEFLATED,
                                  compresslevel=6)
                    continue

                data = zin.read(name)

                # Strip third-party classes from classes.jar.
                if name == 'classes.jar':
                    in_jar = zipfile.ZipFile(io.BytesIO(data))
                    out_buf = io.BytesIO()
                    out_jar = zipfile.ZipFile(out_buf, 'w', zipfile.ZIP_DEFLATED)
                    stripped = 0
                    for ji in in_jar.infolist():
                        if any(ji.filename.startswith(p) for p in STRIP_CLASS_PREFIXES):
                            stripped += 1
                            continue
                        if ji.filename.endswith(('.bin', '.kotlin_module')):
                            stripped += 1
                            continue
                        out_jar.writestr(ji, in_jar.read(ji.filename))
                    out_jar.close()
                    in_jar.close()
                    data = out_buf.getvalue()
                    print(f'Stripped {stripped} third-party classes from classes.jar')

                # Deduplicate R.txt entries (same type+name with different values).
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
                    print(f'R.txt: {len(lines)} -> {len(deduped)} lines')

                zout.writestr(name, data,
                              compress_type=zipfile.ZIP_DEFLATED,
                              compresslevel=6)

    # Inject CEF assets (ICU data, resource paks) into the AAR.
    build_dir = os.path.dirname(os.path.abspath(args.input))
    asset_files = {
        'icudtl.dat': 'assets/icudtl.dat',
        'resources.pak': 'assets/resources.pak',
        'chrome_100_percent.pak': 'assets/chrome_100_percent.pak',
        'chrome_200_percent.pak': 'assets/chrome_200_percent.pak',
        'snapshot_blob.bin': 'assets/snapshot_blob_64.bin',
    }
    locale = os.path.join(build_dir, 'locales', 'en-US.pak')
    if os.path.exists(locale):
        asset_files['locales/en-US.pak'] = 'assets/stored-locales/en-US.pak'

    with zipfile.ZipFile(args.output, 'a') as zout:
        for src, dst in asset_files.items():
            path = os.path.join(build_dir, src)
            if os.path.exists(path):
                zout.writestr(dst, open(path, 'rb').read(),
                              compress_type=zipfile.ZIP_STORED)
                sz = os.path.getsize(path)
                print(f'Asset: {dst} ({sz // 1024} KB)')

    in_size = os.path.getsize(args.input)
    out_size = os.path.getsize(args.output)
    print(f'AAR: {in_size // (1024*1024)} MB -> {out_size // (1024*1024)} MB '
          f'({100 - out_size * 100 // in_size}% reduction)')


if __name__ == '__main__':
    main()
