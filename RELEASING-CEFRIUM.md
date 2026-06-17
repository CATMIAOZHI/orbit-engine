# Releasing Cefrium

Scope: this document is for CEFRIUM (CEF ported to native Android) and its SDK, not upstream CEF.

Runbook for cutting a Cefrium SDK release (the exact, verified sequence:
**build -> merge -> publish -> test**). The Codeberg Maven registry is
**immutable** - a version is uploaded once and never overwritten, so the local
sanity gate (step 7) exists to catch a broken artifact *before* it is published.

Versions follow `VERSIONING-CEFRIUM.md`: new public API => minor, fixes only => patch,
engine bumps noted in the `/releases` table regardless.

## Automated - `cut-release.sh`

The whole pipeline below is orchestrated by one script that stops for explicit
confirmation before the two irreversible steps (immutable publish, destructive
prune):

```bash
# Bump Cefrium.java VERSION first, then:
cef/cefrium_sdk/cut-release.sh <version> <prev-version> --notes "<releases-row HTML>"
# Resume after a long build:  --from publish   (phases: patches build merge
#   compose plugin stats sanity publish flagship release web prune)
```

It chains the per-step scripts (all committed alongside it):
`build-all-variants.sh` -> `merge-multiabi.sh` -> compose -> `update-patch-stats.sh`
-> local sanity -> **[gate]** `publish-maven.sh` (smoke-tests both coordinates) ->
flagship APK -> `release-codeberg.sh` (tag + GPG-signed SHA256SUMS + assets) ->
`release-web-update.sh` (releases row, quickstart bump, previous superseded) ->
**[gate]** `prune-old-versions.sh` (latest-only). Each gate requires typing the
version; `CEFRIUM_YES=1` bypasses them for CI. After it finishes, commit/push the
web + cef repos and regenerate Javadoc/Dokka if the API changed.

The manual sequence below is the reference for what each phase does (and for
running a phase by hand when needed).

## Engine bump - new Chromium / CEF (e.g. 148 -> 149)

A release *cut* recompiles the **same** engine. An engine *bump* moves the port
onto a **new** Chromium/CEF release - a different, harder operation: most patches
will conflict because Chromium changed the files, and Chromium API breaks need
fixing by hand. It is **not** fully automatable. The assistant
`cef/cefrium_sdk/engine-bump.sh` does the **mechanical scaffolding + a per-patch
conflict report**; it does not auto-resolve conflicts or fix API breaks.

```bash
cef/cefrium_sdk/engine-bump.sh <chromium-tag> <cef-branch> [--from <phase>]
#   e.g.  engine-bump.sh 149.0.7827.53 7827      # Debian-tracked Chromium 149
#   read-only audit of the current tree:  --from report
```

Phases: `preflight pin sync rebase apply report brand`.
- **preflight** - verify the cef tree is committed (patches are safe in git), record
  current vs target, confirm the tag is on the remote.
- **pin** - set the new version in the three sources of truth: `.gclient`
  (`@refs/tags/<tag>`), `cef/CHROMIUM_BUILD_COMPATIBILITY.txt`, and
  `Cefrium.CHROMIUM_VERSION`. Config-only, with `.bak` backups.
- **sync** *(gated, destructive)* - checkout the new Chromium tag + `gclient sync`.
  Resets `chromium/src`; the port's patches live in `cef/patch/` so nothing is
  lost - they get re-applied onto the new tree.
- **rebase** *(gated, guided)* - fetch the matching upstream CEF branch and rebase
  `android-port` onto it (core CEF patch conflicts are CEF's, resolved here).
- **apply** - run `patcher.py` on the new tree.
- **report** - per Android patch: `OK` (applied) / `PENDING` (applies clean) /
  `CONFLICT` (regenerate by the patch methodology - see `PATCHING-CEFRIUM.md`).
- **brand** - `regenerate-icons.sh` (the atomic number auto-follows the new
  `CHROMIUM_VERSION`; no argument needed).

Version target follows **Debian Chromium** for CVE parity (149 = `149.0.7827.53`,
installed on the workstation). After the bump: resolve every `CONFLICT` patch, fix
Chromium-version API breaks at build time, then cut a release with `cut-release.sh`.

> CRITICAL (build dirs after a bump): a BUILD-number bump leaves STALE Java build
> metadata (`build_config.json` + turbine graph) in any pre-existing `out/` dir,
> which produces a phantom Java turbine "dependency cycle" (`actor -> ... ->
> actor`) when building the AAR/APK. It survives `gn gen` and even
> `rm build.ninja` -- ONLY a fresh out dir clears it. `engine-bump.sh` wipes
> `out/*` after sync, and `build-all-variants.sh` / `apply_all_patches.sh`
> auto-recreate their out dir when the engine stamp changes, so the scripted path
> is safe. If you build MANUALLY in a reused dir and hit that cycle, do
> `rm -rf out/<dir> && depot_tools/gn gen out/<dir>` -- it is not a real cycle.

> Tip: `engine-bump.sh ... --from report` is a safe read-only audit you can run any
> time to see which Android patches are applied vs missing/conflicting on the
> current tree - it is how the 3 missing guard patches (accessibility/credman/
> webauthn) were found and re-applied.

### Bump scenarios + lessons from the Debian CEF package

The Debian CEF package (`<debian-dev>/chromium-embedded-framework`,
same maintainer, 2000+ builds) has a mature update methodology
(`CEF-UPDATE-GUIDE.md`, `debian/README.source`). Key transferable lessons:

- **CEF branch = the Chromium BUILD number** (3rd version component): 149.0.**7827**.53
  -> CEF branch **7827**. `engine-bump.sh` now auto-derives it from the tag.
- **Three scenarios - only rebase CEF when the BUILD number changes:**
  1. *Patch-level bump* (same build, e.g. 7827.53 -> 7827.99): same CEF branch,
     **no rebase, patches unchanged** - just re-pin the Chromium tag + sync + rebuild.
  2. *New build number* (7827 -> 7900): rebase `android-port` onto the new CEF
     branch; expect patch conflicts (regenerate).
  3. *New major* (148 -> 149): as (2) plus version-string churn (Cefrium.VERSION /
     brand mark). This bump (7778 -> 7827) is scenario 2/3.
- **Known-fuzz core patches:** Debian applies `gn_config` / `chrome_browser_context_menus`
  with `--fuzz=3` via a skip-list - they routinely "fail" but ARE applied. Our
  equivalent is `gn_config` / `chrome_runtime` / `chrome_browser_profiles` (the
  recurring "3 failed"). The report now flags them as known-fuzz and verifies by a
  signature line, not as conflicts.
- **RAM-aware parallelism (~3 GB/job):** `build-all-variants.sh` now auto-sets
  `CEFRIUM_NINJA_JOBS = min(cores, RAM_GB/3)` to avoid the OOM killer (Debian's
  `debian/rules` does the same).
- **CVE parity:** track the Debian Chromium version (find it with
  `apt-cache policy chromium`); that's the tag to pin.

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
#   apply-patches.py sets the illegal 'target_os=="android"' condition key (which
#   neither export nor env can) and runs patcher.py; a bare patcher.py would
#   silently skip every android/* patch.
python3 cef/cefrium_sdk/apply-patches.py
#   NOTE: gn_config / chrome_runtime / chrome_browser_profiles may report
#   "failed" - that is the benign already-applied quirk on CEF's largest
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

## 7. Local sanity gate - BEFORE the immutable publish

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
- **Immutable:** an existing artifact returns `409` and is skipped - never
  overwritten. To ship changed bytes, bump the version.
- It then runs the post-publish smoke test for **both** SDK coordinates if
  `JAVA_HOME`/`ANDROID_HOME` are set; if they are not, it **skips with a warning
  and still succeeds** (the uploads already happened - a missing build toolchain
  must not report a completed publish as failed). Run step 9 manually in that case.

## 9. Post-publish smoke test - DEP-8, BOTH coordinates

A throwaway consumer project that resolves the plugin + SDK **only from the
registry** and assembles. Run it for both coordinates - they are distinct
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
- Rebuild / relabel the flagship `cefrium-browser` APK on `<version>` - never a
  version-ambiguous binary link. The browser lives in its own repo
  (cefrium/cefrium-browser); `cut-release.sh` builds it in `local` source mode
  (`-PcefriumSource=local`) against the freshly built AAR.
- Remove any "releasing shortly" version notice from the guides now that the
  coordinate resolves.

## Provenance note

This runbook records the 0.3.2 cut (2026-06-08), which was executed and verified
end to end: 4 variants built clean, both multi-ABI AARs carry both ABIs, local
sanity green, all artifacts uploaded `201 + verified`, and the registry smoke
test passed for both `cefrium-sdk` and `cefrium-sdk-libre`.
