#!/bin/bash
# check-no-local-leaks.sh -- refuse to publish anything that describes this
# machine: home paths, usernames, credential filenames, signing hardware, or
# build artifacts that embed a build path.
#
# WHY: on 2026-10-16 an audit found the public repo carried absolute
# `/home/<user>/...` paths in scripts and runbooks, the names of the files
# holding the publish tokens, the model of the key used for signing, and -- the
# one no human review would ever catch -- 13 committed `__pycache__/*.pyc`
# blobs with the build path embedded in BINARY. `*.pyc` was already in
# .gitignore; that does nothing for files already tracked.
#
# Vigilance does not scale and a cleanup is not a control. This is the control.
#
# Usage:
#   check-no-local-leaks.sh              # scan every tracked file
#   check-no-local-leaks.sh --staged     # scan the staged diff (pre-commit)
#   check-no-local-leaks.sh --install-hook   # install it as .git/hooks/pre-commit
# Exit: 0 clean, 1 something would leak.
set -uo pipefail

MODE="all"
case "${1:-}" in
  --staged) MODE="staged" ;;
  --install-hook) MODE="install" ;;
  "") ;;
  *) echo "usage: $0 [--staged|--install-hook]" >&2; exit 2 ;;
esac

REPO="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "ERROR: not inside a git repository" >&2; exit 2; }
cd "$REPO"

if [ "$MODE" = "install" ]; then
  HOOK="$(git rev-parse --git-path hooks/pre-commit)"
  printf '%s\n' '#!/bin/sh' \
    "exec \"$(cd "$(dirname "$0")" && pwd)/$(basename "$0")\" --staged" > "$HOOK"
  chmod +x "$HOOK"
  echo "installed $HOOK"
  exit 0
fi

# What must never appear in a published file: pattern @@ reason @@ extra paths
# that are legitimately exempt for THAT pattern. A failure explains itself, and
# per-pattern exemptions keep it from crying wolf -- a gate that reports known
# non-problems is a gate people learn to ignore.
PATTERNS=(
  "/home/[a-z][a-z0-9_-]*/@@an absolute home path -- derive it from \$(dirname \$0) or require an env var@@"
  "registry-token@@the filename holding a publish token -- take the path from an env var@@"
  "publish-token@@the filename holding a publish token -- take the path from an env var@@"
  "hardware signing key@@names the signing hardware -- say 'hardware signing key'@@"
  "ssh-rsa @@an SSH public key@@testdata/|test/data/"
  # Upstream CEF and Chromium ship test fixtures that are deliberately private
  # keys (e.g. libcef_dll/bootstrap/installer/testdata/loopback_tls_key.pem,
  # committed upstream for the e2e installer test). Those are not ours to flag.
  "BEGIN [A-Z ]*PRIVATE KEY@@a private key@@testdata/|test/data/|tests/"
)

# Deliberately public, and not a leak: authorship and contact metadata. And
# this script itself, which necessarily contains every pattern it hunts for.
ALLOW_FILES="LICENSE-CEFRIUM.md|LICENSES/|REUSE.toml|README.md|AUTHORS|check-no-local-leaks.sh"

if [ "$MODE" = "staged" ]; then
  FILES="$(git diff --cached --name-only --diff-filter=ACMR)"
else
  FILES="$(git ls-files)"
fi
[ -n "$FILES" ] || { echo "nothing to scan"; exit 0; }

FAIL=0

# 1. Binary artifacts that embed a build path. These are the dangerous ones:
#    invisible to review, and .gitignore does not cover what is already tracked.
BIN="$(printf '%s\n' "$FILES" | grep -E '(__pycache__/|\.pyc$|\.pyo$)' || true)"
if [ -n "$BIN" ]; then
  echo "FAIL  compiled Python is tracked -- .pyc embeds the absolute build path:"
  printf '%s\n' "$BIN" | sed 's/^/        /'
  echo "      git rm -r --cached <path>   (they are regenerable)"
  FAIL=1
fi

# 2. Text patterns.
for entry in "${PATTERNS[@]}"; do
  pat="${entry%%@@*}"
  rest="${entry#*@@}"
  why="${rest%%@@*}"
  extra="${rest#*@@}"
  allow="$ALLOW_FILES"
  [ -n "$extra" ] && allow="$allow|$extra"
  HITS=""
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    printf '%s' "$f" | grep -qE "$allow" && continue
    m="$(grep -nE "$pat" "$f" 2>/dev/null | head -3)" || true
    [ -n "$m" ] && HITS="$HITS$(printf '\n        %s: %s' "$f" "$(printf '%s' "$m" | head -1)")"
  done <<< "$FILES"
  if [ -n "$HITS" ]; then
    echo "FAIL  $why$HITS"
    FAIL=1
  fi
done

if [ "$FAIL" = "0" ]; then
  echo "OK: nothing machine-specific in $( [ "$MODE" = staged ] && echo "the staged changes" || echo "$(printf '%s\n' "$FILES" | wc -l) tracked files")."
else
  echo
  echo "Nothing about this machine belongs in a published repo. Put the real"
  echo "values in your local notes and read them from the environment."
fi
exit "$FAIL"
