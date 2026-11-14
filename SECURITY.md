# Security Policy

Scope: CEFRIUM, which is CEF ported to native Android, and its SDK
(`com.cefrium:*`). Not upstream CEF, and not Chromium itself.

Cefrium ships a whole browser engine inside your app. That is a large attack
surface and the honest thing to do is say exactly what is defended, what is
not, and how a fix reaches you.

## Reporting a vulnerability

Email **cefrium@proton.me** with `SECURITY:` at the start of the subject. Do
not open a public issue.

Include what you did, what happened, and the SDK version. A reproducer helps
more than anything else; `adb logcat`, a crash dump or a test page is enough to
start. If you want to encrypt it, use the release-signing key, fingerprint
`2A84 0ED0 0DE6 69F4 219B 8B33 02A7 9E3B 8215 1695`, which also signs every
`SHA256SUMS`.

What to expect: an acknowledgement, a fix or a written reason why not, and your
name in the changelog entry unless you ask otherwise. Best effort, with no
guaranteed response time. A contractual response time is available by
arrangement, see [cefrium.com/commercial](https://cefrium.com/commercial/).
Coordinated disclosure, please: give the fix time to reach a release before
publishing.

**A bug in Chromium itself** goes to Chromium, at
https://issues.chromium.org/issues/new, where it may be eligible for their
reward programme. Cefrium carries the fix when it lands upstream; it does not
write independent patches for upstream engine bugs. Tell us anyway if it
affects Android embedders specifically, because that affects which engine bump
we take and when.

## What the engine does to contain a compromised renderer

Measured on a device, not inferred from the manifest. `0.10.2` is the first
release where all of this holds.

- **Renderers run in a separate, isolated process.** Each one is an Android
  `isolatedProcess` service with its own kernel UID in the isolated range and
  the SELinux `isolated_app` domain. The platform grants that, so no SDK or app
  setting can take it away.
- **Renderers install Chromium's seccomp-BPF policy.** Before 0.10.2 every
  Cefrium entry point passed `--no-sandbox`, which stopped `renderer_main` from
  calling `EnableSandbox()`. Children kept only the Android zygote's baseline
  filter. From 0.10.2 a renderer reads `NoNewPrivs 1` with 2 seccomp filters in
  `/proc/<pid>/status`.
- **The GPU runs in the browser process by default**, and the embedder can
  move it to its own process with `Cefrium.setGpuProcessEnabled(true)`. In
  process, GL command buffers and the vendor graphics driver share the process
  that holds your app's code and data. Out of process, a driver bug reached
  from a compromised renderer lands in `:privileged_process0` instead; that
  service shares your app's UID, so it is separated from the renderer, not from
  your files. It costs about 21% more memory (591 MB against 715 MB total PSS
  on a kiosk tablet showing one page), which is why it is not the default.
- **Page JavaScript reaches your code only from origins you allow.**
  `window.cefriumQuery` requires an origin policy at registration; a call from
  any other origin fails on the page with `QUERY_ERROR_ORIGIN_DENIED` and
  never invokes your handler. Choosing every origin is a named option that
  logs a warning.

What is NOT reached, and what a measurement can mislead on:

- **Renderers fork from a hardened app zygote**, as the system WebView's do
  (`android:useAppZygote` on the sandboxed service), from the release after
  0.10.2. Measured side by side on the same tablet: a Cefrium renderer and a
  system WebView renderer both read `NoNewPrivs 1` with 4 seccomp filters.
  Parity, not an approximation of it. This was held back once because input
  stopped reaching a windowed renderer with the zygote on; the cause was a
  fixed 2 s delay in the SDK before a browser joined its window, not the
  zygote, and it is fixed in the same release.
- **`Seccomp:` in `/proc/<pid>/status` proves nothing on Android**, for us or
  for anyone else: every app process inherits the zygote's filter. The filter
  count and `NoNewPrivs` are the signal.

Two engine behaviours that are not sandbox questions but are privacy ones, both
switchable from the SDK: the component updater and network time queries. See
`Cefrium.setComponentUpdatesEnabled` and
`Cefrium.setNetworkTimeQueriesEnabled`, and
[cefrium.com/guides/network-behaviour](https://cefrium.com/guides/network-behaviour/).

## How security fixes reach you

Current engine: **Chromium 156.0.8078.88 / CEF branch 8078**.

Cefrium inherits Chromium's vulnerabilities and Chromium's fixes. There are no
independent engine patches, and there are no backports: a fix arrives as an
engine bump in the next SDK release, and you get it by moving to that release.

The engine target follows Debian's Chromium package, so the port stays on a
version whose CVE coverage is public and checkable. A vulnerability known to be
exploited in the wild is treated differently from the rest: 0.6.1 shipped a
same-branch parity bump to 149.0.7827.102 for the V8 zero-day CVE-2026-11645
rather than waiting for the next milestone. That is the intent for any future
in-the-wild case, not a promise with a clock on it.

Every release states the exact engine it carries, in
[CHANGELOG-CEFRIUM.md](CHANGELOG-CEFRIUM.md), in the published POM as
`cefrium.chromium.version`, and in an SBOM attached to the release.

## Supported versions

| Version | Supported |
|---------|-----------|
| The latest release | Yes, best effort |
| Anything older | No |

The Codeberg Maven registry serves the latest release and the one before it;
Maven Central keeps the versions up to 0.17.1. An old coordinate that still
resolves is not supported: it carries the engine it shipped with. Check the
engine, not the version number.

## Verifying what you downloaded

Every release carries `SHA256SUMS` with a detached GPG signature, an SPDX SBOM
and a NOTICE of every bundled third-party licence, attached to its
[Codeberg release](https://codeberg.org/cefrium/cef-android/releases).

```bash
gpg --recv-keys 02A79E3B82151695
gpg --verify SHA256SUMS-<version>.asc SHA256SUMS-<version>
sha256sum -c SHA256SUMS-<version>
```

The bytes the Codeberg registry serves are the bytes in that signed file. Official
builds come from the channels listed in
[Trademark and Forks](https://cefrium.com/policy/); a build from anywhere else
is a derivative, whatever it is called.

## Maturity

Cefrium is pre-1.0 and the public API can still change, which is a
compatibility statement rather than a security one. The engine is a standard
Chromium build with the Android port's patches, validated on real devices on
every release by a 261-test on-device suite that a release cannot skip. Use it
with the same care you would give any browser engine you embed: keep it
updated, because that is the only way engine fixes reach your users. The SDK
does not check for newer versions itself, at build time or at run time: that
would be a connection you did not ask for. Renovate and Dependabot can watch
`com.cefrium:*` once the Codeberg registry is configured as a Maven registry,
and https://cefrium.com/releases lists the current version.
