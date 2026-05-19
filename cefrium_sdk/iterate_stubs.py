#!/usr/bin/env python3
"""Iteratively find missing Java classes and create stubs.

Runs: build → install → launch → capture ClassNotFoundException → create stub
Updates BUILD.gn sources list automatically.
"""

import os
import re
import subprocess
import sys
import time

CHROMIUM_SRC = '<cef-dev>/chromium/src'
ADB = f'{CHROMIUM_SRC}/third_party/android_sdk/public/platform-tools/adb'
STUBS_DIR = f'{CHROMIUM_SRC}/cef/cefrium_sdk/stubs/src'
BUILD_GN = f'{CHROMIUM_SRC}/cef/cefrium_sdk/BUILD.gn'
DEPOT_TOOLS = os.path.expanduser('~/development/android/cef-dev/depot_tools')


def run(cmd, **kwargs):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True,
                          cwd=CHROMIUM_SRC,
                          env={**os.environ, 'PATH': f'{DEPOT_TOOLS}:{os.environ["PATH"]}'},
                          **kwargs)


def build():
    r = run('autoninja -C out/Official_android_x64 cefrium_test_apk', timeout=600)
    if r.returncode != 0:
        print(f'BUILD FAILED:\n{r.stdout[-500:]}\n{r.stderr[-500:]}')
        return False
    return True


def install_and_run():
    run(f'{ADB} install -r out/Official_android_x64/apks/CefriumTest.apk')
    run(f'{ADB} shell am force-stop org.cefrium.test')
    run(f'{ADB} logcat -c')
    run(f'{ADB} shell am start -n org.cefrium.test/.CefriumTestActivity')
    time.sleep(10)


def get_missing_class():
    r = run(f'{ADB} logcat -d')
    for line in r.stdout.split('\n'):
        if 'ClassNotFoundException' in line:
            m = re.search(r'ClassNotFoundException:\s*(\S+)', line)
            if m:
                return m.group(1).replace('/', '.')
    return None


def check_success():
    r = run(f'{ADB} logcat -d -s Cefrium')
    return 'LOAD COMPLETE' in r.stdout


def create_stub(fqcn):
    class_path = fqcn.replace('.', '/')
    dir_path = os.path.dirname(f'{STUBS_DIR}/{class_path}.java')
    class_name = class_path.split('/')[-1]
    package = fqcn.rsplit('.', 1)[0]

    os.makedirs(dir_path, exist_ok=True)

    # No @CalledByNative methods in stubs — the class just needs to exist.
    # JNI class lookup (LazyGetClassInternal) only checks that the class
    # can be loaded, not that specific methods exist. Method dispatch goes
    # through GEN_JNI which is already compiled into libcef.so.
    methods_str = ''

    with open(f'{STUBS_DIR}/{class_path}.java', 'w') as f:
        f.write(f'package {package};\n')
        f.write(f'/** Stub for CEF. */\n')
        f.write(f'public class {class_name} {{\n')
        f.write(methods_str)
        f.write(f'}}\n')

    return f'stubs/src/{class_path}.java'


def update_build_gn():
    """Update BUILD.gn sources list with all stub files."""
    stubs = []
    for root, dirs, files in os.walk(STUBS_DIR):
        for f in sorted(files):
            if f.endswith('.java'):
                rel = os.path.relpath(os.path.join(root, f),
                                      os.path.dirname(BUILD_GN))
                stubs.append(f'    "{rel}",')

    sources_block = '\n'.join(sorted(stubs))

    with open(BUILD_GN) as f:
        lines = f.readlines()

    # Find and replace the sources block in cef_java_stubs
    out = []
    in_sources = False
    found = False
    for line in lines:
        if 'cef_java_stubs' in line and 'android_library' in line:
            found = True
        if found and 'sources = [' in line and not in_sources:
            in_sources = True
            out.append('  sources = [\n')
            out.append(sources_block + '\n')
            continue
        if in_sources:
            if line.strip() == ']':
                out.append('  ]\n')
                in_sources = False
            # Skip old source lines
            continue
        out.append(line)

    with open(BUILD_GN, 'w') as f:
        f.writelines(out)


def main():
    for i in range(1, 200):
        print(f'\n=== Iteration {i} ===')

        update_build_gn()

        if not build():
            print('Build failed. Check errors above.')
            sys.exit(1)

        install_and_run()

        if check_success():
            stubs = len([f for f in os.listdir(STUBS_DIR)
                         if f.endswith('.java')] if os.path.isdir(STUBS_DIR) else [])
            print(f'\nSUCCESS! All classes resolved.')
            # Count stubs recursively
            count = sum(1 for r, d, fs in os.walk(STUBS_DIR) for f in fs if f.endswith('.java'))
            print(f'Total stubs: {count}')
            run(f'{ADB} logcat -d -s Cefrium')
            r = run(f'{ADB} logcat -d -s Cefrium')
            print(r.stdout[-500:])
            break

        missing = get_missing_class()
        if not missing:
            print('No ClassNotFoundException. Check for other errors:')
            r = run(f'{ADB} logcat -d')
            for line in r.stdout.split('\n'):
                if any(k in line.lower() for k in ['fatal', 'error', 'crash', 'signal']):
                    if 'cefrium' in line.lower() or 'chromium' in line.lower():
                        print(f'  {line.strip()}')
            break

        print(f'Missing: {missing}')
        rel_path = create_stub(missing)
        print(f'Created: {rel_path}')


if __name__ == '__main__':
    main()
