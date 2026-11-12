# Building Cefrium from Source

Scope: this document is for CEFRIUM (CEF ported to native Android) and its SDK, not upstream CEF.

Complete instructions for building the Cefrium SDK from Chromium source.

## System requirements

- **OS:** Linux (Debian/Ubuntu recommended)
- **Disk:** ~400 GB (Chromium source + build artifacts)
- **RAM:** 64 GB recommended (32 GB minimum, will be slower)
- **CPU:** Multi-core (build uses all cores)
- **JDK:** 25 (Chromium's own bundled Temurin, NOT the Android Studio JBR)

## 1. Get the source

```bash
# Install depot_tools
git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git
export PATH="$PWD/depot_tools:$PATH"

# Fetch Chromium at the correct tag
mkdir chromium && cd chromium
fetch --nohooks android
cd src
git checkout 156.0.8078.88
gclient sync --with_branch_heads --with_tags

# Clone CEF android-port branch
git clone -b android-port https://codeberg.org/cefrium/cef-android.git cef
```

## 2. Apply patches

```bash
cd chromium/src
# Apply ALL CEF patches (core + android/*). The android patches are gated on the
# condition key 'target_os=="android"', which is not a legal env var name (it has
# '=' and '"'): neither `export` nor `env(1)` can set it (both split on the first
# '='), so they would silently skip the android set. apply-patches.py sets the key
# correctly and runs patcher.py in-process.
python3 cef/cefrium_sdk/apply-patches.py

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

# Build all 6 variants (arm64/x64/arm x codecs/libre)
cef/cefrium_sdk/build-all-variants.sh

# Or build specific variants
cef/cefrium_sdk/build-all-variants.sh arm64 codecs   # arm64 with H.264/AAC
cef/cefrium_sdk/build-all-variants.sh arm64 libre    # arm64 VP8/VP9 only
cef/cefrium_sdk/build-all-variants.sh x64 codecs     # x64 for emulators
cef/cefrium_sdk/build-all-variants.sh x64 libre      # x64 libre
cef/cefrium_sdk/build-all-variants.sh arm codecs     # armeabi-v7a (32-bit ARM)
cef/cefrium_sdk/build-all-variants.sh arm libre      # armeabi-v7a libre
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

This is what the script does, written out. Use it to read the build or to run
one step by hand; a release is cut with the script, so if the two ever
disagree, the script is right.

```bash
cd chromium/src

# Generate build files. The dir name matters: build-all-variants.sh keys its
# engine stamp off out/Official_android_<cpu>_<codecs|libre>.
depot_tools/gn gen out/Official_android_arm64_codecs --args='
  target_os = "android"
  target_cpu = "arm64"
  is_official_build = true
  is_debug = false
  is_component_build = false
  clang_use_chrome_plugins = false
  android_static_analysis = "off"
  symbol_level = 0
  blink_symbol_level = 0
  is_clang = true
  enable_jni_multiplexing = false
  use_hashed_jni_names = false
  chrome_pgo_phase = 2
  pgo_data_path = "//chrome/android/orderfiles/arm64/pgo_profile.arm64.profdata"
  v8_use_external_startup_data = false
  disable_fieldtrial_testing_config = true
  proprietary_codecs = true
  ffmpeg_branding = "Chrome"
'
# For the libre variant: proprietary_codecs = false, no ffmpeg_branding.
# Use target_cpu = "x64" for emulator builds, with chrome_pgo_phase = 0 and no
# pgo_data_path: the tree has a PGO profile for arm64 only.
# arm64 also needs V8's builtins profile: "checkout_pgo_profiles": True in the
# .gclient custom_vars, then gclient runhooks.

# Build libcef.so + AAR
ninja -C out/Official_android_arm64_codecs cef:libcef cef/cefrium_sdk:cefrium_aar

# Generate slim AAR (346 MB -> 150 MB)
python3 cef/cefrium_sdk/slim_aar.py \
  --input out/Official_android_arm64_codecs/cefrium-raw.aar \
  --output out/release/cefrium-sdk-arm64-codecs.aar
```

`android_non_final_r_java` used to be in this recipe and is gone: GN rejects it
on 156 as an unknown build argument, so it had no effect wherever it was set.
It was also the suspect for the test APK failing to build in a variant dir with
`R.java:5: error: too many constants`, which it is not: adding it reproduced
the identical failure, and the reason is now plain, since GN was discarding it
either way. That failure is the merged R class exceeding the class-file
constant-pool limit, and it is still open. Nothing here depends on it: the AAR,
the suite and the samples all build. It blocks only `cefrium_test_apk` in these
variant dirs, which is why the test-APK-first check runs in a dir of its own.

## 5. Build sample apps

```bash
export JAVA_HOME=/path/to/jdk-25  # JDK 25 REQUIRED for 0.8.0+ (Gradle 9.7.1/AGP 9.4); JDK 21 for 0.7.x
export ANDROID_HOME=/path/to/android-sdk

# The browser lives in its own repo (cefrium/cefrium-browser). It defaults to
# the Maven registry; for a local build against your freshly built AAR use the
# 'local' source mode. dev-build.sh stages the fresh AARs in and builds it:
cd ../cefrium-browser
./dev-build.sh cefrium-browser        # or compose-browser

# Equivalent manual form:
cp out/Official_android_arm64/cefrium-sdk.aar \
  cefrium-browser/app/libs/
cd cefrium-browser
./gradlew assembleDebug -PcefriumSource=local --no-daemon

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 6. Run tests

```bash
cd cefrium-browser/cefrium-browser
./gradlew connectedAndroidTest -PcefriumSource=local  # requires connected device
```

## 7. Cut a release

A Cefrium release has three parts that must stay in sync:

1. **Maven artifacts** on the Codeberg registry -- the canonical distribution
   channel (what Gradle resolves).
2. **A formal Codeberg release** (a signed tag + notes) -- the parity/security
   record that [cefrium.com/releases](https://cefrium.com/releases/) links to.
3. **The cefrium.com/releases row** linking the two.

### 7.0 Recommended: one orchestrator

`cut-release.sh` runs the whole cut end-to-end so you don't drive the sub-scripts
by hand. The sections below (7.1-7.5) document what each phase does; reach for them
only to run a phase in isolation or debug a failure.

```bash
export JAVA_HOME="$PWD/third_party/jdk/current"   # from chromium/src
export ANDROID_HOME=$PWD/third_party/android_sdk/public
cef/cefrium_sdk/cut-release.sh <version> <prev-version>     # e.g. 0.4.0 0.3.2
```

Phases (resume after a long build with `--from <phase>`):
`patches build hotranges merge compose plugin stats sanity suite stage publish central flagship release provenance web prune play`
(the table in RELEASING-CEFRIUM.md says what each does).
- `build` -> `build-all-variants.sh all` (6 variants; runs `slim_aar.py` per variant)
- `merge` -> `merge-multiabi.sh` fuses arm64+x86_64+armeabi-v7a into `out/release/cefrium-sdk.aar`
  and `cefrium-sdk-libre.aar`
- `compose` builds the Compose AAR (auto-restores the gitignored gradle-wrapper.jar
  + `libs/` dir).
- `build` also runs a **GEN_JNI staleness gate** (`regen-gen-jni.sh --check`): fails
  if the checked-in GEN_JNI drifted from the build's generated one (the 0.4.0 root
  cause). `sanity` builds and launches every sample and both browsers on the
  device against the fresh AAR, and `suite` runs the **on-device instrumented
  suite** (a release must be runtime-validated on a connected device; compile
  checks alone let 0.4.0 ship a 149 crash). Then an interactive **gate** guards
  the IMMUTABLE `publish`.
- `publish` -> `publish-maven.sh <version>` (uploads + smoke-tests both coordinates).

A fresh out dir is used per the engine stamp, so an engine bump never reuses a
stale build dir (see "Engine bump" in RELEASING-CEFRIUM.md).

### 7.1 Build the artifacts publish-maven.sh uploads

1. **SDK variant AARs** in `out/release/` (`cefrium-sdk.aar`,
   `cefrium-sdk-libre.aar`), produced by `build-all-variants.sh` +
   `merge-multiabi.sh`.
2. **Compose AAR**:
   ```bash
   cd chromium/src/cef/cefrium_compose
   cp ../../out/Official_android_arm64/cefrium-sdk.aar libs/   # compileOnly
   ./gradlew assembleRelease --no-daemon
   # -> build/outputs/aar/cefrium-compose-release.aar
   ```
3. **UnifiedPush AAR**: the same steps in `cef/cefrium_unifiedpush`
   (`-> build/outputs/aar/cefrium-unifiedpush-release.aar`).
4. **Gradle Plugin JAR** -- `cef/cefrium_sdk/gradle-plugin/build/libs/gradle-plugin.jar`.

### 7.2 Publish to the Codeberg Maven registry

**Token:** Codeberg -> Settings -> Applications -> Generate Token (package scope),
then point `CEFRIUM_REGISTRY_TOKEN` at a file holding it.

```bash
cef/cefrium_sdk/build-all-variants.sh all codecs   # embeds V8 snapshot (multi-ABI ready)
cef/cefrium_sdk/merge-multiabi.sh codecs           # -> out/release/cefrium-sdk.aar (arm64+x64)
# (repeat the two lines with `libre` for cefrium-sdk-libre)
cef/cefrium_sdk/publish-maven.sh <version>
```

`publish-maven.sh` uploads `cefrium-sdk`, `cefrium-sdk-libre`, `cefrium-compose`,
`cefrium-unifiedpush`, `cefrium-gradle-plugin` and the plugin marker POM, each with a generated POM.

**CRITICAL -- raw PUT, never multipart.** The Codeberg Maven API expects the raw
file body on a `PUT` (`curl --upload-file`). Using `curl -F`/`--form` sends
`multipart/form-data`, which bakes the MIME envelope (boundary + headers) into
the stored file -- every POM/AAR/JAR then starts with `------...` instead of `PK`
/`<?xml` and **no consumer can resolve them**. This silently broke 0.3.0's first
publish (the local-AAR samples never caught it). `publish-maven.sh` now uses
`--upload-file` and runs `verify_artifact()` after every upload -- it re-fetches
the artifact and checks the magic bytes (`PK` for AAR/JAR, an XML element for
POMs), aborting the release on corruption.

**Versions are immutable** (registry returns `409`). To re-publish the *same*
version, delete it first:
```bash
TOKEN=$(cat "$CEFRIUM_REGISTRY_TOKEN")
for n in cefrium-sdk cefrium-sdk-libre cefrium-compose cefrium-unifiedpush cefrium-gradle-plugin com.cefrium.gradle.plugin; do
  curl -s -X DELETE -H "Authorization: token $TOKEN" \
    "https://codeberg.org/api/v1/packages/cefrium/maven/com.cefrium%3A$n/$VERSION"
done
```
Otherwise bump the version.

### 7.3 Gate -- prove it is consumable (DEP-8 style)

The samples, the instrumentation harness and the test APK all use a **local**
AAR (`implementation files('libs/...')`), so they never exercise the registry --
a broken publish is invisible to them. Before announcing, build a throwaway
consumer that resolves the plugin + SDK **from the registry**, like the
quickstart:

```bash
JAVA_HOME=<jdk-25> ANDROID_HOME=<android-sdk> \
GRADLE=/path/to/any/sample/gradlew \
  cef/cefrium_sdk/test-published-release.sh <version>
```

`publish-maven.sh` runs this automatically at the end (skip with
`CEFRIUM_SKIP_SMOKE_TEST=1` when Gradle/SDK are unavailable on the publishing
host). A non-zero exit means the release is **not consumable -- do not announce
it**.

### 7.4 Create the formal Codeberg release

Tag the exact commit the artifacts were built from, **signed and annotated**:

```bash
git tag -s v<version> <build-commit> -m "Cefrium <version>"   # -s = GPG-signed
git push codeberg v<version>
```

Then create the release (Codeberg -> Releases -> *New release*, or the API) with
notes following this template:

```
CEF branch 8078, Chromium 156.0.8078.88.

<one paragraph: what changed in this version>

Published to the Codeberg Maven registry on <YYYY-MM-DD>.

Includes Chromium security fixes through 156.0.8078.88.
```

- The **exact Chromium version** is `chrome/VERSION` in the checkout (the Debian
  overlay), **not** CEF's nominal `CHROMIUM_BUILD_COMPATIBILITY.txt` baseline.
- The repeated **"Includes Chromium security fixes through X"** line is what
  turns the releases table into a CVE-coverage record.

**Asset policy.** The Maven registry is the canonical channel. A Codeberg
release carries the auto-generated source archive plus, optionally, a
checksummed and GPG-signed demo APK + `SHA256SUMS` for verification/archival.
Do **not** attach the ~240 MB AARs by default -- they live on the registry. When
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
| All 6 variants, clean | see `tmp/cut-<version>.status` |

## Troubleshooting

**JDK version error:** 0.8.0+ requires JDK 25 (Gradle 9.7.1 / AGP 9.4); a
`cannot access CefriumBrowser` / "Unsupported class file major version 69" error
means the JDK is older than 25 and cannot read the SDK's Java-25 bytecode. (0.7.x
and earlier used JDK 21 / Gradle 8.9 with Android Studio's bundled JBR.)

**GN version:** Use `depot_tools/gn` (v2354+), NOT system `/usr/bin/gn`.

**OOM during link:** Reduce parallelism: `ninja -j4 -C out/...`

**Stale objects after patching:** Always touch patched files (step 2).
Without this, ninja won't recompile and you get subtle runtime bugs.
