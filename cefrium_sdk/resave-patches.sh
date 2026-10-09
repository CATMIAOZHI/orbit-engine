#!/bin/bash
# Regenerate CEF patches with upstream's own tool, in upstream's own format.
# Run from chromium/src or anywhere; paths are derived.
#
#   cef/cefrium_sdk/resave-patches.sh [--all | <patch-name>...] [--dry-run]
#
#     (no args)     every android/* patch that is safe to resave
#     --all         every registered patch, android or not
#     <patch-name>  a specific patch, named as patch.cfg registers it
#                   (e.g. "android/lock_metrics_reentrancy")
#     --dry-run     report what would be done, write nothing
#
# WHY NOT HAND-ROLLED PATCHES
#
# Upstream regenerates patches with `tools/patch_updater.py --resave`, which runs
#   git diff --no-prefix --relative --full-index
# Our old method (copy the pristine file into a workdir, git init, edit, git diff,
# then normalize-patch.sh to strip the a/b prefixes) reproduces the first two
# flags by hand and MISSES --full-index. That is not cosmetic:
#
#   - Full 40-hex index hashes are what let `git apply --3way` find the blobs, and
#     --3way is upstream's documented recovery path when a patch stops applying
#     after a Chromium update. Without them, every engine-bump conflict is manual.
#   - Abbreviated hashes are generated at whatever length was unambiguous that
#     day, so our patches carried 7- to 13-char hashes with no consistency, and
#     two carried no index line at all.
#   - Patches shaped by upstream's tool are submission-ready; patches shaped by
#     ours produce format churn the moment Marshall resaves them.
#
# Measured on android/android_info_gettid_guard: the resave upgraded the index to
# full hashes AND corrected a hunk header that had drifted by one line
# (@@ -50,9 -> @@ -51,9). It only survived because git tolerates fuzz.
#
# THE ONE RULE THIS TOOL CANNOT BREAK
#
# --resave regenerates a patch from the CURRENT WORKING TREE, so it attributes
# every change in a file to the patch being saved. Where two patches touch the
# same file, resaving one silently absorbs the other's hunks. (That is exactly
# how resource_bundle_2512.patch once swallowed the Android changes and broke a
# clean apply.) So this script computes file ownership across ALL patches first
# and refuses to touch any patch that shares a file. Those are listed as SKIPPED
# with the patch they collide with, and must be regenerated deliberately by hand.
#
# ABOUT THE `git checkout HEAD -- <file>` BELOW
#
# The project rule is never to checkout/reset in chromium/src, because an
# unscoped one wipes every applied patch. This one is different in kind and is
# the only way to PROVE a regenerated patch is equivalent: it is scoped to the
# files one single-owner patch targets, and the very next command re-applies a
# patch to them. Any failure path calls restore(), which puts back the pristine
# file plus the backed-up patch. If the script is interrupted between the two
# steps, the recovery is the normal one:
#
#   python3 cef/cefrium_sdk/apply-patches.py
#
# plus restoring any patch file from the backup directory printed at the start.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CEF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$(cd "$CEF_DIR/.." && pwd)"
PATCHES="$CEF_DIR/patch/patches"
BACKUP_ROOT="$(cd "$SRC_DIR/../.." && pwd)/tmp"

DRY=0; SCOPE="android"; NAMED=()
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1; shift ;;
    --all) SCOPE="all"; shift ;;
    -*) echo "unknown flag: $1" >&2; exit 2 ;;
    *) NAMED+=("$1"); SCOPE="named"; shift ;;
  esac
done

cd "$SRC_DIR"

# 1. Ownership map across every patch, so a shared file can be refused.
OWNERSHIP="$(mktemp)"; trap 'rm -f "$OWNERSHIP"' EXIT
python3 - "$PATCHES" > "$OWNERSHIP" <<'PY'
import glob, os, sys, collections
root = sys.argv[1]
owner = collections.defaultdict(set)
for p in glob.glob(os.path.join(root, "*.patch")) + \
         glob.glob(os.path.join(root, "*", "*.patch")):
    name = os.path.relpath(p, root)[:-len(".patch")]
    for line in open(p, encoding="utf-8", errors="replace"):
        if line.startswith("--- ") and "/dev/null" not in line:
            f = line[4:].split("\t")[0].strip()
            if f:
                owner[f].add(name)
# Emit "patch<TAB>colliding-patch<TAB>file" for every shared file.
for f, names in sorted(owner.items()):
    if len(names) > 1:
        for n in sorted(names):
            others = ",".join(sorted(names - {n}))
            print("%s\t%s\t%s" % (n, others, f))
PY

shares_with() { awk -F'\t' -v n="$1" '$1==n {print $2"\t"$3; exit}' "$OWNERSHIP"; }

# 2. Which patches to process, named as patch.cfg registers them.
registered() { grep -oE "'name': '[^']+'" "$CEF_DIR/patch/patch.cfg" | cut -d"'" -f4; }
case "$SCOPE" in
  named) TARGETS=("${NAMED[@]}") ;;
  all)   mapfile -t TARGETS < <(registered) ;;
  *)     mapfile -t TARGETS < <(registered | grep '^android/') ;;
esac
[ "${#TARGETS[@]}" -gt 0 ] || { echo "ERROR: no patches selected" >&2; exit 2; }

# 3. Back up before writing anything. Dated, on the workspace NVMe (not /tmp,
#    which is tmpfs and gone on reboot).
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP="$BACKUP_ROOT/patch-backup-$STAMP"
if [ "$DRY" -eq 0 ]; then
  mkdir -p "$BACKUP"
  for n in "${TARGETS[@]}"; do
    [ -f "$PATCHES/$n.patch" ] || continue
    mkdir -p "$BACKUP/$(dirname "$n")"
    cp "$PATCHES/$n.patch" "$BACKUP/$n.patch"
  done
  echo "Backup: $BACKUP ($(find "$BACKUP" -name '*.patch' | wc -l) patches)"
else
  echo "DRY RUN -- no backup taken, nothing written"
fi
echo

ok=0; changed=0; skipped=0; restored=0; missing=0
for n in "${TARGETS[@]}"; do
  f="$PATCHES/$n.patch"
  if [ ! -f "$f" ]; then
    printf "MISSING  %-52s (registered but no file)\n" "$n"; missing=$((missing+1)); continue
  fi
  coll="$(shares_with "$n")"
  if [ -n "$coll" ]; then
    printf "SKIPPED  %-52s shares %s with %s\n" "$n" \
      "$(printf '%s' "$coll" | cut -f2)" "$(printf '%s' "$coll" | cut -f1)"
    skipped=$((skipped+1)); continue
  fi
  if [ "$DRY" -eq 1 ]; then
    printf "would resave  %s\n" "$n"; ok=$((ok+1)); continue
  fi

  # Files this patch owns. Safe to revert to HEAD because the collision check
  # above proved no other patch touches them.
  mapfile -t pfiles < <(grep "^--- " "$f" | grep -v /dev/null | sed 's/^--- //;s/\t.*//')
  if [ "${#pfiles[@]}" -eq 0 ]; then
    printf "SKIPPED  %-52s no target files parsed\n" "$n"; skipped=$((skipped+1)); continue
  fi
  before="$(sha256sum "${pfiles[@]}" 2>/dev/null | awk '{print $1}' | sha256sum)"

  python3 "$CEF_DIR/tools/patch_updater.py" --resave --patch "$n" >/dev/null 2>&1

  restore() { # put the tree and the patch file back exactly as they were
    git checkout HEAD -- "${pfiles[@]}" 2>/dev/null
    cp "$BACKUP/$n.patch" "$f"
    git apply -p0 "$f" 2>/dev/null
  }

  if [ ! -s "$f" ]; then
    restore
    printf "RESTORED %-52s resave produced an EMPTY patch (change already upstream?)\n" "$n"
    restored=$((restored+1)); continue
  fi

  # EQUIVALENCE BY TREE, NOT BY TEXT.
  #
  # Comparing patch text cannot decide this. A correct resave legitimately
  # rewrites hunk headers (our stored offsets had drifted -- one patch still
  # carried `extends AsyncInitializationActivity` from an older milestone), and
  # git may pick a different but equivalent -/+ split for the same edit. Both
  # look like "content changed" to a text diff and neither is.
  #
  # So revert the files to pristine, apply the REGENERATED patch, and require
  # the resulting bytes to be identical to what the old patch produced. That is
  # the only claim that matters: same tree, better-formed patch.
  git checkout HEAD -- "${pfiles[@]}" 2>/dev/null
  if ! git apply -p0 "$f" 2>/dev/null; then
    restore
    printf "RESTORED %-52s regenerated patch does not apply to a pristine tree\n" "$n"
    restored=$((restored+1)); continue
  fi
  after="$(sha256sum "${pfiles[@]}" 2>/dev/null | awk '{print $1}' | sha256sum)"
  if [ "$before" = "$after" ]; then
    if cmp -s "$BACKUP/$n.patch" "$f"; then
      printf "ok       %-52s already in upstream format\n" "$n"
    else
      printf "RESAVED  %-52s same tree, upstream format\n" "$n"
      changed=$((changed+1))
    fi
    ok=$((ok+1))
  else
    restore
    printf "RESTORED %-52s DIFFERENT TREE -- inspect by hand\n" "$n"
    restored=$((restored+1))
  fi
done

echo
echo "resaved $changed, unchanged $((ok-changed)), skipped $skipped, restored $restored, missing $missing"
[ "$DRY" -eq 1 ] && exit 0

# 4. The patches must still be in CEF's -p0 format and must still apply.
echo
echo "--- format gate ---"
"$SCRIPT_DIR/check-patch-format.sh" || exit 1
echo "--- reverse-apply check (proves each patch matches the tree it describes) ---"
bad=0
for n in "${TARGETS[@]}"; do
  f="$PATCHES/$n.patch"
  [ -f "$f" ] || continue
  git apply -p0 --check --reverse "$f" 2>/dev/null \
    || { echo "  FAILS to reverse-apply: $n"; bad=$((bad+1)); }
done
[ "$bad" -eq 0 ] && echo "  all selected patches reverse-apply cleanly" \
                 || { echo "  $bad patch(es) do not match the tree -- restore from $BACKUP"; exit 1; }
echo
echo "Backup kept at $BACKUP"
