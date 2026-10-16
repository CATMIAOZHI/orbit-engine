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
with `shallow update not allowed`), refuses a diverged push unless you pass
`--force`, pushes a timestamped backup branch at the current remote head before
any forced update, and verifies the remote afterwards instead of trusting the
exit code. `--dry-run` prints what would happen and pushes nothing.

Do not push any other directory. In particular `upstream-patches/` is a
local-only archive and must never be given a remote; back it up with
`upstream-patches/archive.sh` instead.

## Release

```bash
cef/cefrium_sdk/cut-release.sh <version> <prev-version>      # the whole thing
cef/cefrium_sdk/cut-release.sh <version> <prev> --from publish   # resume
```

Phases: `patches build merge compose plugin stats sanity publish central
flagship release web prune play`. Flags that matter:
`CEFRIUM_INCREMENTAL=1` (reuse out dirs), `CEFRIUM_YES=1` (pre-authorize the
irreversible gates -- needed for an unattended run), `CEFRIUM_NO_PRUNE=1`,
`CEFRIUM_NO_PLAY=1`, `CEFRIUM_PLAY_TRACK=internal`. Details and the reasoning
behind the phase order: `RELEASING-CEFRIUM.md`.

Individual phases, runnable on their own:

| Task | Command |
|---|---|
| Build the 4 variants | `cef/cefrium_sdk/build-all-variants.sh [--clean]` |
| Slim one raw AAR | `python3 cef/cefrium_sdk/slim_aar.py --input <raw.aar> --output <slim.aar>` |
| Fuse arm64 + x86_64 | `cef/cefrium_sdk/merge-multiabi.sh` |
| Publish to Codeberg Maven | `cef/cefrium_sdk/publish-maven.sh <version>` |
| Publish to Maven Central | `cef/cefrium_sdk/publish-central.sh <version>` |
| Codeberg release + GPG sums | `cef/cefrium_sdk/release-codeberg.sh <version> <apk>` |
| Update cefrium.com + docs | `cef/cefrium_sdk/release-web-update.sh <version> <prev>` |
| Verify cefrium.com matches | `cef/cefrium_sdk/release-web-verify.sh <version> [prev]` |
| Google Play (apps) | `cef/cefrium_sdk/release-play.sh <version> [track]` |
| Drop superseded versions | `cef/cefrium_sdk/prune-old-versions.sh <version> --yes` |

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
| Smoke the published artifact | `cef/cefrium_sdk/smoke-samples.sh --registry <version>` |
| Test a published release e2e | `cef/cefrium_sdk/test-published-release.sh <version>` |
| Verify an artifact's engine/ABIs | `cef/cefrium_sdk/verify-release-artifacts.sh <version>` |
| Audit the engine's network calls | `cef/cefrium_sdk/audit-engine-network.sh` |
| Did it crash, and of what? | `cef/cefrium_sdk/why-died.sh [package]` |

`why-died.sh` is read-only and answers the question that is most often got
wrong here: it reads `dumpsys activity exit-info` and classifies the reason
(4/5/6 = a real crash, 10 = your own force-stop, 13 subreason 17 = normal
sandboxed-renderer reaping), then pulls the Java cause and any `libcef.so`
backtrace and prints the `llvm-addr2line` command for it. Use it instead of
`pidof`: the W90 takes 15-20 s to spawn a renderer, so a check before that
reports a crash that never happened.

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
