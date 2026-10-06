#!/bin/bash
# Audit what the engine did on the network, by reading the profile a consumer app
# left on a device. This is the FEATURE-067 validation gate and the method behind
# the numbers in PLAY-LISTING-DRAFT / cefrium.com/privacy.
#
# WHY READ THE PROFILE AND NOT LOGCAT
#
# A server-assigned value on disk proves a request happened and got an answer; a
# log line proves only that code ran. It also works on devices that suppress app
# logcat entirely (the W90 does), which is where this gets run.
#
# WHAT IT CHECKS
#
#   1. updateclientdata.apps        -- which components registered (server cohorts)
#   2. installed component versions -- which ones actually downloaded a payload
#   3. network_time_mapping         -- whether the time query ran
#
# The expected-to-be-kept component ids are PARSED OUT OF
# cef/libcef/browser/component_updater_filter.cc rather than copied here, so this
# script cannot drift from the filter it is checking. If that file moves, this
# fails loudly instead of silently checking a stale list.
#
# Usage:
#   audit-engine-network.sh <package>            e.g. com.cefrium.browser
#   audit-engine-network.sh <package> --raw      also dump the matching prefs
#
# The app must be debuggable (run-as needs it). Pass the command to run-as
# DIRECTLY: `run-as <pkg> sh -c '...'` lands in a different SELinux domain and is
# denied.
set -uo pipefail

PKG="${1:?usage: audit-engine-network.sh <package> [--raw]}"
RAW=0; [ "${2:-}" = "--raw" ] && RAW=1

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FILTER_CC="$SCRIPT_DIR/../libcef/browser/component_updater_filter.cc"
ADB="${ADB:-${ANDROID_HOME:-}/platform-tools/adb}"
[ -x "$ADB" ] || ADB=adb

say() { printf '%s\n' "$*"; }
hdr() { say; say "=== $* ==="; }

# ---------------------------------------------------------------- expected list
[ -f "$FILTER_CC" ] || { say "ERROR: cannot find $FILTER_CC -- the allowlist source."; exit 1; }
# Keep-ids are the 32-char quoted strings inside the kKeep array. Take only lines
# that look like an id followed by a // reason, which is the file's own format.
KEEP_IDS=$(grep -oE '"[a-p]{32}"' "$FILTER_CC" | tr -d '"' | sort -u)
KEEP_N=$(printf '%s\n' "$KEEP_IDS" | grep -c . || true)
# The rejected ones are recorded as a comment block; pull them for the report.
DROP_IDS=$(grep -oE '^//   [a-p]{32}' "$FILTER_CC" | awk '{print $2}' | sort -u)
DROP_N=$(printf '%s\n' "$DROP_IDS" | grep -c . || true)

[ "$KEEP_N" -gt 0 ] || { say "ERROR: parsed 0 keep-ids from $FILTER_CC (format changed?)."; exit 1; }

say "Cefrium engine network audit"
say "  package:    $PKG"
say "  allowlist:  $KEEP_N kept, $DROP_N recorded-as-dropped (from component_updater_filter.cc)"

# ------------------------------------------------------------------ device read
"$ADB" get-state >/dev/null 2>&1 || { say "ERROR: no device (adb get-state failed)."; exit 1; }

# Local State lives in the cache dir the SDK configures. Try the known locations
# rather than assuming one, and say which answered.
STATE=""
for cand in "cache/Local State" "app_cefrium/Local State" "files/Local State"; do
  # The path contains a space and `adb shell` hands the whole line to the device's
  # sh, which word-splits it before run-as ever sees it. Quote for THAT shell, not
  # just for this one, or you silently read nothing.
  if out=$("$ADB" shell run-as "$PKG" cat "'$cand'" 2>/dev/null) && [ -n "$out" ]; then
    STATE="$out"; say "  Local State: $cand"; break
  fi
done
if [ -z "$STATE" ]; then
  say "ERROR: could not read Local State for $PKG."
  say "       Is the app debuggable, and has it been launched at least once?"
  say "       Tried: cache/, app_cefrium/, files/"
  exit 1
fi

# -------------------------------------------------------- 1. registered compo.
hdr "components that registered (updateclientdata.apps)"
# The prefs are one JSON blob; pull the app-id keys under updateclientdata.apps.
REGISTERED=$(printf '%s' "$STATE" \
  | python3 -c '
import json,sys
try:
    d = json.load(sys.stdin)
except Exception as e:
    sys.stderr.write("could not parse Local State as JSON: %s\n" % e); sys.exit(2)
apps = (d.get("updateclientdata") or {}).get("apps") or {}
for k in sorted(apps):
    print(k)
' 2>/dev/null)
REG_N=$(printf '%s\n' "$REGISTERED" | grep -c . || true)
say "  $REG_N registered"

fail=0
hdr "KEPT components -- each MUST be present (a missing security feed is a FAIL)"
for id in $KEEP_IDS; do
  reason=$(grep -F "\"$id\"" "$FILTER_CC" | sed 's|.*// *||')
  if printf '%s\n' "$REGISTERED" | grep -qx "$id"; then
    say "  OK       $id  $reason"
  else
    say "  MISSING  $id  $reason"
    fail=$((fail+1))
  fi
done

hdr "DROPPED components -- each must be absent"
leak=0
for id in $DROP_IDS; do
  if printf '%s\n' "$REGISTERED" | grep -qx "$id"; then
    say "  STILL THERE  $id"
    leak=$((leak+1))
  else
    say "  absent       $id"
  fi
done

# Anything registered that is in neither list is new upstream surface: the whole
# point of an allowlist is that this shows up as a decision to make.
hdr "registered but in NEITHER list (new upstream components?)"
unknown=0
for id in $REGISTERED; do
  if ! printf '%s\n' "$KEEP_IDS" | grep -qx "$id" && ! printf '%s\n' "$DROP_IDS" | grep -qx "$id"; then
    say "  UNKNOWN  $id"
    unknown=$((unknown+1))
  fi
done
[ "$unknown" -eq 0 ] && say "  (none)"

# ------------------------------------------------- 2. installed payload dirs
# A registration only proves the engine asked. An installed payload proves it got
# an answer and wrote it. The payload dirs are named after the COMPONENT, not its
# id (CertificateRevocation, PKIMetadata, ...), and each holds a version-numbered
# subdirectory. Rather than carry a second id->name mapping that could drift, look
# for that shape: a directory whose children include a version-looking name.
hdr "installed component payloads (proves a download, not just a check)"
PROFILE_DIR=$(dirname "$(printf '%s' "cache/Local State")")
listing=$("$ADB" shell run-as "$PKG" ls "$PROFILE_DIR" 2>/dev/null | tr -d '\r')
installed=0
while IFS= read -r d; do
  [ -n "$d" ] || continue
  case "$d" in *.pma|*.db|*.db-journal|*.pb|"Local State"|"Code Cache"|Cache) continue;; esac
  # `< /dev/null` is load-bearing: adb shell reads stdin, and inside a while-read
  # loop it swallows the rest of the listing, so the loop sees only one entry.
  kids=$("$ADB" shell run-as "$PKG" ls "'$PROFILE_DIR/$d'" </dev/null 2>/dev/null | tr -d '\r')
  ver=$(printf '%s\n' "$kids" | grep -E '^[0-9]+(\.[0-9]+)*$' | tail -1)
  if [ -n "$ver" ]; then
    say "  $d -> $ver"
    installed=$((installed+1))
  fi
done <<< "$listing"
[ "$installed" -eq 0 ] && say "  (no versioned payload dirs found -- nothing has downloaded yet)"
say "  $installed component(s) with an installed payload"

# ------------------------------------------------------- 3. network time query
hdr "network time query (FEATURE-067 Part B: expected ABSENT by default)"
NT=$(printf '%s' "$STATE" | python3 -c '
import json,sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(2)
nt = d.get("network_time") or {}
m = nt.get("network_time_mapping")
print("PRESENT" if m else "absent")
print(json.dumps(m) if m else "")
' 2>/dev/null)
NT_STATE=$(printf '%s\n' "$NT" | head -1)
if [ "$NT_STATE" = "PRESENT" ]; then
  say "  network_time.network_time_mapping: PRESENT -- the query RAN"
  say "  If this build did not call setNetworkTimeQueriesEnabled(true), that is a FAIL."
  [ "$RAW" = "1" ] && printf '%s\n' "$NT" | tail -n +2 | sed 's/^/    /'
else
  say "  network_time.network_time_mapping: absent -- no query recorded"
  say "  NOTE: absence alone is weak evidence. To prove the switch works, re-run"
  say "        against a build that calls setNetworkTimeQueriesEnabled(true) on an"
  say "        equally fresh profile and confirm it flips to PRESENT."
fi

# ------------------------------------------------- 4. the audited-absent set
hdr "audited as NOT sent (each must stay absent)"
printf '%s' "$STATE" | python3 -c '
import json,sys
d = json.load(sys.stdin)
def has(path):
    cur = d
    for p in path.split("."):
        if not isinstance(cur, dict) or p not in cur: return False
        cur = cur[p]
    return bool(cur)
checks = [
    ("variations seed",        "variations_compressed_seed"),
    ("variations fetch time",  "variations_last_fetch_time"),
    ("metrics client_id",      "user_experience_metrics.client_id"),
    ("metrics reporting",      "user_experience_metrics.reporting_enabled"),
    ("ukm logs",               "ukm.persisted_logs"),
]
bad = 0
for label, key in checks:
    present = has(key)
    print("  %-22s %s" % (label, "PRESENT <- unexpected" if present else "absent"))
    bad += present
sys.exit(1 if bad else 0)
' 2>/dev/null
ABSENT_RC=$?

# ----------------------------------------------------------------- verdict
hdr "verdict"
rc=0
if [ "$fail" -gt 0 ]; then
  say "  FAIL  $fail kept component(s) missing -- a security feed is not arriving."
  say "        Registrations are staggered over ~35 minutes; if the app was only just"
  say "        launched, wait and re-run before believing this."
  rc=1
fi
[ "$leak" -gt 0 ] && { say "  FAIL  $leak dropped component(s) still registering."; rc=1; }
[ "$unknown" -gt 0 ] && say "  REVIEW  $unknown component(s) in neither list -- decide keep or drop."
[ "$ABSENT_RC" != "0" ] && { say "  FAIL  something in the audited-absent set is present."; rc=1; }
[ "$rc" = "0" ] && [ "$unknown" -eq 0 ] && say "  PASS  kept components present, dropped absent, nothing unexpected."
exit $rc
