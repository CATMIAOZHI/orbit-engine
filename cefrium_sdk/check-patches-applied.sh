#!/bin/bash
# check-patches-applied.sh -- is every patch's content actually IN the tree?
#
# WHY THIS EXISTS: `apply-patches.py` cannot answer that question. It reports a
# patch as "failed" both when the patch is already applied and cannot replay
# (harmless) and when part of it is genuinely missing (a broken build, or worse,
# a silently wrong one). On the 154->156 bump it printed the same "3 failed" for
# two fully-applied patches and for `chrome_runtime`, which was missing EVERY
# hunk in chrome/app/chrome_main_delegate.cc.
#
# That gap could not self-heal: `git apply` is atomic per invocation, so once
# one file of a multi-file patch is reverted -- which happens to the files
# shared between a core patch and an android one -- every later run sees the
# other files already applied, fails as a whole, and changes nothing.
#
# Three cheaper tests all give false positives and each one fooled us: "files do
# not differ from HEAD" (a FAILED patch also leaves them pristine), reverse-apply
# (known-fuzz patches do not reverse-apply), and a signature-line heuristic.
# This script uses the one test that survived: CONTENT PRESENCE -- take
# distinctive lines the patch ADDS and grep the target file for them.
#
# Matching by line number does NOT work: 156 moved SetupTracing() from line 1043
# to 1144, which made a correctly-applied hunk look absent.
#
# Usage:  cef/cefrium_sdk/check-patches-applied.sh [--verbose]
# Exit:   0 = every patch's content is present
#         1 = at least one patch is ABSENT or PARTIAL in some file
#
# LIMIT, stated plainly: the probes are a sample of each file's added lines, so
# this proves a patch is missing, not that every last hunk landed. It catches
# the failure mode that actually bites (a whole file's worth of hunks gone).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"          # chromium/src
cd "$SRC"

VERBOSE=0
[ "${1:-}" = "--verbose" ] && VERBOSE=1

python3 - "$VERBOSE" <<'PY'
import os, sys, glob, re

verbose = sys.argv[1] == "1"
patches = sorted(glob.glob("cef/patch/patches/*.patch")) + \
          sorted(glob.glob("cef/patch/patches/android/*.patch"))

# Not every patch applies to chromium/src. patch.cfg gives some of them another
# repository root ('path': 'v8', 'third_party/dawn', ...), and resolving their
# files against src made this script report three healthy patches as missing.
cfg = open("cef/patch/patch.cfg", errors="replace").read()
roots = {}
for block in cfg.split("{"):
    n = re.search(r"'name'\s*:\s*'([^']+)'", block)
    p = re.search(r"'path'\s*:\s*'([^']+)'", block)
    if n and p:
        roots[n.group(1)] = p.group(1)

# Upstream sometimes RESHAPES what a patch adds instead of dropping it, and a
# literal probe cannot see that. On 156, proto_library.gni renamed
# generate_sources to _generate_sources AND folded our generate_library test
# into a compound condition, so four of six probes miss while the change is
# fully in effect. A rename table is not enough; what survives a reshape is the
# IDENTIFIERS the change introduces.
#
# So one (patch, file) pair at a time, and only with a reason written down, the
# literal test is replaced by the tokens that must still be present. This is
# not a mute button: if those tokens go, the check still fails, and a pair that
# needs an entry here is a patch on its way to obsolete -- the note says so on
# every run so it cannot become background noise.
SEMANTIC = {
    ("chrome_browser_privacy_1119417", "third_party/protobuf/proto_library.gni"): {
        "tokens": ["generate_library", "_generate_sources"],
        "why": "156 renamed generate_sources and folded the generate_library "
               "condition into a compound if; CEF regenerates this on its own bump",
    },
}

def probes_for(path):
    """{target file: [distinctive added lines]} for one patch."""
    cur, added = None, {}
    for line in open(path, errors="replace"):
        if line.startswith("--- "):
            cur = line[4:].split("\t")[0].strip()
            if cur == "/dev/null":
                cur = None
            else:
                added.setdefault(cur, [])
        elif line.startswith("+++"):
            continue
        elif line.startswith("+") and cur:
            t = line[1:].rstrip("\n").strip()
            # Long, non-comment lines are the ones unlikely to appear by chance
            # in the pristine file.
            if len(t) > 25 and not t.startswith(("//", "*", "#if", "#endif")):
                added[cur].append(t)
    return added

bad = []
known = []
for p in patches:
    name = p.split("patches/")[1][:-len(".patch")]
    root = roots.get(name.split("/")[-1], "")
    for f, lines in probes_for(p).items():
        path = os.path.join(root, f) if root else f
        if not lines:
            continue                      # nothing distinctive to test
        if not os.path.exists(path):
            bad.append((name, path, "MISSING FILE", 0, 0))
            continue
        body = open(path, errors="replace").read()
        rule = SEMANTIC.get((name, f))
        if rule:
            gone = [t for t in rule["tokens"] if t not in body]
            if gone:
                bad.append((name, path, "RESHAPED+GONE",
                            len(rule["tokens"]) - len(gone), len(rule["tokens"])))
            else:
                known.append((name, f, rule["why"]))
            continue
        sample = lines[:6]
        hit = sum(1 for t in sample if t in body)
        if hit == len(sample):
            if verbose:
                print(f"  OK      {hit}/{len(sample)}  {name}: {f}")
        else:
            state = "ABSENT" if hit == 0 else "PARTIAL"
            bad.append((name, path, state, hit, len(sample)))

if bad:
    print("PATCH CONTENT MISSING FROM THE TREE:")
    for name, f, state, hit, tot in bad:
        print(f"  {state:<12} {hit}/{tot}  {name}")
        print(f"               {f}")
    print()
    print("CONFIRM BEFORE ACTING -- extract that file's section and test it:")
    print("  git apply -p0 --check <section>")
    print("    applies clean  -> the content really is missing; the stored patch")
    print("                      was fine and the TREE lost it. Apply the")
    print("                      section alone (or --reject and fix by hand).")
    print("    does not apply -> it is already there. A PARTIAL often means")
    print("                      upstream RENAMED what we add (e.g. 156 renamed")
    print("                      generate_sources to _generate_sources in")
    print("                      proto_library.gni), which the probes cannot see")
    print("                      and which may mean the patch is going obsolete.")
    sys.exit(1)

for name, f, why in known:
    print(f"  KNOWN RESHAPE  {name}: {f}")
    print(f"                 content present, probes cannot match literally")
    print(f"                 {why}")
print(f"All {len(patches)} patches: content present in every target file.")
PY
