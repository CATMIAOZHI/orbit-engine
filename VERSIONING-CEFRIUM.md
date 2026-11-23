# Versioning

Scope: this document is for CEFRIUM (CEF ported to native Android) and its SDK, not upstream CEF.

Cefrium follows a semantic-versioning convention during pre-1.0 validation:

- **Minor** (`0.X.0` -> `0.(X+1).0`): a release that adds public API.
- **Patch** (`0.X.Y` -> `0.X.(Y+1)`): fixes, packaging, and documentation only -- no new
  public API.
- **Engine bumps**: when the bundled CEF branch or Chromium version changes, it is
  recorded in the [parity table](https://cefrium.com/releases/) regardless of the
  version step.

The convention applies from the release **after** 0.3.1.

Note: 0.3.0 -> 0.3.1 added public API (`setSslErrorHandler`, `setHttpAuthHandler`,
`CefriumInitProvider`), which by this convention is a minor change. It shipped as a
patch and is **not** re-versioned -- 0.3.1 is published and immutable.

Until 1.0 the public API may still change between releases; 1.0 marks the first
stability commitment.

## The User-Agent carries no SDK version

The User-Agent reads `Chrome/<engine version> Cefrium/1.0`, followed by
`Mobile` on a phone (as Chrome: not on a tablet). Two tokens, two meanings:

- `Chrome/<engine version>` is the bundled engine, a build constant. It moves on
  an engine bump and on nothing else. It is never the host app's
  `android:versionName`.
- `Cefrium/1.0` identifies the embedder and stays at 1.0 across releases, so a
  server-side allowlist built on it survives an SDK update.

Read the SDK version with `Cefrium.getVersion()` and the engine version with
`Cefrium.getChromiumVersion()`.

## What 1.0 freezes

The surface is already decided, so you can build against it before the version
number moves.

| Names | Commitment |
|---|---|
| In `com.cefrium`, every type whose name starts with `Cefrium`, and every listener or callback nested inside them | Frozen at 1.0 |
| `com.cefrium.compose`, the whole package | Frozen at 1.0 |
| The Gradle plugin id `com.cefrium` (it takes no configuration block) | Frozen at 1.0 |
| In `com.cefrium`, types whose name starts with `Cef` but not `Cefrium`, and `CefriumMediaSession` | Not API. Either name can change in any release. |
| `com.cefrium.payments`, `com.cefrium.webauthn` | Not API. The engine instantiates these. |
| `org.chromium.*`, `org.jni_zero.*`, `gen.*` | Never API. Upstream renames them between branches. |

Short rule: a type name that starts with `Cefrium` is API, with the single
exception of `CefriumMediaSession`.

No public member of the frozen surface takes or returns an `org.chromium.*`
type, in either the Java SDK or the Compose API. Your code compiles against
Android and Java types only. The freeze keeps it that way, which is what
protects a consumer from an upstream rename.

## From 1.0

- **Major** (`X.0.0`): the only release that may remove or change frozen
  API, and only API whose deprecation period has passed.
- **Minor** (`1.X.0`): adds API, may deprecate API, and may move to a new
  engine branch. It never removes frozen API or changes what it does.
- **Patch** (`1.X.Y`): fixes, security fixes and engine updates within the
  same engine branch. No API change.

**Deprecation.** Deprecated API is marked `@Deprecated`, names its
replacement in its javadoc and in the changelog, keeps working for at least
12 months, and is removed only in a major release. One exception: when
Chromium removes the capability underneath (as it did with Web SQL), the API
stops working with the engine release that removes it. The changelog and the
javadoc say so as soon as the removal is known.

**Experimental.** API whose javadoc says Experimental is not covered: it may
change or go in a minor release.

**Engine.** A release follows each Chrome stable that Debian ships. A
Chromium vulnerability exploited in the wild gets a security release as soon
as possible. No fixed delay is promised here; guaranteed response times and
long-term branches are terms of the support plans.

The full terms of the 1.0 commitment are on
[cefrium.com/guides/adopting-cefrium/](https://cefrium.com/guides/adopting-cefrium/).
