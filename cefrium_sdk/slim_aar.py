#!/usr/bin/env python3
# Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Post-process cefrium.aar for Gradle compatibility (v4).

Combines v1 XML merge + v3 R.txt dedup + assets + attr dedup.
Does NOT strip classes (causes __builtin_trap in official builds).

Phases:
1. Read entries, separate standard vs non-standard res
2. Remap non-values from non-standard dirs to standard paths
3. Merge non-standard values XMLs into standard res/values/ (dedup by name)
4. Deduplicate <attr> declarations globally across all values XMLs
5. Deduplicate R.txt entries (prevents backgroundColor duplicate key)
6. Clean classes.jar (META-INF, .bin, .kotlin_module)
7. Write compressed AAR
8. Inject CEF assets (ICU, paks) as STORED (uncompressed for mmap)
"""

import argparse
import io
import os
import re
import xml.etree.ElementTree as ET
import zipfile
from collections import OrderedDict

STANDARD_RES = (
    'values', 'drawable', 'layout', 'mipmap', 'color',
    'anim', 'animator', 'xml', 'raw', 'font', 'menu',
    'interpolator', 'transition', 'navigation',
)


def is_standard_res(name):
    if not name.startswith('res/'):
        return True
    parts = name.split('/')
    if len(parts) < 2:
        return True
    return any(parts[1].startswith(p) for p in STANDARD_RES)


def is_non_english_locale(name):
    if not name.startswith('res/values-'):
        return False
    locale = name.split('/')[1]
    if locale.startswith('values-v') and locale[8:].isdigit():
        return False
    return True


STRIPPABLE_RES_TYPES = (
    'drawable', 'layout', 'menu', 'anim', 'animator',
    'mipmap', 'xml', 'font', 'raw', 'navigation',
    'interpolator', 'transition',
)


def is_strippable_res(name):
    """Strip Chrome UI resources not used by CEF SDK.

    Chrome includes thousands of drawables, layouts, and menus for its
    Android UI (tabs, toolbar, settings, bookmarks). CEF doesn't use
    Chrome's Android UI — it renders web content via ContentViewRenderView.

    Handles both standard (res/drawable/) and non-standard (res/0_res/drawable/).
    """
    if not name.startswith('res/'):
        return False
    # For non-standard paths (res/0_res/drawable/), check the remapped subdir
    effective_name = name
    std = get_standard_path(name)
    if std:
        effective_name = std
    parts = effective_name.split('/')
    if len(parts) < 2:
        return False
    subdir = parts[1]
    # Keep values/ (strings, styles, attrs — needed for compilation)
    if subdir.startswith('values'):
        return False
    # Keep color/ (referenced by styles)
    if subdir.startswith('color'):
        return False
    # Strip Chrome UI drawables, layouts, menus, animations
    if any(subdir.startswith(p) for p in STRIPPABLE_RES_TYPES):
        return True
    return False


def get_standard_path(name):
    """Map non-standard res path to standard equivalent."""
    parts = name.split('/')
    if len(parts) >= 4:
        subdir = parts[2]
        if any(subdir.startswith(p) for p in STANDARD_RES):
            return 'res/' + '/'.join(parts[2:])
    return None


def collect_defined_names(xml_data):
    names = set()
    try:
        root = ET.fromstring(xml_data)
    except ET.ParseError:
        return names
    for elem in root:
        name = elem.get('name', '')
        if name:
            names.add((elem.tag, name))
        if elem.tag == 'declare-styleable':
            for child in elem:
                cname = child.get('name', '')
                if cname:
                    names.add((child.tag, cname))
    return names


def merge_values_xml(existing_xml, new_xml, global_names):
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

    # Phase 1: Read entries.
    standard_entries = OrderedDict()
    nonstandard_values = {}  # standard_path → [data1, data2, ...]

    with zipfile.ZipFile(args.input) as zin:
        for item in zin.infolist():
            name = item.filename
            if name.endswith('/'):
                continue
            if is_non_english_locale(name):
                continue
            # NOTE: Do NOT strip res/ drawables/layouts — Chrome's values/
            # XMLs reference them. Stripping causes aapt2 "resource not found".
            # if is_strippable_res(name):
            #     continue

            data = zin.read(name)

            if is_standard_res(name):
                standard_entries[name] = data
            else:
                std_path = get_standard_path(name)
                if std_path:
                    if '/values' in std_path:
                        if std_path not in nonstandard_values:
                            nonstandard_values[std_path] = []
                        nonstandard_values[std_path].append(data)
                    else:
                        if std_path not in standard_entries:
                            standard_entries[std_path] = data

    # Phase 2: Collect globally defined resource names.
    global_names = set()
    for name, data in standard_entries.items():
        if name.startswith('res/') and '/values' in name and name.endswith('.xml'):
            global_names |= collect_defined_names(
                data.decode('utf-8', errors='replace'))

    # Phase 3: Merge non-standard values into standard entries.
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

    # Phase 4: Deduplicate <attr> declarations globally.
    global_attrs = set()
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
            if re.match(r'\s*<attr\s+[^>]*/>\s*$', line):
                m = re.search(r'name="([^"]+)"', stripped)
                if m:
                    aname = m.group(1)
                    if aname in global_attrs:
                        if 'format=' in stripped:
                            line = re.sub(r'\s*format="[^"]*"', '', line)
                        if not in_sty:
                            continue  # skip outside styleable
                    else:
                        global_attrs.add(aname)
            new_lines.append(line)
        standard_entries[name] = '\n'.join(new_lines).encode('utf-8')

    # Phase 5: Deduplicate R.txt entries.
    if 'R.txt' in standard_entries:
        data = standard_entries['R.txt']
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
        standard_entries['R.txt'] = '\n'.join(deduped).encode('utf-8')

    # Phase 6: Clean classes.jar.
    # Strip ONLY AndroidX/support classes (provided by consumer's appcompat dep).
    # Do NOT strip com.google, kotlin, etc. — their removal causes
    # Strip third-party classes provided by consumer's Gradle deps,
    # EXCEPT classes Chromium loads at runtime.
    STRIP_FROM_JAR = (
        'androidx/',
        'android/support/',
        'com/google/errorprone/',
        'com/google/thirdparty/',
        'kotlin/',
        'kotlinx/',
        '_COROUTINE/',
        'org/intellij/',
        'org/jetbrains/',
        'org/jspecify/',
    )
    # Keep these even if they match STRIP_FROM_JAR prefixes.
    # Chromium loads them at runtime — stripping causes crashes.
    KEEP_IN_JAR = (
        'com/google/common/',        # Guava (ImmutableMap, etc.)
        'com/google/android/gms/',   # GMS classes
        # Chromium's modified AccessibilityNodeInfoCompat includes methods
        # not in public androidx.core (setExpandedState, setChecked(int), etc.).
        # Required for TalkBack accessibility. Consumer must disable
        # checkDuplicateClasses in build.gradle (documented in QUICKSTART.md).
        # 'androidx/core/view/accessibility/',  # Needs shadow/relocate solution
    )
    if 'classes.jar' in standard_entries:
        jar_data = standard_entries['classes.jar']
        in_jar = zipfile.ZipFile(io.BytesIO(jar_data))
        out_buf = io.BytesIO()
        out_jar = zipfile.ZipFile(out_buf, 'w', zipfile.ZIP_DEFLATED)
        for ji in in_jar.infolist():
            if ji.filename.startswith('META-INF/'):
                continue
            if ji.filename.endswith(('.bin', '.kotlin_module')):
                continue
            if any(ji.filename.startswith(p) for p in STRIP_FROM_JAR):
                # Check if this class should be kept despite matching strip
                if not any(ji.filename.startswith(k) for k in KEEP_IN_JAR):
                    continue
            out_jar.writestr(ji, in_jar.read(ji.filename))
        out_jar.close()
        in_jar.close()
        standard_entries['classes.jar'] = out_buf.getvalue()

    # Phase 7: Write compressed AAR.
    with zipfile.ZipFile(args.output, 'w') as zout:
        for name, data in standard_entries.items():
            zout.writestr(name, data,
                          compress_type=zipfile.ZIP_DEFLATED,
                          compresslevel=9)

    # Phase 8: Inject CEF assets (STORED for mmap access).
    build_dir = os.path.dirname(os.path.abspath(args.input))
    assets = {
        'icudtl.dat': 'assets/icudtl.dat',
        'resources.pak': 'assets/resources.pak',
        'chrome_100_percent.pak': 'assets/chrome_100_percent.pak',
        'chrome_200_percent.pak': 'assets/chrome_200_percent.pak',
        'snapshot_blob.bin': 'assets/snapshot_blob_64.bin',
        'v8_context_snapshot_64.bin': 'assets/v8_context_snapshot_64.bin',
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
