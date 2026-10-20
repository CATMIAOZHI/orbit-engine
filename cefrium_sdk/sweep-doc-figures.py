#!/usr/bin/env python3
"""Review tool: find a figure that update-doc-figures.py cannot see.

    cef/cefrium_sdk/sweep-doc-figures.py

A figure absent from that script's FIGURES table reports NOTHING -- not STALE,
not MISSING -- so reading its output proves nothing about what is missing from
it. Four passes over BUILD-GUIDE-CEFRIUM.md read straight past
"git format-patch origin/7871..android-port".

So sweep by SHAPE: ask the owner which character spans its anchors cover, scan
every committed doc for version-shaped tokens, and classify what falls outside:

  TIME BOMB    equals today's derived value with no anchor -> correct now,
               wrong after the next bump. This is the bucket to act on.
  STALE?       differs from today's value with no historical marker. Read each;
               most are "since 0.5.0" markers and records of past incidents.
  SOURCE       the JDK floor's own sentence, held by hand on purpose.
  history      a record or an illustration, correct as written. Counted only.

NOT A GATE, and deliberately so: about forty hits are legitimate history whose
phrasing no keyword list will ever enumerate, and a check whose exception list
outgrows its findings is the thing update-doc-figures.py exists to abolish. Run
it when adding a figure to a doc, and after an engine bump.

Found so far: "git format-patch origin/7871" in the README contributing
section, and the release runbook's Prerequisites telling the maintainer to
export a JDK whose version was typed by hand.
"""
import os, re, subprocess, sys

CEF = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OWNER = os.path.join(CEF, "cefrium_sdk/update-doc-figures.py")
# Discovered, not listed: a doc added to the repo and forgotten by
# update-doc-figures.py would otherwise be invisible to BOTH tools.
DOCS = sorted(f for f in os.listdir(CEF)
              if f == "README.md" or (f.endswith("-CEFRIUM.md")
                                      and not f.startswith("LICENSE")))
DOCS += ["SECURITY.md", "cefrium_instrumentation_test/README.md"]

# Source files are swept too, because a figure in a javadoc comment is
# published: Cefrium.java's class javadoc carried "Chrome/149.0.7827.102" as
# the example user agent, seven engine versions stale, and it is served at
# cefrium.com/docs. The checker owns Markdown; nothing looked here.
SRC = ["cefrium_sdk/java/src/com/cefrium/Cefrium.java",
       "cefrium_sdk/java/src/com/cefrium/CefriumBrowser.java",
       "cefrium_compose/java/src/com/cefrium/compose/CefriumView.kt",
       "libcef/browser/android/cefrium_sdk_jni.cc"]
SRC = [p for p in SRC if os.path.exists(os.path.join(CEF, p))]

def read(rel):
    return open(os.path.join(CEF, rel), encoding="utf-8", errors="replace").read()

# ---- the derived values, from the owner itself -------------------------------
run = subprocess.run([OWNER, "--list"], capture_output=True, text=True, cwd=CEF)
s = run.stderr
m = re.search(r"derived: ([0-9]+) Android patches \| engine "
              r"([0-9.]+) \(Chromium ([0-9]+), CEF branch ([0-9]+)\) \| SDK ([0-9.]+)", s)
count, ctag, milestone, branch, version = m.groups()
m2 = re.search(r"([0-9]+) sandboxed \+ ([0-9]+) privileged slots \| compileSdk ([0-9]+), "
               r"minSdk ([0-9]+), targetSdk ([0-9]+) \| AGP ([0-9.]+), Gradle ([0-9.]+)", s)
sandboxed, privileged, csdk, msdk, tsdk, agp, gradle = m2.groups()
jdk = re.search(r"JDK ([0-9]+) is a hard floor", read("QUICKSTART-CEFRIUM.md")).group(1)
CUR = {"engine tag": ctag, "CEF branch": branch, "Chromium milestone": milestone,
       "SDK version": version, "patch count": count, "sandboxed slots": sandboxed,
       "compileSdk": csdk, "minSdk": msdk, "targetSdk": tsdk, "AGP": agp,
       "Gradle": gradle, "JDK floor": jdk}

# ---- spans the anchors cover -------------------------------------------------
spans = {d: set() for d in DOCS + SRC}
for row in run.stdout.strip().split("\n"):
    p, pattern = row.split("\t")[0], row.split("\t")[1]
    if p not in spans:
        continue
    body = read(p)
    for mm in re.finditer(pattern, body):
        for g in range(1, (mm.re.groups or 0) + 1):
            spans[p].update(range(mm.start(g), mm.end(g)))

# ---- shapes ------------------------------------------------------------------
SHAPES = [
    ("engine tag",         r"\b[0-9]{2,3}\.0\.[0-9]{4}\.[0-9]+\b", 0),
    ("CEF branch",         r"\b(?:origin/|branch |CEF |branch_heads/)([0-9]{4})\b", 1),
    ("Chromium milestone", r"\bChromium ([0-9]{3})\b", 1),
    ("SDK version",        r"(?<![0-9.])(0\.[0-9]+\.[0-9]+)(?![0-9.])", 1),
    ("patch count",        r"\b([0-9]{2}) (?:Android|Chromium|android) patch", 1),
    ("compileSdk",         r"\bcompileSdk ([0-9]{2})\b", 1),
    ("minSdk",             r"\bminSdk ([0-9]{2})\b", 1),
    ("AGP",                r"\bAGP ([0-9]+\.[0-9]+)\b", 1),
    ("Gradle",             r"\bGradle ([0-9]+\.[0-9]+\.[0-9]+)\b", 1),
    ("JDK floor",          r"\bJDK ([0-9]{2})\b", 1),
]

# Shapes above need the figure's NAME beside the number ("Chromium 156"), which
# is how "Cefrium pins to a specific Chromium version\n(currently 149)" and
# "| Pinned (149) |" stayed invisible through four passes: the word is on the
# previous line, or absent. So also sweep for the VALUES this port has ever
# shipped, harvested from the changelog, wherever they stand alone.
past = read("CHANGELOG-CEFRIUM.md")
EVER = set(re.findall(r"\b[0-9]{2,3}\.0\.[0-9]{4}\.[0-9]+\b", past))
EVER |= {t.split(".")[0] for t in EVER}          # milestones
EVER |= {t.split(".")[2] for t in EVER if t.count(".") == 3}   # CEF branches
for v in sorted(EVER):
    kind = ("engine tag" if v.count(".") == 3
            else "Chromium milestone" if len(v) == 3 else "CEF branch")
    SHAPES.append((kind, r"(?<![0-9.])(" + re.escape(v) + r")(?![0-9.])", 1))
# Documented markers of a HISTORICAL or EXAMPLE context, matched on the line.
HIST = re.compile(r"e\.g\.|was |were |used |unchanged from|obsolete|silently|"
                  r"^## \[|and earlier|-> |since |first publish|root cause|"
                  r"no longer|stopped|removed|dropped|previously|respin|placeholder|"
                  r"Done \(|\(0\.[0-9]", re.I)

# The ONE value held by hand on purpose: the JDK floor's source sentence. Every
# other statement of the floor is propagated from it, so it cannot itself be a
# figure -- deriving a value from itself proves nothing.
SOURCE = [("QUICKSTART-CEFRIUM.md", r"JDK ([0-9]+) is a hard floor")]
src_spans = {}
for d, pat in SOURCE:
    mm = re.search(pat, read(d))
    src_spans.setdefault(d, set()).update(range(mm.start(1), mm.end(1)))

# A CHANGELOG figure inside a "## [x.y.z]" section IS the record: that is what
# that release shipped. Structural, not a keyword guess.
def in_release_section(doc, pos, body):
    if doc != "CHANGELOG-CEFRIUM.md":
        return False
    heads = [m.start() for m in re.finditer(r"^## \[[0-9]", body, re.M)]
    return bool(heads) and pos > heads[0]

# The SDK's own version constants are the source every doc figure derives
# from, so a match on those lines is the truth, not a copy of it.
CONSTANT = re.compile(r"(?:String )?(?:VERSION|CHROMIUM_VERSION)\s*=|"
                      r"CHROME_VERSION_(?:MAJOR|MINOR|BUILD|PATCH)")

buckets = {"TIME BOMB": [], "SOURCE (by design)": [], "STALE?": [],
           "history/example": []}
for d in DOCS + SRC:
    body = read(d)
    lines = body.split("\n")
    for name, shape, grp in SHAPES:
        for mm in re.finditer(shape, body):
            s0, e0 = (mm.start(grp), mm.end(grp)) if grp else (mm.start(), mm.end())
            val = body[s0:e0]
            if set(range(s0, e0)) & spans[d]:
                continue
            # "~150 MB" is an artifact size, not Chromium 150. The value sweep
            # cannot tell them apart by shape, so exclude the size context.
            if re.match(r"\s*MB", body[e0:e0 + 4]) or body[max(0, s0 - 1):s0] == "~":
                continue
            ln = body[:s0].count("\n") + 1
            line = lines[ln - 1].strip()
            cur = CUR.get(name)
            rec = (name, d, ln, val, line[:100])
            if set(range(s0, e0)) & src_spans.get(d, set()) or CONSTANT.search(line):
                buckets["SOURCE (by design)"].append(rec)
            elif in_release_section(d, s0, body) or HIST.search(line):
                buckets["history/example"].append(rec)
            elif cur is not None and val == cur:
                buckets["TIME BOMB"].append(rec)
            else:
                buckets["STALE?"].append(rec)

print("derived now: engine %s (Chromium %s / branch %s), SDK %s, %s patches,"
      % (ctag, milestone, branch, version, count))
print("             compileSdk %s minSdk %s AGP %s Gradle %s JDK %s"
      % (csdk, msdk, agp, gradle, jdk))
print()
anchored = sum(len(v) for v in spans.values())
print("anchor-covered characters: %d across %d files\n" % (anchored, len([s for s in spans.values() if s])))
for b in ("TIME BOMB", "STALE?", "SOURCE (by design)", "history/example"):
    rs = buckets[b]
    print("=== %s: %d ===" % (b, len(rs)))
    notes = {"TIME BOMB": "correct today, no anchor -> wrong after the next bump",
             "STALE?": "differs from today's value with no historical marker -- read each",
             "SOURCE (by design)": "held by hand on purpose; everything else follows it",
             "history/example": "a record or an illustration; correct as written"}
    print("    (%s)" % notes[b])
    if b == "history/example":
        per = {}
        for name, d, ln, val, line in rs:
            per[d] = per.get(d, 0) + 1
        for d in sorted(per):
            print("      %-26s %d" % (d, per[d]))
    else:
        for name, d, ln, val, line in rs:
            print("  %-18s %s:%-4d %-16s %s" % (name, d, ln, val, line))
    print()
