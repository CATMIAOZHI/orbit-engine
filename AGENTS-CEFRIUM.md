# Agent instructions for Cefrium (CEF on Android)

For coding agents working in this repository. Upstream CEF ships its own
agent docs in `tools/claude/` (`setup_claude.py` installs
`chromium/src/CLAUDE.md` from `CLAUDE.md.in`). Use them for CEF internals.
This file adds only what is different on Android. Do not edit
`tools/claude/` or `CLAUDE.md.in`: upstream owns them and every engine bump
merges them.

Building an app on the SDK instead? Read https://cefrium.com/agents.md.

## Where things are

- `libcef/browser/android/`: the Android platform code of libcef.
- `patch/patches/android/*.patch`: Chromium patches for Android, listed in
  `patch/patch.cfg` under the `target_os=="android"` condition.
- `cefrium_sdk/`: the Java SDK (`java/src/com/cefrium/`), the Gradle plugin
  and every build, test and release script.
- `cefrium_compose/`: the Compose module.
- `SCRIPTS-CEFRIUM.md`: one command per task. Use the script; do not hand-chain
  its steps.

## Rules

- CEF API first. Look in `include/*.h` before reaching for `content::`,
  `ui::` or `cc::`. Chromium internals only for Android infrastructure CEF
  does not cover (ContentViewRenderView, WindowAndroid).
- Upstream first. Platform code goes in `libcef/browser/` following the
  delegate/runner pattern the Linux/Mac/Windows ports use, so it can go
  upstream as a plain `git format-patch`. SDK-only code goes in
  `cefrium_sdk/` or `cefrium_compose/`.
- Patches: apply with `cefrium_sdk/apply-patches.py`, never `patcher.py`
  directly (the `target_os=="android"` key is not a legal environment
  variable name, and the Android patches are skipped without an error).
  Patch files are `-p0` with no `a/`/`b/` prefix
  (`cefrium_sdk/check-patch-format.sh`). A clean reapply reports 0 failed.
- After applying patches, touch every patched file: `git apply` keeps old
  timestamps and ninja skips them.
- Never `git checkout` or `git reset` in `chromium/src`: it removes the
  applied patches.
- Build with Chromium's own `third_party/ninja/ninja` and `depot_tools/gn`,
  not the system binaries.

## Proof

- A change is done when it works on a real device with a minimal sample in
  `cefrium-sample/`, not when it compiles. `cefrium_sdk/dev-iterate.sh`
  builds, installs and reads the sample's verdict file.
- A new test counts only after it failed without the fix.
- Surface-backed and off-screen browsers fail differently: test in the mode
  the bug is in.
- "Did it crash?": `cefrium_sdk/why-died.sh` (reads `exit-info`), not
  `pidof`.

## Writing

- ASCII only in committed files. Proper names are the only exception.
- Commit messages: what changed, and why only if it is not obvious.
- Public javadoc describes behaviour, not our internal steps or history.
