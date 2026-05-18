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

    with zipfile.ZipFile(args.input) as zin:
        with zipfile.ZipFile(args.output, 'w') as zout:
            for item in zin.infolist():
                name = item.filename

                # Skip non-English locale value resources.
                # Keep values/ (default), values-vNN/ (API level).
                # Skip values-XX/ (locale translations).
                if name.startswith('res/values-'):
                    parts = name.split('/')
                    if len(parts) >= 2:
                        locale_dir = parts[1]
                        # values-v21, values-v31 etc are API level qualifiers
                        if locale_dir.startswith('values-v') and \
                                locale_dir[8:].isdigit():
                            pass  # keep
                        else:
                            continue  # skip locale

                data = zin.read(name)
                zout.writestr(item, data,
                              compress_type=zipfile.ZIP_DEFLATED,
                              compresslevel=6)

    in_size = os.path.getsize(args.input)
    out_size = os.path.getsize(args.output)
    print(f'AAR: {in_size // (1024*1024)} MB -> {out_size // (1024*1024)} MB '
          f'({100 - out_size * 100 // in_size}% reduction)')


if __name__ == '__main__':
    main()
