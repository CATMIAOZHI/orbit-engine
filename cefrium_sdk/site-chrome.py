#!/usr/bin/env python3
# One top bar and one footer for every content page of cefrium.com. They are
# copied into each static page, so they drifted: six different bars and nine
# footers across eighteen pages. Pages without a `topnav` (demo and test pages,
# the browser's privacy policy) are left alone.
#
#   site-chrome.py <web-dir>          rewrite the pages
#   site-chrome.py <web-dir> --check  exit 1 if any page differs
import glob
import os
import re
import sys

NAV = [('/quickstart/', 'Quickstart'), ('/guides/', 'Guides'), ('/docs/', 'API'),
       ('/releases/', 'Releases'), ('/about/', 'About'), ('https://codeberg.org/cefrium/cef-android', 'Source')]
FOOT = [[('/', 'Cefrium'), ('/quickstart/', 'Quickstart'), ('/guides/', 'Guides'),
         ('/docs/', 'API'), ('/releases/', 'Releases')],
        [('/kiosk/', 'Kiosks'), ('/benchmarks/', 'Benchmarks'), ('/upstream/', 'Upstream'),
         ('/commercial/', 'Support &amp; consulting'), ('/about/', 'About')],
        [('/policy/', 'Trademark &amp; forks'), ('/privacy/', 'Browser privacy policy'),
         ('https://codeberg.org/cefrium/cef-android', 'Source')]]


def nav(section):
    links = ''.join(
        f'    <a href="{h}"{" class=\"active\"" if h == section else ""}>{t}</a>\n'
        for h, t in NAV)
    return ('<nav class="topnav">\n'
            '  <a href="/" class="brand"><img src="/avatar.svg" alt="">Cefrium</a>\n'
            f'  <div class="links">\n{links}  </div>\n</nav>')


def footer():
    rows = ' |\n'.join('  ' + ' | '.join(f'<a href="{h}">{t}</a>' for h, t in row)
                       for row in FOOT)
    return ('<footer>\n' + rows + '\n  <br>LGPL-3.0-or-later. Not affiliated with or '
            'endorsed by Google or the Chromium project.\n</footer>')


web, check = sys.argv[1], '--check' in sys.argv
bad = 0
for p in sorted(glob.glob(os.path.join(web, '**/index.html'), recursive=True)):
    rel = os.path.relpath(p, web)
    if rel.startswith('docs/'):
        continue
    s = open(p).read()
    if '<nav class="topnav">' not in s:
        continue
    section = '/' + rel.split('/')[0] + '/' if '/' in rel else '/'
    t = re.sub(r'<nav class="topnav">.*?</nav>', lambda m: nav(section), s, count=1, flags=re.S)
    t = re.sub(r'<footer>.*?</footer>', lambda m: footer(), t, count=1, flags=re.S)
    if t != s:
        bad += 1
        if check:
            print(f'  site chrome differs: {rel}')
        else:
            open(p, 'w').write(t)
            print(f'  {rel}')
if check and bad:
    print(f'FAIL: {bad} page(s) differ from the shared top bar and footer; '
          'run site-chrome.py <web-dir>')
    sys.exit(1)
print('site chrome: ' + ('consistent' if check else f'{bad} page(s) rewritten'))
