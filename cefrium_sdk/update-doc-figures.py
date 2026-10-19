#!/usr/bin/env python3
"""Derive every figure the committed consumer docs state, and rewrite them.

    cef/cefrium_sdk/update-doc-figures.py            # rewrite the docs
    cef/cefrium_sdk/update-doc-figures.py --check    # assert only, exit 1 if wrong

Owns README.md and QUICKSTART-CEFRIUM.md. Every figure in them that states
something this repo already knows -- the patch count, the engine, the SDK
version, the service-slot count, the Android and Gradle floors -- is derived
from the file that defines it, never typed into prose twice.

It also CHECKS, in those two and in FEATURES-CEFRIUM.md, the references a
reader follows: every Cefrium API the prose names must still be declared in the
SDK source, and every link to another document in the repo must resolve, anchor
included. FEATURES-CEFRIUM.md states no derived figure on purpose -- its one
version, "Cefrium 0.9.2 / Chromium 152", dates a sensor measurement taken on
that build, and rewriting it would claim a measurement nobody made. What rots
in a feature guide is not its numbers, it is the API names in its examples.
Those cannot be auto-repaired, so a missing one FAILS and a human fixes the
prose.

Sources, all in the repo, so --check works in a bare clone (CI has no built
AAR and no Chromium tree):

    patch count         patch/patches/android/*.patch
    engine, SDK version cefrium_sdk/java/src/com/cefrium/Cefrium.java
    service slots       cefrium_sdk/AndroidManifest.xml
    Sdk + Gradle + AGP  cefrium_compose/build.gradle and its wrapper

WHY THIS IS NOT A SHELL SCRIPT like the rest of cefrium_sdk/: a figure is a
CAPTURE GROUP inside a sentence, and only the group is rewritten. Splicing group
spans leaves the sentence untouched, so an anchor can match several artifacts at
once ("cefrium-sdk:X" and "cefrium-compose:X") and still be checked against one
derived value. The shell version matched whole sentences, which forced one
anchor per wording and could not express that at all.

EVERY figure is declared in FIGURES. A figure that is stale is rewritten; one
whose anchor matches nothing FAILS instead of being skipped. That quiet skip is
the defect this file exists to stop: four README sentences said 21 patches
against 34 on disk, the engine line sat six Chromium milestones behind under
"Security updates", and the quickstart told the reader they would see
"full Chromium 152" while 156 shipped.

Figures deliberately NOT owned here, because nothing in the repo defines them:
the JDK floor and the class-file major it implies (set by Chromium's bundled
JDK, which a bare clone does not have), and the ~MB artifact sizes.
"""

import os
import re
import shutil
import sys

CEF = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def die(msg):
    print("FAIL: %s" % msg)
    sys.exit(1)


def read(rel):
    path = os.path.join(CEF, rel)
    if not os.path.exists(path):
        die("source not found: %s" % rel)
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def one(rel, pattern):
    """The single capture in <rel>, or a hard failure naming the file."""
    hits = sorted(set(re.findall(pattern, read(rel))))
    if len(hits) != 1:
        die("%s: expected one match for /%s/, found %s -- a source moved"
            % (rel, pattern, hits if hits else "none"))
    return hits[0]


# ---- derive -----------------------------------------------------------------

patch_dir = os.path.join(CEF, "patch", "patches", "android")
if not os.path.isdir(patch_dir):
    die("patch dir not found: patch/patches/android")
patches = [f for f in os.listdir(patch_dir) if f.endswith(".patch")]
if not patches:
    die("no .patch files in patch/patches/android")
count = str(len(patches))

CEFJAVA = "cefrium_sdk/java/src/com/cefrium/Cefrium.java"
ctag = one(CEFJAVA, r'CHROMIUM_VERSION = "([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)"')
version = one(CEFJAVA, r'\bVERSION = "([0-9]+\.[0-9]+\.[0-9]+)"')
milestone, _, branch, _ = ctag.split(".")

# engine-bump.sh pins .gclient, CHROMIUM_BUILD_COMPATIBILITY.txt and Cefrium.java
# together. If the two committed ones disagree, the docs would state a version
# the build does not use, so stop rather than write either of them.
compat = one("CHROMIUM_BUILD_COMPATIBILITY.txt", r"refs/tags/([0-9][0-9.]*)")
if compat != ctag:
    die("Cefrium.CHROMIUM_VERSION (%s) disagrees with "
        "CHROMIUM_BUILD_COMPATIBILITY.txt (%s). Re-run the engine pin."
        % (ctag, compat))

# Service slots: the value the SDK manifest DECLARES, cross-checked against the
# services it actually ships. A consumer that reads 40 and gets 5 hits the
# "isolated not needed" crash, so the two must agree before either is published.
manifest = read("cefrium_sdk/AndroidManifest.xml")
slots = {}
for kind, meta in (("Sandboxed", "NUM_SANDBOXED_SERVICES"),
                   ("Privileged", "NUM_PRIVILEGED_SERVICES")):
    declared = re.search(
        r'android:name="org\.chromium\.content\.browser\.%s"\s*\n?\s*'
        r'android:value="([0-9]+)"' % meta, manifest)
    if not declared:
        die("cefrium_sdk/AndroidManifest.xml: no %s meta-data" % meta)
    shipped = len(set(re.findall(
        r"org\.chromium\.content\.app\.%sProcessService([0-9]+)" % kind,
        manifest)))
    if int(declared.group(1)) != shipped:
        die("cefrium_sdk/AndroidManifest.xml declares %s=%s but ships %d "
            "%sProcessService entries" % (meta, declared.group(1), shipped, kind))
    slots[kind] = declared.group(1)

# The floors a consumer build must meet are the ones the SDK's own Gradle module
# builds with. Nothing else in the tree states them.
COMPOSE = "cefrium_compose/build.gradle"
compile_sdk = one(COMPOSE, r"compileSdk ([0-9]+)")
min_sdk = one(COMPOSE, r"minSdk ([0-9]+)")
target_sdk = one(COMPOSE, r"targetSdk ([0-9]+)")
agp_full = one(COMPOSE, r"id 'com\.android\.library' version '([0-9.]+)'")
agp = ".".join(agp_full.split(".")[:2])
gradle = one("cefrium_compose/gradle/wrapper/gradle-wrapper.properties",
             r"gradle-([0-9]+\.[0-9]+\.[0-9]+)-bin\.zip")

# On stderr, so that --list prints the figure table and nothing else.
print("derived: %s Android patches | engine %s (Chromium %s, CEF branch %s) | "
      "SDK %s" % (count, ctag, milestone, branch, version), file=sys.stderr)
print("         %s sandboxed + %s privileged slots | compileSdk %s, minSdk %s, "
      "targetSdk %s | AGP %s, Gradle %s"
      % (slots["Sandboxed"], slots["Privileged"], compile_sdk, min_sdk,
         target_sdk, agp, gradle), file=sys.stderr)

# ---- the figures ------------------------------------------------------------
# (file, regex, [expected value per capture group], where it reads)
README = "README.md"
QUICK = "QUICKSTART-CEFRIUM.md"
BOTH = (README, QUICK)

FIGURES = [
    # -- patch count, README only
    (README, r"All ([0-9]+) Chromium patches", [count],
     'patch count, "About Cefrium"'),
    (README, r"([0-9]+) Android-specific", [count],
     "patch count, architecture + sources + tree + methodology"),
    # -- engine
    (README, r"currently `([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)`", [ctag],
     'engine, "Sources" + "Building from source"'),
    (README, r"Currently pinned to ([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)", [ctag],
     'engine, "Security updates"'),
    (README, r"Fork of CEF branch ([0-9]+), tracking Chromium "
             r"([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)", [branch, ctag],
     'engine + branch, "Relationship to upstream CEF"'),
    (README, r"Chromium ([0-9]+) -> branch ([0-9]+)", [milestone, branch],
     'branch-number rule, "Sources"'),
    (QUICK, r"ships Chromium ([0-9]+)'s Java-25 bytecode", [milestone],
     "engine, build-tooling note"),
    (QUICK, r"rendered with full Chromium ([0-9]+)", [milestone],
     'engine, step 6 "Run"'),
    # -- SDK version. Was release-bump-docs.sh, which knew the PREVIOUS version
    # and only failed when that one survived: a coordinate carrying some third
    # version passed it. Derived from Cefrium.VERSION, no argument needed.
    (BOTH, r"id 'com\.cefrium' version '([0-9]+\.[0-9]+\.[0-9]+)'", [version],
     "SDK version, plugin block"),
    (BOTH, r"com\.cefrium:cefrium-(?:sdk|compose|gradle-plugin):"
           r"([0-9]+\.[0-9]+\.[0-9]+)", [version],
     "SDK version, Gradle coordinate"),
    (README, r"cefrium-(?:sdk|compose)-([0-9]+\.[0-9]+\.[0-9]+)\.aar", [version],
     "SDK version, download file name"),
    (README, r"/com/cefrium/cefrium-(?:sdk|compose)/([0-9]+\.[0-9]+\.[0-9]+)/",
     [version], "SDK version, registry URL path"),
    # -- render-process slots
    (QUICK, r"\(([0-9]+) sandboxed \+ ([0-9]+) privileged\)",
     [slots["Sandboxed"], slots["Privileged"]], "service slots, step 4"),
    (QUICK, r"NUM_SANDBOXED_SERVICES=([0-9]+)", [slots["Sandboxed"]],
     "service slots, step 4 + troubleshooting"),
    # -- the floors a consumer build must meet
    (QUICK, r"compileSdk ([0-9]+)", [compile_sdk],
     "compileSdk, build-tooling note + step 2"),
    (QUICK, r"minSdk ([0-9]+)", [min_sdk], "minSdk, step 2"),
    (QUICK, r"Min SDK: ([0-9]+)", [min_sdk], "minSdk, step 1"),
    (QUICK, r"targetSdk ([0-9]+)", [target_sdk], "targetSdk, step 2"),
    # Anchored on wording the HISTORICAL floors do not use, so
    # "(0.7.x and earlier used AGP 8.6+/Gradle 8.9/JDK 21.)" is left alone.
    (QUICK, r"must build with \*\*AGP ([0-9]+\.[0-9]+)\+\*\*, "
            r"\*\*Gradle ([0-9]+\.[0-9]+\.[0-9]+)\+\*\*", [agp, gradle],
     "AGP + Gradle floor, build-tooling note"),
    (QUICK, r"sibling subproject on AGP ([0-9]+\.[0-9]+)", [agp],
     "AGP, multi-module note"),
    (QUICK, r"\(Gradle ([0-9]+\.[0-9]+\.[0-9]+) / AGP ([0-9]+\.[0-9]+)\)",
     [gradle, agp], "Gradle + AGP, JDK-error entry"),
    (QUICK, r"AGP ([0-9]+\.[0-9]+)\+ is a\s+whole", [agp],
     "AGP, multi-module troubleshooting"),
    (QUICK, r"version: '([0-9]+\.[0-9]+\.[0-9]+)'\]", [agp_full],
     "AGP, the quoted Gradle error"),
    (QUICK, r"own wrapper on ([0-9]+\.[0-9]+\.[0-9]+)\+", [gradle],
     "Gradle, multi-module note"),
    (QUICK, r"wrapper pinned to ([0-9]+\.[0-9]+\.[0-9]+)\+", [gradle],
     "Gradle, multi-module troubleshooting"),
]


def figures_for(path):
    for spec in FIGURES:
        files = spec[0] if isinstance(spec[0], tuple) else (spec[0],)
        if path in files:
            yield spec[1], spec[2], spec[3]


def report(texts):
    """Print one line per figure. True when every figure is current."""
    ok = True
    for path in (README, QUICK):
        print("  %s" % path)
        for pattern, want, where in figures_for(path):
            found = list(re.finditer(pattern, texts[path]))
            if not found:
                print("    MISSING  %s" % where)
                print("             nothing matches /%s/ -- the prose moved"
                      % pattern)
                ok = False
                continue
            bad = [m for m in found
                   if [m.group(i + 1) for i in range(len(want))] != want]
            if bad:
                print("    STALE    %s (%d of %d occurrence(s))"
                      % (where, len(bad), len(found)))
                for m in bad:
                    print("             reads %r, must read %r"
                          % (m.group(0), "/".join(want)))
                ok = False
            else:
                print("    OK       %s (%dx)" % (where, len(found)))
    return ok


def rewrite(text, path):
    """Splice each figure's capture groups to the derived value."""
    for pattern, want, _ in figures_for(path):
        out, end = [], 0
        for m in re.finditer(pattern, text):
            for i, value in enumerate(want):
                g = i + 1
                out.append(text[end:m.start(g)])
                out.append(value)
                end = m.end(g)
        out.append(text[end:])
        text = "".join(out)
    return text


# ---- references: APIs and links a reader follows -----------------------------
# Not figures: nothing can be derived, because the right text is a name, not a
# value. They are asserted instead, in every doc including the feature guide.
FEATURES = "FEATURES-CEFRIUM.md"
DOCS = (README, QUICK, FEATURES)

JAVA_DECL = r"(?:public|protected)[\w\s<>\[\],.?@]*?\b%s\s*(?:\(|=|;|\{|,|\))"
KT_DECL = r"\b(?:fun|val|var|class|object|interface)\s+%s\b"

# The receiver must not be preceded by a path or identifier character, or
# "cefrium-browser.png" in the README reads as a call on a browser.
RECEIVERS = [
    ("Cefrium.", "Cefrium.java", JAVA_DECL),
    ("CefriumBrowser.", "CefriumBrowser.java", JAVA_DECL),
    ("browser.", "CefriumBrowser.java", JAVA_DECL),
    ("state.", None, KT_DECL),
]
# Deliberately not checked: "navigator.". The Compose navigator shares the name
# with the web platform's own navigator, so navigator.usb and navigator.loadUrl
# are indistinguishable here, and checking it would reject the web API.


def sdk_sources():
    java = {}
    jdir = os.path.join(CEF, "cefrium_sdk", "java", "src", "com", "cefrium")
    for name in os.listdir(jdir):
        if name.endswith(".java"):
            with open(os.path.join(jdir, name), encoding="utf-8",
                      errors="replace") as fh:
                java[name] = fh.read()
    ktdir = os.path.join(CEF, "cefrium_compose", "java", "src", "com", "cefrium",
                         "compose")
    kt = ""
    for name in sorted(os.listdir(ktdir)):
        if name.endswith(".kt"):
            with open(os.path.join(ktdir, name), encoding="utf-8",
                      errors="replace") as fh:
                kt += fh.read()
    if not java or not kt:
        die("SDK sources not found -- cannot check the API names in the docs")
    return java, kt


def slug(heading):
    """The anchor a Markdown renderer gives a heading."""
    out = re.sub(r"[^a-z0-9 -]", "", heading.strip().lower())
    return re.sub(r"-+", "-", out.replace(" ", "-"))


def check_references(texts):
    java, kt = sdk_sources()
    ok = True
    for path in DOCS:
        doc = texts[path]
        problems = []
        for receiver, source, decl in RECEIVERS:
            used = sorted(set(re.findall(
                r"(?<![\w/.-])" + re.escape(receiver) + r"([A-Za-z_][A-Za-z0-9_]*)",
                doc)))
            body = java.get(source) if source else kt
            for name in used:
                if not re.search(decl % re.escape(name), body):
                    problems.append("API %s%s is not declared in %s"
                                    % (receiver, name, source or "cefrium_compose"))
        for target, anchor in re.findall(r"\]\(([A-Za-z0-9_./-]+\.md)(#[^)]*)?\)", doc):
            full = os.path.join(CEF, target)
            if not os.path.exists(full):
                problems.append("link to %s does not exist" % target)
                continue
            if anchor:
                with open(full, encoding="utf-8", errors="replace") as fh:
                    heads = {slug(h) for h in re.findall(r"^#+\s+(.*)$", fh.read(),
                                                         re.M)}
                if anchor[1:] not in heads:
                    problems.append("link %s%s names no heading in that file"
                                    % (target, anchor))
        if problems:
            ok = False
            print("    BROKEN   %s" % path)
            for p in problems:
                print("             %s" % p)
        else:
            print("    OK       %s: API names and doc links resolve" % path)
    return ok


texts = {p: read(p) for p in DOCS}

if "--list" in sys.argv[1:]:
    # The figure table as data: <file> TAB <regex> TAB <wanted group values>.
    # The staleness test reads this instead of keeping its own copy of the table,
    # so a figure added here is covered by the test without touching it.
    for spec in FIGURES:
        files = spec[0] if isinstance(spec[0], tuple) else (spec[0],)
        for f in files:
            print("%s\t%s\t%s" % (f, spec[1], "\t".join(spec[2])))
    sys.exit(0)

if "--check" in sys.argv[1:]:
    figures_ok = report(texts)
    print("  references")
    refs_ok = check_references(texts)
    if figures_ok and refs_ok:
        print("  doc figures current, references resolve")
        sys.exit(0)
    print("\nRewrite them: cef/cefrium_sdk/%s" % os.path.basename(__file__))
    print("A MISSING figure needs the prose or the FIGURES table fixed first --")
    print("a rewrite cannot find a sentence that is no longer there.")
    sys.exit(1)

# FEATURES-CEFRIUM.md is not rewritten: it states no derived figure, and its one
# version dates a measurement (see the module docstring).
for p in (README, QUICK):
    full = os.path.join(CEF, p)
    shutil.copyfile(full, full + ".bak")          # never edit in place (hard rule)
    new = rewrite(texts[p], p)
    with open(full, "w", encoding="utf-8") as fh:
        fh.write(new)
    texts[p] = new

figures_ok = report(texts)
print("  references")
refs_ok = check_references(texts)
if figures_ok and refs_ok:
    for p in (README, QUICK):
        full = os.path.join(CEF, p)
        changed = open(full + ".bak", encoding="utf-8").read() != texts[p]
        os.remove(full + ".bak")                  # kept only when it is needed
        print("  %s: %s" % (p, "UPDATED" if changed else "already current"))
    sys.exit(0)

if not refs_ok:
    print("\nFAIL: a doc names an API or a document that does not exist.")
    print("      Nothing can derive the right name, so fix the prose.")
    for p in (README, QUICK):
        os.remove(os.path.join(CEF, p + ".bak"))
    sys.exit(1)

print("\nFAIL: a figure is still wrong after the rewrite.")
print("      Fix the FIGURES table in cef/cefrium_sdk/%s -- do not hand-edit"
      % os.path.basename(__file__))
print("      the docs, or the next release overwrites them.")
print("      The originals are at README.md.bak / QUICKSTART-CEFRIUM.md.bak")
sys.exit(1)
