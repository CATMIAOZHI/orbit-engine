#!/bin/bash
# rebase-assist.sh -- carry the engine-bump rebase of android-port, resolving
# only the conflicts that have one right answer, and stop on every other one.
#
#   cef/cefrium_sdk/rebase-assist.sh            # continue after a stop
#
# Start the rebase with the command engine-bump.sh --from rebase --to rebase
# prints, then run this after each manual resolution (it stages the resolved
# files itself). Mechanical resolutions, each logged in
# tmp/engine-bump/rebase-auto.log:
#   README.md         the replayed commit's (later commits rebuild it)
#   patch/patch.cfg   upstream's entries + the commit's android/ entries
#                     (rebase_patchcfg.py; stops if the commit edits an
#                     upstream entry)
#   patch/patches/<core>.patch   upstream's regenerated patch; the commit's
#                     change is saved in tmp/engine-bump/core-patch-reapply.diff,
#                     to redo with patch_updater after the apply phase
#   empty commit      skipped (its change is already upstream)
# Anything else stops and lists the conflicted files.
set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT="$(cd "$CEF/../../.." && pwd)"
LOG="$ROOT/tmp/engine-bump"
mkdir -p "$LOG"
cd "$CEF" || exit 1
[ -d .git/rebase-merge ] || { echo "no rebase in progress in $CEF"; exit 1; }

while :; do
  for f in $(git diff --name-only --diff-filter=U); do
    if grep -q '^<<<<<<< \|^>>>>>>> ' "$f" 2>/dev/null; then
      echo "UNRESOLVED: $f still has conflict markers"; exit 1
    fi
    git add -- "$f"
  done
  GIT_EDITOR=true git rebase --continue > "$LOG/rebase-last.log" 2>&1
  rc=$?
  if [ ! -d .git/rebase-merge ]; then
    echo "REBASE DONE (rc=$rc)"; tail -2 "$LOG/rebase-last.log"; exit "$rc"
  fi
  n=$(cat .git/rebase-merge/msgnum); total=$(cat .git/rebase-merge/end)
  subj="$(git log -1 --format='%h %s' REBASE_HEAD 2>/dev/null)"
  conflicts="$(git diff --name-only --diff-filter=U)"
  if [ -z "$conflicts" ]; then
    if grep -q "nothing to commit\|empty" "$LOG/rebase-last.log"; then
      echo "$n/$total empty, skipped: $subj" >> "$LOG/rebase-auto.log"
      GIT_EDITOR=true git rebase --skip > "$LOG/rebase-last.log" 2>&1
      continue
    fi
    echo "STOPPED at $n/$total (no conflict): $subj"; tail -5 "$LOG/rebase-last.log"; exit 1
  fi
  auto=1
  for f in $conflicts; do
    case "$f" in
      README.md|patch/patch.cfg) ;;
      patch/patches/android/*) auto=0 ;;
      patch/patches/*.patch) ;;
      *) auto=0 ;;
    esac
  done
  if [ "$auto" = 0 ]; then
    echo "STOPPED at $n/$total: $subj"
    echo "$conflicts" | sed 's/^/  conflict: /'
    exit 1
  fi
  for f in $conflicts; do
    case "$f" in
      README.md)
        git checkout --theirs -- README.md && git add README.md
        echo "$n/$total README.md: commit's: $subj" >> "$LOG/rebase-auto.log" ;;
      patch/patch.cfg)
        if ! out=$(python3 "$SCRIPT_DIR/rebase_patchcfg.py" 2>&1); then
          echo "STOPPED at $n/$total: $subj"; echo "  $out"; exit 1
        fi
        python3 -c "import ast; ast.parse(open('patch/patch.cfg').read())" \
          || { echo "STOPPED at $n/$total: patch.cfg does not parse"; exit 1; }
        git add patch/patch.cfg
        echo "$n/$total patch.cfg: $out: $subj" >> "$LOG/rebase-auto.log" ;;
      *)
        git checkout --ours -- "$f" && git add "$f"
        { echo "=== $n/$total $subj"; git show REBASE_HEAD -- "$f"; } \
          >> "$LOG/core-patch-reapply.diff"
        echo "$n/$total $f: upstream's, ours saved: $subj" >> "$LOG/rebase-auto.log" ;;
    esac
  done
done
