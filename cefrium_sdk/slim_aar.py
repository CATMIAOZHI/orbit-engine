#!/usr/bin/env python3
# Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

"""Post-process cefrium.aar for Gradle compatibility (v4).

Combines v1 XML merge + v3 R.txt dedup + assets + attr dedup.
Strips classes the consumer gets from Gradle: androidx/kotlin by package, and
every class a Maven artifact provides (maven_vendored.py, maven-deps.txt).

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
import sys
from collections import OrderedDict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import maven_vendored

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


def values_config(path):
    """'res/values-night/x.xml' -> 'values-night': names are per configuration."""
    return path.split('/')[1]


def collect_defined_names(xml_data, config):
    names = set()
    try:
        root = ET.fromstring(xml_data)
    except ET.ParseError:
        return names
    for elem in root:
        name = elem.get('name', '')
        if name:
            names.add((config, elem.tag, name))
        if elem.tag == 'declare-styleable':
            for child in elem:
                cname = child.get('name', '')
                if cname:
                    names.add((config, child.tag, cname))
    return names


def merge_values_xml(existing_xml, new_xml, global_names, config):
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
        key = (config, elem.tag, name)
        if name and key not in global_names:
            existing_root.append(elem)
            global_names.add(key)
    return ET.tostring(existing_root, encoding='unicode',
                       xml_declaration=True)


BUILD_CONFIG = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            'java', 'src', 'org', 'chromium', 'build',
                            'BuildConfig.java')


def read_asset_suffix():
    """Returns (APK_ASSETS_SUFFIX, APK_ASSETS_SUFFIXED_LIST) from BuildConfig."""
    src = open(BUILD_CONFIG).read()
    suffix = re.search(r'APK_ASSETS_SUFFIX = "([^"]+)";', src)
    listed = re.search(r'APK_ASSETS_SUFFIXED_LIST = \{([^}]*)\};', src)
    if not suffix or not listed:
        sys.exit('slim_aar: no asset suffix in %s' % BUILD_CONFIG)
    names = re.findall(r'"([^"]+)"', listed.group(1))
    if names != sorted(names):
        sys.exit('slim_aar: APK_ASSETS_SUFFIXED_LIST is not sorted')
    return suffix.group(1), names


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
                data.decode('utf-8', errors='replace'), values_config(name))

    # Phase 3: Merge non-standard values into standard entries.
    for std_path, data_list in nonstandard_values.items():
        existing = standard_entries.get(std_path, b'')
        if existing:
            merged = existing.decode('utf-8', errors='replace')
        else:
            merged = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>'
        for data in data_list:
            new_xml = data.decode('utf-8', errors='replace')
            merged = merge_values_xml(merged, new_xml, global_names,
                                      values_config(std_path))
        standard_entries[std_path] = merged.encode('utf-8')

    # Phase 3b: One definition per resource across the standard values files.
    # Libraries that dist_aar packs into one AAR can define the same resource
    # (identical colors and ids, a declare-styleable with different attrs), and
    # aapt2 rejects duplicates inside one library. compat_strings go last: a
    # real definition wins over its placeholder.
    roots = {}
    for name in sorted(standard_entries,
                       key=lambda n: ('/compat_strings' in n, n)):
        if name.startswith('res/') and '/values' in name and name.endswith('.xml'):
            try:
                roots[name] = ET.fromstring(standard_entries[name])
            except ET.ParseError:
                pass
    kept = {}  # (config, kind, name) -> (file, element)
    dirty = set()
    for name, root in roots.items():
        config = values_config(name)
        for elem in list(root):
            rname = elem.get('name', '')
            if not rname:
                continue
            kind = elem.get('type') if elem.tag == 'item' and elem.get('type') \
                else elem.tag
            key = (config, kind, rname)
            if key not in kept:
                kept[key] = (name, elem)
                continue
            if elem.tag == 'declare-styleable':
                owner, first = kept[key]
                have = {c.get('name') for c in first}
                for child in elem:
                    if child.get('name') not in have:
                        first.append(child)
                        have.add(child.get('name'))
                        dirty.add(owner)
            root.remove(elem)
            dirty.add(name)
    for name in dirty:
        standard_entries[name] = ET.tostring(
            roots[name], encoding='unicode', xml_declaration=True).encode('utf-8')
    if dirty:
        print('Duplicate values resources removed from %d files' % len(dirty))

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

    # Phase 5a: Resources of the Maven artifacts the POM declares. A copy here
    # is the version Chromium pins, and it can override the newer one an app
    # picks (resource merge order follows dependency order). Drop a file only
    # when it is byte-identical to the artifact's, and a values entry only when
    # it is identical in the same values folder: anything Chromium changed or
    # redefines on purpose stays.
    lib_files, lib_values = maven_vendored.owned_resources()
    dropped_files = dropped_values = 0
    for name in list(standard_entries):
        if not name.startswith('res/'):
            continue
        if '/values' not in name:
            if lib_files.get(name) == standard_entries[name]:
                del standard_entries[name]
                dropped_files += 1
            continue
        if not name.endswith('.xml'):
            continue
        root = ET.fromstring(standard_entries[name])
        folder = os.path.dirname(name)
        keep = [e for e in root
                if maven_vendored.canonical(e) not in
                lib_values.get((folder, e.tag, e.get('name')), ())]
        if len(keep) == len(root):
            continue
        dropped_values += len(root) - len(keep)
        if not keep:
            del standard_entries[name]
            continue
        root[:] = keep
        standard_entries[name] = (b'<?xml version="1.0" encoding="utf-8"?>\n' +
                                  ET.tostring(root, encoding='utf-8'))
    print('Maven-provided resources dropped: %d files, %d values entries'
          % (dropped_files, dropped_values))

    # Phase 5b: Consumer R8 rules. dist_aar concatenates the rule files of every
    # dependency into proguard.txt, and R8 applies them to the CONSUMER's whole
    # app. Two of them are Chrome's APK policy, not library rules: they strip
    # the app's own Log.v/d/i (-maximumremovedandroidloglevel 3), declare
    # Throwable.addSuppressed, String.format and static fields of android.** and
    # java.** side-effect free, and enable -allowaccessmodification, all in code
    # that is not ours. Drop those sections, and fail if they are not found (a
    # Chromium rename must not silently bring them back) or if a blanket keep of
    # org.chromium reappears.
    if 'proguard.txt' in standard_entries:
        rules = standard_entries['proguard.txt'].decode('utf-8')
        sections = re.split(r'(?m)^(?=# FROM: )', rules)
        app_policy = ('base/android/proguard/chromium_apk.flags',
                      'base/android/proguard/remove_logging.flags')
        kept, dropped = [], []
        for sec in sections:
            first = sec.splitlines()[0] if sec else ''
            if first.startswith('# FROM: ') and first.rstrip().endswith(app_policy):
                dropped.append(first[len('# FROM: '):].strip())
            else:
                kept.append(sec)
        if len(dropped) != len(app_policy):
            sys.exit('slim_aar: expected to drop %s from proguard.txt, dropped %s'
                     % (list(app_policy), dropped))
        rules = ''.join(kept)
        if re.search(r'(?m)^-keep class org\.chromium\.\*\* \{', rules):
            sys.exit('slim_aar: a blanket -keep of org.chromium.** is back in '
                     'proguard.txt; it pins R8 near 10% for every consumer')
        standard_entries['proguard.txt'] = rules.encode('utf-8')
        print('Consumer rules: dropped %d app-policy sections' % len(dropped))

    # Phase 6: Clean classes.jar.
    STRIP_FROM_JAR = maven_vendored.CONSUMER_PROVIDED
    if 'classes.jar' in standard_entries:
        jar_data = standard_entries['classes.jar']
        in_jar = zipfile.ZipFile(io.BytesIO(jar_data))
        # Classes a Maven artifact provides (Guava, Material, GMS, Firebase,
        # ...). Vendoring them made every consumer that uses the same library
        # fail with "Duplicate class". The POM declares the artifacts listed in
        # maven-deps.txt instead, and this fails if that list is stale.
        owned = maven_vendored.ownership(
            maven_vendored.vendored_candidates(in_jar.namelist()))
        maven_vendored.check(owned)
        from_maven = set().union(*owned.values()) if owned else set()
        print('Maven-provided classes stripped: %d from %d artifacts'
              % (len(from_maven), len(owned)))
        out_buf = io.BytesIO()
        out_jar = zipfile.ZipFile(out_buf, 'w', zipfile.ZIP_DEFLATED)
        for ji in in_jar.infolist():
            if ji.filename.startswith('META-INF/'):
                continue
            if ji.filename.endswith(('.bin', '.kotlin_module')):
                continue
            if any(ji.filename.startswith(p) for p in STRIP_FROM_JAR):
                continue
            if ji.filename in from_maven:
                continue
            out_jar.writestr(ji, in_jar.read(ji.filename))
        out_jar.close()
        in_jar.close()
        standard_entries['classes.jar'] = out_buf.getvalue()
        standard_entries[maven_vendored.AAR_ENTRY] = (
            '\n'.join(maven_vendored.runtime_dependencies()) + '\n').encode()
        standard_entries[maven_vendored.POM_AAR_ENTRY] = (
            '\n'.join(maven_vendored.read_deps(maven_vendored.POM_DEPS_FILE))
            + '\n').encode()

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
    # Without these the engine traps at startup (InitializeICU, resource
    # bundle). They are looked up next to --input, so slimming an AAR copied
    # elsewhere used to produce one that silently lacked them.
    required = ('icudtl.dat', 'resources.pak', 'chrome_100_percent.pak')
    missing = [r for r in required
               if not os.path.exists(os.path.join(build_dir, r))]
    if missing:
        sys.exit('slim_aar: %s not found next to %s' % (missing, args.input))
    # The engine reads these through ApkAssets, which appends
    # BuildConfig.APK_ASSETS_SUFFIX to the names in APK_ASSETS_SUFFIXED_LIST
    # (as WebView does for its own): an older WebView adds its APK to the host
    # app's AssetManager, and its icudtl.dat would otherwise win the lookup.
    suffix, suffixed = read_asset_suffix()
    unlisted = sorted(set(assets.values()) - set(suffixed))
    if unlisted:
        sys.exit('slim_aar: %s missing from APK_ASSETS_SUFFIXED_LIST in %s'
                 % (unlisted, BUILD_CONFIG))
    with zipfile.ZipFile(args.output, 'a') as zout:
        for src, dst in assets.items():
            path = os.path.join(build_dir, src)
            if os.path.exists(path):
                zout.writestr(dst + suffix, open(path, 'rb').read(),
                              compress_type=zipfile.ZIP_STORED)

    in_size = os.path.getsize(args.input)
    out_size = os.path.getsize(args.output)
    print(f'AAR: {in_size // (1024*1024)} MB -> {out_size // (1024*1024)} MB '
          f'({100 - out_size * 100 // in_size}% reduction)')


if __name__ == '__main__':
    main()
