#!/usr/bin/env python3
"""Derive every figure the committed consumer docs state, and rewrite them.

    cef/cefrium_sdk/update-doc-figures.py            # rewrite the docs
    cef/cefrium_sdk/update-doc-figures.py --check    # assert only, exit 1 if wrong

Owns README.md, QUICKSTART-CEFRIUM.md, MIGRATION-CEFRIUM.md and
BUILD-GUIDE-CEFRIUM.md. Every figure in them that states something this repo
already knows -- the patch count, the engine, the SDK version, the service-slot
count, the Android/Gradle/JDK floors -- is derived from the file that defines
it, never typed into prose twice.

It also CHECKS, in those four and in FEATURES-CEFRIUM.md, SCRIPTS-CEFRIUM.md,
RELEASING-CEFRIUM.md and PATCHING-CEFRIUM.md, the references a reader follows:
every Cefrium API the prose names must still be declared in the SDK source,
every Cefrium type and composable it names must still exist, every script path
it tells a reader to run must exist, and every link to another document in the
repo must resolve, anchor included.
FEATURES-CEFRIUM.md states no derived figure on purpose -- its one version,
"Cefrium 0.9.2 / Chromium 152", dates a sensor measurement taken on that build,
and rewriting it would claim a measurement nobody made. What rots in a feature
guide is not its numbers, it is the API names in its examples. Those cannot be
auto-repaired, so a missing one FAILS and a human fixes the prose.

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
the ~MB artifact sizes, the host's disk and RAM, the depot_tools GN revision
(depot_tools is not this repo), and the build-times table, which is a
measurement on one named machine, like the sensor row in FEATURES.

The JDK floor has no in-tree source either -- Chromium's bundled JDK sets it --
but the two consumer docs state it in a dozen places, so it is PROPAGATED from
the one sentence that states it rather than left to drift. See `jdk` below.

A figure with no possible owner is removed from the prose, not faked. The
migration guide advertised "EasyList 61K rules"; the repo bundles no list at
all. Cefrium.loadAdBlockList takes a path the consumer supplies, and
Cefrium.downloadAndLoadEasyList fetches easylist.to and refreshes it every 24
hours, so the rule count is whatever that site serves today and changes with no
commit here. The row now states the mechanism and names the API, which IS
checked.
"""

import os
import re
import shutil
import sys

CEF = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

README = "README.md"
QUICK = "QUICKSTART-CEFRIUM.md"
MIGRATION = "MIGRATION-CEFRIUM.md"
BUILD = "BUILD-GUIDE-CEFRIUM.md"
RELEASING = "RELEASING-CEFRIUM.md"
# RELEASING is a runbook with LIVE commands: its Prerequisites block tells the
# maintainer which JDK to export, so that figure must follow the floor. Its
# other versions are history (past incidents, "since 0.8.0" markers) and no
# anchor touches them.
FEATURES = "FEATURES-CEFRIUM.md"
# The brand logo is a periodic-table tile whose atomic number IS the Chromium
# milestone, so it is a figure-bearing artifact, not decoration. cefrium-web
# keeps its own copy of this file, owned by release-web-update.sh -- which it
# had to be, because that badge sat at 154 while 156 was live. One owner per
# file, so each repo's copy is written by the script that lives with it.
LOGO = "assets/cefrium-logo.svg"
CHANGELOG = "CHANGELOG-CEFRIUM.md"


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

# The Gradle plugin's own Java target. A DIFFERENT number from the JDK floor
# below, and the docs say so three times, because reading them as the same is
# the wrong diagnosis three integrators reached for a class-69 error.
plugin_java = one("cefrium_sdk/gradle-plugin/build.gradle",
                  r"sourceCompatibility = JavaVersion\.VERSION_([0-9]+)")

# The JDK floor is the one consumer-facing figure with NO in-tree source:
# Chromium's bundled JDK sets it, and --check must work in a bare clone with no
# Chromium tree. So it is not DERIVED, it is PROPAGATED from the single sentence
# that states it for consumers, and every other copy is rewritten from that one.
# Without this, the build guide listed "JDK: 21" under System requirements while
# its own export line twenty lines later said "JDK 25 REQUIRED", and sent the
# reader to a <JBR-21> that RELEASING-CEFRIUM.md warns against by name.
# To move the floor, edit that sentence; the rest follow.
jdk = one(QUICK, r"JDK ([0-9]+) is a hard floor")
# Fixed by the JVM spec: class-file major = Java release + 44 (21 -> 65,
# 25 -> 69). Stated in three docs as the error a consumer actually sees.
class_major = str(int(jdk) + 44)

# On stderr, so that --list prints the figure table and nothing else.
print("derived: %s Android patches | engine %s (Chromium %s, CEF branch %s) | "
      "SDK %s" % (count, ctag, milestone, branch, version), file=sys.stderr)
print("         %s sandboxed + %s privileged slots | compileSdk %s, minSdk %s, "
      "targetSdk %s | AGP %s, Gradle %s"
      % (slots["Sandboxed"], slots["Privileged"], compile_sdk, min_sdk,
         target_sdk, agp, gradle), file=sys.stderr)

# ---- the figures ------------------------------------------------------------
# (file, regex, [expected value per capture group], where it reads)
BOTH = (README, QUICK)

FIGURES = [
    # -- patch count, README only
    (README, r"All ([0-9]+) Chromium patches", [count],
     'patch count, "About Cefrium"'),
    (README, r"([0-9]+)\s+Android-specific", [count],
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
    # The upstream branch a CONTRIBUTOR is told to diff against. It said 7871
    # while the port tracked 8078, so the command produced six milestones of
    # unrelated churn. Found by sweeping for version-SHAPED tokens no anchor
    # covered, not by reading: a figure with no anchor reports nothing at all.
    (README, r"git format-patch origin/([0-9]+)\.\.android-port", [branch],
     "CEF branch, the contributor's format-patch command"),
    # The WebView comparison, which is the section an evaluator reads first. It
    # said 149 -- seven milestones adrift -- in the one place the whole argument
    # rests on: that Cefrium pins an engine and WebView does not. The shape
    # sweep missed both because it required the word "Chromium" beside the
    # number, and here the sentence wraps across the line break.
    (README, r"\(currently ([0-9]+)\)", [milestone],
     'engine milestone, "Why Cefrium, not WebView?" prose'),
    (README, r"Chromium version control \| Pinned \(([0-9]+)\)", [milestone],
     'engine milestone, the WebView comparison table'),
    (QUICK, r"ships Chromium ([0-9]+)'s Java-([0-9]+) bytecode", [milestone, jdk],
     "engine + JDK, build-tooling note"),
    # The engine tag the build guide tells the reader to CHECK OUT. It sat at
    # 150.0.7871.46 while 156.0.8078.17 shipped, so anyone following the guide
    # built a different engine from the one the same guide documents.
    (BUILD, r"git checkout ([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)", [ctag],
     "engine, step 1 (the tag the reader checks out)"),
    (BUILD, r"CEF branch ([0-9]+), Chromium ([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)",
     [branch, ctag], "engine + branch, release-notes template"),
    (BUILD, r"security fixes through ([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)", [ctag],
     "engine, release-notes template (the CVE-coverage line)"),
    (QUICK, r"rendered with full Chromium ([0-9]+)", [milestone],
     'engine, step 6 "Run"'),
    # Anchored on the whole table cell. The migration guide's other numbers are
    # NOT ours to rewrite: "(0.5.0+)" marks the release a feature arrived in,
    # "API 33+" and "Android 14+" are WebView's and Credential Manager's floors.
    (MIGRATION, r"Pinned Chromium version \| No \(system\) \| Yes \(([0-9]+)\)",
     [milestone], 'engine, "What Cefrium adds over WebView"'),
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
    # -- the shields.io badge row. A badge is a figure like any other: the value
    # lives inside the image URL, so it is spliced there exactly as in prose.
    (README, r"badge/SDK-([0-9]+\.[0-9]+\.[0-9]+)-", [version],
     "SDK version, README badge"),
    (README, r"badge/Chromium-([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)-", [ctag],
     "engine, README badge"),
    (README, r"badge/Android%20patches-([0-9]+)-", [count],
     "patch count, README badge"),
    (README, r"badge/min%20API-([0-9]+)-", [min_sdk], "minSdk, README badge"),
    # The only numeric text element in the logo: the tile's atomic number.
    (LOGO, r">([0-9]+)</text>", [milestone], "Chromium milestone, brand logo"),
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
    # One anchor, three occurrences, two files: the build guide writes the pair
    # with no spaces around the slash and the quickstart with them.
    ((QUICK, BUILD),
     r"\(Gradle ([0-9]+\.[0-9]+\.[0-9]+)\s*/\s*AGP ([0-9]+\.[0-9]+)\)",
     [gradle, agp], "Gradle + AGP, JDK-error entry"),
    (QUICK, r"AGP ([0-9]+\.[0-9]+)\+ is a\s+whole", [agp],
     "AGP, multi-module troubleshooting"),
    (QUICK, r"version: '([0-9]+\.[0-9]+\.[0-9]+)'\]", [agp_full],
     "AGP, the quoted Gradle error"),
    (QUICK, r"own wrapper on ([0-9]+\.[0-9]+\.[0-9]+)\+", [gradle],
     "Gradle, multi-module note"),
    (QUICK, r"wrapper pinned to ([0-9]+\.[0-9]+\.[0-9]+)\+", [gradle],
     "Gradle, multi-module troubleshooting"),
    # -- the JDK floor, propagated from the quickstart's hard-floor sentence.
    # Every anchor carries left context the HISTORICAL lines do not use, so
    # "(0.7.x and earlier used JDK 21 / Gradle 8.9.)" is left alone.
    (QUICK, r"\*\*JDK ([0-9]+)\*\*, and", [jdk], "JDK floor, build-tooling note"),
    (QUICK, r"compile their own Java at `--release ([0-9]+)`", [jdk],
     "JDK floor, why it cannot move"),
    (QUICK, r"For 0\.8\.0\+ use \*\*JDK ([0-9]+)\*\*", [jdk],
     "JDK floor, JDK-error entry"),
    (BUILD, r"\*\*JDK:\*\* ([0-9]+)", [jdk], "JDK floor, system requirements"),
    (BUILD, r"jdk-([0-9]+)\s+# JDK ([0-9]+) REQUIRED", [jdk, jdk],
     "JDK floor, step 5 export line"),
    (BUILD, r"JAVA_HOME=<jdk-([0-9]+)>", [jdk], "JDK floor, the gate invocation"),
    (BUILD, r"requires JDK ([0-9]+) \(Gradle", [jdk],
     "JDK floor, troubleshooting"),
    ((QUICK, BUILD), r"is older than ([0-9]+) and cannot read", [jdk],
     "JDK floor, the error a consumer sees"),
    ((QUICK, BUILD), r"SDK's Java-([0-9]+) bytecode", [jdk],
     "JDK floor, the bytecode it implies"),
    # Derived FROM the floor by the JVM spec, not typed next to it.
    (QUICK, r"class-file ([0-9]+)", [class_major],
     "class-file major, why the floor cannot move"),
    ((QUICK, BUILD), r"class file major version ([0-9]+)", [class_major],
     "class-file major, the error text"),
    # The plugin's own target, which is NOT the floor. Derived from its build file.
    (QUICK, r"built for Java ([0-9]+) and applies on an older JDK", [plugin_java],
     "Gradle-plugin Java target, build-tooling note"),
    (QUICK, r"compiled for Java ([0-9]+) on purpose", [plugin_java],
     "Gradle-plugin Java target, JDK-error entry"),
    # The runbook's own export. "NOT the Android Studio JBR 21" and "javac 21"
    # on the next line are HISTORY -- the JDK that stopped working at 0.8.0 --
    # so the anchors carry wording those do not use.
    (RELEASING, r"JDK ([0-9]+) -- Chromium's bundled Temurin", [jdk],
     "JDK floor, release prerequisites"),
    (RELEASING, r"ships Java-([0-9]+) \(class-([0-9]+)\)", [jdk, class_major],
     "JDK floor + class-file major, release prerequisites"),
]


# The docs this script REWRITES, because every figure in them has an owner
# above. FEATURES-CEFRIUM.md is checked but never rewritten.
OWNED = (README, QUICK, MIGRATION, BUILD, RELEASING)
# Rewritten: the docs plus the logo. DOCS below stays prose-only, so the API,
# type, script and link checks are not run against an SVG.
REWRITTEN = OWNED + (LOGO,)


def figures_for(path):
    for spec in FIGURES:
        files = spec[0] if isinstance(spec[0], tuple) else (spec[0],)
        if path in files:
            yield spec[1], spec[2], spec[3]


def report(texts):
    """Print one line per figure. True when every figure is current."""
    ok = True
    for path in REWRITTEN:
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
# Checked, never rewritten. FEATURES states no derived figure; the three
# maintainer docs state no consumer-facing one. All four name APIs, types,
# documents and SCRIPT PATHS a reader is told to run, and those must resolve.
# SCRIPTS-CEFRIUM.md is the index of every script, so it is where a deleted one
# lingers: three were deleted in one afternoon and nothing checked the docs.
CHECKED_ONLY = (FEATURES, CHANGELOG, "SCRIPTS-CEFRIUM.md",
                "PATCHING-CEFRIUM.md", "VERSIONING-CEFRIUM.md")
DOCS = OWNED + CHECKED_ONLY

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
# Also not checked: "webView.". Half of MIGRATION-CEFRIUM.md is Android
# WebView's own API, quoted so a reader can compare it to ours. Those names are
# Google's to change, this repo declares none of them, and the comparison is
# worthless if one side is dropped for being undeclarable.

# Types and composables the docs name WITHOUT a receiver: "CefriumWebView(",
# "rememberCefriumViewState", a `CefriumViewState` in backticks. The receiver
# scan above cannot see any of them -- there is no dot -- so every Compose entry
# point the guides tell a reader to call went unchecked until this was added.
# \w+ after the prefix, not \w*, or prose ABOUT the pattern ("every
# rememberCefrium* composable") is read as a use of a type called
# "rememberCefrium".
TYPE_USE = r"\b((?:Cefrium[A-Z]\w*|rememberCefrium\w+))\b"
TYPE_DECL = r"\b(?:class|interface|enum|fun|object)\s+%s\b"
# An apk_name, for the test APK the README tells the reader to install.
GN_DECL = r'apk_name\s*=\s*"%s"'

# Every script path the docs tell a reader to RUN. This is the build guide's own
# rot class: its figures are the engine and the toolchain, but what a reader
# actually types is a path. Anchored on the "cef/" prefix the docs use, so
# "./dev-build.sh" and "./gradlew" -- which live in other repos -- are not
# claimed to be ours.
SCRIPT_USE = r"(?<![\w/.-])cef/([A-Za-z0-9_./-]+\.(?:sh|py))"


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


def declared_symbols():
    """Every Java/Kotlin source a doc may legitimately name, as one blob.

    Wider than sdk_sources() on purpose: a type is resolved against the whole
    repo, not one file, because the docs name Compose entry points from five
    different .kt files and the test APK's own activity.
    """
    blob = []
    for rel in ("cefrium_sdk/java/src/com/cefrium",
                "cefrium_compose/java/src/com/cefrium/compose",
                "tests/cef_android_test/apk/src/org/cefrium/test"):
        d = os.path.join(CEF, rel)
        if not os.path.isdir(d):
            die("source dir not found: %s" % rel)
        for name in sorted(os.listdir(d)):
            if name.endswith((".java", ".kt")):
                with open(os.path.join(d, name), encoding="utf-8",
                          errors="replace") as fh:
                    blob.append(fh.read())
    blob.append(read("tests/cef_android_test/BUILD.gn"))
    return "\n".join(blob)


def slug(heading):
    """The anchor a Markdown renderer gives a heading."""
    out = re.sub(r"[^a-z0-9 -]", "", heading.strip().lower())
    return re.sub(r"-+", "-", out.replace(" ", "-"))


# A FILE NAME is not a method call. The lookbehind in RECEIVERS stops
# "assets/cefrium-browser.png", but not a header in backticks: RELEASING names
# Chromium's `browser.h`, which read as a one-letter member of our browser.
#
# A deny-list, not a pattern, because no pattern separates `browser.h` from
# `state.isLoading` -- both are a dotted name in backticks with no parentheses.
# An extension that is NOT listed therefore fails, loudly, and a human adds it.
# That is the right side to err on here: the opposite default would wave through
# a genuinely renamed API, which is the whole thing this file exists to catch.
FILE_EXT = {"h", "cc", "cpp", "py", "sh", "md", "kt", "java", "xml", "gn",
            "gni", "png", "svg", "txt", "json", "jar", "aar", "apk", "so",
            "properties", "gradle", "patch", "html", "yml", "zip"}


def check_changelog(text):
    """Assert the record. A changelog is never rewritten: its figures ARE history.

    "Engine: Chromium 154.0.8037.98 / CEF branch 8037" under [0.9.4] states what
    0.9.4 shipped. Deriving that to the current engine would rewrite the past,
    and the release notes and the /releases page are generated from these
    sections. So only two things are checked, and both hold at every moment:

      - Cefrium.VERSION has a section, and it is the newest released one. This
        catches the version pinned with the entry never written, which is live:
        release-web-update.sh only WARNED and fell back to "New release.".
      - every "Chromium A.B.C.D / CEF branch E" line has E == C, the build
        number. That is the branch rule, so a typo'd branch is caught without
        asserting any value against today's engine.

    What is NOT asserted: that the newest entry names the CURRENT engine. After
    an engine bump and before the next cut those differ on purpose, and
    engine-bump.sh runs this script, so asserting it would fail every bump.
    """
    problems = []
    heads = re.findall(r"^## \[([^\]]+)\]", text, re.M)
    released = [h for h in heads if re.match(r"^[0-9]+\.[0-9]+\.[0-9]+$", h)]
    if not released:
        problems.append("no released section at all")
    elif released[0] != version:
        problems.append(
            "newest released section is [%s] but Cefrium.VERSION is %s -- "
            "write the [%s] entry before cutting" % (released[0], version, version))
    for full, build, br in re.findall(
            r"Chromium ([0-9]+\.[0-9]+\.([0-9]+)\.[0-9]+) / CEF branch ([0-9]+)",
            text):
        if build != br:
            problems.append("engine line 'Chromium %s / CEF branch %s': the CEF "
                            "branch is the build number, so it must be %s"
                            % (full, br, build))
    if problems:
        print("    BROKEN   %s" % CHANGELOG)
        for p in problems:
            print("             %s" % p)
        return False
    print("    OK       %s: [%s] is the newest entry, engine lines consistent"
          % (CHANGELOG, version))
    return True


def check_references(texts):
    java, kt = sdk_sources()
    symbols = declared_symbols()
    ok = True
    for path in DOCS:
        doc = texts[path]
        problems = []
        for name in sorted(set(re.findall(TYPE_USE, doc))):
            if not (re.search(TYPE_DECL % re.escape(name), symbols)
                    or re.search(GN_DECL % re.escape(name), symbols)):
                problems.append("type %s is declared nowhere in the repo" % name)
        for receiver, source, decl in RECEIVERS:
            # (?![\w*]) because prose names a FAMILY with a wildcard: the
            # changelog says `CefriumBrowser.TERMINATION_*`, and the four real
            # constants are TERMINATION_ABNORMAL/KILLED/CRASHED/OOM. The class
            # must exclude \w too, or the greedy name backtracks off the "_" and
            # captures a "TERMINATION" that is equally undeclared.
            used = sorted(set(re.findall(
                r"(?<![\w/.-])" + re.escape(receiver)
                + r"([A-Za-z_][A-Za-z0-9_]*)(?![\w*])", doc)))
            body = java.get(source) if source else kt
            for name in used:
                if name in FILE_EXT:
                    continue
                if not re.search(decl % re.escape(name), body):
                    problems.append("API %s%s is not declared in %s"
                                    % (receiver, name, source or "cefrium_compose"))
        for rel in sorted(set(re.findall(SCRIPT_USE, doc))):
            if not os.path.exists(os.path.join(CEF, rel)):
                problems.append("script cef/%s does not exist" % rel)
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
            print("    OK       %s: API names, types, scripts and doc links resolve" % path)
    return ok


texts = {p: read(p) for p in DOCS + (LOGO,)}

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
    refs_ok = check_references(texts) and check_changelog(texts[CHANGELOG])
    if figures_ok and refs_ok:
        print("  doc figures current, references resolve")
        sys.exit(0)
    print("\nRewrite them: cef/cefrium_sdk/%s" % os.path.basename(__file__))
    print("A MISSING figure needs the prose or the FIGURES table fixed first --")
    print("a rewrite cannot find a sentence that is no longer there.")
    sys.exit(1)

# FEATURES-CEFRIUM.md is not rewritten: it states no derived figure, and its one
# version dates a measurement (see the module docstring).
for p in REWRITTEN:
    full = os.path.join(CEF, p)
    shutil.copyfile(full, full + ".bak")          # never edit in place (hard rule)
    new = rewrite(texts[p], p)
    with open(full, "w", encoding="utf-8") as fh:
        fh.write(new)
    texts[p] = new

figures_ok = report(texts)
print("  references")
refs_ok = check_references(texts) and check_changelog(texts[CHANGELOG])
if figures_ok and refs_ok:
    for p in REWRITTEN:
        full = os.path.join(CEF, p)
        changed = open(full + ".bak", encoding="utf-8").read() != texts[p]
        os.remove(full + ".bak")                  # kept only when it is needed
        print("  %s: %s" % (p, "UPDATED" if changed else "already current"))
    sys.exit(0)

if not refs_ok:
    print("\nFAIL: a doc names an API or a document that does not exist.")
    print("      Nothing can derive the right name, so fix the prose.")
    for p in REWRITTEN:
        os.remove(os.path.join(CEF, p + ".bak"))
    sys.exit(1)

print("\nFAIL: a figure is still wrong after the rewrite.")
print("      Fix the FIGURES table in cef/cefrium_sdk/%s -- do not hand-edit"
      % os.path.basename(__file__))
print("      the docs, or the next release overwrites them.")
print("      The originals are at %s" % ", ".join(p + ".bak" for p in REWRITTEN))
sys.exit(1)
