#!/bin/bash
# run-instrumentation-suite.sh -- the on-device suite, as a gate.
#
#   cef/cefrium_sdk/run-instrumentation-suite.sh [<version>]
#
# Run from chromium/src. Without a version it tests out/release/cefrium-sdk.aar
# and the compose build output, which is what a CUT uses: the versioned copies
# do not exist yet at that point (release-codeberg.sh makes them), and those two
# files are the bytes verify-release-artifacts.sh has just asserted.
#
# With a version it tests out/release/cefrium-sdk-<version>.aar instead, for
# checking a release after the fact.
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
VERSION="${1:-}"

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
  SDK_AAR="$SRC/out/release/cefrium-sdk.aar"
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
[ "$DEVICES" -gt 1 ] && echo "NOTE: $DEVICES devices attached; Gradle picks one."

echo ">>> staging artifacts into the harness"
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

echo ">>> connectedDebugAndroidTest"
( cd "$ITEST" && ./gradlew connectedDebugAndroidTest --no-daemon )
GRADLE_RC=$?

# Gradle's own status is necessary but not sufficient: read the result files.
python3 - "$RESULTS" "$GRADLE_RC" <<'PY'
import glob, os, sys
import xml.etree.ElementTree as ET

results, gradle_rc = sys.argv[1], int(sys.argv[2])
files = sorted(glob.glob(os.path.join(results, "**", "*.xml"), recursive=True))
if not files:
    print("FAIL: no result XML under %s -- the suite did not run." % results)
    sys.exit(1)

tests = failures = errors = skipped = 0
zero_time = []
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
            if t <= 0:
                zero_time.append("%s.%s" % (case.get("classname"), case.get("name")))

print("    tests=%d failures=%d errors=%d skipped=%d  total=%.1fs"
      % (tests, failures, errors, skipped, total))

problems = []
if gradle_rc != 0:
    problems.append("gradle exited %d" % gradle_rc)
if tests == 0:
    problems.append("no tests ran")
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
print("PASS: %d tests, all green, every case spent time on device." % tests)
PY
