#!/usr/bin/env python3
# Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Post-process cefrium.aar for Gradle compatibility.

Chromium's dist_aar produces resource directories like res/0_res/values/
and res/0_res_overlay/values/ which AAPT rejects. This script:
1. Merges non-standard values XMLs into standard res/values/ (deduplicating)
2. Strips non-English locale translations
3. Compresses all entries including the native library
"""

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from collections import OrderedDict

STANDARD_RES_PREFIXES = (
    'values', 'drawable', 'layout', 'mipmap', 'color',
    'anim', 'animator', 'xml', 'raw', 'font', 'menu',
    'interpolator', 'transition', 'navigation',
)


def is_standard_res(name):
    """Check if a res/ path uses standard Android resource directories."""
    if not name.startswith('res/'):
        return True  # not a res entry
    parts = name.split('/')
    if len(parts) < 2:
        return True
    return any(parts[1].startswith(p) for p in STANDARD_RES_PREFIXES)


def is_non_english_locale(name):
    """Check if a res/ path is a non-English locale resource."""
    if not name.startswith('res/values-'):
        return False
    locale_dir = name.split('/')[1]
    # Keep values-vNN (API level qualifiers)
    if locale_dir.startswith('values-v') and locale_dir[8:].isdigit():
        return False
    return True


def get_standard_values_path(name):
    """Map a non-standard values path to its standard equivalent.
    res/0_res/values/foo.xml → res/values/foo.xml
    res/0_res_overlay/values-v31/bar.xml → res/values-v31/bar.xml
    """
    parts = name.split('/')
    # res/0_res/values/foo.xml → ['res', '0_res', 'values', 'foo.xml']
    if len(parts) >= 4:
        subdir = parts[2]
        if any(subdir.startswith(p) for p in STANDARD_RES_PREFIXES):
            return 'res/' + '/'.join(parts[2:])
    return None


def collect_defined_names(xml_data):
    """Collect all resource names defined in a values XML."""
    names = set()
    try:
        root = ET.fromstring(xml_data)
    except ET.ParseError:
        return names
    for elem in root:
        name = elem.get('name', '')
        if name:
            names.add((elem.tag, name))
        # Also collect attrs inside declare-styleable
        if elem.tag == 'declare-styleable':
            for child in elem:
                cname = child.get('name', '')
                if cname:
                    names.add((child.tag, cname))
    return names


def merge_values_xml(existing_xml, new_xml, global_names):
    """Merge new entries into existing XML, skipping globally duplicated names."""
    try:
        existing_root = ET.fromstring(existing_xml)
    except ET.ParseError:
        return new_xml

    try:
        new_root = ET.fromstring(new_xml)
    except ET.ParseError:
        return existing_xml

    for elem in new_root:
        name = elem.get('name', '')
        key = (elem.tag, name)
        if name and key not in global_names:
            existing_root.append(elem)
            global_names.add(key)

    return ET.tostring(existing_root, encoding='unicode',
                       xml_declaration=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()

    # Phase 1: Read all entries and collect values XMLs for merging.
    standard_entries = OrderedDict()  # name → data
    nonstandard_values = {}  # standard_path → [data1, data2, ...]

    with zipfile.ZipFile(args.input) as zin:
        for item in zin.infolist():
            name = item.filename

            # Skip directory entries
            if name.endswith('/'):
                continue

            # Skip non-English locales
            if is_non_english_locale(name):
                continue

            # Skip Chrome-feature layouts that reference unused strings.
            if '/layout' in name and name.endswith('.xml'):
                basename = name.rsplit('/', 1)[-1].replace('.xml', '')
                if basename in ('__arcore_education',
                                'cast_expanded_controller_activity',
                                'cast_intro_overlay', 'cast_mini_controller',
                                'dialog_password', 'fastscroll_handle',
                                'find_in_file'):
                    continue

            data = zin.read(name)

            if is_standard_res(name):
                standard_entries[name] = data
            else:
                # Non-standard resource: remap to standard path
                std_path = get_standard_values_path(name)
                if std_path:
                    if '/values' in std_path:
                        # Values XMLs need merging (dedup entries)
                        if std_path not in nonstandard_values:
                            nonstandard_values[std_path] = []
                        nonstandard_values[std_path].append(data)
                    else:
                        # Non-values (color/, drawable/, etc) — copy directly
                        if std_path not in standard_entries:
                            standard_entries[std_path] = data

    # Phase 2: Collect ALL defined resource names from standard entries.
    global_names = set()
    for name, data in standard_entries.items():
        if name.startswith('res/') and '/values' in name and name.endswith('.xml'):
            global_names |= collect_defined_names(
                data.decode('utf-8', errors='replace'))

    # Phase 3: Merge non-standard values, skipping globally defined names.
    for std_path, data_list in nonstandard_values.items():
        existing = standard_entries.get(std_path, b'')
        if existing:
            merged = existing.decode('utf-8', errors='replace')
        else:
            merged = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>'

        for data in data_list:
            new_xml = data.decode('utf-8', errors='replace')
            merged = merge_values_xml(merged, new_xml, global_names)

        standard_entries[std_path] = merged.encode('utf-8')

    # Phase 4: Deduplicate <attr> declarations GLOBALLY across all values XMLs.
    # AAPT rejects the same attr declared (with format=) in multiple files.
    # Strategy: scan all files, on duplicate attrs either remove format=
    # or remove the entire line if it's a bare <attr name="X" />.
    global_attrs = {}  # attr_name → first file where declared with format
    dedup_count = 0
    for name in sorted(standard_entries.keys()):
        data = standard_entries[name]
        if not (name.startswith('res/') and '/values' in name and
                name.endswith('.xml')):
            continue
        xml_str = data if isinstance(data, str) else data.decode('utf-8', errors='replace')
        lines = xml_str.split('\n')
        new_lines = []
        in_styleable = False
        for line in lines:
            stripped = line.strip()
            if '<declare-styleable' in stripped:
                in_styleable = True
            if '</declare-styleable' in stripped:
                in_styleable = False
            # Match any self-closing <attr> tag
            m = re.search(r'<attr\s+[^>]*name="([^"]+)"', stripped)
            if m:
                attr_name = m.group(1)
                has_format = 'format=' in stripped
                if attr_name in global_attrs:
                    if has_format:
                        # Remove format= to convert to reference
                        line = re.sub(r'\s*format="[^"]*"', '', line)
                    elif not in_styleable:
                        # Bare duplicate outside styleable — skip
                        dedup_count += 1
                        continue
                    # Inside styleable: keep bare references (AAPT allows them)
                    dedup_count += 1
                else:
                    global_attrs[attr_name] = name
            new_lines.append(line)
        standard_entries[name] = '\n'.join(new_lines).encode('utf-8')

    # Phase 5: FINAL global attr dedup pass.
    # After all merges, scan ALL values files and ensure no attr is
    # declared (with format=) more than once across all files.
    all_declared = {}  # attr_name → first_file
    for name in sorted(standard_entries.keys()):
        data = standard_entries[name]
        if not (name.startswith('res/') and '/values' in name and
                name.endswith('.xml')):
            continue
        xml_str = data if isinstance(data, str) else data.decode('utf-8', errors='replace')
        lines = xml_str.split('\n')
        new_lines = []
        in_sty = False
        for line in lines:
            stripped = line.strip()
            if '<declare-styleable' in stripped:
                in_sty = True
            if '</declare-styleable' in stripped:
                in_sty = False
            m = re.search(r'<attr\s+[^>]*name="([^"]+)"', stripped)
            if m:
                aname = m.group(1)
                has_fmt = 'format=' in stripped
                if aname in all_declared:
                    if has_fmt:
                        # Remove format= to downgrade to reference.
                        line = re.sub(r'\s*format="[^"]*"', '', line)
                    if not in_sty:
                        # Outside styleable: skip duplicate attr entirely.
                        dedup_count += 1
                        continue
                    dedup_count += 1
                else:
                    all_declared[aname] = name
            new_lines.append(line)
        standard_entries[name] = '\n'.join(new_lines).encode('utf-8')

    # Phase 6: Strip AndroidX/Google classes from classes.jar.
    # These are provided by the consumer's own AndroidX deps.
    # Strip third-party classes that the consumer provides via their own deps.
    STRIP_PREFIXES = (
        'androidx/',
        'android/support/',
        'com/google/',
        'kotlin/',
        'kotlinx/',
        'org/intellij/',
        'org/jetbrains/',
    )
    if 'classes.jar' in standard_entries:
        import io
        jar_data = standard_entries['classes.jar']
        in_jar = zipfile.ZipFile(io.BytesIO(jar_data))
        out_buf = io.BytesIO()
        out_jar = zipfile.ZipFile(out_buf, 'w', zipfile.ZIP_DEFLATED)
        stripped_count = 0
        for item in in_jar.infolist():
            if any(item.filename.startswith(p) for p in STRIP_PREFIXES):
                stripped_count += 1
                continue
            # Strip debug/metadata files that conflict with consumer deps.
            if item.filename.endswith(('.bin', '.kotlin_module')):
                stripped_count += 1
                continue
            out_jar.writestr(item, in_jar.read(item.filename))
        out_jar.close()
        in_jar.close()
        standard_entries['classes.jar'] = out_buf.getvalue()
        print(f'Stripped {stripped_count} AndroidX/Google classes from classes.jar')

    # Phase 7: Write output AAR (compressed).
    with zipfile.ZipFile(args.output, 'w') as zout:
        for name, data in standard_entries.items():
            zout.writestr(name, data,
                          compress_type=zipfile.ZIP_DEFLATED,
                          compresslevel=6)

    in_size = os.path.getsize(args.input)
    out_size = os.path.getsize(args.output)
    n_merged = len(nonstandard_values)
    print(f'AAR: {in_size // (1024*1024)} MB -> {out_size // (1024*1024)} MB '
          f'({100 - out_size * 100 // in_size}% reduction, '
          f'{n_merged} values merged)')


if __name__ == '__main__':
    main()
