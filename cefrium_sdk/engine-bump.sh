#!/bin/bash
# Engine-bump assistant -- the MECHANICAL scaffolding for moving the Android port
# onto a new Chromium/CEF release (e.g. 148 -> 149). It does NOT auto-resolve
# patch conflicts or fix Chromium API breaks: those need human judgment. What it
# does: pin the new version, sync, guide the CEF rebase, re-apply patches, and
# print a per-patch CONFLICT REPORT with the exact regeneration commands.
#
# Usage:
#   cef/cefrium_sdk/engine-bump.sh <chromium-tag> <cef-branch> [--from <phase>]
#     e.g. engine-bump.sh 149.0.7827.53 7827        # Debian-tracked Chromium 149
# Phases: preflight pin sync rebase apply report brand
#   Run report-only against the CURRENT tree any time:  --from report
#
# Destructive phases (sync, rebase) require typing the chromium tag to confirm;
# CEFRIUM_YES=1 bypasses (CI). The port's patches live in cef/patch/ (committed),
# so a clean re-sync never loses them -- it re-applies them onto the new tree.
set -euo pipefail

CTAG="${1:?usage: engine-bump.sh <chromium-tag> [cef-branch] [--from phase]}"; shift
# CEF branch = the Chromium BUILD number (3rd version component). This is the
# Debian CEF mapping ("CEF branch number = Chromium BUILD number": 149.0.7827.53
# -> 7827). Defaulted from the tag; pass an explicit branch to override.
CEFBR="$(echo "$CTAG" | cut -d. -f3)"
if [ $# -gt 0 ] && [ "${1#--}" = "$1" ]; then CEFBR="$1"; shift; fi
FROM="preflight"
while [ $# -gt 0 ]; do case "$1" in --from) FROM="$2"; shift 2;; *) echo "unknown arg: $1"; exit 1;; esac; done

# Core CEF patches that are large and routinely apply with fuzz on a new Chromium
# (Debian handles the same class via a skip-list + `--fuzz=3`). They are NOT real
# conflicts -- the report flags them separately so a bump isn't derailed by them.
KNOWN_FUZZ="gn_config chrome_runtime chrome_browser_profiles"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"            # chromium/src
ROOT="$(cd "$SRC/../.." && pwd)"                  # cef-dev
CEF="$SRC/cef"
GCLIENT="$SRC/../.gclient"
COMPAT="$CEF/CHROMIUM_BUILD_COMPATIBILITY.txt"
CEFJAVA="$CEF/cefrium_sdk/java/src/com/cefrium/Cefrium.java"
# Bump artefacts (patcher log, analyses) live on the workspace NVMe, not /tmp:
# /tmp is tmpfs here, so a reboot mid-bump loses the log you need to diagnose the
# conflicts, and this machine has already hit a machine-wide ENOSPC from /tmp.
BUMP_TMP="$ROOT/tmp/engine-bump"
mkdir -p "$BUMP_TMP"
PHASES="preflight pin sync rebase apply report brand"

idx() { local i=0; for p in $PHASES; do [ "$p" = "$1" ] && { echo $i; return; }; i=$((i+1)); done; echo 999; }
FROM_I=$(idx "$FROM")
run() { [ "$(idx "$1")" -ge "$FROM_I" ]; }
hdr() { echo; echo "============================================================"; echo "  PHASE: $1"; echo "============================================================"; }
gate() {
  [ "${CEFRIUM_YES:-0}" = "1" ] && { echo "[gate] $1 -- auto (CEFRIUM_YES=1)"; return; }
  echo; echo "*** DESTRUCTIVE: $1"
  printf "    Type the chromium tag (%s) to proceed, anything else aborts: " "$CTAG"
  read -r a; [ "$a" = "$CTAG" ] || { echo "Aborted."; exit 1; }
}

CUR_CTAG="$(git -C "$SRC" describe --tags 2>/dev/null || echo unknown)"

if run preflight; then hdr "preflight"
  echo "  current Chromium: $CUR_CTAG   ->   target: $CTAG   (CEF branch $CEFBR)"
  # Patches are safe only if the cef working tree is committed.
  if [ -n "$(git -C "$CEF" status --porcelain)" ]; then
    echo "  ERROR: cef tree has uncommitted changes -- commit/stash before bumping (patches must be safe in git)."; exit 1
  fi
  echo "  cef android-port: clean ($(git -C "$CEF" rev-parse --short HEAD))"
  # Confirm the new Chromium tag is reachable on the remote.
  if git -C "$SRC" ls-remote --tags origin "refs/tags/$CTAG" 2>/dev/null | grep -q "$CTAG"; then
    echo "  remote has refs/tags/$CTAG: yes"
  else
    echo "  WARN: refs/tags/$CTAG not found on origin (check the exact Debian-tracked tag)."
  fi
fi

if run pin; then hdr "pin new version (config only, non-destructive)"
  cp "$GCLIENT" "$GCLIENT.bak"; cp "$COMPAT" "$COMPAT.bak"
  perl -0777 -i -pe "s{(refs/tags/)[0-9.]+}{\${1}$CTAG}" "$GCLIENT"
  perl -0777 -i -pe "s{(refs/tags/)[0-9.]+}{\${1}$CTAG}" "$COMPAT"
  perl -0777 -i -pe "s{(CHROMIUM_VERSION = \")[0-9.]+(\")}{\${1}$CTAG\${2}}" "$CEFJAVA"
  echo "  pinned .gclient + CHROMIUM_BUILD_COMPATIBILITY + Cefrium.CHROMIUM_VERSION -> $CTAG"
  echo "  (review: git -C $CEF diff; backups at *.bak)"
fi

if run sync; then hdr "gclient sync to $CTAG (DESTRUCTIVE: resets chromium/src working tree)"
  gate "reset chromium/src to clean $CTAG and gclient sync (downloads; ~hours)"
  echo "  reverting chromium/src patched files to clean state (patches are in cef/, safe)..."
  git -C "$SRC" fetch --tags origin "refs/tags/$CTAG"
  # -f is REQUIRED: after any build the tree carries our applied Chromium patches
  # (modified tracked files), so a plain checkout aborts with "local changes would
  # be overwritten". Forcing discards those working-tree mods -- safe because the
  # patch SOURCES live in cef/patch/ (committed) and are re-applied in the apply
  # phase; cef/ and out/ are nested/gitignored so checkout never touches them.
  git -C "$SRC" -c advice.detachedHead=false checkout -f "refs/tags/$CTAG"  # discards applied patches
  git -C "$SRC" clean -fd -e '/cef' -e '/out'                              # keep cef/ and out/
  # DEP HYGIENE -- do this BEFORE gclient sync, or the sync dies partway through.
  #
  # gclient only CHECKS OUT a dep that is on a detached HEAD. A dep sitting on a
  # local branch it treats as "managed by you", so it tries to REBASE that branch
  # onto the new DEPS pin -- and if the branch tracks the dep's real upstream it is
  # hundreds of commits ahead, so the rebase conflicts and aborts the whole sync.
  # On the 152->154 bump third_party/fontconfig/src was on `master`, 498 commits
  # ahead, and died on a conflict in src/fcstat.c after the main tree had already
  # been checked out.
  #
  # Detaching at the CURRENT commit discards nothing: gclient then checks out the
  # pin itself, the normal path. We never patch a separate dep repo (our patches
  # target main src only, blink included), so there is nothing of ours to lose.
  #
  # NOTE: an in-progress rebase leaves HEAD detached, so "is it on a branch?"
  # MISSES exactly the dep that is already stuck -- check for a rebase state dir
  # too. (That is why the first attempt at this looked clean and was not.)
  echo "  detaching gclient deps so sync checks them out instead of rebasing them..."
  while IFS= read -r g; do
    d="$(dirname "$g")"
    [ "$d" = "$SRC" ] && continue          # never the main tree
    case "$d" in "$SRC"/cef|"$SRC"/cef/*|"$SRC"/out/*) continue;; esac
    if [ -d "$g/rebase-merge" ] || [ -d "$g/rebase-apply" ]; then
      echo "    aborting a stuck rebase in ${d#$SRC/}"
      git -C "$d" rebase --abort 2>/dev/null || true
    fi
    if git -C "$d" symbolic-ref --quiet HEAD >/dev/null 2>&1; then
      echo "    detaching ${d#$SRC/} (was on $(git -C "$d" rev-parse --abbrev-ref HEAD))"
      git -C "$d" checkout --detach --quiet 2>/dev/null || true
    fi
  done < <(find "$SRC" -maxdepth 6 -name .git -not -path "$SRC/.git" -not -path "$SRC/cef/*" 2>/dev/null)

  # --force --reset: gclient-managed deps (third_party/dawn, angle, ...) are
  # SEPARATE git checkouts; the checkout -f above only reset the main src tree, so
  # a dep left dirty by a prior sync/build makes gclient refuse ("you have
  # uncommitted changes"). --reset cleans each dep to its DEPS pin first. Safe: our
  # CEF patches only target main-src files (incl. third_party/blink, which lives IN
  # main src), never a separate dep repo, so nothing of ours is discarded here.
  ( cd "$ROOT/depot_tools" && cd "$SRC" && gclient sync --no-history -D --force --reset )
  echo "  synced to $CTAG."
  # CRITICAL: a BUILD-number bump leaves STALE Java build metadata
  # (build_config.json + turbine graph) in any pre-existing out/ dir. That stale
  # state produces a phantom Java turbine "dependency cycle" (actor -> ... ->
  # actor) when building the AAR/APK. It survives `gn gen` and even
  # `rm build.ninja` -- ONLY a fresh out dir clears it (a targeted delete of
  # build_config.json/*.turbine.jar is NOT reliable). So wipe the build dirs:
  # every post-bump build must start from a clean out dir.
  echo "  wiping out/ build dirs (stale across a BUILD-number bump -- see above)..."
  rm -rf "$SRC"/out/* 2>/dev/null || true
fi

if run rebase; then hdr "rebase CEF android-port onto upstream branch $CEFBR (guided)"
  gate "fetch + rebase cef android-port onto upstream/$CEFBR"
  cd "$CEF"
  # CEF's upstream is on bitbucket (origin here) and github; use whichever has it.
  REBASE_REMOTE=origin
  git rev-parse --verify "origin/$CEFBR" >/dev/null 2>&1 || \
    git fetch --depth=200 origin "$CEFBR:refs/remotes/origin/$CEFBR" 2>/dev/null || \
    { git remote get-url upstream >/dev/null 2>&1 || git remote add upstream https://github.com/chromiumembedded/cef.git; \
      git fetch --depth=200 upstream "$CEFBR:refs/remotes/upstream/$CEFBR"; REBASE_REMOTE=upstream; }
  # CRITICAL: remove untracked build artifacts first. During the rebase, early
  # commits predate the .gitignore rules for build/.gradle/__pycache__, so any
  # such files on disk make git refuse the checkout ("untracked working tree
  # files would be overwritten"). The rebase re-creates source from commits.
  echo "  cleaning untracked build artifacts before rebase..."
  git clean -fdq
  # -fdq above is NOT enough: it leaves files that .gitignore covers TODAY, and
  # those are exactly the ones old commits TRACKED (gradle-plugin/build,
  # cefrium_compose/build, __pycache__). The rebase then stops partway with
  # "untracked working tree files would be overwritten". So clean the ignored
  # build outputs too -- by explicit path, because a blanket `-fdx` would also
  # take gradle/wrapper/gradle-wrapper.jar (gitignored, and a pain to restore).
  echo "  cleaning gitignored build outputs (old commits tracked these paths)..."
  git clean -fdxq \
    cefrium_sdk/gradle-plugin/build cefrium_sdk/gradle-plugin/.gradle \
    cefrium_compose/build cefrium_compose/.gradle cefrium_compose/libs \
    cefrium_instrumentation_test/build cefrium_instrumentation_test/.gradle \
    cefrium_instrumentation_test/app/build 2>/dev/null || true
  # The AARs in cefrium_compose/libs/ are staged copies of a PREVIOUS engine's
  # build; after a bump they are stale and actively dangerous to keep, since a
  # Gradle step can pick one up silently. They are re-staged from the new build.
  # FORK POINT -- derived, never hardcoded. The replay range must be exactly our
  # own commits: `<fork>..android-port`. Our branch sits on whichever upstream
  # branch the LAST bump rebased it onto, so the fork point is the merge-base
  # with THAT branch -- and which one it is changes every bump. Hardcoding it
  # (this used to say origin/7778) silently rots: against a stale base the range
  # also picks up the previous branch's upstream commits that the new branch does
  # not have, and the rebase replays upstream CEF history onto upstream CEF
  # history. Measured on the 152->154 bump: a 7778 base gave 547 commits where
  # the real answer was 469, i.e. 78 upstream commits replayed for nothing.
  #
  # So: of every upstream branch ref we have, take the one whose merge-base with
  # android-port is NEWEST (the fewest commits to replay). That is by definition
  # the branch we last rebased onto, and it self-corrects on every future bump.
  FORK=""; FORK_N=""; FORK_SRC=""
  for ref in $(git for-each-ref --format='%(refname:short)' \
                 'refs/remotes/origin/[0-9][0-9][0-9][0-9]' \
                 'refs/remotes/upstream/[0-9][0-9][0-9][0-9]' 2>/dev/null); do
    mb=$(git merge-base "$ref" android-port 2>/dev/null) || continue
    n=$(git rev-list --count "$mb..android-port" 2>/dev/null) || continue
    if [ -z "$FORK_N" ] || [ "$n" -lt "$FORK_N" ]; then
      FORK="$mb"; FORK_N="$n"; FORK_SRC="$ref"
    fi
  done
  if [ -z "$FORK" ]; then
    echo "  ERROR: could not derive a fork point (no upstream branch refs fetched?)."
    echo "         Fetch the branch you last rebased onto, then re-run --from rebase."
    exit 1
  fi
  echo "  fork point: $FORK  (merge-base with $FORK_SRC; $FORK_N commits to replay)"
  # Sanity: the replay range must contain only our commits. An upstream author in
  # there means the fork point is wrong and the rebase would replay CEF history.
  # Both maintainer addresses count: older port commits are authored as
  # vejeta@gmail.com (the address the Chromium CLA/Gerrit side uses), newer ones
  # as juan.mendezr@proton.me.
  MAINTAINER_MAILS='juan.mendezr@proton.me|vejeta@gmail.com'
  foreign=$(git log "$FORK..android-port" --format='%ae' | grep -Evc "$MAINTAINER_MAILS" || true)
  if [ "${foreign:-0}" -gt 0 ]; then
    echo "  WARNING: $foreign of those $FORK_N commits are authored by neither maintainer"
    echo "           address, which usually means the fork point is too old and the"
    echo "           rebase would replay upstream CEF history. Inspect before rebasing:"
    echo "             git log $FORK..android-port --format='%h %ae %s' | grep -Ev '$MAINTAINER_MAILS'"
  fi
  echo "  Rebase (do it by hand so you control conflict resolution):"
  echo "    cd $CEF"
  echo "    git branch -f android-port-$CEFBR android-port && git checkout android-port-$CEFBR"
  echo "    git rebase --committer-date-is-author-date \\"
  echo "        --onto $REBASE_REMOTE/$CEFBR $FORK android-port-$CEFBR"
  echo "  Resolution playbook (from the 7778->7827 bump):"
  echo "    - PRESERVE DATES: the --committer-date-is-author-date flag above sets each"
  echo "      replayed commit's committer date to its author date (the real history),"
  echo "      NOT the rebase day. Do NOT drop it -- a plain rebase flattens every"
  echo "      committer date to today and the whole branch reads as one day's work."
  echo "    - If you must re-sign afterwards (cold hardware signing key during the rebase), do NOT"
  echo "      use a bare 'git commit --amend -S' (it resets the committer date to now)."
  echo "      Re-sign preserving the date, e.g.:"
  echo "        git rebase --exec 'GIT_COMMITTER_DATE=\"\$(git show -s --format=%aD HEAD)\" git commit --amend --no-edit -S' $REBASE_REMOTE/$CEFBR"
  echo "    - doc/README conflicts: 'git checkout --theirs <file>; git add <file>' (later"
  echo "      commits rebuild the final doc), then 'git rebase --continue'."
  echo "    - a commit that goes empty (its change is already in $CEFBR): 'git rebase --skip'."
  echo "    - real source conflicts: resolve by hand, then continue."
  echo "  When the rebase is clean, fast-forward android-port to it, then re-run:"
  echo "    $0 $CTAG $CEFBR --from apply"
  exit 0
fi

if run apply; then hdr "apply patches (incl. Android-conditional set)"
  cd "$SRC"
  # NOTE: do NOT use `export 'target_os=="android"'=1` -- that illegal env name
  # is mangled by the shell and the Android patches get silently skipped. The
  # wrapper sets the condition key correctly (see apply-patches.py header).
  python3 cef/cefrium_sdk/apply-patches.py 2>&1 | tee "$BUMP_TMP/patcher-$CTAG.log" || true
fi

if run report; then hdr "patch conflict report"
  cd "$SRC"
  log="$BUMP_TMP/patcher-$CTAG.log"
  clean=0; conflict=0
  echo "  Per Android patch (the port's own; core CEF patches come from the rebased branch):"
  for p in cef/patch/patches/android/*.patch; do
    name="$(basename "$p" .patch)"
    if git apply -p0 --reverse --check "$p" 2>/dev/null; then
      echo "    OK       $name (applied)"; clean=$((clean+1))
    elif git apply -p0 --check "$p" 2>/dev/null; then
      echo "    PENDING  $name (applies clean, not yet applied)"
    else
      echo "    CONFLICT $name  -> regenerate (project methodology):"
      echo "             # 1) apply the surviving hunks, fix the rest by hand in the target files"
      echo "             # 2) cd \$workdir && git diff > cef/patch/patches/android/$name.patch"
      conflict=$((conflict+1))
    fi
  done
  echo
  echo "  Summary: $clean applied, $conflict conflicting (need manual regeneration)."
  [ -f "$log" ] && echo "  Full patcher log: $log"
  echo

  # Upstream's own patcher-output analyzer. CEF ships it (with unit tests) at
  # cef/tools/claude/, and it does what used to be done by reading the raw log by
  # eye: groups the failures and detects files that MOVED between the two
  # Chromium versions, which is the most common cause of a patch that "just
  # stopped applying". Reading the log manually is slow, unrepeatable, and the
  # conclusion depends on who read it.
  ANALYZER="cef/tools/claude/analyze_patch_output.py"
  if [ -f "$log" ] && [ -f "$ANALYZER" ]; then
    out="$BUMP_TMP/patch-analysis-$CTAG.txt"
    if python3 "$ANALYZER" "$log" --no-color \
         --old-version "${CUR_CTAG#v}" --new-version "$CTAG" \
         --project-root "$SRC" > "$out" 2>/dev/null; then
      echo "  Upstream patch analysis: $out"
      sed -n '1,40p' "$out" | sed 's/^/    /'
      [ "$(grep -c . "$out")" -gt 40 ] && echo "    ... (full report in $out)"
    else
      echo "  (analyze_patch_output.py could not parse the log; raw log above)"
    fi
    echo
  fi

  # OBSOLESCENCE AUDIT. A patch whose files no longer differ from HEAD
  # contributes nothing: upstream adopted the change, or it stopped applying.
  # This is invisible otherwise, because apply-patches.py runs under `|| true`
  # for the known-fuzz core patches -- which is how android/glic_android_jni_
  # headers_dep stayed registered and dead from Chromium 154 onward.
  #
  # WHAT THIS CANNOT SEE, so do not read "none" as "every patch is justified":
  # the other kind of dead patch is one that still applies PERFECTLY and is no
  # longer NEEDED -- a workaround for a bug upstream fixed elsewhere, a guard for
  # a code path nothing reaches any more, a flag for a toolchain nobody ships.
  # Those produce a clean diff forever and no tool detects them. Only reading the
  # patch and asking whether its reason still holds does, which is human work and
  # belongs in the periodic patch review, not here.
  echo "  Obsolescence audit (patch files identical to upstream HEAD => dead patch):"
  dead=0
  for p in cef/patch/patches/android/*.patch; do
    files=$(grep "^--- " "$p" | grep -v /dev/null | sed 's/^--- //;s/\t.*//')
    [ -z "$files" ] && continue
    if [ "$(git diff --name-only HEAD -- $files 2>/dev/null | wc -l)" -eq 0 ]; then
      echo "    DEAD     $(basename "$p" .patch) -- upstream has it; drop the patch + its patch.cfg entry"
      dead=$((dead+1))
    fi
  done
  [ "$dead" -eq 0 ] && echo "    none -- every android patch still contributes a change"
  echo
  echo "  Known-fuzz core CEF patches (Debian applies these with --fuzz=3; patcher.py"
  echo "  reports them 'failed' but they ARE applied -- verify by a signature line, don't"
  echo "  treat as conflicts):"
  for kf in $KNOWN_FUZZ; do
    pf="cef/patch/patches/$kf.patch"
    [ -f "$pf" ] || { echo "    -        $kf (patch not present)"; continue; }
    tgt=$(grep -E '^\+\+\+ ' "$pf" | head -1 | sed 's/^+++ [ab]\///;s/\t.*//')
    tok=$(grep -E '^\+' "$pf" | grep -viE '^\+\+\+' | grep -iE 'cef' | head -1 | sed 's/^+//;s/^[[:space:]]*//')
    if [ -n "$tok" ] && grep -qF "$tok" "$tgt" 2>/dev/null; then
      echo "    APPLIED  $kf (signature present in $tgt)"
    else
      echo "    CHECK    $kf (signature absent -- this one may be a real conflict from the rebase)"
    fi
  done
fi

# `--from report` is a read-only audit: stop before touching the brand mark.
[ "$FROM" = "report" ] && { echo; echo "(report-only run -- no files changed)"; exit 0; }

if run brand; then hdr "brand mark (atomic number auto-follows CHROMIUM_VERSION)"
  ( cd "$ROOT/cefrium-sample" && ./regenerate-icons.sh ) || echo "  (run regenerate-icons.sh manually)"
fi

echo; echo "Engine-bump scaffolding done for $CTAG. Manual next: resolve any CONFLICT patches,"
echo "fix Chromium-149 API breaks at build time, then cut a release with cut-release.sh."
