#!/usr/bin/env python3
# Apply the CEF patch set, including the Android-conditional patches.
#
# Why this wrapper exists:
#   patcher.py gates each patch on `if patch['condition'] not in os.environ`
#   (patcher.py:75). The Android patches in patch.cfg carry the condition string
#   'target_os=="android"'. That string is NOT a legal POSIX environment variable
#   name -- it contains '=' and '"'. Consequences:
#     * `export 'target_os=="android"'=1` in a shell does NOT create that key:
#       the shell splits on the first '=', so it actually sets `target_os` to
#       `="android"=1`. The Android patches are then silently skipped.
#     * Assigning os.environ['target_os=="android"'] = '1' in Python raises,
#       because CPython's os._Environ rejects '=' in keys.
#
#   The robust fix: replace os.environ with a plain dict that carries the key
#   (a plain dict has no key validation), then run patcher.py in-process via
#   runpy so it observes that dict. Modules are singletons, so patcher.py's
#   `import os; os.environ` sees exactly what we set here.
#
# Usage (run from anywhere; paths resolve via __file__):
#   python3 cef/cefrium_sdk/apply-patches.py
#
# After applying, remember the project rule: patcher.py does not bump mtimes, so
# touch the patched files before ninja or stale .o files survive (see CLAUDE.md
# "After applying patches, force recompilation").
import os
import sys
import runpy

CEF_TOOLS = os.path.abspath(
    os.path.join(os.path.dirname(__file__), os.pardir, 'tools'))

# patcher.py does `from file_util import *` / `from git_util import ...`, which
# require cef/tools on sys.path (normally provided by running it directly).
sys.path.insert(0, CEF_TOOLS)

# Plain dict bypasses os._Environ key validation; carries the illegal-name key.
env = dict(os.environ)
env['target_os=="android"'] = '1'
os.environ = env

# Forward any extra args (e.g. --resave) to patcher.py.
sys.argv = ['patcher.py'] + sys.argv[1:]
runpy.run_path(os.path.join(CEF_TOOLS, 'patcher.py'), run_name='__main__')
