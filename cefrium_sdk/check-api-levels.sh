#!/bin/bash
# check-api-levels.sh -- fail if SDK or Compose code calls a framework API newer
# than the minSdk without a version check (Android lint NewApi + InlinedApi).
#
# 0.17.0 called ApplicationStartInfo.getStartComponent() (API 36) behind an
# API 35 check: every app crashed at launch on Android 15 (issue #17). The
# Chromium build runs with android_static_analysis="off", so nothing saw it.
#
# Usage:  cef/cefrium_sdk/check-api-levels.sh
# Exit:   0 = clean; 1 = a violation (the report is printed).
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"                 # chromium/src
export JAVA_HOME="${JAVA_HOME:-$SRC/third_party/jdk/current}"
SDK="${ANDROID_HOME:-$SRC/third_party/android_sdk/public}"
LINT="$SDK/cmdline-tools/latest/bin/lint"
MIN_SDK=29   # Chromium's default_min_sdk_version, which the AAR is built for
COMPILE_SDK=37
ANDROID_JAR="$SDK/platforms/android-$COMPILE_SDK.0/android.jar"
[ -f "$ANDROID_JAR" ] || ANDROID_JAR="$SDK/platforms/android-$COMPILE_SDK/android.jar"
ANNOTATIONS="$SRC/third_party/androidx/cipd/libs/androidx_annotation_annotation_jvm/annotation-jvm.jar"
for f in "$LINT" "$ANDROID_JAR" "$ANNOTATIONS"; do
  [ -e "$f" ] || { echo "FAIL: missing $f"; exit 1; }
done

WORK="$(mktemp -d "$SRC/../../tmp/check-api-levels.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
cat > "$WORK/AndroidManifest.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.cefrium.apicheck">
  <uses-sdk android:minSdkVersion="$MIN_SDK" android:targetSdkVersion="$COMPILE_SDK"/>
</manifest>
EOF
cat > "$WORK/lint.xml" <<'EOF'
<lint>
  <issue id="all" severity="ignore"/>
  <issue id="NewApi" severity="error"/>
  <issue id="InlinedApi" severity="error"/>
</lint>
EOF

rc=0
for dir in cef/cefrium_sdk/java/src cef/cefrium_compose/java; do
  [ -d "$SRC/$dir" ] || { echo "FAIL: no sources at $dir"; exit 1; }
  # Lint treats an empty source set as clean; make sure it saw files.
  n=$(find "$SRC/$dir" -name '*.java' -o -name '*.kt' | wc -l)
  [ "$n" -gt 0 ] || { echo "FAIL: no .java/.kt under $dir"; exit 1; }
  "$LINT" --sdk-home "$SDK" --config "$WORK/lint.xml" \
      --sources "$SRC/$dir" --classpath "$ANDROID_JAR:$ANNOTATIONS" \
      --compile-sdk-version "$COMPILE_SDK" --text "$WORK/report.txt" \
      --exitcode "$WORK" > "$WORK/lint.log" 2>&1
  r=$?
  if [ "$r" -eq 0 ]; then
    echo "OK   $dir ($n files, minSdk $MIN_SDK)"
  else
    echo "FAIL $dir: framework API above minSdk $MIN_SDK without a version check"
    grep -E 'Error|^\s' "$WORK/report.txt" | sed 's/^/    /'
    [ -s "$WORK/report.txt" ] || tail -20 "$WORK/lint.log" | sed 's/^/    /'
    rc=1
  fi
done
exit $rc
