# Contributing to CEF Android Port

This document explains how changes are tracked, reviewed, and
contributed upstream to the Chromium Embedded Framework.

## Change tracking

Changes in this project fall into two categories:

### 1. CEF changes (`src/cef/`)

Direct commits on the `android-port` branch. This branch forks from
upstream CEF branch 7778 (Chromium 148).

**Examples:** `menu_runner_android.cc`, `browser_platform_delegate_native_android.cc`,
`cefrium_sdk/` Java API, `cefrium_compose/` Compose wrapper.

**Upstream contribution:** via `git format-patch`:
```bash
cd chromium/src/cef
git format-patch origin/7778..android-port -- \
  ':!README.md' ':!assets' ':!CONTRIBUTING.md'
```

### 2. Chromium changes (`src/` excluding `src/cef/`)

Tracked as patch files in `src/cef/patch/patches/android/`.
Applied automatically by `cef/tools/patcher.py`.

**Examples:** GN build fixes, Android-specific Chromium adaptations.

These patches follow CEF's existing patch infrastructure and are
candidates for upstream CEF (not upstream Chromium — Chromium won't
accept CEF-specific changes).

## Upstream-first philosophy

Every feature must ask: **"Can this go upstream to CEF?"**

- **If yes** — implement in `cef/libcef/browser/` following CEF's
  architecture (platform delegates, handlers, runners). Use the same
  patterns as Linux/Mac/Windows implementations.

- **If no** (SDK-specific) — implement in `cef/cefrium_sdk/` or
  `cef/cefrium_compose/`.

## Code style

Match CEF's existing style, which follows Chromium/Google C++ style:
- 2-space indentation
- `CamelCase` for classes, `snake_case` for methods/variables
- `// Comment` style (not `/* */`)
- Include guards: `CEF_LIBCEF_BROWSER_NATIVE_MENU_RUNNER_ANDROID_H_`
- License header on all new files (BSD for CEF)

Java/Kotlin code in `cefrium_sdk/` follows Android conventions.

## Commit messages

- English, concise (1-2 lines describing WHAT changed)
- No emojis, no co-author tags
- Rationale and analysis go in FEATURE .md documents (not committed
  to the CEF repo)

## Review process

All changes will undergo thorough review before upstream submission:

1. **Internal review** — maintainer studies each patch line by line,
   understands the motivation, and can defend it independently
2. **Testing** — validated on arm64 real device and x64 emulator
3. **Patch cleanup** — one logical change per patch, descriptive
   subject, body explaining motivation and trade-offs
4. **Upstream submission** — pull request to
   `github.com/chromiumembedded/cef` when the patch series is ready

## Contact

- Issues: https://codeberg.org/cefrium/cef-android/issues
- Email: cefrium@proton.me
