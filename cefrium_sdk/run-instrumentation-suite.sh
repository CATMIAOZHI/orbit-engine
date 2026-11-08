#!/bin/bash
# run-instrumentation-suite.sh -- the on-device suite, as a gate.
#
#   cef/cefrium_sdk/run-instrumentation-suite.sh [--minified] [<version>]
#
# --minified runs the same suite against the harness app built with R8 on, as a
# consumer that enables minification builds it.
#
# Run from chromium/src. Without a version it tests out/release/cefrium-sdk.aar
# and the compose build output, which is what a CUT uses: the versioned copies
# do not exist yet at that point (release-codeberg.sh makes them), and those two
# files are the bytes verify-release-artifacts.sh has just asserted.
#
# With a version it tests out/release/cefrium-sdk-<version>.aar instead, for
# checking a release after the fact.
#
# CEFRIUM_SDK_AAR=<path> (no version) tests that SDK AAR instead, e.g. the
# dev-iterate.sh output. Like CEFRIUM_TEST_CLASS, never set by a cut.
#
# CEFRIUM_TEST_CLASS=<SimpleName> runs only that class of com.cefrium.itest.sdk
# (compose.<SimpleName> for the Compose tests), for iterating on one feature. It is not a gate: a cut never sets it, and the
# declared-tests check then covers only that class. One class: AGP runs only the
# first of a comma list.
#
# WHY THIS EXISTS: the suite is the only harness that exercises the SDK the way
# a consumer does, and until now it was run by hand. A cut therefore shipped on
# compile success plus one sample launch. That is not enough, and it has already
# cost a release: the sandbox battery passed a build whose touch input was dead,
# and only the suite caught it, because the battery measured isolation and
# filters rather than whether the browser still answered a tap.
#
# WHY IT CHECKS DURATIONS: a suite that cannot tell "nothing happened" from
# "nothing ran" signs empty passes. A result file reading tests=46 failures=0
# with every time=0.000 is not a pass, it is a suite that did not run, and it
# looks identical in the Gradle output. So every case must have spent time.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"          # chromium/src
ITEST="$SRC/cef/cefrium_instrumentation_test"
TASK=connectedDebugAndroidTest
GRADLE_PROPS=()
if [ "${1:-}" = "--minified" ]; then
  TASK=connectedMinifiedAndroidTest
  GRADLE_PROPS=(-PcefriumMinified=1)
  shift
fi
VERSION="${1:-}"
ONLY="${CEFRIUM_TEST_CLASS:-}"
if [ -n "$ONLY" ]; then
  case "$ONLY" in
    compose.*) TEST_CLASS="com.cefrium.itest.$ONLY" ;;
    *) TEST_CLASS="com.cefrium.itest.sdk.$ONLY" ;;
  esac
  case "${ONLY#compose.}" in
    *[!A-Za-z0-9_]*) echo "ERROR: CEFRIUM_TEST_CLASS takes one class name: $ONLY" >&2; exit 1 ;;
  esac
  GRADLE_PROPS+=("-Pandroid.testInstrumentationRunnerArguments.class=$TEST_CLASS")
fi
# CEFRIUM_TEST_ABI=<abi> packages only that ABI, so the suite runs as that
# process type (armeabi-v7a on an arm64 device that also runs 32-bit apps).
[ -n "${CEFRIUM_TEST_ABI:-}" ] && GRADLE_PROPS+=("-PcefriumAbi=$CEFRIUM_TEST_ABI")

if [ ! -f "$SRC/.gn" ]; then
  echo "ERROR: run this from chromium/src" >&2
  exit 1
fi

# Artifacts under test. The unversioned names are left over from whatever was
# built last, so a cut must pass its version and test the bytes it will publish.
if [ -n "$VERSION" ]; then
  SDK_AAR="$SRC/out/release/cefrium-sdk-$VERSION.aar"
  COMPOSE_AAR="$SRC/out/release/cefrium-compose-$VERSION.aar"
else
  SDK_AAR="${CEFRIUM_SDK_AAR:-$SRC/out/release/cefrium-sdk.aar}"
  COMPOSE_AAR="$SRC/cef/cefrium_compose/build/outputs/aar/cefrium-compose-release.aar"
fi
for f in "$SDK_AAR" "$COMPOSE_AAR"; do
  [ -f "$f" ] || { echo "ERROR: missing artifact under test: $f" >&2; exit 1; }
done

ADB="${ANDROID_HOME:-$SRC/third_party/android_sdk/public}/platform-tools/adb"
[ -x "$ADB" ] || { echo "ERROR: no adb at $ADB" >&2; exit 1; }
DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device" {n++} END {print n+0}')
if [ "$DEVICES" -eq 0 ]; then
  echo "ERROR: no device. The suite is an ON-DEVICE gate; there is no dry run." >&2
  exit 1
fi
# Two devices without ANDROID_SERIAL: adb refuses and Gradle picks one, so a
# result could come from a device nobody chose. Make the choice explicit.
if [ "$DEVICES" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
  echo "ERROR: $DEVICES devices attached; set ANDROID_SERIAL." >&2
  exit 1
fi

echo ">>> staging artifacts into the harness"
# The wrapper is gitignored here; a fresh clone takes it from a sample.
WJI="$ITEST/gradle/wrapper/gradle-wrapper.jar"
ROOT="$(cd "$SRC/../.." && pwd)"
[ -f "$WJI" ] || { mkdir -p "$(dirname "$WJI")"; cp "$ROOT/cefrium-sample/push-demo/gradle/wrapper/gradle-wrapper.jar" "$WJI" || exit 1; }
[ -f "$ITEST/gradlew" ] || cp "$ROOT/cefrium-sample/push-demo/gradlew" "$ITEST/gradlew" || exit 1
chmod +x "$ITEST/gradlew"
mkdir -p "$ITEST/app/libs"
cp "$SDK_AAR" "$ITEST/app/libs/cefrium-sdk.aar" || exit 1
cp "$COMPOSE_AAR" "$ITEST/app/libs/cefrium-compose.aar" || exit 1
echo "    sdk:     $(basename "$SDK_AAR")"
echo "    compose: $(basename "$COMPOSE_AAR")"

# Since SDK 0.8.0 the AAR carries Java-25 bytecode, so the harness needs the
# same JDK every consumer needs. Chromium's bundled one is the reference.
export JAVA_HOME="${JAVA_HOME:-$SRC/third_party/jdk/current}"
export ANDROID_HOME="${ANDROID_HOME:-$SRC/third_party/android_sdk/public}"

RESULTS="$ITEST/app/build/outputs/androidTest-results/connected"
rm -rf "$RESULTS"

echo ">>> $TASK"
# A runner that dies in onCreate leaves Gradle waiting forever (6 h once, for
# a minified build missing androidx.tracing). Bound the run.
( cd "$ITEST" && timeout "${CEFRIUM_SUITE_TIMEOUT:-7200}" \
    ./gradlew clean "$TASK" "${GRADLE_PROPS[@]}" --no-daemon )
GRADLE_RC=$?

# Gradle's own status is necessary but not sufficient: read the result files.
python3 - "$RESULTS" "$GRADLE_RC" "$ITEST/app/src/androidTest" "$ONLY" <<'PY'
import glob, os, re, sys
import xml.etree.ElementTree as ET

results, gradle_rc, sources = sys.argv[1], int(sys.argv[2]), sys.argv[3]
only = sys.argv[4].split(".")[-1]

# Every @Test in the sources must appear in the results. A crash of the test
# process ends the run early, and gradle has been seen to exit 0 with a third
# of the suite never executed.
declared = set()
for src in glob.glob(os.path.join(sources, "**", "*.java"), recursive=True) + \
        glob.glob(os.path.join(sources, "**", "*.kt"), recursive=True):
    cls = os.path.splitext(os.path.basename(src))[0]
    if only and cls != only:
        continue
    text = open(src, encoding="utf-8").read()
    for m in re.finditer(r"@Test\b[^\n]*\n(?:\s*@[^\n]*\n)*\s*(?:public\s+)?(?:void|fun)\s+`?(\w+)", text):
        declared.add("%s.%s" % (cls, m.group(1)))
files = sorted(glob.glob(os.path.join(results, "**", "*.xml"), recursive=True))
if not files:
    print("FAIL: no result XML under %s -- the suite did not run." % results)
    sys.exit(1)

tests = failures = errors = skipped = 0
# Classes whose cases are pure computation on data already in memory: on a
# fast emulator they finish under the 1 ms the XML can show. They still count
# as declared tests, so one that never runs is still caught.
INSTANT = {"CefriumLibraryPrefetchTest"}
zero_time = []
executed = set()
total = 0.0
for f in files:
    root = ET.parse(f).getroot()
    for suite in ([root] if root.tag == "testsuite" else root.iter("testsuite")):
        tests += int(suite.get("tests", 0))
        failures += int(suite.get("failures", 0))
        errors += int(suite.get("errors", 0))
        skipped += int(suite.get("skipped", 0))
        for case in suite.iter("testcase"):
            t = float(case.get("time", 0) or 0)
            total += t
            executed.add("%s.%s" % (case.get("classname", "").split(".")[-1],
                                    case.get("name")))
            if t <= 0 and case.get("classname", "").split(".")[-1] not in INSTANT:
                zero_time.append("%s.%s" % (case.get("classname"), case.get("name")))

print("    tests=%d failures=%d errors=%d skipped=%d  total=%.1fs"
      % (tests, failures, errors, skipped, total))

problems = []
if gradle_rc != 0:
    problems.append("gradle exited %d" % gradle_rc)
if tests == 0:
    problems.append("no tests ran")
if not declared:
    problems.append("found no @Test in %s -- the count check cannot run" % sources)
missing = sorted(declared - executed)
if missing:
    problems.append("%d of %d declared test(s) never ran (did the test process "
                    "crash?): %s" % (len(missing), len(declared), ", ".join(missing[:5])))
if failures or errors:
    problems.append("%d failure(s), %d error(s)" % (failures, errors))
if skipped:
    problems.append("%d test(s) skipped -- a skipped test proves nothing" % skipped)
if zero_time:
    problems.append("%d test(s) took 0.000s, which is an empty pass: %s"
                    % (len(zero_time), ", ".join(zero_time[:3])))
if problems:
    print("FAIL: " + "; ".join(problems))
    sys.exit(1)
print("PASS: %d tests, all green, every case spent time on device, "
      "and all %d declared tests ran." % (tests, len(declared)))
PY
