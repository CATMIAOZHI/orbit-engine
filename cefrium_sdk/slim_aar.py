#!/usr/bin/env python3
# Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Post-process cefrium.aar to reduce size.

Strips non-English locale resources and compresses all entries
including the native library.
"""

import argparse
import os
import sys
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True, help='Input AAR path')
    parser.add_argument('--output', required=True, help='Output AAR path')
    args = parser.parse_args()

    STANDARD_RES_PREFIXES = (
        'values', 'drawable', 'layout', 'mipmap', 'color',
        'anim', 'animator', 'xml', 'raw', 'font', 'menu',
        'interpolator', 'transition', 'navigation',
    )

    def should_keep(name):
        """Return True if the entry should be kept."""
        if name.endswith('/'):
            return False
        # Skip non-English locale resources.
        if name.startswith('res/values-'):
            locale_dir = name.split('/')[1]
            if locale_dir.startswith('values-v') and locale_dir[8:].isdigit():
                return True
            return False
        # Keep all res/ content including non-standard Chromium dirs.
        # Stripping them causes missing resource errors from Chrome themes.
        return True

    seen_names = set()
    with zipfile.ZipFile(args.input) as zin:
        with zipfile.ZipFile(args.output, 'w') as zout:
            for item in zin.infolist():
                if not should_keep(item.filename):
                    continue

                out_name = item.filename
                # Remap non-standard res dirs to standard.
                # res/0_res/values/foo.xml → res/values/foo_0.xml
                if out_name.startswith('res/'):
                    parts = out_name.split('/')
                    top = parts[1]
                    if not any(top.startswith(p) for p in STANDARD_RES_PREFIXES):
                        if len(parts) >= 4:
                            # Add prefix to filename to avoid collisions.
                            prefix = top.replace('/', '_')
                            fname = parts[-1]
                            base, ext = os.path.splitext(fname)
                            out_name = 'res/' + '/'.join(parts[2:-1]) + \
                                       '/' + base + '_' + prefix + ext

                if out_name in seen_names:
                    continue
                seen_names.add(out_name)

                data = zin.read(item.filename)
                zout.writestr(out_name, data,
                              compress_type=zipfile.ZIP_DEFLATED,
                              compresslevel=6)

    in_size = os.path.getsize(args.input)
    out_size = os.path.getsize(args.output)
    print(f'AAR: {in_size // (1024*1024)} MB -> {out_size // (1024*1024)} MB '
          f'({100 - out_size * 100 // in_size}% reduction)')


if __name__ == '__main__':
    main()
