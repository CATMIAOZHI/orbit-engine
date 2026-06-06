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
# patcher.py activates the android/* patches when this env var is set. Its name
# contains '==', which bash rejects in `export name=value`, so pass it via env(1).
env 'target_os=="android"=1' python3 cef/tools/patcher.py

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

## 7. Cut a release

A Cefrium release has three parts that must stay in sync:

1. **Maven artifacts** on the Codeberg registry — the canonical distribution
   channel (what Gradle resolves).
2. **A formal Codeberg release** (a signed tag + notes) — the parity/security
   record that [cefrium.com/releases](https://cefrium.com/releases/) links to.
3. **The cefrium.com/releases row** linking the two.

### 7.1 Build the artifacts publish-maven.sh uploads

1. **SDK variant AARs** in `out/release/` (`cefrium-sdk.aar`,
   `cefrium-sdk-libre.aar`), produced by `build-all-variants.sh` +
   `merge-multiabi.sh`.
2. **Compose AAR**:
   ```bash
   cd chromium/src/cef/cefrium_compose
   cp ../../out/Official_android_arm64/cefrium-sdk.aar libs/   # compileOnly
   ./gradlew assembleRelease --no-daemon
   # → build/outputs/aar/cefrium-compose-release.aar
   ```
3. **Gradle Plugin JAR** — `cef/cefrium_sdk/gradle-plugin/build/libs/gradle-plugin.jar`.

### 7.2 Publish to the Codeberg Maven registry

**Token:** Codeberg → Settings → Applications → Generate Token (package scope),
then `echo "your-token" > ~/.registry-token`.

```bash
cef/cefrium_sdk/build-all-variants.sh all codecs   # embeds V8 snapshot (multi-ABI ready)
cef/cefrium_sdk/merge-multiabi.sh codecs           # -> out/release/cefrium-sdk.aar (arm64+x64)
# (repeat the two lines with `libre` for cefrium-sdk-libre)
cef/cefrium_sdk/publish-maven.sh 0.3.0
```

`publish-maven.sh` uploads `cefrium-sdk`, `cefrium-sdk-libre`, `cefrium-compose`,
`cefrium-gradle-plugin` and the plugin marker POM, each with a generated POM.

**CRITICAL — raw PUT, never multipart.** The Codeberg Maven API expects the raw
file body on a `PUT` (`curl --upload-file`). Using `curl -F`/`--form` sends
`multipart/form-data`, which bakes the MIME envelope (boundary + headers) into
the stored file — every POM/AAR/JAR then starts with `------…` instead of `PK`
/`<?xml` and **no consumer can resolve them**. This silently broke 0.3.0's first
publish (the local-AAR samples never caught it). `publish-maven.sh` now uses
`--upload-file` and runs `verify_artifact()` after every upload — it re-fetches
the artifact and checks the magic bytes (`PK` for AAR/JAR, an XML element for
POMs), aborting the release on corruption.

**Versions are immutable** (registry returns `409`). To re-publish the *same*
version, delete it first:
```bash
TOKEN=$(cat ~/.registry-token)
for n in cefrium-sdk cefrium-sdk-libre cefrium-compose cefrium-gradle-plugin com.cefrium.gradle.plugin; do
  curl -s -X DELETE -H "Authorization: token $TOKEN" \
    "https://codeberg.org/api/v1/packages/cefrium/maven/com.cefrium%3A$n/0.3.0"
done
```
Otherwise bump the version.

### 7.3 Gate — prove it is consumable (DEP-8 style)

The samples, the instrumentation harness and the test APK all use a **local**
AAR (`implementation files('libs/...')`), so they never exercise the registry —
a broken publish is invisible to them. Before announcing, build a throwaway
consumer that resolves the plugin + SDK **from the registry**, like the
quickstart:

```bash
JAVA_HOME=<JBR-21> ANDROID_HOME=<android-sdk> \
GRADLE=/path/to/any/sample/gradlew \
  cef/cefrium_sdk/test-published-release.sh 0.3.0
```

`publish-maven.sh` runs this automatically at the end (skip with
`CEFRIUM_SKIP_SMOKE_TEST=1` when Gradle/SDK are unavailable on the publishing
host). A non-zero exit means the release is **not consumable — do not announce
it**.

### 7.4 Create the formal Codeberg release

Tag the exact commit the artifacts were built from, **signed and annotated**:

```bash
git tag -s v0.3.0 <build-commit> -m "Cefrium 0.3.0"   # -s = GPG-signed
git push codeberg v0.3.0
```

Then create the release (Codeberg → Releases → *New release*, or the API) with
notes following this template:

```
CEF branch 7778 · Chromium 148.0.7778.167.

<one paragraph: what changed in this version>

Published to the Codeberg Maven registry on <YYYY-MM-DD>.

Includes Chromium security fixes through 148.0.7778.167.
```

- The **exact Chromium version** is `chrome/VERSION` in the checkout (the Debian
  overlay), **not** CEF's nominal `CHROMIUM_BUILD_COMPATIBILITY.txt` baseline.
- The repeated **"Includes Chromium security fixes through X"** line is what
  turns the releases table into a CVE-coverage record.

**Asset policy.** The Maven registry is the canonical channel. A Codeberg
release carries the auto-generated source archive plus, optionally, a
checksummed and GPG-signed demo APK + `SHA256SUMS` for verification/archival.
Do **not** attach the ~240 MB AARs by default — they live on the registry. When
assets are attached, state in the notes: *"Artifacts are consumed via the
Codeberg Maven registry; attached files are for verification and archival."*

### 7.5 Update cefrium.com/releases

In the `pages` repo, add one `<tr>` to the static table in
`releases/index.html` (rows are plain HTML, not JS-rendered) linking the version
to its Codeberg release tag and showing the Gradle coordinate. Keep newest
first.

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
