# Releasing Cefrium

Scope: this document is for CEFRIUM (CEF ported to native Android) and its SDK, not upstream CEF.

Versions follow `VERSIONING-CEFRIUM.md`: new public API => minor, fixes only =>
patch, engine bumps noted in the `/releases` table regardless.

## The process

One script runs all of it. The phases, in order, and what each one is for:

| # | Phase | What it does | Script |
|---|-------|--------------|--------|
| 1 | `patches` | Set `Cefrium.VERSION` **and commit it**, apply the CEF patches, touch their targets so ninja rebuilds | `apply-patches.py` |
| 2 | `build` | The 4 variants: codecs/libre x arm64/x86_64 (~6.5 h cold on 24c/62G) | `build-all-variants.sh` |
| 3 | `merge` | Fuse arm64 + x86_64 into the published fat AARs | `merge-multiabi.sh` |
| 4 | `compose` | The Compose wrapper AAR | (gradle, in `cefrium_compose`) |
| 5 | `plugin` | Check the Gradle plugin jar | - |
| 6 | `stats` | Derive every figure README.md, QUICKSTART-CEFRIUM.md, MIGRATION-CEFRIUM.md and BUILD-GUIDE-CEFRIUM.md state (patch counts, engine, SDK version, service slots, toolchain and JDK floors), commit them, then assert what was committed; also assert that every API name, Cefrium type, script path and doc link in those four and in FEATURES/CHANGELOG/SCRIPTS/RELEASING/PATCHING still resolves; also assert the changelog has this version's section as its newest entry | `update-doc-figures.py` |
| 7 | `sanity` | Build a REAL consumer against the fresh AAR, before anything immutable | - |
| 8 | `suite` | The on-device suite, read from the result files, not from Gradle's exit status | `run-instrumentation-suite.sh` |
| 9 | `publish` | Upload to the Codeberg registry, then DEP-8 smoke BOTH coordinates | `publish-maven.sh` |
| 10 | `central` | Upload to Maven Central, the channel adopters actually pin | `publish-central.sh` |
| 11 | `flagship` | Build the browser APK against this exact SDK | - |
| 12 | `release` | Codeberg release: tag, assets, GPG-signed `SHA256SUMS` | `release-codeberg.sh` |
| 13 | `web` | Releases row, quickstart, upstream page, Javadoc + Dokka, re-assert the repo docs, then VERIFY and push the cef repo | `release-web-update.sh`, `regen-docs.sh`, `release-web-verify.sh` |
| 14 | `prune` | Drop superseded versions from the latest-only Codeberg mirror | `prune-old-versions.sh` |
| 15 | `play` | Pin + version-bump the apps, rebuild every sample, upload to Play | `release-play.sh` |

The order of 13 and 14 is deliberate and was learned the hard way. `prune`
exits 1 when the release looks incomplete, and on the 0.10.2 cut "incomplete"
only meant Maven Central had not propagated yet, which took six minutes. That
exit used to happen before the push, so the source of a published release sat
unpushed: the tag was on Codeberg and the branch was seven commits behind.

Two things need a human and are not automated:

- **The gates.** Publishing is irreversible (Maven versions are immutable) and
  Play reaches users. Each gate asks you to type the version. `CEFRIUM_YES=1`
  pre-authorizes them, which an unattended run MUST set -- a background job has
  no stdin and dies at the first gate otherwise.
- **The hardware signing key**, for the GPG-signed `SHA256SUMS` in phase 11. Prime it in a
  terminal that can show a pinentry prompt before starting.

Everything else, including the pushes, is scripted. The index of every script
is `SCRIPTS-CEFRIUM.md`.

## Running it

The whole pipeline below is orchestrated by one script that stops for explicit
confirmation before the two irreversible steps (immutable publish, destructive
prune):

```bash
# The cut sets Cefrium.VERSION itself, and commits it:
cef/cefrium_sdk/cut-release.sh <version> <prev-version> --notes "<releases-row HTML>"
# Resume after a long build:  --from publish   (phases: patches build merge
#   compose plugin stats sanity suite publish central flagship release
#   provenance web prune play)
```

Useful flags: `CEFRIUM_INCREMENTAL=1` reuses the out dirs (note the `patches`
phase touches every patched file by design, so this saves less than it looks);
`CEFRIUM_NO_PRUNE=1` and `CEFRIUM_NO_PLAY=1` skip those phases;
`CEFRIUM_PLAY_TRACK` picks the Play track.

**The `play` phase ships the apps, and it runs last for a reason.** The browser
apps resolve the SDK from the registry, so uploading before the coordinate is
served would put an app built against the PREVIOUS engine in front of users --
browser 0.1.18 shipped Chromium 149 exactly that way, from a `cefriumVersion`
left pinned at 0.5.9. `release-play.sh <version> [track]` therefore does the
whole mechanical chain itself: pin `cefriumVersion` in both app modules, bump
`versionCode` (+1, which Play rejects only after a full upload if you forget)
and `versionName`, rebuild EVERY sample against the new fat AAR as an API gate
-- a sample that stops compiling means we broke the public API and the release
must stop -- and only then build the signed AABs and upload them. Track defaults
to `internal`; set `CEFRIUM_PLAY_TRACK`. `CEFRIUM_NO_PLAY=1` skips the phase for
a cut not meant to reach users. What stays manual, because it is judgement and
not mechanics: the first AAB of a new app (Play requires it by hand), the
listing copy, staged-rollout percentages, and the 12-testers-for-14-days closed
test that gates production access. See `PLAY-PUBLIC-TRACK-RUNBOOK.md`.

**Maven Central is THE channel adopters consume -- not a migration target.** This
was mis-framed here as something aspirational, and the consequence was concrete:
Central publishing stayed a separate manual step, stopped being run after 0.8.9
(2026-09-18), and 0.9.0 through 0.9.3 went to Codeberg only. Every 0.9.x is an
HTTP 404 on Central, so no external adopter received those four releases -- and
because bug reports went quiet three days later, the silence read as new-found
stability. Treat "published" as meaning Central.

The Codeberg registry is a **latest-only mirror**: we prune it because it is a
quota-limited donated host, and that pruning is what 404'd our first external
adopter's pinned version. It cannot be pinned, which is exactly what we tell
integrators, so it is not a delivery channel on its own.

`cut-release.sh` now has a **`central` phase** between `publish` and `flagship`,
so it can no longer be forgotten: it fails the cut when `CEFRIUM_CENTRAL_TOKEN`
is unset or its file is missing, and only skips on a deliberate
`CEFRIUM_SKIP_CENTRAL=1`. Note the phase
needs `cefrium-sdk-libre.aar`, so a single-variant cut cannot reach Central at
all -- another reason releases ship the full four variants.

`publish-central.sh` ships one signed bundle per coordinate (each SDK variant,
compose, plugin + marker), defaults to USER_MANAGED (validates but does not
release until you press Publish in the Portal), and supports `--dry-run`
(assemble + validate locally, no network) and `--publish` (release, immutable).
One-time setup is in the script header: verify the `com.cefrium` namespace via a
cefrium.com DNS TXT record, put a Portal user token in the file
`CEFRIUM_CENTRAL_TOKEN` names, and
send the signing key's public half to keys.openpgp.org. Since Central keeps every
version permanently, the Codeberg prune gate stays optional housekeeping, and
QUICKSTART's "Version availability" note should say all Central versions are
permanent.

**Reference apps are a release gate, not an afterthought.** The two reference
apps (`cefrium-browser/` View API + `compose-browser/` Compose API) are the
canonical, living migration example an external adopter reads to learn the
current toolchain -- they were what the first external adopter (TE2) used to
derive the AGP 9 setup. Before publishing a release (especially across an engine
or toolchain bump), both MUST build and run on the new toolchain against the
about-to-be-published SDK; the `flagship` phase builds+runs the flagship on
device, but confirm `compose-browser` builds too so neither reference app ships
stale. A reference app that does not build on the release's own toolchain is a
release blocker.

To run a single phase by hand, use its script from the table above, or the
per-task index in `SCRIPTS-CEFRIUM.md`. This document used to carry a parallel
hand-run copy of the whole sequence; it was removed because it had drifted --
it still told you to export the JDK 21 that stopped working at 0.8.0, and to
spot-check a "signature line" to decide whether a patch applied, a test that
reports applied patches as failures. Two copies of a procedure means one of
them is wrong and you cannot tell which.

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
  `Cefrium.CHROMIUM_VERSION`. Config-only, with `.bak` backups. The two tracked
  files are committed here as `Pin Chromium <tag> (CEF branch <br>)`; `.gclient`
  is local config and stays out of git. Only those two paths are staged, so
  in-flight work elsewhere is untouched.
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

The Debian CEF package (same maintainer, 2000+ builds) has a mature update
methodology
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

### Cross-branch rebase mechanics (learned doing 149->150->151->152)

A new BUILD number (scenario 2/3) means rebasing the ~370-commit `android-port`
onto the new CEF branch. What the terse "rebase" phase actually takes:

- **Rebase base = `origin/<oldbranch>`, NOT the script's 7778 merge-base.** Replay
  ONLY our commits:
  `git branch -f android-port-<new> android-port && git checkout android-port-<new>`
  then `git rebase --onto origin/<new> $(git merge-base origin/<old> android-port) android-port-<new>`.
- **Resolution strategy (drive it, don't hand-resolve 370 commits).** Per conflicted
  file: docs (`*.md`/README/LICENSE...) -> `--theirs`; **pure-port file** (does NOT
  exist in `origin/<new>` -- our SDK/JNI/tooling; test with
  `git cat-file -e origin/<new>:<file>`) -> `--theirs` (our commit is the forward
  state; upstream never touched it); **shared file** (exists in `origin/<new>`) ->
  auto-resolve only when ONE conflict side is empty (keep the non-empty side), else
  STOP and merge by hand (BUILD.gn, patch.cfg, chrome/content/base source). Skip
  commits that go empty. `git clean -fdxq` before each step (untracked build residue
  blocks the next checkout).
- **VERIFY the rebase, always.** `git diff android-port android-port-<new> --
  cefrium_sdk/ cefrium_compose/ libcef/browser/android/` MUST be ~empty (only
  `GEN_JNI.java`). Any other diff is a mis-resolution -> restore that file from
  `android-port` and commit a fixup. (This caught a dropped
  `SdkLoadHandler::OnLoadingStateChange` in the 150->151 bump.)
- **GIT-CLEAN GOTCHA:** the `rebase` phase runs `git clean -fdq` in `cef/`, which
  DELETES untracked files. **Commit any new source (e.g. a new instrumented test)
  BEFORE a sync/bump**, or it is gone.
- After a clean rebase: fast-forward `android-port` to it, re-pin
  `Cefrium.CHROMIUM_VERSION` (the pin is discarded before the rebase), then
  `engine-bump.sh <tag> <branch> --from apply`.

### Per-engine API breaks + gotchas (fix at build/gate time)

- **Chromium API breaks** are found by compiling; fix them, they're usually few.
  150->151 was 1 GN (`android_apk` dropped `strip_resource_names`/
  `short_resource_paths`) + 5 Java (`OverscrollRefreshHandler.release(boolean)` ->
  `release(int @OverscrollActivationStatus)`; `MediaSession.suspend()/resume()` ->
  `suspend/resume(SuspendType)`). Zero C++ breaks. Same-branch (.71->.137) was 0
  breaks; only `resource_bundle_2512` needed a fuzz/offset regen
  (`patch -p0 --fuzz=3` then `git diff --no-prefix > patch`, normalize).
  151->152 was 3 C++ breaks, all upstream-correct guards/adaptations:
  (a) `ui::Layer::SetShowSurface` color arg `SkColor` -> `SkColor4f` (wrap our OSR
  Android call in `SkColor4f::FromColor(...)`; same SkColor->SkColor4f migration hit
  `SetColor` in the rebased OSR conflict); (b) `autofill::AutofillClient` added pure
  virtual `UsesPlatformAutofill() const` -> override in `CefAutofillClientAndroid`
  returning `false` (CEF uses Chrome's built-in autofill, like ChromeAutofillClient,
  not Android's platform autofill = `true`); (c) upstream added test helpers using
  desktop-only `ChromeBrowserHostImpl`/`BrowserView`/Views to `test_helpers_impl.cc`
  -- guard the desktop-only includes + both function bodies with
  `#if !BUILDFLAG(IS_ANDROID)` and Android stubs (`browser.h`/`tab_strip_model.h`
  have been `#error`-desktop-only since 151; `chrome_browser_host_impl.cc` is already
  `sources -=`'d out of the Android build, so only new INCLUDERS of its header break).
  Also drop patches upstream merged into Chromium: 152 absorbed `tools_metrics_27f8690`
  (Chromium's own `tools/metrics/BUILD.gn` gained the non-git-checkout guard) -> its
  patcher run reported "patch file does not exist"; remove the orphaned `patch.cfg`
  entry (the .patch file was already dropped by the rebase following upstream).
- **New security features flipped ON by default with no CEF UI.** Each engine can
  enable a feature CEF has no host UI to satisfy, which then hangs/breaks. 151 turned
  on Local Network Access checks for WebSockets by default -> a page connecting to a
  local/private-address WebSocket stalled waiting for an LNA permission decision
  (cef-android issue #1). Fix pattern: `DisableFeatureByDefault(<feature>,
  disable_features)` in `libcef/common/chrome/chrome_main_delegate_cef.cc`, matching
  what Android WebView disables (`android_webview/browser/aw_field_trials.cc`). Add a
  hermetic instrumented regression guard so the gate catches a re-break.
- **AAR resource drift.** 151 moved Material/AppCompat strings out of the AAR's base
  `values/` into locale-only `values-XX`; a Maven consumer then fails
  `processDebugResources` ("resource string/... not found"). Backfill the missing
  base strings into `cefrium_sdk/res/values/compat_strings.xml`. Compute the exact
  set: `@string/` refs in the built AAR's `res/layout*/` MINUS strings defined in its
  base `res/values/`. The `sanity` gate (push-demo build) catches this pre-publish.
- **GEN_JNI drift gate** blocks the cut whenever native-method count changed: run
  `cef/cefrium_sdk/regen-gen-jni.sh out/<dir>` and rebuild the AAR, then commit the
  regenerated `GEN_JNI.java`.
- **GPG-sign gotcha:** commits are `commit.gpgsign=true` (hardware signing key). When the PIN
  cache is cold `git commit` HANGS. Prime it with `your key-priming script`, or commit
  `--no-gpg-sign` and re-sign later:
  `git rebase --exec 'git commit --amend --no-edit -S' <last-signed>` +
  `git push --force-with-lease`.
- **CRITICAL -- gpgsign SILENTLY CORRUPTS the cross-branch rebase.** With a cold PIN,
  every `git rebase --continue` fails at "gpg failed to sign ... signing failed:
  Timeout" -> the pick does NOT advance and leaves NO conflict, so the auto-driver's
  "not-advanced + no unmerged = empty commit -> `git rebase --skip`" heuristic DROPS
  the real commit and its files (whole Android commits vanished; `origin/<new>..HEAD`
  stayed empty while 5+ picks "done"). Symptom: pure-port dirs go MISSING mid-rebase.
  Fix: before starting the rebase, `git -C cef config --local commit.gpgsign false`
  (repo-local override, global stays true), run the whole rebase+driver unsigned, then
  re-sign the range and unset the local flag. Abort + restart if you only notice after
  files disappear. This is separate from the plain "commit hangs" gotcha above.
- **web `brand`/version:** the `brand` phase bumps the logo atomic number (auto-follows
  `CHROMIUM_VERSION`). Also update the site's CURRENT-engine references (landing
  "currently ..." line, the comparison-table "Engine" row) -- but LEAVE historical
  `/releases` cards and benchmark "measured on X" data (changing those falsifies
  history / measured numbers).

## Prerequisites

```bash
# JDK 25 -- Chromium's bundled Temurin. NOT the Android Studio JBR 21: since
# SDK 0.8.0 the AAR ships Java-25 (class-69) bytecode that javac 21 cannot read.
cd <your-checkout>/chromium/src
export JAVA_HOME="$PWD/third_party/jdk/current"
export ANDROID_HOME="$PWD/third_party/android_sdk/public"
export PATH="$PWD/../../depot_tools:$PATH"   # gn + ninja
```

Also needed: `CEFRIUM_REGISTRY_TOKEN` and `CEFRIUM_CENTRAL_TOKEN`, each naming
a file that holds the corresponding token (read by the scripts, never echoed;
the paths live in your local setup notes, deliberately not here), the Maven Central credentials for the
`central` phase, the Play service-account JSON at
`cefrium-browser/secrets/play-sa.json` for the `play` phase, and a primed
hardware signing key for the signed `SHA256SUMS`.

## Facts that bite

These are the ones that have actually cost a release:

- **Maven versions are immutable.** An existing artifact returns `409` and is
  skipped, never overwritten. To ship changed bytes you bump the version --
  there is no other remedy, which is why the `sanity` phase builds a real
  consumer against the fresh AAR BEFORE anything is uploaded.
- **"Published" means Maven Central.** Codeberg is a latest-only mirror that
  gets pruned and cannot be pinned. Central publishing used to be a separate
  manual step, it stopped being run after 0.8.9, and every 0.9.x was a 404 for
  external adopters for three weeks. It is a phase now; do not skip it.
- **Publish all four variants.** x86_64 lives INSIDE the multi-ABI AAR, so an
  arm64-only cut gives emulator users an `UnsatisfiedLinkError` at runtime, and
  a codecs-only cut publishes no `-libre` coordinate at all. 0.9.3 shipped that
  way and cost us developers. `cut-release.sh` now refuses a partial matrix at
  the publish phase.
- **Smoke BOTH coordinates after publishing.** They are distinct artifacts and
  the FOSS audience goes straight to `-libre`. `publish-maven.sh` runs the DEP-8
  test itself when the toolchain is present; if `JAVA_HOME`/`ANDROID_HOME` are
  missing it warns, skips, and still succeeds -- the upload did happen, and a
  missing toolchain must not report a completed publish as failed. Run it by
  hand in that case:

  ```bash
  export GRADLE=../../cefrium-sample/push-demo/gradlew   # any real gradlew
  for art in cefrium-sdk cefrium-sdk-libre; do
    ARTIFACT="$art" cef/cefrium_sdk/test-published-release.sh <version>
  done
  ```

- **A pin that is not committed reaches nobody.** The cut rewrites tracked
  files -- `Cefrium.VERSION`, the changelog, the README patch counts, the
  consumer docs -- and `push.sh` moves commits, not working trees. 0.10.1
  published with `Cefrium.VERSION` still reading 0.10.0 in git: the cut's final
  push printed `uncommitted changes -- these are NOT pushed` and that note is
  easy to read past. The tag is annotated at HEAD and Maven is immutable, so
  neither could be corrected afterwards. The cut now commits each pin in the
  phase that writes it, and `assert_pins_committed` stops it before the
  immutable publish and before the tag if any pinned path is still dirty. The
  pinned set lives in one place, `PINNED` in `cut-release.sh` -- extend it the
  moment a phase starts rewriting another tracked file. The same applies to an
  engine bump: `engine-bump.sh` commits `CHROMIUM_BUILD_COMPATIBILITY.txt` and
  `Cefrium.CHROMIUM_VERSION` in its `pin` phase.
- **A phase that "ran" is not a phase that worked.** Both doc generators fail
  silently, so `regen-docs.sh` asserts every public symbol has a page, and
  `release-web-verify.sh` asserts the site actually shows the new version and
  that no referenced asset is missing. The published Javadoc once sat at 0.9.2
  while 0.9.4 was the release, because nothing checked.
- **After the cut:** remove any "releasing shortly" notice from the guides now
  that the coordinate resolves.

## Provenance note

This runbook records the 0.3.2 cut (2026-06-08), which was executed and verified
end to end: 4 variants built clean, both multi-ABI AARs carry both ABIs, local
sanity green, all artifacts uploaded `201 + verified`, and the registry smoke
test passed for both `cefrium-sdk` and `cefrium-sdk-libre`.
