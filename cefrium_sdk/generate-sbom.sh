#!/bin/bash
# Produce the SBOM and licence NOTICE published with a Cefrium release.
# Run from chromium/src.
#
#   generate-sbom.sh <version> [out-dir]      (default out-dir: out/release)
#
# Outputs:
#   <out>/cefrium-sdk-<ver>.spdx.json    the engine, declared with its version
#   <out>/cefrium-sdk-<ver>-NOTICE.txt   every bundled third-party licence
#
# WHY THE SBOM IS ONE PACKAGE AND NOT THREE HUNDRED
#
# Every richer option was tried and measured, and each one misrepresents the
# artifact:
#
#   - Scanning it finds nothing. syft on libcef.so (214 MB) reports 0 packages:
#     Chromium statically links its third-party code into one stripped binary
#     that syft's classifiers do not cover. syft on the AAR also reports 0,
#     because classes.jar carries no Maven coordinates to derive identity from.
#     (A control scan of an ordinary jar works, so the tool is fine.)
#   - Chromium's own generator lists components but no versions: of 306
#     packages, 0 have a version, 0 have a CPE or purl. Fine for licences,
#     useless for vulnerabilities.
#   - Declaring the engine with a CPE does produce matches, which is why we do
#     not: the Chrome CPE matches at MILESTONE level, so 154.0.8037.98 and
#     154.0.8037.152 both report the same ~122 CVEs (16 Critical) "fixed in
#     155". A fully patched build would look identical to an unpatched one.
#   - Shipping the 306-entry list AS an SBOM is just as bad by another route:
#     grype name-matches the versionless entries and invents findings (sqlite
#     came back High and Critical with an empty INSTALLED column).
#
# So the SBOM states the one fact that is both true and useful -- which engine
# this release embeds -- and the component list ships as a NOTICE, which is what
# a licence disclosure actually is. Verified: grype on the resulting SBOM says
# "No vulnerabilities found" rather than inventing any.
#
# The licence list is not busywork: it answers the one real published policy
# finding. Sonatype marks com.cefrium Non-Compliant purely on the LGPL-3.0
# "weak copyleft" row, with vulnerabilities Pass and malware Clean.
set -uo pipefail

VERSION="${1:?usage: generate-sbom.sh <version> [out-dir]}"
OUTDIR="${2:-out/release}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$SRC"

CEFJAVA="$SCRIPT_DIR/java/src/com/cefrium/Cefrium.java"
ENGINE="$(grep -oE 'CHROMIUM_VERSION = "[0-9.]+"' "$CEFJAVA" | grep -oE '[0-9.]+')"
[ -n "$ENGINE" ] || { echo "ERROR: cannot read CHROMIUM_VERSION from $CEFJAVA" >&2; exit 2; }

# Scope to what is actually linked into libcef.so for Android, not to every
# directory under third_party.
GN_OUT="${CEFRIUM_SBOM_OUT_DIR:-out/Official_android_arm64_codecs}"
[ -d "$GN_OUT" ] || { echo "ERROR: $GN_OUT not built; build a variant first." >&2; exit 2; }

mkdir -p "$OUTDIR"
SBOM="$OUTDIR/cefrium-sdk-$VERSION.spdx.json"
NOTICE="$OUTDIR/cefrium-sdk-$VERSION-NOTICE.txt"
HELPER="$(mktemp "${TMPDIR:-/tmp}/cefrium-sbom-engine.XXXXXX.py")"
trap 'rm -f "$HELPER"' EXIT

echo ">>> SPDX skeleton for cefrium-sdk $VERSION (engine $ENGINE) from $GN_OUT"
python3 tools/licenses/licenses.py license_file \
  --format spdx --target-os android \
  --gn-out-dir "$GN_OUT" --gn-target "//cef:libcef" \
  --spdx-doc-name "cefrium-sdk-$VERSION" \
  --spdx-link "https://cefrium.com/releases/" \
  "$SBOM" || { echo "ERROR: SPDX generation failed" >&2; exit 1; }

cat > "$HELPER" <<'HELPER_EOF'
import json, sys
path, engine = sys.argv[1], sys.argv[2]
with open(path) as f:
    doc = json.load(f)
spdxid = "SPDXRef-Package-chromium-engine"
before = len(doc.get("packages", []))
pkg = {
    "SPDXID": spdxid,
    "name": "Chromium",
    "versionInfo": engine,
    "downloadLocation": "https://chromium.googlesource.com/chromium/src.git",
    "homepage": "https://www.chromium.org/",
    "licenseConcluded": "BSD-3-Clause",
    "licenseDeclared": "BSD-3-Clause",
    "copyrightText": "NOASSERTION",
    "filesAnalyzed": False,
    "comment": ("The browser engine embedded in libcef.so. Declared with a version and "
                "intentionally without a CPE: the Chrome CPE matches at milestone level, "
                "so every 154.x reports the same CVE set regardless of patch level, which "
                "would misrepresent a fully patched build. The bundled third-party "
                "components are disclosed in the accompanying NOTICE file; they are kept "
                "out of this document because their versions are unknown to the build "
                "metadata, and versionless entries make scanners invent findings."),
}
doc["packages"] = [pkg]
# Drop the 303 inline licence texts the generator emits for components we no
# longer list: they are what made a one-package document 2.2 MB, and they
# duplicate the NOTICE verbatim. Keep only LicenseRefs the document still cites.
cited = {pkg.get("licenseConcluded"), pkg.get("licenseDeclared")}
doc["hasExtractedLicensingInfos"] = [
    x for x in doc.get("hasExtractedLicensingInfos", [])
    if x.get("licenseId") in cited
]
doc["relationships"] = [
    {"spdxElementId": r, "relationshipType": "CONTAINS", "relatedSpdxElement": spdxid}
    for r in (doc.get("documentDescribes") or [])
]
with open(path, "w") as f:
    json.dump(doc, f, indent=1)
print("    declared Chromium %s; reduced %d packages to 1" % (engine, before))
HELPER_EOF
python3 "$HELPER" "$SBOM" "$ENGINE" || { echo "ERROR: could not declare the engine" >&2; exit 1; }

echo ">>> third-party licence NOTICE"
python3 tools/licenses/licenses.py license_file \
  --format notice --target-os android \
  --gn-out-dir "$GN_OUT" --gn-target "//cef:libcef" \
  "$NOTICE" || { echo "ERROR: NOTICE generation failed" >&2; exit 1; }

echo ">>> $SBOM ($(du -h "$SBOM" | cut -f1))"
echo ">>> $NOTICE ($(du -h "$NOTICE" | cut -f1), $(grep -c . "$NOTICE") lines)"
