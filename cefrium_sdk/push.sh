#!/bin/bash
# push.sh -- push one Cefrium repo to Codeberg, safely and the same way every time.
#
# WHY THIS EXISTS: pushing was the last step still typed by hand, and it is the
# step with the most ways to go wrong. All of these have actually happened:
#   - pushing from the wrong directory after a chained `cd` failed silently;
#   - a push rejected with "shallow update not allowed", because a
#     `git fetch --depth=...` had re-shallowed the clone (see engine-bump.sh);
#   - a force push with no backup of what it was about to make unreachable.
# Each time the recovery was improvised, which is why it felt like the rules
# changed. They do not. This script IS the rule.
#
# Usage:
#   cef/cefrium_sdk/push.sh <repo> [--force] [--dry-run]
#     repo = cef | sample | browser | web
#
#   --force    allowed only when the histories have diverged (after a rebase).
#              Pushes a timestamped backup branch at the CURRENT remote head
#              first, then uses --force-with-lease pinned to it.
#   --dry-run  do every check and print what WOULD be pushed; push nothing.
#
# One repo per invocation, on purpose: never chain two pushes.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"        # chromium/src
ROOT="$(cd "$SRC/../.." && pwd)"              # cef-dev

REPO_KEY="${1:-}"; shift || true
FORCE=0; DRY=0
for a in "$@"; do
  case "$a" in
    --force) FORCE=1 ;;
    --dry-run) DRY=1 ;;
    *) echo "unknown option: $a" >&2; exit 2 ;;
  esac
done

case "$REPO_KEY" in
  cef)     DIR="$SRC/cef";              BRANCH="android-port"; SLUG="cef-android" ;;
  sample)  DIR="$ROOT/cefrium-sample";  BRANCH="main";         SLUG="cefrium-sample" ;;
  browser) DIR="$ROOT/cefrium-browser"; BRANCH="main";         SLUG="cefrium-browser" ;;
  web)     DIR="$ROOT/cefrium-web";     BRANCH="pages";        SLUG="pages" ;;
  *)
    cat >&2 <<EOF
usage: push.sh <repo> [--force] [--dry-run]

  cef      $SRC/cef                -> cefrium/cef-android      (android-port)
  sample   $ROOT/cefrium-sample    -> cefrium/cefrium-sample   (main)
  browser  $ROOT/cefrium-browser   -> cefrium/cefrium-browser  (main)
  web      $ROOT/cefrium-web       -> cefrium/pages            (pages)

Only these four are pushable. Anything else -- in particular the local-only
upstream-patches archive -- is not, and must not be given a remote.
EOF
    exit 2 ;;
esac

say() { echo ">>> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

# ---- verify we are where we think we are -------------------------------
[ -d "$DIR/.git" ] || die "$DIR is not a git repository"
cd "$DIR"
say "repo:   $(pwd)"

REMOTE_URL="$(git remote get-url codeberg 2>/dev/null || true)"
[ -n "$REMOTE_URL" ] || die "no 'codeberg' remote in $DIR"
say "remote: $REMOTE_URL"
case "$REMOTE_URL" in
  *"$SLUG"*) ;;
  *) die "the codeberg remote is $REMOTE_URL, which is not $SLUG -- wrong repo" ;;
esac

CURRENT="$(git rev-parse --abbrev-ref HEAD)"
[ "$CURRENT" = "$BRANCH" ] || die "on branch '$CURRENT', expected '$BRANCH'"
say "branch: $BRANCH"

if [ -n "$(git status --porcelain)" ]; then
  echo
  echo "NOTE: uncommitted changes -- these are NOT pushed:"
  git status --porcelain | sed 's/^/      /'
  echo
fi

# ---- a shallow clone cannot push --------------------------------------
# Codeberg answers "shallow update not allowed". Repair it here rather than
# letting the push fail at the end of a long job.
if [ -f .git/shallow ]; then
  say "clone is shallow -- deepening (Codeberg rejects shallow pushes)"
  if [ "$DRY" = "1" ]; then
    echo "    [dry-run] would run: git fetch --unshallow origin"
  else
    git fetch --unshallow origin || git fetch --unshallow codeberg \
      || die "could not deepen the clone; fix before pushing"
  fi
fi

# ---- what are we about to do? -----------------------------------------
say "fetching $SLUG"
git fetch codeberg --quiet

LOCAL="$(git rev-parse HEAD)"
if git rev-parse --verify --quiet "codeberg/$BRANCH" >/dev/null; then
  REMOTE="$(git rev-parse "codeberg/$BRANCH")"
else
  REMOTE=""
fi

if [ -z "$REMOTE" ]; then
  say "remote branch does not exist yet -- this creates it"
  NEED_FORCE=0
elif [ "$LOCAL" = "$REMOTE" ]; then
  say "already in sync ($LOCAL) -- nothing to push"
  exit 0
elif git merge-base --is-ancestor "$REMOTE" HEAD; then
  AHEAD="$(git rev-list --count "$REMOTE"..HEAD)"
  say "fast-forward: $AHEAD commit(s) ahead, no force needed"
  NEED_FORCE=0
else
  DROPPED="$(git rev-list --count HEAD.."$REMOTE")"
  AHEAD="$(git rev-list --count "$REMOTE"..HEAD)"
  say "HISTORIES DIVERGED: +$AHEAD local, $DROPPED commit(s) only on the remote"
  NEED_FORCE=1
fi

if [ "$NEED_FORCE" = "1" ] && [ "$FORCE" != "1" ]; then
  cat >&2 <<EOF

This push would overwrite $DROPPED commit(s) that exist only on the remote.
That is normal right after a rebase (an engine bump rewrites the branch), and
wrong in every other case. If it is the expected one, re-run with --force:

  cef/cefrium_sdk/push.sh $REPO_KEY --force

EOF
  exit 1
fi

if [ "$DRY" = "1" ]; then
  [ "$NEED_FORCE" = "1" ] && HOW=" (forced, after a backup branch)" || HOW=""
  say "[dry-run] would push $LOCAL -> codeberg/$BRANCH$HOW"
  exit 0
fi

# ---- back the remote up before making anything unreachable -------------
if [ "$NEED_FORCE" = "1" ]; then
  BACKUP="$BRANCH-backup-$(date +%Y%m%d-%H%M%S)"
  say "backing the current remote head up as '$BACKUP' ($REMOTE)"
  git push codeberg "$REMOTE:refs/heads/$BACKUP" \
    || die "backup push failed -- NOT force-pushing without it"
fi

# ---- push ---------------------------------------------------------------
if [ "$NEED_FORCE" = "1" ]; then
  # Lease pinned to the ref we just fetched: if anyone else moved the branch
  # in between, this fails instead of overwriting their work.
  git push --force-with-lease="$BRANCH:$REMOTE" codeberg "$BRANCH"
else
  git push codeberg "$BRANCH"
fi

# ---- verify, do not assume ---------------------------------------------
git fetch codeberg --quiet
AFTER="$(git rev-parse "codeberg/$BRANCH")"
[ "$AFTER" = "$LOCAL" ] || die "push reported success but the remote is at $AFTER, expected $LOCAL"
say "OK: codeberg/$BRANCH is at $LOCAL"
[ "${BACKUP:-}" = "" ] || say "backup branch on the remote: $BACKUP"
