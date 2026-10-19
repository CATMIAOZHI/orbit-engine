#!/bin/bash
# Print the "areas touched" sentence fragment for cefrium.com/upstream/, derived
# from the Android patch set.
#
#   cef/cefrium_sdk/patch-area-breakdown.sh            # the HTML fragment
#   cef/cefrium_sdk/patch-area-breakdown.sh --files    # total distinct files
#
# One script, two callers: release-web-update.sh writes the sentence and
# release-web-verify.sh asserts the published page carries exactly this string.
# A second copy of the generator would drift from the first, which is how the
# page came to claim "components (11)" after the count had moved to 12.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APD="$SCRIPT_DIR/../patch/patches/android"
MODE="html"; [ "${1:-}" = "--files" ] && MODE="files"

# One line per distinct Chromium file the Android patches touch.
touched() {
  grep -h '^--- ' "$APD"/*.patch | grep -v '/dev/null' \
    | sed 's/^--- //; s/\t.*//' | sort -u
}

if [ "$MODE" = "files" ]; then touched | wc -l | tr -d ' '; exit 0; fi

# Counts per top-level directory, biggest first, name as the tiebreak so the
# output is stable between runs.
touched | cut -d/ -f1 | sort | uniq -c | sort -k1,1nr -k2,2 | awk '
  { n[NR]=$1; a[NR]=$2; total=NR }
  END {
    out=""; onec=0
    for (i=1; i<=total; i++) {
      if (n[i] > 1) {
        s = "<code>" a[i] "</code> (" n[i] (out=="" ? " files" : "") ")"
        out = (out=="" ? s : out ", " s)
      } else { onec++; o[onec]=a[i] }
    }
    if (onec == 1) tail = "<code>" o[1] "</code> (1)"
    else if (onec > 1) {
      tail = ""
      for (j=1; j<=onec; j++) {
        pre = (j==1 ? "" : (j==onec ? " and " : ", "))
        tail = tail pre "<code>" o[j] "</code>"
      }
      tail = tail " (1 each)"
    }
    if (tail != "") out = (out=="" ? tail : out ", " tail)
    print out
  }'
