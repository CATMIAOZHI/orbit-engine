# Scripts -- one command per task

Every routine operation in this project has exactly ONE script. This page maps
the task to the command so nothing has to be reconstructed from memory, from
chat history, or by asking anybody. If something here is wrong, fix the script
and this line -- do not work around it by hand, because a hand-run step is how
the same job ends up done differently twice.

All paths are relative to `chromium/src` unless stated. Set these first; every
Gradle step needs them (JDK 25 since SDK 0.8.0 -- older javac cannot read the
class-69 bytecode the AAR ships):

```bash
cd <your-checkout>/chromium/src
export JAVA_HOME="$PWD/third_party/jdk/current"
export ANDROID_HOME="$PWD/third_party/android_sdk/public"
```

## Push to Codeberg

```bash
cef/cefrium_sdk/push.sh <cef|sample|browser|web> [--force] [--dry-run]
```

One repo per run. It verifies the directory, the `codeberg` remote and the
branch before doing anything, repairs a shallow clone (Codeberg rejects those
with `shallow update not allowed`), **scans the tree for machine-specific
content and refuses to push if it finds any**, refuses a diverged push unless
you pass `--force`, pushes a timestamped backup branch at the current remote
head before any forced update, and verifies the remote afterwards instead of
trusting the exit code. `--dry-run` prints what would happen and pushes nothing.

## Keep this machine out of the published repos

```bash
cef/cefrium_sdk/check-no-local-leaks.sh [--staged|--install-hook] [--require-private]
```

`push.sh` runs it with `--require-private` on whichever repo it is pushing, and
`--install-hook` wires it in as `pre-commit`. It flags absolute `/home/<user>/`
paths, credential filenames, SSH keys, private keys, and tracked `*.pyc` --
those last were the find no review would have made, 13 `__pycache__` blobs with
the build path embedded in binary.

The needles that would themselves name something private -- the signing
hardware's vendor, the real credential filenames, the host and account aliases,
this machine's home path -- are **not in the script**, because the script is
published. They come from a local-only list, by default `LOCAL-LEAK-PATTERNS`
beside the checkouts, or wherever `CEFRIUM_LEAK_PATTERNS` points.
`--require-private` fails if that list is missing, so a run that only checked
the generic classes can never be mistaken for a full one. The script is exempt
from its own generic patterns and from nothing else: the blanket
self-exemption it started with is exactly what let it sit in a public repo
with a vendor name in cleartext.

Do not push any other directory. In particular `upstream-patches/` is a
local-only archive and must never be given a remote; back it up with
`upstream-patches/archive.sh` instead.

## Release

```bash
cef/cefrium_sdk/cut-release.sh <version> <prev-version>      # the whole thing
cef/cefrium_sdk/cut-release.sh <version> <prev> --from publish   # resume
```

Outcome: last line of `tmp/cut-<version>.status`. The cut checks the signing
key, the VPN and `JAVA_HOME` before it builds.

Phases: `patches build hotranges merge compose plugin stats sanity suite publish central
flagship release provenance web prune play`. Flags that matter:
`CEFRIUM_INCREMENTAL=1` (reuse out dirs), `CEFRIUM_YES=1` (pre-authorize the
irreversible gates -- needed for an unattended run), `CEFRIUM_NO_PRUNE=1`,
`CEFRIUM_NO_PLAY=1`, `CEFRIUM_PLAY_TRACK=internal`. Details and the reasoning
behind the phase order: `RELEASING-CEFRIUM.md`.

The cut commits the tracked files it rewrites, each in the phase that writes it:
`Cefrium.VERSION` and the changelog in `patches`, the derived README figures in
`stats`, the consumer docs in `web`. It then refuses to publish to Maven or to
tag the release while any of them is still dirty. Do not bump
`Cefrium.VERSION` by hand before a cut, and if the cut stops on a pinned source,
commit it under its own subject and resume with `--from <phase>`.

Individual phases, runnable on their own:

| Task | Command |
|---|---|
| Build the 4 variants | `cef/cefrium_sdk/build-all-variants.sh [--clean]` |
| Slim one raw AAR | `python3 cef/cefrium_sdk/slim_aar.py --input <raw.aar> --output <slim.aar>` |
| Fuse arm64 + x86_64 | `cef/cefrium_sdk/merge-multiabi.sh` |
| Publish to Codeberg Maven | `cef/cefrium_sdk/publish-maven.sh <version>` |
| Publish to Maven Central | `cef/cefrium_sdk/publish-central.sh <version>` |
| Codeberg release + GPG sums | `cef/cefrium_sdk/release-codeberg.sh <version> <apk>` |
| Central serves the signed bytes | `cef/cefrium_sdk/verify-central-provenance.sh <version>` |
| Derive the repo-doc figures | `cef/cefrium_sdk/update-doc-figures.py [--check]` |
| Find a figure that has no owner | `cef/cefrium_sdk/sweep-doc-figures.py` |
| Update cefrium.com + docs | `cef/cefrium_sdk/release-web-update.sh <version> <prev>` |
| Verify cefrium.com matches | `cef/cefrium_sdk/release-web-verify.sh <version> [prev]` |
| Google Play (apps) | `cef/cefrium_sdk/release-play.sh <version> [track]` |
| Drop superseded versions | `cef/cefrium_sdk/prune-old-versions.sh <version> --yes` |

`update-doc-figures.py` is the only writer of the figures in `cef/README.md`,
`cef/QUICKSTART-CEFRIUM.md`, `cef/MIGRATION-CEFRIUM.md` and
`cef/BUILD-GUIDE-CEFRIUM.md`. Each is derived from the file that defines it:

| Figure | Derived from |
|---|---|
| Patch count (5 sentences) | `patch/patches/android/*.patch` |
| Engine tag, Chromium milestone, CEF branch | `Cefrium.CHROMIUM_VERSION` (cross-checked against `CHROMIUM_BUILD_COMPATIBILITY.txt`) |
| SDK version in every coordinate, plugin block, URL and file name | `Cefrium.VERSION` |
| Render-process slots (`NUM_SANDBOXED_SERVICES`, privileged) | `cefrium_sdk/AndroidManifest.xml`, cross-checked against the services it ships |
| `compileSdk` / `minSdk` / `targetSdk` | `cefrium_compose/build.gradle` |
| AGP and Gradle floors | the same file and its `gradle-wrapper.properties` |
| Gradle-plugin Java target | `cefrium_sdk/gradle-plugin/build.gradle` |
| JDK floor (a dozen statements) | PROPAGATED from the quickstart's "JDK N is a hard floor" sentence -- Chromium's bundled JDK sets it and a bare clone has no Chromium tree, so there is nothing to derive it from |
| Class-file major | the JDK floor + 44, fixed by the JVM spec |
| README badge row (SDK, Chromium, patches, min API) | the same sources -- the value lives inside the shields.io URL and is spliced there like any other figure |
| The Chromium milestone inside `assets/cefrium-logo.svg` | `Cefrium.CHROMIUM_VERSION`. The brand logo is a periodic-table tile whose atomic number is the Chromium milestone, so it is a figure, not decoration. cefrium-web holds its own copy, owned by `release-web-update.sh`: one owner per file, each next to the repo it serves |

`--check` asserts all of them and runs in three places: CI, the `stats` phase
AFTER its commit, and the `web` phase before the site is published. It is also
the first thing `engine-bump.sh`'s `pin` does after writing the new engine.

A figure whose sentence stops matching FAILS the script. The version it replaced
warned and carried on, which is how four README sentences said 21 patches
against 34 on disk, the engine line sat six Chromium milestones behind under
"Security updates", and the quickstart told the reader they would see "full
Chromium 152" six milestones later. It also replaces `release-bump-docs.sh`,
which was given the PREVIOUS version and only failed when that exact version
survived -- a coordinate carrying some third version passed it.

Only the capture group inside a sentence is rewritten, so the prose around a
figure is never touched and one anchor can cover several artifacts
(`cefrium-sdk:X` and `cefrium-compose:X`) against one derived value.

**To find a figure the script cannot see, sweep by SHAPE, not by value:**
`cef/cefrium_sdk/sweep-doc-figures.py`. A missing anchor reports nothing at all
-- not `STALE`, not `MISSING` -- so reading `--check` proves nothing about what
is absent from the table. The sweep asks the owner which character spans its
anchors cover, then classifies every version-shaped token outside them:

| Bucket | Meaning |
|---|---|
| `TIME BOMB` | equals today's derived value with no anchor: correct now, wrong after the next bump. **Act on these.** |
| `STALE?` | differs from today's value with no historical marker. Read each; most are "since 0.5.0" markers and records of past incidents. |
| `SOURCE` | the JDK floor's own sentence, held by hand on purpose |
| `history` | a record or an illustration, correct as written. Counted only. |

It discovers the docs by globbing rather than from a list, because a doc added
to the repo and forgotten by `update-doc-figures.py` is otherwise invisible to
BOTH tools -- which is how `VERSIONING-CEFRIUM.md` turned out to be unchecked
while the changelog pointed readers at it.

It is **not a gate**, deliberately: around forty hits are legitimate history
whose phrasing no keyword list will enumerate, and a check whose exception list
outgrows its findings is the thing this script exists to abolish. Run it when
adding a figure to a doc, and after an engine bump.

Two real defects so far: `git format-patch origin/7871..android-port` in the
README contributing section, six milestones behind, after four passes over this
file had read past it; and the release runbook's own Prerequisites telling the
maintainer to export a JDK whose version was typed by hand. Searching for the
CURRENT values finds only the correct-but-unanchored ones, so the shape sweep is
what finds the stale ones.

The same run also ASSERTS, in those four files and in `FEATURES-CEFRIUM.md`,
`SCRIPTS-CEFRIUM.md`, `RELEASING-CEFRIUM.md` and `PATCHING-CEFRIUM.md`, the
references a reader follows:

- every `Cefrium.`, `CefriumBrowser.`, `browser.` and `state.` name in the prose
  must still be declared in `cefrium_sdk/java/src/com/cefrium/` or
  `cefrium_compose/java/`;
- every `Cefrium*` type and `rememberCefrium*` composable the prose names must
  be declared somewhere in the repo's own sources. These carry no dot, so the
  receiver scan above cannot see them, and every Compose entry point the guides
  tell a reader to call -- `CefriumWebView`, `CefriumView`,
  `rememberCefriumViewState`, `rememberCefriumNavigator`,
  `rememberCefriumBrowserPool` -- went unchecked until this was added;
- every `cef/...` script path the prose tells a reader to RUN must exist. This
  is the build guide's own rot class -- what a reader types there is a path, not
  a symbol -- and this file is the index of every script, so it is where a
  deleted one lingers. Three were deleted in one afternoon with nothing
  checking the docs. `./dev-build.sh` and `./gradlew` are not checked: they
  live in other repos;
- every link to another document in the repo must resolve, including its
  `#heading` anchor.

`CHANGELOG-CEFRIUM.md` and `PATCHING-CEFRIUM.md` own no figure either, and the
reason is worth stating because it looks like an omission. The changelog holds
**49 distinct SDK versions and 10 distinct engine tags**: "Engine: Chromium
154.0.8037.98 / CEF branch 8037" under `[0.9.4]` states what 0.9.4 shipped, so
deriving it would rewrite the past, and the release notes and the /releases row
are generated from these sections. The patching guide's versions are teaching
examples, marked `e.g.`: a same-branch respin needs TWO tags on one branch
(`149.0.7827.53 -> .102`) and a milestone bump needs two branches
(`7778 -> 7827`), so replacing either with today's single engine destroys the
thing being illustrated. Its `20 android patches silently failed` is a past
incident, and `android/my_new_guard` is a tutorial placeholder.

So the changelog is ASSERTED, never rewritten, and only on what holds at every
moment:

- `Cefrium.VERSION` must have a section, and it must be the newest released
  one. This caught a live gap: `release-web-update.sh` derives the /releases
  note from that section and merely **warned** when it was absent, falling back
  to "New release." -- the same shape as the `|| true` that let the README sit
  six milestones behind. It now refuses, with passing the notes as arg 4 as the
  explicit escape hatch.
- every `Chromium A.B.C.D / CEF branch E` line must have `E == C`, the build
  number, which is the branch rule. A typo'd branch is caught without asserting
  any value against today's engine.

What is deliberately NOT asserted: that the newest entry names the CURRENT
engine. Between an engine bump and the next cut those differ on purpose, and
`engine-bump.sh` runs this script, so asserting it would fail every bump.

A patch-NAME check was considered and rejected. Of the seven `android/...` names
the docs mention, three are legitimately absent from disk: the tutorial
placeholder, `android/glic_android_jni_headers_dep` (upstream adopted the fix
independently, so the patch was deleted and the changelog records that), and a
`cef-android/releases` URL that merely looks like one. An exception list longer
than the findings is the kind of hand-maintained list this script exists to
abolish; `patch.cfg` and `check-patches-applied.sh` already own that question.

`FEATURES-CEFRIUM.md` has no derived figure and is never rewritten. That is
deliberate: its one version, "Cefrium 0.9.2 / Chromium 152", dates a sensor
measurement taken on that build, and deriving it would claim a measurement
nobody made. What rots in a feature guide is the API names in its examples, and
no script can derive the right name -- so a missing one fails the release and a
human fixes the prose. `navigator.` is not checked, because the Compose
navigator shares that name with the web platform's own. `webView.` is not
checked either: half of `MIGRATION-CEFRIUM.md` quotes Android WebView's API so
a reader can compare it to ours, those names are Google's to change, and the
comparison is worthless if one side is dropped for being undeclarable.

`BUILD-GUIDE-CEFRIUM.md` owns the engine in three places, including the
`git checkout` of step 1: it said 150.0.7871.46, six Chromium milestones behind
the engine the same guide documents, so anyone following it built something
else. It also listed one JDK under System requirements while its own export
line twenty lines down required another, and sent the reader to a `<JBR-21>`
that this file warns against by name. (Neither sentence names the current
engine or the current floor on purpose: prose that quotes a live figure rots
exactly like the figure, and this one is not worth an anchor.) Its unowned figures stay
unowned: host disk and RAM, the `~MB` artifact sizes, the GN revision
(depot_tools is not this repo) and the build-times table, which is a measurement
on one named machine. Its `0.3.0` command examples became `<version>`
placeholders, matching the idiom the same document already used for
`cut-release.sh <version> <prev-version>`.

`MIGRATION-CEFRIUM.md` owns one figure, the pinned Chromium milestone in its
"What Cefrium adds over WebView" table, which was seven milestones adrift. Its
other numbers are not ours: `(0.5.0+)` marks the release a feature arrived in,
`API 33+` and `Android 14+` are WebView's and Credential Manager's floors. Its
ad-blocking row claimed "EasyList 61K rules" and now names
`Cefrium.loadAdBlockList` instead -- the repo bundles no list at all, the
consumer supplies the path or `downloadAndLoadEasyList` fetches easylist.to and
refreshes it daily, so the count changes with no commit here and can have no
owner. A figure with no possible owner is removed, not faked.

`release-web-verify.sh` checks the POST-conditions rather than the steps, on
the local tree AND on the live site:

- `/releases` has this version as its **top row** and the "Which version, and
  where" table **recommends it** -- "the string appears somewhere" is vacuous
  on a page that lists every past version, and that weak check let the table
  keep recommending 0.9.4 after 0.10.0 shipped;
- the **avatar badge** states the Chromium major this release carries. It is
  the most visible number on the site and was hand-edited, so it sat at 154
  while 156 was live. `release-web-update.sh` now derives it from
  `Cefrium.CHROMIUM_VERSION`;
- quickstart coordinates, the upstream page, both doc sets, every referenced
  asset exists, download links return 200;
- then **cefrium.com itself**. Checking only the local checkout is how this
  script once reported "consistent" while the site served the previous
  release. A live mismatch is a WARNING when the tree is correct and pushed
  (Pages caches for 600s and edges disagree meanwhile) and a FAILURE when the
  repo has unpushed commits.

## Check what is actually published

```bash
cef/cefrium_sdk/release-status.sh <version>
```

Both registries x 5 coordinates, the release assets, and the ABIs and engine
found INSIDE the published bytes. This is the answer to "did it really go out",
and it gates the prune.

## Engine bump

```bash
cef/cefrium_sdk/probe-engine-bump.sh <chromium-tag>   # price it, no sync
cef/cefrium_sdk/engine-bump.sh                        # do it (guided)
```

`probe` fetches only our patch targets over HTTP and reports, per patch, clean /
minor drift / real drift. `engine-bump.sh` stops and PRINTS the rebase commands
rather than rewriting history itself -- run those yourself, in a terminal where
the hardware signing key can prompt. Full method: `PATCHING-CEFRIUM.md`.

After any bump, in this order:

```bash
python3 cef/cefrium_sdk/apply-patches.py            # never patcher.py directly
cef/cefrium_sdk/check-patches-applied.sh            # is the content really there?
cef/cefrium_sdk/check-patch-format.sh               # -p0 / no-prefix
cef/cefrium_sdk/regen-gen-jni.sh out/<dir>          # checked-in generated Java
```

`check-patches-applied.sh` exists because `apply-patches.py` reports "failed"
both for a patch that is already applied and for one that is genuinely missing.
On the 154->156 bump that ambiguity hid a half-applied core patch.

## Patches

| Task | Command |
|---|---|
| Regenerate one patch | `python3 cef/tools/patch_updater.py --resave --patch android/<name>` |
| Create one from tree edits | `... --resave --patch android/<name> --add <file>` |
| Work on one in isolation | `... --revert --patch android/<name>` then `--reapply` |
| Bulk regenerate | `cef/cefrium_sdk/resave-patches.sh` |
| Strip a/b prefixes | `cef/cefrium_sdk/normalize-patch.sh <file.patch>` |

`--patch` takes the name as `patch.cfg` registers it, WITH the `android/`
prefix; the bare basename is a silent no-op.

## Docs

```bash
cef/cefrium_sdk/regen-docs.sh                 # Javadoc + Dokka, with assertions
cef/cefrium_sdk/release-publish-docs.sh       # commit + push both doc trees
```

`regen-docs.sh` refuses to claim success unless every public symbol in the
source has a generated page -- both generators fail silently otherwise.

## Dev iteration (the loop you run while fixing something)

```bash
cef/cefrium_sdk/dev-iterate.sh [--activity <Name>] [--clean-profile] [--skip-build]
```

ninja (libcef + AAR) -> slim -> stage into the sample -> Gradle -> install ->
launch -> read the verdict file, exiting non-zero unless it says PASS. It
snapshots the verdict files before launching so a leftover result cannot be
read as this run's pass, and `--clean-profile` does the `pm clear` + re-grant
that permission tests need (a decided permission is remembered, and then the
handler is never consulted). `--skip-build` for sample-only edits.

## Device checks

| Task | Command |
|---|---|
| Run the instrumentation suite as a gate | `cef/cefrium_sdk/run-instrumentation-suite.sh [<version>]` |
| Same suite on the app minified with R8 | `cef/cefrium_sdk/run-instrumentation-suite.sh --minified [<version>]` |
| Web Push through FCM on a device: repeated resubscribes per profile, two sites | `cef/cefrium_sdk/push-e2e.sh [<sdk.aar>]` |
| Smoke the published artifact | `cef/cefrium_sdk/smoke-samples.sh --registry <version>` |
| R8 consumer rules: Play's 25% bar + minified run | `cef/cefrium_sdk/smoke-minified.sh` |
| Test a published release e2e | `cef/cefrium_sdk/test-published-release.sh <version>` |
| Verify an artifact's engine/ABIs | `cef/cefrium_sdk/verify-release-artifacts.sh <version>` |
| Audit the engine's network calls | `cef/cefrium_sdk/audit-engine-network.sh` |
| Record the startup read-ahead list for an arm64 AAR (the cut's `hotranges` phase) | `ANDROID_SERIAL=<s> cef/cefrium_sdk/record-hot-ranges.sh <aar> [--inject]` |
| Did it crash, and of what? | `cef/cefrium_sdk/why-died.sh [package]` |
| Web Push end to end (real FCM) | `cefrium-sample/push-demo/e2e/webpush-e2e.py --serial <s> --profile default\|<name>` |

`run-instrumentation-suite.sh` stages the AARs into the harness, runs
`connectedDebugAndroidTest`, and then reads the result files instead of
trusting Gradle's exit status. It fails on a failure, on a skipped test, and on
a case whose duration is 0.000s: a result file reading `tests=46 failures=0`
with every time zero is a suite that did not run, and it looks exactly like a
pass in the build output. `cut-release.sh` runs it as the `suite` phase, the
last gate before anything becomes immutable.

`why-died.sh` is read-only and answers the question that is most often got
wrong here: it reads `dumpsys activity exit-info` and classifies the reason
(4/5/6 = a real crash, 10 = your own force-stop, 13 subreason 17 = normal
sandboxed-renderer reaping), then pulls the Java cause and any `libcef.so`
backtrace and prints the `llvm-addr2line` command for it. Use it instead of
`pidof`: the W90 takes 15-20 s to spawn a renderer, so a check before that
reports a crash that never happened.

`webpush-e2e.py` is the one check that is not hermetic, so it is not in the
gate: it sends real VAPID messages through FCM to the push-demo app and passes
only when the service worker reports each one, with the app in front and with
its process killed first. It needs Play services on the device; the W90 and a
`google_apis` emulator both pass (Web Push registers through InstanceID, which
works where `FirebaseMessaging.getToken()` hangs). Run it before a cut that
touches push, the profiles or the GCM patch hunks.

Validation samples live in `cefrium-sample/java-sample`; each writes a verdict
file read with `adb shell run-as com.cefrium.sample cat files/<name>.txt`. The
ones added for 0.10.0:

```bash
adb shell am start -n com.cefrium.sample/.OsrFocusKeyActivity         # OSR focus + keys + permissions
adb shell am start -n com.cefrium.sample/.MediaServiceMultiTabActivity # media foreground service
```

## Backups

```bash
upstream-patches/archive.sh      # verified bundle + tarball of the private archive
```
