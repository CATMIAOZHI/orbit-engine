#!/bin/bash
# Iteratively find missing Java classes when running without chrome_all_java.
# Each iteration: build → install → run → capture ClassNotFoundException → create stub.
set -e

CHROMIUM_SRC=<cef-dev>/chromium/src
ADB=$CHROMIUM_SRC/third_party/android_sdk/public/platform-tools/adb
STUBS_DIR=$CHROMIUM_SRC/cef/cefrium_sdk/stubs/src
DEPOT_TOOLS=$HOME/development/android/cef-dev/depot_tools

cd "$CHROMIUM_SRC"

iteration=0
while true; do
    iteration=$((iteration + 1))
    echo ""
    echo "=== Iteration $iteration ==="

    # Build
    PATH="$DEPOT_TOOLS:$PATH" autoninja -C out/Official_android_x64 cefrium_test_apk 2>&1 | tail -3
    if [ $? -ne 0 ]; then
        echo "BUILD FAILED"
        exit 1
    fi

    # Install and run
    $ADB install -r out/Official_android_x64/apks/CefriumTest.apk 2>&1 | tail -1
    $ADB shell am force-stop org.cefrium.test
    $ADB logcat -c
    $ADB shell am start -n org.cefrium.test/.CefriumTestActivity >/dev/null 2>&1
    sleep 10

    # Check for success
    if $ADB logcat -d -s Cefrium | grep -q "LOAD COMPLETE"; then
        echo "SUCCESS! All classes resolved."
        $ADB logcat -d -s Cefrium | tail -5
        break
    fi

    # Find missing class
    MISSING=$($ADB logcat -d | grep "ClassNotFoundException" | head -1 | sed 's/.*ClassNotFoundException: //' | tr '/' '.')
    if [ -z "$MISSING" ]; then
        echo "No ClassNotFoundException found. Check logcat manually."
        $ADB logcat -d | grep -i "error\|fatal\|class" | tail -5
        break
    fi

    echo "Missing: $MISSING"

    # Create stub
    CLASS_PATH=$(echo "$MISSING" | tr '.' '/')
    DIR=$(dirname "$STUBS_DIR/$CLASS_PATH.java")
    CLASS_NAME=$(basename "$CLASS_PATH")
    PACKAGE=$(echo "$MISSING" | sed "s/\.$CLASS_NAME$//")

    mkdir -p "$DIR"
    cat > "$STUBS_DIR/$CLASS_PATH.java" << JAVAEOF
package $PACKAGE;
/** Stub for CEF — class called from native at init. */
public class $CLASS_NAME {}
JAVAEOF

    echo "Created stub: $CLASS_PATH.java"

    # Add to BUILD.gn sources list
    echo "  Stub count: $(find $STUBS_DIR -name '*.java' | wc -l)"
done

echo ""
echo "=== Total stubs created: $(find $STUBS_DIR -name '*.java' | wc -l) ==="
find $STUBS_DIR -name '*.java' | sort
