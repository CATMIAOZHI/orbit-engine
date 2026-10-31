#!/usr/bin/env python3
# How long after a Chrome stable update does a Cefrium release carry it? The
# number behind any security-response commitment: promise only what this
# history already shows.
#
# For every Chrome stable update (Linux channel, the tags CEF builds from)
# since the first Cefrium release: the first Cefrium release whose engine is
# that version or newer, and the days in between. Same-major updates are what
# a same-branch bump can deliver; a new major also waits for a CEF branch.
#
#   security-latency.py [--since YYYY-MM-DD] [--csv out.csv]
#
# Engines come from CHROMIUM_BUILD_COMPATIBILITY.txt at each v* tag, dates
# from CHANGELOG-CEFRIUM.md, Chrome dates from the versionhistory API.
import csv
import datetime as dt
import json
import os
import re
import statistics
import subprocess
import sys
import urllib.request

CEF = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = ('https://versionhistory.googleapis.com/v1/chrome/platforms/linux/channels/'
       'stable/versions/all/releases?order_by=starttime%20asc&pageSize=1000'
       '&filter=starttime%3E{since}T00:00:00Z')

args = sys.argv[1:]
since = args[args.index('--since') + 1] if '--since' in args else None
out_csv = args[args.index('--csv') + 1] if '--csv' in args else None


def ver(s):
    return tuple(int(x) for x in s.split('.'))


def git(*a):
    return subprocess.run(['git', '-C', CEF, *a], capture_output=True, text=True,
                          check=True).stdout


# Cefrium releases: version -> (date, engine).
dates = dict(re.findall(r'^## \[(\d+\.\d+\.\d+)\] -+ (\d{4}-\d{2}-\d{2})',
                        open(os.path.join(CEF, 'CHANGELOG-CEFRIUM.md')).read(), re.M))
releases = []
for tag in git('tag', '-l', 'v*').split():
    v = tag[1:]
    if v not in dates:
        continue
    pin = re.search(r"refs/tags/([\d.]+)", git('show', f'{tag}:CHROMIUM_BUILD_COMPATIBILITY.txt'))
    releases.append((dt.date.fromisoformat(dates[v]), v, pin.group(1)))
releases.sort()
if not releases:
    sys.exit('no tagged release with a CHANGELOG date')
start = dt.date.fromisoformat(since) if since else releases[0][0]

# Chrome stable updates, oldest first, with the day each started serving.
chrome, page = [], ''
while True:
    url = API.format(since=start.isoformat()) + (f'&pageToken={page}' if page else '')
    with urllib.request.urlopen(url, timeout=30) as r:
        d = json.load(r)
    for rel in d.get('releases', []):
        day = dt.date.fromisoformat(rel['serving']['startTime'][:10])
        if not chrome or ver(rel['version']) > ver(chrome[-1][1]):
            chrome.append((day, rel['version']))
    page = d.get('nextPageToken', '')
    if not page:
        break
if not chrome:
    sys.exit('versionhistory returned no stable releases')

rows = []
for day, v in chrome:
    # The engine Cefrium shipped when this update came out decides its kind.
    before = [r for r in releases if r[0] <= day]
    shipped = before[-1][2] if before else releases[0][2]
    kind = 'same-major' if ver(v)[0] == ver(shipped)[0] else 'new-major'
    hit = next((r for r in releases if r[0] >= day and ver(r[2]) >= ver(v)), None)
    rows.append({'chrome_version': v, 'chrome_date': day.isoformat(), 'kind': kind,
                 'cefrium_release': hit[1] if hit else '',
                 'cefrium_date': hit[0].isoformat() if hit else '',
                 'days': (hit[0] - day).days if hit else ''})

print(f"{'chrome':<16}{'stable':<12}{'kind':<12}{'cefrium':<10}{'released':<12}days")
for r in rows:
    print(f"{r['chrome_version']:<16}{r['chrome_date']:<12}{r['kind']:<12}"
          f"{r['cefrium_release'] or '-':<10}{r['cefrium_date'] or '-':<12}"
          f"{r['days'] if r['days'] != '' else 'open'}")

today = dt.date.today()
print()
for kind in ('same-major', 'new-major'):
    done = [r['days'] for r in rows if r['kind'] == kind and r['days'] != '']
    open_ = [(today - dt.date.fromisoformat(r['chrome_date'])).days
             for r in rows if r['kind'] == kind and r['days'] == '']
    if done:
        print(f"{kind}: {len(done)} caught up, median {statistics.median(done):g} days, "
              f"max {max(done)} days" + (f"; {len(open_)} open, oldest {max(open_)} days"
                                          if open_ else ''))
    elif open_:
        print(f"{kind}: none caught up; {len(open_)} open, oldest {max(open_)} days")
latest = releases[-1]
print(f"latest Cefrium {latest[1]} ({latest[0]}) ships {latest[2]}; "
      f"latest Chrome stable {chrome[-1][1]} ({chrome[-1][0]})")

if out_csv:
    with open(out_csv, 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
