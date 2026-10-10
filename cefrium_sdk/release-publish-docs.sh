#!/bin/bash
# Commit and push everything a release changed in the two documentation repos.
#
#   cef/cefrium_sdk/release-publish-docs.sh <version> [--dry-run]
#
# Repos and what belongs to each:
#   cefrium-web (branch "pages")  -> the cefrium.com site: quickstart/, releases/,
#                                    docs/ (Javadoc), docs/compose/ (Dokka)
#   cef-android (branch android-port) -> the COMMITTED consumer docs:
#                                    QUICKSTART-CEFRIUM.md, README.md
#
# WHY THIS IS A SCRIPT
#
# The cut used to end by PRINTING "Review + commit the web repo AND the cef
# repo", leaving two hand-typed commit/push cycles per release. That is how the
# published Javadoc came to say "Cefrium 0.9.2" while 0.9.4 was shipping, with
# the 0.9.3 network opt-out API (setComponentUpdatesEnabled /
# setNetworkTimeQueriesEnabled) missing from the site entirely. Docs that are
# published by hand are docs that silently stop being published.
#
# PUSH SAFETY is the whole design here. This project has pushed to the wrong
# repo before, so each push is its own verified step: cd, print pwd, assert the
# remote URL matches the expected repo and the branch is the expected branch,
# and only then push. Never `cd a && push && cd b && push`.
set -uo pipefail

VERSION="${1:?usage: release-publish-docs.sh <version> [--dry-run]}"; shift || true
DRY=0
[ "${1:-}" = "--dry-run" ] && DRY=1

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="$(cd "$CEF_DIR/.." && pwd)"
ROOT="$(cd "$SRC/../.." && pwd)"
WEB="$ROOT/cefrium-web"
fail=0

# Publish one repo: stage only the paths that belong to it, show what changed,
# commit, then verify identity before pushing.
publish() { # <dir> <expected-remote-substring> <expected-branch> <commit-msg> <paths...>
  local dir="$1" want_remote="$2" want_branch="$3" msg="$4"; shift 4
  echo
  echo "--- $(basename "$dir") ---"
  if [ ! -d "$dir/.git" ]; then echo "  SKIP: $dir is not a git repo"; return; fi
  cd "$dir"

  # Stage only the expected paths. Never `git add -A`: these repos also hold
  # local scratch, and a release must not sweep in whatever happens to be there.
  local staged=0 p
  for p in "$@"; do
    [ -e "$p" ] || continue
    git add -- "$p" 2>/dev/null && staged=1
  done
  if [ "$staged" = "0" ] || git diff --cached --quiet; then
    echo "  nothing to publish (no changes in: $*)"
    return
  fi
  echo "  staged:"
  git diff --cached --stat | sed 's/^/    /' | tail -12

  if [ "$DRY" = "1" ]; then echo "  DRY RUN -- not committing or pushing"; git reset -q; return; fi

  git -c user.name="Juan Manuel Mendez Rey" -c user.email="juan.mendezr@proton.me" \
      commit -q -m "$msg" || { echo "  ERROR: commit failed"; fail=1; return; }
  echo "  committed $(git rev-parse --short HEAD)"

  # IDENTITY CHECK, immediately before the push and in this same directory.
  echo "  pwd:    $(pwd)"
  local remote branch
  remote="$(git remote get-url codeberg 2>/dev/null || true)"
  branch="$(git branch --show-current)"
  echo "  remote: ${remote:-<none>}"
  echo "  branch: $branch"
  case "$remote" in
    *"$want_remote"*) ;;
    *) echo "  ABORT: remote does not look like $want_remote -- not pushing"; fail=1; return ;;
  esac
  if [ "$branch" != "$want_branch" ]; then
    echo "  ABORT: on '$branch', expected '$want_branch' -- not pushing"; fail=1; return
  fi
  git push codeberg "$want_branch" 2>&1 | tail -2 | sed 's/^/    /' \
    || { echo "  ERROR: push failed"; fail=1; }
}

publish "$WEB" "cefrium/pages" "pages" \
  "Publish $VERSION: site, quickstart, releases and regenerated API docs" \
  quickstart releases docs index.html assets

publish "$CEF_DIR" "cefrium/cef-android" "android-port" \
  "Update consumer docs for $VERSION" \
  QUICKSTART-CEFRIUM.md README.md

echo
if [ "$fail" = "0" ]; then
  if [ "$DRY" = "1" ]; then
    echo "DRY RUN complete for $VERSION -- nothing was committed or pushed."
  else
    echo "Documentation published for $VERSION."
  fi
  exit 0
fi
echo "DOCUMENTATION NOT FULLY PUBLISHED -- see the ABORT/ERROR lines above."
exit 1
