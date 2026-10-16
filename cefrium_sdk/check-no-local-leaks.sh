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
# The needles that would themselves name something private (a vendor, the real
# credential filenames, a host) are NOT in this file -- it is published, so
# writing them here leaks exactly what it defends, and the first version of
# this script did. They come from a local-only list; see PRIVATE_LIST.
#
# Usage:
#   check-no-local-leaks.sh              # scan every tracked file
#   check-no-local-leaks.sh --staged     # scan the staged diff (pre-commit)
#   check-no-local-leaks.sh --install-hook      # install as .git/hooks/pre-commit
#   check-no-local-leaks.sh --require-private   # fail if the private list is absent
# Exit: 0 clean, 1 something would leak, 2 usage / required list missing.
set -uo pipefail

MODE="all"
REQUIRE_PRIVATE=0
for a in "$@"; do
  case "$a" in
    --staged) MODE="staged" ;;
    --install-hook) MODE="install" ;;
    --require-private) REQUIRE_PRIVATE=1 ;;
    *) echo "usage: $0 [--staged|--install-hook] [--require-private]" >&2; exit 2 ;;
  esac
done

SELF_PATH="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
REPO="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "ERROR: not inside a git repository" >&2; exit 2; }
cd "$REPO"

if [ "$MODE" = "install" ]; then
  HOOK="$(git rev-parse --git-path hooks/pre-commit)"
  printf '%s\n' '#!/bin/sh' "exec \"$SELF_PATH\" --staged" > "$HOOK"
  chmod +x "$HOOK"
  echo "installed $HOOK"
  exit 0
fi

# What must never appear in a published file: pattern @@ reason @@ extra paths
# that are legitimately exempt for THAT pattern. A failure explains itself, and
# per-pattern exemptions keep it from crying wolf -- a gate that reports known
# non-problems is a gate people learn to ignore.
PATTERNS=(
  # Upstream CEF and Chromium write illustrative `/home/user/...` paths in their
  # own docs and tests, so the generic form is scoped to the files we author.
  # The path that identifies THIS machine is a private needle, checked
  # everywhere, which is what makes the check exhaustive.
  "/home/[a-z][a-z0-9_-]*/@@an absolute home path -- derive it from \$(dirname \$0) or require an env var@@^docs/|^tools/|^libcef_dll/|^libcef/|^include/|^tests/|^patch/patches/[a-z]"
  # Credential filenames as a class: a dotfile named `<word>-token` reads as
  # the file holding one. Naming the specific ones here would publish them.
  "[~/.][a-z][a-z0-9]*-token@@the filename of a credential file -- take the path from an env var@@"
  # `AAAA` because every real key body starts with it. Without it the pattern
  # matches this very line, and a gate that flags itself gets switched off.
  "ssh-rsa AAAA@@an SSH public key@@testdata/|test/data/"
  # Upstream CEF and Chromium ship test fixtures that are deliberately private
  # keys (e.g. libcef_dll/bootstrap/installer/testdata/loopback_tls_key.pem,
  # committed upstream for the e2e installer test). Those are not ours to flag.
  "BEGIN [A-Z ]*PRIVATE KEY@@a private key@@testdata/|test/data/|tests/"
)
N_BUILTIN="${#PATTERNS[@]}"

# Needles that cannot be written down in a published file: the hardware vendor,
# the account and host names, the real credential filenames, this machine's home
# path. Format is the same `pattern@@reason@@exempt-paths`, one per line, `#`
# comments ignored. Keep the list OUTSIDE every repo -- the default sits next to
# the checkouts, not in one.
PRIVATE_LIST="${CEFRIUM_LEAK_PATTERNS:-$(cd "$(dirname "$0")/../../../.." 2>/dev/null && pwd)/LOCAL-LEAK-PATTERNS}"
PRIVATE_N=0
if [ -f "$PRIVATE_LIST" ]; then
  while IFS= read -r line; do
    case "$line" in ""|\#*) continue ;; esac
    case "$line" in *@@*) PATTERNS+=("$line") ;; *) PATTERNS+=("$line@@a private needle@@") ;; esac
    PRIVATE_N=$((PRIVATE_N+1))
  done < "$PRIVATE_LIST"
elif [ "$REQUIRE_PRIVATE" = "1" ]; then
  echo "ERROR: --require-private, but there is no list at $PRIVATE_LIST" >&2
  echo "       point CEFRIUM_LEAK_PATTERNS at it; without it this gate checks" >&2
  echo "       the generic classes only." >&2
  exit 2
fi

# Deliberately public, and not a leak: authorship and contact metadata.
ALLOW_FILES="LICENSE-CEFRIUM.md|LICENSES/|REUSE.toml|AUTHORS"

# This file necessarily spells out the GENERIC patterns, so it is exempt from
# those -- and from nothing else. The blanket self-exemption it used to have is
# what let it carry a vendor name in cleartext in a public repo.
SELF="check-no-local-leaks.sh"

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

# 2. Text patterns. One `git grep` per pattern, NOT one `grep` per file per
#    pattern: this tree has ~20k tracked files and the nested form took minutes,
#    which is long enough that a pre-commit hook gets bypassed.
for i in "${!PATTERNS[@]}"; do
  entry="${PATTERNS[$i]}"
  pat="${entry%%@@*}"
  rest="${entry#*@@}"
  why="${rest%%@@*}"
  extra="${rest#*@@}"
  allow="$ALLOW_FILES"
  [ "$i" -lt "$N_BUILTIN" ] && allow="$allow|$SELF"
  [ -n "$extra" ] && allow="$allow|$extra"
  if [ "$MODE" = "staged" ]; then
    RAW="$(git grep --cached -I -n -E -e "$pat" -- $FILES 2>/dev/null || true)"
  else
    RAW="$(git grep -I -n -E -e "$pat" 2>/dev/null || true)"
  fi
  # git grep prints `path:line:text`, and the exemption applies to the PATH
  # only -- matched against the whole line, a file could excuse itself by
  # quoting an exempt path in its own text.
  HITS=""
  while IFS= read -r hit; do
    [ -n "$hit" ] || continue
    p="${hit%%:*}"
    printf '%s' "$p" | grep -qE "$allow" && continue
    HITS="$HITS$hit
"
  done <<< "$RAW"
  if [ -n "$HITS" ]; then
    echo "FAIL  $why"
    printf '%s' "$HITS" | head -5 | cut -c1-140 | sed 's/^/        /'
    FAIL=1
  fi
done

# Say which list ran. A run without the private needles is weaker than one with
# them, and the two must never look alike in the output.
if [ "$PRIVATE_N" -gt 0 ]; then
  echo "      (+$PRIVATE_N private needle(s) from ${PRIVATE_LIST##*/})"
else
  echo "      (generic classes only -- no private needle list at $PRIVATE_LIST)"
fi

if [ "$FAIL" = "0" ]; then
  echo "OK: nothing machine-specific in $( [ "$MODE" = staged ] && echo "the staged changes" || echo "$(printf '%s\n' "$FILES" | wc -l) tracked files")."
else
  echo
  echo "Nothing about this machine belongs in a published repo. Put the real"
  echo "values in your local notes and read them from the environment."
fi
exit "$FAIL"
