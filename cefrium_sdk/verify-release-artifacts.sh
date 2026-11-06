#!/bin/bash
# Assert that the artifacts a release is about to publish actually contain what
# the release claims. Run from chromium/src.
#
#   verify-release-artifacts.sh <version> [--apk <path>]
#
# WHY THIS EXISTS
#
# Three times in one day a build reported success while shipping something stale,
# and each was invisible until a device ran it:
#   - the checked-in GEN_JNI.java was from the previous engine (107 JNI methods
#     missing) -> NoSuchMethodError on the first child-process launch;
#   - `ninja cefrium_aar` recompiled the Java but did not repackage the .aar;
#   - a sample's APK came out OLDER than the AAR staged into its libs/, because
#     Gradle reported "40 actionable tasks: 40 up-to-date".
# In every case the exit code was 0 and the log said the right things. The only
# reliable signal is the bytes in the artifact, so check those.
#
# Exits non-zero on the first mismatch, naming it. Nothing here is advisory.
set -uo pipefail

VERSION="${1:?usage: verify-release-artifacts.sh <version> [--apk <path>]}"; shift || true
APK=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"
REL="$SRC/out/release"
CEFJAVA="$SCRIPT_DIR/java/src/com/cefrium/Cefrium.java"
JDK="$SRC/third_party/jdk/current/bin"
READELF="$SRC/third_party/llvm-build/Release+Asserts/bin/llvm-readelf"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fail=0
bad() { echo "  MISMATCH: $*"; fail=1; }
ok()  { echo "  ok: $*"; }

# The engine version the SDK ADVERTISES. Everything else is checked against this
# rather than against a hardcoded number, so the check cannot drift from the tree.
ENGINE="$(grep -oE 'CHROMIUM_VERSION = "[0-9.]+"' "$CEFJAVA" | grep -oE '[0-9.]+')"
[ -n "$ENGINE" ] || { echo "ERROR: cannot read CHROMIUM_VERSION from $CEFJAVA"; exit 2; }
echo "Verifying release $VERSION (engine $ENGINE)"

check_aar() { # <aar> <label> <expect-both-abis:yes|no>
  local aar="$1" label="$2" both="$3"
  local d="$WORK/$label"   # separate: bash expands a `local` line before assigning
  if [ ! -f "$aar" ]; then bad "$label missing ($aar)"; return; fi
  mkdir -p "$d"

  # 1. ABIs. x86_64 lives inside this AAR, so its absence is what breaks emulator
  #    users at runtime with UnsatisfiedLinkError -- never a build error.
  local abis
  abis="$(unzip -l "$aar" 2>/dev/null | grep -oE 'jni/[a-z0-9_-]+/libcef\.so' | sed 's|jni/||;s|/libcef.so||' | sort -u | tr '\n' ' ')"
  if [ "$both" = "yes" ]; then
    case "$abis" in
      *arm64-v8a*) ;; *) bad "$label has no arm64-v8a (got: ${abis:-none})" ;;
    esac
    case "$abis" in
      *x86_64*) ;; *) bad "$label has no x86_64 -- emulator users would get UnsatisfiedLinkError (got: ${abis:-none})" ;;
    esac
    [ -n "$abis" ] && ok "$label ABIs: $abis"
  fi

  # 2. The engine inside the binary must be the one we advertise.
  if unzip -o -q "$aar" "jni/arm64-v8a/libcef.so" -d "$d" 2>/dev/null; then
    # grep -cF, not -qF: with `set -o pipefail`, grep -q exits on the first hit
    # and SIGPIPEs `strings`, which pipefail then reports as a failed pipeline --
    # a false "stale binary" on every correct artifact. -c consumes all input.
    local hits
    hits="$(strings -a "$d/jni/arm64-v8a/libcef.so" 2>/dev/null | grep -cF "$ENGINE" || true)"
    if [ "${hits:-0}" -gt 0 ]; then
      ok "$label libcef.so carries $ENGINE"
    else
      bad "$label libcef.so does NOT carry $ENGINE -- stale binary"
    fi
  else
    bad "$label has no jni/arm64-v8a/libcef.so to inspect"
  fi

  # 3. The startup read-ahead list was recorded from this arm64 libcef.so. The
  #    SDK would ignore a mismatched one, so a stale list costs the gain silently.
  if [ -f "$d/jni/arm64-v8a/libcef.so" ]; then
    local want have
    want="$("$READELF" -n "$d/jni/arm64-v8a/libcef.so" 2>/dev/null | awk '!f && /Build ID:/ {print $3; f=1}')"
    have="$(unzip -p "$aar" assets/cefrium/libcef-hot-ranges.txt 2>/dev/null | awk '!f && /^build-id / {print $2; f=1}')"
    if [ -n "$want" ] && [ "$have" = "$want" ]; then
      ok "$label read-ahead list matches libcef.so ($want)"
    else
      bad "$label read-ahead list is for '${have:-none}', libcef.so is '${want:-unreadable}'"
    fi
  fi

  # 4. The SDK version compiled into the Java must equal the release version.
  #    This is what caught Cefrium.VERSION lagging across releases before.
  if unzip -o -q "$aar" classes.jar -d "$d" 2>/dev/null; then
    local v
    v="$("$JDK/javap" -classpath "$d/classes.jar" -constants com.cefrium.Cefrium 2>/dev/null \
         | grep -oE 'String VERSION = "[^"]+"' | grep -oE '"[^"]+"' | tr -d '"')"
    if [ "$v" = "$VERSION" ]; then ok "$label Cefrium.VERSION = $v"
    else bad "$label Cefrium.VERSION is '${v:-unreadable}', expected '$VERSION'"; fi
  else
    bad "$label has no classes.jar"
  fi

  # 5. The manifest declares a targetSdkVersion. Without one, every app's
  #    manifest merger treats the SDK as older than API 4 and adds
  #    READ_PHONE_STATE and READ/WRITE_EXTERNAL_STORAGE to the app (0.17.1 and
  #    earlier shipped that way).
  local m
  m="$(unzip -p "$aar" AndroidManifest.xml 2>/dev/null)"
  if [[ "$m" == *"<uses-sdk"*"android:targetSdkVersion="* ]]; then
    ok "$label manifest declares a targetSdkVersion"
  else
    bad "$label manifest has no targetSdkVersion -- apps would get READ_PHONE_STATE and storage permissions"
  fi
}

echo "--- SDK AARs (what gets published) ---"
check_aar "$REL/cefrium-sdk.aar"       "cefrium-sdk"       yes
check_aar "$REL/cefrium-sdk-libre.aar" "cefrium-sdk-libre" yes

# codecs and libre must be DIFFERENT builds, not the same file copied. A
# single-variant cut used to produce cefrium-sdk.aar by `cp`, and the libre
# coordinate silently did not exist.
if [ -f "$REL/cefrium-sdk.aar" ] && [ -f "$REL/cefrium-sdk-libre.aar" ]; then
  a="$(unzip -p "$REL/cefrium-sdk.aar"       jni/arm64-v8a/libcef.so 2>/dev/null | wc -c)"
  b="$(unzip -p "$REL/cefrium-sdk-libre.aar" jni/arm64-v8a/libcef.so 2>/dev/null | wc -c)"
  if [ "$a" = "$b" ]; then bad "codecs and libre libcef.so are byte-identical in size ($a) -- is libre really a separate build?"
  else ok "codecs vs libre are distinct builds ($a vs $b bytes)"; fi
fi

echo "--- Gradle plugin ---"
PJ="$SCRIPT_DIR/gradle-plugin/build/libs/gradle-plugin.jar"
if [ -f "$PJ" ]; then
  mkdir -p "$WORK/pj" && ( cd "$WORK/pj" && unzip -o -q "$PJ" )
  # The plugin is loaded during Gradle's configuration phase, so its bytecode
  # level dictates the JDK a consumer needs merely to APPLY it. It must stay at
  # or below 61 (JVM 17), the floor Gradle 9 / AGP 9 already require.
  hi=0
  while IFS= read -r f; do
    m="$(od -An -tu1 -j7 -N1 "$f" | tr -d ' ')"
    [ -n "$m" ] && [ "$m" -gt "$hi" ] && hi="$m"
  done < <(find "$WORK/pj" -name '*.class')
  if [ "$hi" -le 61 ]; then ok "plugin bytecode major $hi (<= 61, so JDK 17+ can load it)"
  else bad "plugin bytecode major $hi forces consumers onto a newer JDK just to apply the plugin"; fi
else
  bad "gradle-plugin.jar missing ($PJ)"
fi

if [ -n "$APK" ]; then
  echo "--- flagship APK ---"
  if [ ! -f "$APK" ]; then bad "APK missing ($APK)"; else
    # An APK older than the AAR staged for it means Gradle said "up-to-date" and
    # shipped the previous engine. Compare mtimes, not the build log.
    if [ -f "$REL/cefrium-sdk.aar" ] && [ "$REL/cefrium-sdk.aar" -nt "$APK" ]; then
      bad "APK is OLDER than cefrium-sdk.aar -- Gradle reused a stale build; run with clean"
    else ok "APK is newer than the SDK AAR it was built against"; fi
    abis="$(unzip -l "$APK" 2>/dev/null | grep -oE 'lib/[a-z0-9_-]+/libcef\.so' | sed 's|lib/||;s|/libcef.so||' | sort -u | tr '\n' ' ')"
    case "$abis" in *x86_64*) ok "APK ABIs: $abis" ;; *) bad "APK has no x86_64 (got: ${abis:-none})" ;; esac
    mkdir -p "$WORK/apk"
    if unzip -o -q "$APK" "lib/arm64-v8a/libcef.so" -d "$WORK/apk" 2>/dev/null; then
      ahits="$(strings -a "$WORK/apk/lib/arm64-v8a/libcef.so" 2>/dev/null | grep -cF "$ENGINE" || true)"
      if [ "${ahits:-0}" -gt 0 ]; then ok "APK libcef.so carries $ENGINE"
      else bad "APK libcef.so does NOT carry $ENGINE"; fi
    fi
  fi
fi

echo
if [ "$fail" = "0" ]; then
  echo "All artifact assertions passed -- the bytes match what $VERSION claims."
  exit 0
fi
cat <<EOF
RELEASE ARTIFACTS DO NOT MATCH THE CLAIM -- do not publish.
Maven versions are immutable, so a wrong artifact cannot be replaced, only
superseded. Rebuild the offending variant from a CLEAN out dir and re-run this.
EOF
exit 1
