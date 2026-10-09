# Patching Chromium for Cefrium

Scope: this document is for CEFRIUM (CEF ported to native Android) and its SDK, not upstream CEF.

How the Android port modifies the Chromium/CEF source tree, how those changes
are stored, and how to add or regenerate a patch correctly. Read this before
touching anything under `cef/patch/`.

## Model: patches, not commits

The Chromium checkout (`chromium/src`, excluding `chromium/src/cef`) is **never
committed to**. Every change we make to a Chromium file lives as a `.patch`
file under `cef/patch/patches/` and is **re-applied on every build / engine
bump**. This keeps the port rebaseable onto a new Chromium release and keeps the
diff reviewable and upstream-submittable.

Two sets:

- `cef/patch/patches/*.patch` -- **core** patches. The upstream CEF patch set
  plus our Chromium build workarounds. Applied to every build.
- `cef/patch/patches/android/*.patch` -- **Android-conditional** patches. Applied
  only when building for Android. These are the port's own changes (platform
  guards, JNI seams, GN fixes for non-component Android).

CEF source itself (`cef/libcef/...`, the SDK) IS committed -- on the
`android-port` branch. Only the *Chromium-tree* changes are patches.

## How patches are applied

```
python3 cef/cefrium_sdk/apply-patches.py
```

This runs CEF's `cef/tools/patcher.py` in-process. Two things to know:

1. **patcher.py applies with `git apply -p0`** (`cef/tools/git_util.py`). `-p0`
   means *no path prefix is stripped*. This dictates the patch file format (next
   section) -- get it wrong and the patch silently fails to apply.

2. **Android patches are gated on a condition.** Each android entry in
   `cef/patch/patch.cfg` carries `'condition': 'target_os=="android"'`.
   patcher.py applies a conditional patch only if that string is a key in
   `os.environ`. But `target_os=="android"` is **not a legal POSIX env var
   name** (it contains `=` and `"`): a shell `export 'target_os=="android"'=1`
   splits on the first `=` and sets the wrong variable, so the android patches
   get **silently skipped**. `apply-patches.py` works around this by setting the
   key in a plain Python dict assigned to `os.environ`, then running patcher.py
   via `runpy`. **Always apply via `apply-patches.py`, never `patcher.py`
   directly with a shell-exported condition.**

## Storage format: `-p0`, NO `a/`/`b/` prefix

Because patcher applies with `-p0`, every patch file must use **no path
prefix**. Headers look like this (note: bare paths, no `a/` or `b/`):

```
diff --git chrome/browser/ui/browser.cc chrome/browser/ui/browser.cc
--- chrome/browser/ui/browser.cc
+++ chrome/browser/ui/browser.cc
@@ ...
```

NOT the git default (`diff --git a/chrome/... b/chrome/...`, `--- a/chrome/...`,
`+++ b/chrome/...`). With `-p0`, git apply looks for a literal file named
`a/chrome/...` and fails with `No such file or directory`.

This is the single most important rule. A patch in the wrong (`a/`/`b/`) format
can appear to "work" if the tree already carries the change from a previous
manual apply -- but the first **clean** re-apply (an engine bump, a fresh
checkout) fails.

**Create, then transform.** The reliable way to *create* a patch is `git diff`
over the original file in a workdir (the proven Debian-CEF technique -- see the
next section). `git diff` emits `a/`/`b/` prefixes, so *normalize* its output to
the `-p0` storage format as a separate step:

```
git diff > my.patch                          # create (proven technique)
cef/cefrium_sdk/normalize-patch.sh my.patch  # transform to -p0 (idempotent)
```

Keeping creation and format separate is deliberate: a normalize step makes the
plain-`git diff` habit safe and repairs any already-prefixed patch, whereas a
"remember the flag" rule fails silently when the flag is forgotten.
(`git diff --no-prefix` does both at once if you prefer a one-shot.)

A guard enforces the result: `cef/cefrium_sdk/check-patch-format.sh` fails if any
patch under `cef/patch/patches/` carries an `a/`/`b/` prefix. It runs in the
release gate.

## Registration: `patch.cfg`

A `.patch` file does nothing until it is listed in `cef/patch/patch.cfg`:

```python
  {
    # one-line description of what and why
    'name': 'android/my_new_guard',          # path under patches/, no .patch
    'condition': 'target_os=="android"',     # omit for an unconditional core patch
  },
```

Order in `patch.cfg` is apply order. Core patches are listed before the android
block, so an android patch may build on top of a core patch's change (see
"ownership" below). Add new android patches to the android block.

## Ownership: one file, one patch

Each Chromium file's changes belong to **exactly one** patch. Do not let two
patches modify the same hunk:

- **No duplicate hunks.** If patch A already adds a guard to a file, patch B must
  not add the same guard. (A real bug we hit: two android patches both added the
  same constructor guard to `global_browser_collection_platform_delegate_android.cc`;
  the second failed because the first already applied it.)
- **Build on core patches, do not fight them.** If a core patch (e.g.
  `gn_config.patch`) already adds an `if (enable_cef) { ... }` block, an android
  patch that needs it Android-aware must **modify that block**
  (`if (enable_cef && !is_android)`), not add a second parallel block. Core
  applies first, so the android patch sees the core-modified file.

## Adding or regenerating a patch: use upstream's tool

**Preferred method, for both new and existing patches.** CEF ships the tool that
owns this job, and it has been in the tree all along:

```
SRC=<cef-dev>/chromium/src
cd "$SRC"

# Create a patch from changes already made to a file in the tree:
python3 cef/tools/patch_updater.py --resave --patch android/my_new_guard \
                                   --add chrome/browser/ui/browser.cc

# Regenerate an existing patch (after editing the file in the tree):
python3 cef/tools/patch_updater.py --resave --patch android/my_new_guard

# Revert / reapply a single patch while working on it:
python3 cef/tools/patch_updater.py --revert  --patch android/my_new_guard
python3 cef/tools/patch_updater.py --reapply --patch android/my_new_guard
```

Internally it runs `git diff --no-prefix --relative --full-index`, which is the
`-p0` format CEF stores **plus full 40-hex index hashes**. Those hashes are not
cosmetic: they are what lets `git apply --3way` locate the blobs, and `--3way` is
upstream's documented recovery path when a patch stops applying after a Chromium
bump. Hand-rolled patches (the workdir method below) omit them, which is why
engine-bump conflicts were always resolved by hand here.

**TRAP: `--patch` takes the name exactly as `patch.cfg` registers it, WITH the
`android/` prefix.** Passing the bare basename is a *silent no-op*: it prints
only `Reading patch config`, writes nothing, and exits 0. The selector is
`if options.patch and not patch['name'] in options.patch: continue`.

**Constraint (unchanged by the tool):** `--resave` regenerates from the working
tree, so it attributes every change in a file to the patch being saved. Where
two patches touch one file it silently absorbs the other's hunks -- exactly how
`resource_bundle_2512.patch` once swallowed the Android changes. Only resave a
patch that solely owns its files; see "Ownership" above.

### Bulk regeneration

`cef/cefrium_sdk/resave-patches.sh` does the whole android set safely: it backs
up every patch to `cef-dev/tmp/patch-backup-<stamp>/`, computes file ownership
across *all* patches and refuses any patch that shares a file (naming the
collision), then for each one proves equivalence **by tree, not by text** --
revert the owned files to pristine, apply the regenerated patch, and require the
resulting bytes to be identical. Anything that fails is restored from the backup.

Text comparison cannot decide this. A correct resave legitimately rewrites hunk
headers: our stored patches carried offsets and context from older milestones
(`picture_in_picture_guard_null_tab` still said
`extends AsyncInitializationActivity` where upstream now has
`extends VideoOverlayActivity`), and git may choose a different but equivalent
`-`/`+` split. Both read as "content changed" to a diff; neither is.

```
cef/cefrium_sdk/resave-patches.sh --dry-run    # report only
cef/cefrium_sdk/resave-patches.sh              # all safe android/* patches
cef/cefrium_sdk/resave-patches.sh android/lock_metrics_reentrancy
```

It also finds dead patches: a resave that comes out EMPTY means the tree no
longer differs from upstream for those files, i.e. upstream adopted the change.
That is how `android/glic_android_jni_headers_dep` was found obsolete in 154 --
upstream added both `//chrome/browser/glic/android:jni_headers` deps, the patch
had stopped applying, and the failure was invisible because `apply-patches.py`
runs under `|| true` for the known-fuzz core patches.

## Adding or regenerating a patch (workdir method, legacy)

Use this only when `patch_updater.py --resave` cannot: a patch whose file is
shared with another patch, where resaving would merge both. Never hand-write
patch bodies. Generate them from a real diff in an isolated git workdir so paths
and context are exact.

```
SRC=<cef-dev>/chromium/src
cd "$SRC"

# 1. Workdir mirroring the path from the src root. Copy the CURRENT (already
#    patched) target file so context matches what patcher produces -- i.e. core
#    patches already applied. Use ./tmp (NVMe, persists), not /tmp (tmpfs).
WD=../../tmp/mypatch-wd
rm -rf "$WD"; mkdir -p "$WD/chrome/browser/ui"
cp chrome/browser/ui/browser.cc "$WD/chrome/browser/ui/"
cd "$WD" && git init -q && git add -A && git commit -qm orig

# 2. Edit the file in the workdir (Read then Edit; never sed -i).

# 3. Generate with git diff (the proven technique), then normalize to -p0.
P="$SRC/cef/patch/patches/android/my_new_guard.patch"
git diff > "$P"
"$SRC/cef/cefrium_sdk/normalize-patch.sh" "$P"

# 4. Register it in cef/patch/patch.cfg (see above).

# 5. Verify it applies the way patcher will (-p0) against the real tree.
cd "$SRC"
git apply -p0 --check cef/patch/patches/android/my_new_guard.patch && echo OK
```

When an existing patch conflicts after a Chromium bump, regenerate it the same
way (copy the current file, re-apply the surviving hunks by hand, diff). Do not
edit the `.patch` text directly.

## Verify reproducibility (the real test)

An incremental tree that "builds" proves nothing -- it may carry changes a clean
apply cannot reproduce. The only real check is a **clean reset + reapply**:

```
cd chromium/src
git checkout -f refs/tags/<chromium-tag>      # sanctioned reset: patches live in
git clean -fdq -e '/cef' -e '/out'            # cef/patch/, so nothing is lost
python3 cef/cefrium_sdk/apply-patches.py
# Must report:  N patches total (M applied, K skipped, 0 failed)
```

`0 failed` is the gate. Any non-zero "failed" means a patch is broken (wrong
format, conflict, or duplicate hunk) -- fix it before building. `skipped` is
normal (condition-gated patches not for this configuration).

> Note on `git checkout -f` / `git clean` in `chromium/src`: this is destructive
> and normally forbidden, BUT it is the **sanctioned** reset for a patch reapply
> or engine bump, because the patch *sources* are committed in `cef/patch/` and
> `cef/` and `out/` are excluded. `engine-bump.sh` does exactly this. Do not run
> a bare `git checkout`/`reset` in `chromium/src` for any other reason.

## After applying: force recompilation

`patcher.py` and `git apply` do not bump file mtimes, so ninja will not
recompile patched files. After every apply, touch them:

```
for p in cef/patch/patches/*.patch cef/patch/patches/android/*.patch; do
  for f in $(grep '^--- ' "$p" | grep -v /dev/null | sed 's/^--- //;s/\t.*//'); do
    [ -f "$f" ] && touch "$f"
  done
done
```

And after a BUILD-number engine bump, build in a **fresh** `out/` dir -- stale
Java build metadata otherwise produces a phantom turbine "dependency cycle". The
scripts handle this via an engine stamp; only manual builds in a reused dir are
at risk.

## Engine bumps

To move the port onto a new Chromium release use the assistant, which pins the
version, syncs, re-applies, and prints a per-patch conflict report:

```
cef/cefrium_sdk/engine-bump.sh <chromium-tag> <cef-branch>
# e.g. engine-bump.sh 149.0.7827.102 7827
```

A same-branch security respin (e.g. `149.0.7827.53 -> .102`) is cheap: no CEF
rebase (the branch number is unchanged), just re-sync Chromium to the new tag
and re-apply. A milestone bump (e.g. `7778 -> 7827`) also rebases the CEF
`android-port` branch. Either way, finish with the clean reapply check above and
fix any conflicting patches with the workdir method.

## Where the rules come from

These conventions were not arbitrary -- each one is a bug we paid for once:

- `-p0` no-prefix format: 20 android patches silently failed the first clean
  reapply because they were stored with git's default `a/`/`b/` prefix.
- one-file-one-patch + build-on-core: two pairs of patches collided on the same
  hunk after a bump.
- clean-reapply-is-the-test: both of the above were invisible on the incremental
  tree and only surfaced on a from-scratch apply.
