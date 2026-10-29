#!/usr/bin/env python3
# Keep the newest releases on cefrium.com/releases; the rest of the history is
# CHANGELOG-CEFRIUM.md, which the page links to. Each cut adds an entry, so
# without this the page only grows.
#
#   trim-releases-page.py <releases/index.html> [keep]   (default keep: 5)
import re
import sys

path = sys.argv[1]
keep = int(sys.argv[2]) if len(sys.argv) > 2 else 5
s = open(path).read()
entry = re.compile(r'    <details class="rel" id="v[0-9.]+".*?</details>\n\n?', re.S)
found = list(entry.finditer(s))
if len(found) <= keep:
    print(f"  releases: {len(found)} entries, nothing to trim")
    sys.exit(0)
start, end = found[keep].start(), found[-1].end()
s = s[:start] + s[end:]
open(path, 'w').write(s)
print(f"  releases: kept {keep} entries, dropped {len(found) - keep} (history in the changelog)")
