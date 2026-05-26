# Building Cefrium from Source

Complete instructions for building the Cefrium SDK from Chromium source.

## System requirements

- **OS:** Linux (Debian/Ubuntu recommended)
- **Disk:** ~400 GB (Chromium source + build artifacts)
- **RAM:** 64 GB recommended (32 GB minimum, will be slower)
- **CPU:** Multi-core (build uses all cores)
- **JDK:** 21 (Android Studio JBR recommended)

## 1. Get the source

```bash
# Install depot_tools
git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git
export PATH="$PWD/depot_tools:$PATH"

# Fetch Chromium at the correct tag
mkdir chromium && cd chromium
fetch --nohooks android
cd src
git checkout 148.0.7778.167
gclient sync --with_branch_heads --with_tags

# Clone CEF android-port branch
git clone -b android-port https://codeberg.org/cefrium/cef-android.git cef
```

## 2. Apply patches

```bash
cd chromium/src
export 'target_os=="android"'=1
python3 cef/tools/patcher.py

# CRITICAL: touch all patched files to force recompilation
for patch in cef/patch/patches/*.patch cef/patch/patches/android/*.patch; do
  [ -f "$patch" ] || continue
  for f in $(grep "^--- " "$patch" | grep -v "/dev/null" | sed 's/^--- //;s/\t.*//'); do
    [ -f "$f" ] && touch "$f"
  done
done
```

## 3. Build SDK variants

Use the build script:

```bash
cd chromium/src

# Build all 4 variants (arm64/x64 x codecs/libre)
cef/cefrium_sdk/build-all-variants.sh

# Or build specific variants
cef/cefrium_sdk/build-all-variants.sh arm64 codecs   # arm64 with H.264/AAC
cef/cefrium_sdk/build-all-variants.sh arm64 libre    # arm64 VP8/VP9 only
cef/cefrium_sdk/build-all-variants.sh x64 codecs     # x64 for emulators
cef/cefrium_sdk/build-all-variants.sh x64 libre      # x64 libre
```

Output AARs are in `out/release/`:
```
out/release/
  cefrium-sdk-arm64-codecs.aar    # ~150 MB
  cefrium-sdk-arm64-libre.aar     # ~150 MB
  cefrium-sdk-x64-codecs.aar      # ~150 MB
  cefrium-sdk-x64-libre.aar       # ~150 MB
```

## 4. Build manually (without script)

```bash
cd chromium/src

# Generate build files
depot_tools/gn gen out/Official_android_arm64 --args='
  target_os = "android"
  target_cpu = "arm64"
  is_official_build = true
  is_debug = false
  is_component_build = false
  android_non_final_r_java = true
  proprietary_codecs = true
  ffmpeg_branding = "Chrome"
'
# Omit proprietary_codecs + ffmpeg_branding for the libre variant.
# Use target_cpu = "x64" for emulator builds.

# Build libcef.so + AAR
ninja -C out/Official_android_arm64 cef:libcef cef/cefrium_sdk:cefrium_aar

# Generate slim AAR (346 MB → 150 MB)
python3 cef/cefrium_sdk/slim_aar.py \
  --input out/Official_android_arm64/cefrium-raw.aar \
  --output out/Official_android_arm64/cefrium-sdk.aar
```

## 5. Build sample apps

```bash
export JAVA_HOME=/path/to/android-studio/jbr  # JDK 21 REQUIRED
export ANDROID_HOME=/path/to/android-sdk

# Copy AAR to sample project
cp out/Official_android_arm64/cefrium-sdk.aar \
  ../cefrium-sample/cefrium-browser/app/libs/

# Build browser
cd ../cefrium-sample/cefrium-browser
./gradlew assembleDebug --no-daemon

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 6. Run tests

```bash
cd cefrium-sample/cefrium-browser
./gradlew connectedAndroidTest  # requires connected device
```

## 7. Publish to Codeberg Packages

```bash
# Set up token: Codeberg → Settings → Applications → Generate Token (package scope)
echo "your-token" > ~/.registry-token

cd chromium/src
cef/cefrium_sdk/publish-maven.sh 0.1.0
```

## Build times (reference: Ryzen 9 7900X, 64 GB DDR5, NVMe)

| Build | Time |
|-------|------|
| Full build (first time) | ~45-60 min |
| Incremental (one .cc changed) | ~30 sec |
| Slim AAR | ~10 sec |
| Gradle sample APK | ~1 min |
| All 4 variants | ~3-4 hours |

## Troubleshooting

**JDK version error:** Gradle 8.9 requires JDK 21. JDK 22+ causes
"Unsupported class file major version". Use Android Studio's bundled JBR.

**GN version:** Use `depot_tools/gn` (v2354+), NOT system `/usr/bin/gn`.

**OOM during link:** Reduce parallelism: `ninja -j4 -C out/...`

**Stale objects after patching:** Always touch patched files (step 2).
Without this, ninja won't recompile and you get subtle runtime bugs.
