#!/bin/bash
# Regenerate the published API docs: Javadoc for the Java SDK (cefrium.com/docs)
# and Dokka for the Compose wrapper (cefrium.com/docs/compose).
#
#   cef/cefrium_sdk/regen-docs.sh [version] [--javadoc-only|--dokka-only]
#
# version defaults to Cefrium.VERSION as compiled in the tree.
#
# WHY THIS IS A SCRIPT AND NOT A RECIPE
#
# This was ~40 lines of fiddly shell in CLAUDE.md, retyped every release, with
# three traps that have each already shipped bad docs:
#
#   1. Two DIFFERENT JDKs are mandatory, in opposite directions. Javadoc needs
#      Chromium's bundled JDK 25, because the SDK is class-69 bytecode that JDK 21
#      cannot even read ("cannot access CefriumBrowser"). Dokka 2.0.0 CRASHES on
#      JDK 25 (IllegalArgumentException: 25.0.3) and must run on the JBR 21.
#   2. Javadoc's classpath needs the standalone JARS (androidx.annotation,
#      jspecify, kotlin-stdlib), not only the AARs. Missing them aborts javadoc on
#      every @Nullable file -- and javadoc leaves the PREVIOUS docs in place, so
#      the failure publishes as "no change". That shipped stale docs in 0.5.1.
#   3. Dokka does NOT refresh a pre-populated output directory: it keeps the old
#      page set and SILENTLY omits newly added API. --rerun-tasks and
#      --no-build-cache do not help; only deleting the output dir does. That is
#      how setPullToRefreshEnabled went undocumented in 0.5.7.
#
# Every one of those failures is silent. So this script ASSERTS the result
# instead of trusting the exit code: it compares the public API found in the
# SOURCE against the pages actually generated, and fails naming what is missing.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="$(cd "$CEF_DIR/.." && pwd)"                       # chromium/src
ROOT="$(cd "$SRC/../.." && pwd)"                       # cef-dev
WEB="$ROOT/cefrium-web"
CEFJAVA="$SCRIPT_DIR/java/src/com/cefrium/Cefrium.java"

VERSION=""; DO_JAVADOC=1; DO_DOKKA=1
while [ $# -gt 0 ]; do
  case "$1" in
    --javadoc-only) DO_DOKKA=0; shift ;;
    --dokka-only)   DO_JAVADOC=0; shift ;;
    -*) echo "unknown flag: $1" >&2; exit 2 ;;
    *)  VERSION="$1"; shift ;;
  esac
done
[ -n "$VERSION" ] || VERSION="$(grep -oE 'String VERSION = "[^"]+"' "$CEFJAVA" | grep -oE '"[^"]+"' | tr -d '"')"
[ -n "$VERSION" ] || { echo "ERROR: no version given and none readable from Cefrium.java" >&2; exit 2; }

# The two JDKs, each mandatory for exactly one of the two generators.
JDK25="$SRC/third_party/jdk/current"
JBR21="$ROOT/../android-studio/android-studio/jbr"
ANDROID_JAR="$SRC/third_party/android_sdk/public/platforms/android-37.0/android.jar"
SDK_AAR="$SRC/out/release/cefrium-sdk.aar"

[ -x "$JDK25/bin/javadoc" ] || { echo "ERROR: no javadoc at $JDK25/bin (Chromium's bundled JDK 25)" >&2; exit 2; }
[ -f "$ANDROID_JAR" ]       || { echo "ERROR: $ANDROID_JAR missing (compileSdk 37)" >&2; exit 2; }
[ -d "$WEB" ]               || { echo "ERROR: web repo not at $WEB" >&2; exit 2; }

WORK="$ROOT/tmp/regen-docs"
rm -rf "$WORK"; mkdir -p "$WORK/cp"
cd "$SRC"
echo "Regenerating docs for Cefrium $VERSION"
echo "  javadoc: $("$JDK25/bin/java" -version 2>&1 | head -1)"
fail=0

# Shared helper: compare the public API in the SOURCE against generated pages.
# This is the whole point of the script -- both generators fail silently, so the
# only trustworthy signal is "is every public symbol actually on a page".
coverage_check() { # <source-dir> <ext> <docs-dir> <label> <symbol-regex>
  python3 - "$@" <<'PY'
import os, re, sys
src, ext, docs, label, pattern = sys.argv[1:6]
names = set()
for root, _, files in os.walk(src):
    for f in files:
        if f.endswith(ext):
            txt = open(os.path.join(root, f), encoding="utf-8", errors="replace").read()
            names.update(m.group(1) for m in re.finditer(pattern, txt, re.M))
if not names:
    print("  WARN: %s -- no public symbols parsed out of the source" % label)
    raise SystemExit(0)
def kebab(n):
    return re.sub(r'(?<!^)(?=[A-Z])', '-', n).lower()
have = set()
for root, dirs, files in os.walk(docs):
    for d in dirs:
        have.add(d.lstrip("-").lower())
    for f in files:
        if f.endswith(".html"):
            have.add(f[:-5].lstrip("-").lower())
# Dokka names pages in kebab-case; javadoc names them after the class. Accept
# either spelling so one helper serves both.
missing = sorted(n for n in names if kebab(n) not in have and n.lower() not in have)
print("  %s: %d public symbols in source, %d documented names, %d missing"
      % (label, len(names), len(have), len(missing)))
for n in missing[:25]:
    print("    UNDOCUMENTED: %s" % n)
raise SystemExit(1 if missing else 0)
PY
}

if [ "$DO_JAVADOC" = "1" ]; then
  echo
  echo "=== Javadoc (Java SDK -> $WEB/docs) ==="
  [ -f "$SDK_AAR" ] || { echo "ERROR: $SDK_AAR missing; build/merge the SDK first" >&2; exit 2; }
  unzip -o -q "$SDK_AAR" classes.jar -d "$WORK" || { echo "ERROR: cannot read classes.jar from the AAR" >&2; exit 2; }

  # Classpath: the SDK's own classes, plus every androidx/material/gms dependency
  # the SDK imports. BOTH forms are needed -- AARs (classes.jar inside) AND plain
  # JARS. androidx.annotation, jspecify and the kotlin stdlib ship as .jar, and
  # without them javadoc fails on every @Nullable file (trap 2 above).
  i=0
  while IFS= read -r aar; do
    i=$((i+1))
    unzip -o -q "$aar" classes.jar -d "$WORK/xtmp" 2>/dev/null \
      && mv "$WORK/xtmp/classes.jar" "$WORK/cp/aar-$i.jar" 2>/dev/null || true
  done < <(find "$HOME/.gradle/caches/modules-2/files-2.1" -name "*.aar" 2>/dev/null \
            | grep -iE "androidx|com\.google\.android\.material|com\.google\.android\.gms|firebase|unifiedpush")
  j=0
  while IFS= read -r jar; do
    j=$((j+1)); cp "$jar" "$WORK/cp/jar-$j.jar" 2>/dev/null || true
  done < <(find "$HOME/.gradle/caches/modules-2/files-2.1" -name "*.jar" 2>/dev/null \
            | grep -ivE "sources|javadoc" \
            | grep -iE "androidx|jspecify|jetbrains|com\.google|kotlin")
  echo "  classpath: $i AAR classes.jar + $j standalone jars"

  # javadoc -d OVERWRITES, so unlike Dokka it needs no pre-clean. But it also
  # leaves the old tree untouched when it ABORTS, which is why the assertions
  # below exist rather than trusting the exit status.
  "$JDK25/bin/javadoc" \
    -d "$WEB/docs" \
    -sourcepath "$CEF_DIR/cefrium_sdk/java/src" \
    -subpackages com.cefrium \
    -classpath "$ANDROID_JAR:$WORK/classes.jar:$WORK/cp/*" \
    -quiet -windowtitle "Cefrium SDK API" -doctitle "Cefrium SDK API" \
    -header "Cefrium $VERSION" -Xdoclint:none 2>&1 | tail -5
  jrc=${PIPESTATUS[0]}
  [ "$jrc" -eq 0 ] || echo "  WARN: javadoc exited $jrc (assertions below decide)"

  # ASSERT 1: every public class in the source has a page.
  coverage_check "$CEF_DIR/cefrium_sdk/java/src/com/cefrium" ".java" "$WEB/docs" \
    "javadoc classes" '^public (?:final |abstract )?(?:class|interface|enum) ([A-Za-z0-9_]+)' \
    || fail=1
  # ASSERT 2: the header really says this version, i.e. the docs were rewritten.
  if grep -rqF "Cefrium $VERSION" "$WEB/docs" 2>/dev/null; then
    echo "  header: docs carry \"Cefrium $VERSION\""
  else
    echo "  MISMATCH: no file under docs/ mentions \"Cefrium $VERSION\" -- javadoc left the OLD tree"
    fail=1
  fi

  echo
  echo "=== Javadoc (cefrium-unifiedpush -> $WEB/docs/unifiedpush) ==="
  "$JDK25/bin/javadoc" \
    -d "$WEB/docs/unifiedpush" \
    -sourcepath "$CEF_DIR/cefrium_unifiedpush/java/src" \
    -subpackages com.cefrium.unifiedpush \
    -classpath "$ANDROID_JAR:$WORK/classes.jar:$WORK/cp/*" \
    -quiet -windowtitle "Cefrium UnifiedPush API" -doctitle "Cefrium UnifiedPush API" \
    -header "Cefrium $VERSION" -Xdoclint:none 2>&1 | tail -5
  jrc=${PIPESTATUS[0]}
  [ "$jrc" -eq 0 ] || echo "  WARN: javadoc exited $jrc (assertions below decide)"
  coverage_check "$CEF_DIR/cefrium_unifiedpush/java/src/com/cefrium/unifiedpush" ".java" \
    "$WEB/docs/unifiedpush" "unifiedpush classes" \
    '^public (?:final |abstract )?(?:class|interface|enum) ([A-Za-z0-9_]+)' || fail=1
fi

if [ "$DO_DOKKA" = "1" ]; then
  echo
  echo "=== Dokka (Compose wrapper -> $WEB/docs/compose) ==="
  [ -x "$JBR21/bin/java" ] || { echo "ERROR: no JBR 21 at $JBR21 (Dokka 2.0.0 crashes on JDK 25)" >&2; exit 2; }
  echo "  dokka: $("$JBR21/bin/java" -version 2>&1 | head -1)"

  # Resolve the Dokka 2.x CLI + its plugin classpath through a throwaway Gradle
  # project, and reuse the resolution when every jar it names still exists. Dokka
  # 2.x's Gradle plugin does not wire up under AGP 9 built-in Kotlin, which is why
  # this goes through the CLI at all. (Follow-up: re-add Dokka to cefrium_compose
  # properly once that is fixed upstream.)
  RESOLVE="$ROOT/tmp/dokka-cli-resolve"
  need_resolve=1
  if [ -s "$RESOLVE/cli.txt" ] && [ -s "$RESOLVE/plugins.txt" ]; then
    # Check every named jar still exists. Done in python because the two files
    # have no trailing newline: concatenating them in the shell glues the cli
    # path onto the first plugin path, producing one bogus "missing" entry and
    # re-resolving on every run.
    if python3 - "$RESOLVE/cli.txt" "$RESOLVE/plugins.txt" <<'PY'
import os, sys
paths = open(sys.argv[1]).read().split("\n") + open(sys.argv[2]).read().split(";")
missing = [p for p in (x.strip() for x in paths) if p and not os.path.isfile(p)]
raise SystemExit(1 if missing else 0)
PY
    then need_resolve=0; fi
  fi
  if [ "$need_resolve" = "1" ]; then
    echo "  resolving dokka-cli 2.0.0 + plugins via a throwaway Gradle project"
    mkdir -p "$RESOLVE"
    cat > "$RESOLVE/settings.gradle" <<'EOF'
rootProject.name = 'dokka-cli-resolve'
EOF
    cat > "$RESOLVE/build.gradle" <<'EOF'
plugins { id 'java' }
repositories { mavenCentral(); google() }
configurations { dokkaCli; dokkaPlugin }
dependencies {
  dokkaCli 'org.jetbrains.dokka:dokka-cli:2.0.0'
  dokkaPlugin 'org.jetbrains.dokka:dokka-base:2.0.0'
  dokkaPlugin 'org.jetbrains.dokka:analysis-kotlin-descriptors:2.0.0'
}
tasks.register('printcp') {
  doLast {
    new File("${projectDir}/cli.txt").text =
      configurations.dokkaCli.files.collect{it.absolutePath}.join('\n')
    new File("${projectDir}/plugins.txt").text =
      configurations.dokkaPlugin.files.collect{it.absolutePath}.join(';')
  }
}
EOF
    # Find a gradle launcher. `gradle` is not on PATH in a plain shell here, so
    # try the project's own wrapper first, then PATH, then the newest already
    # downloaded wrapper distribution (no network needed for that one).
    GRADLE=""
    if [ -x "$RESOLVE/gradlew" ] && [ -f "$RESOLVE/gradle/wrapper/gradle-wrapper.jar" ]; then
      GRADLE="$RESOLVE/gradlew"
    elif command -v gradle >/dev/null 2>&1; then
      GRADLE="gradle"
    else
      GRADLE="$(find "$HOME/.gradle/wrapper/dists" -path "*/bin/gradle" -type f 2>/dev/null \
                | sort -V | tail -1)"
    fi
    [ -n "$GRADLE" ] && [ -x "$GRADLE" ] \
      || { echo "ERROR: no gradle launcher found (tried the wrapper, PATH, and ~/.gradle/wrapper/dists)" >&2; exit 1; }
    echo "  using gradle: $GRADLE"
    ( cd "$RESOLVE" && JAVA_HOME="$JBR21" "$GRADLE" printcp --no-daemon -q ) \
      || { echo "ERROR: could not resolve the Dokka CLI" >&2; exit 1; }
  fi
  CLI_JAR="$(head -1 "$RESOLVE/cli.txt")"
  PLUGINS="$(cat "$RESOLVE/plugins.txt")"
  echo "  cli: $(basename "$CLI_JAR"), plugins: $(tr ';' '\n' < "$RESOLVE/plugins.txt" | grep -c . ) jars"

  # The sourceSet classpath uses ';' (NOT ':') and must OMIT the SDK classes.jar:
  # Dokka runs on JDK 21 here, which cannot read the class-69 SDK bytecode. The
  # cost is that cross-references into the Java SDK render unresolved; the Compose
  # API itself documents fully.
  DCP="$ANDROID_JAR"
  while IFS= read -r jar; do DCP="$DCP;$jar"; done \
    < <(find "$HOME/.gradle/caches/modules-2/files-2.1" -name "*.jar" 2>/dev/null \
         | grep -ivE "sources|javadoc" | grep -iE "androidx|kotlin|jetbrains|jspecify")

  # TRAP 3: the output dir MUST be empty, or new API is silently left out.
  OUT="$WEB/docs/compose"
  rm -rf "$OUT"; mkdir -p "$OUT"
  "$JBR21/bin/java" -jar "$CLI_JAR" \
    -pluginsClasspath "$PLUGINS" \
    -moduleName cefrium-compose \
    -outputDir "$OUT" \
    -sourceSet "-src $CEF_DIR/cefrium_compose/java/src -classpath $DCP -analysisPlatform jvm -jdkVersion 17" \
    2>&1 | tail -4
  drc=${PIPESTATUS[0]}
  [ "$drc" -eq 0 ] || echo "  WARN: dokka exited $drc (assertions below decide)"

  # ASSERT: every public fun in the Kotlin source has a page. This is the exact
  # check that would have caught setPullToRefreshEnabled in 0.5.7.
  coverage_check "$CEF_DIR/cefrium_compose/java/src" ".kt" "$OUT" \
    "dokka functions" '^\s*(?:@\w+\s*)*(?:public\s+)?fun\s+([A-Za-z0-9_]+)' \
    || fail=1
fi

echo
if [ "$fail" = "0" ]; then
  cat <<EOF
Docs regenerated and ASSERTED for $VERSION.
Publish them (separate verified push, never chained):
  cd $WEB && pwd && git remote -v
  git add docs && git commit -m "Regenerate API docs for $VERSION" && git push codeberg pages
EOF
  exit 0
fi
echo "DOCS ARE INCOMPLETE -- see the UNDOCUMENTED/MISMATCH lines above."
echo "Do NOT publish: both generators fail silently, so a bad run looks like success."
exit 1
