#!/usr/bin/env python3
"""Keep the licence header on Cefrium's own sources.

    cef/cefrium_sdk/license-headers.py            # rewrite the headers
    cef/cefrium_sdk/license-headers.py --check    # assert only, exit 1 if wrong

Cefrium's own code (the SDK, Compose, UnifiedPush, the relay, the Gradle
plugin and the instrumentation tests) is dual-licensed, LGPL-3.0-or-later or
the commercial licence in LICENSE-CEFRIUM.md. Files had been started from
CEF's BSD header, some naming the CEF authors, and many had none.

libcef/ and patch/ are out of scope: CEF-derived files keep CEF's header.
"""
import os, re, sys

CEF = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOTS = ["cefrium_sdk/java", "cefrium_sdk/gradle-plugin", "cefrium_compose",
         "cefrium_unifiedpush", "cefrium_relay", "cefrium_instrumentation_test"]
EXTS = (".java", ".kt", ".groovy", ".go")
# REUSE form, matching the dual-licence annotation in REUSE.toml.
HEADER = ("// SPDX-FileCopyrightText: 2026 Juan Manuel M\u00e9ndez Rey "
          "<cefrium@proton.me>\n"
          "// SPDX-License-Identifier: LGPL-3.0-or-later OR "
          "LicenseRef-Cefrium-Commercial\n")
# A leading run of // lines that carries a copyright is the old header.
OLD = re.compile(r"\A(?://[^\n]*\n)*?//[^\n]*(?:Copyright|SPDX-FileCopyrightText)[^\n]*\n"
                 r"(?://[^\n]*\n)*")


def sources():
    for root in ROOTS:
        for d, dirs, names in os.walk(os.path.join(CEF, root)):
            dirs[:] = [x for x in dirs if x not in ("build", ".gradle")]
            for n in sorted(names):
                path = os.path.join(d, n)
                # The SDK also checks in generated Chromium Java (GEN_JNI, R
                # classes under org/ and gen/); that is not ours to license.
                if n.endswith(".go") or (n.endswith(EXTS) and "/com/cefrium/" in path):
                    yield path


def fixed(text):
    if text.startswith(HEADER):
        return text
    m = OLD.match(text)
    body = text[m.end():] if m else text
    return HEADER + "\n" + body.lstrip("\n")


def main():
    check = "--check" in sys.argv
    bad = 0
    for path in sources():
        text = open(path, encoding="utf-8").read()
        want = fixed(text)
        if want == text:
            continue
        rel = os.path.relpath(path, CEF)
        if check:
            print("  missing or wrong header: " + rel)
            bad += 1
        else:
            open(path, "w", encoding="utf-8").write(want)
            print("  fixed: " + rel)
    if bad:
        sys.exit("%d file(s) without the Cefrium licence header; run "
                 "cef/cefrium_sdk/license-headers.py" % bad)
    print("licence headers: OK")


if __name__ == "__main__":
    main()
