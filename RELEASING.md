# Releasing Cefrium

Runbook for cutting a Cefrium SDK release (the exact, verified sequence:
**build → merge → publish → test**). The Codeberg Maven registry is
**immutable** — a version is uploaded once and never overwritten, so the local
sanity gate (step 7) exists to catch a broken artifact *before* it is published.

Versions follow `VERSIONING.md`: new public API ⇒ minor, fixes only ⇒ patch,
engine bumps noted in the `/releases` table regardless.

## 0. Environment

```bash
export JAVA_HOME=<dev>/android/android-studio/android-studio/jbr   # JDK 21
export ANDROID_HOME=<cef-dev>/chromium/src/third_party/android_sdk/public
export PATH="<cef-dev>/depot_tools:$PATH"               # gn + ninja
# Publish token at ~/.registry-token (package scope); read by the script, never echoed.
```

## 1. Pre-flight

```bash
cd <cef-dev>/chromium/src

# (a) Bump the version string.
#   cef/cefrium_sdk/java/src/com/cefrium/Cefrium.java -> VERSION = "<version>"

# (b) Confirm the version is NOT already published (immutable; expect HTTP 404).
curl -s -o /dev/null -w '%{http_code}\n' \
  "https://codeberg.org/api/packages/cefrium/maven/com/cefrium/cefrium-sdk/<version>/cefrium-sdk-<version>.aar"

# (c) Apply the CEF patches and force-recompile the deltas.
export 'target_os=="android"'=1
python3 cef/tools/patcher.py
#   NOTE: gn_config / chrome_runtime / chrome_browser_profiles may report
#   "failed" — that is the benign already-applied quirk on CEF's largest
#   patches, NOT a broken tree. Spot-check a signature line from each if unsure
#   (e.g. grep 'IsUniqueForCEF' chrome/browser/profiles/off_the_record_profile_impl.cc).
# Touch the Android-patch targets + edited libcef sources so ninja rebuilds them:
for p in cef/patch/patches/android/*.patch; do
  for f in $(grep '^+++ ' "$p" | grep -v /dev/null | sed 's/^+++ [ab]\///;s/\t.*//'); do
    [ -f "$f" ] && touch "$f"; done; done
touch cef/libcef/browser/android/cefrium_sdk_jni.cc \
      cef/libcef/common/chrome/chrome_main_delegate_cef.cc
```

## 2. Build the 4 variants

```bash
CEFRIUM_NINJA_JOBS=16 cef/cefrium_sdk/build-all-variants.sh all
# -> out/release/cefrium-sdk-{arm64,x64}-{codecs,libre}.aar  (~6.5 h cold, on 24c/62G)
```

## 3. Merge multi-ABI

```bash
cef/cefrium_sdk/merge-multiabi.sh codecs   # -> out/release/cefrium-sdk.aar
cef/cefrium_sdk/merge-multiabi.sh libre    # -> out/release/cefrium-sdk-libre.aar
# Verify each carries BOTH jni/arm64-v8a/libcef.so and jni/x86_64/libcef.so:
unzip -l out/release/cefrium-sdk.aar | grep 'jni/.*/libcef.so'
```

## 4. Compose AAR

```bash
cp out/release/cefrium-sdk.aar cef/cefrium_compose/libs/
( cd cef/cefrium_compose && ./gradlew assembleRelease --no-daemon )
# -> cef/cefrium_compose/build/outputs/aar/cefrium-compose-release.aar
```

## 5. Gradle plugin jar

Ensure `cef/cefrium_sdk/gradle-plugin/build/libs/gradle-plugin.jar` is current
(its compiled `CHROMIUM_R_PACKAGES` must include every package whose `R` class
Chromium code reads at runtime). Rebuild if the source is newer; verify:

```bash
unzip -p cef/cefrium_sdk/gradle-plugin/build/libs/gradle-plugin.jar \
  'com/cefrium/CefriumPlugin*.class' | strings | grep -E 'gms.common|notifications'
```

## 6. Refresh the derived "auditable" numbers

```bash
cef/cefrium_sdk/update-patch-stats.sh        # rewrites patch count/lines on landing + README
# (idempotent; --check exits non-zero if stale, for CI)
```

## 7. Local sanity gate — BEFORE the immutable publish

Build a real consumer against the freshly merged AAR. Green here means the
artifact is consumable and the new APIs resolve; only then publish.

```bash
cp out/release/cefrium-sdk.aar ../../cefrium-sample/push-demo/app/libs/cefrium-sdk.aar
( cd ../../cefrium-sample/push-demo && ./gradlew clean assembleDebug --no-daemon )
```

## 8. Publish (immutable)

```bash
cef/cefrium_sdk/publish-maven.sh <version>
```

- Uploads `cefrium-sdk`, `cefrium-sdk-libre`, `cefrium-compose`,
  `cefrium-gradle-plugin` (+ POMs + the plugin marker POM), verifying each
  upload's integrity.
- **Immutable:** an existing artifact returns `409` and is skipped — never
  overwritten. To ship changed bytes, bump the version.
- It then runs the post-publish smoke test for **both** SDK coordinates if
  `JAVA_HOME`/`ANDROID_HOME` are set; if they are not, it **skips with a warning
  and still succeeds** (the uploads already happened — a missing build toolchain
  must not report a completed publish as failed). Run step 9 manually in that case.

## 9. Post-publish smoke test — DEP-8, BOTH coordinates

A throwaway consumer project that resolves the plugin + SDK **only from the
registry** and assembles. Run it for both coordinates — they are distinct
artifacts and the FOSS/no-telemetry audience goes straight to `-libre`.

```bash
# GRADLE must point at a real gradlew (no system gradle); any project's works.
export GRADLE=../../cefrium-sample/push-demo/gradlew
for art in cefrium-sdk cefrium-sdk-libre; do
  ARTIFACT="$art" cef/cefrium_sdk/test-published-release.sh <version>
done
# Both must print "PASS: <artifact> <version> resolves and assembles ...".
```

## 10. Release page, assets, flagship APK

- `/releases` (cefrium-web): add the `<version>` row; confirm every link resolves.
- Attach the merged AARs to the Codeberg release with a `SHA256SUMS`,
  **GPG-signed** (hardware signing key); document verification on `/releases`.
- Rebuild / relabel the flagship `cefrium-browser` APK on `<version>` — never a
  version-ambiguous binary link.
- Remove any "releasing shortly" version notice from the guides now that the
  coordinate resolves.

## Provenance note

This runbook records the 0.3.2 cut (2026-06-08), which was executed and verified
end to end: 4 variants built clean, both multi-ABI AARs carry both ABIs, local
sanity green, all artifacts uploaded `201 + verified`, and the registry smoke
test passed for both `cefrium-sdk` and `cefrium-sdk-libre`.
