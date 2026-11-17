#!/bin/bash
# components-resolve.sh -- every library Gradle puts on an app's runtime
# classpath for com.cefrium:cefrium-sdk:<version>, with what pulls it in.
#
#   cef/cefrium_sdk/components-resolve.sh <version> [<out-file>]
#
# Default out file: out/release/cefrium-sdk-<version>-resolved.txt. Run from
# chromium/src. The cut runs it in the `web` phase (release-web-update.sh),
# after the release is on maven.cefrium.com, so the list is what an adopter
# resolves. CEFRIUM_MAVEN_URL resolves from another repository (a staged
# file:// directory) instead.
#
# Deterministic: a fixed throwaway Android app (AGP and Gradle pinned to the
# SDK's floor, no other dependency), its releaseRuntimeClasspath, only the
# subtree under cefrium-sdk, sorted. Same version in, same file out.
#
# Format, one library per line, sorted:
#   group:artifact<TAB>resolved version<TAB>parents (group:artifact, comma
#   separated; com.cefrium:cefrium-sdk-core for a direct dependency)<TAB>
#   core|gms: whether an app on cefrium-sdk-core alone (no cefrium-gms) gets
#   it too, from a second resolution of exactly that.
set -euo pipefail
VERSION="${1:?usage: components-resolve.sh <version> [<out-file>]}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"                 # chromium/src
ROOT="$(cd "$SRC/../.." && pwd)"                       # cef-dev
OUT="${2:-$SRC/out/release/cefrium-sdk-$VERSION-resolved.txt}"
REPO="${CEFRIUM_MAVEN_URL:-https://maven.cefrium.com}"
export JAVA_HOME="${JAVA_HOME:-$SRC/third_party/jdk/current}"
export ANDROID_HOME="${ANDROID_HOME:-$SRC/third_party/android_sdk/public}"

# The same pins the SDK states as the consumer floor (cefrium_compose).
AGP="$(grep -oP "id 'com\.android\.library' version '\K[0-9.]+" \
       "$SRC/cef/cefrium_compose/build.gradle")"
WRAPPER="$ROOT/cefrium-sample/push-demo"
[ -n "$AGP" ] || { echo "ERROR: no AGP version in cefrium_compose/build.gradle" >&2; exit 1; }

WORK="$ROOT/tmp/components-resolve-$VERSION"
rm -rf "${WORK:?}"
mkdir -p "$WORK/app/src/main" "$WORK/core/src/main" "$WORK/gradle"
cp -r "$WRAPPER/gradle/wrapper" "$WORK/gradle/"
cp "$WRAPPER/gradlew" "$WORK/"
cat > "$WORK/settings.gradle" <<EOF
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        maven { url '$REPO'; content { includeGroup 'com.cefrium' } }
        google()
        mavenCentral()
    }
}
rootProject.name = 'components-resolve'
include ':app', ':core'
EOF
cat > "$WORK/build.gradle" <<EOF
plugins { id 'com.android.application' version '$AGP' apply false }
EOF
cat > "$WORK/app/build.gradle" <<EOF
plugins { id 'com.android.application' }
android {
    namespace 'com.cefrium.componentsresolve'
    compileSdk 37
    defaultConfig { minSdk 29 }
}
dependencies { implementation 'com.cefrium:cefrium-sdk:$VERSION' }
EOF
echo '<manifest />' > "$WORK/app/src/main/AndroidManifest.xml"
sed -e "s/componentsresolve/componentsresolvecore/" \
    -e "s/cefrium-sdk:$VERSION/cefrium-sdk-core:$VERSION/" \
    "$WORK/app/build.gradle" > "$WORK/core/build.gradle"
cp "$WORK/app/src/main/AndroidManifest.xml" "$WORK/core/src/main/"

TREE="$WORK/tree.txt"
CORE_TREE="$WORK/tree-core.txt"
for m in app core; do
  t="$TREE"; [ "$m" = core ] && t="$CORE_TREE"
  ( cd "$WORK" && ./gradlew -q --no-daemon ":$m:dependencies" \
      --configuration releaseRuntimeClasspath ) > "$t" 2>"$WORK/gradle-$m.err" || {
    echo "ERROR: Gradle could not resolve the $m module for $VERSION (see $WORK/gradle-$m.err)" >&2
    exit 1
  }
done

python3 - "$TREE" "$CORE_TREE" "$OUT" <<'PY'
import re, sys
tree, core_tree, out = sys.argv[1], sys.argv[2], sys.argv[3]
core = set(re.findall(r'--- ([^:\s]+:[^:\s]+)', open(core_tree, encoding='utf-8').read()))
line_re = re.compile(r'^((?:[| ] {4})*)[+\\]--- (.*)$')
stack, inside, versions, parents = [], None, {}, {}
for line in open(tree, encoding='utf-8'):
    m = line_re.match(line.rstrip('\n'))
    if not m:
        continue
    depth = len(m.group(1)) // 5
    spec = re.sub(r' \((?:\*|c|n)\)$', '', m.group(2)).strip()
    if spec.startswith('project '):
        continue
    # "g:a:v", "g:a:v -> w" (conflict) or "g:a -> w" (no version asked).
    base, _, upgraded = spec.partition(' -> ')
    parts = base.split(':')
    ga = ':'.join(parts[:2])
    version = upgraded or (parts[2] if len(parts) > 2 else '')
    stack = stack[:depth] + [ga]
    if depth == 0:
        inside = ga == 'com.cefrium:cefrium-sdk'
        continue
    if not inside or ga.startswith('com.cefrium:'):
        continue
    versions[ga] = version
    parent = stack[depth - 1]
    if parent == 'com.cefrium:cefrium-sdk':
        continue
    parents.setdefault(ga, set()).add(parent)
if not versions:
    sys.exit('components-resolve: nothing under com.cefrium:cefrium-sdk in %s' % tree)
with open(out, 'w', encoding='utf-8') as f:
    for ga in sorted(versions):
        f.write('%s\t%s\t%s\t%s\n' % (ga, versions[ga],
                                      ','.join(sorted(parents.get(ga, ()))),
                                      'core' if ga in core else 'gms'))
print('%d libraries -> %s' % (len(versions), out))
PY
