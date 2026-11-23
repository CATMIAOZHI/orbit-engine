#!/bin/bash
# record-hot-ranges.sh -- record which parts of libcef.so a cold start reads,
# and in what order, for the SDK's startup read-ahead (LibraryPrefetch).
#
# Usage: cefrium_sdk/record-hot-ranges.sh <arm64 aar> [--inject]
#   ANDROID_SERIAL  the arm64 device (Android 11+, no root needed)
#   RUNS            cold starts to merge (default 3)
#
# The startup bench (cefrium-sample/benchmark-suite/startup) is built on the
# AAR, installed, and started cold with the read-ahead off while perfetto
# records the kernel's filemap/mm_filemap_add_to_page_cache events. The pages
# of libcef.so read until the first page ran, merged over the runs, become
# ranges in first-read order (gaps of up to 64 KiB read as one), under the
# build-id of the library recorded. --inject adds the list to the AAR as
# assets/cefrium/hot-ranges/<build-id>.txt.
#
# The offsets are only valid for that exact libcef.so, which is why the list
# carries its build-id and the SDK ignores a list that does not match: never
# copy a list between builds.
set -euo pipefail

AAR="$(realpath "${1:?usage: record-hot-ranges.sh <arm64 aar> [--inject]}")"
INJECT="${2:-}"
RUNS="${RUNS:-3}"
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the arm64 device}"
export ANDROID_SERIAL

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"
ROOT="$(cd "$SRC/../.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$SRC/third_party/jdk/current}"
export ANDROID_HOME="${ANDROID_HOME:-$SRC/third_party/android_sdk/public}"
ADB="$ANDROID_HOME/platform-tools/adb"
READELF="$SRC/third_party/llvm-build/Release+Asserts/bin/llvm-readelf"
TP="$SRC/third_party/perfetto/tools/trace_processor"
BENCH="$ROOT/cefrium-sample/benchmark-suite/startup"
PKG=com.cefrium.bench.startup
FILES=/sdcard/Android/data/$PKG/files

WORK="$(mktemp -d -p "$ROOT/tmp" hotranges.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

# The build-id the list will be checked against, from the AAR's own library.
unzip -q -o "$AAR" jni/arm64-v8a/libcef.so -d "$WORK"
ID="$("$READELF" -n "$WORK/jni/arm64-v8a/libcef.so" | awk '!f && /Build ID:/ {print $3; f=1}')"
[ -n "$ID" ] || { echo "ERROR: no build-id in the AAR's arm64 libcef.so" >&2; exit 1; }
echo ">>> libcef.so build-id $ID"
# Named after the library, so each native module carries its own list.
ASSET="assets/cefrium/hot-ranges/$ID.txt"

# The bench, built on this AAR. push + pm install: some OEM builds refuse a
# plain adb install of a new package.
cp "$AAR" "$BENCH/app/libs/cefrium-sdk.aar"
( cd "$BENCH" && ./gradlew assembleRelease --no-daemon -q )
"$ADB" push "$BENCH/app/build/outputs/apk/release/app-release.apk" /data/local/tmp/bench.apk >/dev/null
"$ADB" shell pm install -r /data/local/tmp/bench.apk >/dev/null
if [ ! -x "$BENCH/pagecache/pagecache" ]; then   # gitignored: built here
  "$SRC/third_party/llvm-build/Release+Asserts/bin/clang" --target=aarch64-linux-android29 \
    --sysroot="$SRC/third_party/android_toolchain/ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot" \
    -fuse-ld=lld --rtlib=compiler-rt --unwindlib=none -O2 \
    -o "$BENCH/pagecache/pagecache" "$BENCH/pagecache/pagecache.c"
fi
"$ADB" push "$BENCH/pagecache/pagecache" /data/local/tmp/pagecache >/dev/null
"$ADB" shell chmod 755 /data/local/tmp/pagecache
DIR="$("$ADB" shell pm path $PKG | head -1 | tr -d '\r' | sed 's/^package://; s/base.apk$//')"
LIB="${DIR}lib/arm64/libcef.so"
DEVID="$("$ADB" shell sha256sum "$LIB" | cut -d' ' -f1)"
[ "$DEVID" = "$(sha256sum "$WORK/jni/arm64-v8a/libcef.so" | cut -d' ' -f1)" ] \
  || { echo "ERROR: the installed libcef.so is not the AAR's" >&2; exit 1; }
INODE="$("$ADB" shell stat -c %i "$LIB" | tr -d '\r')"
PAGE="$("$ADB" shell getconf PAGESIZE | tr -d '\r')"
"$ADB" shell "mkdir -p $FILES; touch $FILES/no-prefetch"

cat > "$WORK/trace.pbtx" <<'EOF'
buffers { size_kb: 65536 fill_policy: DISCARD }
data_sources { config { name: "linux.ftrace" ftrace_config { ftrace_events: "filemap/mm_filemap_add_to_page_cache" } } }
duration_ms: 12000
EOF
"$ADB" push "$WORK/trace.pbtx" /data/local/tmp/hotranges.pbtx >/dev/null
echo "select ts, extract_arg(arg_set_id,'index') idx from ftrace_event where name='mm_filemap_add_to_page_cache' and extract_arg(arg_set_id,'i_ino')=$INODE order by ts;" > "$WORK/q.sql"

for i in $(seq 1 "$RUNS"); do
  "$ADB" shell am force-stop $PKG
  sleep 2
  "$ADB" shell /data/local/tmp/pagecache evict "$LIB"
  "$ADB" shell /data/local/tmp/pagecache evict "${DIR}base.apk"
  before="$("$ADB" shell "cat $FILES/startup.csv 2>/dev/null | wc -l" | tr -d '\r')"
  pid="$("$ADB" shell "cat /data/local/tmp/hotranges.pbtx | perfetto --txt -c - --background -o /data/misc/perfetto-traces/hotranges.pftrace" | tr -d '\r' | tail -1)"
  sleep 2
  "$ADB" shell am start -n $PKG/.MainActivity >/dev/null
  while "$ADB" shell "[ -d /proc/$pid ]"; do sleep 1; done
  line="$("$ADB" shell "tail -n +$((before + 1)) $FILES/startup.csv 2>/dev/null | head -1" | tr -d '\r')"
  [ -n "$line" ] || { echo "ERROR: run $i: the bench wrote no result" >&2; exit 1; }
  # Field 5 is process start -> first page running script.
  ready_ms="$(echo "$line" | cut -d, -f5)"
  "$ADB" pull /data/misc/perfetto-traces/hotranges.pftrace "$WORK/run$i.pftrace" >/dev/null
  python3 "$TP" "$WORK/run$i.pftrace" -q "$WORK/q.sql" 2>/dev/null > "$WORK/run$i.csv"
  echo ">>> run $i: first page at ${ready_ms} ms, $(($(wc -l < "$WORK/run$i.csv") - 1)) pages read"
  echo "$ready_ms" > "$WORK/run$i.ready"
done
"$ADB" shell "rm -f $FILES/no-prefetch /data/misc/perfetto-traces/hotranges.pftrace"
"$ADB" shell am force-stop $PKG

OUT="$WORK/libcef-hot-ranges.txt"
python3 - "$WORK" "$RUNS" "$ID" "$OUT" "$PAGE" "$(stat -c %s "$WORK/jni/arm64-v8a/libcef.so")" <<'PY'
import csv, sys
work, runs, bid, out, size = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], int(sys.argv[5])
length = int(sys.argv[6])
first = {}
for i in range(1, runs + 1):
    rows = [(int(r["ts"]), int(r["idx"])) for r in csv.DictReader(open(f"{work}/run{i}.csv"))]
    if not rows:
        sys.exit(f"ERROR: run {i} recorded no reads of libcef.so")
    ready = int(open(f"{work}/run{i}.ready").read()) * 1_000_000
    t0 = rows[0][0]
    # The process starts before its first read of the library, so cutting at
    # first read + ready_ms keeps every page read before the first page ran.
    for ts, page in rows:
        if ts - t0 > ready:
            break
        rel = ts - t0
        if page not in first or rel < first[page]:
            first[page] = rel
runs_ = []
for page in sorted(first):
    if runs_ and (page - runs_[-1][1]) * size <= 64 * 1024:
        runs_[-1][1] = page + 1
        runs_[-1][2] = min(runs_[-1][2], first[page])
    else:
        runs_.append([page, page + 1, first[page]])
runs_.sort(key=lambda r: r[2])
with open(out, "w") as f:
    f.write("# libcef.so ranges a cold start read, in first-read order (record-hot-ranges.sh)\n")
    f.write(f"build-id {bid}\n")
    for a, b, _ in runs_:
        # The last page of the file is partial: end at the file, not the page.
        f.write(f"{a * size} {min(b * size, length)}\n")
mb = sum(b - a for a, b, _ in runs_) * size / 2**20
print(f">>> {len(runs_)} ranges, {mb:.1f} MB")
PY

if [ "$INJECT" = "--inject" ]; then
  mkdir -p "$WORK/assets/cefrium/hot-ranges"
  cp "$OUT" "$WORK/$ASSET"
  # The fixed date and no extra fields of every AAR entry (slim_aar.py), so
  # a rebuild reproduces the AAR.
  ( cd "$WORK" && TZ=UTC touch -d '2001-01-01 00:00:00' "$ASSET" \
      && TZ=UTC zip -q -X "$AAR" "$ASSET" )
  echo ">>> added $ASSET to $AAR"
else
  cp "$OUT" "$ROOT/tmp/libcef-hot-ranges.txt"
  echo ">>> list: $ROOT/tmp/libcef-hot-ranges.txt"
fi
