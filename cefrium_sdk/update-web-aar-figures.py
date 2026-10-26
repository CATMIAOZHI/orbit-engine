#!/usr/bin/env python3
"""Derive the AAR figures on cefrium.com/guides/adopting-cefrium/ and rewrite them.

    cef/cefrium_sdk/update-web-aar-figures.py <version> <aar> [--check]

The page states what we counted in the published artifact: class totals, how
many are class-file 69 and whose they are, and the libcef.so sizes. Those were
edited by hand at each release, so the page named the previous version until a
verify run caught it. They are measured here from the AAR that was published.
"""
import collections, io, os, re, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.environ.get("CEFRIUM_WEB",
                     os.path.join(HERE, "..", "..", "..", "..", "cefrium-web"))
PAGE = os.path.join(WEB, "guides", "adopting-cefrium", "index.html")


def measure(aar_path):
    aar = zipfile.ZipFile(aar_path)
    c = collections.Counter()
    for n in aar.namelist():
        if not n.endswith(".jar"):
            continue
        jar = zipfile.ZipFile(io.BytesIO(aar.read(n)))
        for e in jar.namelist():
            if not e.endswith(".class"):
                continue
            head = jar.read(e)[:8]
            c["total"] += 1
            if (head[6] << 8 | head[7]) == 69:
                c["j25"] += 1
                if e.startswith("org/chromium/"):
                    c["j25_chromium"] += 1
                elif e.startswith("com/cefrium/"):
                    c["j25_cefrium"] += 1
    for abi in ("arm64-v8a", "x86_64"):
        i = aar.getinfo("jni/%s/libcef.so" % abi)
        c[abi + "_raw"] = i.file_size
        c[abi + "_zip"] = i.compress_size
    c["aar"] = os.path.getsize(aar_path)
    return c


def mb(n):
    return "%d MB" % round(n / 1e6)


def thousands(n):
    return "{:,}".format(n)


def main():
    args = [a for a in sys.argv[1:] if a != "--check"]
    check = "--check" in sys.argv
    if len(args) != 2:
        sys.exit(__doc__)
    version, aar = args
    m = measure(aar)
    # (pattern, replacement, label). Each anchor is the prose around a figure,
    # so a reworded sentence fails loudly instead of being skipped.
    subs = [
        (r"(We counted it in the published )[0-9.]+( artifact)",
         r"\g<1>%s\2" % version, "class count: version"),
        (r"(<li>)[0-9,]+( classes in total,</li>)",
         r"\g<1>%s\2" % thousands(m["total"]), "classes in total"),
        (r"(<li>)[0-9,]+( of them are class-file major 69)",
         r"\g<1>%s\2" % thousands(m["j25"]), "class-file 69"),
        (r"(<li>)[0-9,]+( of those belong to Chromium itself)",
         r"\g<1>%s\2" % thousands(m["j25_chromium"]), "Chromium-owned"),
        (r"(Your build has to read those )[0-9,]+( classes)",
         r"\g<1>%s\2" % thousands(m["j25"]), "classes to read"),
        (r"(Cefrium owns only )[0-9,]+( of)",
         r"\g<1>%s\2" % thousands(m["j25_cefrium"]), "Cefrium-owned"),
        (r"(measured on the\s+published )[0-9.]+( artifact)",
         r"\g<1>%s\2" % version, "sizes: version"),
        (r"(<code>libcef.so</code>, uncompressed</td><td>)[0-9]+ MB(</td><td>)[0-9]+ MB",
         r"\g<1>%s\g<2>%s" % (mb(m["arm64-v8a_raw"]), mb(m["x86_64_raw"])),
         "libcef uncompressed"),
        (r"(Compressed inside the AAR</td><td>)[0-9]+ MB(</td><td>)[0-9]+ MB",
         r"\g<1>%s\g<2>%s" % (mb(m["arm64-v8a_zip"]), mb(m["x86_64_zip"])),
         "libcef compressed"),
        (r"(holds both architectures and measures )[0-9]+ MB",
         r"\g<1>%s" % mb(m["aar"]), "AAR size"),
        (r"(the real download is about )[0-9]+ MB",
         r"\g<1>%s" % mb(m["arm64-v8a_zip"]), "download per phone"),
        (r"(About )[0-9]+ MB( per architecture)",
         r"\g<1>%s\2" % mb(m["arm64-v8a_zip"]), "table: per architecture"),
    ]
    text = open(PAGE, encoding="utf-8").read()
    out, bad = text, 0
    for pat, rep, label in subs:
        new, n = re.subn(pat, rep, out)
        if n == 0:
            print("  FAIL  %s: anchor not found" % label)
            bad += 1
        elif new != out:
            print("  %s  %s" % ("STALE" if check else "fixed", label))
            if check:
                bad += 1
        else:
            print("  OK    %s" % label)
        out = new
    if bad:
        sys.exit("adopting-cefrium figures: %d problem(s)" % bad)
    if not check and out != text:
        open(PAGE, "w", encoding="utf-8").write(out)
    print("adopting-cefrium figures match the %s AAR" % version)


if __name__ == "__main__":
    main()
