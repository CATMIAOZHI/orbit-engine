#!/bin/bash
# Engine-bump assistant — the MECHANICAL scaffolding for moving the Android port
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
# so a clean re-sync never loses them — it re-applies them onto the new tree.
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
# conflicts — the report flags them separately so a bump isn't derailed by them.
KNOWN_FUZZ="gn_config chrome_runtime chrome_browser_profiles"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"            # chromium/src
ROOT="$(cd "$SRC/../.." && pwd)"                  # cef-dev
CEF="$SRC/cef"
GCLIENT="$SRC/../.gclient"
COMPAT="$CEF/CHROMIUM_BUILD_COMPATIBILITY.txt"
CEFJAVA="$CEF/cefrium_sdk/java/src/com/cefrium/Cefrium.java"
PHASES="preflight pin sync rebase apply report brand"

idx() { local i=0; for p in $PHASES; do [ "$p" = "$1" ] && { echo $i; return; }; i=$((i+1)); done; echo 999; }
FROM_I=$(idx "$FROM")
run() { [ "$(idx "$1")" -ge "$FROM_I" ]; }
hdr() { echo; echo "============================================================"; echo "  PHASE: $1"; echo "============================================================"; }
gate() {
  [ "${CEFRIUM_YES:-0}" = "1" ] && { echo "[gate] $1 — auto (CEFRIUM_YES=1)"; return; }
  echo; echo "*** DESTRUCTIVE: $1"
  printf "    Type the chromium tag (%s) to proceed, anything else aborts: " "$CTAG"
  read -r a; [ "$a" = "$CTAG" ] || { echo "Aborted."; exit 1; }
}

CUR_CTAG="$(git -C "$SRC" describe --tags 2>/dev/null || echo unknown)"

if run preflight; then hdr "preflight"
  echo "  current Chromium: $CUR_CTAG   ->   target: $CTAG   (CEF branch $CEFBR)"
  # Patches are safe only if the cef working tree is committed.
  if [ -n "$(git -C "$CEF" status --porcelain)" ]; then
    echo "  ERROR: cef tree has uncommitted changes — commit/stash before bumping (patches must be safe in git)."; exit 1
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
  git -C "$SRC" -c advice.detachedHead=false checkout "refs/tags/$CTAG"   # discards applied patches
  git -C "$SRC" clean -fd -e '/cef' -e '/out'                              # keep cef/ and out/
  ( cd "$ROOT/depot_tools" && cd "$SRC" && gclient sync --no-history -D )
  echo "  synced to $CTAG."
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
  FORK=$(git merge-base "$REBASE_REMOTE/$CEFBR" android-port 2>/dev/null || git merge-base origin/7778 android-port)
  echo "  Rebase (do it by hand so you control conflict resolution):"
  echo "    cd $CEF"
  echo "    git branch -f android-port-$CEFBR android-port && git checkout android-port-$CEFBR"
  echo "    git rebase --onto $REBASE_REMOTE/$CEFBR \$(git merge-base origin/7778 HEAD) android-port-$CEFBR"
  echo "  Resolution playbook (from the 7778->7827 bump):"
  echo "    - doc/README conflicts: 'git checkout --theirs <file>; git add <file>' (later"
  echo "      commits rebuild the final doc), then 'git rebase --continue'."
  echo "    - a commit that goes empty (its change is already in $CEFBR): 'git rebase --skip'."
  echo "    - real source conflicts: resolve by hand, then continue."
  echo "  When the rebase is clean, fast-forward android-port to it, then re-run:"
  echo "    $0 $CTAG $CEFBR --from apply"
  exit 0
fi

if run apply; then hdr "apply patches (patcher.py)"
  cd "$SRC"; export 'target_os=="android"'=1
  python3 cef/tools/patcher.py 2>&1 | tee /tmp/cefrium-patcher-$CTAG.log || true
fi

if run report; then hdr "patch conflict report"
  cd "$SRC"
  log="/tmp/cefrium-patcher-$CTAG.log"
  clean=0; conflict=0
  echo "  Per Android patch (the port's own; core CEF patches come from the rebased branch):"
  for p in cef/patch/patches/android/*.patch; do
    name="$(basename "$p" .patch)"
    if git apply --reverse --check "$p" 2>/dev/null; then
      echo "    OK       $name (applied)"; clean=$((clean+1))
    elif git apply --check "$p" 2>/dev/null; then
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
  echo "  Known-fuzz core CEF patches (Debian applies these with --fuzz=3; patcher.py"
  echo "  reports them 'failed' but they ARE applied — verify by a signature line, don't"
  echo "  treat as conflicts):"
  for kf in $KNOWN_FUZZ; do
    pf="cef/patch/patches/$kf.patch"
    [ -f "$pf" ] || { echo "    -        $kf (patch not present)"; continue; }
    tgt=$(grep -E '^\+\+\+ ' "$pf" | head -1 | sed 's/^+++ [ab]\///;s/\t.*//')
    tok=$(grep -E '^\+' "$pf" | grep -viE '^\+\+\+' | grep -iE 'cef' | head -1 | sed 's/^+//;s/^[[:space:]]*//')
    if [ -n "$tok" ] && grep -qF "$tok" "$tgt" 2>/dev/null; then
      echo "    APPLIED  $kf (signature present in $tgt)"
    else
      echo "    CHECK    $kf (signature absent — this one may be a real conflict from the rebase)"
    fi
  done
fi

# `--from report` is a read-only audit: stop before touching the brand mark.
[ "$FROM" = "report" ] && { echo; echo "(report-only run — no files changed)"; exit 0; }

if run brand; then hdr "brand mark (atomic number auto-follows CHROMIUM_VERSION)"
  ( cd "$ROOT/cefrium-sample" && ./regenerate-icons.sh ) || echo "  (run regenerate-icons.sh manually)"
fi

echo; echo "Engine-bump scaffolding done for $CTAG. Manual next: resolve any CONFLICT patches,"
echo "fix Chromium-149 API breaks at build time, then cut a release with cut-release.sh."
